package com.syconx.us2us.feature.conversation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.syconx.us2us.domain.CommunitiesService
import com.syconx.us2us.ui.theme.U2Theme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "tr-rTR-w402dp-h874dp-xxhdpi")
class MessageInviteSheetTest {
    @get:Rule val compose = createComposeRule()
    private val base = CommunityConversationTest.ManageFixture()
    private val codes = mutableListOf<String>()
    private var closed = false
    private val code = "us2us-community:v1:YWJj"
    private fun show(redeem: suspend () -> Unit = {}) {
        val service = object : CommunitiesService by base {
            override suspend fun redeem(code: String) { codes += code; redeem() }
        }
        compose.setContent { U2Theme(dark = true, reduceMotion = true) { MessageInviteSheet(code, service) { closed = true } } }
    }
    @Test fun confirmationIsRequiredAndSuccessWaitsForAdministrator() {
        val gate = CompletableDeferred<Unit>()
        show { gate.await() }
        assertTrue(codes.isEmpty())
        compose.onNodeWithTag("messageInvite.confirm").performClick()
        compose.onNodeWithTag("messageInvite.confirm").assertIsNotEnabled()
        assertEquals(listOf(code), codes)
        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("Katılım isteği gönderildi.", substring = true).assertExists()
        compose.onNodeWithText("Yönetici", substring = true).assertExists()
        compose.onNodeWithTag("messageInvite.confirm").assertDoesNotExist()
        assertFalse(closed)
    }
    @Test fun cancelDoesNotRedeem() {
        show()
        compose.onNodeWithTag("messageInvite.close").performClick()
        assertTrue(closed)
        assertTrue(codes.isEmpty())
    }
    @Test fun invalidOrExpiredInviteShowsFailureAndCanBeRetried() {
        show { throw IllegalArgumentException("invalid invite") }
        compose.onNodeWithTag("messageInvite.confirm").performClick()
        compose.onNodeWithTag("messageInvite.error").assertExists()
        compose.onNodeWithTag("messageInvite.confirm").assertIsEnabled().performClick()
        assertEquals(listOf(code, code), codes)
    }
    @Test fun lockClosesThePendingConfirmation() {
        show()
        compose.runOnIdle { base.unlocked.value = false }
        compose.waitForIdle()
        assertTrue(closed)
        assertTrue(codes.isEmpty())
    }
}
