package com.syconx.us2us.live

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import com.syconx.us2us.runtime.support.RuntimeString
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The pick folder, the runtime's own words and the sources' only endpoint, on Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LivePlatformTest {
    private val context: Application = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun aPickedFileGetsAFolderOfItsOwnAndGoesWithIt() {
        val pickers = SystemPickers(context)
        val original = File(context.filesDir, "gece.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val first = pickers.copy(original.toURI(), context.contentResolver)!!
        val second = pickers.copy(original.toURI(), context.contentResolver)!!
        assertTrue(pickers.isPicked(first))
        assertNotEquals("every pick in its own folder", first.parentFile, second.parentFile)
        assertEquals("gece.jpg", first.name)
        assertTrue(first.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("a copy already in the pick folder is used as it is", first, pickers.copy(first.toURI(), context.contentResolver))

        pickers.discardPicked(first)
        assertFalse(first.exists())
        assertFalse("its folder goes too", first.parentFile!!.exists())
        assertTrue(second.exists())
        pickers.removeAllPicked()
        assertFalse(second.exists())
        assertTrue("the original is never touched", original.exists())
    }

    @Test
    fun aPickersContentIsCopiedUnderASafeName() {
        val uri = Uri.parse("content://com.example.provider/picks/1")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("selam".toByteArray()))
        val copy = SystemPickers(context).copy(URI(uri.toString()), context.contentResolver)
        assertNotNull(copy)
        assertEquals("selam", copy!!.readText())
        assertFalse("no provider name, still a file of its own", copy.name.isEmpty())
        assertNull("an unreadable pick gives nothing", SystemPickers(context).copy(URI("file:///nowhere/yok.jpg"), context.contentResolver))
    }

    @Test
    fun theRuntimesWordsAreInBothLanguages() {
        val english = localized("en")
        val turkish = localized("tr")
        for (key in RuntimeString.entries) {
            val resource = LiveMessenger.resource(key)
            assertEquals(key.resourceName, context.resources.getResourceEntryName(resource))
            val en = english.getString(resource)
            val tr = turkish.getString(resource)
            assertTrue(key.name, en.isNotBlank() && tr.isNotBlank())
            assertNotEquals(key.name, en, tr)
        }
        assertEquals("us2us'un kilidini aç", turkish.getString(R.string.runtime_biometric_reason))
        assertEquals("Kodla aç", turkish.getString(R.string.runtime_biometric_use_code))
        assertEquals("Unlock us2us", english.getString(R.string.runtime_biometric_reason))
        // The runtime reads them by key, in the language the app shows now.
        val strings = LiveMessenger.runtimeStrings(context) { null }
        assertEquals(context.getString(R.string.runtime_line_locked), strings.string(RuntimeString.LINE_LOCKED))
    }

    /**
     * No network endpoint but the relay in the live service's sources (the iOS
     * `noOtherNetworkEndpointsInTheSources`): the relay URL itself comes from the core.
     */
    @Test
    fun noOtherNetworkEndpointsInTheSources() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "src/main/kotlin").isDirectory || File(it, "live/src/main/kotlin").isDirectory }
        val sources = if (File(root, "src/main/kotlin").isDirectory) File(root, "src/main") else File(root, "live/src/main")
        val pattern = Regex("""https?://[A-Za-z0-9.\-]+[/A-Za-z0-9._\-]*""")
        // The relay, and the manifest's XML namespace (a name, never fetched).
        val allowed = listOf("https://relay.us2us.app", "http://schemas.android.com/apk/res/android")
        val offenders = sources.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.flatMap { file ->
            pattern.findAll(file.readText()).map { it.value }.filter { found -> allowed.none { found.startsWith(it) } }.map { "${file.name}: $it" }
        }.toList()
        assertTrue("unexpected endpoints: $offenders", offenders.isEmpty())
    }

    private fun localized(language: String): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration)
    }
}
