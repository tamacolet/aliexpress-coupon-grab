package com.tamacolet.coupongrab.automation

import com.tamacolet.coupongrab.core.AutomationDiagnostics
import com.tamacolet.coupongrab.core.CodeRotation
import com.tamacolet.coupongrab.core.SecurityCheckException
import com.tamacolet.coupongrab.core.CouponObservation
import com.tamacolet.coupongrab.core.CouponResultGate
import com.tamacolet.coupongrab.core.AutomationFlow
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.tamacolet.coupongrab.core.ALI_PACKAGE
import com.tamacolet.coupongrab.core.AutomationTiming
import com.tamacolet.coupongrab.core.CART_ACTIVITY
import com.tamacolet.coupongrab.core.BUILDING_ACTIVITY
import com.tamacolet.coupongrab.core.CART_URL
import com.tamacolet.coupongrab.core.CHECKOUT_ACTIVITY
import com.tamacolet.coupongrab.core.CodeStatus
import com.tamacolet.coupongrab.core.CodeValidation
import com.tamacolet.coupongrab.core.CouponCode
import com.tamacolet.coupongrab.core.CouponStateDecider
import com.tamacolet.coupongrab.core.CouponUiState
import com.tamacolet.coupongrab.service.WakeActivity
import com.tamacolet.coupongrab.time.CorrectedClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class ExactPreemptionException : Exception("時刻指定の実行を優先します")

