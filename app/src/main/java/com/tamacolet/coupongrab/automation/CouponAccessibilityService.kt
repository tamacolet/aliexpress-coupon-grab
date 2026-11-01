package com.tamacolet.coupongrab.automation

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    fun root(activity: String? = null): AccessibilityNodeInfo {
        val node = rootInActiveWindow ?: throw AutomationException("AliExpressの画面を取得できません")
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

        fun launchWakeActivity(fallback: android.content.Context) {
            val service = instance
            if (service != null) WakeActivity.launch(service) else WakeActivity.launch(fallback)
        }
    }
}

class AutomationException(message: String) : Exception(message)

fun AccessibilityNodeInfo.snapshot(): SafetyNode = SafetyNode(
    resourceId = viewIdResourceName.orEmpty(),
    text = text?.toString().orEmpty(),
    contentDescription = contentDescription?.toString().orEmpty(),
    clickable = isClickable,
    visible = isVisibleToUser,
    enabled = isEnabled,
    children = List(childCount) { index -> getChild(index)?.snapshot() ?: SafetyNode() },
)

fun AccessibilityNodeInfo.flatten(): List<AccessibilityNodeInfo> =
    listOf(this) + List(childCount) { index -> getChild(index) }.filterNotNull().flatMap { it.flatten() }

fun AccessibilityNodeInfo.byId(shortName: String): AccessibilityNodeInfo? =
    findAccessibilityNodeInfosByViewId(AliIds.qualified(shortName)).firstOrNull { it.isVisibleToUser }

fun AccessibilityNodeInfo.byText(value: String): AccessibilityNodeInfo? = flatten().firstOrNull {
    it.isVisibleToUser && (it.text?.toString() == value || it.contentDescription?.toString() == value)
}

private fun AccessibilityNodeInfo.shallowSnapshot(): SafetyNode = SafetyNode(
    resourceId = viewIdResourceName.orEmpty(),
    text = text?.toString().orEmpty(),
    contentDescription = contentDescription?.toString().orEmpty(),
    clickable = isClickable,
    visible = isVisibleToUser,
    enabled = isEnabled,
)

class PreparedClick internal constructor(
    private val entry: AccessibilityNodeInfo,
    private val target: AccessibilityNodeInfo,
    private val expected: SafetyNode,
    private val focusGuard: () -> Unit,
) {
    /** 待機中に対象が変わっていても、押す入口で最新の文字・IDを再検査する。保存はしない。 */
    fun perform(beforeDispatch: () -> Unit = {}): Boolean {
        focusGuard()
        if (!entry.refresh() || (entry != target && !target.refresh())) {
            throw AutomationException("押す対象が画面から消えました")
        }
        if (!target.isClickable) throw AutomationException("押す対象がボタンではなくなりました")
        val current = entry.shallowSnapshot()
        if (current.resourceId != expected.resourceId || current.text != expected.text ||
            current.contentDescription != expected.contentDescription) {
            throw AutomationException("押す対象が待機中に変わりました")
        }
        val ancestors = mutableListOf<SafetyNode>()
        var parent = entry.parent
        while (parent != null) {
            ancestors += parent.shallowSnapshot()
            parent = parent.parent
        }
        PaymentSafety.requireSafe(current, target.snapshot(), ancestors)
        beforeDispatch()
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}

fun AccessibilityNodeInfo.prepareSafeClick(focusGuard: () -> Unit = {}): PreparedClick {
    focusGuard()
    var target = this
    while (!target.isClickable) {
        target = target.parent ?: throw AutomationException("クリック可能な要素がありません")
    }
    val ancestors = mutableListOf<SafetyNode>()
    var parent = parent
    while (parent != null) {
        ancestors += parent.shallowSnapshot()
        parent = parent.parent
    }
    val entry = shallowSnapshot()
    PaymentSafety.requireSafe(entry, target.snapshot(), ancestors)
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
