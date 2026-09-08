package com.thsvkd.curfew.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thsvkd.curfew.collect.SLOT_MS
import com.thsvkd.curfew.collect.STALE_THRESHOLD_MS
import com.thsvkd.curfew.collect.UsageAccess
import com.thsvkd.curfew.data.ChartMode
import com.thsvkd.curfew.data.CollectorState
import com.thsvkd.curfew.data.CoverageGap
import com.thsvkd.curfew.data.CurfewDb
import com.thsvkd.curfew.data.CurfewSettings
import com.thsvkd.curfew.data.SettingsStore
import com.thsvkd.curfew.data.UsageBucket
import com.thsvkd.curfew.score.DayResult
import com.thsvkd.curfew.score.DayStatus
import com.thsvkd.curfew.score.score
import com.thsvkd.curfew.score.streakOf
import com.thsvkd.curfew.score.usedSecondsIn
import com.thsvkd.curfew.score.windowFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class HomeState(
    val loading: Boolean = true,
    val permissionGranted: Boolean = true,
    val stale: Boolean = false,
    val settings: CurfewSettings = CurfewSettings(),
    val week: List<DayResult> = emptyList(),
    val latest: DayResult? = null,
    val streak: Int = 0,
    val weekSuccess: Int = 0,
    val date: LocalDate = LocalDate.now(),
    /** 그날의 10분 칸별 사용 초. 평상시 144개. */
    val slots: List<Int> = emptyList(),
    val gapSlots: Set<Int> = emptySet(),
    val dayTotalSeconds: Int = 0,
    val curfewSeconds: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = CurfewDb.get(app).dao()
    private val settings = SettingsStore(app)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val permission = MutableStateFlow(UsageAccess.isGranted(app))

    val state: StateFlow<HomeState> =
        combine(settings.flow, selectedDate, permission) { s, date, granted -> Triple(s, date, granted) }
            .flatMapLatest { (s, date, granted) ->
                val today = LocalDate.now(zone)
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val weekStart = windowFor(today.minusDays(6), zone, s.startMinutes, s.endMinutes)
                    ?.startMs ?: dayStart
                // 창 경계에 걸친 칸까지 읽으려면 한 칸 앞에서 시작해야 한다.
                val from = minOf(dayStart, weekStart) - SLOT_MS
                val to = maxOf(dayEnd, System.currentTimeMillis())

                combine(
                    dao.bucketsBetween(from, to),
                    dao.gapsOverlapping(from, to),
                    dao.stateFlow(),
                ) { buckets, gaps, collector ->
                    build(s, date, today, granted, buckets, gaps, collector, dayStart, dayEnd)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun refresh() {
        permission.value = UsageAccess.isGranted(getApplication())
    }

    fun showDate(date: LocalDate) {
        selectedDate.value = date
    }

    fun shiftDate(days: Long) {
        val next = selectedDate.value.plusDays(days)
        if (!next.isAfter(LocalDate.now(zone))) selectedDate.value = next
    }

    fun setCurfew(startMinutes: Int, endMinutes: Int) {
        viewModelScope.launch { settings.setCurfew(startMinutes, endMinutes) }
    }

    fun setChartMode(mode: ChartMode) {
        viewModelScope.launch { settings.setChartMode(mode) }
    }

    private fun build(
        s: CurfewSettings,
        date: LocalDate,
        today: LocalDate,
        granted: Boolean,
        buckets: List<UsageBucket>,
        gaps: List<CoverageGap>,
        collector: CollectorState?,
        dayStart: Long,
        dayEnd: Long,
    ): HomeState {
        val now = System.currentTimeMillis()

        val week = (6L downTo 0L).map { back ->
            val d = today.minusDays(back)
            val window = windowFor(d, zone, s.startMinutes, s.endMinutes)
            DayResult(d, score(window, now, buckets, gaps, s.toleranceSeconds))
        }
        val latest = week.lastOrNull { it.status != DayStatus.IN_PROGRESS }

        val slotCount = ((dayEnd - dayStart) / SLOT_MS).toInt()
        val byStart = buckets.associateBy { it.bucketStartMs }
        val slots = (0 until slotCount).map { i -> byStart[dayStart + i * SLOT_MS]?.usedSeconds ?: 0 }
        val gapSlots = (0 until slotCount).filterTo(mutableSetOf()) { i ->
            val slotStart = dayStart + i * SLOT_MS
            // 아직 오지 않은 시각을 사용 0분으로 그리면 하루를 다 보낸 것처럼 읽힌다.
            slotStart >= now || gaps.any { it.startMs < slotStart + SLOT_MS && it.endMs > slotStart }
        }

        val shownWindow = windowFor(date, zone, s.startMinutes, s.endMinutes)
        val curfewSeconds = shownWindow?.let { usedSecondsIn(it, buckets).roundToInt() } ?: 0

        return HomeState(
            loading = false,
            permissionGranted = granted,
            stale = collector != null && now - collector.lastCursorMs > STALE_THRESHOLD_MS,
            settings = s,
            week = week,
            latest = latest,
            streak = streakOf(week),
            weekSuccess = week.count { it.status == DayStatus.SUCCESS },
            date = date,
            slots = slots,
            gapSlots = gapSlots,
            dayTotalSeconds = slots.sum(),
            curfewSeconds = curfewSeconds,
        )
    }
}
