package com.tamacolet.coupongrab.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-02 0:27 に Pixel 7a で読んだ本物の確認窓（JPAF82 が当たった購入手続きで JPAF70 を入れた時）。
 * 部品の並び・ID・文字は uiautomator の記録そのまま。
 */
class RealConfirmDialogTest {
    private val cancel = SafetyNode(text = "取り消し", clickable = true)
    private val approve = SafetyNode(text = "承認", clickable = true)
    private val buttons = SafetyNode(children = listOf(cancel, approve))
    private val items = SafetyNode(resourceId = "com.alibaba.aliexpresshd:id/items_container", children = listOf(buttons))
    private val body = SafetyNode(children = listOf(
        SafetyNode(resourceId = "com.alibaba.aliexpresshd:id/tv_title", text = "このクーポン/コードを本当に適用してよろしいですか?"),
        SafetyNode(resourceId = "com.alibaba.aliexpresshd:id/tv_content", text = "割引額から 2,000円 失うことになります。"),
        items,
    ))
    private val close = SafetyNode(resourceId = "com.alibaba.aliexpresshd:id/tv_close", text = "", clickable = true)
    private val group = SafetyNode(children = listOf(close, body))
    private val content = SafetyNode(resourceId = "android:id/content", children = listOf(group))
    private val dialogRoot = SafetyNode(children = listOf(SafetyNode(children = listOf(content))))
    private val dialog = AliIds.flatten(listOf(dialogRoot))

    /** 確認窓の下の購入手続き画面。プロモコード行は新しいコード、割引額は前の券のまま残る。 */
    private fun checkout(code: String) = AliIds.flatten(listOf(SafetyNode(children = listOf(
        SafetyNode(text = "プロモコード"), SafetyNode(text = code, clickable = true),
        SafetyNode(text = "割引額"), SafetyNode(text = "-2,000円"),
        SafetyNode(resourceId = "com.alibaba.aliexpresshd:id/btn_place_order", text = "今支払います", clickable = true),
    ))))
    private val promoDialog = AliIds.flatten(listOf(SafetyNode(children = listOf(
        SafetyNode(resourceId = "com.alibaba.aliexpresshd:id/tv_dialog_title", text = "プロモコード"),
        SafetyNode(resourceId = "com.alibaba.aliexpresshd:id/et_coupon_code_input_view", text = "JPAF70"),
    ))))

    @Test fun `real dialog is recognised as confirmation and only its buttons are pressable`() {
        assertEquals(CouponUiState.CONFIRMATION, CouponStateDecider.decide(dialog, "JPAF70"))
        val found = assertNotNull(AliIds.byText(dialog, "承認")).let { AliIds.byText(dialog, "承認")!! }
        assertTrue(found.clickable)
        val ancestors = listOf(buttons, items, body, group, content, dialogRoot.children.single(), dialogRoot)
        assertTrue(PaymentSafety.canClick(approve, approve, ancestors))
        assertTrue(PaymentSafety.canClick(cancel, cancel, ancestors))
        // 「割引額から…失う」は割引額の行と取り違えない（完全一致だけ見る）。
        assertEquals(null, AliIds.byText(dialog, "割引額"))
    }

    @Test fun `JPAF82 then JPAF70 approves the late dialog instead of trusting the gap`() = runBlocking {
        // 押した後: 小窓 → 小窓が閉じたすき間（前の割引額＋新しいコード）→ 遅れて確認窓 → 承認 → 本当に当たった画面。
        val screens = ArrayDeque(listOf(promoDialog, checkout("JPAF70"), checkout("JPAF70"), dialog) +
            List(30) { checkout("JPAF70") })
        val actions = mutableListOf<String>()
        var now = 0L
        val before = ValidationSnapshot.from(promoDialog)
        val outcome = AutomationFlow.awaitOutcome(CouponResultGate(before, 0),
            read = {
                val nodes = screens.removeFirst()
                CouponObservation.from(nodes, "JPAF70").also { actions += it.state.name }
            },
            nowMs = { now },
            approve = { actions += "approve" },
            pause = { now += 100 })
        assertEquals(CouponUiState.APPLIED, outcome)
        assertEquals(listOf("DIALOG", "APPLIED", "APPLIED", "CONFIRMATION", "approve"), actions.take(5))
        // 承認の後、取れた表示が1.5秒続いてから確定する。
        assertEquals(CouponResultGate.APPLIED_STABLE_MS, actions.drop(5).size.toLong() * 100 - 100)
    }

    @Test fun `recovery before the next code also waits through the gap`() = runBlocking {
        val screens = ArrayDeque(listOf(checkout("JPAF70"), dialog) + List(30) { checkout("JPAF70") })
        val actions = mutableListOf<String>()
        var now = 0L
        val state = AutomationFlow.settleBeforeNext(
            read = { CouponObservation.from(screens.removeFirst(), "JPAF70").also { actions += it.state.name } },
            nowMs = { now }, approve = { actions += "approve" }, pause = { now += 100 })
        assertEquals(CouponUiState.APPLIED, state)
        assertEquals(listOf("APPLIED", "CONFIRMATION", "approve"), actions.take(3))
    }
}
