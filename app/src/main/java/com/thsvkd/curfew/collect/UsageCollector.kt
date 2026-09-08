package com.thsvkd.curfew.collect

import kotlin.math.roundToInt

/** 표본 한 칸의 길이. 10분. */
const val SLOT_MS = 600_000L

/**
 * 쓰는 이벤트 종류. 값은 AOSP `UsageEvents.Event` 기준이며, 여기서 상수를 다시 적는 이유는
 * 이 파일이 Android 프레임워크에 기대지 않고 단위 테스트에서 그대로 돌게 하기 위해서다.
 */
object EventType {
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
}

data class UsageEventLite(val timeStampMs: Long, val type: Int)

/** 수집 창 경계에서 이어받는 기기 상태. */
data class DeviceState(val screenOn: Boolean = false, val unlocked: Boolean = false)

/** 사용 중이던 구간. 반열린 구간 [startMs, endMs). */
data class Segment(val startMs: Long, val endMs: Long)

object UsageCollector {

    /**
     * 이벤트 목록을 사용 구간으로 바꾼다.
     *
     * 사용 중의 정의는 화면이 상호작용 가능하면서 동시에 잠금이 해제된 상태다. 구간은 항상
     * [windowStartMs, windowEndMs] 안으로 잘린다.
     *
     * [startState]가 창 시작 시점의 실제 기기 상태여야 한다는 점이 중요하다. 이미 화면을 켜고
     * 쓰는 중에 창이 시작되면 그 창 안에는 켜짐 이벤트가 없어서, 상태를 꺼짐으로 가정하는 순간
     * 사용을 통째로 놓친다. 실패를 성공으로 뒤집는 방향의 오차다.
     */
    fun collect(
        events: List<UsageEventLite>,
        startState: DeviceState,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<Segment> {
        var screenOn = startState.screenOn
        var unlocked = startState.unlocked
        var using = screenOn && unlocked
        var segmentStart = windowStartMs
        val segments = mutableListOf<Segment>()

        val ordered = events
            .filter { it.timeStampMs in windowStartMs..windowEndMs }
            .sortedBy { it.timeStampMs }

        for (event in ordered) {
            when (event.type) {
                EventType.SCREEN_INTERACTIVE -> screenOn = true
                EventType.SCREEN_NON_INTERACTIVE -> screenOn = false
                EventType.KEYGUARD_HIDDEN -> unlocked = true
                EventType.KEYGUARD_SHOWN -> unlocked = false
                // 잠금 상태에서는 일반 앱의 액티비티가 재개되지 않는다. KEYGUARD_HIDDEN을
                // 흘리는 기기가 있어 액티비티 재개도 잠금 해제 신호로 함께 받는다.
                EventType.ACTIVITY_RESUMED -> unlocked = true
                else -> continue
            }
            val nowUsing = screenOn && unlocked
            if (!using && nowUsing) {
                segmentStart = event.timeStampMs
            } else if (using && !nowUsing && event.timeStampMs > segmentStart) {
                segments += Segment(segmentStart, event.timeStampMs)
            }
            using = nowUsing
        }

        if (using && windowEndMs > segmentStart) {
            segments += Segment(segmentStart, windowEndMs)
        }
        return segments
    }

    /**
     * 사용 구간을 10분 칸에 나눠 담는다. 반환값은 칸의 시작 시각에서 그 칸의 사용 초로 가는
     * 맵이며, 초 단위로 반올림은 칸마다 한 번만 한다.
     */
    fun toBuckets(segments: List<Segment>): Map<Long, Int> {
        val millisPerSlot = HashMap<Long, Long>()
        for (segment in segments) {
            var slotStart = Math.floorDiv(segment.startMs, SLOT_MS) * SLOT_MS
            while (slotStart < segment.endMs) {
                val slotEnd = slotStart + SLOT_MS
                val overlap = minOf(segment.endMs, slotEnd) - maxOf(segment.startMs, slotStart)
                if (overlap > 0) {
                    millisPerSlot[slotStart] = (millisPerSlot[slotStart] ?: 0L) + overlap
                }
                slotStart = slotEnd
            }
        }
        return millisPerSlot.mapValues { (_, millis) ->
            (millis / 1000.0).roundToInt().coerceIn(0, 600)
        }
    }
}
