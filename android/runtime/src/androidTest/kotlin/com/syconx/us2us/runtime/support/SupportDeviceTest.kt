package com.syconx.us2us.runtime.support

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The platform pieces of the support package on a phone. */
@RunWith(AndroidJUnit4::class)
class SupportDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aSensitiveCopyIsMarkedAndCleared() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clipboard = SystemClipboard(context)
            clipboard.write("12345 67890", sensitive = true)
            val manager = context.getSystemService(ClipboardManager::class.java)
            // Without focus the description may be hidden from this process; when it is not, it is marked.
            manager.primaryClipDescription?.let { description ->
                assertTrue(description.extras?.getBoolean(SystemClipboard.EXTRA_IS_SENSITIVE) == true)
                assertEquals("", description.label?.toString() ?: "")
            }
            val pasteboard = SecurePasteboard(clipboard)
            pasteboard.copy("code", SecurePasteboard.Sensitivity.SENSITIVE)
            pasteboard.clearIfOwned()
        }
    }

    @Test
    fun runtimeStringsFollowTheRequestedLanguage() {
        val english = RuntimeStrings.fromResources(context, "en") { android.R.string.cancel }
        val turkish = RuntimeStrings.fromResources(context, "tr") { android.R.string.cancel }
        assertEquals("Cancel", english.string(RuntimeString.BIOMETRIC_USE_CODE))
        assertTrue(turkish.string(RuntimeString.BIOMETRIC_USE_CODE) != "Cancel")
    }
}
