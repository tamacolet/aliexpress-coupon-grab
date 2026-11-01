package com.tamacolet.coupongrab.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRulesTest {
    @Test fun `実押下時刻を試行へ使い結果へ更新しても保持する`() {
        val attempted = HistoryRules.record(AppState(), entry(
            event = "apply", atMs = 9_000, elapsedRealtimeMs = 90,
            detail = "押下 correctedAtMs=1234",
        ))
        assertEquals(1_234L, attempted.history.single().atMs)

        val completed = HistoryRules.record(attempted, entry(event = "code_applied", atMs = 9_999, detail = "取得成功"))
        val result = completed.history.single()
        assertEquals("code_applied", result.event)
        assertEquals(1_234L, result.atMs)
        assertEquals(90L, result.elapsedRealtimeMs)
        assertEquals("取得成功", result.detail)
    }

    @Test fun `再試行は前回を失敗確定し最終成功は再試行の時刻を保つ`() {
        var state = HistoryRules.record(AppState(), entry(
            event = "apply", atMs = 1_000, detail = "correctedAtMs=1100",
        ))
        state = HistoryRules.record(state, entry(
            event = "apply_retry", atMs = 2_000, detail = "correctedAtMs=2100",
        ))
        assertEquals(listOf("code_failure", "apply_retry"), state.history.map { it.event })
        assertEquals("適用できません", state.history.first().detail)
        assertEquals(1_100L, state.history.first().atMs)
        assertEquals(2_100L, state.history.last().atMs)

        state = HistoryRules.record(state, entry(event = "code_applied", atMs = 3_000))
        assertEquals(listOf("code_failure", "code_applied"), state.history.map { it.event })
        assertEquals(2_100L, state.history.last().atMs)
    }

    @Test fun `rotated invalid attempts finish history without finalizing coupon and preserve all press times`() {
        var state = AppState(schedules = listOf(Schedule(id = "S", nextRunAtMs = 1000, codes = listOf(CouponCode("A"), CouponCode("B")))))
        val events = listOf(
            entry("apply", code = "A", detail = "correctedAtMs=1000"),
            entry("code_failure", code = "A", detail = "適用できません"),
            entry("apply", code = "B", detail = "correctedAtMs=2000"),
            entry("code_used", code = "B"),
            entry("apply", code = "A", detail = "correctedAtMs=3000"),
            entry("code_applied", code = "A"),
        )
        events.forEach { state = HistoryRules.record(state, it) }
        assertEquals(listOf("code_failure", "code_used", "code_applied"), state.history.map { it.event })
        assertEquals(listOf(1000L, 2000L, 3000L), state.history.map { it.atMs })
        assertEquals("適用できません", state.history.first().detail)
        assertEquals(listOf(CodeStatus.WAITING, CodeStatus.WAITING), state.schedules.single().codes.map { it.status })
    }

    @Test fun `別コードと別予定の未確定試行を上書きしない`() {
        var state = AppState()
        state = HistoryRules.record(state, entry(event = "apply", scheduleId = "A", code = "ONE", atMs = 1))
        state = HistoryRules.record(state, entry(event = "apply", scheduleId = "A", code = "TWO", atMs = 2))
        state = HistoryRules.record(state, entry(event = "apply", scheduleId = "B", code = "ONE", atMs = 3))
        state = HistoryRules.record(state, entry(event = "code_used", scheduleId = "A", code = "ONE", atMs = 4))

        assertEquals(listOf("code_used", "apply", "apply"), state.history.map { it.event })
        assertEquals(listOf(1L, 2L, 3L), state.history.map { it.atMs })
    }

    @Test fun `対応する試行がない結果は新しい履歴行にする`() {
        val state = HistoryRules.record(AppState(), entry(event = "code_failure", atMs = 123, detail = "無効"))
        assertEquals("code_failure", state.history.single().event)
        assertEquals(123L, state.history.single().atMs)
        assertEquals("無効", state.history.single().detail)
    }

    @Test fun `診断イベントは履歴へ追加しない`() {
        val initial = HistoryRules.record(AppState(), entry(event = "apply"))
        listOf("code_error", "clock", "run_complete", "nas_defer", "cleanup", "cleanup_failure", "unknown")
            .forEach { event ->
                assertEquals(event, initial, HistoryRules.record(initial, entry(event = event)))
            }
    }

    @Test fun `履歴は最新100件に制限する`() {
        var state = AppState()
        repeat(105) { index ->
            state = HistoryRules.record(state, entry(event = "apply", atMs = index.toLong()))
        }
        assertEquals(HISTORY_LIMIT, state.history.size)
        assertEquals(5L, state.history.first().atMs)
        assertEquals(104L, state.history.last().atMs)
    }

    private fun entry(
        event: String,
        atMs: Long = 1_000,
        elapsedRealtimeMs: Long = 10,
        scheduleId: String = "S",
        code: String = "ABC",
        detail: String = "",
    ) = HistoryEntry(atMs, elapsedRealtimeMs, event, scheduleId, code, detail)
}
