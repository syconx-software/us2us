package com.syconx.us2us.ui.brand

import android.graphics.Bitmap
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotPaths
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import java.io.File
import kotlin.time.Duration
import org.robolectric.RuntimeEnvironment

/** The environment, output folders and bitmap helpers of the brand tests. */
internal object BrandShots {
    /** The fixtures app around a screen: theme, environment, design insets and an overlay host for sheets and covers. */
    val environment = ShotEnvironment { spec, content ->
        val model = remember {
            PreviewAppModel(
                FixturesMessengerService(
                    hasAccount = true,
                    clock = DesignClock.frozen(DesignClock.referenceDate),
                    english = spec.lang == "en",
                    latency = Duration.ZERO,
                ),
            )
        }
        U2PreviewRoot(model, dark = spec.isDark, designInsets = spec.designInsets) { content() }
    }

    /** Specimens are not app screens: they go beside them, where the parity compare does not collect them. */
    val specimenDir = File(ShotPaths.shotsDir, "android/designsystem/brand")

    /** The gallery pages, named as the iOS captures (`gallery_stickers`), with the app's shots. */
    fun appHost(): ShotHost = ShotHost(environment = environment)

    fun specimenHost(): ShotHost = ShotHost(environment = environment, outputDir = specimenDir)

    /** A text measurer outside composition, on the app's fonts. */
    fun measurer(): TextMeasurer =
        TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), Density(1f), LayoutDirection.Ltr)

    fun sticker(selection: BrandStickerSelection, dark: Boolean, side: Int = 300): Bitmap =
        BrandStickerBitmap.render(selection, dark, side, measurer()).asAndroidBitmap()

    /** Pixels of a bitmap, ARGB. */
    fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
