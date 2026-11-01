package com.tamacolet.coupongrab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunQueueTest {
    @Test fun `同時アラームのIDを失わず時刻指定を優先する`() {
        val repeat = RunRequest("repeat", ScheduleKind.REPEAT)
        val exact1 = RunRequest("exact1", ScheduleKind.EXACT)
        val exact2 = RunRequest("exact2", ScheduleKind.EXACT)
        var queue = emptyList<RunRequest>()
        queue = RunQueue.enqueue(queue, repeat)
        queue = RunQueue.enqueue(queue, exact1)
        queue = RunQueue.enqueue(queue, exact2)
        assertEquals(exact1, RunQueue.next(queue))
        assertEquals(exact2, RunQueue.next(RunQueue.remove(queue, exact1)))
        assertEquals(3, queue.size)
    }

    @Test fun `同じ予定の再通知は重複させず種別を更新する`() {
        val old = RunRequest("one", ScheduleKind.REPEAT)
        val new = RunRequest("one", ScheduleKind.EXACT)
        val queue = RunQueue.enqueue(listOf(old), new)
        assertEquals(listOf(new), queue)
    }
}

class RunFailureReducerTest {
    private val base = Schedule(
        id = "one",
        kind = ScheduleKind.EXACT,
        nextRunAtMs = 1_000,
        codes = listOf(
            CouponCode("DONE", CodeStatus.APPLIED, "success"),
            CouponCode("USED", CodeStatus.USED, "used"),
            CouponCode("NEXT"),
            CouponCode("FAILED", CodeStatus.FAILURE),
        ),
    )

    @Test fun `一つ成功した後の例外でも成功と使用済みを保持する`() {
        val result = RunFailureReducer.apply(AppState(schedules = listOf(base)), "one", "screen lost")
        val schedule = result.schedules.single()
        assertEquals(CodeStatus.APPLIED, schedule.codes[0].status)
        assertEquals("success", schedule.codes[0].detail)
        assertEquals(CodeStatus.USED, schedule.codes[1].status)
        assertEquals(CodeStatus.FAILURE, schedule.codes[2].status)
        assertEquals("screen lost", schedule.codes[2].detail)
    }

    @Test fun `削除済み予定を復活させない`() {
        val state = AppState(schedules = emptyList())
        assertEquals(state, RunFailureReducer.apply(state, "deleted", "failure"))
    }

    @Test fun `無効化した予定を失敗処理で再有効化しない`() {
        val state = AppState(schedules = listOf(base.copy(enabled = false)))
        val result = RunFailureReducer.apply(state, "one", "failure")
        assertFalse(result.schedules.single().enabled)
    }
}

class ExecutionPolicyTest {
    @Test fun `古い時刻指定だけを stale と判定する`() {
        val exact = Schedule(
            id = "exact", kind = ScheduleKind.EXACT, nextRunAtMs = 10_000,
            codes = listOf(CouponCode("ABC")),
        )
        assertFalse(ExecutionPolicy.isStaleExact(exact, 10_000 + ExecutionPolicy.EXACT_STALE_TOLERANCE_MS))
        assertTrue(ExecutionPolicy.isStaleExact(exact, 10_001 + ExecutionPolicy.EXACT_STALE_TOLERANCE_MS))
        assertFalse(ExecutionPolicy.isStaleExact(exact.copy(kind = ScheduleKind.REPEAT), Long.MAX_VALUE))
    }
}

class ActivityEventReducerTest {
    @Test fun `カートから会計へ進みダイアログでは会計Activityを保持する`() {
        var activity = ActivityEventReducer.reduce("", ALI_PACKAGE, CART_ACTIVITY)
        activity = ActivityEventReducer.reduce(activity, ALI_PACKAGE, CHECKOUT_ACTIVITY)
        activity = ActivityEventReducer.reduce(activity, ALI_PACKAGE, "android.app.Dialog")
        activity = ActivityEventReducer.reduce(activity, ALI_PACKAGE, "android.widget.FrameLayout")
        assertEquals(CHECKOUT_ACTIVITY, activity)
    }

    @Test fun `不明なAliのActivityは不明な画面として記録する`() {
        val result = ActivityEventReducer.reduce(CHECKOUT_ACTIVITY, ALI_PACKAGE, "com.example.UnknownActivity")
        assertEquals("com.example.UnknownActivity", result)
    }

    @Test fun `他アプリのイベントでは変更しない`() {
        assertEquals(CART_ACTIVITY, ActivityEventReducer.reduce(CART_ACTIVITY, "other", "other.MainActivity"))
    }
}

