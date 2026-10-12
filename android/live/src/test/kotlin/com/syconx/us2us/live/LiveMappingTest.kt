package com.syconx.us2us.live

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.AvatarSearchCopy
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ConnectionState
import com.syconx.us2us.domain.ContactCode
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessagePreviewKind
import com.syconx.us2us.domain.MessageRequest
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.domain.Reaction
import com.syconx.us2us.domain.SystemEvent
import com.syconx.us2us.domain.ViewOnceState
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.domain.sticker.StickerResolution
import com.syconx.us2us.runtime.engine.AvatarLookup
import com.syconx.us2us.runtime.engine.AvatarRecordStatus
import com.syconx.us2us.runtime.engine.ContactInfo
import com.syconx.us2us.runtime.engine.OwnProfilePicture
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The live service's mapping layer, driven by hand-made records (stand-ins for what
// RuntimeSnapshots builds from the runtime): the iOS LiveServiceTests' mapping suite.

private val now: Instant = Instant.ofEpochSecond(1_791_000_000)
private const val PEER_ID = "6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10"

private fun chat(
    state: LiveRequestState = LiveRequestState.Accepted,
    username: String? = "deniz",
    last: LiveMessageRecord? = null,
    verified: Boolean = false,
    changed: Boolean = false,
    sharing: Boolean = true,
    ghost: Pair<Boolean, Long> = false to 0L,
    expire: Long = 0,
    usernameVerified: Boolean = true,
    avatar: LiveAvatar = LiveAvatar.Initials,
) = LiveChatRecord(
    peer = PEER_ID,
    username = username,
    usernameVerified = usernameVerified,
    displayName = null,
    requestState = state,
    verified = verified,
    safetyNumberChanged = changed,
    presenceSharing = sharing,
    ghostEnabled = ghost.first,
    ghostTimeoutSeconds = ghost.second,
    ghostDeadline = if (ghost.first) now.plusSeconds(3_600) else null,
    expireTimerSeconds = expire,
    unreadCount = 2,
    lastActivity = now,
    lastMessage = last,
    avatar = avatar,
)

private fun id(byte: Int): Bytes = Bytes.of(ByteArray(16) { byte.toByte() })

private fun record(
    byte: Int,
    direction: LiveMessageRecord.Direction = LiveMessageRecord.Direction.Incoming,
    body: LiveBody = LiveBody.Text("selam"),
    status: LiveOutgoingStatus? = null,
    viewOnce: Boolean = false,
    expire: Long = 0,
) = LiveMessageRecord(id = id(byte), direction = direction, body = body, sentAt = now, expireTimerSeconds = expire, viewOnce = viewOnce, status = status)

private fun image(thumbnail: Bytes? = Bytes.of(byteArrayOf(1, 2, 3)), availability: LiveAttachment.Availability = LiveAttachment.Availability.Local) =
    LiveAttachment(
        kind = LiveAttachment.Kind.Image,
        contentType = "image/jpeg",
        fileName = null,
        size = 120_000,
        durationMs = 0,
        thumbnail = thumbnail,
        caption = "Moda",
        waveform = null,
        availability = availability,
    )

private val Incoming = LiveMessageRecord.Direction.Incoming
private val Outgoing = LiveMessageRecord.Direction.Outgoing
private val SystemLine = LiveMessageRecord.Direction.System

class LiveMappingTest {
    @Test
    fun messageIdsRoundTripAsHex() {
        val data = Bytes.ofUnsigned((0 until 16).map { it * 17 })
        val id = LiveMapping.messageId(data)
        assertEquals(32, id.value.length)
        assertEquals(data, LiveMapping.messageData(id))
        assertEquals("system-line suffixes still name the record", data, LiveMapping.messageData(MessageId(id.value + "#1")))
        assertNull(LiveMapping.messageData(MessageId("zz")))
        assertNull(LiveMapping.messageData(MessageId("")))
    }

    @Test
    fun ghostAndTimerValuesSnapToTheDesignsChoices() {
        assertEquals(GhostPeriod.OneHour, LiveMapping.ghostPeriod(3_600))
        assertEquals(GhostPeriod.OneDay, LiveMapping.ghostPeriod(86_000))
        assertEquals(604_800L, LiveMapping.seconds(GhostPeriod.SevenDays))
        assertEquals(DisappearingTimer.Off, LiveMapping.disappearing(0))
        assertEquals(DisappearingTimer.ThirtySeconds, LiveMapping.disappearing(30))
        assertEquals(DisappearingTimer.FiveMinutes, LiveMapping.disappearing(290))
        assertEquals(0L, LiveMapping.seconds(DisappearingTimer.Off))
        for (timer in DisappearingTimer.entries) assertEquals(timer, LiveMapping.disappearing(LiveMapping.seconds(timer)))
        for (period in GhostPeriod.entries) assertEquals(period, LiveMapping.ghostPeriod(LiveMapping.seconds(period)))
    }

    @Test
    fun statusesMapToTheFourDesignStates() {
        assertEquals(MessageStatus.Sending, LiveMapping.status(LiveOutgoingStatus.Sending))
        assertEquals(MessageStatus.Sent, LiveMapping.status(LiveOutgoingStatus.Sent))
        assertEquals(MessageStatus.Delivered, LiveMapping.status(LiveOutgoingStatus.Delivered))
        assertEquals(MessageStatus.Read, LiveMapping.status(LiveOutgoingStatus.Read))
        assertEquals(MessageStatus.Failed, LiveMapping.status(LiveOutgoingStatus.NotDelivered))
        assertEquals(MessageStatus.Failed, LiveMapping.status(LiveOutgoingStatus.Failed))
    }

