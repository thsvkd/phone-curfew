package com.thsvkd.curfew

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thsvkd.curfew.collect.DeviceState
import com.thsvkd.curfew.collect.EventType
import com.thsvkd.curfew.collect.UsageCollector
import com.thsvkd.curfew.collect.UsageEventLite
import com.thsvkd.curfew.data.CoverageGap
import com.thsvkd.curfew.data.CurfewDao
import com.thsvkd.curfew.data.CurfewDb
import com.thsvkd.curfew.score.DayResult
import com.thsvkd.curfew.score.DayStatus
import com.thsvkd.curfew.score.Window
import com.thsvkd.curfew.score.score
import com.thsvkd.curfew.score.streakOf
import com.thsvkd.curfew.score.usedSecondsIn
import com.thsvkd.curfew.score.windowFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

/** 수집 주기. WorkManager가 허용하는 최소 주기와 같다. */
private const val WINDOW_MS = 15 * 60 * 1000L

private const val CURFEW_START = 2 * 60      // 02:00
private const val CURFEW_END = 7 * 60        // 07:00
private const val TOLERANCE_SECONDS = 5 * 60 // 5분

/** 2026년 9월 [day]일 [hour]시 [minute]분(KST)을 밀리초로. */
private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    LocalDate.of(2026, 9, day).atStartOfDay(ZONE)
        .plusHours(hour.toLong()).plusMinutes(minute.toLong())
        .toInstant().toEpochMilli()

/** 실제로 핸드폰을 쓴 구간. 이 목록이 정답지다. */
private data class Use(val startMs: Long, val endMs: Long)

/**
 * 밤을 기다리지 않고 일주일치를 재현하는 통합 검증.
 *
 * 정답지가 되는 사용 구간을 먼저 정하고, 거기서 시스템이 내보낼 이벤트를 만든 다음, 실제 워커와
 * 같은 15분 창으로 쪼개어 수집을 반복한다. 저장은 진짜 Room 데이터베이스에 하고, 마지막에 판정을
 * 돌려 정답지와 맞춰 본다.
 *
 * 단위 테스트가 덮지 못하던 세 가지가 여기서 처음 검증된다. DAO의 삽입 후 누적 SQL, 수집 창
 * 경계에 걸친 사용이 이중으로 세이지 않는지, 그리고 수집부터 판정까지의 연쇄가 실제로 맞물리는지.
 */
@RunWith(AndroidJUnit4::class)
class OvernightE2ETest {

    private lateinit var db: CurfewDb
    private lateinit var dao: CurfewDao

