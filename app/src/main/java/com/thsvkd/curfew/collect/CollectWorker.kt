package com.thsvkd.curfew.collect

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        if (now <= from) return@withContext Result.success()

        val events = readEvents(context, from, now)
        if (now - from > GAP_THRESHOLD_MS) {
            val firstEventMs = events.firstOrNull()?.timeStampMs ?: now
            if (firstEventMs > from) dao.addGap(CoverageGap(from, firstEventMs))
        }

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
