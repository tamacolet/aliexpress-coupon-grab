package com.tamacolet.coupongrab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tamacolet.coupongrab.service.app
import kotlin.concurrent.thread

class NasReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val until = when (intent.action) {
            ACTION_NAS_BUSY -> intent.getLongExtra(EXTRA_UNTIL_MS, 0L).coerceAtLeast(0L)
            ACTION_NAS_FREE -> 0L
            else -> return
        }
        val pending = goAsync()
        thread(name = "coupon-nas") {
            try {
                // busyは実際に端末側の適用が始まるまで立てない。NAS状態とは独立して保持する。
                context.app.store.update { it.copy(nasBusyUntilMs = until) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_NAS_BUSY = "com.tamacolet.coupongrab.NAS_BUSY"
        const val ACTION_NAS_FREE = "com.tamacolet.coupongrab.NAS_FREE"
        const val EXTRA_UNTIL_MS = "untilMs"
    }
}