class AutomationTimingTest {
    @Test fun `最後20msまで中断可能な待機をする`() {
        assertEquals(100, AutomationTiming.coarseDelayMs(1_000))
        assertEquals(100, AutomationTiming.coarseDelayMs(400))
        assertEquals(80, AutomationTiming.coarseDelayMs(100))
        assertEquals(1, AutomationTiming.coarseDelayMs(21))
        assertEquals(0, AutomationTiming.coarseDelayMs(20))
        assertEquals(0, AutomationTiming.coarseDelayMs(-1))
    }

    @Test fun `補正済みepochを単調時計のtargetへ写像する`() {
        assertEquals(
            5_000_000_000L,
            AutomationTiming.monotonicTargetNanos(
                targetEpochMs = 10_000,
                correctedNowMs = 7_000,
                currentElapsedNanos = 2_000_000_000L,
            ),
        )
    }
}

class CurrentRequestTest {
    @Test fun `編集前の種類や時刻で早く実行しない`() {
        val schedule = Schedule(id = "one", kind = ScheduleKind.EXACT, nextRunAtMs = 200_000, codes = listOf(CouponCode("ABC")))
        assertFalse(ExecutionPolicy.accepts(schedule, RunRequest("one", ScheduleKind.REPEAT), 200_000))
        assertFalse(ExecutionPolicy.accepts(schedule, RunRequest("one", ScheduleKind.EXACT), 100_000))
        assertTrue(ExecutionPolicy.accepts(schedule, RunRequest("one", ScheduleKind.EXACT), 140_000))
        assertFalse(ExecutionPolicy.accepts(schedule.copy(kind = ScheduleKind.REPEAT), RunRequest("one", ScheduleKind.REPEAT), 140_000))
        assertTrue(ExecutionPolicy.accepts(schedule.copy(kind = ScheduleKind.REPEAT), RunRequest("one", ScheduleKind.REPEAT), 200_000))
    }
}

class ReviewRegressionTest {
    private val schedule = Schedule(id = "repeat", nextRunAtMs = 1_000, codes = listOf(CouponCode("ABC")))
    private val request = RunRequest("repeat", ScheduleKind.REPEAT)

    @Test fun `最新のNAS状態から占有と延期を一緒に決める`() {
        val idle = AppState(schedules = listOf(schedule))
        val (claimed, execute) = RunAdmission.claim(idle, request, 1_000)
        assertTrue(claimed.busy)
        assertEquals(ScheduleRules.NasDecision.Execute, execute)
        val (deferred, decision) = RunAdmission.claim(idle.copy(nasBusyUntilMs = 99_000), request, 1_000)
        assertFalse(deferred.busy)
        assertEquals(31_000, deferred.schedules.single().nextRunAtMs)
        assertEquals(ScheduleRules.NasDecision.Defer(), decision)
        assertEquals(99_000, deferred.nasBusyUntilMs)
        assertEquals(null, RunAdmission.claim(idle.copy(schedules = emptyList()), request, 1_000).second)
        assertEquals(null, RunAdmission.claim(idle.copy(schedules = listOf(schedule.copy(enabled = false))), request, 1_000).second)
    }

    @Test fun `近い時刻指定は繰り返しを延期し60秒前に時刻指定を占有する`() {
        val exact = schedule.copy(id = "exact", kind = ScheduleKind.EXACT, nextRunAtMs = 181_000)
        val state = AppState(schedules = listOf(schedule, exact))
        assertEquals(ScheduleRules.NasDecision.Defer(), RunAdmission.claim(state, request, 1_000).second)
        val (claimed, decision) = RunAdmission.claim(state.copy(nasBusyUntilMs = Long.MAX_VALUE),
            RunRequest("exact", ScheduleKind.EXACT), 121_000)
        assertTrue(claimed.busy)
        assertEquals(ScheduleRules.NasDecision.Execute, decision)
    }

    @Test fun `最初の準備が失敗しても次の全コードが指定時刻を待つ`() = kotlinx.coroutines.runBlocking {
        val actions = mutableListOf<String>()
        try {
            AutomationFlow.applyAtTarget<String>(10_000, prepare = { throw AutomationException("prepare failed") },
                await = { actions += "wait:$it" }, apply = { actions += "apply" })
        } catch (_: AutomationException) { }
        repeat(2) {
            AutomationFlow.applyAtTarget(10_000, prepare = { actions += "prepare"; "ABC" },
                await = { actions += "wait:$it" }, apply = { actions += "apply:$it" })
        }
        assertEquals(listOf("prepare", "wait:10000", "apply:ABC", "prepare", "wait:10000", "apply:ABC"), actions)
    }

