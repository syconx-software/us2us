package com.syconx.us2us.ui.env

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Terms of Use the app asks for (App Review 1.2, as on iOS): where the full text is, and what was accepted here. */
class TermsOfUseTest {
    @Test fun theFullTextIsOnTheWebsiteInTheAppsLanguage() {
        assertEquals("https://us2us.app/tr/kullanim-kosullari", TermsOfUse.url(Locale.forLanguageTag("tr-TR")))
        assertEquals("https://us2us.app/en/terms", TermsOfUse.url(Locale.forLanguageTag("en-GB")))
        assertEquals("any other language reads the English page", "https://us2us.app/en/terms", TermsOfUse.url(Locale.GERMAN))
    }

    @Test fun onlyTheCurrentVersionCountsAsAccepted() {
        val preferences = MemoryPreferences().apply { acceptedTermsVersion = null }
        assertFalse(preferences.hasAcceptedCurrentTerms)
        preferences.acceptedTermsVersion = "2026-01-01"
        assertFalse("a new version asks everyone again once", preferences.hasAcceptedCurrentTerms)
        preferences.acceptTerms()
        assertTrue(preferences.hasAcceptedCurrentTerms)
        assertEquals(TermsOfUse.VERSION, preferences.acceptedTermsVersion)
    }
}
