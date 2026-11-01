package com.tamacolet.coupongrab.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tamacolet.coupongrab.R

class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "クーポン適用の実行", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "時刻・実行の警告", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun running(text: String = "登録したクーポンを準備しています"): Notification =
        NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("券取り")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    fun clockFallback() = alert(
        "時刻補正を使えません",
        "SNTPとHTTP Dateの両方に失敗したため、補正なしの端末時計で実行します。",
    )

    fun failure(message: String) = alert("クーポン適用に失敗しました", message)

    fun codeResult(code: String, status: com.tamacolet.coupongrab.core.CodeStatus, detail: String) {
        val label = when (status) {
            com.tamacolet.coupongrab.core.CodeStatus.APPLIED -> "取れた"
            com.tamacolet.coupongrab.core.CodeStatus.USED -> "使用済み"
            com.tamacolet.coupongrab.core.CodeStatus.FAILURE -> "失敗"
            com.tamacolet.coupongrab.core.CodeStatus.WAITING -> "待ち"
        }
        alert("$code: $label", if (detail == status.name) label else detail, 1_000 + (code.hashCode() and 0x7fffffff) % 100_000)
    }

    private fun alert(title: String, text: String, notificationId: Int = 2) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        manager.notify(
            notificationId,
            NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val RUNNING_ID = 1
        private const val CHANNEL_RUNNING = "running"
        private const val CHANNEL_ALERT = "alerts"
    }
}
