package com.tamacolet.coupongrab.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CodeRotationTest {
    @Test fun `both schedule kinds rotate one attempt at a time`() {
        ScheduleKind.entries.forEach { kind ->
            val schedule = Schedule(kind = kind, nextRunAtMs = 1, codes = listOf("A", "B", "C").map { CouponCode(it) })
            val rotation = CodeRotation(ScheduleRules.pendingCodes(schedule).map { it.code })
            rotation.start(100)
            val order = (1..6).map {
                rotation.next(100)!!.also { rotation.complete(it, null) }
            }
            assertEquals(listOf("A", "B", "C", "A", "B", "C"), order)
        }
    }

    @Test fun `success used and exception failure leave rotation while invalid stays`() {
        CodeStatus.entries.filter { it != CodeStatus.WAITING }.forEach { status ->
            val rotation = CodeRotation(listOf("A", "B", "C"))
            rotation.complete("A", null)
            rotation.complete("B", status)
            assertEquals(listOf("C", "A"), rotation.pending())
            rotation.complete("C", null)
            assertEquals("A", rotation.next(0))
        }
    }

    @Test fun `recovery finalizes the pressed code once and never replaces success with failure`() {
        val rotation = CodeRotation(listOf("A", "B", "C"))
        rotation.complete("A", null)
        assertTrue(rotation.finishIfPending("A", CodeStatus.APPLIED))
        assertFalse(rotation.finishIfPending("A", CodeStatus.FAILURE))
        assertTrue(rotation.finishIfPending("B", CodeStatus.FAILURE))
        assertEquals(listOf("C"), rotation.pending())
    }

    @Test fun `shared budget starts at first press and is based on initial count`() {
        val rotation = CodeRotation(listOf("A", "B", "C"))
        assertNull(rotation.remainingMs(999_999))
        assertEquals("A", rotation.next(999_999))
        rotation.start(1_000_000)
        rotation.complete("A", CodeStatus.APPLIED)
        rotation.start(1_040_000)
        assertEquals(1L, rotation.remainingMs(1_044_999))
        assertEquals("B", rotation.next(1_044_999))
        assertNull(rotation.next(1_045_000))
        assertEquals(listOf("B", "C"), rotation.pending())
    }

    @Test fun `all done returns no next and late completion does not resurrect codes`() {
        val rotation = CodeRotation(listOf("A"))
        rotation.complete("A", CodeStatus.USED)
        rotation.complete("A", null)
        assertNull(rotation.next(0))
        assertTrue(rotation.pending().isEmpty())
    }
}

class CouponResultGateTest {
    private val stale = ValidationSnapshot("適用できません", true)
    private fun observation(state: CouponUiState, snapshot: ValidationSnapshot = stale) = CouponObservation(state, snapshot)

    @Test fun `stale invalid waits exactly 1200ms but text or icon changes release it`() {
        val gate = CouponResultGate(stale, 100)
        assertEquals(CouponResultGate.Action.WAIT, gate.observe(observation(CouponUiState.INVALID), 1_299))
        assertEquals(CouponResultGate.Action.INVALID, gate.observe(observation(CouponUiState.INVALID), 1_300))
        listOf(stale.copy(tips = "別のエラー"), stale.copy(warning = false)).forEach {
            assertEquals(CouponResultGate.Action.INVALID,
                CouponResultGate(stale, 100).observe(observation(CouponUiState.INVALID, it), 101))
        }
        val changed = CouponResultGate(stale, 100)
        changed.observe(observation(CouponUiState.DIALOG, ValidationSnapshot("", false)), 101)
        assertEquals(CouponResultGate.Action.INVALID, changed.observe(observation(CouponUiState.INVALID), 102))
    }

    @Test fun `applied and confirmation need no grace period`() {
        listOf(CouponUiState.APPLIED to CouponResultGate.Action.APPLIED,
            CouponUiState.CONFIRMATION to CouponResultGate.Action.APPROVE).forEach { (state, action) ->
            assertEquals(action, CouponResultGate(stale, 100).observe(observation(state), 100))
        }
    }

