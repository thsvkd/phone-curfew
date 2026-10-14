package com.thsvkd.curfew

import com.thsvkd.curfew.collect.EventType
import com.thsvkd.curfew.collect.GAP_THRESHOLD_MS
import com.thsvkd.curfew.collect.RETENTION_LOOKBACK_MS
import com.thsvkd.curfew.collect.SLOT_MS
import com.thsvkd.curfew.collect.UsageEventLite
import com.thsvkd.curfew.collect.coverageGapForLateCollect
import com.thsvkd.curfew.collect.decideCoverageGaps
import com.thsvkd.curfew.collect.provesRetention
import com.thsvkd.curfew.data.CoverageGap
import com.thsvkd.curfew.data.UsageBucket
import com.thsvkd.curfew.score.DayStatus
import com.thsvkd.curfew.score.minuteOfDay
import com.thsvkd.curfew.score.score
import com.thsvkd.curfew.score.unrecordedGaps
import com.thsvkd.curfew.score.usedSecondsIn
import com.thsvkd.curfew.score.windowFor
import com.thsvkd.curfew.score.windowLengthMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
private val DAY: LocalDate = LocalDate.of(2026, 9, 7)

/** 그 날짜의 로컬 시각을 밀리초로. */
private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
    date.atStartOfDay(ZONE).plusHours(hour.toLong()).plusMinutes(minute.toLong())
        .toInstant().toEpochMilli()

/** [hour:minute]에서 시작하는 10분 칸에 [seconds]초를 채운다. */
private fun bucket(date: LocalDate, hour: Int, minute: Int, seconds: Int) =
    UsageBucket(at(date, hour, minute), seconds)

class CurfewTest {

    @Test
    fun `현재 시각을 하루 안의 분으로 바꾼다`() {
        assertEquals(0, minuteOfDay(at(DAY, 0, 0), ZONE))
        assertEquals(14 * 60 + 35, minuteOfDay(at(DAY, 14, 35), ZONE))
        assertEquals(1439, minuteOfDay(at(DAY, 23, 59), ZONE))
    }

    @Test
    fun `자정을 넘는 커퓨의 길이를 구한다`() {
        assertEquals(300, windowLengthMinutes(120, 420))      // 02:00 - 07:00
        assertEquals(450, windowLengthMinutes(23 * 60 + 30, 420))  // 23:30 - 07:00
        assertEquals(0, windowLengthMinutes(120, 120))
    }

    @Test
    fun `커퓨 길이가 0이면 창이 없다`() {
        assertNull(windowFor(DAY, ZONE, 120, 120))
    }

    @Test
    fun `자정을 넘는 창은 끝나는 날짜에 귀속한다`() {
        val window = windowFor(DAY, ZONE, 23 * 60 + 30, 420)!!

        assertEquals(at(DAY.minusDays(1), 23, 30), window.startMs)
        assertEquals(at(DAY, 7), window.endMs)
    }

    @Test
    fun `제한 시간대 사용이 없으면 성공이다`() {
        val window = windowFor(DAY, ZONE, 120, 420)!!
        val buckets = listOf(bucket(DAY, 1, 0, 600), bucket(DAY, 8, 0, 600))

        assertEquals(
            DayStatus.SUCCESS,
            score(window, at(DAY, 12), buckets, emptyList(), 300),
        )
    }

    @Test
    fun `허용 오차 경계에서 성공과 실패가 갈린다`() {
        val window = windowFor(DAY, ZONE, 120, 420)!!
        val now = at(DAY, 12)

        assertEquals(
            DayStatus.SUCCESS,
            score(window, now, listOf(bucket(DAY, 3, 0, 300)), emptyList(), 300),
        )
        assertEquals(
            DayStatus.FAIL,
            score(window, now, listOf(bucket(DAY, 3, 0, 301)), emptyList(), 300),
        )
    }

    @Test
    fun `창이 공백과 겹치면 사용량과 무관하게 판정하지 않는다`() {
        val window = windowFor(DAY, ZONE, 120, 420)!!
        val gap = CoverageGap(at(DAY, 3), at(DAY, 3) + 1_000)

        assertEquals(
            DayStatus.NO_DATA,
            score(window, at(DAY, 12), emptyList(), listOf(gap), 300),
        )
    }

    @Test
    fun `창 밖의 공백은 판정에 영향을 주지 않는다`() {
        val window = windowFor(DAY, ZONE, 120, 420)!!
        val gap = CoverageGap(at(DAY, 12), at(DAY, 13))

        assertEquals(
            DayStatus.SUCCESS,
            score(window, at(DAY, 20), emptyList(), listOf(gap), 300),
        )
    }

