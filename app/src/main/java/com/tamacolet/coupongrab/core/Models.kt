package com.tamacolet.coupongrab.core

import java.util.UUID

const val HISTORY_LIMIT = 100
const val ALARM_LEAD_MS = 60_000L
const val NAS_RETRY_MS = 30_000L

enum class ScheduleKind { EXACT, REPEAT }
enum class CodeStatus { WAITING, APPLIED, USED, FAILURE }
enum class ClockSource { SNTP, HTTP_DATE, NONE }

data class CouponCode(
    val code: String,
    val status: CodeStatus = CodeStatus.WAITING,
    val detail: String = "",
)

data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val kind: ScheduleKind = ScheduleKind.REPEAT,
    val exactAtMs: Long? = null,
    val repeatMinutes: Int = 5,
    val nextRunAtMs: Long,
    val codes: List<CouponCode>,
)

data class TimeCorrection(
    val source: ClockSource = ClockSource.NONE,
    val offsetMs: Long = 0,
    val anchorElapsedMs: Long = 0,
    val measuredAtMs: Long = 0,
    val rttMs: Long = 0,
) {
    fun now(elapsedRealtimeMs: Long): Long =
        measuredAtMs + offsetMs + (elapsedRealtimeMs - anchorElapsedMs)
}

data class HistoryEntry(
    val atMs: Long,
    val elapsedRealtimeMs: Long,
    val event: String,
    val scheduleId: String = "",
    val code: String = "",
    val detail: String = "",
)

data class AppState(
    val schedules: List<Schedule> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val correction: TimeCorrection = TimeCorrection(),
    val nasBusyUntilMs: Long = 0,
    val busy: Boolean = false,
    val pendingRuns: List<RunRequest> = emptyList(),
) {
    fun append(entry: HistoryEntry): AppState =
        copy(history = (history + entry).takeLast(HISTORY_LIMIT))
}

object CodeValidation {
    private val pattern = Regex("^[A-Za-z0-9]{3,32}$")

    fun isValid(code: String): Boolean = pattern.matches(code)

    fun parseMultiline(text: String): List<String> {
        val codes = text.lines().filter { it.isNotEmpty() }
        require(codes.isNotEmpty()) { "コードを1行以上入力してください" }
        require(codes.all(::isValid)) { "コードはASCII英数字3〜32文字で、1行に1つ入力してください" }
        require(codes.distinct().size == codes.size) { "同じコードが重複しています" }
        return codes
    }

    fun mergeStatuses(text: String, previous: List<CouponCode>): List<CouponCode> {
        val old = previous.associateBy { it.code }
        return parseMultiline(text).map { old[it] ?: CouponCode(it) }
    }
}

object ScheduleRules {
    fun pendingCodes(schedule: Schedule): List<CouponCode> = schedule.codes.filter {
        it.status == CodeStatus.WAITING ||
            (schedule.kind == ScheduleKind.REPEAT && it.status == CodeStatus.FAILURE)
    }

    fun isComplete(schedule: Schedule): Boolean = schedule.codes.isNotEmpty() &&
        schedule.codes.all {
            when (schedule.kind) {
                ScheduleKind.EXACT -> it.status != CodeStatus.WAITING
                ScheduleKind.REPEAT -> it.status == CodeStatus.APPLIED || it.status == CodeStatus.USED
            }
        }

    fun alarmTriggerAt(
        kind: ScheduleKind,
        targetAtMs: Long,
        clockOffsetMs: Long = 0,
    ): Long = targetAtMs - clockOffsetMs - if (kind == ScheduleKind.EXACT) ALARM_LEAD_MS else 0

    /** 最新の永続状態にだけ失敗を書き、成功・使用済みを絶対に後退させない。 */
    fun failPending(schedule: Schedule, detail: String): Schedule = schedule.copy(
        codes = schedule.codes.map { coupon ->
            if (pendingCodes(schedule).any { it.code == coupon.code }) {
                coupon.copy(status = CodeStatus.FAILURE, detail = detail)
            } else coupon
        },
    )

    fun afterRun(schedule: Schedule, nowMs: Long): Schedule {
        if (isComplete(schedule)) {
            return schedule.copy(enabled = false, nextRunAtMs = 0)
        }
        return when (schedule.kind) {
            ScheduleKind.EXACT -> schedule.copy(enabled = false, nextRunAtMs = 0)
            ScheduleKind.REPEAT -> schedule.copy(
                nextRunAtMs = nowMs + schedule.repeatMinutes * 60_000L,
            )
        }
    }

    sealed interface NasDecision {
        data object Execute : NasDecision
        data class Defer(val delayMs: Long = NAS_RETRY_MS) : NasDecision
    }

    fun nasDecision(schedule: Schedule, nowMs: Long, nasBusyUntilMs: Long, nextExactAtMs: Long = 0): NasDecision {
        if (schedule.kind == ScheduleKind.EXACT) return NasDecision.Execute
        val exactSoon = nextExactAtMs > 0 && nextExactAtMs <= nowMs + 3 * 60_000L
        return if (nasBusyUntilMs > nowMs || exactSoon) NasDecision.Defer() else NasDecision.Execute
    }

    fun nextExactAtMs(schedules: List<Schedule>): Long = schedules
        .filter { it.enabled && it.kind == ScheduleKind.EXACT && it.nextRunAtMs > 0 }
        .minOfOrNull { it.nextRunAtMs } ?: 0
}
