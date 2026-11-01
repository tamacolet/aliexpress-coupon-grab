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
    @Test fun `最後400msは短い間隔で最後2msはspinへ移る`() {
        assertEquals(100, AutomationTiming.coarseDelayMs(1_000))
        assertEquals(100, AutomationTiming.coarseDelayMs(500))
        assertEquals(0, AutomationTiming.coarseDelayMs(400))
        assertEquals(5, AutomationTiming.fineDelayMs(100))
        assertEquals(1, AutomationTiming.fineDelayMs(3))
        assertEquals(0, AutomationTiming.fineDelayMs(2))
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
