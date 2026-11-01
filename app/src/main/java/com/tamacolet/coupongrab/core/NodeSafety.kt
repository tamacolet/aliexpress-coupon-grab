package com.tamacolet.coupongrab.core

const val ALI_PACKAGE = "com.alibaba.aliexpresshd"
const val ALI_ID_PREFIX = "$ALI_PACKAGE:id/"
const val CART_ACTIVITY = "com.aliexpress.android.aeaicart.ui.CartEntryActivity"
const val CHECKOUT_ACTIVITY = "com.aliexpress.android.placeorder.ui.PlaceOrderActivity"
/** 対応していないウェブ画面を開いた時の「工事中」画面。 */
const val BUILDING_ACTIVITY = "com.aliexpress.module.navigation.AEBuildingActivity"
const val CART_URL ="https://ja.aliexpress.com/p/shoppingcart/index.html"

data class SafetyNode(
    val resourceId: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val clickable: Boolean = false,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val children: List<SafetyNode> = emptyList(),
    val readable: Boolean = true,
)

object AliIds {
    fun <T : Any> flattenReadable(root: T, children: (T) -> List<T?>): List<T> =
        listOf(root) + children(root).flatMap {
            flattenReadable(it ?: throw AutomationException("画面に読み取れない部品があります"), children)
        }

    fun qualified(shortName: String): String {
        require(shortName.matches(Regex("^[A-Za-z0-9_]+$")))
        return ALI_ID_PREFIX + shortName
    }

    fun byId(nodes: List<SafetyNode>, shortName: String): SafetyNode? {
        val expected = qualified(shortName)
        return nodes.firstOrNull { it.visible && it.resourceId == expected }
    }

    fun flatten(roots: List<SafetyNode>): List<SafetyNode> = roots.flatMap { node ->
        listOf(node) + flatten(node.children)
    }

    fun byText(nodes: List<SafetyNode>, text: String): SafetyNode? = nodes.firstOrNull {
        it.visible && (it.text == text || it.contentDescription == text)
    }
}

object PaymentSafety {
    private val forbidden = Regex(
        "今支払います|今支払|注文|支払|place[ _-]?order|pay[ _-]?now|" +
            "btn_place_order|bt_place_order|btn_container|payment|checkout_submit|submit_order",
        RegexOption.IGNORE_CASE,
    )

    fun isForbidden(node: SafetyNode): Boolean = !node.readable || forbidden.containsMatchIn(
        "${node.resourceId}\n${node.text}\n${node.contentDescription}",
    )

    fun subtreeIsSafe(node: SafetyNode): Boolean =
        !isForbidden(node) && node.children.all(::subtreeIsSafe)

    /** クリック開始ノード、実際に押す祖先、ルートまでの祖先をすべて検査する。 */
    fun canClick(entry: SafetyNode, clickTarget: SafetyNode, ancestors: List<SafetyNode>): Boolean =
        entry.visible && entry.enabled && clickTarget.visible && clickTarget.enabled &&
            !isForbidden(entry) && subtreeIsSafe(clickTarget) &&
            (ancestors + clickTarget).none(::isForbidden)

    fun requireSafe(entry: SafetyNode, clickTarget: SafetyNode, ancestors: List<SafetyNode>) {
        require(canClick(entry, clickTarget, ancestors)) {
            "支払い・注文操作の可能性があるためクリックを拒否しました: " +
                describe(listOf(entry) + AliIds.flatten(listOf(clickTarget)) + ancestors)
        }
    }

    /** 拒否の理由を後で調べられるよう、最初に引っかかったノードを短く書く。 */
    private fun describe(nodes: List<SafetyNode>): String {
        val node = nodes.firstOrNull { isForbidden(it) || !it.visible || !it.enabled } ?: return "不明"
        val text = "${node.text}|${node.contentDescription}".take(40)
        return "${node.resourceId.removePrefix(ALI_ID_PREFIX)} $text visible=${node.visible} enabled=${node.enabled}"
    }

    /**
     * 押せる印の無い行（プロモコード行など）を位置で押す時の検査。
     * 押す文字・その祖先に加え、押す位置に重なる全ノードが安全な時だけ許可する。
     * 押せる印のあるノードはタップを受け取り得るので配下まで、ほかは自身だけを見る
     * （画面全体の入れ物は下部の支払いボタンも配下に持つため）。
     */
    fun canTap(entry: SafetyNode, ancestors: List<SafetyNode>, nodesAtPoint: List<SafetyNode>): Boolean =
        entry.visible && entry.enabled && !isForbidden(entry) &&
            ancestors.none(::isForbidden) &&
            nodesAtPoint.all { if (it.clickable) subtreeIsSafe(it) else !isForbidden(it) }

    fun requireSafeTap(entry: SafetyNode, ancestors: List<SafetyNode>, nodesAtPoint: List<SafetyNode>) {
        require(canTap(entry, ancestors, nodesAtPoint)) {
            "支払い・注文操作の可能性があるため位置タップを拒否しました: " +
                describe(listOf(entry) + ancestors + nodesAtPoint.flatMap { if (it.clickable) AliIds.flatten(listOf(it)) else listOf(it) })
        }
    }
}

enum class CouponUiState { CONFIRMATION, USED, INVALID, DIALOG, APPLIED, UNKNOWN }

object CouponStateDecider {
    fun decide(nodes: List<SafetyNode>, code: String): CouponUiState {
        val tips = AliIds.byId(nodes, "tv_coupon_code_validation_tips")?.text.orEmpty()
        if (nodes.any { it.visible && (it.text.contains("本当に適用") || it.contentDescription.contains("本当に適用")) }) {
            return CouponUiState.CONFIRMATION
        }
        if (tips.contains("使用済み")) return CouponUiState.USED
        if (AliIds.byId(nodes, "iv_warning_icon") != null || tips.contains("適用できません")) {
            return CouponUiState.INVALID
        }
        if (AliIds.byId(nodes, "tv_dialog_title") != null ||
            AliIds.byId(nodes, "et_coupon_code_input_view") != null
        ) return CouponUiState.DIALOG
        if (AliIds.byText(nodes, "割引額") != null && AliIds.byText(nodes, code) != null) {
            return CouponUiState.APPLIED
        }
        return CouponUiState.UNKNOWN
    }
}
