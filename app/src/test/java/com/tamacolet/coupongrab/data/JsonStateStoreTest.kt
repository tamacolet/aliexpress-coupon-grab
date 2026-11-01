package com.tamacolet.coupongrab.data

import com.tamacolet.coupongrab.core.AppState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class JsonStateStoreTest {
    @Test fun `hold survives reload clears and old fieldless state remains compatible`() {
        val directory = Files.createTempDirectory("coupon-state").toFile()
        try {
            val file = directory.resolve("state.json")
            JsonStateStore(file).save(AppState(securityHoldSinceMs = 123))
            assertEquals(123L, JsonStateStore(file).load().securityHoldSinceMs)
            JsonStateStore(file).update { it.copy(securityHoldSinceMs = null) }
            assertNull(JsonStateStore(file).load().securityHoldSinceMs)
            val legacy = JSONObject(file.readText()).apply { remove("securityHoldSinceMs") }
            file.writeText(legacy.toString())
            assertNull(JsonStateStore(file).load().securityHoldSinceMs)
        } finally { directory.deleteRecursively() }
    }
}
