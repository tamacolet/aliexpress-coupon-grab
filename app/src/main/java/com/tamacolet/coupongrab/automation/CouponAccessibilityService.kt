package com.tamacolet.coupongrab.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.tamacolet.coupongrab.core.ALI_PACKAGE
import com.tamacolet.coupongrab.core.ActivityEventReducer
import com.tamacolet.coupongrab.core.AliIds
import com.tamacolet.coupongrab.core.PaymentSafety
import com.tamacolet.coupongrab.core.SafetyNode
import com.tamacolet.coupongrab.service.WakeActivity
import kotlinx.coroutines.delay

class CouponAccessibilityService : AccessibilityService() {
    @Volatile var currentActivity: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != ALI_PACKAGE) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentActivity = ActivityEventReducer.reduce(
                currentActivity,
                event.packageName?.toString().orEmpty(),
                event.className?.toString().orEmpty(),
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * 一番上のアプリの窓を返す。アリエクの確認窓はフォーカスを持たないことがあり、
     * キーボードが出る瞬間は「今使っている窓」が取れないので、窓の重なり順で選ぶ。
     */
    fun root(activity: String? = null): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        for (attempt in 0 until 5) {
            node = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            if (node != null) break
            // 窓の控え（キャッシュ）が古いまま残り、消えた窓だけが見えることがある（7aで確認）。
            if (android.os.Build.VERSION.SDK_INT >= 33) clearCache()
            Thread.sleep(40)
        }
        if (node == null) {
            val seen = windows.joinToString(" ") { "${it.type}/${it.layer}/${it.title}/${it.root?.packageName}" }
            android.util.Log.i("CouponGrab", "root_missing windows=$seen")
            throw AutomationException("AliExpressの画面を取得できません")
        }
        if (node.packageName?.toString() != ALI_PACKAGE) throw AutomationException("対象外のアプリが前面です")
        if (activity != null && currentActivity != activity) {
            throw AutomationException("想定外の画面です: $currentActivity")
        }
        return node
    }

    fun backFromAli() {
        root()
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) throw AutomationException("戻る操作に失敗しました")
    }

    companion object {
        @Volatile private var instance: CouponAccessibilityService? = null

        suspend fun awaitConnected(timeoutMs: Long = 5_000): CouponAccessibilityService {
            val started = android.os.SystemClock.elapsedRealtime()
            while (true) {
                instance?.let { return it }
                if (android.os.SystemClock.elapsedRealtime() - started >= timeoutMs) {
                    throw AutomationException("ユーザー補助サービスを有効にしてください")
                }
                delay(100)
            }
        }

        /** 押せる印の無い行だけに使う位置タップ。安全検査は呼び出し側で済ませる。 */
        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: throw AutomationException("ユーザー補助サービスを有効にしてください")
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun launchWakeActivity(fallback: android.content.Context) {
            val service = instance
            if (service != null) WakeActivity.launch(service) else WakeActivity.launch(fallback)
        }
    }
}

typealias AutomationException = com.tamacolet.coupongrab.core.AutomationException

fun AccessibilityNodeInfo.snapshot(): SafetyNode = SafetyNode(
    resourceId = viewIdResourceName.orEmpty(),
    text = text?.toString().orEmpty(),
    contentDescription = contentDescription?.toString().orEmpty(),
    clickable = isClickable,
    visible = isVisibleToUser,
    enabled = isEnabled,
    children = List(childCount) { index -> getChild(index)?.snapshot() ?: SafetyNode(readable = false) },
)

fun AccessibilityNodeInfo.flatten(): List<AccessibilityNodeInfo> =
    AliIds.flattenReadable(this) { node -> List(node.childCount) { node.getChild(it) } }

fun AccessibilityNodeInfo.byId(shortName: String): AccessibilityNodeInfo? =
    findAccessibilityNodeInfosByViewId(AliIds.qualified(shortName)).firstOrNull { it.isVisibleToUser }

fun AccessibilityNodeInfo.byText(value: String): AccessibilityNodeInfo? = flatten().firstOrNull {
    it.isVisibleToUser && (it.text?.toString() == value || it.contentDescription?.toString() == value)
}