    @Test
    fun presenceIsReciprocalAndOnlyForAcceptedContacts() {
        assertEquals(Presence.Online, LiveMapping.presence(LivePresence.Active, now, ownSharing = true))
        assertEquals("Kimse: nobody's either", Presence.Hidden, LiveMapping.presence(LivePresence.Active, now, ownSharing = false))
        assertEquals(Presence.LastSeen(now.minusSeconds(300)), LiveMapping.presence(LivePresence.MinutesAgo(5), now, ownSharing = true))
        assertTrue(LiveMapping.peer(chat(), LivePresence.Active, now, true).isOnline)
        assertEquals(Presence.Hidden, LiveMapping.peer(chat(LiveRequestState.OutgoingRequest), LivePresence.Active, now, true).presence)
        assertEquals(Presence.Hidden, LiveMapping.peer(chat(sharing = false), LivePresence.Active, now, true).presence)
    }

    @Test
    fun aChangedSafetyNumberRemovesTheSeal() {
        assertTrue(LiveMapping.peer(chat(verified = true), null, now, true).isVerified)
        assertFalse(LiveMapping.peer(chat(verified = true, changed = true), null, now, true).isVerified)
        assertTrue(LiveMapping.peer(chat(verified = true, changed = true), null, now, true).safetyNumberChanged)
    }

    @Test
    fun chatSummaryCarriesGhostTimerUnreadAndPreview() {
        val last = record(1, Outgoing, LiveBody.Text("geliyorum"), status = LiveOutgoingStatus.Read)
        val summary = LiveMapping.chatSummary(chat(last = last, ghost = true to 86_400L, expire = 300), null, now, true)
        assertEquals(ChatId(PEER_ID), summary.id)
        assertEquals("deniz", summary.peer.username)
        assertEquals(AvatarPalette.forUsername("deniz"), summary.peer.palette)
        assertEquals(2, summary.unreadCount)
        assertTrue(summary.ghost.isEnabled)
        assertEquals(GhostPeriod.OneDay, summary.ghost.period)
        assertEquals(now.plusSeconds(3_600), summary.ghost.deadline)
        assertEquals(DisappearingTimer.FiveMinutes, summary.disappearing)
        assertEquals(MessagePreviewKind.Text("geliyorum"), summary.lastMessage?.kind)
        assertEquals(MessageStatus.Read, summary.lastMessage?.status)
        assertEquals(MessageDirection.Outgoing, summary.lastMessage?.direction)
    }

    @Test
    fun aBlockedChatHasNoBadgeAndShowsTheInitial() {
        val blocked = LiveMapping.chatSummary(chat(LiveRequestState.Blocked, avatar = LiveAvatar.Preset(3)), LivePresence.Active, now, true)
        assertEquals(0, blocked.unreadCount)
        assertTrue(blocked.peer.isBlocked)
        assertEquals(AvatarPicture.Initials, blocked.peer.avatar)
        assertEquals(Presence.Hidden, blocked.peer.presence)
    }

    @Test
    fun previewsFollowTheDesignsKinds() {
        assertEquals(MessagePreviewKind.Photo, LiveMapping.previewKind(record(1, Incoming, LiveBody.Attachment(image())), now))
        assertEquals(MessagePreviewKind.PhotoViewOnce, LiveMapping.previewKind(record(1, Incoming, LiveBody.Attachment(image()), viewOnce = true), now))
        assertEquals(MessagePreviewKind.Disappearing, LiveMapping.previewKind(record(1, Incoming, LiveBody.Text("x"), expire = 30), now))
        assertEquals(MessagePreviewKind.Deleted, LiveMapping.previewKind(record(1, Incoming, LiveBody.Deleted), now))
        val live = LiveBody.LiveLocation(Bytes.of(byteArrayOf(1)), now.plusSeconds(60), stopped = false)
        assertEquals(MessagePreviewKind.LiveLocation, LiveMapping.previewKind(record(1, Outgoing, live), now))
        assertEquals(MessagePreviewKind.LiveLocationEnded, LiveMapping.previewKind(record(1, Outgoing, live), now.plusSeconds(120)))
        assertNull(LiveMapping.preview(record(1, SystemLine, LiveBody.Notice(LiveNotice.ScreenshotByMe)), now))
    }

    @Test
    fun requestsFlagMediaLinksAndUnconfirmedNames() {
        val link = LiveMapping.request(chat(LiveRequestState.IncomingRequest, last = record(1, Incoming, LiveBody.Text("bak https://example.com"))), now)
        assertTrue(MessageRequest.Flag.HasLink in link.flags)
        assertEquals("deniz", link.sender)
        assertEquals(1, link.messages.size)
        val media = LiveMapping.request(chat(LiveRequestState.IncomingRequest, last = record(1, Incoming, LiveBody.Attachment(image()))), now)
        assertTrue(MessageRequest.Flag.HasMedia in media.flags)
        val unconfirmed = LiveMapping.request(chat(LiveRequestState.IncomingRequest, usernameVerified = false), now)
        assertTrue(MessageRequest.Flag.Suspicious in unconfirmed.flags)
        assertEquals(AvatarPalette.C, LiveMapping.peer(chat(LiveRequestState.IncomingRequest), LivePresence.Active, now, true).palette)
        assertFalse(LiveMapping.containsLink("Merhaba! Nasılsın?"))
        assertTrue(LiveMapping.containsLink("Ödülünü almak için tıkla: bit.ly/3xQ"))
    }

