package com.tamacolet.coupongrab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentSafetyTest {
    @Test fun `正確なAliリソースIDだけを探す`() {
        val expected = SafetyNode(resourceId = "com.alibaba.aliexpresshd:id/bt_checkout")
        val wrong = SafetyNode(resourceId = "evil:id/bt_checkout")
        assertEquals(expected, AliIds.byId(listOf(wrong, expected), "bt_checkout"))
    }

    @Test fun `日本語と英語の支払い注文対象を拒否する`() {
        listOf(
            SafetyNode(text = "今支払います"),
            SafetyNode(text = "注文を確定"),
            SafetyNode(contentDescription = "支払方法"),
            SafetyNode(resourceId = "com.alibaba.aliexpresshd:id/btn_place_order"),
            SafetyNode(resourceId = "com.alibaba.aliexpresshd:id/payment_submit"),
            SafetyNode(text = "Pay now"),
        ).forEach { assertTrue(PaymentSafety.isForbidden(it)) }
    }

    @Test fun `安全な適用ボタンを許可する`() {
        val apply = SafetyNode(resourceId = AliIds.qualified("bt_update_coupon_code_ok"), clickable = true)
        assertTrue(PaymentSafety.canClick(apply, apply, emptyList()))
    }

    @Test fun `禁止された祖先を拒否する`() {
        val entry = SafetyNode(text = "確定")
        val parent = SafetyNode(resourceId = AliIds.qualified("btn_container"), clickable = true)
        assertFalse(PaymentSafety.canClick(entry, parent, listOf(parent)))
    }

    @Test fun `クリックする祖先の配下に支払いボタンがあれば拒否する`() {
        val entry = SafetyNode(text = "プロモコード")
        val parent = SafetyNode(clickable = true, children = listOf(entry, SafetyNode(text = "今支払います")))
        assertFalse(PaymentSafety.canClick(entry, parent, listOf(parent)))
    }

    @Test fun `押せる印の無いプロモコード行は位置タップを許可する`() {
        val promo = SafetyNode(text = "プロモコード")
        val row = SafetyNode(children = listOf(promo, SafetyNode(text = "コードを入力")))
        assertTrue(PaymentSafety.canTap(promo, listOf(row), listOf(row, promo)))
        // 押せない画面全体の入れ物は、配下に支払いボタンがあっても自身だけを見る。
        val page = SafetyNode(children = listOf(row, SafetyNode(text = "今支払います")))
        assertTrue(PaymentSafety.canTap(promo, listOf(row, page), listOf(page, row, promo)))
    }

    @Test fun `位置タップの場所に支払いボタンが重なれば拒否する`() {
        val promo = SafetyNode(text = "プロモコード")
        val payBar = SafetyNode(resourceId = AliIds.qualified("btn_container"), clickable = true)
        assertFalse(PaymentSafety.canTap(promo, emptyList(), listOf(promo, payBar)))
        val payChild = SafetyNode(clickable = true, children = listOf(SafetyNode(text = "今支払います")))
        assertFalse(PaymentSafety.canTap(promo, emptyList(), listOf(payChild)))
    }

    @Test fun `位置タップでも支払いの祖先は拒否する`() {
        val entry = SafetyNode(text = "確定")
        assertFalse(PaymentSafety.canTap(entry, listOf(SafetyNode(text = "注文")), listOf(entry)))
    }

    @Test fun `拒否の理由に引っかかった部品を書く`() {
        val disabled = SafetyNode(resourceId = AliIds.qualified("bt_update_coupon_code_ok"), text = "適用",
            clickable = true, enabled = false)
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PaymentSafety.requireSafe(disabled, disabled, emptyList())
        }
        assertTrue(error.message!!.contains("bt_update_coupon_code_ok 適用| visible=true enabled=false"))
    }

    @Test fun `対象外の支払いノードだけでは別の直接ボタンを拒否しない`() {
        val apply = SafetyNode(text = "適用", clickable = true)
        val root = SafetyNode(children = listOf(apply, SafetyNode(text = "注文")))
        assertTrue(PaymentSafety.canClick(apply, apply, listOf(root)))
    }
}

class CouponStateDeciderTest {
    @Test fun `確認使用済み無効を区別する`() {
        assertEquals(CouponUiState.CONFIRMATION, decide(SafetyNode(resourceId = id("tv_title"), text = "本当に適用しますか")))
        assertEquals(CouponUiState.USED, decide(SafetyNode(resourceId = id("tv_coupon_code_validation_tips"), text = "使用済みです")))
        assertEquals(CouponUiState.INVALID, decide(SafetyNode(resourceId = id("iv_warning_icon"))))
    }

    @Test fun `適用済みは割引額と完全一致コードの両方を要求する`() {
        val discount = SafetyNode(text = "割引額")
        assertEquals(CouponUiState.UNKNOWN, decide(discount))
        assertEquals(CouponUiState.APPLIED, decide(discount, SafetyNode(contentDescription = "ABC123")))
        assertEquals(CouponUiState.UNKNOWN, decide(discount, SafetyNode(text = "XABC123X")))
    }

    @Test fun `ダイアログ表示中を誤って適用済みにしない`() {
        val state = decide(
            SafetyNode(resourceId = id("tv_dialog_title"), text = "プロモコード"),
            SafetyNode(text = "割引額"), SafetyNode(text = "ABC123"),
        )
        assertEquals(CouponUiState.DIALOG, state)
    }

    private fun decide(vararg nodes: SafetyNode) = CouponStateDecider.decide(nodes.toList(), "ABC123")
    private fun id(short: String) = AliIds.qualified(short)
}

class CurrentNodeSafetyTest {
    @org.junit.Test fun `非表示の過去コードや警告を結果に使わない`() {
        val nodes = listOf(
            SafetyNode(text = "割引額"), SafetyNode(text = "ABC123", visible = false),
            SafetyNode(resourceId = AliIds.qualified("iv_warning_icon"), visible = false),
        )
        org.junit.Assert.assertEquals(CouponUiState.UNKNOWN, CouponStateDecider.decide(nodes, "ABC123"))
    }

    @org.junit.Test fun `押す直前の非表示無効と支払への変化を拒否する`() {
        val safe = SafetyNode(text = "適用", clickable = true)
        org.junit.Assert.assertTrue(PaymentSafety.canClick(safe, safe, emptyList()))
        listOf(safe.copy(visible = false), safe.copy(enabled = false), safe.copy(text = "今支払います"),
            safe.copy(resourceId = "com.alibaba.aliexpresshd:id/submit_order")).forEach {
            org.junit.Assert.assertFalse(PaymentSafety.canClick(it, it, emptyList()))
        }
    }
}
