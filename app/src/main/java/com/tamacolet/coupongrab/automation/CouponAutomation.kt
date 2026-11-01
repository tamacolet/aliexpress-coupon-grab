package com.tamacolet.coupongrab.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.tamacolet.coupongrab.core.ALI_PACKAGE
import com.tamacolet.coupongrab.core.AutomationTiming
import com.tamacolet.coupongrab.core.CART_ACTIVITY
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

    private fun yieldIfExactNeeded() {
        if (!cleaningUp && shouldYieldForExact()) throw ExactPreemptionException()
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
            for ((index, coupon) in codes.withIndex()) {
                yieldIfExactNeeded()
                val status = try {
                    require(CodeValidation.isValid(coupon.code)) { "コード形式が不正です" }
                    applyWithRetries(accessibility, coupon.code, index == 0, targetAtMs)
                } catch (preemption: ExactPreemptionException) {
                    throw preemption
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // 直前までの APPLIED / USED は永続済み。このコードだけを失敗にする。
                    onCode(coupon.code, CodeStatus.FAILURE, error.message.orEmpty())
                    onEvent("code_error", coupon.code, error.message.orEmpty())
                    if (!recoverCheckout(accessibility)) throw error
                    null
                }
                if (status != null) {
                    onCode(coupon.code, status, status.name)
                    try {
                        closeDialog(accessibility)
                    } catch (error: Exception) {
                        if (!recoverCheckout(accessibility)) throw error
                    }
                }
            }
        } finally {
            cleaningUp = true
            // キャンセル・タイムアウトでも、ダイアログを閉じてから会計を中止する。
            withContext(NonCancellable) {
                try {
                    runCatching {
                        withTimeout(15_000) { cleanup(accessibility) }
                    }.onFailure { runCatching { onEvent("cleanup_failure", "", it.message.orEmpty()) } }
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
        var checkoutScreen = waitFor(20_000) {
            runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull()
        }
        repeat(3) {
            if (checkoutScreen.byText("プロモコード") != null) return@repeat
            if (!checkoutScreen.scrollForward { service.root(CHECKOUT_ACTIVITY) }) return@repeat
            delay(400)
            checkoutScreen = waitFor(2_000) {
                runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull()
            }
        }
        if (checkoutScreen.byText("プロモコード") == null) {
            throw AutomationException("プロモコード欄が見つかりません")
        }
        // WakeActivity はカートを実際に確認した後まで終了しない。最後の cleanup まで画面維持も継続する。
    }

    private suspend fun applyWithRetries(
        service: CouponAccessibilityService,
        code: String,
        first: Boolean,
        targetAtMs: Long,
    ): CodeStatus {
        openPromoAndSet(service, code)
        var preparedApply = prepareApplyTarget(service, code)
        if (first) waitUntil(targetAtMs)
        val started = SystemClock.elapsedRealtime()
        clickApply(preparedApply, code, "apply")
        var approved = false
        while (SystemClock.elapsedRealtime() - started <= 15_000) {
            yieldIfExactNeeded()
            val nodes = service.root(CHECKOUT_ACTIVITY).flatten().filter { it.isVisibleToUser }.map { it.snapshot() }
            when (CouponStateDecider.decide(nodes, code)) {
                CouponUiState.CONFIRMATION -> {
                    if (!approved) {
                        val approve = service.root(CHECKOUT_ACTIVITY).byText("承認")
                            ?: throw AutomationException("承認ボタンが見つかりません")
                        approve.safeClick { service.root(CHECKOUT_ACTIVITY) }
                        approved = true
                    }
                    delay(100)
                }
                CouponUiState.APPLIED -> return CodeStatus.APPLIED
                CouponUiState.USED -> return CodeStatus.USED
                CouponUiState.INVALID -> {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (elapsed > 15_000 - 500) break
                    delay(500)
                    // 同じダイアログ・同じ検証済みコードの適用ボタンだけを 500ms ごとに再押しする。
                    val root = service.root(CHECKOUT_ACTIVITY)
                    val currentInput = root.byId("et_coupon_code_input_view")
                    if (currentInput == null) {
                        openPromoAndSet(service, code)
                    } else if (currentInput.text?.toString() != code) {
                        root.byId("fl_coupon_code_clear_icon")?.safeClick { service.root(CHECKOUT_ACTIVITY) }
                        currentInput.setVerifiedText(code) { service.root(CHECKOUT_ACTIVITY) }
                        waitFor(2_000) {
                            runCatching {
                                service.root(CHECKOUT_ACTIVITY).byId("et_coupon_code_input_view")
                                    ?.takeIf { it.text?.toString() == code }
                            }.getOrNull()
                        }
                    }
                    preparedApply = prepareApplyTarget(service, code)
                    clickApply(preparedApply, code, "apply_retry")
                }
                else -> delay(100)
            }
        }
        return CodeStatus.FAILURE
    }

    private fun prepareApplyTarget(
        service: CouponAccessibilityService,
        code: String,
    ): PreparedClick {
        val root = service.root(CHECKOUT_ACTIVITY)
        val input = root.byId("et_coupon_code_input_view")
            ?: throw AutomationException("コード入力欄が見つかりません")
        if (input.text?.toString() != code) throw AutomationException("入力コードの読み戻しが一致しません")
        val apply = root.byId("bt_update_coupon_code_ok")
            ?: throw AutomationException("適用ボタンが見つかりません")
        // 待機前に対象を準備し、押す入口でも最新の文字・IDと支払い拒否を確認する。
        return apply.prepareSafeClick { service.root(CHECKOUT_ACTIVITY) }
    }

    private fun clickApply(prepared: PreparedClick, code: String, event: String) {
        var beforeMs = 0L
        val clicked = prepared.perform { beforeMs = clock.now() }
        val correctedAtMs = clock.now()
        if (!clicked) throw AutomationException("クリックに失敗しました")
        // 実際の ACTION_CLICK 直後に補正時刻を確保し、その後で永続化コールバックへ渡す。
        onEvent(event, code, "correctedAtMs=$correctedAtMs correctedBeforeMs=$beforeMs")
    }

    private suspend fun openPromoAndSet(service: CouponAccessibilityService, code: String) {
        val promo = service.root(CHECKOUT_ACTIVITY).byText("プロモコード")
            ?: throw AutomationException("プロモコード欄が見つかりません")
        promo.safeClick { service.root(CHECKOUT_ACTIVITY) }
        val dialog = waitFor(8_000) {
            val root = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return@waitFor null
            root.takeIf {
                root.byId("tv_dialog_title")?.text?.toString() == "プロモコード" &&
                    root.byId("et_coupon_code_input_view") != null
            }
        }
        dialog.byId("fl_coupon_code_clear_icon")?.safeClick { service.root(CHECKOUT_ACTIVITY) }
        val input = service.root(CHECKOUT_ACTIVITY).byId("et_coupon_code_input_view")
            ?: throw AutomationException("コード入力欄が見つかりません")
        input.setVerifiedText(code) { service.root(CHECKOUT_ACTIVITY) }
        waitFor(2_000) {
            val current = runCatching {
                service.root(CHECKOUT_ACTIVITY).byId("et_coupon_code_input_view")
            }.getOrNull() ?: return@waitFor null
            current.takeIf { it.text?.toString() == code }
        }
    }

    private suspend fun closeDialog(service: CouponAccessibilityService) {
        val root = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return
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

    private suspend fun recoverCheckout(service: CouponAccessibilityService): Boolean = try {
        closeDialog(service)
        val root = service.root(CHECKOUT_ACTIVITY)
        !dialogVisible(root)
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
        if (service.currentActivity == CART_ACTIVITY) {
            service.root(CART_ACTIVITY)
            return
        }
        service.root(CHECKOUT_ACTIVITY)
        closeDialog(service)
        service.backFromAli()
        val cancel = try {
            waitFor(3_000) {
                val root = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return@waitFor null
                val title = root.byId("tv_title")?.text?.toString().orEmpty()
                if (title.contains("会計手続きを中止しますか")) root.byText("中止する") else null
            }
        } catch (_: AutomationException) {
            null // 確認なしでカートへ戻る画面版にも対応する。
        }
        cancel?.safeClick { service.root(CHECKOUT_ACTIVITY) }
        waitFor(10_000) { runCatching { service.root(CART_ACTIVITY) }.getOrNull() }
    }

    private fun dialogVisible(root: AccessibilityNodeInfo): Boolean =
        root.byId("et_coupon_code_input_view") != null ||
            root.byId("tv_dialog_title")?.text?.toString() == "プロモコード" ||
            root.byId("tv_title")?.text?.toString()?.contains("本当に適用") == true

    private suspend fun waitUntil(targetAtMs: Long) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = targetAtMs - clock.now()
            val delayMs = AutomationTiming.coarseDelayMs(remaining)
            if (delayMs == 0L) break
            delay(delayMs)
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = targetAtMs - clock.now()
            val delayMs = AutomationTiming.fineDelayMs(remaining)
            if (delayMs == 0L) break
            // 最後400msはHandlerの実行待ちにせず、単調時計を短い間隔で読み直す。
            Thread.sleep(delayMs)
        }
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val targetNanos = AutomationTiming.monotonicTargetNanos(targetAtMs, clock.now(), nowNanos)
        var checks = 0
        while (SystemClock.elapsedRealtimeNanos() < targetNanos) {
            if (++checks % 256 == 0) currentCoroutineContext().ensureActive()
        }
    }

    private fun checkoutText(text: String): Int {
        val match = Regex("購入手続きへ\\s*\\((\\d+)\\)").find(text) ?: return -1
        return match.groupValues[1].toIntOrNull() ?: -1
    }

    private suspend fun <T : Any> waitFor(timeoutMs: Long, read: () -> T?): T {
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