    @Test
    fun conversationMessagesMapEveryContentType() {
        val records = listOf(
            record(1, Incoming, LiveBody.Text("selam")),
            record(2, Outgoing, LiveBody.Attachment(image()), status = LiveOutgoingStatus.Delivered),
            record(3, Incoming, LiveBody.Attachment(image()), viewOnce = true),
            record(4, Incoming, LiveBody.Location("Moda İskelesi", "Kadıköy", 40.98, 29.02)),
            record(5, Outgoing, LiveBody.Sticker("us2us.text", 0, "tamam!", ""), status = LiveOutgoingStatus.Sent),
            record(6, Incoming, LiveBody.Deleted),
            record(7, Incoming, LiveBody.ViewOnceOpened),
            record(8, SystemLine, LiveBody.Notice(LiveNotice.ScreenshotByContact)),
            record(9, Incoming, LiveBody.Unsupported),
            record(
                10, Incoming,
                LiveBody.Attachment(
                    LiveAttachment(
                        LiveAttachment.Kind.Voice, "audio/mp4", null, 9_000, 12_000, null, null,
                        Bytes.ofUnsigned(listOf(0, 128, 255)), LiveAttachment.Availability.Local,
                    ),
                ),
            ),
            record(
                11, Incoming,
                LiveBody.Attachment(
                    LiveAttachment(LiveAttachment.Kind.File, "application/pdf", "kira.pdf", 2_100_000, 0, null, null, null, LiveAttachment.Availability.Remote),
                ),
            ),
        )
        val messages = LiveMapping.messages(records, "selin", "deniz", now, emptyMap())
        assertEquals("unsupported payloads are not shown", 10, messages.size)
        assertEquals(MessageContent.Text("selam"), messages[0].content)
        assertEquals(MessageContent.Photo(MediaSource.Memory(Bytes.of(byteArrayOf(1, 2, 3))), "Moda"), messages[1].content)
        assertEquals(MessageStatus.Delivered, messages[1].status)
        assertEquals(MessageContent.ViewOnce(isVideo = false, state = ViewOnceState.Unopened), messages[2].content)
        assertEquals(MessageContent.Location("Moda İskelesi", "Kadıköy", 40.98, 29.02), messages[3].content)
        assertEquals(MessageContent.TextSticker("tamam!"), messages[4].content)
        assertEquals(MessageContent.Deleted, messages[5].content)
        assertEquals(MessageContent.ViewOnce(isVideo = false, state = ViewOnceState.Opened), messages[6].content)
        assertEquals(MessageContent.System(SystemEvent.ScreenshotTaken("deniz")), messages[7].content)
        val voice = messages[8].content as MessageContent.Voice
        assertEquals(12.seconds, voice.duration)
        assertEquals("the design's 30 bars", 30, voice.waveform.size)
        assertEquals(MessageContent.File("kira.pdf", 2_100_000, "PDF", null), messages[9].content)
        assertEquals(MediaAvailability.Remote, messages[9].mediaAvailability)
    }

    /**
     * PROTOCOL §8.8: a sticker keeps its pack, id, emoji and the language it was sent in; the bubble
     * resolves it: a catalogue sticker in that language (the reader's only without one), the text
     * sticker of earlier builds as its card, an unknown one as its emoji, nothing as the placeholder.
     */
    @Test
    fun stickersKeepTheirIdsAndLanguageAndResolveToFourOutcomes() {
        val records = listOf(
            record(1, Incoming, LiveBody.Sticker("us2us.words", 103, "🫂", "en")),
            record(2, Incoming, LiveBody.Sticker("us2us.words", 109, "👌", "")),
            record(3, Incoming, LiveBody.Sticker("us2us.text", 0, " tamam! ", "")),
            record(4, Incoming, LiveBody.Sticker("us2us.future", 9001, "🫶", "tr")),
            record(5, Outgoing, LiveBody.Sticker("us2us.future", 9002, "  ", ""), status = LiveOutgoingStatus.Read),
            record(6, Outgoing, LiveBody.Sticker("us2us.cups", 1, "👋", ""), status = LiveOutgoingStatus.Sent),
        )
        val messages = LiveMapping.messages(records, "selin", "deniz", now, emptyMap())
        assertEquals(6, messages.size)
        assertEquals(MessageContent.Sticker("us2us.words", 103, "🫂", "en"), messages[0].content)
        assertEquals("earlier builds keep their Doto card", MessageContent.TextSticker("tamam!"), messages[2].content)
        assertEquals(MessageStatus.Read, messages[4].status)

        val words = StickerCatalog.sticker(103)!!
        val ok = StickerCatalog.sticker(109)!!
        val hello = StickerCatalog.sticker(1)!!
        val resolved = messages.map { StickerCatalog.resolution(it.content, StickerLanguage.Turkish) }
        assertEquals("the sender's language", StickerResolution.Sticker(BrandStickerSelection(words, StickerLanguage.English)), resolved[0])
        assertEquals("no lang: the reader's", StickerResolution.Sticker(BrandStickerSelection(ok, StickerLanguage.Turkish)), resolved[1])
        assertEquals(StickerResolution.LegacyText("tamam!"), resolved[2])
        assertEquals(StickerResolution.Emoji("🫶"), resolved[3])
        assertEquals(StickerResolution.Unavailable, resolved[4])
        assertEquals(StickerResolution.Sticker(BrandStickerSelection(hello, StickerLanguage.Turkish)), resolved[5])
        assertEquals(
            StickerResolution.Sticker(BrandStickerSelection(ok, StickerLanguage.English)),
            StickerCatalog.resolution(messages[1].content, StickerLanguage.English),
        )
        for (record in records) assertEquals(MessagePreviewKind.Sticker, LiveMapping.previewKind(record, now))
    }

