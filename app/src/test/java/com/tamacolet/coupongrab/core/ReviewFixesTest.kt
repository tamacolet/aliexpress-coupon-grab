package com.tamacolet.coupongrab.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReviewFixesTest {
    private val exact = Schedule(id = "exact", kind = ScheduleKind.EXACT, nextRunAtMs = 100_000,
        codes = listOf(CouponCode("ABC")))
    private val repeat = exact.copy(id = "repeat", kind = ScheduleKind.REPEAT)
    private val securityActivity = "com.alibaba.wireless.security.open.ContainerActivity"

    @Test fun `hold rejects both kinds and stale queued exact while preserving schedule and reason`() {
        val requests = listOf(exact, repeat).map { RunRequest(it.id, it.kind) }
        var state = AppState(schedules = listOf(exact, repeat), pendingRuns = requests, securityHoldSinceMs = 1)
        requests.forEach { request ->
            val (next, decision) = RunAdmission.claim(state, request, 1_000_000, 12)
            assertEquals(ScheduleRules.NasDecision.SecurityHold, decision)
            assertFalse(next.busy)
            assertEquals(SECURITY_CHECK_REASON, next.history.last().detail)
            assertEquals(request.scheduleId, next.history.last().scheduleId)
            assertEquals("security_hold_skip", next.history.last().event)
            assertEquals(12L, next.history.last().elapsedRealtimeMs)
            assertEquals(exact, next.schedules.first())
            assertEquals(requests, next.pendingRuns)
            state = next
        }
        assertEquals(1_300_000L, state.schedules.last().nextRunAtMs)
    }

    @Test fun `security detection history survives normal history filtering`() {
        val entry = HistoryEntry(123, 456, "security_hold", exact.id, detail = SECURITY_CHECK_REASON)
        assertEquals(listOf(entry), HistoryRules.record(AppState(), entry).history)
    }

    @Test fun `clearing hold admits due exact normally`() {
        val state = AppState(schedules = listOf(exact), securityHoldSinceMs = 1)
        val (next, decision) = RunAdmission.claim(state.copy(securityHoldSinceMs = null),
            RunRequest(exact.id, exact.kind), 100_000)
        assertEquals(ScheduleRules.NasDecision.Execute, decision)
        assertTrue(next.busy)
    }

    @Test fun `common wait detects security before another root read or timeout`() = runBlocking {
        var now = 0L
        var reads = 0
        try {
            AutomationFlow.waitFor(20_000, { if (now == 0L) CART_ACTIVITY else securityActivity }, { now }, {
                reads++
                AutomationFlow.recoverableRead<String> { throw AutomationException("not ready") }.getOrNull()
            }, { now += 100 })
            fail("must propagate")
        } catch (_: SecurityCheckException) { }
        assertEquals(1, reads)
        assertEquals(100L, now)
    }

    @Test fun `recoverable read propagates security and cancellation but tolerates missing root`() {
        for (error in listOf(SecurityCheckException(), CancellationException("cancel"))) {
            try {
                AutomationFlow.recoverableRead<Unit> { throw error }.getOrNull()
                fail("must propagate")
            } catch (caught: Exception) { assertSame(error, caught) }
        }
        assertNull(AutomationFlow.recoverableRead<Unit> { throw AutomationException("not ready") }.getOrNull())
    }

    @Test fun `common wait returns ready values and times out ordinary missing screens`() = runBlocking {
        assertEquals("ready", AutomationFlow.waitFor(100, { CART_ACTIVITY }, { 0 }, { "ready" }, {}))
        var now = 0L
        try {
            AutomationFlow.waitFor<String>(100, { CART_ACTIVITY }, { now }, { null }, { now += 100 })
            fail("must timeout")
        } catch (_: AutomationException) { }
        assertEquals(100L, now)
    }

    @Test fun `delayed previous applied or used rotates preparing code instead of failing it`() {
        for (status in listOf(CodeStatus.APPLIED, CodeStatus.USED)) {
            val cycle = CodeRotation(listOf("A", "B", "C"))
            cycle.complete("A", null)
            assertTrue(cycle.finishIfPending("A", status))
            assertFalse(cycle.afterRecovery("B", previousCompleted = true))
            assertEquals(listOf("C", "B"), cycle.pending())
            assertFalse(cycle.finishIfPending("A", CodeStatus.FAILURE))
        }
    }

    @Test fun `unrecovered error still fails only preparing code`() {
        val cycle = CodeRotation(listOf("A", "B"))
        assertTrue(cycle.afterRecovery("A", previousCompleted = false))
        assertEquals(listOf("B"), cycle.pending())
    }

    @Test fun `twenty fifth press expires shared execution even with ample time left`() {
        val cycle = CodeRotation((1..100).map { "CODE$it" })
        repeat(24) { cycle.pressed(it * 2_000L) }
        assertFalse(cycle.expired(48_000))
        cycle.pressed(48_000)
        assertTrue(cycle.remainingMs(48_000)!! > 0)
        assertTrue(cycle.expired(48_000))
        assertNull(cycle.next(48_000))
        assertEquals(100, cycle.pending().size)
        try { cycle.pressed(50_000); fail("26th press") } catch (_: IllegalStateException) { }
    }

    @Test fun `precheck exception and cancellation release claimed busy state`() = runBlocking {
        for (error in listOf(AutomationException("precheck"), CancellationException("cancelled"))) {
            var state = RunAdmission.claim(AppState(schedules = listOf(exact)),
                RunRequest(exact.id, exact.kind), 100_000).first
            assertTrue(state.busy)
            try {
                AutomationFlow.withCleanup(action = { throw error }, cleanup = { state = state.copy(busy = false) })
            } catch (caught: Exception) { assertSame(error, caught) }
            assertFalse(state.busy)
        }
    }

    @Test fun `deletion cancel or busy preserves all and confirmation deletes only target schedule and queue`() {
        val state = AppState(schedules = listOf(exact, repeat),
            pendingRuns = listOf(RunRequest(exact.id, exact.kind), RunRequest(repeat.id, repeat.kind)))
        assertSame(state, ScheduleDeletion.apply(state, exact.id, false))
        val busy = state.copy(busy = true)
        assertSame(busy, ScheduleDeletion.apply(busy, exact.id, true))
        val deleted = ScheduleDeletion.apply(state, exact.id, true)
        assertEquals(listOf(repeat), deleted.schedules)
        assertEquals(listOf(RunRequest(repeat.id, repeat.kind)), deleted.pendingRuns)
    }

    @Test fun `recovery refuses stale used even after long wait and confirmation approval`() = runBlocking {
        val used = ValidationSnapshot("使用済み", false)
        val gate = CouponResultGate(used, 0)
        val read = { CouponObservation(CouponUiState.USED, used) }
        assertNull(AutomationFlow.settleBeforeNext(read, { 99_999 }, {}, {}, gate))
        assertEquals(CouponResultGate.Action.APPROVE,
            gate.observe(CouponObservation(CouponUiState.CONFIRMATION, used), 100_000))
        assertNull(AutomationFlow.settleBeforeNext(read, { 100_100 }, {}, {}, gate))
        gate.observe(CouponObservation(CouponUiState.DIALOG, ValidationSnapshot("", false)), 100_200)
        assertEquals(CouponUiState.USED, AutomationFlow.settleBeforeNext(read, { 100_300 }, {}, {}, gate))
    }
}
