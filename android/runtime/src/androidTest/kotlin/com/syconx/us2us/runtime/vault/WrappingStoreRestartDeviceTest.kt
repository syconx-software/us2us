package com.syconx.us2us.runtime.vault

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Run seed and verify in separate instrumentation invocations for a real process boundary. */
@RunWith(AndroidJUnit4::class)
class WrappingStoreRestartDeviceTest {
    @Test fun wrappingGenerationPersists() {
        val phase = InstrumentationRegistry.getArguments().getString("wrappingPhase") ?: "both"
        require(phase in listOf("seed", "verify", "both"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.noBackupFilesDir, "wrapping-process-test")
        val alias = "us2us.secure-items.process-test"
        val store = KeystoreItemStore(File(root, "items"), alias)
        var successful = false
        try {
            if (phase != "verify") {
                store.deleteAll()
                writeLegacyWrappingItems(File(root, "items"), alias,
                    mapOf("device-secret" to ByteArray(32) { 0x61 }, "secondary" to byteArrayOf(1, 2)))
                store.migrateAfterSuccessfulUnlock()
                store.write("device-secret", ByteArray(32) { 0x71 })
                store.delete("secondary")
                File(root, "seed.pid").writeText(Process.myPid().toString())
            }
            if (phase != "seed") {
                if (phase == "verify") assertNotEquals("A new process is required", File(root, "seed.pid").readText().toInt(), Process.myPid())
                val reopened = KeystoreItemStore(File(root, "items"), alias)
                assertArrayEquals(ByteArray(32) { 0x71 }, reopened.read("device-secret"))
                assertNull(reopened.read("secondary"))
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                assertFalse(keys.containsAlias(alias)); assertTrue(keys.containsAlias("$alias.wrap-v2"))
                assertNull(keys.getKey("$alias.wrap-v2", null).encoded)
            }
            Log.i("us2us.vault.test", "wrapping process phase=$phase pid=${Process.myPid()} verified")
            successful = true
        } finally {
            if (phase != "seed" || !successful) {
                store.deleteAll(); root.deleteRecursively()
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                assertFalse(keys.containsAlias(alias)); assertFalse(keys.containsAlias("$alias.wrap-v2"))
            }
        }
    }
}
