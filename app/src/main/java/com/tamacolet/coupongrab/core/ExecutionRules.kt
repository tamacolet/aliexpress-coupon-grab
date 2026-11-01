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
    const val SPIN_THRESHOLD_MS = 20L

    fun coarseDelayMs(remainingMs: Long): Long =
        (remainingMs - SPIN_THRESHOLD_MS).coerceIn(0L, 100L)

    fun monotonicTargetNanos(targetEpochMs: Long, correctedNowMs: Long, currentElapsedNanos: Long): Long =
        currentElapsedNanos + (targetEpochMs - correctedNowMs) * 1_000_000L
}

class AutomationException(message: String) : Exception(message)

/** Android に依存しない再試行と待機。画面の読み直しも呼び出し側から渡す。 */
object AutomationFlow {
    suspend fun retryInvalid(retry: suspend () -> Unit, reread: () -> CouponUiState, onRetried: () -> Unit) {
        try {
            retry()
            onRetried()
        } catch (error: AutomationException) {
            if (reread() !in listOf(CouponUiState.APPLIED, CouponUiState.CONFIRMATION)) throw error
        }
    }

    suspend fun <T> applyAtTarget(
        targetAtMs: Long, prepare: suspend () -> T, await: suspend (Long) -> Unit, apply: (T) -> Unit,
    ) {
        val prepared = prepare()
        await(targetAtMs)
        apply(prepared)
    }

    suspend fun <T : Any> openDialog(open: suspend () -> T?, recover: suspend () -> Unit): T {
        repeat(3) { attempt ->
            val dialog = try {
                open()
            } catch (error: AutomationException) {
                if (attempt == 2) throw error
                null
            }
            if (dialog != null) return dialog
            recover()
        }
        throw AutomationException("プロモコードの小窓が開きませんでした")
    }

    suspend fun awaitWake(timeoutMs: Long, ready: suspend () -> Unit) {
        if (kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { ready(); true } != true) {
            throw AutomationException("画面を起こせませんでした")
        }
    }

    fun shouldScrollPromo(centerY: Int, paymentTop: Int?): Boolean =
        paymentTop != null && centerY >= paymentTop

    fun revealPromo(covered: Boolean, show: () -> Boolean, scroll: () -> Boolean): Boolean {
        val shown = show()
        val scrolled = if (covered || !shown) scroll() else false
        return shown || scrolled
    }

    fun statusChanged(previous: CodeStatus, next: CodeStatus): Boolean = previous != next
}

/** NAS の確認と端末の占有を、同じ保存処理の最新状態から決める。 */
object RunAdmission {
    fun claim(state: AppState, request: RunRequest, nowMs: Long): Pair<AppState, ScheduleRules.NasDecision?> {
        val schedule = state.schedules.firstOrNull { it.id == request.scheduleId }
            ?: return state to null
        if (!ExecutionPolicy.accepts(schedule, request, nowMs)) return state to null
        val decision = ScheduleRules.nasDecision(schedule, nowMs, state.nasBusyUntilMs,
            ScheduleRules.nextExactAtMs(state.schedules))
        val updated = when (decision) {
            ScheduleRules.NasDecision.Execute -> state.copy(busy = true)
            is ScheduleRules.NasDecision.Defer -> state.copy(schedules = state.schedules.map {
                if (it.id == schedule.id) it.copy(nextRunAtMs = nowMs + decision.delayMs) else it
            })
        }
        return updated to decision
    }
}