    @Test fun `runtime flow sees stale invalid then approves confirmation and awaits applied`() = runBlocking {
        val actions = mutableListOf<String>()
        val states = ArrayDeque(listOf(CouponUiState.INVALID, CouponUiState.CONFIRMATION,
            CouponUiState.CONFIRMATION, CouponUiState.DIALOG, CouponUiState.UNKNOWN, CouponUiState.APPLIED))
        var now = 0L
        val outcome = AutomationFlow.awaitOutcome(CouponResultGate(stale, 0),
            read = { observation(states.removeFirst()).also { actions += it.state.name } },
            nowMs = { now }, approve = { actions += "approve" }, pause = { now += 500 })
        assertEquals(CouponUiState.APPLIED, outcome)
        assertEquals(listOf("INVALID", "CONFIRMATION", "approve", "CONFIRMATION", "DIALOG", "UNKNOWN", "APPLIED"), actions)
    }

    @Test fun `runtime before next flow blocks all next code preparation until confirmation applied`() = runBlocking {
        val actions = mutableListOf<String>()
        val states = ArrayDeque(listOf(CouponUiState.CONFIRMATION, CouponUiState.DIALOG,
            CouponUiState.UNKNOWN, CouponUiState.APPLIED))
        val state = AutomationFlow.settleBeforeNext(
            read = { observation(states.removeFirst()).also { actions += it.state.name } },
            nowMs = { 5_000 }, approve = { actions += "approve" }, pause = { actions += "wait" })
        actions += "prepare-next"
        assertEquals(CouponUiState.APPLIED, state)
        assertEquals(listOf("CONFIRMATION", "approve", "wait", "DIALOG", "wait", "UNKNOWN", "wait", "APPLIED", "prepare-next"), actions)
    }

    @Test fun `execution recovery approves previous code and rotates the preparing code`() = runBlocking {
        val rotation = CodeRotation(listOf("A", "B"))
        rotation.complete("A", null)
        val results = mutableListOf<Pair<String, CodeStatus>>()
        val actions = mutableListOf<String>()
        val states = ArrayDeque(listOf(CouponUiState.CONFIRMATION, CouponUiState.UNKNOWN, CouponUiState.APPLIED))
        AutomationFlow.resolveConfirmation(cleaningUp = false, settle = {
            val state = AutomationFlow.settleBeforeNext(
                read = { observation(states.removeFirst()).also { actions += it.state.name } },
                nowMs = { 0 }, approve = { actions += "approve" }, pause = { actions += "wait" })
            assertEquals(CouponUiState.APPLIED, state)
            if (rotation.finishIfPending("A", CodeStatus.APPLIED)) results += "A" to CodeStatus.APPLIED
        }, cancel = { fail("execution must never cancel") })
        actions += "close"
        if (rotation.afterRecovery("B", previousCompleted = true)) results += "B" to CodeStatus.FAILURE
        if (rotation.finishIfPending("A", CodeStatus.FAILURE)) fail("success must be retained")
        assertEquals(listOf("CONFIRMATION", "approve", "wait", "UNKNOWN", "wait", "APPLIED", "close"), actions)
        assertEquals(listOf("A" to CodeStatus.APPLIED), results)
        assertEquals(listOf("B"), rotation.pending())
        AutomationFlow.resolveConfirmation(cleaningUp = true,
            settle = { fail("cleanup must not approve") }, cancel = { actions += "cancel" })
        assertEquals("cancel", actions.last())
    }

    @Test fun `stale used never completes based on elapsed time alone`() {
        val used = ValidationSnapshot("使用済み", false)
        val gate = CouponResultGate(used, 100)
        val observation = observation(CouponUiState.USED, used)
        assertEquals(CouponResultGate.Action.WAIT, gate.observe(observation, 1299))
        assertEquals(CouponResultGate.Action.WAIT, gate.observe(observation, 1_000_000))
        assertEquals(CouponResultGate.Action.USED,
            CouponResultGate(used, 100).observe(observation.copy(validation = used.copy(warning = true)), 101))
    }

