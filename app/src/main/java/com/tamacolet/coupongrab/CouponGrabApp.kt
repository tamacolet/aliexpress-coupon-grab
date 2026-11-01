package com.tamacolet.coupongrab

import android.app.Application
import android.os.SystemClock
import com.tamacolet.coupongrab.core.TimeCorrection
import com.tamacolet.coupongrab.data.JsonStateStore
import java.io.File

class CouponGrabApp : Application() {
    val store: JsonStateStore by lazy { JsonStateStore(File(filesDir, "coupon-state.json")) }

    override fun onCreate() {
        super.onCreate()
        val elapsed = SystemClock.elapsedRealtime()
        store.update { state ->
            val staleClock = state.correction.measuredAtMs <= 0 || state.correction.anchorElapsedMs > elapsed
            state.copy(
                // ContentProvider がプロセスを起動した時も、前回実行の busy を残さない。
                busy = false,
                correction = if (staleClock) {
                    TimeCorrection()
                } else state.correction,
            )
        }
    }
}
