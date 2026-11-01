package com.tamacolet.coupongrab.ui

import com.tamacolet.coupongrab.core.CodeStatus
import com.tamacolet.coupongrab.core.CodeValidation
import com.tamacolet.coupongrab.core.Schedule
import com.tamacolet.coupongrab.core.ScheduleKind
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

private val jst: ZoneId = ZoneId.of("Asia/Tokyo")
private val exactFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
    .withResolverStyle(ResolverStyle.STRICT)

data class ScheduleEditorDraft(
    val id: String = UUID.randomUUID().toString(),
    val kind: ScheduleKind = ScheduleKind.REPEAT,
    val exactAtText: String = "",
    val repeatMinutesText: String = "5",
    val codesText: String = "",
    val enabled: Boolean = true,
)

object ScheduleEditorRules {
    fun newDraft(nowMs: Long): ScheduleEditorDraft = ScheduleEditorDraft(
        exactAtText = formatExactAtMs(nowMs + 5 * 60_000L),
    )

    fun fromSchedule(schedule: Schedule): ScheduleEditorDraft = ScheduleEditorDraft(
        id = schedule.id,
        kind = schedule.kind,
        exactAtText = schedule.exactAtMs?.let(::formatExactAtMs).orEmpty(),
        repeatMinutesText = schedule.repeatMinutes.toString(),
        codesText = schedule.codes.joinToString("\n") { it.code },
        enabled = schedule.enabled,
    )

    fun build(draft: ScheduleEditorDraft, persisted: Schedule?, nowMs: Long): Schedule {
        val codes = CodeValidation.mergeStatuses(draft.codesText, persisted?.codes.orEmpty())
        return when (draft.kind) {
            ScheduleKind.EXACT -> {
                val target = parseExactAtMs(draft.exactAtText)
                require(target != null) { "日時はYYYY-MM-DD HH:mm:ss形式のJSTで入力してください" }
                require(!draft.enabled || target > nowMs) { "有効な時刻指定は現在より後の日時にしてください" }
                val rescheduled = persisted == null || persisted.kind != ScheduleKind.EXACT ||
                    persisted.exactAtMs != target || (draft.enabled && !persisted.enabled)
                Schedule(
                    id = draft.id,
                    enabled = draft.enabled,
                    kind = ScheduleKind.EXACT,
                    exactAtMs = target,
                    repeatMinutes = 5,
                    nextRunAtMs = if (draft.enabled) target else 0,
                    codes = if (rescheduled) codes.map {
                        if (it.status == CodeStatus.FAILURE) it.copy(status = CodeStatus.WAITING, detail = "") else it
                    } else codes,
                )
            }
            ScheduleKind.REPEAT -> {
                val minutes = draft.repeatMinutesText.toIntOrNull()
                require(minutes != null && minutes in 1..60) { "繰り返し間隔は1〜60分の整数で入力してください" }
                val keepNext = draft.enabled && persisted?.enabled == true &&
                    persisted.kind == ScheduleKind.REPEAT && persisted.repeatMinutes == minutes &&
                    persisted.nextRunAtMs > 0
                Schedule(
                    id = draft.id,
                    enabled = draft.enabled,
                    kind = ScheduleKind.REPEAT,
                    exactAtMs = null,
                    repeatMinutes = minutes,
                    nextRunAtMs = when {
                        !draft.enabled -> 0
                        keepNext -> persisted?.nextRunAtMs ?: nowMs + minutes * 60_000L
                        else -> nowMs + minutes * 60_000L
                    },
                    codes = codes,
                )
            }
        }
    }

    fun parseExactAtMs(value: String): Long? = runCatching {
        LocalDateTime.parse(value, exactFormat).atZone(jst).toInstant().toEpochMilli()
    }.getOrNull()

    fun formatExactAtMs(value: Long): String = exactFormat.format(Instant.ofEpochMilli(value).atZone(jst))
}
