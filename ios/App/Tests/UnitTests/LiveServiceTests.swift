import Foundation
import Testing

@testable import us2us

// The live service's mapping layer, driven by hand-made records (fakes of
// what RuntimeSnapshots builds from Us2usRuntime), plus the launch wiring
// and the network-endpoint rule.

private let now = Date(timeIntervalSince1970: 1_791_000_000)
private let peerID = "6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10"

private func chat(
    _ state: LiveRequestState = .accepted, username: String? = "deniz", last: LiveMessageRecord? = nil,
    verified: Bool = false, changed: Bool = false, sharing: Bool = true, ghost: (Bool, UInt32) = (false, 0),
    expire: UInt32 = 0, usernameVerified: Bool = true
) -> LiveChatRecord {
    LiveChatRecord(
        peer: peerID, username: username, usernameVerified: usernameVerified, displayName: nil, requestState: state,
        verified: verified, safetyNumberChanged: changed, presenceSharing: sharing, ghostEnabled: ghost.0,
        ghostTimeoutSeconds: ghost.1, ghostDeadline: ghost.0 ? now.addingTimeInterval(3_600) : nil,
        expireTimerSeconds: expire, unreadCount: 2, lastActivity: now, lastMessage: last)
}

private func record(
    _ id: UInt8, _ direction: LiveMessageRecord.Direction = .incoming, _ body: LiveBody = .text("selam"),
    status: LiveOutgoingStatus? = nil, viewOnce: Bool = false, expire: UInt32 = 0
) -> LiveMessageRecord {
    LiveMessageRecord(
        id: Data(repeating: id, count: 16), direction: direction, body: body, sentAt: now, quoteTarget: nil,
        quoteText: nil, expireTimerSeconds: expire, viewOnce: viewOnce, status: status)
}

private func image(thumbnail: Data? = Data([1, 2, 3]), availability: LiveAttachment.Availability = .local)
    -> LiveAttachment
{
    LiveAttachment(
        kind: .image, contentType: "image/jpeg", fileName: nil, size: 120_000, durationMs: 0, thumbnail: thumbnail,
        caption: "Moda", waveform: nil, availability: availability)
}

@Suite("Live service mapping")
@MainActor
struct LiveMappingTests {
    @Test func messageIdsRoundTripAsHex() {
        let data = Data((0..<16).map { UInt8($0 * 17) })
        let id = LiveMapping.messageID(data)
        #expect(id.count == 32)
        #expect(LiveMapping.messageData(id) == data)
        #expect(LiveMapping.messageData(id + "#1") == data, "system-line suffixes still name the record")
        #expect(LiveMapping.messageData("zz") == nil)
    }

    @Test func ghostAndTimerValuesSnapToTheDesignsChoices() {
        #expect(LiveMapping.ghostPeriod(seconds: 3_600) == .oneHour)
        #expect(LiveMapping.ghostPeriod(seconds: 86_000) == .oneDay)
        #expect(LiveMapping.seconds(.sevenDays) == 604_800)
        #expect(LiveMapping.disappearing(seconds: 0) == .off)
        #expect(LiveMapping.disappearing(seconds: 30) == .thirtySeconds)
        #expect(LiveMapping.disappearing(seconds: 290) == .fiveMinutes)
        #expect(LiveMapping.seconds(.off) == 0)
        for timer in DisappearingTimer.allCases {
            #expect(LiveMapping.disappearing(seconds: LiveMapping.seconds(timer)) == timer)
        }
        for period in GhostPeriod.allCases {
            #expect(LiveMapping.ghostPeriod(seconds: LiveMapping.seconds(period)) == period)
        }
    }

    @Test func statusesMapToTheFourDesignStates() {
        #expect(LiveMapping.status(.sending) == .sending)
        #expect(LiveMapping.status(.sent) == .sent)
        #expect(LiveMapping.status(.delivered) == .delivered)
        #expect(LiveMapping.status(.read) == .read)
        #expect(LiveMapping.status(.notDelivered) == .failed)
        #expect(LiveMapping.status(.failed) == .failed)
    }