    @Test
    fun loadedFullImagesReplaceTheThumbnail() {
        val photo = record(2, Incoming, LiveBody.Attachment(image()))
        val full = Bytes.of(ByteArray(64) { 9 })
        val messages = LiveMapping.messages(listOf(photo), "selin", "deniz", now, mapOf(LiveMapping.messageId(photo.id) to full))
        assertEquals(MessageContent.Photo(MediaSource.Memory(full), "Moda"), messages.first().content)
    }

    @Test
    fun aStoredVideoKeepsThePosterItWasSentWith() {
        val attachment = LiveAttachment(
            LiveAttachment.Kind.Video, "video/mp4", null, 12_400_000, 18_000, Bytes.of(byteArrayOf(1)), null, null,
            LiveAttachment.Availability.Transferring, width = 1_280, height = 720,
        )
        val video = record(5, Outgoing, LiveBody.Attachment(attachment), status = LiveOutgoingStatus.Sent)
        val poster = Bytes.ofUnsigned(listOf(0xFF, 0xD8, 0xFF, 0xE0))
        val kept = LiveMapping.messages(listOf(video), "selin", "deniz", now, mapOf(LiveMapping.messageId(video.id) to poster)).first()
        assertEquals(MediaSource.Memory(poster), (kept.content as MessageContent.Video).source)
        assertEquals("what this phone sent is here, also while it uploads", MediaAvailability.Ready, kept.mediaAvailability)
        val plain = LiveMapping.messages(listOf(video), "selin", "deniz", now, emptyMap()).first()
        assertEquals(MediaSource.Memory(Bytes.of(byteArrayOf(1))), (plain.content as MessageContent.Video).source)
        assertEquals(18.seconds, (plain.content as MessageContent.Video).duration)
    }

    @Test
    fun downloadsShowTheirRingAndProgress() {
        val remote = image(availability = LiveAttachment.Availability.Remote)
        assertEquals(MediaAvailability.Remote, LiveMapping.availability(remote, outgoing = false))
        assertEquals(MediaAvailability.Downloading(null), LiveMapping.availability(remote, outgoing = false, download = LiveDownload()))
        val transferring = image(availability = LiveAttachment.Availability.Transferring)
        assertEquals(MediaAvailability.Downloading(0.5), LiveMapping.availability(transferring, outgoing = false, download = LiveDownload(0.5)))
        assertEquals(MediaAvailability.Ready, LiveMapping.availability(transferring, outgoing = true))
        assertEquals(MediaAvailability.Held, LiveMapping.availability(image(availability = LiveAttachment.Availability.HeldUntilAccepted), false))
        assertEquals(MediaAvailability.Unavailable, LiveMapping.availability(image(availability = LiveAttachment.Availability.Unavailable), false))
    }

    /** The runtime's progress (by its message id) and the user's requests → what each bubble's ring shows. */
    @Test
    fun theRuntimesProgressFillsTheRing() {
        val asked = LiveMapping.messageId(id(1))
        val running = LiveMapping.messageId(id(2))
        val downloads = LiveMapping.downloads(mapOf(asked to LiveDownload()), mapOf(id(2) to 0.42))
        assertEquals(mapOf(asked to LiveDownload(), running to LiveDownload(0.42)), downloads)
        // Once the runtime transfers what the user asked for, its fraction shows.
        assertEquals(LiveDownload(0.1), LiveMapping.downloads(mapOf(asked to LiveDownload()), mapOf(id(1) to 0.1))[asked])
        val transferring = image(availability = LiveAttachment.Availability.Transferring)
        assertEquals(MediaAvailability.Downloading(0.42), LiveMapping.availability(transferring, outgoing = false, download = downloads[running]))
        assertEquals(MediaAvailability.Downloading(null), LiveMapping.availability(transferring, outgoing = false, download = downloads[asked]))
        assertTrue(LiveMapping.downloads(emptyMap(), emptyMap()).isEmpty())
    }

    @Test
    fun repliesReactionsAndTimersCarryOver() {
        val target = record(1, Outgoing, LiveBody.Text("ilk"), status = LiveOutgoingStatus.Read)
        val reply = record(2, Incoming, LiveBody.Text("cevap")).copy(
            quoteTarget = target.id,
            quoteText = "ilk",
            reactions = listOf(LiveReaction(true, "❤️"), LiveReaction(false, "❤️"), LiveReaction(false, "😂")),
            expireTimerSeconds = 30,
        )
        val mapped = LiveMapping.messages(listOf(target, reply), "selin", "deniz", now, emptyMap())[1]
        assertEquals("the quoted message is the user's own", "selin", mapped.replyTo?.author)
        assertEquals(com.syconx.us2us.domain.QuotedMessage.Kind.Text("ilk"), mapped.replyTo?.kind)
        assertEquals(LiveMapping.messageId(target.id), mapped.replyTo?.messageId)
        assertEquals(listOf(Reaction("❤️", 2, includesMe = true), Reaction("😂", 1, includesMe = false)), mapped.reactions)
        assertEquals(DisappearingTimer.ThirtySeconds, mapped.disappearing)
        assertEquals("cevap", mapped.copyableText)
    }

