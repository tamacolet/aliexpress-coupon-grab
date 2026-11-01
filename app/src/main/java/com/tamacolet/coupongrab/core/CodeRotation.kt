package com.tamacolet.coupongrab.core

/** 最初の押下から全コードで時間を共有する。無効だけは列の最後へ戻す。 */
class CodeRotation(codes: List<String>) {
    private var queue = codes.toList()
    private val budgetMs = 15_000L * codes.size
    private var startedAtMs: Long? = null
    private var lastPressAtMs: Long? = null
    private var presses = 0

    init { require(codes.isNotEmpty() && codes.distinct().size == codes.size) }

    fun start(nowMs: Long) { if (startedAtMs == null) startedAtMs = nowMs }
    fun pressed(nowMs: Long) { check(presses < MAX_PRESSES); start(nowMs); lastPressAtMs = nowMs; presses++ }
    /** 1秒ごとに31回押すとアリエクの安全確認（スライダー）が出た（7a 11-01）。押す間を空ける。 */
    fun waitBeforePressMs(nowMs: Long): Long =
        lastPressAtMs?.let { (it + MIN_PRESS_GAP_MS - nowMs).coerceAtLeast(0) } ?: 0
    fun remainingMs(nowMs: Long): Long? = startedAtMs?.let { (budgetMs - (nowMs - it)).coerceAtLeast(0) }
    fun expired(nowMs: Long): Boolean = presses >= MAX_PRESSES || remainingMs(nowMs) == 0L
    fun next(nowMs: Long): String? = queue.firstOrNull().takeUnless { expired(nowMs) }
    fun pending(): List<String> = queue.toList()

    fun finishIfPending(code: String, status: CodeStatus): Boolean {
        if (code !in queue) return false
        complete(code, status)
        return true
    }

    fun complete(code: String, status: CodeStatus?) {
        if (code !in queue) return
        queue = queue.filterNot { it == code } + if (status == null) listOf(code) else emptyList()
    }

    /** 前の券の結果を回収したら、準備中の券は失敗にせず最後へ戻す。 */
    fun afterRecovery(attempting: String?, previousCompleted: Boolean): Boolean {
        if (attempting == null) return false
        if (previousCompleted) { complete(attempting, null); return false }
        return finishIfPending(attempting, CodeStatus.FAILURE)
    }

    companion object {
        const val MIN_PRESS_GAP_MS = 2_000L
        const val MAX_PRESSES = 25
    }
}

const val SECURITY_CHECK_REASON = "アリエクの安全確認（スライダー）が出たので止めました。スマホで手で解いてください"

/** 安全確認の画面は com.alibaba.wireless.security の ContainerActivity（7a 11-01）。解かずに止める。 */
class SecurityCheckException : Exception(SECURITY_CHECK_REASON) {
    companion object {
        fun isSecurityScreen(activity: String?): Boolean = activity?.contains(".wireless.security.") == true
    }
}

data class ValidationSnapshot(val tips: String, val warning: Boolean) {
    companion object {
        fun from(nodes: List<SafetyNode>) = ValidationSnapshot(
            AliIds.byId(nodes, "tv_coupon_code_validation_tips")?.text.orEmpty(),
            AliIds.byId(nodes, "iv_warning_icon") != null,
        )
    }
}

data class CouponObservation(val state: CouponUiState, val validation: ValidationSnapshot) {
    companion object {
        fun from(nodes: List<SafetyNode>, code: String) =
            CouponObservation(CouponStateDecider.decide(nodes, code), ValidationSnapshot.from(nodes))
    }
}

/** 承認は4秒ずつ最大2回。古い検証表示は、新しい結果になるまで待つ。 */
class CouponResultGate(private val before: ValidationSnapshot, private val pressedAtMs: Long) {
    enum class Action { WAIT, APPROVE, APPLIED, USED, INVALID, FAIL }
    private var approvedAtMs: Long? = null
    private var approvals = 0
    private var validationChanged = false

