package com.tamacolet.coupongrab.core

/** 適用試行とその結果だけを、コード単位の履歴へ対応付ける。 */
object HistoryRules {
    private val correctedAtMs = Regex("""correctedAtMs=(\d+)""")
    private val resultEvents = setOf("code_applied", "code_used", "code_failure")

    fun record(state: AppState, entry: HistoryEntry): AppState = when (entry.event) {
        "security_hold", "security_hold_skip" -> state.append(entry)
        "apply" -> state.append(atActualPress(entry))
        "apply_retry" -> state.failPreviousAttempt(entry).append(atActualPress(entry))
        in resultEvents -> state.completeAttempt(entry)
        else -> state
    }

    private fun atActualPress(entry: HistoryEntry): HistoryEntry {
        val atMs = correctedAtMs.find(entry.detail)?.groupValues?.get(1)?.toLongOrNull()
            ?: return entry
        return entry.copy(atMs = atMs)
    }

    private fun AppState.failPreviousAttempt(entry: HistoryEntry): AppState {
        val index = history.lastPendingAttemptIndex(entry)
        if (index < 0) return this
        return copy(history = history.mapIndexed { candidate, previous ->
            if (candidate == index) {
                previous.copy(event = "code_failure", detail = "適用できません")
            } else previous
        })
    }

    private fun AppState.completeAttempt(entry: HistoryEntry): AppState {
        val index = history.lastPendingAttemptIndex(entry)
        if (index < 0) return append(entry)
        val previous = history[index]
        return copy(history = history.mapIndexed { candidate, current ->
            if (candidate == index) {
                previous.copy(event = entry.event, detail = entry.detail)
            } else current
        }.takeLast(HISTORY_LIMIT))
    }

    private fun List<HistoryEntry>.lastPendingAttemptIndex(entry: HistoryEntry): Int =
        indexOfLast {
            (it.event == "apply" || it.event == "apply_retry") &&
                it.scheduleId == entry.scheduleId && it.code == entry.code
        }
}
