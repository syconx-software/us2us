package com.syconx.us2us.feature.conversation

import androidx.compose.foundation.layout.Box
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.UxMatrix
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review-matrix scenes (`UX_PHASE`) of the conversation that no harness state opens: the Android
 * live-location notice (notifications off) over 5.1.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ConversationUxScenesTest {
    private var rig = ConversationRig()

    @get:Rule
    val host = ShotHost(environment = conversationShots { rig })

    @Test
    fun liveShareNotice() {
        assumeTrue(UxMatrix.enabled)
        for (variant in UxMatrix.variants) {
            rig = ConversationRig(english = variant.lang == "en")
            rig.plan("deniz", "5.1")
            host.render(variant.theme, variant.lang, variant.options()) {
                Box {
                    ConversationScreen(ChatId("deniz"))
                    U2Sheet(isPresented = true, onDismissRequest = {}, style = U2SheetStyle.Floating) {
                        LiveShareSheet(onAllow = {}, onShare = {})
                    }
                }
            }
            host.advance(1_000)
            host.captureUx("conversation_live_share_notice", variant)
        }
    }
}
