package com.tamacolet.coupongrab.time

import com.tamacolet.coupongrab.core.ClockSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import com.tamacolet.coupongrab.core.TimeCorrection
import org.junit.Test

class ClockCorrectionTest {
    @Test fun `端末が900ms進んでいる時のオフセットはマイナス900ms`() {
        val sample = ClockSample(
            source = ClockSource.SNTP,
            requestEpochMs = 1_000_900,
            receiveEpochMs = 1_000_020,
            transmitEpochMs = 1_000_020,
            responseEpochMs = 1_000_940,
            requestElapsedMs = 100,
            responseElapsedMs = 140,
        )
        assertEquals(-900, sample.offsetMs)
        assertEquals(40, sample.rttMs)
    }

    @Test fun `非対称遅延とサーバ処理時間を含むSNTP計算が正しい`() {
        val sample = ClockSample(
            source = ClockSource.SNTP,
            requestEpochMs = 1_000,
            receiveEpochMs = 1_020,
            transmitEpochMs = 1_050,
            responseEpochMs = 1_120,
            requestElapsedMs = 200,
            responseElapsedMs = 320,
        )
        assertEquals(-25, sample.offsetMs)
        assertEquals(90, sample.rttMs)
        assertEquals(1_095, sample.toCorrection().now(320))
    }

    @Test fun `複数サンプルから最小RTTを選ぶ`() {
        val slow = sample(t1 = 10_000, t2 = 10_200, t3 = 10_250, request = 100, response = 400)
        val fast = sample(t1 = 20_000, t2 = 20_010, t3 = 20_020, request = 500, response = 540)
        val best = ClockCorrection.best(listOf(slow, fast))
        assertEquals(30, best.rttMs)
        assertEquals(-5, best.offsetMs)
        assertEquals(540, best.anchorElapsedMs)
        assertEquals(20_040, best.measuredAtMs)
    }

    @Test fun `HTTP Dateは応答時にRTTの半分を加える`() {
        val sample = ClockSample(
            source = ClockSource.HTTP_DATE,
            requestEpochMs = 10_000,
            receiveEpochMs = 10_000,
            transmitEpochMs = 10_000,
            responseEpochMs = 10_200,
            requestElapsedMs = 100,
            responseElapsedMs = 300,
        )
        assertEquals(200, sample.rttMs)
        assertEquals(10_100, sample.toCorrection().now(300))
    }

    @Test fun `サーバ送信時刻が受信時刻より前のサンプルを拒否する`() {
        assertThrows(IllegalArgumentException::class.java) {
            sample(t1 = 10_000, t2 = 10_000, t3 = 9_999, request = 100, response = 120)
        }
    }

    @Test fun `ネットワーク経過を2ms以上上回るサーバ処理時間を拒否する`() {
        assertThrows(IllegalArgumentException::class.java) {
            sample(t1 = 10_000, t2 = 10_000, t3 = 10_002, request = 100, response = 100)
        }
    }

    @Test fun `ミリ秒丸めによるマイナス1msのRTTは0にする`() {
        val rounded = sample(t1 = 10_000, t2 = 10_000, t3 = 10_001, request = 100, response = 100)
        assertEquals(0, rounded.rttMs)
        assertEquals(0, rounded.toCorrection().rttMs)
    }

    @Test fun `補正時計は壁時計が変わっても単調時計で進む`() {
        val correction = sample(t1 = 10_000, t2 = 10_000, t3 = 10_000, request = 100, response = 120)
            .toCorrection()
        var elapsed = 120L
        var unusedWallClock = 10_000L
        val clock = CorrectedClock(elapsedRealtimeMs = { elapsed }, correction = correction)
        assertEquals(10_010, clock.now())
        unusedWallClock = 1_000_000_000L
        elapsed = 121L
        assertEquals(10_011, clock.now())
        assertEquals(1_000_000_000L, unusedWallClock)
    }

    @Test fun `最後の待機目標を単調時計へ写す`() {
        val correction = sample(t1 = 10_000, t2 = 10_000, t3 = 10_000, request = 100, response = 100)
            .toCorrection()
        val clock = CorrectedClock(elapsedRealtimeMs = { 500 }, correction = correction)
        val target = clock.targetElapsedNanos(targetEpochMs = 11_007, currentElapsedNanos = 2_000_000_000)
        assertEquals(2_607_000_000, target)
    }

    @Test fun `計測失敗時の補正は現在時刻へアンカーされ1970年にならない`() {
        val correction = systemTimeCorrection(epochMs = 1_700_000_000_000, elapsedRealtimeMs = 8_000)
        assertEquals(ClockSource.NONE, correction.source)
        assertEquals(0, correction.offsetMs)
        assertEquals(1_700_000_001_000, correction.now(9_000))
    }

    @Test fun `未測定は現在時刻へ戻し測定済みのアンカーは保持する`() {
        val fresh = anchorClockCorrection(TimeCorrection(), 1_700_000_000_000L, 8_000)
        val clock = CorrectedClock({ 9_000 }, fresh)
        assertEquals(1_700_000_001_000L, clock.now())
        assertSame(fresh, anchorClockCorrection(fresh, 1_800_000_000_000L, 10_000))
    }

    private fun sample(t1: Long, t2: Long, t3: Long, request: Long, response: Long) = ClockSample(
        source = ClockSource.SNTP,
        requestEpochMs = t1,
        receiveEpochMs = t2,
        transmitEpochMs = t3,
        responseEpochMs = t1 + (response - request),
        requestElapsedMs = request,
        responseElapsedMs = response,
    )
}
