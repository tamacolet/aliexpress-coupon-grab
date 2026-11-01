package com.tamacolet.coupongrab.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NtpPacketTest {
    @Test fun `有効な応答から受信と送信の時刻を読む`() {
        val request = NtpPacket.request(EPOCH_MS)
        val response = validResponse(request)
        val timestamps = NtpPacket.parseResponse(response, response.size, request)
        assertEquals(EPOCH_MS + 20, timestamps.receiveEpochMs)
        assertEquals(EPOCH_MS + 40, timestamps.transmitEpochMs)
    }

    @Test fun `要求の送信時刻をSNTP形式で符号化できる`() {
        val request = NtpPacket.request(EPOCH_MS)
        val response = validResponse(request)
        request.copyInto(response, 32, 40, 48)
        request.copyInto(response, 40, 40, 48)
        val timestamps = NtpPacket.parseResponse(response, response.size, request)
        assertEquals(EPOCH_MS, timestamps.transmitEpochMs)
    }

    @Test fun `48バイト未満の応答を拒否する`() {
        val request = NtpPacket.request(EPOCH_MS)
        assertInvalid { NtpPacket.parseResponse(ByteArray(47), 47, request) }
    }

    @Test fun `サーバモード以外を拒否する`() {
        val request = NtpPacket.request(EPOCH_MS)
        val response = validResponse(request).apply { this[0] = 0x23 }
        assertInvalid { NtpPacket.parseResponse(response, response.size, request) }
    }

    @Test fun `unsynchronized leap indicator を拒否する`() {
        val request = NtpPacket.request(EPOCH_MS)
        val response = validResponse(request).apply { this[0] = 0xE4.toByte() }
        assertInvalid { NtpPacket.parseResponse(response, response.size, request) }
    }

    @Test fun `stratum 0 を拒否する`() {
        val request = NtpPacket.request(EPOCH_MS)
        val response = validResponse(request).apply { this[1] = 0 }
        assertInvalid { NtpPacket.parseResponse(response, response.size, request) }
    }

    @Test fun `originate timestamp が一致しない応答を拒否する`() {
        val request = NtpPacket.request(EPOCH_MS)
        val response = validResponse(request).apply { this[31] = (this[31].toInt() xor 1).toByte() }
        assertInvalid { NtpPacket.parseResponse(response, response.size, request) }
    }

    @Test fun `受信時刻がゼロの応答を拒否する`() {
        val request = NtpPacket.request(EPOCH_MS)
        val response = validResponse(request).apply { fill(0, 32, 40) }
        assertInvalid { NtpPacket.parseResponse(response, response.size, request) }
    }

    @Test fun `送信時刻がゼロの応答を拒否する`() {
        val request = NtpPacket.request(EPOCH_MS)
        val response = validResponse(request).apply { fill(0, 40, 48) }
        assertInvalid { NtpPacket.parseResponse(response, response.size, request) }
    }

    private fun validResponse(request: ByteArray): ByteArray {
        val response = ByteArray(NtpPacket.SIZE)
        response[0] = 0x24 // leap=0, version=4, server mode=4
        response[1] = 2
        request.copyInto(response, 24, 40, 48)
        writeTimestamp(response, 32, EPOCH_MS + 20)
        writeTimestamp(response, 40, EPOCH_MS + 40)
        return response
    }

    private fun writeTimestamp(packet: ByteArray, offset: Int, epochMs: Long) {
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val seconds = epochMs / 1_000 + NTP_UNIX_EPOCH_OFFSET_SECONDS
        val fraction = (epochMs % 1_000) * FRACTION_DENOMINATOR / 1_000
        buffer.putInt(offset, seconds.toInt())
        buffer.putInt(offset + 4, fraction.toInt())
    }

    private fun assertInvalid(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    private companion object {
        const val EPOCH_MS = 1_700_000_000_000L
        const val NTP_UNIX_EPOCH_OFFSET_SECONDS = 2_208_988_800L
        const val FRACTION_DENOMINATOR = 4_294_967_296L
    }
}
