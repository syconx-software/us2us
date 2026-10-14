package com.syconx.us2us.ui.sheet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.env.LocalDesignInsets
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sheet's own frame and grabber against the drawn sheets: 4.2 and 5.4 (full height), 5.5 (content), 3.3 (floating). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class U2SheetGeometryTest {
    @get:Rule
    val host = ShotHost(environment = SheetShots)

    /** A screen with a sheet over it; the sheet's content is a plain block of [contentHeight]. */
    @Composable
    private fun SheetOver(style: U2SheetStyle, contentHeight: Float, bottomPadding: Float? = null) {
        U2Screen { }
        U2Sheet(isPresented = true, onDismissRequest = {}, style = style) {
            val padded = if (bottomPadding != null) Modifier.u2Bottom(bottomPadding.dp) else Modifier
            Spacer(padded.fillMaxWidth().height(contentHeight.dp))
        }
    }

    private fun shootSheet(name: String, theme: ShotTheme, layout: String, content: @Composable () -> Unit) {
        host.render(theme, "tr", content = content)
        host.useLayout(layout)
        // Named after the sheet, not the screen: the content is a placeholder, so no image diff against the reference.
        host.capture(name, design = null)
    }

    @Test
    fun fullHeightStartsAtDesignY58() {
        for (theme in ShotTheme.entries) {
            shootSheet("sheet-full-height", theme, "4.2-yeni-sohbet") { SheetOver(U2SheetStyle.FullHeight, contentHeight = 200f) }
            host.assertFrame(U2SheetTags.SHEET, element = "r.1")
            host.assertFrame(U2SheetTags.GRABBER, element = "r.1.0")
            host.useLayout("5.4-mesaj-bilgisi")
            host.assertFrame(U2SheetTags.SHEET, element = "r.1")
            host.assertFrame(U2SheetTags.GRABBER, element = "r.1.0")
        }
    }

    @Test
    fun contentHeightHugsItsContent() {
        // 5.5: 586.19 tall = 8 + grabber 5 + content (533.19 + the 40 dp bottom padding).
        for (theme in ShotTheme.entries) {
            shootSheet("sheet-content", theme, "5.5-ghost-ve-sureli-mesaj") {
                SheetOver(U2SheetStyle.Content, contentHeight = 533.19f, bottomPadding = 40f)
            }
            host.assertFrame(U2SheetTags.SHEET, element = "r.1")
            host.assertFrame(U2SheetTags.GRABBER, element = "r.1.0")
        }
    }

    @Test
    fun floatingIsInsetEightWithItsGrabberTenFromTheTop() {
        // 3.3: 462.98 tall = 10 + grabber 5 + content 447.98, 8 from the sides and the bottom.
        for (theme in ShotTheme.entries) {
            shootSheet("sheet-floating", theme, "3.3-kodu-unuttum") { SheetOver(U2SheetStyle.Floating, contentHeight = 447.98f) }
            host.assertFrame(U2SheetTags.SHEET, element = "r.2")
            host.assertFrame(U2SheetTags.GRABBER, element = "r.2.0")
        }
    }

    @Test
    fun toastSitsAtDesignY70() {
        host.render(ShotTheme.Dark, "tr") {
            Box(Modifier.fillMaxSize()) {
                U2Screen { }
                U2Toast(message = stringResource(R.string.common_lock_cover_locked), onDismiss = {})
            }
        }
        host.capture("toast", design = null)
        val bounds = host.compose.onNodeWithTag(U2_TOAST_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(70f, bounds.top.value, 1f)
    }
}

/** Shots of the sheet layer: the theme, design insets and an overlay host around the screen. */
internal val SheetShots = ShotEnvironment { spec, content ->
    U2Theme(dark = spec.isDark, reduceMotion = false) {
        CompositionLocalProvider(LocalDesignInsets provides spec.designInsets) {
            U2OverlayHost { content() }
        }
    }
}
