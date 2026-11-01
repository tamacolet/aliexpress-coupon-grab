package com.tamacolet.coupongrab.time

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure SNTP packet encoding and validation, kept independent of Android for JVM tests. */
internal object NtpPacket {
    const val SIZE = 48
    private const val ORIGIN_OFFSET = 24
    private const val RECEIVE_OFFSET = 32
    private const val TRANSMIT_OFFSET = 40
    private const val NTP_UNIX_EPOCH_OFFSET_SECONDS = 2_208_988_800L
    private const val FRACTION_DENOMINATOR = 4_294_967_296L

    data class Timestamps(
        val receiveEpochMs: Long,
        val transmitEpochMs: Long,
    )

    fun request(epochMs: Long): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0, 0x23) // leap=0, version=4, client mode=3
        writeTimestamp(buffer, TRANSMIT_OFFSET, epochMs)
        return buffer.array()
    }

    fun parseResponse(packet: ByteArray, length: Int, expectedRequest: ByteArray): Timestamps {
        require(length >= SIZE && length <= packet.size) { "Invalid SNTP response length" }
        require(expectedRequest.size >= TRANSMIT_OFFSET + 8) { "Invalid SNTP request" }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val first = buffer.get(0).toInt() and 0xff
        val leap = first ushr 6
        val version = (first ushr 3) and 0x07
        val mode = first and 0x07
        val stratum = buffer.get(1).toInt() and 0xff
        require(leap != 3) { "SNTP server clock is unsynchronized" }
        require(version in 3..4 && mode == 4) { "Invalid SNTP response header" }
        require(stratum in 1..15) { "Invalid SNTP stratum" }
        for (index in 0 until 8) {
            require(packet[ORIGIN_OFFSET + index] == expectedRequest[TRANSMIT_OFFSET + index]) {
                "SNTP originate timestamp does not match request"
            }
        }
        return Timestamps(
            receiveEpochMs = readTimestamp(buffer, RECEIVE_OFFSET),
            transmitEpochMs = readTimestamp(buffer, TRANSMIT_OFFSET),
        )
    }

    private fun readTimestamp(buffer: ByteBuffer, offset: Int): Long {
        val seconds = buffer.getInt(offset).toLong() and 0xffffffffL
        val fraction = buffer.getInt(offset + 4).toLong() and 0xffffffffL
        require(seconds != 0L || fraction != 0L) { "SNTP timestamp is zero" }
        return (seconds - NTP_UNIX_EPOCH_OFFSET_SECONDS) * 1_000L +
            (fraction * 1_000L + FRACTION_DENOMINATOR / 2) / FRACTION_DENOMINATOR
    }

    private fun writeTimestamp(buffer: ByteBuffer, offset: Int, epochMs: Long) {
        val ntpMs = epochMs + NTP_UNIX_EPOCH_OFFSET_SECONDS * 1_000L
        val seconds = Math.floorDiv(ntpMs, 1_000L)
        val millis = Math.floorMod(ntpMs, 1_000L)
        val fraction = millis * FRACTION_DENOMINATOR / 1_000L
        buffer.putInt(offset, seconds.toInt())
        buffer.putInt(offset + 4, fraction.toInt())
    }
}
