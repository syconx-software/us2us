package com.syconx.us2us.runtime.support

import com.syconx.us2us.runtime.net.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RuntimeStringsTest {
    /** Texts that name their key and language, so the test sees which one was asked for. */
    private val echo = RuntimeTexts { key, language -> "$language:${key.name}" }

    @Test
    fun everyKeyHasItsOwnResource() {
        val names = RuntimeString.entries.map { it.resourceName }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.matches(Regex("runtime_[a-z_]+")) })
        assertEquals("runtime_biometric_use_code", RuntimeString.BIOMETRIC_USE_CODE.resourceName)
    }

    @Test
    fun turkishOrEnglishOnly() {
        assertEquals("tr", RuntimeStrings("tr", echo).languageCode)
        assertEquals("tr", RuntimeStrings("tr-TR", echo).languageCode)
        assertEquals("en", RuntimeStrings("de", echo).languageCode)
        assertEquals("en", RuntimeStrings("", echo).languageCode)
        assertEquals("tr:BIOMETRIC_REASON", RuntimeStrings("tr", echo).string(RuntimeString.BIOMETRIC_REASON))
        assertEquals("en:BIOMETRIC_REASON", RuntimeStrings("de", echo).string(RuntimeString.BIOMETRIC_REASON))
    }

    @Test
    fun hatLines() {
        val strings = RuntimeStrings("tr", echo)
        assertEquals("tr:LINE_OPEN", ConnectionStatus.Open.line(strings))
        assertEquals("tr:LINE_CONNECTING", ConnectionStatus.Connecting.line(strings))
        assertEquals("tr:LINE_CONNECTING", ConnectionStatus.Retrying(Instant.EPOCH).line(strings))
        assertEquals("tr:LINE_SYNCING", ConnectionStatus.Syncing.line(strings))
        assertEquals("tr:LINE_OFFLINE", ConnectionStatus.Offline.line(strings))
        assertEquals("tr:LINE_WAITING_FOR_NETWORK", ConnectionStatus.WaitingForNetwork.line(strings))
        assertEquals("tr:LINE_REVOKED", ConnectionStatus.Revoked.line(strings))
        assertTrue(ConnectionStatus.Open.isConnected && ConnectionStatus.Syncing.isConnected)
        assertTrue(!ConnectionStatus.Connecting.isConnected && !ConnectionStatus.Retrying(Instant.EPOCH).isConnected)
    }

    /** The resource route resolves in the language asked for, whatever the app currently shows. */
    @Test
    fun resourcesAreReadInTheLanguageAskedFor() {
        val context = RuntimeEnvironment.getApplication()
        val turkish = RuntimeStrings.fromResources(context, "tr") { android.R.string.cancel }
        val english = RuntimeStrings.fromResources(context, "en") { android.R.string.cancel }
        assertEquals("Cancel", english.string(RuntimeString.BIOMETRIC_USE_CODE))
        assertNotEquals(english.string(RuntimeString.BIOMETRIC_USE_CODE), turkish.string(RuntimeString.BIOMETRIC_USE_CODE))
    }

    @Test
    fun utf8PrefixCutsAtCharacters() {
        assertEquals("ağ", "ağaç".utf8Prefix(3))
        assertEquals("abc", "abc".utf8Prefix(10))
        assertEquals("", "abc".utf8Prefix(0))
        // A flag is two regional indicators (8 bytes): never half of it.
        assertEquals("a", "a🇹🇷".utf8Prefix(5))
        assertEquals("a🇹🇷", "a🇹🇷".utf8Prefix(9))
    }
}
