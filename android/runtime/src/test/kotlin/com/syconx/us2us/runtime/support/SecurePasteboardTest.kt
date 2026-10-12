package com.syconx.us2us.runtime.support

import android.content.ClipboardManager
import com.syconx.us2us.runtime.support.SecurePasteboard.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/** A clipboard that the test can look into, and that another app can write to. */
class FakeClipboard : ClipboardWriting {
    var text: String? = null
        private set
    var sensitive = false
        private set
    private var clock = 0L
    private var currentStamp: Long? = null
    private var listener: (() -> Unit)? = null

    /** Whether this app may read the clipboard (it is in focus). */
    var readable = true

    override fun write(text: String, sensitive: Boolean): Long? {
        this.text = text
        this.sensitive = sensitive
        currentStamp = ++clock
        listener?.invoke()
        return stamp()
    }

    override fun stamp(): Long? = if (readable) currentStamp else null

    override fun clear() {
        text = null
        sensitive = false
        currentStamp = null
    }

    override fun onChange(listener: (() -> Unit)?) {
        this.listener = listener
    }

    /** Another app copied something; [notify] when this app is in focus and hears of it. */
    fun externalCopy(notify: Boolean = true) {
        text = "theirs"
        sensitive = false
        currentStamp = ++clock
        if (notify) listener?.invoke()
    }
}

/** Expiry timers the test fires by hand. */
class FakeTimer : ExpiryTimer {
    class Entry(val at: Instant, val action: () -> Unit) {
        var cancelled = false
    }

    val entries = mutableListOf<Entry>()

    override fun schedule(at: Instant, action: () -> Unit): () -> Unit {
        val entry = Entry(at, action)
        entries += entry
        return { entry.cancelled = true }
    }

    /** Runs every timer due at [now] that is not cancelled. */
    fun fire(now: Instant) {
        for (entry in entries.toList()) {
            if (!entry.cancelled && entry.at <= now) {
                entry.cancelled = true
                entry.action()
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurePasteboardTest {
    private val now = Instant.ofEpochSecond(1_800_000_000)

    @Test
    fun copiesAreMarkedAndExpire() {
        val board = FakeClipboard()
        val timer = FakeTimer()
        val pasteboard = SecurePasteboard(board, now = { now }, timer = timer)
        assertTrue(pasteboard.copy("12345 67890", Sensitivity.SENSITIVE))
        assertTrue(board.sensitive)
        assertEquals(now.plusSeconds(60), pasteboard.expiresAt)
        assertTrue(pasteboard.copy("hello", Sensitivity.ORDINARY))
        assertFalse(board.sensitive)
        assertEquals(now.plusSeconds(600), pasteboard.expiresAt)
        assertFalse(pasteboard.copy("disappearing", Sensitivity.FORBIDDEN))
        assertEquals("hello", board.text)
        // The sensitive copy's timer was replaced: only the ordinary one clears, at its time.
        timer.fire(now.plusSeconds(60))
        assertEquals("hello", board.text)
        timer.fire(now.plusSeconds(600))
        assertNull(board.text)
        assertNull(pasteboard.expiresAt)
    }

    @Test
    fun clearsOnlyItsOwnCopy() {
        val board = FakeClipboard()
        val pasteboard = SecurePasteboard(board, now = { now }, timer = FakeTimer())
        pasteboard.copy("code", Sensitivity.SENSITIVE)
        pasteboard.clearIfOwned()
        assertNull(board.text)
        pasteboard.copy("code", Sensitivity.SENSITIVE)
        board.externalCopy()
        pasteboard.clearIfOwned()
        assertEquals("theirs", board.text)
    }

    @Test
    fun anExpiryLeavesWhatAnotherAppCopied() {
        val board = FakeClipboard()
        val timer = FakeTimer()
        val pasteboard = SecurePasteboard(board, now = { now }, timer = timer)
        pasteboard.copy("code", Sensitivity.SENSITIVE)
        board.externalCopy(notify = false)
        timer.fire(now.plusSeconds(60))
        assertEquals("theirs", board.text)
    }

    /**
     * From the background the platform lets the app neither read the clipboard nor hear of
     * changes: a copy nothing reported replaced counts as ours, so the expiry still clears it.
     */
    @Test
    fun anExpiryInTheBackgroundStillClears() {
        val board = FakeClipboard()
        val timer = FakeTimer()
        val pasteboard = SecurePasteboard(board, now = { now }, timer = timer)
        pasteboard.copy("k7mq-x2pd-9tfa-wn3h-b8ze", Sensitivity.HAND_OFF)
        board.readable = false
        timer.fire(now.plusSeconds(60))
        assertNull(board.text)
    }

    /**
     * The generated backup password is copied to be pasted into a password manager: leaving the
     * app (which locks it) must not clear it, so only its one-minute expiry does.
     */
    @Test
    fun aHandOffSurvivesLeavingTheAppAndExpires() {
        val board = FakeClipboard()
        val timer = FakeTimer()
        val pasteboard = SecurePasteboard(board, now = { now }, timer = timer)
        assertTrue(pasteboard.copy("k7mq-x2pd-9tfa-wn3h-b8ze", Sensitivity.HAND_OFF))
        assertTrue(board.sensitive)
        assertEquals(now.plusSeconds(60), pasteboard.expiresAt)
        pasteboard.clearIfOwned()
        assertEquals("cleared before it could be pasted", "k7mq-x2pd-9tfa-wn3h-b8ze", board.text)
        // A sensitive copy made before is replaced, and nothing clears the hand-off.
        pasteboard.copy("12345 67890", Sensitivity.SENSITIVE)
        pasteboard.copy("k7mq-x2pd-9tfa-wn3h-b8ze", Sensitivity.HAND_OFF)
        pasteboard.clearIfOwned()
        assertEquals("k7mq-x2pd-9tfa-wn3h-b8ze", board.text)
        timer.fire(now.plusSeconds(60))
        assertNull(board.text)
    }

    @Test
    fun theSystemClipboardMarksSensitiveCopies() {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = SystemClipboard(context)
        clipboard.write("12345 67890", sensitive = true)
        val manager = context.getSystemService(ClipboardManager::class.java)
        val clip = manager.primaryClip!!
        assertEquals("12345 67890", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean(SystemClipboard.EXTRA_IS_SENSITIVE))
        assertEquals("", clip.description.label.toString())
        clipboard.write("hello", sensitive = false)
        assertFalse(manager.primaryClip!!.description.extras?.getBoolean(SystemClipboard.EXTRA_IS_SENSITIVE) ?: false)
        clipboard.clear()
        assertFalse(manager.hasPrimaryClip() && manager.primaryClip?.getItemAt(0)?.text?.isNotEmpty() == true)
    }
}