    @Test func presenceIsReciprocalAndOnlyForAcceptedContacts() {
        #expect(LiveMapping.presence(.active, now: now, ownSharing: true) == .online)
        #expect(LiveMapping.presence(.active, now: now, ownSharing: false) == .hidden, "Kimse: nobody's either")
        #expect(LiveMapping.presence(.minutesAgo(5), now: now, ownSharing: true) == .lastSeen(now.addingTimeInterval(-300)))
        let accepted = LiveMapping.peer(chat(), presence: .active, now: now, ownSharing: true)
        #expect(accepted.isOnline)
        let pending = LiveMapping.peer(chat(.outgoingRequest), presence: .active, now: now, ownSharing: true)
        #expect(pending.presence == .hidden)
        let notSharing = LiveMapping.peer(chat(sharing: false), presence: .active, now: now, ownSharing: true)
        #expect(notSharing.presence == .hidden)
    }

    @Test func aChangedSafetyNumberRemovesTheSeal() {
        #expect(LiveMapping.peer(chat(verified: true), presence: nil, now: now, ownSharing: true).isVerified)
        #expect(!LiveMapping.peer(chat(verified: true, changed: true), presence: nil, now: now, ownSharing: true).isVerified)
    }

    @Test func chatSummaryCarriesGhostTimerUnreadAndPreview() {
        let last = record(1, .outgoing, .text("geliyorum"), status: .read)
        let summary = LiveMapping.chatSummary(
            chat(last: last, ghost: (true, 86_400), expire: 300), presence: nil, now: now, ownSharing: true)
        #expect(summary.id == peerID)
        #expect(summary.peer.username == "deniz")
        #expect(summary.peer.palette == AvatarPalette.forUsername("deniz"))
        #expect(summary.unreadCount == 2)
        #expect(summary.ghost.isEnabled && summary.ghost.period == .oneDay)
        #expect(summary.ghost.deadline == now.addingTimeInterval(3_600))
        #expect(summary.disappearing == .fiveMinutes)
        #expect(summary.lastMessage?.kind == .text("geliyorum"))
        #expect(summary.lastMessage?.status == .read)
        #expect(summary.lastMessage?.direction == .outgoing)
    }

    @Test func previewsFollowTheDesignsKinds() {
        #expect(LiveMapping.previewKind(of: record(1, .incoming, .attachment(image())), now: now) == .photo)
        #expect(LiveMapping.previewKind(of: record(1, .incoming, .attachment(image()), viewOnce: true), now: now) == .photoViewOnce)
        #expect(LiveMapping.previewKind(of: record(1, .incoming, .text("x"), expire: 30), now: now) == .disappearing)
        #expect(LiveMapping.previewKind(of: record(1, .incoming, .deleted), now: now) == .deleted)
        let live = LiveBody.liveLocation(sessionId: Data([1]), expiresAt: now.addingTimeInterval(60), stopped: false)
        #expect(LiveMapping.previewKind(of: record(1, .outgoing, live), now: now) == .liveLocation)
        #expect(LiveMapping.previewKind(of: record(1, .outgoing, live), now: now.addingTimeInterval(120)) == .liveLocationEnded)
        #expect(LiveMapping.preview(of: record(1, .system, .notice(.screenshotByMe)), now: now) == nil)
    }

    @Test func requestsFlagMediaLinksAndUnconfirmedNames() {
        let link = LiveMapping.request(chat(.incomingRequest, last: record(1, .incoming, .text("bak https://example.com"))), now: now)
        #expect(link.flags.contains(.hasLink))
        #expect(link.sender == "deniz")
        #expect(link.messages.count == 1)
        let media = LiveMapping.request(chat(.incomingRequest, last: record(1, .incoming, .attachment(image()))), now: now)
        #expect(media.flags.contains(.hasMedia))
        let unconfirmed = LiveMapping.request(chat(.incomingRequest, usernameVerified: false), now: now)
        #expect(unconfirmed.flags.contains(.suspicious))
        #expect(LiveMapping.peer(chat(.incomingRequest), presence: .active, now: now, ownSharing: true).palette == .c)
    }

