package com.tamacolet.coupongrab.service

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import com.tamacolet.coupongrab.core.ExecutionPolicy
import com.tamacolet.coupongrab.core.SecurityCheckException
import kotlinx.coroutines.CompletableDeferred
import com.tamacolet.coupongrab.core.AutomationFlow
import java.lang.ref.WeakReference

class WakeActivity : Activity() {
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = WeakReference(this)
        acquireScreenWakeLock()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        dismissKeyguardWhenReady()
    }

    override fun onPause() {
        resumed = false
        window.decorView.removeCallbacks(recheckKeyguard)
        super.onPause()
    }

    // 消灯から間もない時、Android 17 はロック画面を飛ばして直接解除する。その時は
    // requestDismissKeyguard の返事が来ないので、解除されたかを自分で確かめ続ける。
    private val recheckKeyguard = object : Runnable {
        override fun run() {
            if (!resumed || ready.isCompleted) return
            if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) completeReady()
            else window.decorView.postDelayed(this, KEYGUARD_RECHECK_MS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acquireScreenWakeLock()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (resumed) dismissKeyguardWhenReady()
    }

    private fun dismissKeyguardWhenReady() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            completeReady()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = completeReady()
            override fun onDismissCancelled() {
                ready.completeExceptionally(IllegalStateException("キーガード解除がキャンセルされました"))
            }
            override fun onDismissError() {
                ready.completeExceptionally(IllegalStateException("キーガードを解除できませんでした"))
            }
        })
        window.decorView.removeCallbacks(recheckKeyguard)
        window.decorView.postDelayed(recheckKeyguard, KEYGUARD_RECHECK_MS)
    }

    private fun completeReady() {
        if (resumed && !ready.isCompleted) ready.complete(Unit)
    }

    override fun onDestroy() {
        if (active?.get() === this) active = null
        super.onDestroy()
    }

    companion object {
        private const val KEYGUARD_RECHECK_MS = 200L
        private var active: WeakReference<WakeActivity>? = null
        private var ready: CompletableDeferred<Unit> = CompletableDeferred()
        private var screenWakeLock: PowerManager.WakeLock? = null

        fun launch(context: Context) {
            ready.cancel()
            ready = CompletableDeferred()
            context.startActivity(Intent(context, WakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }

        suspend fun awaitReady(timeoutMs: Long = 10_000) {
            AutomationFlow.awaitWake(timeoutMs) { ready.await() }
        }

        /** onResume とキーガード解除を待った後、WakeActivity またはユーザー補助から開く。 */
        fun launchCart(fallback: Context, intent: Intent) {
            if (fallback.app.store.load().securityHoldSinceMs != null) throw SecurityCheckException()
            val resumedActivity = active?.get()?.takeIf { it.resumed }
            (resumedActivity ?: fallback).startActivity(intent)
        }

        @Suppress("DEPRECATION")
        private fun acquireScreenWakeLock() {
            val activity = active?.get() ?: return
            if (screenWakeLock?.isHeld == true) return
            // Ali が前面にある間も画面を維持する必要があるため、期限付きの従来型だけが使える。
            // Activity の KEEP_SCREEN_ON や PARTIAL_WAKE_LOCK だけでは Ali 前面の画面を維持できない。
            screenWakeLock = activity.getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "coupongrab:screen",
            ).apply { acquire(ExecutionPolicy.MAX_EXECUTION_MS) }
        }

        fun dismiss() {
            active?.get()?.finish()
            active = null
            if (screenWakeLock?.isHeld == true) screenWakeLock?.release()
            screenWakeLock = null
            ready.cancel()
        }
    }
}
