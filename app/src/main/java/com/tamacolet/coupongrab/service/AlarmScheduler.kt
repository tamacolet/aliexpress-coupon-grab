package com.tamacolet.coupongrab.service

import kotlinx.coroutines.launch
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tamacolet.coupongrab.accessibility.AccessibilityWatch
import com.tamacolet.coupongrab.core.AccessibilityWatchRules
import com.tamacolet.coupongrab.CouponGrabApp
import com.tamacolet.coupongrab.GrabService
import com.tamacolet.coupongrab.ui.MainActivity
import com.tamacolet.coupongrab.core.RunQueue
import com.tamacolet.coupongrab.core.RunRequest
import com.tamacolet.coupongrab.core.Schedule
import com.tamacolet.coupongrab.core.ScheduleKind
import com.tamacolet.coupongrab.core.ScheduleRules

val Context.app: CouponGrabApp get() = applicationContext as CouponGrabApp

object AlarmScheduler {
    const val ACTION_ACCESSIBILITY_CHECK = "com.tamacolet.coupongrab.ACCESSIBILITY_CHECK"
    private const val EXTRA_OCCURRENCE = "occurrence"
    fun scheduleAll(context: Context) {
        context.app.store.load().schedules.forEach { schedule ->
            if (schedule.enabled) schedule(context, schedule) else cancel(context, schedule.id)
        }
    }

    fun schedule(context: Context, schedule: Schedule): kotlinx.coroutines.Job? {
        if (!schedule.enabled || schedule.nextRunAtMs <= 0) {
            cancel(context, schedule.id)
            return null
        }
        cancelCheck(context, schedule.id)
        val correction = context.app.store.load().correction
        val offset = if (correction.measuredAtMs > 0) correction.offsetMs else 0
        val checkAt = AccessibilityWatchRules.checkTriggerAt(schedule.nextRunAtMs, offset)
        val checkDue = AccessibilityWatchRules.checkDue(schedule.nextRunAtMs, offset, System.currentTimeMillis())
        val check = if (schedule.kind == ScheduleKind.EXACT && checkDue) {
            AccessibilityWatch.checkAsync(context, schedule)
        } else null
        val manager = context.getSystemService(AlarmManager::class.java)
        if (!manager.canScheduleExactAlarms()) {
            // 不正確なアラームへ黙って落とさない。許可状況は設定画面に表示する。
            cancel(context, schedule.id)
            return check
        }
        val triggerAt = ScheduleRules.alarmTriggerAt(
            kind = schedule.kind,
            targetAtMs = schedule.nextRunAtMs,
            clockOffsetMs = offset,
        )
        val showSchedule = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 1分間隔・30秒のNAS延期も、省電力中の間引きを受けない目覚まし方式で登録する。
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showSchedule), operation(context, schedule))
        if (schedule.kind == ScheduleKind.EXACT && !checkDue) {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(checkAt, showSchedule), checkOperation(context, schedule.id, schedule),
            )
        }
        return check
    }

    private fun checkOperation(context: Context, id: String, schedule: Schedule? = null): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_ACCESSIBILITY_CHECK
                data = Uri.parse("coupongrab://accessibility-check/$id")
                putExtra(GrabService.EXTRA_SCHEDULE_ID, id)
                if (schedule != null) putExtra(EXTRA_OCCURRENCE, AccessibilityWatchRules.occurrence(schedule))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelCheck(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(checkOperation(context, id))
    }

    fun currentSchedule(context: Context, intent: Intent): Schedule? {
        val id = intent.getStringExtra(GrabService.EXTRA_SCHEDULE_ID) ?: return null
        return context.app.store.load().schedules.firstOrNull {
            it.id == id && AccessibilityWatchRules.matches(it, intent.getStringExtra(EXTRA_OCCURRENCE))
        }
    }

    fun cancel(context: Context, scheduleId: String) {
        cancelCheck(context, scheduleId)
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(PendingIntent.getBroadcast(
            context,
            scheduleId.hashCode(),
            Intent(context, AlarmReceiver::class.java).apply {
                action = GrabService.ACTION_RUN
                data = Uri.parse("coupongrab://run/$scheduleId")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
    }

    private fun operation(context: Context, schedule: Schedule): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            schedule.id.hashCode(),
            Intent(context, AlarmReceiver::class.java).apply {
                action = GrabService.ACTION_RUN
                data = Uri.parse("coupongrab://run/${schedule.id}")
                putExtra(GrabService.EXTRA_SCHEDULE_ID, schedule.id)
                putExtra(GrabService.EXTRA_KIND, schedule.kind.name)
                putExtra(EXTRA_OCCURRENCE, AccessibilityWatchRules.occurrence(schedule))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class AlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val schedule = AlarmScheduler.currentSchedule(context, intent) ?: return
        if (intent.action == AlarmScheduler.ACTION_ACCESSIBILITY_CHECK) {
            if (schedule.kind != ScheduleKind.EXACT) return
            // 時計補正や端末時計の変更で早く届いた場合は、現在の時刻で登録し直す。
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                var check: kotlinx.coroutines.Job? = null
                try {
                    // goAsync の受信期限より短くし、別スコープの確認も残さない。
                    kotlinx.coroutines.withTimeoutOrNull(8_000) {
                        check = AlarmScheduler.schedule(context.applicationContext, schedule)
                        check?.join()
                    }
                } finally {
                    check?.cancel()
                    pending.finish()
                }
            }
            return
        }
        if (intent.action != GrabService.ACTION_RUN) return
        val id = schedule.id
        val kind = schedule.kind
        val request = RunRequest(id, kind)
        // startForegroundService が失敗しても要求自体は残す。
        context.app.store.update { it.copy(pendingRuns = RunQueue.enqueue(it.pendingRuns, request)) }
        val service = Intent(context, GrabService::class.java).apply {
            action = GrabService.ACTION_RUN
            putExtra(GrabService.EXTRA_SCHEDULE_ID, id)
            putExtra(GrabService.EXTRA_KIND, kind.name)
        }
        runCatching { context.startForegroundService(service) }
            .onFailure { Notifier(context).failure("実行サービスを開始できませんでした。予定は保持しています。") }
    }
}
