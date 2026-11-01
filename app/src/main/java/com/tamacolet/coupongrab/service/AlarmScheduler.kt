package com.tamacolet.coupongrab.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
    fun scheduleAll(context: Context) {
        context.app.store.load().schedules.forEach { schedule ->
            if (schedule.enabled) schedule(context, schedule) else cancel(context, schedule.id)
        }
    }

    fun schedule(context: Context, schedule: Schedule) {
        if (!schedule.enabled || schedule.nextRunAtMs <= 0) {
            cancel(context, schedule.id)
            return
        }
        val manager = context.getSystemService(AlarmManager::class.java)
        if (!manager.canScheduleExactAlarms()) {
            // 不正確なアラームへ黙って落とさない。許可状況は設定画面に表示する。
            cancel(context, schedule.id)
            return
        }
        val correction = context.app.store.load().correction
        val offset = if (correction.measuredAtMs > 0) correction.offsetMs else 0
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
    }

    fun cancel(context: Context, scheduleId: String) {
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
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class AlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(GrabService.EXTRA_SCHEDULE_ID) ?: return
        val kind = runCatching {
            ScheduleKind.valueOf(intent.getStringExtra(GrabService.EXTRA_KIND).orEmpty())
        }.getOrDefault(ScheduleKind.REPEAT)
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