    @Test func conversationMessagesMapEveryContentType() {
        let records: [LiveMessageRecord] = [
            record(1, .incoming, .text("selam")),
            record(2, .outgoing, .attachment(image()), status: .delivered),
            record(3, .incoming, .attachment(image()), viewOnce: true),
            record(4, .incoming, .location(name: "Moda İskelesi", address: "Kadıköy", latitude: 40.98, longitude: 29.02)),
            record(5, .outgoing, .sticker(packID: "us2us.text", stickerID: 0, emoji: "tamam!", lang: ""), status: .sent),
            record(6, .incoming, .deleted),
            record(7, .incoming, .viewOnceOpened),
            record(8, .system, .notice(.screenshotByContact)),
            record(9, .incoming, .unsupported),
            record(10, .incoming, .attachment(LiveAttachment(
                kind: .voice, contentType: "audio/mp4", fileName: nil, size: 9_000, durationMs: 12_000, thumbnail: nil,
                caption: nil, waveform: Data([0, 128, 255]), availability: .local))),
            record(11, .incoming, .attachment(LiveAttachment(
                kind: .file, contentType: "application/pdf", fileName: "kira.pdf", size: 2_100_000, durationMs: 0,
                thumbnail: nil, caption: nil, waveform: nil, availability: .remote))),
        ]
        let messages = LiveMapping.messages(records, ownUsername: "selin", peerUsername: "deniz", now: now, media: [:])
        #expect(messages.count == 10, "unsupported payloads are not shown")
        #expect(messages[0].content == .text("selam"))
        #expect(messages[1].content == .photo(.memory(Data([1, 2, 3])), caption: "Moda"))
        #expect(messages[1].status == .delivered)
        #expect(messages[2].content == .viewOnce(isVideo: false, state: .unopened))
        #expect(messages[3].content == .location(name: "Moda İskelesi", locality: "Kadıköy", latitude: 40.98, longitude: 29.02))
        #expect(messages[4].content == .textSticker("tamam!"))
        #expect(messages[5].content == .deleted)
        #expect(messages[6].content == .viewOnce(isVideo: false, state: .opened))
        #expect(messages[7].content == .system(.screenshotTaken(by: "deniz")))
        guard case .voice(let duration, let waveform, _) = messages[8].content else {
            Issue.record("voice expected")
            return
        }
        #expect(duration == 12)
        #expect(waveform.count == 30, "the design's 30 bars")
        #expect(messages[9].content == .file(name: "kira.pdf", byteCount: 2_100_000, typeLabel: "PDF", pageCount: nil))
    }

    /// PROTOCOL §8.8: a sticker keeps its pack, id, emoji and the language it
    /// was sent in; the bubble resolves it — a catalog sticker in that
    /// language (the reader's only without one), the text sticker of earlier
    /// builds as its card, an unknown one as its emoji, nothing as the
    /// placeholder. The chat list says “Sticker” for all of them.
    @Test func stickersKeepTheirIdsAndLanguageAndResolveToFourOutcomes() {
        let records: [LiveMessageRecord] = [
            record(1, .incoming, .sticker(packID: "us2us.words", stickerID: 103, emoji: "🫂", lang: "en")),
            record(2, .incoming, .sticker(packID: "us2us.words", stickerID: 109, emoji: "👌", lang: "")),
            record(3, .incoming, .sticker(packID: "us2us.text", stickerID: 0, emoji: " tamam! ", lang: "")),
            record(4, .incoming, .sticker(packID: "us2us.future", stickerID: 9001, emoji: "🫶", lang: "tr")),
            record(5, .outgoing, .sticker(packID: "us2us.future", stickerID: 9002, emoji: "  ", lang: ""), status: .read),
            record(6, .outgoing, .sticker(packID: "us2us.cups", stickerID: 1, emoji: "👋", lang: ""), status: .sent),
        ]
        let messages = LiveMapping.messages(records, ownUsername: "selin", peerUsername: "deniz", now: now, media: [:])
        #expect(messages.count == 6)
        #expect(messages[0].content == .sticker(packID: "us2us.words", stickerID: 103, emoji: "🫂", lang: "en"))
        #expect(messages[2].content == .textSticker("tamam!"), "earlier builds keep their Doto card")
        #expect(messages[4].status == .read)

        let words = BrandStickerCatalog.sticker(id: 103)!
        let ok = BrandStickerCatalog.sticker(id: 109)!
        let hello = BrandStickerCatalog.sticker(id: 1)!
        let resolved = messages.map { $0.content.stickerResolution(ui: .turkish) }
        #expect(resolved[0] == .sticker(BrandStickerSelection(words, language: .english)), "the sender's language")
        #expect(resolved[1] == .sticker(BrandStickerSelection(ok, language: .turkish)), "no lang: the reader's")
        #expect(resolved[2] == .legacyText("tamam!"))
        #expect(resolved[3] == .emoji("🫶"))
        #expect(resolved[4] == .unavailable)
        #expect(resolved[5] == .sticker(BrandStickerSelection(hello, language: .turkish)))
        #expect(messages[1].content.stickerResolution(ui: .english) == .sticker(BrandStickerSelection(ok, language: .english)))

        for record in records {
            #expect(LiveMapping.previewKind(of: record, now: now) == .sticker)
        }
    }

