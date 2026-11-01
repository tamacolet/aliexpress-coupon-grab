package com.tamacolet.coupongrab.data

import com.tamacolet.coupongrab.core.AppState
import com.tamacolet.coupongrab.core.ClockSource
import com.tamacolet.coupongrab.core.CodeStatus
import com.tamacolet.coupongrab.core.CouponCode
import com.tamacolet.coupongrab.core.HistoryEntry
import com.tamacolet.coupongrab.core.RunRequest
import com.tamacolet.coupongrab.core.Schedule
import com.tamacolet.coupongrab.core.ScheduleKind
import com.tamacolet.coupongrab.core.TimeCorrection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class JsonStateStore(private val file: File) {
    private val lock = Companion.lock

    fun load(): AppState = synchronized(lock) {
        if (!file.exists()) return@synchronized AppState()
        runCatching { fromJson(JSONObject(file.readText(Charsets.UTF_8))) }.getOrDefault(AppState())
    }

    fun save(state: AppState) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(toJson(state).toString(2).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun update(transform: (AppState) -> AppState): AppState = synchronized(lock) {
        val current = if (file.exists()) {
            runCatching { fromJson(JSONObject(file.readText(Charsets.UTF_8))) }.getOrDefault(AppState())
        } else AppState()
        val updated = transform(current)
        save(updated)
        updated
    }

    companion object {
        private val lock = Any()
    }
}

private fun toJson(state: AppState): JSONObject = JSONObject().apply {
    put("version", 1)
    put("busy", state.busy)
    put("nasBusyUntilMs", state.nasBusyUntilMs)
    put("correction", JSONObject().apply {
        put("source", state.correction.source.name)
        put("offsetMs", state.correction.offsetMs)
        put("anchorElapsedMs", state.correction.anchorElapsedMs)
        put("measuredAtMs", state.correction.measuredAtMs)
        put("rttMs", state.correction.rttMs)
    })
    put("schedules", JSONArray().apply {
        state.schedules.forEach { schedule ->
            put(JSONObject().apply {
                put("id", schedule.id)
                put("enabled", schedule.enabled)
                put("kind", schedule.kind.name)
                put("exactAtMs", schedule.exactAtMs ?: JSONObject.NULL)
                put("repeatMinutes", schedule.repeatMinutes)
                put("nextRunAtMs", schedule.nextRunAtMs)
                put("deferralStartedAtMs", schedule.deferralStartedAtMs ?: JSONObject.NULL)
                put("codes", JSONArray().apply {
                    schedule.codes.forEach { code ->
                        put(JSONObject().apply {
                            put("code", code.code)
                            put("status", code.status.name)
                            put("detail", code.detail)
                        })
                    }
                })
            })
        }
    })
    put("pendingRuns", JSONArray().apply {
        state.pendingRuns.forEach { request ->
            put(JSONObject().apply {
                put("scheduleId", request.scheduleId)
                put("kind", request.kind.name)
            })
        }
    })
    put("history", JSONArray().apply {
        state.history.forEach { entry ->
            put(JSONObject().apply {
                put("atMs", entry.atMs)
                put("elapsedRealtimeMs", entry.elapsedRealtimeMs)
                put("event", entry.event)
                put("scheduleId", entry.scheduleId)
                put("code", entry.code)
                put("detail", entry.detail)
            })
        }
    })
}

private fun fromJson(root: JSONObject): AppState {
    require(root.optInt("version", 0) == 1) { "Unknown state version" }
    val correctionJson = root.getJSONObject("correction")
    val correction = TimeCorrection(
        source = ClockSource.valueOf(correctionJson.getString("source")),
        offsetMs = correctionJson.getLong("offsetMs"),
        anchorElapsedMs = correctionJson.getLong("anchorElapsedMs"),
        measuredAtMs = correctionJson.getLong("measuredAtMs"),
        rttMs = correctionJson.getLong("rttMs"),
    )
    val schedulesJson = root.getJSONArray("schedules")
    val schedules = List(schedulesJson.length()) { index ->
        val item = schedulesJson.getJSONObject(index)
        val codesJson = item.getJSONArray("codes")
        Schedule(
            id = item.getString("id"),
            enabled = item.getBoolean("enabled"),
            kind = ScheduleKind.valueOf(item.getString("kind")),
            exactAtMs = if (item.isNull("exactAtMs")) null else item.getLong("exactAtMs"),
            repeatMinutes = item.getInt("repeatMinutes"),
            nextRunAtMs = item.getLong("nextRunAtMs"),
            deferralStartedAtMs = if (item.isNull("deferralStartedAtMs")) null else item.getLong("deferralStartedAtMs"),
            codes = List(codesJson.length()) { codeIndex ->
                val code = codesJson.getJSONObject(codeIndex)
                CouponCode(
                    code = code.getString("code"),
                    status = CodeStatus.valueOf(code.getString("status")),
                    detail = code.optString("detail"),
                )
            },
        )
    }
    val historyJson = root.getJSONArray("history")
    val history = List(historyJson.length()) { index ->
        val item = historyJson.getJSONObject(index)
        HistoryEntry(
            atMs = item.getLong("atMs"),
            elapsedRealtimeMs = item.getLong("elapsedRealtimeMs"),
            event = item.getString("event"),
            scheduleId = item.optString("scheduleId"),
            code = item.optString("code"),
            detail = item.optString("detail"),
        )
    }
    val pendingRunsJson = root.optJSONArray("pendingRuns") ?: JSONArray()
    val pendingRuns = List(pendingRunsJson.length()) { index ->
        val item = pendingRunsJson.getJSONObject(index)
        RunRequest(
            scheduleId = item.getString("scheduleId"),
            kind = ScheduleKind.valueOf(item.getString("kind")),
        )
    }
    return AppState(
        schedules = schedules,
        history = history.takeLast(100),
        correction = correction,
        nasBusyUntilMs = root.optLong("nasBusyUntilMs"),
        busy = root.optBoolean("busy", false),
        pendingRuns = pendingRuns,
    )
}
