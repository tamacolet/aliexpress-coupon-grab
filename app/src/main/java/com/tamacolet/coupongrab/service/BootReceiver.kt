package com.tamacolet.coupongrab.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tamacolet.coupongrab.GrabService
import com.tamacolet.coupongrab.core.TimeCorrection
import kotlin.concurrent.thread

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        thread(name = "coupon-boot") {
            try {
                val state = context.app.store.update {
                    it.copy(
                        busy = false,
                        // elapsedRealtime は再起動で戻るため、古い補正アンカーを再利用しない。
                        correction = TimeCorrection(),
                    )
                }
                AlarmScheduler.scheduleAll(context)
                if (state.pendingRuns.isNotEmpty()) {
                    runCatching {
                        context.startForegroundService(Intent(context, GrabService::class.java))
                    }.onFailure { Notifier(context).failure("保留中の実行を再開できませんでした。") }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