    @Test func loadedFullImagesReplaceTheThumbnail() {
        let photo = record(2, .incoming, .attachment(image()))
        let id = LiveMapping.messageID(photo.id)
        let full = Data(repeating: 9, count: 64)
        let messages = LiveMapping.messages([photo], ownUsername: "selin", peerUsername: "deniz", now: now, media: [id: full])
        #expect(messages.first?.content == .photo(.memory(full), caption: "Moda"))
    }

    @Test func repliesReactionsAndTimersCarryOver() {
        let target = record(1, .outgoing, .text("ilk"), status: .read)
        var reply = record(2, .incoming, .text("cevap"))
        reply.quoteTarget = target.id
        reply.quoteText = "ilk"
        reply.reactions = [(byMe: true, emoji: "❤️"), (byMe: false, emoji: "❤️"), (byMe: false, emoji: "😂")]
        reply.expireTimerSeconds = 30
        let messages = LiveMapping.messages([target, reply], ownUsername: "selin", peerUsername: "deniz", now: now, media: [:])
        let mapped = messages[1]
        #expect(mapped.replyTo?.author == "selin", "the quoted message is the user's own")
        #expect(mapped.replyTo?.kind == .text("ilk"))
        #expect(mapped.replyTo?.messageID == LiveMapping.messageID(target.id))
        #expect(mapped.reactions == [Reaction(emoji: "❤️", count: 2, includesMe: true), Reaction(emoji: "😂", count: 1, includesMe: false)])
        #expect(mapped.disappearing == .thirtySeconds)
        #expect(mapped.copyableText == "cevap")
    }

    @Test func settingsNoticesBecomeTheDesignsSystemLines() {
        let both = LiveNotice.settingsChanged(
            ghostEnabled: true, ghostTimeout: 3_600, expireTimer: 300, previousGhostEnabled: false,
            previousGhostTimeout: 0, previousExpireTimer: 0)
        #expect(LiveMapping.events(of: both, ownUsername: "selin", peerUsername: "deniz")
            == [.ghostEnabled(.oneHour), .disappearingChanged(.fiveMinutes)])
        let off = LiveNotice.settingsChanged(
            ghostEnabled: false, ghostTimeout: 0, expireTimer: 300, previousGhostEnabled: true,
            previousGhostTimeout: 3_600, previousExpireTimer: 300)
        #expect(LiveMapping.events(of: off, ownUsername: "selin", peerUsername: "deniz") == [.ghostDisabled])
        let rows = LiveMapping.messages(
            [record(3, .system, .notice(both))], ownUsername: "selin", peerUsername: "deniz", now: now, media: [:])
        #expect(rows.count == 2)
        #expect(Set(rows.map(\.id)).count == 2, "two lines, two ids")
        #expect(LiveMapping.events(of: .screenshotByMe, ownUsername: "selin", peerUsername: "deniz") == [.screenshotTaken(by: "selin")])
    }

    @Test func contactCodeFingerprintIsCheckedInConstantTime() {
        let key = Data((0..<33).map { UInt8($0) })
        let fingerprint = LiveMapping.identityFingerprint(key)
        #expect(fingerprint.count == 32)
        #expect(LiveMapping.fingerprintMatches(fingerprint, identityKey: key))
        #expect(LiveMapping.fingerprintMatches(fingerprint.uppercased(), identityKey: key))
        #expect(!LiveMapping.fingerprintMatches(fingerprint, identityKey: key + Data([1])))
        let code = ContactCode.encode(username: "deniz", keyFingerprint: fingerprint)
        #expect(ContactCode.decode(code)?.keyFingerprint == fingerprint)
    }

    @Test func backupHeaderAndConnectionLine() {
        #expect(LiveMapping.looksLikeBackup(Data("BRPB".utf8) + Data([1, 0, 0])))
        #expect(!LiveMapping.looksLikeBackup(Data("U2UBACKUP\u{1}".utf8)))
        #expect(!LiveMapping.looksLikeBackup(Data()))
        #expect(LiveMapping.connection(.open) == .open)
        #expect(LiveMapping.connection(.syncing) == .open)
        #expect(LiveMapping.connection(.retrying) == .connecting)
        #expect(LiveMapping.connection(.waitingForNetwork) == .closed)
        #expect(LiveMapping.connection(.revoked) == .revoked, "a signed-out phone is never “retrying”")
    }
}