    @Test fun `confirmation has exactly two four second approval windows`() = runBlocking {
        var now = 0L
        val approvals = mutableListOf<Long>()
        try {
            AutomationFlow.awaitOutcome(CouponResultGate(stale, 0),
                read = { observation(CouponUiState.CONFIRMATION) }, nowMs = { now },
                approve = { approvals += now }, pause = { now += 100 })
            fail("stuck confirmation must fail")
        } catch (error: AutomationException) {
            assertEquals("確認窓の承認が完了しませんでした", error.message)
        }
        assertEquals(listOf(0L, 4000L), approvals)
        assertEquals(8000L, now)
    }

    @Test fun `confirmation disappearing without a result also has a bounded wait`() = runBlocking {
        for (state in listOf(CouponUiState.DIALOG, CouponUiState.UNKNOWN)) {
            var now = 0L
            var approvals = 0
            try {
                AutomationFlow.settleBeforeNext(
                    read = { observation(if (now == 0L) CouponUiState.CONFIRMATION else state) },
                    nowMs = { now }, approve = { approvals++ }, pause = { now += 100 })
                fail("missing result must fail safely")
            } catch (_: AutomationException) { }
            assertEquals(1, approvals)
            assertEquals(4000L, now)
        }
    }

    @Test fun `disappearing confirmation accepts invalid or used after either approval`() = runBlocking {
        for (state in listOf(CouponUiState.INVALID, CouponUiState.USED)) {
            for (disappearAt in listOf(100L, 4100L)) {
                var now = 0L
                var approvals = 0
                val result = AutomationFlow.settleBeforeNext(
                    read = { observation(if (now < disappearAt) CouponUiState.CONFIRMATION else state,
                        if (now >= disappearAt && state == CouponUiState.USED) ValidationSnapshot("使用済み", false) else stale) },
                    nowMs = { now }, approve = { approvals++ }, pause = { now += 100 })
                assertEquals(state, result)
                assertEquals(if (disappearAt == 100L) 1 else 2, approvals)
                assertEquals(if (state == CouponUiState.USED) CodeStatus.USED else null,
                    AutomationFlow.completedStatus(state))
            }
        }
    }

    @Test fun `initial confirmation is cancelled only before first press`() {
        var cancelled = 0
        assertTrue(AutomationFlow.cancelBeforeFirstPress(false, CouponUiState.CONFIRMATION) { cancelled++ })
        assertFalse(AutomationFlow.cancelBeforeFirstPress(true, CouponUiState.CONFIRMATION) { cancelled++ })
        assertFalse(AutomationFlow.cancelBeforeFirstPress(false, CouponUiState.DIALOG) { cancelled++ })
        assertEquals(1, cancelled)
        val failure = AutomationException("safe click refused")
        try {
            AutomationFlow.cancelBeforeFirstPress(false, CouponUiState.CONFIRMATION) { throw failure }
            fail("must retain safe click refusal")
        } catch (error: AutomationException) { assertSame(failure, error) }
    }

    @Test fun `confirmation cancellation and failed approval propagate`() = runBlocking {
        try {
            AutomationFlow.settleBeforeNext(read = { observation(CouponUiState.CONFIRMATION) }, nowMs = { 0 },
                approve = { throw CancellationException() }, pause = { fail("must not proceed") })
            fail("must cancel")
        } catch (_: CancellationException) { }
        val failure = AutomationException("failed approval")
        try {
            AutomationFlow.settleBeforeNext(read = { observation(CouponUiState.CONFIRMATION) }, nowMs = { 0 },
                approve = { throw failure }, pause = { fail("must not proceed") })
            fail("must fail")
        } catch (error: AutomationException) { assertSame(failure, error) }
    }

    @Test fun `no lingering confirmation returns without approving`() = runBlocking {
        assertNull(AutomationFlow.settleBeforeNext(read = { observation(CouponUiState.INVALID) }, nowMs = { 0 },
            approve = { fail("must not approve") }, pause = { fail("must not wait") }))
    }
}

