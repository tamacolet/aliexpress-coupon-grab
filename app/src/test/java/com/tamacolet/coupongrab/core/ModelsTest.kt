package com.tamacolet.coupongrab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeValidationTest {
    @Test fun `英数字3から32文字を許可する`() {
        assertTrue(CodeValidation.isValid("Ab3"))
        assertTrue(CodeValidation.isValid("a".repeat(32)))
    }

    @Test fun `空白日本語短すぎるコードを拒否する`() {
        listOf("", "ab", "a".repeat(33), "ABC ", "A B", "あいう", "A-B").forEach {
            assertFalse(it, CodeValidation.isValid(it))
        }
    }

    @Test fun `複数行を状態付きコードへ変換する`() {
        val old = listOf(CouponCode("ABC", CodeStatus.APPLIED))
        val merged = CodeValidation.mergeStatuses("ABC\nDEF123", old)
        assertEquals(listOf(CodeStatus.APPLIED, CodeStatus.WAITING), merged.map { it.status })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `重複コードを拒否する`() {
        CodeValidation.parseMultiline("ABC\nABC")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空の一覧を拒否する`() {
        CodeValidation.parseMultiline("\n")
    }
}

class ScheduleRulesTest {
    private val waiting = CouponCode("WAIT")
    private val failed = CouponCode("FAIL", CodeStatus.FAILURE)
    private val applied = CouponCode("DONE", CodeStatus.APPLIED)
    private val used = CouponCode("USED", CodeStatus.USED)

    @Test fun `時刻指定では失敗も終端で待機だけを実行する`() {
        val schedule = schedule(ScheduleKind.EXACT, listOf(waiting, failed, applied, used))
        assertEquals(listOf(waiting), ScheduleRules.pendingCodes(schedule))
        assertFalse(ScheduleRules.isComplete(schedule))
        assertTrue(ScheduleRules.isComplete(schedule.copy(codes = listOf(failed, applied, used))))
    }

    @Test fun `繰り返しでは失敗を再試行し適用と使用済みを保持する`() {
        val schedule = schedule(ScheduleKind.REPEAT, listOf(waiting, failed, applied, used))
        assertEquals(listOf(waiting, failed), ScheduleRules.pendingCodes(schedule))
        assertTrue(ScheduleRules.isComplete(schedule.copy(codes = listOf(applied, used))))
    }

    @Test fun `時刻指定だけ60秒早く時計補正分をRTCから引く`() {
        assertEquals(940_000, ScheduleRules.alarmTriggerAt(ScheduleKind.EXACT, 1_000_000))
        assertEquals(935_000, ScheduleRules.alarmTriggerAt(ScheduleKind.EXACT, 1_000_000, clockOffsetMs = 5_000))
    }

    @Test fun `繰り返しとNAS延期は60秒早くせず指定時刻に鳴らす`() {
        assertEquals(1_000_000, ScheduleRules.alarmTriggerAt(ScheduleKind.REPEAT, 1_000_000))
        assertEquals(995_000, ScheduleRules.alarmTriggerAt(ScheduleKind.REPEAT, 1_000_000, clockOffsetMs = 5_000))
    }

    @Test fun `繰り返しの次回時刻を間隔から計算する`() {
        val result = ScheduleRules.afterRun(schedule(ScheduleKind.REPEAT, listOf(failed)), 10_000)
        assertEquals(310_000, result.nextRunAtMs)
        assertTrue(result.enabled)
    }

    @Test fun `全コード終端なら繰り返しを停止する`() {
        val result = ScheduleRules.afterRun(schedule(ScheduleKind.REPEAT, listOf(applied, used)), 10_000)
        assertFalse(result.enabled)
        assertEquals(0, result.nextRunAtMs)
    }

    @Test fun `NAS混雑時は30秒延期し開始時刻を保持する`() {
        val schedule = schedule(ScheduleKind.REPEAT, listOf(waiting))
        val first = ScheduleRules.nasDecision(schedule, 100_000, 999_000) as ScheduleRules.NasDecision.Defer
        assertEquals(30_000, first.delayMs)
        assertEquals(100_000, first.startedAtMs)
        val persisted = schedule.copy(deferralStartedAtMs = first.startedAtMs)
        val second = ScheduleRules.nasDecision(persisted, 130_000, 999_000) as ScheduleRules.NasDecision.Defer
        assertEquals(100_000, second.startedAtMs)
    }

    @Test fun `NAS混雑でも10分後と時刻指定は実行する`() {
        val repeat = schedule(ScheduleKind.REPEAT, listOf(waiting)).copy(deferralStartedAtMs = 0)
        assertEquals(ScheduleRules.NasDecision.Execute, ScheduleRules.nasDecision(repeat, 600_000, 999_000))
        val exact = schedule(ScheduleKind.EXACT, listOf(waiting))
        assertEquals(ScheduleRules.NasDecision.Execute, ScheduleRules.nasDecision(exact, 100_000, 999_000))
    }

    @Test fun `次の時刻指定だけをNASへ報告する`() {
        val exact = schedule(ScheduleKind.EXACT, listOf(waiting)).copy(nextRunAtMs = 500)
        val repeat = schedule(ScheduleKind.REPEAT, listOf(waiting)).copy(nextRunAtMs = 100)
        assertEquals(500, ScheduleRules.nextExactAtMs(listOf(repeat, exact)))
    }

    private fun schedule(kind: ScheduleKind, codes: List<CouponCode>) = Schedule(
        id = kind.name, kind = kind, nextRunAtMs = 1_000, codes = codes,
    )
}

class StateTest {
    @Test fun `履歴はミリ秒時刻を保って最新100件に制限する`() {
        var state = AppState()
        repeat(105) { index ->
            state = state.append(HistoryEntry(atMs = 1_000_000_000_000L + index, index.toLong(), "event"))
        }
        assertEquals(100, state.history.size)
        assertEquals(1_000_000_000_005L, state.history.first().atMs)
        assertEquals(1_000_000_000_104L, state.history.last().atMs)
    }

    @Test fun `状態行はbusyとNASを区別する`() {
        val state = AppState(busy = false, nasBusyUntilMs = 123)
        assertEquals("coupongrab busy=false nextExactAtMs=0 nasBusyUntilMs=123", StatusReporter.line(state))
    }
}