    @Test
    fun `아직 끝나지 않은 창은 진행 중이다`() {
        val window = windowFor(DAY, ZONE, 120, 420)!!

        assertEquals(
            DayStatus.IN_PROGRESS,
            score(window, at(DAY, 3), listOf(bucket(DAY, 2, 30, 600)), emptyList(), 0),
        )
    }

    @Test
    fun `창 경계가 칸 가운데를 지나면 겹치는 비율만큼만 센다`() {
        // 창은 02:05에 시작한다. 02:00 칸은 뒤쪽 5분만 겹치므로 절반만 센다.
        val window = windowFor(DAY, ZONE, 125, 420)!!
        val buckets = listOf(bucket(DAY, 2, 0, 600))

        assertEquals(300, usedSecondsIn(window, buckets).roundToInt())
        assertEquals(SLOT_MS / 2, window.startMs - at(DAY, 2))
    }

    @Test
    fun `수집이 한 번도 없었다면 지난 창은 기록 없음이다`() {
        val window = windowFor(DAY, ZONE, 120, 420)!!
        val now = at(DAY, 20)

        assertEquals(
            DayStatus.NO_DATA,
            score(window, now, emptyList(), unrecordedGaps(null, granted = false, nowMs = now), 300),
        )
    }

    @Test
    fun `권한이 꺼진 동안의 창은 기록 없음이다`() {
        val window = windowFor(DAY, ZONE, 120, 420)!!
        val now = at(DAY, 20)
        val lastCursor = at(DAY.minusDays(3), 9)

        assertEquals(
            DayStatus.NO_DATA,
            score(window, now, emptyList(), unrecordedGaps(lastCursor, granted = false, nowMs = now), 300),
        )
    }

    @Test
    fun `권한이 켜져 있고 수집 중이면 공백을 더하지 않는다`() {
        assertEquals(
            emptyList<CoverageGap>(),
            unrecordedGaps(at(DAY, 19), granted = true, nowMs = at(DAY, 20)),
        )
    }

    @Test
    fun `6시간 안의 정적은 공백이 아니다`() {
        val from = at(DAY, 2, 0)
        assertNull(coverageGapForLateCollect(from, from + GAP_THRESHOLD_MS, null, null))
    }

    @Test
    fun `6시간을 넘겨 수집해도 커서 이전 이벤트가 남아 있으면 공백이 아니다`() {
        val from = at(DAY, 2, 20)
        assertNull(
            coverageGapForLateCollect(from, at(DAY, 10, 48), at(DAY, 2, 25), at(DAY, 2, 0)),
        )
    }

    @Test
    fun `커서 이전을 확인할 수 없으면 늦은 수집은 공백이다`() {
        val from = at(DAY, 2, 20)
        val now = at(DAY, 10, 48)
        assertEquals(CoverageGap(from, now), coverageGapForLateCollect(from, now, null, null))
    }

    @Test
    fun `보관 확인 구간 밖의 이벤트는 커서를 증명하지 못한다`() {
        val from = at(DAY, 2, 20)
        assertEquals(true, provesRetention(from, from - RETENTION_LOOKBACK_MS))
        assertEquals(false, provesRetention(from, from - RETENTION_LOOKBACK_MS - 1))
        assertEquals(false, provesRetention(0L, from))
    }

    @Test
    fun `취침 중 8시간 밀려도 직전 사용이 남아 있으면 그날은 성공이다`() {
        val day = LocalDate.of(2026, 10, 14)
        val window = windowFor(day, ZONE, 2 * 60, 9 * 60)!!
        val from = at(day, 2, 20)
        val now = at(day, 10, 48)
        val stored = listOf(CoverageGap(from, at(day, 2, 25)))
        val retained = listOf(UsageEventLite(at(day, 2, 0), EventType.SCREEN_NON_INTERACTIVE))
        val forward = listOf(UsageEventLite(at(day, 2, 25), EventType.SCREEN_INTERACTIVE))

        val (add, delete) = decideCoverageGaps(from, now, forward, retained, stored)

        assertNull(add)
        assertEquals(listOf(from), delete)
        assertEquals(
            DayStatus.SUCCESS,
            score(window, now, emptyList(), stored.filter { it.startMs !in delete }, 300),
        )
    }

    @Test
    fun `직전 사용을 확인할 수 없으면 밀린 밤 전체는 기록 없음이다`() {
        val day = LocalDate.of(2026, 10, 14)
        val window = windowFor(day, ZONE, 2 * 60, 9 * 60)!!
        val from = at(day, 2, 20)
        val now = at(day, 10, 48)
        val installGap = CoverageGap(0L, at(day.minusDays(20), 0))

        val (add, delete) = decideCoverageGaps(from, now, emptyList(), emptyList(), listOf(installGap))

        assertEquals(CoverageGap(from, now), add)
        assertEquals(emptyList<Long>(), delete)
        assertEquals(DayStatus.NO_DATA, score(window, now, emptyList(), listOf(add!!), 300))
    }
}