    fun observe(observation: CouponObservation, nowMs: Long): Action {
        if (observation.validation != before) validationChanged = true
        if (observation.state == CouponUiState.APPLIED) return Action.APPLIED
        if (observation.state == CouponUiState.CONFIRMATION) {
            val approvedAt = approvedAtMs
            if (approvedAt != null && nowMs - approvedAt < 4_000) return Action.WAIT
            if (approvals >= 2) return Action.FAIL
            approvals++
            approvedAtMs = nowMs
            return Action.APPROVE
        }
        return when (observation.state) {
            CouponUiState.USED -> if (validationChanged) Action.USED else Action.WAIT
            CouponUiState.INVALID -> if (approvedAtMs != null || validationChanged || nowMs - pressedAtMs >= 1_200) Action.INVALID else Action.WAIT
            else -> if (approvedAtMs?.let { nowMs - it >= 4_000 } == true) Action.FAIL else Action.WAIT
        }
    }
}

/** 未知の画面文字・例外本文は記録しない。コードが定型表示と重なった時も伏せる。 */
object AutomationDiagnostics {
    private val labels = setOf("プロモコード", "適用", "承認", "取り消し", "閉じる", "割引額", "中止する", "本当に適用しますか")
    private val reasons = setOf(
        "コード入力欄が見つかりません", "適用ボタンが見つかりません", "承認ボタンが見つかりません",
        "コード入力に失敗しました", "入力コードの読み戻しが一致しません", "クリックに失敗しました",
        "プロモコード欄が見つかりません", "画面の待機がタイムアウトしました", "カートが空です",
        "「適用」が押せる状態になりません", "コード形式が不正です",
        "カートが空か、購入手続きへ進めません", "プロモコードの小窓が開きませんでした",
        "取り消しボタンが見つかりません", "AliExpressの画面を取得できません", "想定外の画面です",
        "支払い・注文操作の可能性があるためクリックを拒否しました",
        "支払い・注文操作の可能性があるため位置タップを拒否しました",
        "画面に読み取れない部品があります", "対象外のアプリが前面です", "戻る操作に失敗しました",
        "ユーザー補助サービスを有効にしてください", "押す対象が画面から消えました",
        "押す対象が待機中に変わりました", "押す対象がボタンではなくなりました",
        "クリック可能な要素がありません", "確認画面の対象コードが不明です",
        "後片付け中に対象外のアプリが前面になりました", "後片付けでカートへ戻れませんでした",
        "画面を起こせませんでした", "確認窓の承認が完了しませんでした",
        "画面操作を完了できませんでした", SECURITY_CHECK_REASON,
    )

    fun detail(error: Throwable, nodes: List<SafetyNode>, codes: List<String>): String {
        fun excludesCodes(text: String) = codes.none { it.isNotEmpty() && text.contains(it, ignoreCase = true) }
        // 接尾のノード名や画面名は捨て、許可された理由だけを残す。整形済みの詳細も再利用する。
        val message = error.message.orEmpty()
        val reason = reasons.filter { message.startsWith(it) && excludesCodes(it) }.maxByOrNull { it.length }
            ?: "画面操作を完了できませんでした"
        val priorVisible = message.substringAfter(" / 表示: ", "").split("、")
        if (message.startsWith("$reason / 表示: ") && priorVisible.size in 1..10 && priorVisible.all {
                (it in labels || it in setOf("[非公開]", "[確認窓:本当に適用]", "取得できません")) && excludesCodes(it)
            }) return message
        val visible = nodes.asSequence().filter { it.visible }
            .flatMap { sequenceOf(it.text, it.contentDescription) }.filter { it.isNotBlank() }.take(10)
            .map { when {
                it.contains("本当に適用") -> "[確認窓:本当に適用]".takeIf(::excludesCodes) ?: "[非公開]"
                it in labels && excludesCodes(it) -> it.take(20)
                else -> "[非公開]"
            } }.toList()
        return "$reason / 表示: ${visible.joinToString("、").ifEmpty { "取得できません" }}"
    }
}
