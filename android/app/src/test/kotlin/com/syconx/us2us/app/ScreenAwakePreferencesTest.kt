package com.syconx.us2us.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenAwakePreferencesTest {
    @Test fun optInSurvivesReopenAndResetRestoresOff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = context.getSharedPreferences("awake-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        try {
            val first = AppPreferences(store, appliesLanguage = false)
            assertFalse(first.keepScreenOn)
            first.keepScreenOn = true
            val reopened = AppPreferences(store, appliesLanguage = false)
            assertTrue(reopened.keepScreenOn)
            reopened.reset()
            assertFalse(reopened.keepScreenOn)
            assertFalse(AppPreferences(store, appliesLanguage = false).keepScreenOn)
        } finally { store.edit().clear().commit() }
    }

    @Test fun acceptedTermsSurviveReopenUnderTheIosKeyAndResetForgetsThem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = context.getSharedPreferences("terms-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        try {
            val first = AppPreferences(store, appliesLanguage = false)
            assertNull("a phone that never accepted them", first.acceptedTermsVersion)
            first.acceptedTermsVersion = com.syconx.us2us.ui.env.TermsOfUse.VERSION
            assertEquals(com.syconx.us2us.ui.env.TermsOfUse.VERSION, store.getString("u2.acceptedTerms", null))
            val reopened = AppPreferences(store, appliesLanguage = false)
            assertEquals(com.syconx.us2us.ui.env.TermsOfUse.VERSION, reopened.acceptedTermsVersion)
            reopened.reset()
            assertNull(reopened.acceptedTermsVersion)
            assertNull(AppPreferences(store, appliesLanguage = false).acceptedTermsVersion)
        } finally { store.edit().clear().commit() }
    }
}
