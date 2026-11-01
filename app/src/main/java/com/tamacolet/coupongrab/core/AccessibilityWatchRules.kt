package com.tamacolet.coupongrab.core

/** Android に依存しない、点検時刻と通知の判断。 */
object AccessibilityWatchRules {
    const val CHECK_LEAD_MS = 10 * 60_000L

    // 実行準備用の ALARM_LEAD_MS は引かない。券の取得時刻のちょうど10分前。
    fun checkTriggerAt(targetAtMs: Long, clockOffsetMs: Long = 0): Long =
        targetAtMs - clockOffsetMs - CHECK_LEAD_MS

    fun checkDue(targetAtMs: Long, clockOffsetMs: Long, deviceNowMs: Long): Boolean =
        checkTriggerAt(targetAtMs, clockOffsetMs) <= deviceNowMs

    /** 状態の変化（成功・失敗）を除いた、保存内容と実行回の目印。 */
    fun occurrence(schedule: Schedule): String =
        "${schedule.id}|${schedule.kind}|${schedule.exactAtMs}|${schedule.repeatMinutes}|" +
            "${schedule.nextRunAtMs}|${schedule.codes.joinToString(",") { it.code }}"

    fun exactOccurrence(schedule: Schedule): String = "${schedule.id}|${schedule.nextRunAtMs}"

    fun matches(schedule: Schedule?, expected: String?): Boolean =
        schedule != null && schedule.enabled && schedule.nextRunAtMs > 0 && occurrence(schedule) == expected

    data class Decision(val notify: Boolean, val checked: Boolean, val connected: Boolean)

    fun exact(checked: Boolean, connected: Boolean): Decision =
        Decision(notify = !checked && !connected, checked = true, connected = connected)

    /** 接続時だけ呼び、記録済みなら保存を繰り返さない。 */
    fun shouldRearmRepeat(previousConnected: Boolean?): Boolean = previousConnected != true

    fun repeat(previousConnected: Boolean?, connected: Boolean): Decision =
        Decision(notify = !connected && previousConnected != false, checked = true, connected = connected)
}
