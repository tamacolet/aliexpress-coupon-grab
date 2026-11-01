package com.tamacolet.coupongrab.time

import com.tamacolet.coupongrab.core.ClockSource
import com.tamacolet.coupongrab.core.TimeCorrection

data class ClockSample(
    val source: ClockSource,
    val requestEpochMs: Long,
    val receiveEpochMs: Long,
    val transmitEpochMs: Long,
    val responseEpochMs: Long,
    val requestElapsedMs: Long,
    val responseElapsedMs: Long,
) {
    init {
        require(source != ClockSource.NONE)
        require(responseElapsedMs >= requestElapsedMs)
        require(responseEpochMs == requestEpochMs + (responseElapsedMs - requestElapsedMs)) {
            "Response epoch must be anchored to the monotonic clock"
        }
        require(transmitEpochMs >= receiveEpochMs) {
            "Server transmit timestamp precedes its receive timestamp"
        }
        require(rawRttMs >= -1L) { "Server processing time exceeds the network round trip" }
    }

    private val rawRttMs: Long
        get() = (responseElapsedMs - requestElapsedMs) - (transmitEpochMs - receiveEpochMs)

    val rttMs: Long
        get() = rawRttMs.coerceAtLeast(0L)

    val offsetMs: Long
        get() = ((receiveEpochMs - requestEpochMs) + (transmitEpochMs - responseEpochMs)) / 2

    fun toCorrection(): TimeCorrection = TimeCorrection(
        source = source,
        offsetMs = offsetMs,
        anchorElapsedMs = responseElapsedMs,
        measuredAtMs = responseEpochMs,
        rttMs = rttMs,
    )
}

object ClockCorrection {
    fun best(samples: List<ClockSample>): TimeCorrection =
        samples.minByOrNull { it.rttMs }?.toCorrection()
            ?: throw IllegalArgumentException("時刻サンプルがありません")
}

internal fun systemTimeCorrection(epochMs: Long, elapsedRealtimeMs: Long): TimeCorrection =
    TimeCorrection(
        source = ClockSource.NONE,
        offsetMs = 0,
        anchorElapsedMs = elapsedRealtimeMs,
        measuredAtMs = epochMs,
        rttMs = 0,
    )

class CorrectedClock(
    private val elapsedRealtimeMs: () -> Long,
    @Volatile var correction: TimeCorrection,
) {
    fun now(): Long = correction.now(elapsedRealtimeMs())

    /** 最後の2msだけを単調時計で待つための、単調時計上の目標を返す。 */
    fun targetElapsedNanos(targetEpochMs: Long, currentElapsedNanos: Long): Long {
        val remainingMs = targetEpochMs - now()
        return currentElapsedNanos + remainingMs * 1_000_000L
    }
}

internal fun anchorClockCorrection(correction: TimeCorrection, epochMs: Long, elapsedMs: Long): TimeCorrection =
    if (correction.source == ClockSource.NONE && correction.measuredAtMs == 0L) {
        systemTimeCorrection(epochMs, elapsedMs)
    } else correction
