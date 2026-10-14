package com.syconx.us2us.app.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.feature.report.AddressOnlyReportMailer
import com.syconx.us2us.feature.report.LocalReportMailer
import com.syconx.us2us.ui.brand.BrandAvatarGallery
import com.syconx.us2us.ui.brand.BrandStickerGallery

/** Debug builds: the developer pages over the app (`U2Gallery`) and the launch arguments screens read. */
object DebugScreens {
    /** `U2Gallery stickers` · `avatars` · `YES`: the gallery over everything but the lock, as iOS draws it. */
    @Composable
    fun Gallery(configuration: LaunchConfig) {
        when {
            configuration.showsStickerGallery -> BrandStickerGallery()
            configuration.showsAvatarGallery -> BrandAvatarGallery()
            configuration.showsGallery -> ComponentGallery()
        }
    }

    /** `U2ReportMail address`: “Bildir” › “E-postayla gönder” shows the address to copy, whatever the phone has. */
    @Composable
    fun Provide(configuration: LaunchConfig, content: @Composable () -> Unit) {
        if (configuration.reportMail == "address") {
            CompositionLocalProvider(LocalReportMailer provides AddressOnlyReportMailer, content = content)
        } else {
            content()
        }
    }
}