    @Test
    fun aFailedMessageSaysWhen() {
        val failed = LiveMapping.messages(listOf(record(1, Outgoing, LiveBody.Text("x"), status = LiveOutgoingStatus.NotDelivered)), "selin", "deniz", now, emptyMap()).first()
        assertEquals(MessageStatus.Failed, failed.status)
        assertEquals(now, failed.failedAt)
        val waiting = LiveMapping.messages(listOf(record(1, Outgoing, LiveBody.Text("x"))), "selin", "deniz", now, emptyMap()).first()
        assertEquals("without a status it is still on this phone", MessageStatus.Sending, waiting.status)
    }

    @Test
    fun settingsNoticesBecomeTheDesignsSystemLines() {
        val both = LiveNotice.SettingsChanged(true, 3_600, 300, false, 0, 0)
        assertEquals(
            listOf(SystemEvent.GhostEnabled(GhostPeriod.OneHour), SystemEvent.DisappearingChanged(DisappearingTimer.FiveMinutes)),
            LiveMapping.events(both, "selin", "deniz"),
        )
        val off = LiveNotice.SettingsChanged(false, 0, 300, true, 3_600, 300)
        assertEquals(listOf(SystemEvent.GhostDisabled), LiveMapping.events(off, "selin", "deniz"))
        val rows = LiveMapping.messages(listOf(record(3, SystemLine, LiveBody.Notice(both))), "selin", "deniz", now, emptyMap())
        assertEquals(2, rows.size)
        assertEquals("two lines, two ids", 2, rows.map { it.id }.toSet().size)
        assertEquals(listOf(SystemEvent.ScreenshotTaken("selin")), LiveMapping.events(LiveNotice.ScreenshotByMe, "selin", "deniz"))
        assertEquals(emptyList<SystemEvent>(), LiveMapping.events(LiveNotice.RequestAccepted, "selin", "deniz"))
    }

    @Test
    fun contactCodeFingerprintIsCheckedInConstantTime() {
        val key = Bytes.ofUnsigned((0 until 33).toList())
        val fingerprint = LiveMapping.identityFingerprint(key)
        assertEquals(32, fingerprint.length)
        assertTrue(LiveMapping.fingerprintMatches(fingerprint, key))
        assertTrue(LiveMapping.fingerprintMatches(fingerprint.uppercase(Locale.ROOT), key))
        assertFalse(LiveMapping.fingerprintMatches(fingerprint, Bytes.ofUnsigned((0 until 33).toList() + 1)))
        val code = ContactCode.encode("deniz", fingerprint)
        assertEquals(fingerprint, ContactCode.decode(code)?.keyFingerprint)
        assertEquals(fingerprint, LiveMapping.peer(chat().copy(identityKey = key), null, now, true).identityFingerprint)
    }

    @Test
    fun backupHeaderAndConnectionLine() {
        assertTrue(LiveMapping.looksLikeBackup("BRPB".toByteArray() + byteArrayOf(1, 0, 0)))
        assertFalse(LiveMapping.looksLikeBackup("U2UBACKUP\u0001".toByteArray()))
        assertFalse(LiveMapping.looksLikeBackup(ByteArray(0)))
        assertEquals(ConnectionState.Open, LiveMapping.connection(LiveConnection.Open))
        assertEquals(ConnectionState.Open, LiveMapping.connection(LiveConnection.Syncing))
        assertEquals(ConnectionState.Connecting, LiveMapping.connection(LiveConnection.Retrying))
        assertEquals(ConnectionState.Closed, LiveMapping.connection(LiveConnection.WaitingForNetwork))
        assertEquals("a signed-out phone is never “retrying”", ConnectionState.Revoked, LiveMapping.connection(LiveConnection.Revoked))
    }

    @Test
    fun aReportNamesTheUsernameOnly() {
        assertEquals("deniz", LiveMapping.reportSubject(chat()).username)
        assertTrue(LiveMapping.reportSubject(chat()).isUsernameVerified)
        assertFalse(LiveMapping.reportSubject(chat(usernameVerified = false)).isUsernameVerified)
        val anonymous = LiveMapping.reportSubject(chat(username = null).copy(displayName = "Deniz"))
        assertNull("never the display name or the account id", anonymous.username)
    }

    @Test
    fun blockedPeopleAreListedByNameWithTheInitial() {
        val people = LiveMapping.blockedPeople(
            listOf(
                LiveBlockedRecord(PEER_ID, null, "Kim"),
                LiveBlockedRecord("aaaaaaaa-0000-4000-8000-000000000001", "zeynep", null),
                LiveBlockedRecord("bbbbbbbb-0000-4000-8000-000000000002", "ali", null),
            ),
        )
        assertEquals(listOf("ali", "zeynep", "Kim"), people.map { it.name })
        assertFalse("no username kept: listed last", people.last().isUsernameKnown)
        assertEquals(ChatId(PEER_ID), people.last().id)
        assertTrue(people.all { it.avatar == AvatarPicture.Initials })
    }