fun AccessibilityNodeInfo.shallowSnapshot(): SafetyNode = SafetyNode(
    resourceId = viewIdResourceName.orEmpty(),
    text = text?.toString().orEmpty(),
    contentDescription = contentDescription?.toString().orEmpty(),
    clickable = isClickable,
    visible = isVisibleToUser,
    enabled = isEnabled,
)

class PreparedClick internal constructor(
    private val entry: AccessibilityNodeInfo,
    /** null の時は押せる印が無いので、文字の中心を位置で押す。 */
    private val target: AccessibilityNodeInfo?,
    private val expected: SafetyNode,
    private val focusGuard: () -> Unit,
) {
    /** 待機中に対象が変わっていても、押す入口で最新の文字・IDを再検査する。保存はしない。 */
    fun perform(beforeDispatch: () -> Unit = {}): Boolean {
        focusGuard()
        if (!entry.refresh() || (target != null && entry != target && !target.refresh())) {
            throw AutomationException("押す対象が画面から消えました")
        }
        val current = entry.shallowSnapshot()
        if (current.resourceId != expected.resourceId || current.text != expected.text ||
            current.contentDescription != expected.contentDescription) {
            throw AutomationException("押す対象が待機中に変わりました")
        }
        val ancestors = entry.ancestorSnapshots()
        if (target == null) {
            val bounds = Rect().also(entry::getBoundsInScreen)
            val x = bounds.exactCenterX()
            val y = bounds.exactCenterY()
            PaymentSafety.requireSafeTap(current, ancestors, entry.nodesAt(bounds.centerX(), bounds.centerY()))
            beforeDispatch()
            return CouponAccessibilityService.tap(x, y)
        }
        if (!target.isClickable) throw AutomationException("押す対象がボタンではなくなりました")
        PaymentSafety.requireSafe(current, target.snapshot(), ancestors)
        beforeDispatch()
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}

private fun AccessibilityNodeInfo.ancestorSnapshots(): List<SafetyNode> {
    val ancestors = mutableListOf<SafetyNode>()
    var parent = parent
    while (parent != null) {
        ancestors += parent.shallowSnapshot()
        parent = parent.parent
    }
    return ancestors
}

/** 同じ画面で (x, y) に重なる見えているノード。押せるものは配下まで写す。 */
private fun AccessibilityNodeInfo.nodesAt(x: Int, y: Int): List<SafetyNode> {
    var top = this
    while (true) top = top.parent ?: break
    val bounds = Rect()
    return top.flatten().filter {
        it.isVisibleToUser && bounds.also(it::getBoundsInScreen).contains(x, y)
    }.map { if (it.isClickable) it.snapshot() else it.shallowSnapshot() }
}

fun AccessibilityNodeInfo.prepareSafeClick(focusGuard: () -> Unit = {}): PreparedClick {
    focusGuard()
    var target: AccessibilityNodeInfo? = this
    while (target != null && !target.isClickable) target = target.parent
    val ancestors = ancestorSnapshots()
    val entry = shallowSnapshot()
    if (target == null) {
        // AliExpress のプロモコード行は押せる印を持たず、触れた位置で反応する。
        val bounds = Rect().also(::getBoundsInScreen)
        if (bounds.isEmpty) throw AutomationException("クリック可能な要素がありません")
        PaymentSafety.requireSafeTap(entry, ancestors, nodesAt(bounds.centerX(), bounds.centerY()))
    } else {
        PaymentSafety.requireSafe(entry, target.snapshot(), ancestors)
    }
    return PreparedClick(this, target, entry, focusGuard)
}

fun AccessibilityNodeInfo.safeClick(focusGuard: () -> Unit = {}) {
    if (!prepareSafeClick(focusGuard).perform()) {
        throw AutomationException("クリックに失敗しました")
    }
}

fun AccessibilityNodeInfo.setVerifiedText(value: String, focusGuard: () -> Unit = {}) {
    val arguments = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
    }
    focusGuard()
    if (!performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
        throw AutomationException("コード入力に失敗しました")
    }
}

fun AccessibilityNodeInfo.scrollForward(focusGuard: () -> Unit = {}): Boolean {
    val scrollable = flatten().firstOrNull { it.isVisibleToUser && it.isScrollable } ?: return false
    focusGuard()
    return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
}
