package com.tamacolet.coupongrab.time

import android.net.DnsResolver
import android.os.CancellationSignal
import android.os.SystemClock
import com.tamacolet.coupongrab.core.ClockSource
import com.tamacolet.coupongrab.core.TimeCorrection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executor
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NetworkClockSync {
    suspend fun measure(): TimeCorrection = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        fun remainingMs(): Long = MEASUREMENT_BUDGET_MS - (SystemClock.elapsedRealtime() - started)

        val sntpSamples = SNTP_HOSTS.mapNotNull { host ->
            val remaining = remainingMs()
            if (remaining <= 0) null else runCatching { sntp(host, remaining) }.getOrNull()
        }
        if (sntpSamples.isNotEmpty()) return@withContext ClockCorrection.best(sntpSamples)

        val httpSamples = List(HTTP_ATTEMPTS) {
            val remaining = remainingMs()
            if (remaining <= 0) null else runCatching { httpDate(remaining) }.getOrNull()
        }.filterNotNull()
        if (httpSamples.isNotEmpty()) {
            ClockCorrection.best(httpSamples)
        } else {
            systemTimeCorrection(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        }
    }

    private suspend fun sntp(host: String, remainingMs: Long): ClockSample {
        val attemptStarted = SystemClock.elapsedRealtime()
        val address = resolve(host, remainingMs)
        DatagramSocket().use { socket ->
            val afterDns = SystemClock.elapsedRealtime()
            socket.soTimeout = timeoutMs(remainingMs - (afterDns - attemptStarted), SNTP_TIMEOUT_MS)
            socket.connect(address, SNTP_PORT)
            val requestElapsed = SystemClock.elapsedRealtime()
            val requestEpoch = System.currentTimeMillis()
            val request = NtpPacket.request(requestEpoch)
            socket.send(DatagramPacket(request, request.size, address, SNTP_PORT))
            val response = DatagramPacket(ByteArray(MAX_SNTP_RESPONSE_SIZE), MAX_SNTP_RESPONSE_SIZE)
            socket.receive(response)
            val responseElapsed = SystemClock.elapsedRealtime()
            val responseEpoch = requestEpoch + (responseElapsed - requestElapsed)
            require(response.address == address && response.port == SNTP_PORT) {
                "SNTP response came from an unexpected peer"
            }
            val timestamps = NtpPacket.parseResponse(response.data, response.length, request)
            return ClockSample(
                source = ClockSource.SNTP,
                requestEpochMs = requestEpoch,
                receiveEpochMs = timestamps.receiveEpochMs,
                transmitEpochMs = timestamps.transmitEpochMs,
                responseEpochMs = responseEpoch,
                requestElapsedMs = requestElapsed,
                responseElapsedMs = responseElapsed,
            )
        }
    }

    private suspend fun httpDate(remainingMs: Long): ClockSample {
        val connection = URL(HTTP_DATE_URL).openConnection() as HttpsURLConnection
        return try {
            withTimeout(remainingMs) {
                runInterruptible(Dispatchers.IO) { httpDate(connection, remainingMs) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpDate(connection: HttpsURLConnection, remainingMs: Long): ClockSample {
        connection.connectTimeout = timeoutMs(remainingMs, HTTP_TIMEOUT_MS)
        connection.readTimeout = timeoutMs(remainingMs, HTTP_TIMEOUT_MS)
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        val requestElapsed = SystemClock.elapsedRealtime()
        val requestEpoch = System.currentTimeMillis()
        connection.connect()
        val connected = SystemClock.elapsedRealtime()
        connection.readTimeout = timeoutMs(remainingMs - (connected - requestElapsed), HTTP_TIMEOUT_MS)
        connection.inputStream.use { it.readBytes() }
        val responseElapsed = SystemClock.elapsedRealtime()
        val responseEpoch = requestEpoch + (responseElapsed - requestElapsed)
        val date = connection.date
        require(date > 0) { "HTTP Date header is missing" }
        return ClockSample(
            source = ClockSource.HTTP_DATE,
            requestEpochMs = requestEpoch,
            receiveEpochMs = date,
            transmitEpochMs = date,
            responseEpochMs = responseEpoch,
            requestElapsedMs = requestElapsed,
            responseElapsedMs = responseElapsed,
        )
    }

    private suspend fun resolve(host: String, remainingMs: Long): InetAddress =
        withTimeout(remainingMs) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                DnsResolver.getInstance().query(
                    null,
                    host,
                    DnsResolver.FLAG_EMPTY,
                    DIRECT_EXECUTOR,
                    signal,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            val address = answer.firstOrNull()
                            if (address == null) {
                                continuation.resumeWithException(UnknownHostException(host))
                            } else {
                                continuation.resume(address)
                            }
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            continuation.resumeWithException(error)
                        }
                    },
                )
            }
        }

    private fun timeoutMs(remainingMs: Long, maximumMs: Long): Int {
        require(remainingMs > 0) { "Clock measurement budget exhausted" }
        return remainingMs.coerceAtMost(maximumMs).toInt()
    }

    private companion object {
        const val MEASUREMENT_BUDGET_MS = 12_000L
        const val SNTP_TIMEOUT_MS = 1_500L
        const val HTTP_TIMEOUT_MS = 2_000L
        const val HTTP_ATTEMPTS = 3
        const val SNTP_PORT = 123
        const val MAX_SNTP_RESPONSE_SIZE = 512
        const val HTTP_DATE_URL = "https://www.google.com/generate_204"
        val SNTP_HOSTS = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}

fun systemClock(correction: TimeCorrection = TimeCorrection()): CorrectedClock {
    val anchored = anchorClockCorrection(correction, System.currentTimeMillis(), SystemClock.elapsedRealtime())
    return CorrectedClock(SystemClock::elapsedRealtime, anchored)
}