    @Test fun `snapshot precedes final target await and dispatch has no intervening read`() = kotlinx.coroutines.runBlocking {
        val actions = mutableListOf<String>()
        var now = 0L
        AutomationFlow.applyAtTarget(10_000, prepare = { actions += "prepare"; "target" },
            await = { now = it; actions += "await:$it" }, snapshotLeadMs = 250,
            snapshot = { actions += "snapshot:$it"; now += 80 },
            apply = { actions += "dispatch:$it:$now" })
        assertEquals(listOf("prepare", "await:9750", "snapshot:target", "await:10000", "dispatch:target:10000"), actions)
    }

    @Test fun `小窓の待機切れを3回まで再試行して最終失敗だけを投げる`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        var recovered = 0
        val result = AutomationFlow.openDialog(open = {
            if (++attempts < 3) throw AutomationException("timeout")
            "dialog"
        }, recover = { recovered++ })
        assertEquals("dialog", result)
        assertEquals(3, attempts)
        assertEquals(2, recovered)
        attempts = 0
        recovered = 0
        val timeout = AutomationException("last timeout")
        try {
            AutomationFlow.openDialog<String>(open = { attempts++; throw timeout }, recover = { recovered++ })
            org.junit.Assert.fail("must fail after three attempts")
        } catch (error: AutomationException) {
            assertTrue(error === timeout)
            assertEquals(3, attempts)
            assertEquals(2, recovered)
        }
    }

    @Test fun `小窓再試行で取り消しを握りつぶさない`() = kotlinx.coroutines.runBlocking {
        try {
            AutomationFlow.openDialog<String>(open = { throw kotlinx.coroutines.CancellationException("cancel") },
                recover = { error("must not recover cancellation") })
            org.junit.Assert.fail("must cancel")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun `画面起動の時間切れと全体の時間切れを区別する`() = kotlinx.coroutines.runBlocking {
        AutomationFlow.awaitWake(1_000) {}
        try {
            AutomationFlow.awaitWake(5) { kotlinx.coroutines.awaitCancellation() }
            org.junit.Assert.fail("must fail wake")
        } catch (error: AutomationException) { assertEquals("画面を起こせませんでした", error.message) }
        try {
            kotlinx.coroutines.withTimeout(5) {
                AutomationFlow.awaitWake(1_000) { kotlinx.coroutines.awaitCancellation() }
            }
            org.junit.Assert.fail("must retain outer timeout")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
    }

    @Test fun `支払い欄より下の行は追加スクロールを求める`() {
        assertFalse(AutomationFlow.shouldScrollPromo(99, 100))
        assertTrue(AutomationFlow.shouldScrollPromo(100, 100))
        assertTrue(AutomationFlow.shouldScrollPromo(101, 100))
        assertFalse(AutomationFlow.shouldScrollPromo(101, null))
    }

    @Test fun `表示操作が成功しても支払い欄に重なる時は続けてスクロールする`() {
        val actions = mutableListOf<String>()
        assertTrue(AutomationFlow.revealPromo(true,
            show = { actions += "show"; true }, scroll = { actions += "scroll"; true }))
        assertEquals(listOf("show", "scroll"), actions)
        actions.clear()
        AutomationFlow.revealPromo(false, show = { actions += "show"; true }, scroll = { actions += "scroll"; true })
        assertEquals(listOf("show"), actions)
        actions.clear()
        AutomationFlow.revealPromo(false, show = { actions += "show"; false }, scroll = { actions += "scroll"; true })
        assertEquals(listOf("show", "scroll"), actions)
    }

    @Test fun `全体例外でも個別結果でも同じ失敗を通知対象にしない`() {
        val failed = schedule.copy(codes = listOf(CouponCode("ABC", CodeStatus.FAILURE)))
        val state = AppState(schedules = listOf(failed))
        val again = RunFailureReducer.apply(state, "repeat", "new detail").schedules.single().codes.single()
        assertFalse(AutomationFlow.statusChanged(CodeStatus.FAILURE, again.status))
        assertTrue(AutomationFlow.statusChanged(CodeStatus.WAITING, again.status))
        CodeStatus.entries.forEach { assertFalse(AutomationFlow.statusChanged(it, it)) }
        assertTrue(AutomationFlow.statusChanged(CodeStatus.FAILURE, CodeStatus.APPLIED))
    }
}
