package com.tamacolet.coupongrab.automation

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
            for (coupon in codes) {
                yieldIfExactNeeded()
                val status = try {
                    require(CodeValidation.isValid(coupon.code)) { "コード形式が不正です" }
                    applyWithRetries(accessibility, coupon.code, targetAtMs)
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
                        withTimeout(30_000) { cleanup(accessibility) }
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
        waitForPromo(service)
        // WakeActivity はカートを実際に確認した後まで終了しない。最後の cleanup まで画面維持も継続する。
    }

    private suspend fun applyWithRetries(
        service: CouponAccessibilityService,
        code: String,
        targetAtMs: Long,
    ): CodeStatus {
        var started = 0L
        AutomationFlow.applyAtTarget(targetAtMs, prepare = {
            openPromoAndSet(service, code)
            prepareApplyTarget(service, code)
        }, await = { waitUntil(it) }, apply = {
            started = SystemClock.elapsedRealtime()
            clickApply(it, code, "apply")
        })
        var approved = false
        while (SystemClock.elapsedRealtime() - started <= 15_000) {
            yieldIfExactNeeded()
            val nodes = service.root(CHECKOUT_ACTIVITY).flatten().filter { it.isVisibleToUser }.map { it.shallowSnapshot() }
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
                    AutomationFlow.retryInvalid(retry = {
                        val root = service.root(CHECKOUT_ACTIVITY)
                        val currentInput = root.byId("et_coupon_code_input_view")
                            ?: throw AutomationException("コード入力欄が見つかりません")
                        currentInput.setVerifiedText("") { service.root(CHECKOUT_ACTIVITY) }
                        currentInput.setVerifiedText(code) { service.root(CHECKOUT_ACTIVITY) }
                        waitForApplyReady(service, code)
                        clickApply(prepareApplyTarget(service, code), code, "apply_retry")
                    }, reread = {
                        CouponStateDecider.decide(
                            service.root(CHECKOUT_ACTIVITY).flatten().map { it.shallowSnapshot() }, code,
                        )
                    }, onRetried = { approved = false })
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
        val dialog = AutomationFlow.openDialog(open = {
            waitForPromo(service).safeClick { service.root(CHECKOUT_ACTIVITY) }
            val opened: Any = waitFor(8_000) {
                if (service.currentActivity == BUILDING_ACTIVITY) return@waitFor BUILDING_ACTIVITY
                val root = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return@waitFor null
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
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        input.setVerifiedText("") { service.root(CHECKOUT_ACTIVITY) }
        input.setVerifiedText(code) { service.root(CHECKOUT_ACTIVITY) }
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
        var seen = ""
        try {
            waitFor(2_000) {
                val root = runCatching { service.root(CHECKOUT_ACTIVITY) }.getOrNull() ?: return@waitFor null
                val text = root.byId("et_coupon_code_input_view")?.text?.toString()
                val enabled = root.byId("bt_update_coupon_code_ok")?.isEnabled
                seen = "input=$text applyEnabled=$enabled"
                root.takeIf { text == code && enabled == true }
            }
        } catch (_: AutomationException) {
            throw AutomationException("「適用」が押せる状態になりません: $seen")
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
        leaveBuildingPage(service)
        if (service.currentActivity == CART_ACTIVITY) {
            service.root(CART_ACTIVITY)
            return
        }
        service.root(CHECKOUT_ACTIVITY)
        closeDialog(service)
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
            root.byId("tv_title")?.text?.toString()?.contains("本当に適用") == true

    private suspend fun waitUntil(targetAtMs: Long) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val delayMs = AutomationTiming.coarseDelayMs(targetAtMs - clock.now())
            if (delayMs == 0L) break
            delay(delayMs)
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
