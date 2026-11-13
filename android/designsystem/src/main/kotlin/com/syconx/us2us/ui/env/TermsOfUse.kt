package com.syconx.us2us.ui.env

import com.syconx.us2us.ui.l10n.L10n
import java.util.Locale

/**
 * The Terms of Use, as on iOS (App Review 1.2, and Google Play's rules for user-generated content): the
 * agreement users accept, with no tolerance for objectionable content or abusive users. The full text is on
 * us2us.app; the app shows its key rules (`TermsContent`) and keeps, on this phone, which version was accepted.
 */
object TermsOfUse {
    /** The version the app asks for (the same as iOS). A new one asks everyone again once. */
    const val VERSION = "2026-11-09"

    /** `https://us2us.app/tr/kullanim-kosullari` in Turkish, `/en/terms` otherwise. */
    fun url(locale: Locale): String = "https://us2us.app" + if (L10n.isTurkish(locale)) "/tr/kullanim-kosullari" else "/en/terms"
}

/** The current terms were accepted on this phone. */
val U2Preferences.hasAcceptedCurrentTerms: Boolean get() = acceptedTermsVersion == TermsOfUse.VERSION

/** “Kabul ediyorum”: the current version, kept on this phone. */
fun U2Preferences.acceptTerms() {
    acceptedTermsVersion = TermsOfUse.VERSION
}
