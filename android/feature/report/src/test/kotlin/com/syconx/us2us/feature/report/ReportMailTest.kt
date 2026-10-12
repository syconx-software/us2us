package com.syconx.us2us.feature.report

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.ReportReason
import com.syconx.us2us.domain.ReportSubject
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.l10n.U2Strings
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** 2026-10-03 04:00 UTC, as in the iOS tests. */
internal val reportNow: Instant = Instant.ofEpochSecond(1_791_000_000)

internal fun reportStrings(tag: String): U2Strings =
    U2Strings.of(ApplicationProvider.getApplicationContext<Context>(), Locale.forLanguageTag(tag))

/**
 * The report e-mail in both languages (iOS `ReportMailTests`): subject, body, an unconfirmed or
 * unknown username, and that nothing but the username, the reason, the note, the app's version and
 * the time goes into it. The app line names Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReportMailTest {
    private val turkish get() = reportStrings("tr-TR")
    private val english get() = reportStrings("en-TR")
    private val deniz = ReportSubject("deniz", isUsernameVerified = true)

    @Test
    fun turkishSubjectAndBody() {
        val mail = ReportMail.make(deniz, ReportReason.Harassment, "  Sürekli rahatsız ediyor \n", "1.0.8", "11", reportNow, turkish)
        assertEquals("us2us bildirimi: @deniz", mail.subject)
        assertEquals(
            """
            Bildirilen kullanıcı: @deniz
            Neden: Taciz veya zorbalık
            Not: Sürekli rahatsız ediyor

            Uygulama: us2us Android 1.0.8 (11)
            Zaman: 2026-10-03 04:00 UTC

            Mesajlar uçtan uca şifreli olduğu için bu bildirime hiçbir mesaj içeriği eklenmedi. İstersen ekran görüntüsü ekleyebilirsin.
            """.trimIndent(),
            mail.body,
        )
    }

    @Test
    fun englishSubjectAndBody() {
        val mail = ReportMail.make(deniz, ReportReason.Impersonation, "", "1.0.8", "11", reportNow, english)
        assertEquals("us2us report: @deniz", mail.subject)
        assertEquals(
            """
            Reported user: @deniz
            Reason: Impersonation
            Note: —

            App: us2us Android 1.0.8 (11)
            Time: 2026-10-03 04:00 UTC

            Messages are end-to-end encrypted, so no message content was added to this report. You may attach screenshots.
            """.trimIndent(),
            mail.body,
        )
    }

    @Test
    fun everyReasonIsNamedInBothLanguages() {
        for (reason in ReportReason.entries) {
            for ((strings, prefix) in listOf(turkish to "Neden: ", english to "Reason: ")) {
                val line = ReportMail.make(deniz, reason, "", "1", "1", reportNow, strings).body.split("\n")[1]
                assertTrue(line, line.startsWith(prefix))
                assertEquals(prefix + strings[reason.titleRes], line)
                assertFalse(line, line.contains("report_reason"))
            }
        }
        assertEquals(listOf("Spam", "Taciz veya zorbalık", "Uygunsuz içerik", "Kimliğe bürünme", "Diğer"), ReportReason.entries.map { turkish[it.titleRes] })
    }

    @Test
    fun anUnconfirmedUsernameIsMarkedAsSuch() {
        val claim = ReportSubject("hediye_kazan", isUsernameVerified = false)
        val tr = ReportMail.make(claim, ReportReason.Spam, "", "1", "1", reportNow, turkish)
        val en = ReportMail.make(claim, ReportReason.Spam, "", "1", "1", reportNow, english)
        assertEquals("us2us bildirimi: @hediye_kazan", tr.subject)
        assertTrue(tr.body.startsWith("Bildirilen kullanıcı: @hediye_kazan (kullanıcı adı doğrulanmadı)\n"))
        assertTrue(en.body.startsWith("Reported user: @hediye_kazan (username not confirmed)\n"))
    }

    @Test
    fun withoutAUsernameAReportNamesNobody() {
        for (subject in listOf(ReportSubject.Unknown, ReportSubject("", isUsernameVerified = true))) {
            val tr = ReportMail.make(subject, ReportReason.Spam, "", "1.0.8", "11", reportNow, turkish)
            assertEquals("us2us bildirimi", tr.subject)
            assertTrue(tr.body.startsWith("Bildirilen kullanıcı: kullanıcı adı bilinmiyor\n"))
            val en = ReportMail.make(subject, ReportReason.Spam, "", "1.0.8", "11", reportNow, english)
            assertEquals("us2us report", en.subject)
            assertTrue(en.body.startsWith("Reported user: username unknown\n"))
        }
    }

    /** The design's chat with deniz: none of its messages or captions end up in a report about deniz. */
    @Test
    fun noMessageContentIsEverAdded() {
        val service = FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO)
        val chat = ChatId("deniz")
        val contents = service.messages(chat).value.mapNotNull { message ->
            when (val content = message.content) {
                is MessageContent.Text -> content.text
                is MessageContent.Photo -> content.caption
                else -> null
            }
        }
        assertTrue(contents.size >= 3)
        val subject = checkNotNull(service.reportSubject(chat))
        for (strings in listOf(turkish, english)) {
            val mail = ReportMail.make(subject, ReportReason.Inappropriate, "", "1.0.8", "11", reportNow, strings)
            for (text in contents) assertFalse("“$text” is not in the report", mail.body.contains(text) || mail.subject.contains(text))
            assertFalse("nor the reporter's own name", mail.body.contains(DesignFixtures.OWN_USERNAME))
            // Eight lines, two of them empty: nothing else is ever added.
            assertEquals(8, mail.body.split("\n").size)
        }
    }

    @Test
    fun theTimeIsUtcWhereverThePhoneIs() {
        assertEquals("2026-10-03 04:00 UTC", ReportMail.utcTime(reportNow))
        assertEquals("1970-01-01 00:00 UTC", ReportMail.utcTime(Instant.EPOCH))
    }

    @Test
    fun theNoteIsKeptShort() {
        val long = "a".repeat(ReportMail.MAX_NOTE_LENGTH + 40)
        assertEquals(ReportMail.MAX_NOTE_LENGTH, ReportMail.clamped(long).length)
        val exact = "b".repeat(ReportMail.MAX_NOTE_LENGTH)
        assertEquals(exact, ReportMail.clamped(exact))
        // Characters as the user sees them: an emoji with a skin tone or a flag is one, never split.
        val emoji = "👍🏽".repeat(ReportMail.MAX_NOTE_LENGTH + 1)
        val clamped = ReportMail.clamped(emoji)
        assertEquals(ReportMail.MAX_NOTE_LENGTH, ReportMail.length(clamped))
        assertEquals("👍🏽".repeat(ReportMail.MAX_NOTE_LENGTH), clamped)
        val model = ReportModel(deniz, "deniz", offersBlock = true, mailer = { ReportDelivery.Sent }, scope = CoroutineScope(Job())) {}
        model.note = long
        assertEquals(ReportMail.MAX_NOTE_LENGTH, model.note.length)
        // The note goes into the body trimmed and clamped.
        val mail = ReportMail.make(deniz, ReportReason.Other, "  $long", "1", "1", reportNow, english)
        assertTrue(mail.body.contains("Note: " + "a".repeat(ReportMail.MAX_NOTE_LENGTH - 2) + "\n"))
    }

    @Test
    fun mailtoGoesToSupportWithSubjectAndBody() {
        val mail = ReportMail.make(deniz, ReportReason.Spam, "a&b", "1.0.8", "11", reportNow, english)
        assertTrue(mail.mailtoUri, mail.mailtoUri.startsWith("mailto:support@us2us.app?subject=us2us%20report%3A%20%40deniz&body="))
        assertTrue("CRLF line breaks, & encoded", mail.mailtoUri.contains("Note%3A%20a%26b%0D%0A"))
        assertFalse(mail.mailtoUri.contains(" "))
        val turkishMail = ReportMail.make(deniz, ReportReason.Harassment, "", "1", "1", reportNow, turkish)
        assertTrue("UTF-8 percent-encoded", turkishMail.mailtoUri.contains("Taciz%20veya%20zorbal%C4%B1k"))
    }

    // MARK: The system mailer

    @Test
    fun theMailAppGetsTheAddressSubjectAndBody() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadow = Shadows.shadowOf(app)
        val guard = ExternalActivityGuard()
        val mail = ReportMail.make(deniz, ReportReason.Spam, "", "1.0.8", "11", reportNow, english)
        assertEquals(ReportDelivery.HandedOff, SystemReportMailer(app, guard).deliver(mail))
        val intent = checkNotNull(shadow.nextStartedActivity)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals(mail.mailtoUri, intent.dataString)
        assertEquals(listOf(ReportMail.ADDRESS), intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList())
        assertEquals(mail.subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(mail.body, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue("the mail app does not lock the app", guard.isActive)
        guard.resumed()
        assertFalse(guard.isActive)
    }

    @Test
    fun withoutAMailAppTheAddressIsShown() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadow = Shadows.shadowOf(app)
        shadow.checkActivities(true)
        val guard = ExternalActivityGuard()
        val mail = ReportMail.make(deniz, ReportReason.Spam, "", "1", "1", reportNow, english)
        assertEquals(ReportDelivery.AddressShown, SystemReportMailer(app, guard).deliver(mail))
        assertFalse("nothing opened: no guard left behind", guard.isActive)
        // With a mail app installed, the same report is handed off.
        val packages = Shadows.shadowOf(app.packageManager)
        val mailApp = ComponentName("com.example.mail", "com.example.mail.Compose")
        packages.addActivityIfNotPresent(mailApp)
        packages.addIntentFilterForActivity(mailApp, IntentFilter(Intent.ACTION_SENDTO).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("mailto")
        })
        assertEquals(ReportDelivery.HandedOff, SystemReportMailer(app, guard).deliver(mail))
    }

    // MARK: Handed off, or not

    private fun resumedActivity() = Robolectric.buildActivity(ComponentActivity::class.java).setup()

    /** A mail app came up over the app (the activity stopped): the report is in its hands. */
    @Test
    fun aMailAppThatComesUpHasTheReport() = runTest {
        val controller = resumedActivity()
        val activity = controller.get()
        val observers = (activity.lifecycle as LifecycleRegistry).observerCount
        val guard = ExternalActivityGuard()
        val mail = ReportMail.make(deniz, ReportReason.Spam, "", "1", "1", reportNow, english)
        val delivery = async { SystemReportMailer(activity, guard).deliver(mail) }
        runCurrent()
        assertEquals(mail.mailtoUri, Shadows.shadowOf(activity).nextStartedActivity?.dataString)
        assertFalse("waits for the mail app to come up", delivery.isCompleted)
        controller.pause().stop()
        assertEquals(ReportDelivery.HandedOff, delivery.await())
        assertTrue("the mail app does not lock the app", guard.isActive)
        assertEquals("nothing left watching", observers, (activity.lifecycle as LifecycleRegistry).observerCount)
    }

    /**
     * Several mail apps and no default: the system asks which one (“Open with”), which only pauses
     * the app. Dismissed, it hands nothing off: nobody is blocked and the form stays.
     */
    @Test
    fun theOpenWithSheetDismissedHandsNothingOff() = runTest {
        val controller = resumedActivity()
        val observers = (controller.get().lifecycle as LifecycleRegistry).observerCount
        val mail = ReportMail.make(deniz, ReportReason.Spam, "", "1", "1", reportNow, english)
        val delivery = async { SystemReportMailer(controller.get(), ExternalActivityGuard()).deliver(mail) }
        runCurrent()
        controller.pause()
        advanceTimeBy(HandOff.PAUSE_WAIT_MILLIS * 3)
        assertFalse("the sheet is up: still waiting", delivery.isCompleted)
        controller.resume()
        assertEquals(ReportDelivery.NotSent, delivery.await())
        assertFalse(ReportDelivery.blocks(delivery.await(), alsoBlock = true))
        assertEquals(observers, (controller.get().lifecycle as LifecycleRegistry).observerCount)
    }

    /** Not even paused, the mail app opened beside the app (another window): handed off after a moment. */
    @Test
    fun aMailAppInAnotherWindowHasTheReportToo() = runTest {
        val controller = resumedActivity()
        val guard = ExternalActivityGuard()
        val mail = ReportMail.make(deniz, ReportReason.Spam, "", "1", "1", reportNow, english)
        val delivery = async { SystemReportMailer(controller.get(), guard).deliver(mail) }
        runCurrent()
        assertTrue(guard.isActive)
        advanceTimeBy(HandOff.PAUSE_WAIT_MILLIS - 1)
        assertFalse(delivery.isCompleted)
        advanceTimeBy(2)
        assertEquals(ReportDelivery.HandedOff, delivery.await())
        // The app stayed in front: no resume will come to end the guard, and a stale one would let
        // the next real background pass without the lock.
        assertFalse(guard.isActive)
    }

    /** No mail app at all: the address at once, nothing left watching the activity. */
    @Test
    fun withoutAMailAppTheActivityIsNotWatched() = runTest {
        val controller = resumedActivity()
        val activity = controller.get()
        val observers = (activity.lifecycle as LifecycleRegistry).observerCount
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)
        val guard = ExternalActivityGuard()
        val mail = ReportMail.make(deniz, ReportReason.Spam, "", "1", "1", reportNow, english)
        assertEquals(ReportDelivery.AddressShown, SystemReportMailer(activity, guard).deliver(mail))
        assertFalse(guard.isActive)
        assertEquals(observers, (activity.lifecycle as LifecycleRegistry).observerCount)
    }

    @Test
    fun theAppVersionIsRead() {
        val (version, build) = appVersion(ApplicationProvider.getApplicationContext())
        assertTrue(version.isNotEmpty())
        assertTrue(build.isNotEmpty())
    }
}
