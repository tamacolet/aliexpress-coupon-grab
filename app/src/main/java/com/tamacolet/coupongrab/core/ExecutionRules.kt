package com.tamacolet.coupongrab.core

/** アラーム受信時に先に保存し、Service 未起動・プロセス再作成でも要求を失わない。 */
data class RunRequest(
    val scheduleId: String,
    val kind: ScheduleKind,
)

object RunQueue {
    fun enqueue(queue: List<RunRequest>, request: RunRequest): List<RunRequest> =
        queue.filterNot { it.scheduleId == request.scheduleId } + request

    /** 同時要求は ID ごとに残し、時刻指定を繰り返しより先に選ぶ。 */
    fun next(queue: List<RunRequest>): RunRequest? = queue.minByOrNull {
        if (it.kind == ScheduleKind.EXACT) 0 else 1
    }

    fun remove(queue: List<RunRequest>, request: RunRequest): List<RunRequest> =
        queue.filterNot { it.scheduleId == request.scheduleId && it.kind == request.kind }
}

object RunFailureReducer {
    fun apply(state: AppState, scheduleId: String, detail: String): AppState = state.copy(
        schedules = state.schedules.map { schedule ->
            if (schedule.id == scheduleId) ScheduleRules.failPending(schedule, detail) else schedule
        },
    )
}

object ExecutionPolicy {
    const val MAX_EXECUTION_MS = 20 * 60_000L
    const val EXACT_STALE_TOLERANCE_MS = 5 * 60_000L

    fun accepts(schedule: Schedule, request: RunRequest, nowMs: Long): Boolean =
        schedule.enabled && schedule.kind == request.kind && schedule.id == request.scheduleId &&
            schedule.nextRunAtMs > 0 && nowMs + 1_000 >= ScheduleRules.alarmTriggerAt(schedule.kind, schedule.nextRunAtMs)

    fun isStaleExact(schedule: Schedule, nowMs: Long): Boolean =
        schedule.kind == ScheduleKind.EXACT && schedule.nextRunAtMs > 0 &&
            nowMs > schedule.nextRunAtMs + EXACT_STALE_TOLERANCE_MS
}

/** ダイアログや Widget のイベントで、直前に確認した Activity 名を上書きしない。 */
object ActivityEventReducer {
    fun reduce(currentActivity: String, packageName: String, className: String): String {
        if (packageName != ALI_PACKAGE || className.isEmpty()) return currentActivity
        val isActivity = className == CART_ACTIVITY || className == CHECKOUT_ACTIVITY ||
            className.substringAfterLast('.').endsWith("Activity")
        return if (isActivity) className else currentActivity
    }
}

object AutomationTiming {
    const val FINE_WAIT_THRESHOLD_MS = 400L
    const val SPIN_THRESHOLD_MS = 2L

    fun coarseDelayMs(remainingMs: Long): Long = when {
        remainingMs <= FINE_WAIT_THRESHOLD_MS -> 0
        else -> minOf(100L, remainingMs - FINE_WAIT_THRESHOLD_MS)
    }

    fun fineDelayMs(remainingMs: Long): Long = when {
        remainingMs <= SPIN_THRESHOLD_MS -> 0
        else -> minOf(5L, remainingMs - SPIN_THRESHOLD_MS)
    }

    fun monotonicTargetNanos(targetEpochMs: Long, correctedNowMs: Long, currentElapsedNanos: Long): Long =
        currentElapsedNanos + (targetEpochMs - correctedNowMs) * 1_000_000L
}
