package com.thsvkd.curfew.collect

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thsvkd.curfew.data.CollectorState
import com.thsvkd.curfew.data.CoverageGap
import com.thsvkd.curfew.data.CurfewDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 이보다 오래 수집이 끊겼다면, 그 사이 이벤트가 이미 시스템에서 지워졌을 수 있다고 본다.
 * 정기 작업이 절전 정책으로 몇 시간 밀리는 것은 정상 범위라 공백으로 세지 않는다.
 */
const val GAP_THRESHOLD_MS = 6 * 60 * 60 * 1000L

/**
 * 커서보다 이전으로 이 길이만큼만 거슬러 올라가 보관 여부를 확인한다.
 * 그 안에 이벤트가 하나라도 남아 있으면 보관 기간이 커서를 덮으므로, 커서 뒤의 정적은
 * 삭제가 아니라 화면 이벤트가 없었다는 뜻이다. 하나도 없으면 지워졌는지 알 수 없다.
 */
const val RETENTION_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000L

/**
 * [boundaryMs]보다 이전인 [priorEventMs]가 보관 확인 구간 안에 있으면, 그 시각의 이벤트는
 * 아직 지워지지 않은 것이다. 그보다 나중 구간은 더 최근이므로 같이 남아 있다.
 * 설치 이전을 뜻하는 0 시각은 확인할 대상이 아니다.
 */
fun provesRetention(
    boundaryMs: Long,
    priorEventMs: Long?,
    lookbackMs: Long = RETENTION_LOOKBACK_MS,
): Boolean {
    if (boundaryMs <= 0L || priorEventMs == null || priorEventMs >= boundaryMs) return false
    return boundaryMs - priorEventMs <= lookbackMs
}

/** [boundaryMs] 직전, 보관 확인 구간 안에서 가장 최근 이벤트 시각. 없으면 null. */
fun newestRetainedEventMs(
    events: List<UsageEventLite>,
    boundaryMs: Long,
    lookbackMs: Long = RETENTION_LOOKBACK_MS,
): Long? = events
    .asSequence()
    .map { it.timeStampMs }
    .filter { provesRetention(boundaryMs, it, lookbackMs) }
    .maxOrNull()

/**
 * 수집이 늦어졌을 때 `[fromMs, 첫 이벤트)`를 공백으로 남길지 결정한다.
 * 6시간 이하면 공백이 아니다. 6시간을 넘어도 커서 이전에 이벤트가 남아 있으면 공백이 아니다.
 * 남아 있는 이벤트가 없을 때만 공백을 만든다. 첫 이벤트가 없으면 끝은 [nowMs]다.
 */
fun coverageGapForLateCollect(
    fromMs: Long,
    nowMs: Long,
    firstEventMs: Long?,
    priorEventMs: Long?,
    lookbackMs: Long = RETENTION_LOOKBACK_MS,
): CoverageGap? {
    if (nowMs - fromMs <= GAP_THRESHOLD_MS) return null
    val gapEnd = firstEventMs ?: nowMs
    if (gapEnd <= fromMs) return null
    if (provesRetention(fromMs, priorEventMs, lookbackMs)) return null
    return CoverageGap(fromMs, gapEnd)
}

/**
 * 이번 수집에서 만들 공백과 지울 공백의 시작 시각.
 * 지울 대상은 시작이 0보다 크고, 그 직전 이벤트가 보관 확인 구간 안에 남아 있는 공백뿐이다.
 */
fun decideCoverageGaps(
    fromMs: Long,
    nowMs: Long,
    forwardEvents: List<UsageEventLite>,
    retainedEvents: List<UsageEventLite>,
    storedGaps: List<CoverageGap>,
): Pair<CoverageGap?, List<Long>> {
    val toDelete = storedGaps.mapNotNull { gap ->
        val prior = newestRetainedEventMs(retainedEvents, gap.startMs)
        if (provesRetention(gap.startMs, prior)) gap.startMs else null
    }
    val toAdd = coverageGapForLateCollect(
        fromMs = fromMs,
        nowMs = nowMs,
        firstEventMs = forwardEvents.minOfOrNull { it.timeStampMs },
        priorEventMs = newestRetainedEventMs(retainedEvents, fromMs),
    )
    return toAdd to toDelete
}

/** 이만큼 기록이 비면 화면에 경고를 띄운다. */
const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L

object UsageAccess {