    // MARK: 7.1 › Engellenenler (the iOS BlockedPeopleMappingTests)

    @Test
    fun aBlockedContactIsListedByUsernameWithTheInitial() {
        val person = LiveMapping.blockedPerson(LiveBlockedRecord(PEER_ID, "deniz", "Deniz Y."))
        assertEquals("unblocked by its account, chat or not", ChatId(PEER_ID), person.id)
        assertEquals("deniz", person.name)
        assertTrue(person.isUsernameKnown)
        assertEquals(AvatarPalette.forUsername("deniz"), person.palette)
        assertEquals("no picture for someone blocked", AvatarPicture.Initials, person.avatar)
    }

    /**
     * A ghost wipe with `wipe_contact` keeps only the block: the list shows what 4.1 would (the
     * display name, else a short account id) and says that the username is unknown.
     */
    @Test
    fun withoutAUsernameTheListShowsWhat41WouldAndSaysSo() {
        val named = LiveMapping.blockedPerson(LiveBlockedRecord(PEER_ID, null, "Deniz Y."))
        assertEquals("Deniz Y.", named.name)
        assertFalse(named.isUsernameKnown)
        val bare = LiveMapping.blockedPerson(LiveBlockedRecord(PEER_ID, "", null))
        assertEquals(PEER_ID.take(8), bare.name)
        assertFalse(bare.isUsernameKnown)
    }

    @Test
    fun theBlockedListIsByNameWithUnknownNamesLast() {
        val people = LiveMapping.blockedPeople(
            listOf(
                LiveBlockedRecord("c-$PEER_ID", null, null),
                LiveBlockedRecord("b", "mert_k", null),
                LiveBlockedRecord("a", "ayla_", null),
                LiveBlockedRecord("d", "kuzey_dev", null),
            ),
        )
        assertEquals(listOf("ayla_", "kuzey_dev", "mert_k", "c-6f1c2a"), people.map { it.name })
    }

    /** The runtime's blocked contact (also one without a conversation) → the record, field by field. */
    @Test
    fun aRuntimeBlockedContactBecomesItsRecord() {
        val contact = ContactInfo(
            account = AccountId.parse(PEER_ID),
            requestState = ContactState.BLOCKED,
            displayName = null,
            username = null,
            usernameVerified = false,
            presenceSharing = false,
            safetyNumberChanged = false,
            verified = false,
            identityKey = null,
            avatar = null,
        )
        assertEquals(LiveBlockedRecord(PEER_ID, null, null), LiveBlockedRecord.of(contact))
        val named = LiveBlockedRecord.of(contact.copy(username = "deniz", displayName = "Deniz Y."))
        assertEquals(LiveBlockedRecord(PEER_ID, "deniz", "Deniz Y."), named)
        assertEquals(ChatId(PEER_ID), LiveMapping.blockedPeople(listOf(named)).single().id)
    }

    // MARK: Pictures (§8.9)

    @Test
    fun whichPictureEachChatShows() {
        val photo = LiveAvatar.Photo(Bytes.of(byteArrayOf(1, 2)), "image/jpeg")
        assertTrue(LiveMapping.avatar(chat(avatar = photo)).isPhoto)
        assertEquals("the user's own request shows the search copy", AvatarPicture.preset(4), LiveMapping.avatar(chat(LiveRequestState.OutgoingRequest, avatar = LiveAvatar.Preset(4))))
        assertEquals("a stranger's picture is never shown on its own", AvatarPicture.Initials, LiveMapping.avatar(chat(LiveRequestState.IncomingRequest, avatar = photo)))
        assertEquals(AvatarPicture.Initials, LiveMapping.avatar(chat(LiveRequestState.Blocked, avatar = photo)))
        assertEquals("preset 0 is no preset", AvatarPicture.Initials, LiveMapping.avatar(LiveAvatar.Preset(0)))
        assertTrue(LiveMapping.offersAvatar(chat(LiveRequestState.IncomingRequest)))
        assertFalse(LiveMapping.offersAvatar(chat(LiveRequestState.IncomingRequest, usernameVerified = false)))
        assertFalse(LiveMapping.offersAvatar(chat(LiveRequestState.Accepted)))
        assertTrue(LiveMapping.request(chat(LiveRequestState.IncomingRequest), now).offersAvatar)
        assertEquals(AvatarPicture.Initials, LiveMapping.request(chat(LiveRequestState.IncomingRequest, avatar = photo), now).avatar)
    }

    @Test
    fun theOwnPictureAndItsSearchCopy() {
        val locked = LiveMapping.ownAvatar(null)
        assertEquals(AvatarPicture.Initials, locked.picture)
        assertTrue("the setting reads on while locked", locked.showInSearch)
        val own = LiveMapping.ownAvatar(LiveOwnAvatar(LiveAvatar.Preset(7), showInSearch = false, status = LiveAvatarSearchStatus.Pending))
        assertEquals(AvatarPicture.preset(7), own.picture)
        assertFalse(own.showInSearch)
        assertEquals(AvatarSearchCopy.Pending, own.searchCopy)
        assertEquals(AvatarSearchCopy.Unavailable, LiveMapping.ownAvatar(LiveOwnAvatar(LiveAvatar.Initials, true, LiveAvatarSearchStatus.Unavailable)).searchCopy)
    }