class CouponAutomation(
    private val context: Context,
    private val clock: CorrectedClock,
    private val onEvent: (event: String, code: String, detail: String) -> Unit,
    private val onCode: (code: String, status: CodeStatus, detail: String) -> Unit,
    private val shouldYieldForExact: () -> Boolean = { false },
) {
    private var checkoutAttempted = false
    private var cleaningUp = false
    private var hasPressed = false
    private var rotation: CodeRotation? = null
    private class BudgetExpired : Exception()
    private class ConfirmationAppeared : Exception()

    private fun yieldIfExactNeeded() {
        if (!cleaningUp && shouldYieldForExact()) throw ExactPreemptionException()
        if (!cleaningUp && rotation?.expired(SystemClock.elapsedRealtime()) == true) throw BudgetExpired()
    }

    suspend fun execute(codes: List<CouponCode>, targetAtMs: Long) {
        require(codes.isNotEmpty())
        val accessibility = CouponAccessibilityService.awaitConnected()
        try {
            try {
                CouponAccessibilityService.launchWakeActivity(context)
            } catch (_: Exception) {
                WakeActivity.launch(context)
            }
            WakeActivity.awaitReady()
            val launch = Intent(Intent.ACTION_VIEW, Uri.parse(CART_URL)).apply {
                setPackage(ALI_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) // 0x10008000
            }
            WakeActivity.launchCart(accessibility, launch)
            prepareCart(accessibility)
            val cycle = CodeRotation(codes.map { it.code })
            rotation = cycle
            var previousCode: String? = null
            while (cycle.pending().isNotEmpty()) {
                var attempting: String? = null
                try {
                    withinBudget(cycle) {
                        yieldIfExactNeeded()
                        previousCode?.let { previous ->
                            settleConfirmation(accessibility, previous)?.let { state ->
                                val status = AutomationFlow.completedStatus(state)
                                if (status != null && previous in cycle.pending()) {
                                    cycle.complete(previous, status)
                                    onCode(previous, status, status.name)
                                }
                            }
                        }
                        val code = cycle.next(SystemClock.elapsedRealtime()) ?: throw BudgetExpired()
                        attempting = code
                        require(CodeValidation.isValid(code)) { "コード形式が不正です" }
                        val status = applyOnce(accessibility, code, targetAtMs, cycle) { previousCode = code }
                        previousCode = code
                        cycle.complete(code, status)
                        if (status != null) onCode(code, status, status.name)
                    }
                } catch (_: BudgetExpired) {
                    break
                } catch (_: ConfirmationAppeared) {
                    // 初回押下前なら取り消して準備し直す。前回の券があれば次の周で承認する。
                    if (previousCode == null) cancelInitialConfirmation(accessibility)
                } catch (preemption: ExactPreemptionException) {
                    throw preemption
                } catch (check: SecurityCheckException) {
                    throw check
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val failed = attempting ?: previousCode?.takeIf { it in cycle.pending() }
                    val detail = safeDetail(accessibility, error, codes)
                    onEvent("code_error", failed.orEmpty(), detail)
                    try {
                        val recovered = withinBudget(cycle) {
                            recoverCheckout(accessibility, previousCode) { state ->
                                val previous = checkNotNull(previousCode)
                                val status = AutomationFlow.completedStatus(state)
                                if (status != null && cycle.finishIfPending(previous, status)) onCode(previous, status, status.name)
                            }
                        }
                        // 回復中に適用済みと分かった券を失敗へ戻さない。
                        if (failed != null && cycle.finishIfPending(failed, CodeStatus.FAILURE)) onCode(failed, CodeStatus.FAILURE, detail)
                        if (!recovered) throw AutomationException(detail)
                    } catch (_: BudgetExpired) { break }
                }
            }
            cycle.pending().forEach { code ->
                onCode(code, CodeStatus.FAILURE, "全コード共通の制限時間に達しました")
                cycle.complete(code, CodeStatus.FAILURE)
            }
        } catch (preemption: ExactPreemptionException) {
            throw preemption
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw AutomationException(safeDetail(accessibility, error, codes))
        } finally {
            cleaningUp = true
            // キャンセル・タイムアウトでも、ダイアログを閉じてから会計を中止する。
            withContext(NonCancellable) {
                try {
                    runCatching {
                        withTimeout(30_000) { cleanup(accessibility) }
                    }.onFailure { runCatching { onEvent("cleanup_failure", "", safeDetail(accessibility, it, codes)) } }
                } finally {
                    WakeActivity.dismiss()
                }
            }
        }
    }

    private suspend fun prepareCart(service: CouponAccessibilityService) {
        waitFor(20_000) { runCatching { service.root(CART_ACTIVITY) }.getOrNull() }
        var checkoutRoot = runCatching {
            waitFor(3_000) {
                val root = runCatching { service.root(CART_ACTIVITY) }.getOrNull() ?: return@waitFor null
                if (root.byId("cart_empty") != null) throw AutomationException("カートが空です")
                val checkout = root.byId("bt_checkout") ?: return@waitFor null
                root.takeIf { checkoutText(checkout.text?.toString().orEmpty()) > 0 }
            }
        }.onFailure {
            if (it is ExactPreemptionException || it is CancellationException) throw it
        }.getOrNull()
        if (checkoutRoot == null) {
            val current = runCatching { service.root(CART_ACTIVITY) }.getOrNull()
            if (current?.byId("cart_empty") != null) throw AutomationException("カートが空です")
            val selectAll = current?.byId("ck_footer_select_all")
            val count = checkoutText(current?.byId("bt_checkout")?.text?.toString().orEmpty())
            if (selectAll != null && !selectAll.isChecked && count == 0) {
                selectAll.safeClick { service.root(CART_ACTIVITY) }
                checkoutRoot = waitFor(5_000) {
                    val root = runCatching { service.root(CART_ACTIVITY) }.getOrNull() ?: return@waitFor null
                    root.takeIf { checkoutText(root.byId("bt_checkout")?.text?.toString().orEmpty()) > 0 }
                }
            }
        }
        val checkout = checkoutRoot?.byId("bt_checkout")
            ?: throw AutomationException("カートが空か、購入手続きへ進めません")
        checkoutAttempted = true
        checkout.safeClick { service.root(CART_ACTIVITY) }
        waitForPromo(service)
        // WakeActivity はカートを実際に確認した後まで終了しない。最後の cleanup まで画面維持も継続する。
    }

    private suspend fun <T> withinBudget(cycle: CodeRotation, action: suspend () -> T): T {
        val remaining = cycle.remainingMs(SystemClock.elapsedRealtime()) ?: return action()
        if (remaining <= 0) throw BudgetExpired()
        val completed = withTimeoutOrNull(remaining) { Result.success(action()) } ?: throw BudgetExpired()
        return completed.getOrThrow()
    }

    private fun observation(service: CouponAccessibilityService, code: String): CouponObservation {
        yieldIfExactNeeded()
        return CouponObservation.from(service.root(CHECKOUT_ACTIVITY).flatten().map { it.shallowSnapshot() }, code)
    }

    private suspend fun approve(service: CouponAccessibilityService) {
        currentCoroutineContext().ensureActive()
        yieldIfExactNeeded()
        val approve = service.root(CHECKOUT_ACTIVITY).byText("承認")
            ?: throw AutomationException("承認ボタンが見つかりません")
        approve.safeClick { service.root(CHECKOUT_ACTIVITY) }
    }

    private suspend fun settleConfirmation(service: CouponAccessibilityService, code: String): CouponUiState? =
        AutomationFlow.settleBeforeNext(read = { observation(service, code) },
            nowMs = { SystemClock.elapsedRealtime() }, approve = { approve(service) }, pause = { delay(100) })

    private suspend fun applyOnce(
        service: CouponAccessibilityService,
        code: String,
        targetAtMs: Long,
        cycle: CodeRotation,
        onPressed: () -> Unit,
    ): CodeStatus? {
        var before: CouponObservation? = null
        var gate: CouponResultGate? = null
        AutomationFlow.applyAtTarget(targetAtMs, prepare = {
            openPromoAndSet(service, code)
            prepareApplyTarget(service, code)
        }, await = {
            waitUntil(it)
            delay(cycle.waitBeforePressMs(SystemClock.elapsedRealtime()))
        }, snapshotLeadMs = 250, snapshot = {
            // IPC は最終待機の前。250ms の余裕で読み、指定時刻後には追加しない。
            before = observation(service, code)
            if (before?.state == CouponUiState.CONFIRMATION) throw ConfirmationAppeared()
        }, apply = {
            val validation = checkNotNull(before).validation
            clickApply(it, code, "apply") {
                val pressedAt = SystemClock.elapsedRealtime()
                hasPressed = true
                cycle.pressed(pressedAt)
                gate = CouponResultGate(validation, pressedAt)
                onPressed()
            }
        })
        return withinBudget(cycle) {
            when (AutomationFlow.awaitOutcome(checkNotNull(gate), read = { observation(service, code) },
                nowMs = { SystemClock.elapsedRealtime() }, approve = { approve(service) }, pause = { delay(100) })) {
                CouponUiState.APPLIED -> CodeStatus.APPLIED
                CouponUiState.USED -> CodeStatus.USED
                else -> {
                    // この試行だけを履歴で完了。予定の券は待機のまま巡回する。
                    onEvent("code_failure", code, "適用できません")
                    null
                }
            }
        }
    }

    private fun safeDetail(service: CouponAccessibilityService, error: Throwable, codes: List<CouponCode>): String =
        AutomationDiagnostics.detail(error,
            runCatching { service.root().flatten().map { it.shallowSnapshot() } }.getOrDefault(emptyList()),
            codes.map { it.code })

    private suspend fun cancelInitialConfirmation(service: CouponAccessibilityService): Boolean {
        if (hasPressed) return false
        val cancelled = AutomationFlow.cancelBeforeFirstPress(false, observation(service, "").state) {
            val cancel = service.root(CHECKOUT_ACTIVITY).byText("取り消し")
                ?: throw AutomationException("取り消しボタンが見つかりません")
            cancel.safeClick { service.root(CHECKOUT_ACTIVITY) }
        }
        if (cancelled) waitFor(2_000) {
            val root = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return@waitFor null
            root.takeUnless {
                CouponStateDecider.decide(it.flatten().map { node -> node.shallowSnapshot() }, "") == CouponUiState.CONFIRMATION
            }
        }
        return cancelled
    }

    private fun rejectConfirmation(service: CouponAccessibilityService) {
        if (observation(service, "").state == CouponUiState.CONFIRMATION) throw ConfirmationAppeared()
    }

    private fun prepareApplyTarget(
        service: CouponAccessibilityService,
        code: String,
    ): PreparedClick {
        rejectConfirmation(service)
        val root = service.root(CHECKOUT_ACTIVITY)
        val input = root.byId("et_coupon_code_input_view")
            ?: throw AutomationException("コード入力欄が見つかりません")
        if (input.text?.toString() != code) throw AutomationException("入力コードの読み戻しが一致しません")
        val apply = root.byId("bt_update_coupon_code_ok")
            ?: throw AutomationException("適用ボタンが見つかりません")
        // 待機前に対象を準備し、押す入口でも最新の文字・IDと支払い拒否を確認する。
        return apply.prepareSafeClick { service.root(CHECKOUT_ACTIVITY) }
    }

    private fun clickApply(prepared: PreparedClick, code: String, event: String, beforeDispatch: () -> Unit) {
        var beforeMs = 0L
        val clicked = prepared.perform { beforeDispatch(); beforeMs = clock.now() }
        val correctedAtMs = clock.now()
        if (!clicked) throw AutomationException("クリックに失敗しました")
        // 実際の ACTION_CLICK 直後に補正時刻を確保し、その後で永続化コールバックへ渡す。
        onEvent(event, code, "correctedAtMs=$correctedAtMs correctedBeforeMs=$beforeMs")
    }

    private suspend fun openPromoAndSet(service: CouponAccessibilityService, code: String) {
        rejectConfirmation(service)
        val existing = service.root(CHECKOUT_ACTIVITY).takeIf { it.byId("et_coupon_code_input_view") != null }
        val dialog = existing ?: AutomationFlow.openDialog(open = {
            waitForPromo(service).safeClick { rejectConfirmation(service); service.root(CHECKOUT_ACTIVITY) }
            val opened: Any = waitFor(8_000) {
                if (service.currentActivity == BUILDING_ACTIVITY) return@waitFor BUILDING_ACTIVITY
                val root = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return@waitFor null
                rejectConfirmation(service)
                root.takeIf {
                    root.byId("tv_dialog_title")?.text?.toString() == "プロモコード" &&
                        root.byId("et_coupon_code_input_view") != null
                }
            }
            opened as? AccessibilityNodeInfo
        }, recover = { leaveBuildingPage(service) })
        // 前回の文字が残っていると「適用」が押せないことがあるので、空にしてから入れる。
        // 消すボタン（×）は押した後で遅れて効き、入れた文字まで消すので使わない。
        val input = dialog.byId("et_coupon_code_input_view")
            ?: throw AutomationException("コード入力欄が見つかりません")
        // 人が欄を触った時と同じく、先に入力欄へ合わせてから入れる（押す操作ではない）。
        rejectConfirmation(service)
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        input.setVerifiedText("") { rejectConfirmation(service); service.root(CHECKOUT_ACTIVITY) }
        input.setVerifiedText(code) { rejectConfirmation(service); service.root(CHECKOUT_ACTIVITY) }
        waitForApplyReady(service, code)
    }

    /**
     * プロモコード行が安全に押せる位置に出るまで待って返す。
     * 開いた直後は読み込み中のことがあり、支払い方法の欄が長いと行が画面の下端で支払い欄と重なる。
     * 1秒ごとに、行が見えていれば画面の中へ出し（ACTION_SHOW_ON_SCREEN）、見えなければ下へ送る（計4回まで）。
     */
    private suspend fun waitForPromo(service: CouponAccessibilityService): AccessibilityNodeInfo {
        var polls = 0
        var moves = 0
        var lastBounds: Rect? = null
        return try {
            waitFor(20_000) {
                val root = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return@waitFor null
                if (cancelInitialConfirmation(service)) return@waitFor null
                rejectConfirmation(service)
                val promo = root.byText("プロモコード")
                // 動かした直後は画面が流れている途中なので、位置が前回と同じ（止まった）時だけ返す。
                val bounds = promo?.let { Rect().also(it::getBoundsInScreen) }
                val settled = bounds != null && bounds == lastBounds
                lastBounds = bounds
                val paymentTop = root.byId("btn_container")?.let { Rect().also(it::getBoundsInScreen).top }
                val covered = bounds != null && AutomationFlow.shouldScrollPromo(bounds.centerY(), paymentTop)
                if (settled && !covered && runCatching { promo!!.prepareSafeClick() }.isSuccess) return@waitFor promo
                if (++polls % 10 == 0 && moves < 4) {
                    val moved = AutomationFlow.revealPromo(covered, show = {
                        promo?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) ?: false
                    }, scroll = { root.scrollForward { service.root(CHECKOUT_ACTIVITY) } })
                    if (moved) moves++
                }
                null
            }
        } catch (_: AutomationException) {
            throw AutomationException("プロモコード欄が見つかりません")
        }
    }

    /**
     * 読み込み途中にプロモコード行を押すと、小窓ではなくウェブの「工事中」画面
     * （promocode_v3.html）が開くことがある（7aで確認）。戻って購入手続きへ帰る。
     */
    private suspend fun leaveBuildingPage(service: CouponAccessibilityService) {
        if (service.currentActivity != BUILDING_ACTIVITY) return
        onEvent("building_page", "", "")
        service.backFromAli()
        waitFor(5_000) { runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() }
    }

    /** 入力欄がそのコードになり、「適用」が押せる状態になるまで待つ。 */
    private suspend fun waitForApplyReady(service: CouponAccessibilityService, code: String) {
        try {
            waitFor(2_000) {
                val root = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return@waitFor null
                val text = root.byId("et_coupon_code_input_view")?.text?.toString()
                val enabled = root.byId("bt_update_coupon_code_ok")?.isEnabled
                rejectConfirmation(service)
                root.takeIf { text == code && enabled == true }
            }
        } catch (_: AutomationException) {
            throw AutomationException("「適用」が押せる状態になりません")
        }
    }

    private suspend fun closeDialog(
        service: CouponAccessibilityService,
        previousCode: String? = null,
        onSettled: (CouponUiState) -> Unit = {},
    ) {
        var root = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return
        if (CouponStateDecider.decide(root.flatten().map { it.shallowSnapshot() }, "") == CouponUiState.CONFIRMATION) {
            AutomationFlow.resolveConfirmation(cleaningUp,
                settle = {
                    val code = previousCode ?: throw AutomationException("確認画面の対象コードが不明です")
                    settleConfirmation(service, code)?.let(onSettled)
                }, cancel = {
                    val cancel = root.byText("取り消し") ?: throw AutomationException("取り消しボタンが見つかりません")
                    cancel.safeClick { service.root(CHECKOUT_ACTIVITY) }
                })
            root = waitFor(2_000) {
                val current = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return@waitFor null
                current.takeUnless {
                    CouponStateDecider.decide(it.flatten().map { node -> node.shallowSnapshot() }, "") == CouponUiState.CONFIRMATION
                }
            }
        }
        if (!dialogVisible(root)) return
        val close = root.byId("button_close")
        if (close != null) {
            close.safeClick { service.root(CHECKOUT_ACTIVITY) }
        } else {
            // 戻る操作はダイアログを閉じるためだけに使い、支払い操作の代替にはしない。
            service.backFromAli()
        }
        waitFor(2_000) {
            val current = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return@waitFor null
            current.takeUnless(::dialogVisible)
        }
    }

    private suspend fun recoverCheckout(
        service: CouponAccessibilityService,
        previousCode: String?,
        onSettled: (CouponUiState) -> Unit,
    ): Boolean = try {
        // 例外の直後に結果だけが届いた場合も、前の券に帰属させる。
        previousCode?.let { settleConfirmation(service, it)?.let(onSettled) }
        closeDialog(service, previousCode, onSettled)
        val root = service.root(CHECKOUT_ACTIVITY)
        !dialogVisible(root)
    } catch (expired: BudgetExpired) {
        throw expired
    } catch (preemption: ExactPreemptionException) {
        throw preemption
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun cleanup(service: CouponAccessibilityService) {
        if (!checkoutAttempted) return
        runCatching { service.root() }.getOrNull()
            ?: throw AutomationException("後片付け中に対象外のアプリが前面になりました")
        leaveBuildingPage(service)
        if (service.currentActivity == CART_ACTIVITY) {
            service.root(CART_ACTIVITY)
            return
        }
        service.root(CHECKOUT_ACTIVITY)
        // 窓が閉じる途中の画面取得失敗でも、戻る・会計中止は続ける。
        runCatching { closeDialog(service) }
        if (runCatching { service.root(CART_ACTIVITY) }.isSuccess) return
        // 戻るの1回目はキーボードを閉じるだけのことがあるので、カートに戻るまで最大3回くり返す。
        repeat(3) {
            service.backFromAli()
            val cancel = try {
                waitFor(3_000) {
                    val root = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return@waitFor null
                    val title = root.byId("tv_title")?.text?.toString().orEmpty()
                    if (title.contains("会計手続きを中止しますか")) root.byText("中止する") else null
                }
            } catch (_: AutomationException) {
                null // キーボードが閉じただけ、または確認なしでカートへ戻る画面版。
            }
            cancel?.safeClick { service.root(CHECKOUT_ACTIVITY) }
            val backAtCart = try {
                waitFor(if (cancel != null) 10_000 else 1_000) { runCatching { service.root(CART_ACTIVITY) }.getOrNull() }
                true
            } catch (_: AutomationException) {
                false
            }
            if (backAtCart) return
        }
        throw AutomationException("後片付けでカートへ戻れませんでした")
    }

    private fun dialogVisible(root: AccessibilityNodeInfo): Boolean =
        root.byId("et_coupon_code_input_view") != null ||
            root.byId("tv_dialog_title")?.text?.toString() == "プロモコード" ||
            CouponStateDecider.decide(root.flatten().map { it.shallowSnapshot() }, "") == CouponUiState.CONFIRMATION

    private suspend fun waitUntil(targetAtMs: Long) {
        while (true) {
            currentCoroutineContext().ensureActive()
            yieldIfExactNeeded()
            val delayMs = AutomationTiming.coarseDelayMs(targetAtMs - clock.now())
            if (delayMs == 0L) break
            delay(delayMs)
        }
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val targetNanos = AutomationTiming.monotonicTargetNanos(targetAtMs, clock.now(), nowNanos)
        var checks = 0
        while (SystemClock.elapsedRealtimeNanos() < targetNanos) {
            if (++checks % 256 == 0) { currentCoroutineContext().ensureActive(); yieldIfExactNeeded() }
        }
    }

    private fun checkoutText(text: String): Int {
        val match = Regex("購入手続きへ\\s*\\((\\d+)\\)").find(text) ?: return -1
        return match.groupValues[1].toIntOrNull() ?: -1
    }

    private suspend fun <T : Any> waitFor(timeoutMs: Long, read: suspend () -> T?): T {
        val started = SystemClock.elapsedRealtime()
        while (true) {
            currentCoroutineContext().ensureActive()
            yieldIfExactNeeded()
            read()?.let { return it }
            if (SystemClock.elapsedRealtime() - started >= timeoutMs) {
                throw AutomationException("画面の待機がタイムアウトしました")
            }
            delay(100)
        }
    }
}