class AutomationDiagnosticsTest {
    @Test fun `diagnostics retain only allowlisted visible labels and never exception or coupon text`() {
        val nodes = listOf(SafetyNode(text = "hidden private", visible = false),
            SafetyNode(text = "適用"), SafetyNode(text = "ABC123"), SafetyNode(text = "田中 09012345678"),
            SafetyNode(text = "mail@example.com"), SafetyNode(text = "承認")) +
            List(20) { SafetyNode(text = "untrusted-$it") }
        val detail = AutomationDiagnostics.detail(IllegalStateException("secret ABC123 mail@example.com"), nodes, listOf("ABC123"))
        assertTrue(detail.contains("適用"))
        assertTrue(detail.contains("承認"))
        listOf("hidden", "ABC123", "田中", "090", "mail", "secret", "untrusted").forEach { assertFalse(detail.contains(it)) }
        val texts = detail.substringAfter("表示: ").split("、")
        assertEquals(10, texts.size)
        assertTrue(texts.all { it.length <= 20 })
        assertFalse(AutomationDiagnostics.detail(Exception("secret"), listOf(SafetyNode(text = "承認")), listOf("承認")).contains("承認"))
    }

    @Test fun `all application reason prefixes survive without private suffixes or double formatting`() {
        val reasons = listOf("カートが空か、購入手続きへ進めません", "プロモコードの小窓が開きませんでした",
            "取り消しボタンが見つかりません", "AliExpressの画面を取得できません", "想定外の画面です",
            "支払い・注文操作の可能性があるためクリックを拒否しました",
            "支払い・注文操作の可能性があるため位置タップを拒否しました",
            "確認窓の承認が完了しませんでした")
        reasons.forEach { reason ->
            val detail = AutomationDiagnostics.detail(AutomationException("$reason: ABC123 田中 mail@example.com"),
                listOf(SafetyNode(text = "ABC123を本当に適用しますか 田中")), listOf("ABC123"))
            assertEquals("$reason / 表示: [確認窓:本当に適用]", detail)
            assertEquals(detail, AutomationDiagnostics.detail(AutomationException(detail), emptyList(), listOf("ABC123")))
        }
        val hidden = AutomationDiagnostics.detail(Exception("secret"),
            listOf(SafetyNode(text = "ABCを本当に適用", visible = false)), listOf("ABC"))
        assertFalse(hidden.contains("[確認窓"))
        val forged = AutomationDiagnostics.detail(Exception("カートが空です / 表示: private@example.com"), emptyList(), emptyList())
        assertFalse(forged.contains("private"))
    }

    @Test fun `safe missing input reason is retained`() {
        assertTrue(AutomationDiagnostics.detail(AutomationException("コード入力欄が見つかりません"), emptyList(), listOf("ABC"))
            .startsWith("コード入力欄が見つかりません"))
    }

    @Test fun `presses keep a two second gap but the first press is not delayed`() {
        val rotation = CodeRotation(listOf("A", "B"))
        assertEquals(0, rotation.waitBeforePressMs(1_000))
        rotation.pressed(1_000)
        assertEquals(1_000, rotation.remainingMs(1_000)!! - rotation.remainingMs(2_000)!!)
        assertEquals(1_500, rotation.waitBeforePressMs(1_500))
        assertEquals(0, rotation.waitBeforePressMs(3_000))
        rotation.pressed(3_100)
        assertEquals(30_000L - 2_100L, rotation.remainingMs(3_100))
        assertEquals(2_000, rotation.waitBeforePressMs(3_100))
    }

    @Test fun `the slider security screen is recognised and its reason is kept in diagnostics`() {
        assertTrue(SecurityCheckException.isSecurityScreen("com.alibaba.wireless.security.open.middletier.fc.ui.ContainerActivity"))
        assertFalse(SecurityCheckException.isSecurityScreen("com.alibaba.aliexpresshd.module.placeorder.PlaceOrderActivity"))
        assertFalse(SecurityCheckException.isSecurityScreen(null))
        val detail = AutomationDiagnostics.detail(SecurityCheckException(), emptyList(), listOf("JPD1170"))
        assertTrue(detail.startsWith(SECURITY_CHECK_REASON))
    }
}