    /** The runtime's own picture with its record status → the record, for each status. */
    @Test
    fun theRuntimesOwnPictureBecomesItsRecord() {
        val preset = ProfilePicture.preset(7)
        assertEquals(
            LiveOwnAvatar(LiveAvatar.Preset(7), showInSearch = true, status = LiveAvatarSearchStatus.InSync),
            LiveOwnAvatar.of(OwnProfilePicture(preset, showInSearch = true, searchRecord = AvatarRecordStatus.IN_SYNC)),
        )
        val pending = LiveOwnAvatar.of(OwnProfilePicture(preset, showInSearch = true, searchRecord = AvatarRecordStatus.PENDING))
        assertEquals(LiveAvatarSearchStatus.Pending, pending.status)
        assertEquals(AvatarSearchCopy.Pending, LiveMapping.ownAvatar(pending).searchCopy)
        val unavailable = LiveOwnAvatar.of(OwnProfilePicture(null, showInSearch = false, searchRecord = AvatarRecordStatus.UNAVAILABLE))
        assertEquals(LiveOwnAvatar(LiveAvatar.Initials, showInSearch = false, status = LiveAvatarSearchStatus.Unavailable), unavailable)
        assertEquals(AvatarSearchCopy.Unavailable, LiveMapping.ownAvatar(unavailable).searchCopy)
    }

    /** A search result's picture as the runtime found it → what 4.2 draws once its ring stops. */
    @Test
    fun aSearchResultsPictureIsDrawnAsFound() {
        assertEquals(LiveAvatar.Initials, LiveAvatar.of(null))
        assertEquals(AvatarPicture.Initials, LiveMapping.avatar(LiveAvatar.of(null)))
        assertEquals(LiveAvatar.Preset(4), LiveAvatar.of(ProfilePicture.preset(4)))
        assertEquals(AvatarPicture.preset(4), LiveMapping.avatar(LiveAvatar.of(ProfilePicture.preset(4))))
    }

    /** What a 4.2 result leaves for the user's own request to that account (§8.9): only what a search found. */
    @Test
    fun aSearchLeavesItsCopyForTheOwnRequestOnly() {
        assertEquals(LiveAvatar.Preset(4), LiveAvatar.searchCopy(AvatarLookup(ProfilePicture.preset(4), AvatarLookup.Source.SEARCH)))
        assertEquals("none found is remembered as the initial", LiveAvatar.Initials, LiveAvatar.searchCopy(AvatarLookup.INITIALS))
        assertNull("a contact's own picture is no search copy", LiveAvatar.searchCopy(AvatarLookup(ProfilePicture.preset(4), AvatarLookup.Source.CONTACT)))
        assertNull(LiveAvatar.searchCopy(AvatarLookup(ProfilePicture.preset(4), AvatarLookup.Source.OWN)))
    }

    // MARK: Live location (5.2 › harita)

    private fun share(byte: Int, outgoing: Boolean = false, sequence: Long = 0, heading: Float = -1f, stopped: Boolean = false, minutes: Long = 60) =
        LiveLocationRecord(id(byte), outgoing, now, 40.9846, 29.0249, 15f, heading, now.plusSeconds(minutes * 60), sequence, stopped)

    @Test
    fun tickTimesFollowTheSequence() {
        val seen = now.plusSeconds(120)
        var ticks = LiveMapping.liveTicks(listOf(share(1), share(2, sequence = 4)), emptyMap(), seen)
        assertEquals("a share at its start began when it was sent", now, ticks[id(1)]?.at)
        assertNull("a share first seen mid-way has no known time", ticks[id(2)]?.at)
        val moved = seen.plusSeconds(20)
        ticks = LiveMapping.liveTicks(listOf(share(1, sequence = 1), share(2, sequence = 4)), ticks, moved)
        assertEquals(moved, ticks[id(1)]?.at)
        assertEquals(4L, ticks[id(2)]?.sequence)
        assertNull("an unchanged sequence keeps what was known", ticks[id(2)]?.at)
        ticks = LiveMapping.liveTicks(listOf(share(2, sequence = 4)), ticks, moved)
        assertEquals("shares that are gone are forgotten", 1, ticks.size)
    }

    @Test
    fun theSnapshotCarriesBothSides() {
        val records = listOf(share(1), share(2, outgoing = true, heading = 90f))
        val snapshot = LiveMapping.liveLocationSnapshot(records, LiveMapping.liveTicks(records, emptyMap(), now))
        val peer = snapshot.activeShare(MessageDirection.Incoming, now)!!
        assertEquals(LiveMapping.messageId(id(1)), peer.id)
        assertFalse(peer.isOutgoing)
        assertNull("a negative heading is unknown", peer.position.heading)
        assertEquals(15.0, peer.position.accuracy, 0.0)
        assertEquals(now, peer.position.updatedAt)
        val own = snapshot.activeShare(MessageDirection.Outgoing, now)!!
        assertTrue(own.isOutgoing)
        assertEquals(90.0, own.position.heading!!, 0.0)
    }

    @Test
    fun endedSharesAreNoLongerShown() {
        val snapshot = LiveMapping.liveLocationSnapshot(listOf(share(1, stopped = true), share(2, minutes = 1)), emptyMap())
        assertEquals(2, snapshot.shares.size)
        assertEquals(LiveMapping.messageId(id(2)), snapshot.activeShare(MessageDirection.Incoming, now)?.id)
        assertNull(snapshot.activeShare(MessageDirection.Incoming, now.plusSeconds(61)))
        assertEquals(true, snapshot.share(LiveMapping.messageId(id(1)))?.isStopped)
    }
}

