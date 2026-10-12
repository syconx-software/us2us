package com.syconx.us2us.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.ProtectedPreviewFiles
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import java.io.File
import java.net.URI
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 5.2 › File → the app's preview: “kira-sozlesmesi.pdf” with the share button (`5.2-file`) and as a
 * disappearing message without it (`5.2-file-disappearing`); the notice for a file it cannot show;
 * the copy deleted when the preview closes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class FilePreviewShotTest {
    private val environment = MediaShotEnvironment()

    @get:Rule
    val host = ShotHost(environment = environment)

    private val mert = ChatId("mert_k")
    private val pdf = MessageId("m1")

    @Before
    fun freshPreviewFolder() {
        ProtectedPreviewFiles.root = Files.createTempDirectory("preview").toFile()
    }

    @Test
    fun pdfPreview() {
        for (theme in ShotTheme.entries) {
            host.render(theme, "tr") { FilePreviewCover(mert, pdf, onDismiss = {}) }
            host.settle { host.has(FilePreviewTags.PREVIEW) }
            host.advance(600)
            host.capture("conversation:mert_k/5.2-file")
            host.compose.onNodeWithTag(FilePreviewTags.SHARE).assertIsDisplayed()
            assertEquals(1, ProtectedPreviewFiles.existingFiles.size)
        }
    }

    @Test
    fun disappearingPdfHasNoShareButton() {
        for (theme in ShotTheme.entries) {
            environment.prepare = { it.setDisappearing(DisappearingTimer.FiveMinutes, pdf, mert) }
            host.render(theme, "tr") { FilePreviewCover(mert, pdf, onDismiss = {}) }
            host.settle { host.has(FilePreviewTags.PROTECTED_PREVIEW) }
            host.advance(600)
            host.capture("conversation:mert_k/5.2-file-disappearing")
            host.compose.onNodeWithTag(FilePreviewTags.SHARE).assertDoesNotExist()
            host.compose.onNodeWithTag(FilePreviewTags.NOTE, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun closingDeletesTheCopy() {
        var dismissed = 0
        host.render(ShotTheme.Dark, "tr") { FilePreviewCover(mert, pdf, onDismiss = { dismissed++ }) }
        host.settle { host.has(FilePreviewTags.PREVIEW) }
        val copies = ProtectedPreviewFiles.existingFiles
        assertEquals(1, copies.size)
        host.compose.onNodeWithTag(FilePreviewTags.CLOSE).performClick()
        host.advance(100)
        assertEquals(1, dismissed)
        // The cover leaves: its copy goes with it.
        host.render(ShotTheme.Dark, "tr") { Box(Modifier.fillMaxSize()) }
        assertTrue(copies.none(File::exists))
    }

    /** The fixtures' PDF, handed over as a copy named [name] with [text] in it. */
    private fun copyNamed(name: String, text: String) {
        environment.wrap = { fixtures ->
            object : MessengerService by fixtures {
                override suspend fun previewFile(message: MessageId, chat: ChatId): URI? =
                    ProtectedPreviewFiles.writeNow(Bytes.utf8(text), name)
            }
        }
    }

    @Test
    fun plainTextIsShownInTheApp() {
        copyNamed("notlar.txt", "Kira her ayın beşine kadar ödenir.")
        host.render(ShotTheme.Light, "tr") { FilePreviewCover(mert, pdf, onDismiss = {}) }
        host.settle { host.has(FilePreviewTags.PREVIEW) }
        host.advance(600)
        host.capture("media-file-text", design = null)
        host.compose.onNodeWithText("Kira her ayın beşine kadar ödenir.").assertIsDisplayed()
    }

    @Test
    fun aFileItCannotShowRaisesTheNoticeAndItsCopyGoesWithIt() {
        copyNamed("butce-2026.numbers", "x")
        var dismissed = 0
        host.render(ShotTheme.Dark, "tr") { FilePreviewCover(mert, pdf, onDismiss = { dismissed++ }) }
        host.settle { host.has(FilePreviewTags.SAVE) }
        host.capture("media-file-notice-over-chat", design = null)
        assertEquals(1, ProtectedPreviewFiles.existingFiles.size)
        host.compose.onNodeWithTag(FilePreviewTags.NOTICE_CLOSE).performClick()
        host.advance(1_500)
        assertEquals(1, dismissed)
        host.render(ShotTheme.Dark, "tr") { Box(Modifier.fillMaxSize()) }
        assertTrue(ProtectedPreviewFiles.existingFiles.isEmpty())
    }

    @Test
    fun notice() {
        for (theme in ShotTheme.entries) {
            val notice = FileNotice(FileNotice.Kind.NotPreviewable, pdf, "butce-2026.numbers", savableFile = File("x").toURI())
            host.render(theme, "tr") {
                Box(Modifier.fillMaxSize().background(U2Theme.colors.bg))
                U2Sheet(isPresented = true, onDismissRequest = {}, style = U2SheetStyle.Floating) {
                    FileNoticeContent(notice, onSave = {}, onClose = {})
                }
            }
            host.advance(1_000)
            host.capture("media-file-notice", design = null)
            host.compose.onNodeWithTag(FilePreviewTags.SAVE).assertIsDisplayed()
        }
    }
}
