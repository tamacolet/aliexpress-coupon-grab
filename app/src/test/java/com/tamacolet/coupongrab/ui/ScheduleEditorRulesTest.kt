package com.tamacolet.coupongrab.ui

import com.tamacolet.coupongrab.core.CodeStatus
import com.tamacolet.coupongrab.core.CouponCode
import com.tamacolet.coupongrab.core.Schedule
import com.tamacolet.coupongrab.core.ScheduleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleEditorRulesTest {
    private val now = LocalDateTime.of(2026, 11, 1, 12, 0, 0)
        .atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
    private val future = ScheduleEditorRules.formatExactAtMs(now + 60_000)

    @Test fun `追加の初期値は5分繰り返しで保存前の下書き`() {
        val draft = ScheduleEditorRules.newDraft(now)
        assertEquals(ScheduleKind.REPEAT, draft.kind)
        assertEquals("5", draft.repeatMinutesText)
        assertEquals("", draft.codesText)
        assertTrue(ScheduleEditorRules.parseExactAtMs(draft.exactAtText)!! > now)
    }

    @Test fun `有効な時刻指定とコードを完全な予定として組み立てる`() {
        val result = ScheduleEditorRules.build(
            ScheduleEditorDraft(kind = ScheduleKind.EXACT, exactAtText = future, codesText = "Ab3\nXYZ789"),
            persisted = null,
            nowMs = now,
        )
        assertEquals(ScheduleEditorRules.parseExactAtMs(future), result.exactAtMs)
        assertEquals(result.exactAtMs, result.nextRunAtMs)
        assertEquals(listOf(CodeStatus.WAITING, CodeStatus.WAITING), result.codes.map { it.status })
    }

    @Test fun `不正な日時と新規有効化した過去の時刻指定を拒否する`() {
        val invalid = ScheduleEditorDraft(kind = ScheduleKind.EXACT, exactAtText = "2026-02-30 12:00:00", codesText = "ABC")
        assertTrue(runCatching { ScheduleEditorRules.build(invalid, null, now) }.isFailure)
        val past = invalid.copy(exactAtText = ScheduleEditorRules.formatExactAtMs(now))
        assertTrue(runCatching { ScheduleEditorRules.build(past, null, now) }.isFailure)
        assertFalse(ScheduleEditorRules.build(past.copy(enabled = false), null, now).enabled)
    }

    @Test fun `繰り返しは1から60以外を丸めず拒否する`() {
        listOf("", "0", "61", "4.5", "abc").forEach { value ->
            val draft = ScheduleEditorDraft(repeatMinutesText = value, codesText = "ABC")
            assertTrue(value, runCatching { ScheduleEditorRules.build(draft, null, now) }.isFailure)
        }
        val valid = ScheduleEditorRules.build(ScheduleEditorDraft(repeatMinutesText = "60", codesText = "ABC"), null, now)
        assertEquals(60, valid.repeatMinutes)
        assertEquals(now + 60 * 60_000L, valid.nextRunAtMs)
    }

    @Test fun `空空白日本語重複コードは無効でも保存できない`() {
        listOf("", "   ", "AB", "A-B", "あいう", "ABC\nABC").forEach { codes ->
            val draft = ScheduleEditorDraft(codesText = codes, enabled = false)
            assertTrue(codes, runCatching { ScheduleEditorRules.build(draft, null, now) }.isFailure)
        }
    }

    @Test fun `保存時に最新永続状態を使い取得済みと使用済みを上書きしない`() {
        val persisted = schedule(
            enabled = true,
            target = now + 60_000,
            codes = listOf(
                CouponCode("APPLIED", CodeStatus.APPLIED, "最新の結果"),
                CouponCode("USED", CodeStatus.USED),
                CouponCode("FAIL", CodeStatus.FAILURE),
            ),
        )
        val draft = ScheduleEditorRules.fromSchedule(
            persisted.copy(codes = persisted.codes.map { it.copy(status = CodeStatus.WAITING, detail = "") }),
        )
        val result = ScheduleEditorRules.build(draft, persisted, now)
        assertEquals(listOf(CodeStatus.APPLIED, CodeStatus.USED, CodeStatus.FAILURE), result.codes.map { it.status })
        assertEquals("最新の結果", result.codes.first().detail)
    }

    @Test fun `時刻指定の新しい日時と再有効化では失敗だけ待ちへ戻す`() {
        val codes = listOf(
            CouponCode("APPLIED", CodeStatus.APPLIED),
            CouponCode("USED", CodeStatus.USED),
            CouponCode("FAIL", CodeStatus.FAILURE, "期限切れ"),
        )
        val persisted = schedule(enabled = false, target = now + 60_000, codes = codes)
        val newTarget = ScheduleEditorRules.fromSchedule(persisted).copy(
            exactAtText = ScheduleEditorRules.formatExactAtMs(now + 120_000),
        )
        val moved = ScheduleEditorRules.build(newTarget, persisted, now)
        assertEquals(listOf(CodeStatus.APPLIED, CodeStatus.USED, CodeStatus.WAITING), moved.codes.map { it.status })
        assertEquals("", moved.codes.last().detail)

        val reenabled = ScheduleEditorRules.build(ScheduleEditorRules.fromSchedule(persisted).copy(enabled = true), persisted, now)
        assertEquals(CodeStatus.WAITING, reenabled.codes.last().status)
        assertEquals(CodeStatus.APPLIED, reenabled.codes.first().status)
        assertEquals(CodeStatus.USED, reenabled.codes[1].status)
    }

    @Test fun `変更のない繰り返し保存では次回時刻を延ばさない`() {
        val persisted = Schedule(
            id = "repeat", kind = ScheduleKind.REPEAT, repeatMinutes = 5,
            nextRunAtMs = now + 123_000, codes = listOf(CouponCode("ABC")),
        )
        val result = ScheduleEditorRules.build(ScheduleEditorRules.fromSchedule(persisted), persisted, now)
        assertEquals(now + 123_000, result.nextRunAtMs)
    }

    private fun schedule(enabled: Boolean, target: Long, codes: List<CouponCode>) = Schedule(
        id = "exact", enabled = enabled, kind = ScheduleKind.EXACT,
        exactAtMs = target, nextRunAtMs = if (enabled) target else 0, codes = codes,
    )
}
