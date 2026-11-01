package com.tamacolet.coupongrab.core

object StatusReporter {
    fun line(state: AppState): String =
        "coupongrab busy=${state.busy} nextExactAtMs=${ScheduleRules.nextExactAtMs(state.schedules)} " +
            "nasBusyUntilMs=${state.nasBusyUntilMs}"
}
