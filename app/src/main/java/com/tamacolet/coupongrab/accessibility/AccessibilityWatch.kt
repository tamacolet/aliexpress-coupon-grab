package com.tamacolet.coupongrab.accessibility

import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.tamacolet.coupongrab.automation.CouponAccessibilityService
import com.tamacolet.coupongrab.core.AccessibilityWatchRules
import com.tamacolet.coupongrab.core.Schedule
import com.tamacolet.coupongrab.core.ScheduleKind
import com.tamacolet.coupongrab.service.Notifier
import com.tamacolet.coupongrab.service.app

object AccessibilityWatch {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    fun checkAsync(context: Context, schedule: Schedule): kotlinx.coroutines.Job =
        scope.launch { check(context.applicationContext, schedule) }

    enum class Status(val label: String) {
        CONNECTED("動いている"), ENABLED_DISCONNECTED("設定は入っているが動いていない"), DISABLED("外れている"),
    }

    fun status(context: Context): Status {
        if (CouponAccessibilityService.isConnected()) return Status.CONNECTED
        val component = ComponentName(context, CouponAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        return if (enabled) Status.ENABLED_DISCONNECTED else Status.DISABLED
    }

    fun settingsIntent(context: Context): Intent {
        val component = ComponentName(context, CouponAccessibilityService::class.java).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", component)
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", component)
            })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun appInfoIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"),
    )

    private fun preferences(context: Context) =
        context.getSharedPreferences("accessibility_watch", Context.MODE_PRIVATE)

    /** 実接続を観測したら、繰り返し予定の次の切断通知を再び有効にする。 */
    @Synchronized
    fun observeConnection(context: Context) {
        if (!CouponAccessibilityService.isConnected()) return
        val prefs = preferences(context)
        val disconnected = context.app.store.load().schedules.filter {
            it.kind == ScheduleKind.REPEAT && AccessibilityWatchRules.shouldRearmRepeat(
                prefs.getBoolean("repeat_connected:${it.id}", false),
            )
        }
        if (disconnected.isEmpty()) return
        val editor = prefs.edit()
        disconnected.forEach { editor.putBoolean("repeat_connected:${it.id}", true) }
        editor.commit()
    }

    suspend fun check(context: Context, schedule: Schedule) {
        try {
            CouponAccessibilityService.awaitConnected(5_000)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: com.tamacolet.coupongrab.core.AutomationException) {
            // 接続の猶予を使い切った後、最新の接続状態で判定する。
        }
        recordCheck(context, schedule)
    }

    @Synchronized
    private fun recordCheck(context: Context, schedule: Schedule) {
        // 呼び出し元の古いスナップショットでも通知しない。
        val current = context.app.store.load().schedules.firstOrNull {
            it.id == schedule.id && AccessibilityWatchRules.matches(it, AccessibilityWatchRules.occurrence(schedule))
        } ?: return
        val connected = CouponAccessibilityService.isConnected()
        val prefs = preferences(context)
        val editor = prefs.edit()
        val decision = if (current.kind == ScheduleKind.EXACT) {
            val key = AccessibilityWatchRules.exactOccurrence(current)
            AccessibilityWatchRules.exact(prefs.getString("exact:${current.id}", null) == key, connected).also {
                editor.putString("exact:${current.id}", key)
            }
        } else {
            val previous = if (prefs.contains("repeat_connected:${current.id}")) {
                prefs.getBoolean("repeat_connected:${current.id}", false)
            } else null
            AccessibilityWatchRules.repeat(previous, connected).also {
                editor.putBoolean("repeat_connected:${current.id}", connected)
            }
        }
        // 通知より先に同期保存し、プロセスの作り直しでも同じ警告を繰り返さない。
        if (editor.commit() && decision.notify) Notifier(context).accessibilityDisconnected(current.id)
        if (connected) observeConnection(context)
    }
}