@Suite("Live service wiring")
@MainActor
struct LiveWiringTests {
    @Test func productionUsesTheRelayOnly() {
        #expect(RelayEndpoint.production == URL(string: "https://relay.us2us.app"))
        #expect(AppConfiguration().relayURL == RelayEndpoint.production)
        #expect(!AppConfiguration().usesFixtures, "the live service is the default")
    }

    @Test func debugRelayOverrideFollowsTheNetworkPolicy() {
        #expect(RelayEndpoint.debugOverride("http://127.0.0.1:3000") == URL(string: "http://127.0.0.1:3000"))
        #expect(RelayEndpoint.debugOverride("https://relay.example.test") != nil)
        #expect(RelayEndpoint.debugOverride("http://relay.example.test") == nil, "plain http only to loopback")
        #expect(RelayEndpoint.debugOverride("https://user:pw@relay.us2us.app") == nil)
        #expect(RelayEndpoint.debugOverride(nil) == nil)
    }

    @Test func fixturesOnlyWhenAsked() {
        let defaults = UserDefaults(suiteName: "us2us.tests.\(UUID().uuidString)")!
        #expect(!AppConfiguration.fromLaunchArguments(defaults).usesFixtures)
        defaults.set(true, forKey: "U2Fixtures")
        #expect(AppConfiguration.fromLaunchArguments(defaults).usesFixtures)
        defaults.set("http://127.0.0.1:3000", forKey: "U2RelayURL")
        #expect(AppConfiguration.fromLaunchArguments(defaults).relayURL == URL(string: "http://127.0.0.1:3000"))
    }

    /// The design's moment (8 Oct 2026 18:52) is for the design's data: the
    /// live service shows wall-clock time (countdowns, presence, “today”,
    /// the backup's file name).
    @Test func liveServiceRunsOnTheWallClock() {
        var live = AppConfiguration()
        let liveClock = AppModel.clock(for: live, injected: false)
        #expect(!liveClock.isFrozen)
        #expect(abs(liveClock.now.timeIntervalSinceNow) < 5)

        var fixtures = AppConfiguration()
        fixtures.usesFixtures = true
        #expect(abs(AppModel.clock(for: fixtures, injected: false).now.timeIntervalSince(DesignFixtures.referenceDate)) < 5)
        #expect(abs(AppModel.clock(for: live, injected: true).now.timeIntervalSince(DesignFixtures.referenceDate)) < 5)

        live.frozenClock = true
        #expect(AppModel.clock(for: live, injected: false).now == DesignFixtures.referenceDate)
    }

    /// No network endpoint but the relay anywhere in the app's sources. The
    /// invite feature's links are text the user shares (us2us.app/u/… is the
    /// universal link that opens 4.2; wa.me is handed to WhatsApp by the
    /// system), and Hakkında's privacy policy and support pages open in the
    /// browser (`WebsitePage`) — the app itself never requests them.
    @Test func noOtherNetworkEndpointsInTheSources() throws {
        let sources = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources")
        let websitePages = [
            "https://us2us.app/tr/gizlilik", "https://us2us.app/en/privacy",
            "https://us2us.app/tr/destek", "https://us2us.app/en/support",
        ]
        // `MessageLinkText` tags tappable ranges with an opaque index under the
        // reserved `.invalid` TLD (RFC 2606); its URL action only reads the index
        // and never opens or requests that address.
        let localLinkTags = ["https://message-link.invalid/"]
        let allowed = ["https://relay.us2us.app", "https://us2us.app/u/", "https://wa.me/"] + websitePages + localLinkTags
        let pattern = try NSRegularExpression(pattern: #"https?://[A-Za-z0-9.\-]+[/A-Za-z0-9._\-]*"#)
        var offenders: [String] = []
        let enumerator = FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil)
        while let url = enumerator?.nextObject() as? URL {
            guard ["swift", "xcstrings", "plist"].contains(url.pathExtension) else { continue }
            let text = try String(contentsOf: url, encoding: .utf8)
            for match in pattern.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                guard let range = Range(match.range, in: text) else { continue }
                let found = String(text[range])
                if !allowed.contains(where: { found.hasPrefix($0) }) {
                    offenders.append("\(url.lastPathComponent): \(found)")
                }
            }
        }
        #expect(offenders.isEmpty, "unexpected endpoints: \(offenders)")
    }
}
