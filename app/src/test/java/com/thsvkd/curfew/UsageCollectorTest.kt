package com.thsvkd.curfew

import com.thsvkd.curfew.collect.DeviceState
import com.thsvkd.curfew.collect.EventType
import com.thsvkd.curfew.collect.SLOT_MS
import com.thsvkd.curfew.collect.Segment
import com.thsvkd.curfew.collect.UsageCollector
import com.thsvkd.curfew.collect.UsageEventLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-09-07 00:00 KST 근처의 임의 기준점. 값 자체에는 의미가 없다. */
private const val T0 = 1_788_800_000_000L

private fun event(offsetMs: Long, type: Int) = UsageEventLite(T0 + offsetMs, type)

class UsageCollectorTest {

    @Test
    fun `화면과 잠금이 모두 풀려야 사용 구간이 시작된다`() {
        val events = listOf(
            event(1_000, EventType.SCREEN_INTERACTIVE),
            event(3_000, EventType.KEYGUARD_HIDDEN),
            event(9_000, EventType.SCREEN_NON_INTERACTIVE),
        )
        val segments = UsageCollector.collect(events, DeviceState(), T0, T0 + 10_000)

        assertEquals(listOf(Segment(T0 + 3_000, T0 + 9_000)), segments)
    }

    /**
     * 실기기에서 처음 잡힌 결함에 대한 회귀 테스트. 이미 화면을 켜고 쓰는 중에 수집 창이
     * 시작되면 그 창 안에는 켜짐 이벤트가 없다. 시작 상태를 꺼짐으로 가정하면 사용을 통째로
     * 놓치고, 실패해야 할 날이 성공으로 뒤집힌다.
     */
    @Test
    fun `창이 시작될 때 이미 사용 중이면 창 시작부터 센다`() {
        val segments = UsageCollector.collect(
            events = emptyList(),
            startState = DeviceState(screenOn = true, unlocked = true),
            windowStartMs = T0,
            windowEndMs = T0 + 600_000,
        )

        assertEquals(listOf(Segment(T0, T0 + 600_000)), segments)
        assertEquals(600, UsageCollector.toBuckets(segments).values.sum())
    }

    @Test
    fun `KEYGUARD_HIDDEN이 없어도 액티비티 재개를 잠금 해제로 받는다`() {
        val events = listOf(
            event(1_000, EventType.SCREEN_INTERACTIVE),
            event(2_000, EventType.ACTIVITY_RESUMED),
            event(5_000, EventType.SCREEN_NON_INTERACTIVE),
        )
        val segments = UsageCollector.collect(events, DeviceState(), T0, T0 + 10_000)

        assertEquals(listOf(Segment(T0 + 2_000, T0 + 5_000)), segments)
    }

    @Test
    fun `화면만 켜지고 잠금이 남아 있으면 사용이 아니다`() {
        val events = listOf(
            event(1_000, EventType.SCREEN_INTERACTIVE),
            event(4_000, EventType.SCREEN_NON_INTERACTIVE),
        )
        val segments = UsageCollector.collect(events, DeviceState(), T0, T0 + 10_000)

        assertTrue(segments.isEmpty())
    }

    @Test
    fun `칸 경계를 넘는 구간은 두 칸에 나뉘고 합이 보존된다`() {
        // 한 칸의 끝 2분 전에 시작해 다음 칸으로 3분 더 이어지는 구간.
        val slotStart = Math.floorDiv(T0, SLOT_MS) * SLOT_MS
        val start = slotStart + SLOT_MS - 120_000
        val end = start + 300_000
        val buckets = UsageCollector.toBuckets(listOf(Segment(start, end)))

        assertEquals(2, buckets.size)
        assertEquals(120, buckets[slotStart])
        assertEquals(180, buckets[slotStart + SLOT_MS])
        assertEquals(300, buckets.values.sum())
    }

    @Test
    fun `열린 구간이 다음 수집으로 이어지며 총합이 끊기지 않는다`() {
        val first = UsageCollector.collect(
            events = listOf(
                event(0, EventType.SCREEN_INTERACTIVE),
                event(0, EventType.KEYGUARD_HIDDEN),
            ),
            startState = DeviceState(),
            windowStartMs = T0,
            windowEndMs = T0 + 60_000,
        )
        // 다음 창의 시작 상태는 워커가 기기에 직접 물어본 값이다. 창이 끝나는 시점에는
        // 화면이 켜져 있고 잠금도 풀려 있었다.
        val second = UsageCollector.collect(
            events = listOf(event(90_000, EventType.SCREEN_NON_INTERACTIVE)),
            startState = DeviceState(screenOn = true, unlocked = true),
            windowStartMs = T0 + 60_000,
            windowEndMs = T0 + 120_000,
        )

        val total = (first + second).sumOf { it.endMs - it.startMs }
        assertEquals(90_000L, total)
        assertEquals(90, UsageCollector.toBuckets(first + second).values.sum())
    }

    @Test
    fun `커서보다 앞선 이벤트를 다시 넣어도 값이 늘지 않는다`() {
        val stale = listOf(
            event(-50_000, EventType.SCREEN_INTERACTIVE),
            event(-40_000, EventType.KEYGUARD_HIDDEN),
        )
        val segments = UsageCollector.collect(stale, DeviceState(), T0, T0 + 10_000)

        assertTrue(segments.isEmpty())
    }
}