    @Before
    fun openDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CurfewDb::class.java).build()
        dao = db.dao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    /**
     * 9월 2일부터 8일까지 일곱 밤. 성공과 실패, 허용 오차 양쪽 경계, 수집 공백을 모두 포함한다.
     * 커퓨는 02:00부터 07:00까지이고 허용 오차는 5분이다.
     */
    private val uses = listOf(
        // 9/2 커퓨 전에 잠들었다. 성공.
        Use(at(1, 23, 0), at(2, 1, 50)),
        Use(at(2, 8, 0), at(2, 9, 0)),
        // 9/3 커퓨 안 사용은 없지만 이날은 수집 공백이 있어 판정하지 않는다.
        Use(at(2, 23, 30), at(3, 1, 0)),
        // 9/4 커퓨 안에서 80분. 실패.
        Use(at(3, 22, 0), at(4, 3, 20)),
        // 9/5 커퓨 안에서 3분. 허용 오차 안이라 성공.
        Use(at(4, 23, 0), at(5, 2, 3)),
        // 9/6 커퓨 안에서 6분. 허용 오차를 넘겨 실패.
        Use(at(5, 23, 0), at(6, 2, 6)),
        // 9/7 성공.
        Use(at(6, 23, 0), at(7, 1, 30)),
        // 9/8 성공.
        Use(at(7, 21, 0), at(7, 23, 45)),
        Use(at(8, 9, 0), at(8, 10, 0)),
    )

    /** 9/3 커퓨 창과 겹치는 수집 공백. 절전으로 워커가 멈췄던 상황을 흉내 낸다. */
    private val gap = CoverageGap(at(3, 3, 0), at(3, 4, 0))

    private val expected = mapOf(
        2 to DayStatus.SUCCESS,
        3 to DayStatus.NO_DATA,
        4 to DayStatus.FAIL,
        5 to DayStatus.SUCCESS,
        6 to DayStatus.FAIL,
        7 to DayStatus.SUCCESS,
        8 to DayStatus.SUCCESS,
    )

    @Test
    fun 일주일치_밤을_15분_주기로_수집해_판정까지_재현한다() = runTest {
        val now = at(8, 12, 0)
        simulateWeek(now)
        dao.addGap(gap)

        val buckets = dao.bucketsBetween(at(1, 0), now).first()
        val gaps = dao.gapsOverlapping(at(1, 0), now).first()

        // 1) 하루 총 사용이 정답지와 일치하는가. 창 경계에서 새거나 겹쳐 세면 여기서 어긋난다.
        for (day in 2..8) {
            val wholeDay = Window(at(day, 0), at(day + 1, 0))
            assertEquals(
                "9/$day 하루 총 사용",
                trueSeconds(wholeDay),
                usedSecondsIn(wholeDay, buckets).roundToInt().toLong(),
            )
        }

        // 2) 커퓨 창 안의 사용이 정답지와 일치하는가.
        for (day in 2..8) {
            val window = curfewWindow(day)
            assertEquals(
                "9/$day 커퓨 시간대 사용",
                trueSeconds(window),
                usedSecondsIn(window, buckets).roundToInt().toLong(),
            )
        }

        // 3) 판정이 기대와 일치하는가.
        val results = (2..8).map { day ->
            DayResult(
                LocalDate.of(2026, 9, day),
                score(curfewWindow(day), now, buckets, gaps, TOLERANCE_SECONDS),
            )
        }
        for ((index, day) in (2..8).withIndex()) {
            assertEquals("9/$day 판정", expected[day], results[index].status)
        }

        // 4) 화면에 큰 숫자로 나가는 두 값.
        assertEquals("이번 주 성공", 4, results.count { it.status == DayStatus.SUCCESS })
        assertEquals("연속 성공", 2, streakOf(results))
    }

    @Test
    fun 아직_끝나지_않은_창은_사용량과_무관하게_진행_중이다() = runTest {
        // 9/8 새벽 3시. 커퓨 창은 07:00에 닫히므로 아직 결론이 없다.
        val now = at(8, 3, 0)
        simulateWeek(now)

        val buckets = dao.bucketsBetween(at(1, 0), now).first()
        val status = score(curfewWindow(8), now, buckets, emptyList(), TOLERANCE_SECONDS)

        assertEquals(DayStatus.IN_PROGRESS, status)
    }

    @Test
    fun 한_칸에_10분을_넘겨_담아도_600초에서_멈춘다() = runTest {
        val bucket = at(4, 2, 0)
        dao.addUsage(bucket, 400)
        dao.addUsage(bucket, 400)

        val stored = dao.allBuckets().single { it.bucketStartMs == bucket }
        assertEquals(600, stored.usedSeconds)
    }

    /**
     * 워커가 하는 일을 그대로 반복한다. 15분 창으로 쪼개고, 창의 시작 상태는 기기에 물어본 값을
     * 쓴다. 실기기에서는 PowerManager와 KeyguardManager가 알려 주는 값이고, 여기서는 정답지에서
     * 같은 시각의 상태를 꺼내 쓴다.
     */
    private suspend fun simulateWeek(nowMs: Long) {
        val events = uses.flatMap {
            listOf(
                UsageEventLite(it.startMs, EventType.SCREEN_INTERACTIVE),
                UsageEventLite(it.startMs, EventType.KEYGUARD_HIDDEN),
                UsageEventLite(it.endMs, EventType.KEYGUARD_SHOWN),
                UsageEventLite(it.endMs, EventType.SCREEN_NON_INTERACTIVE),
            )
        }.sortedBy { it.timeStampMs }

        var cursor = at(1, 22, 0)
        while (cursor < nowMs) {
            val windowEnd = minOf(cursor + WINDOW_MS, nowMs)
            val segments = UsageCollector.collect(
                events = events.filter { it.timeStampMs in cursor..windowEnd },
                startState = deviceStateAt(cursor),
                windowStartMs = cursor,
                windowEndMs = windowEnd,
            )
            for ((bucketStartMs, seconds) in UsageCollector.toBuckets(segments)) {
                if (seconds > 0) dao.addUsage(bucketStartMs, seconds)
            }
            cursor = windowEnd
        }
    }

    private fun deviceStateAt(timeMs: Long): DeviceState {
        val using = uses.any { timeMs >= it.startMs && timeMs < it.endMs }
        return DeviceState(screenOn = using, unlocked = using)
    }

    private fun curfewWindow(day: Int): Window =
        windowFor(LocalDate.of(2026, 9, day), ZONE, CURFEW_START, CURFEW_END)!!

    /** 정답지에서 구한 [window] 안의 실제 사용 초. */
    private fun trueSeconds(window: Window): Long =
        uses.sumOf {
            maxOf(0L, minOf(window.endMs, it.endMs) - maxOf(window.startMs, it.startMs))
        } / 1000
}