    fun isGranted(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** [packageName]을 주면 Android 10 이상에서 이 앱 항목이 바로 열린다. 그보다 낮으면 무시되어 목록이 열린다. */
    fun settingsIntent(packageName: String? = null): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            if (packageName != null) data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

class CollectWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        if (!UsageAccess.isGranted(context)) return@withContext Result.success()

        val dao = CurfewDb.get(context).dao()
        val now = System.currentTimeMillis()
        val state = dao.state()

        if (state == null) {
            // 첫 실행. 설치 이전은 통째로 공백이고, 수집은 지금부터 시작한다.
            dao.addGap(CoverageGap(0L, now))
            dao.saveState(CollectorState(0, now, deviceStateNow(context)))
            return@withContext Result.success()
        }

        val from = state.lastCursorMs
        val storedGaps = dao.allGaps()
        val healable = storedGaps.filter { it.startMs > 0L }
        if (now <= from && healable.isEmpty()) return@withContext Result.success()

        val late = now > from && now - from > GAP_THRESHOLD_MS
        val retained = if (late || healable.isNotEmpty()) {
            val boundaries = buildList {
                if (late) add(from)
                healable.forEach { add(it.startMs) }
            }
            readEventsBefore(context, boundaries)
        } else {
            emptyList()
        }
        val events = if (now > from) readEvents(context, from, now) else emptyList()
        val (gap, deleteStarts) = decideCoverageGaps(from, now, events, retained, storedGaps)
        for (startMs in deleteStarts) dao.deleteGap(startMs)
        if (gap != null) dao.addGap(gap)
        if (now <= from) return@withContext Result.success()

        val segments = UsageCollector.collect(
            events = events,
            startState = DeviceState(state.screenOn, state.unlocked),
            windowStartMs = from,
            windowEndMs = now,
        )
        for ((bucketStartMs, seconds) in UsageCollector.toBuckets(segments)) {
            if (seconds > 0) dao.addUsage(bucketStartMs, seconds)
        }
        // 이벤트만으로 상태를 이어 가면 이벤트가 한 번 새는 순간부터 계속 어긋난다. 창의 끝은
        // 바로 지금이므로 기기에 직접 물어본 값을 다음 창의 시작 상태로 넘긴다. 이렇게 하면
        // 어긋남이 한 창(15분)을 넘겨 이어지지 않는다.
        dao.saveState(CollectorState(0, now, deviceStateNow(context)))
        Result.success()
    }

    /** 지금 이 순간의 화면과 잠금 상태. 이벤트 스트림보다 이쪽이 언제나 정확하다. */
    private fun deviceStateNow(context: Context): DeviceState {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return DeviceState(
            screenOn = power.isInteractive,
            unlocked = !keyguard.isKeyguardLocked,
        )
    }

    /** [boundaries] 각 시각보다 이전, 보관 확인 구간 안의 이벤트. 끝은 그 시각들을 포함하지 않는다. */
    private fun readEventsBefore(context: Context, boundaries: List<Long>): List<UsageEventLite> {
        if (boundaries.isEmpty()) return emptyList()
        val end = boundaries.max()
        val begin = (boundaries.min() - RETENTION_LOOKBACK_MS).coerceAtLeast(0L)
        if (end <= begin) return emptyList()
        return readEvents(context, begin, end)
    }

    private fun readEvents(context: Context, fromMs: Long, toMs: Long): List<UsageEventLite> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stream = manager.queryEvents(fromMs, toMs)
        val events = ArrayList<UsageEventLite>()
        val event = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            // 같은 객체를 재사용하므로 값을 바로 복사해 둔다.
            stream.getNextEvent(event)
            events += UsageEventLite(event.timeStamp, event.eventType)
        }
        events.sortBy { it.timeStampMs }
        return events
    }

    companion object {
        const val PERIODIC_NAME = "collect-usage-periodic"
        const val ONE_SHOT_NAME = "collect-usage-now"
    }
}

/**
 * 15분은 WorkManager가 허용하는 최소 주기다. 실시간성이 필요 없고 커서로 이어 붙이므로
 * 이보다 잦게 돌릴 이유가 없다. 재부팅 후 재등록은 WorkManager가 알아서 한다.
 */
fun scheduleCollection(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        CollectWorker.PERIODIC_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<CollectWorker>(15, TimeUnit.MINUTES).build(),
    )
}

/** 앱을 열 때 밀린 구간을 바로 메운다. */
fun collectNow(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        CollectWorker.ONE_SHOT_NAME,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<CollectWorker>().build(),
    )
}