/** Saved media in the live mapping (PROTOCOL §8.4): the contact's SAVED and our own saves, whatever the read-receipt setting. */
class SavedReceiptMappingTest {
    private fun saved(
        kind: LiveAttachment.Kind,
        byte: Int,
        direction: LiveMessageRecord.Direction,
        timer: Long = 0,
        viewOnce: Boolean = false,
        savedByPeerAt: Instant? = null,
        savedByMeAt: Instant? = null,
    ): LiveMessageRecord {
        val attachment = LiveAttachment(
            kind = kind,
            contentType = if (kind == LiveAttachment.Kind.File) "application/pdf" else "image/jpeg",
            fileName = if (kind == LiveAttachment.Kind.File) "rapor.pdf" else null,
            size = 1_000,
            durationMs = if (kind == LiveAttachment.Kind.Voice) 2_000 else 0,
            thumbnail = null,
            caption = null,
            waveform = null,
            availability = LiveAttachment.Availability.Local,
        )
        return LiveMessageRecord(
            id = id(byte),
            direction = direction,
            body = LiveBody.Attachment(attachment),
            sentAt = now,
            expireTimerSeconds = timer,
            viewOnce = viewOnce,
            status = if (direction == Outgoing) LiveOutgoingStatus.Delivered else null,
            savedByPeerAt = savedByPeerAt,
            savedByMeAt = savedByMeAt,
        )
    }

    private fun map(records: List<LiveMessageRecord>, readReceipts: Boolean = true) =
        LiveMapping.messages(records, "selin", "deniz", now, emptyMap(), showsPeerPlayback = readReceipts)

    @Test
    fun theContactsSaveShowsOnOurPhotosVideosAndFilesAlsoWithReadReceiptsOff() {
        val at = now.plusSeconds(60)
        val records = listOf(LiveAttachment.Kind.Image, LiveAttachment.Kind.Video, LiveAttachment.Kind.File)
            .mapIndexed { index, kind -> saved(kind, index + 1, Outgoing, savedByPeerAt = at) }
        for (messages in listOf(map(records), map(records, readReceipts = false))) {
            assertEquals(listOf(true, true, true), messages.map { it.savedByPeer })
            assertEquals("5.4 › Kaydedildi shows when", listOf(at, at, at), messages.map { it.savedByPeerAt })
            assertTrue(messages.none { it.savedByMe })
        }
        val unsaved = map(listOf(saved(LiveAttachment.Kind.Image, 9, Outgoing))).first()
        assertFalse(unsaved.savedByPeer)
        assertNull(unsaved.savedByPeerAt)
    }

    @Test
    fun ourOwnSaveShowsOnReceivedAndSentItems() {
        val at = now.plusSeconds(5)
        val messages = map(
            listOf(
                saved(LiveAttachment.Kind.Image, 1, Incoming, savedByMeAt = at),
                saved(LiveAttachment.Kind.File, 2, Outgoing, savedByMeAt = at),
                saved(LiveAttachment.Kind.Video, 3, Incoming),
            ),
        )
        assertEquals(listOf(true, true, false), messages.map { it.savedByMe })
        assertTrue(messages.none { it.savedByPeer })
    }

    @Test
    fun nothingElseShowsAsSaved() {
        val at = now.plusSeconds(5)
        val messages = map(
            listOf(
                // A received item never shows the contact's save.
                saved(LiveAttachment.Kind.Image, 1, Incoming, savedByPeerAt = at),
                saved(LiveAttachment.Kind.Voice, 2, Outgoing, savedByPeerAt = at, savedByMeAt = at),
                saved(LiveAttachment.Kind.Image, 3, Outgoing, viewOnce = true, savedByPeerAt = at, savedByMeAt = at),
                saved(LiveAttachment.Kind.Video, 4, Outgoing, timer = 3600, savedByPeerAt = at, savedByMeAt = at),
            ),
        )
        assertEquals(4, messages.size)
        assertTrue(messages.all { !it.savedByPeer && !it.savedByMe })
    }

    @Test
    fun aNewSaveIsAChangeOfTheRecord() {
        val before = saved(LiveAttachment.Kind.Image, 1, Outgoing)
        assertFalse(before == before.copy(savedByPeerAt = now))
        assertFalse(before == before.copy(savedByMeAt = now))
    }

    @Test
    fun aPlayedVoiceMessageFollowsTheReadReceipts() {
        val played = saved(LiveAttachment.Kind.Voice, 1, Outgoing).copy(viewedAt = now)
        assertTrue(map(listOf(played)).first().isVoicePlayed)
        assertFalse("read receipts are mutual", map(listOf(played), readReceipts = false).first().isVoicePlayed)
        val mine = saved(LiveAttachment.Kind.Voice, 2, Incoming).copy(viewedAt = now)
        assertTrue(map(listOf(mine), readReceipts = false).first().isVoicePlayed)
    }

    @Test
    fun theMediaTabsAskForTheRightKind() {
        assertEquals(com.syconx.us2us.runtime.engine.MediaKind.VISUAL, mediaKind(com.syconx.us2us.domain.ChatMediaKind.Visual))
        assertEquals(com.syconx.us2us.runtime.engine.MediaKind.DOCUMENTS, mediaKind(com.syconx.us2us.domain.ChatMediaKind.Documents))
    }
}
