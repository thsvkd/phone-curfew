package com.thsvkd.curfew.score

import com.thsvkd.curfew.collect.SLOT_MS
import com.thsvkd.curfew.data.CoverageGap
import com.thsvkd.curfew.data.UsageBucket
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

const val MINUTES_PER_DAY = 1440

enum class DayStatus { SUCCESS, FAIL, IN_PROGRESS, NO_DATA }

/** 커퓨 창. 반열린 구간 [startMs, endMs). */
data class Window(val startMs: Long, val endMs: Long)

data class DayResult(val date: LocalDate, val status: DayStatus)

/** 자정을 넘는 설정도 그대로 받도록, 길이를 하루 안에서 순환시켜 구한다. */
fun windowLengthMinutes(startMinutes: Int, endMinutes: Int): Int =
    ((endMinutes - startMinutes) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY

/**
 * 날짜 [date]에 귀속하는 커퓨 창을 구한다. 창은 그 날짜의 해제 시각에 끝나고 거기서 길이만큼
 * 거슬러 올라가 시작한다. 그래서 23:30에 시작해 다음 날 07:00에 끝나는 창의 결과는 잠에서 깬
 * 날짜에 붙는다. 커퓨 길이가 0이면 설정되지 않은 것으로 보고 null을 돌려준다.
 */
fun windowFor(date: LocalDate, zone: ZoneId, startMinutes: Int, endMinutes: Int): Window? {
    val length = windowLengthMinutes(startMinutes, endMinutes)
    if (length == 0) return null
    val endMs = date.atStartOfDay(zone).plusMinutes(endMinutes.toLong()).toInstant().toEpochMilli()
    return Window(endMs - length * 60_000L, endMs)
}

/**
 * 창 안의 사용 초를 구한다. 창 경계가 10분 칸 가운데를 지나면 그 칸은 겹치는 비율만큼만 센다.
 * 칸 안에서 사용이 고르게 퍼져 있다는 가정이며, 10분 해상도에서 더 정밀하게 나눌 근거가 없다.
 */
fun usedSecondsIn(window: Window, buckets: List<UsageBucket>): Double =
    buckets.sumOf { bucket ->
        val overlap = minOf(window.endMs, bucket.bucketStartMs + SLOT_MS) -
            maxOf(window.startMs, bucket.bucketStartMs)
        if (overlap <= 0) 0.0 else bucket.usedSeconds * (overlap.toDouble() / SLOT_MS)
    }

fun Window.overlaps(gap: CoverageGap): Boolean = gap.startMs < endMs && gap.endMs > startMs

/**
 * 하루를 판정한다. 순서가 중요하다. 아직 안 끝난 창을 먼저 걸러 내고, 그다음 데이터가 성한지
 * 보고, 마지막에 채점한다. 수집 공백을 성공으로 오판하지 않기 위한 순서다.
 */
fun score(
    window: Window?,
    nowMs: Long,
    buckets: List<UsageBucket>,
    gaps: List<CoverageGap>,
    toleranceSeconds: Int,
): DayStatus {
    if (window == null) return DayStatus.NO_DATA
    if (nowMs < window.endMs) return DayStatus.IN_PROGRESS
    if (gaps.any { window.overlaps(it) }) return DayStatus.NO_DATA
    val used = usedSecondsIn(window, buckets).roundToInt()
    return if (used <= toleranceSeconds) DayStatus.SUCCESS else DayStatus.FAIL
}

/** 가장 최근에 끝난 날부터 거슬러 올라가며 연속으로 성공한 일수를 센다. */
fun streakOf(results: List<DayResult>): Int {
    var count = 0
    for (result in results.sortedByDescending { it.date }) {
        when (result.status) {
            DayStatus.IN_PROGRESS -> continue
            DayStatus.SUCCESS -> count++
            else -> return count
        }
    }
    return count
}
