package com.tamacolet.coupongrab.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

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

/** Android に依存しない結果判定と待機。画面の読み直しも呼び出し側から渡す。 */
object AutomationFlow {
    suspend fun <T : Any> waitFor(
        timeoutMs: Long, activity: () -> String?, nowMs: () -> Long,
        read: suspend () -> T?, pause: suspend () -> Unit,
    ): T {
        val started = nowMs()
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            checkSecurity(activity())
            read()?.let { return it }
            if (nowMs() - started >= timeoutMs) throw AutomationException("画面の待機がタイムアウトしました")
            pause()
        }
    }

    /** 準備中の例外でも占有状態を必ず解放する。 */
    suspend fun <T> withCleanup(action: suspend () -> T, cleanup: () -> Unit): T =
        try { action() } finally { cleanup() }

    fun checkSecurity(activity: String?) {
        if (SecurityCheckException.isSecurityScreen(activity)) throw SecurityCheckException()
    }

    inline fun <T> recoverableRead(read: () -> T): Result<T> = runCatching(read).onFailure {
        if (it is SecurityCheckException || it is CancellationException) throw it
    }

    suspend fun awaitOutcome(
        gate: CouponResultGate,
        read: () -> CouponObservation,
        nowMs: () -> Long,
        approve: suspend () -> Unit,
        pause: suspend () -> Unit,
    ): CouponUiState {
        while (true) {
            when (gate.observe(read(), nowMs())) {
                CouponResultGate.Action.APPROVE -> approve()
                CouponResultGate.Action.APPLIED -> return CouponUiState.APPLIED
                CouponResultGate.Action.USED -> return CouponUiState.USED
                CouponResultGate.Action.INVALID -> return CouponUiState.INVALID
                CouponResultGate.Action.FAIL -> throw AutomationException("確認窓の承認が完了しませんでした")
                CouponResultGate.Action.WAIT -> Unit
            }
            pause()
        }
    }

    /** 次のコードの準備より前に、遅れて現れた確認窓を必ず処理する。 */
    suspend fun settleBeforeNext(
        read: () -> CouponObservation,
        nowMs: () -> Long,
        approve: suspend () -> Unit,
        pause: suspend () -> Unit,
        gate: CouponResultGate? = null,
    ): CouponUiState? {
        val first = read()
        if (first.state == CouponUiState.USED) {
            return first.state.takeIf { gate == null || gate.observe(first, nowMs()) == CouponResultGate.Action.USED }
        }
        // 「取れた」に見えても、確認窓が遅れて出るすき間かもしれないので、門で続くのを確かめる。
        if (first.state != CouponUiState.CONFIRMATION && first.state != CouponUiState.APPLIED) return null
        var initial: CouponObservation? = first
        return awaitOutcome(gate ?: CouponResultGate(first.validation, nowMs()),
            read = { initial?.also { initial = null } ?: read() }, nowMs = nowMs, approve = approve, pause = pause)
    }

    /** 初回押下前の確認窓には帰属先がないので、安全な取り消しで閉じる。 */
    fun cancelBeforeFirstPress(hasPressed: Boolean, state: CouponUiState, cancel: () -> Unit): Boolean {
        if (hasPressed || state != CouponUiState.CONFIRMATION) return false
        cancel()
        return true
    }

    fun completedStatus(state: CouponUiState): CodeStatus? = when (state) {
        CouponUiState.APPLIED -> CodeStatus.APPLIED
        CouponUiState.USED -> CodeStatus.USED
        else -> null
    }

    suspend fun <T> applyAtTarget(
        targetAtMs: Long, prepare: suspend () -> T, await: suspend (Long) -> Unit,
        snapshotLeadMs: Long = 0, snapshot: (T) -> Unit = {}, apply: (T) -> Unit,
    ) {
        val prepared = prepare()
        if (snapshotLeadMs > 0) await(targetAtMs - snapshotLeadMs)
        snapshot(prepared)
        await(targetAtMs)
        apply(prepared)
    }

    /** 初回押下後の回復は承認して結果待ち。取り消しは後片付けで使う。 */
    suspend fun resolveConfirmation(cleaningUp: Boolean, settle: suspend () -> Unit, cancel: suspend () -> Unit) {
        if (cleaningUp) cancel() else settle()
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
    fun claim(state: AppState, request: RunRequest, nowMs: Long, elapsedMs: Long = 0): Pair<AppState, ScheduleRules.NasDecision?> {
        val schedule = state.schedules.firstOrNull { it.id == request.scheduleId }
            ?: return state to null
        if (!ExecutionPolicy.accepts(schedule, request, nowMs)) return state to null
        if (state.securityHoldSinceMs != null) {
            val held = state.copy(schedules = state.schedules.map {
                if (it.id == schedule.id && it.kind == ScheduleKind.REPEAT && it.enabled) {
                    it.copy(nextRunAtMs = nowMs + it.repeatMinutes * 60_000L)
                } else it
            }).append(HistoryEntry(nowMs, elapsedMs, "security_hold_skip", schedule.id, detail = SECURITY_CHECK_REASON))
            return held to ScheduleRules.NasDecision.SecurityHold
        }
        val decision = ScheduleRules.nasDecision(schedule, nowMs, state.nasBusyUntilMs,
            ScheduleRules.nextExactAtMs(state.schedules))
        val updated = when (decision) {
            ScheduleRules.NasDecision.Execute -> state.copy(busy = true)
            ScheduleRules.NasDecision.SecurityHold -> state
            is ScheduleRules.NasDecision.Defer -> state.copy(schedules = state.schedules.map {
                if (it.id == schedule.id) it.copy(nextRunAtMs = nowMs + decision.delayMs) else it
            })
        }
        return updated to decision
    }
}

/** 確認の取り消し・実行中は、予定も待機要求も変更しない。 */
object ScheduleDeletion {
    fun apply(state: AppState, scheduleId: String, confirmed: Boolean): AppState =
        if (!confirmed || state.busy) state else state.copy(
            schedules = state.schedules.filterNot { it.id == scheduleId },
            pendingRuns = state.pendingRuns.filterNot { it.scheduleId == scheduleId },
        )
}
