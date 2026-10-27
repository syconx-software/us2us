import SwiftUI
import Testing

@testable import us2us

/// The shared pieces promoted when the feature screens were integrated:
/// QR payloads, Turkish suffixes, the merged `MessengerService` operations
/// (fixtures), the chats / conversation view-model rules and the CSS layouts.

private let reference = DesignFixtures.referenceDate

@MainActor
private func makeService(hasAccount: Bool = true) -> DesignFixturesService {
    DesignFixturesService(hasAccount: hasAccount, clock: .frozen(at: reference), latency: .zero)
}

// MARK: - QR payloads

@Suite("QR payloads")
struct QRPayloadTests {
    @Test func contactCodeRoundTrips() {
        let code = ContactCode.encode(username: "selin_n", keyFingerprint: "0a1b2c")
        #expect(code == "us2us:@selin_n#0a1b2c")
        #expect(ContactCode.decode(code)?.username == "selin_n")
        #expect(ContactCode.decode(code)?.keyFingerprint == "0a1b2c")
        #expect(ContactCode.username(from: "us2us:@deniz") == "deniz")
    }

    @Test func foreignTextIsNotAContactCode() {
        #expect(ContactCode.username(from: "https://example.com") == nil)
        #expect(ContactCode.username(from: "us2us:@Deniz") == nil, "usernames are lower-case ASCII")
        #expect(ContactCode.username(from: "us2us:@selin.n") == nil, "no dot")
        #expect(ContactCode.username(from: "us2us:@1abc") == nil, "a letter first")
        #expect(ContactCode.username(from: "us2us:@" + String(repeating: "a", count: 25)) == nil)
        #expect(ContactCode.username(from: "us2us:@" + String(repeating: "a", count: 24)) != nil)
        #expect(ContactCode.username(from: "us2us:@deniz#not-hex") == nil)
        #expect(ContactCode.username(from: SafetyQRCodec.encode(Data([1, 2, 3]))) == nil)
    }

    @Test func safetyCodeRoundTrips() {
        let payload = Data(DesignFixtures.safetyNumberGroups.joined().utf8)
        let text = SafetyQRCodec.encode(payload)
        #expect(text.hasPrefix("U2SN:"))
        #expect(text.dropFirst(5).allSatisfy { "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".contains($0) })
        #expect(SafetyQRCodec.decode(text) == payload)
        #expect(SafetyQRCodec.decode("us2us:@deniz") == nil)
        #expect(SafetyQRCodec.constantTimeEquals(payload, payload))
        #expect(!SafetyQRCodec.constantTimeEquals(payload, payload.dropLast()))
    }

    @Test @MainActor func theSafetyCardIsTheRealCode() throws {
        let text = SafetyQRCodec.encode(Data(DesignFixtures.safetyNumberGroups.joined().utf8))
        let modules = try #require(QRModules(payload: text))
        #expect(modules.size == 37, "60 bytes → version 5 (37 × 37) with error correction M")
        #expect(QRModules.cached(text) == modules)
    }
}

// MARK: - Turkish suffixes

@Suite("Turkish suffixes")
struct TurkishSuffixTests {
    @Test func genitive() {
        #expect(TurkishSuffix.genitive("deniz") == "deniz’in")
        #expect(TurkishSuffix.genitive("ayla_") == "ayla_’nın")
        #expect(TurkishSuffix.genitive("mert_k") == "mert_k’in")
        #expect(TurkishSuffix.genitive("kaan2") == "kaan2’nin")
        #expect(TurkishSuffix.genitive("bora") == "bora’nın")
        #expect(TurkishSuffix.genitive("oya_t") == "oya_t’ın", "a final consonant letter follows the last vowel")
    }

    @Test func dative() {
        #expect(TurkishSuffix.dative("deniz") == "deniz’e")
        #expect(TurkishSuffix.dative("ayla_") == "ayla_’ya")
        #expect(TurkishSuffix.dative("kaan") == "kaan’a")
        #expect(TurkishSuffix.dative("kaan2") == "kaan2’ye")
    }

    @Test func possessiveFollowsTheLanguage() {
        #expect(L10n.possessive("deniz", locale: Locale(identifier: "tr_TR")) == "deniz’in")
        #expect(L10n.possessive("deniz", locale: Locale(identifier: "en_US")) == "deniz’s")
    }
}

// MARK: - The merged MessengerService (fixtures)

@MainActor
@Suite("Messenger fixtures · conversation")
struct ConversationFixtureTests {
    @Test func replyCarriesItsQuoteAndMovesTheRowUp() async {
        let service = makeService()
        let quote = QuotedMessage(author: "deniz", kind: .text("Bu akşam boş musun?"))
        await service.send(text: "Evet!", replyTo: quote, in: "mert_k")
        #expect(service.messages(in: "mert_k").last?.replyTo == quote)
        #expect(service.chats.first?.id == "mert_k")
        #expect(service.chats.first?.lastMessage?.kind == .text("Evet!"))
    }

    @Test func attachmentsUpdateTheListPreview() async throws {
        let service = makeService()
        await service.send(attachment: .file(name: "a.pdf", byteCount: 1, typeLabel: "PDF", pageCount: 1), in: "deniz")
        #expect(service.chat("deniz")?.lastMessage?.kind == .file("a.pdf"))
        let ok = try #require(BrandStickerCatalog.sticker(id: 109))
        await service.send(sticker: BrandStickerSelection(ok, language: .turkish), replyTo: nil, in: "deniz")
        #expect(service.chat("deniz")?.lastMessage?.kind == .sticker)
    }

    @Test func ownReactionReplacesOnlyMine() async {
        let service = makeService()
        // d2 carries deniz's ❤️.
        await service.setReaction("❤️", on: "d2", in: "deniz")
        var reactions = service.messages(in: "deniz").first { $0.id == "d2" }?.reactions ?? []
        #expect(reactions == [Reaction(emoji: "❤️", count: 2, includesMe: true)])
        await service.setReaction("😂", on: "d2", in: "deniz")
        reactions = service.messages(in: "deniz").first { $0.id == "d2" }?.reactions ?? []
        #expect(reactions == [Reaction(emoji: "❤️", count: 1, includesMe: false), Reaction(emoji: "😂", count: 1, includesMe: true)])
        await service.setReaction(nil, on: "d2", in: "deniz")
        reactions = service.messages(in: "deniz").first { $0.id == "d2" }?.reactions ?? []
        #expect(reactions == [Reaction(emoji: "❤️", count: 1, includesMe: false)])
    }

    @Test func liveLocationStopsAndViewOnceOpensOnce() async {
        let service = makeService()
        await service.stopLiveLocation("m4", in: "mert_k")
        guard case .liveLocation(_, let active) = service.messages(in: "mert_k").first(where: { $0.id == "m4" })?.content else {
            Issue.record("m4 is not a live location")
            return
        }
        #expect(!active)
        #expect(await service.viewOnceMedia("m6", in: "mert_k") == .designArtwork(.nightPier))
        await service.markViewOnceOpened("m6", in: "mert_k")
        #expect(await service.viewOnceMedia("m6", in: "mert_k") == nil)
    }

    @Test func resendQueuesTheSameContentAgain() async {
        let service = makeService()
        service.setStatus(.failed, of: "d6", in: "deniz")
        await service.resend("d6", in: "deniz")
        let last = service.messages(in: "deniz").last
        #expect(last?.id != "d6")
        #expect(last?.content == .text("Kapı kodu 4471"))
        #expect(last?.status == .sent)
        #expect(last?.disappearing == .fiveMinutes)
        #expect(!service.messages(in: "deniz").contains { $0.id == "d6" })
    }
}

@MainActor
@Suite("Messenger fixtures · chats & verification")
struct VerificationFixtureTests {
    @Test func startChatOpensOrCreates() async {
        let service = makeService()
        #expect(await service.startChat(username: "mert_k") == "mert_k")
        #expect(await service.startChat(username: "selin_n") == nil, "not with yourself")
        let new = await service.startChat(username: "yeni_kisi")
        #expect(new == "yeni_kisi")
        #expect(service.chat("yeni_kisi")?.peer.isAcceptedContact == false, "a pending request: no presence")
        #expect(service.chats.first?.id == "yeni_kisi")
    }

    @Test func ownContactCodeNamesTheUser() async {
        let service = makeService()
        let code = await service.ownContactCode()
        #expect(code.flatMap(ContactCode.username(from:)) == "selin_n")
        #expect(await makeService(hasAccount: false).ownContactCode() == nil)
    }

    @Test func scannedSafetyCodes() async {
        let service = makeService()
        let match = SafetyQRCodec.encode(Data(DesignFixtures.safetyNumberGroups.joined().utf8))
        #expect(await service.verifyScannedSafetyCode(SafetyQRCodec.encode(Data("x".utf8)), for: "deniz") == .mismatch)
        #expect(await service.verifyScannedSafetyCode("https://example.com", for: "deniz") == .notASafetyCode)
        #expect(await service.safetyNumber(for: "deniz")?.isVerified == false, "nothing is verified without a match")
        #expect(await service.verifyScannedSafetyCode(match, for: "deniz") == .match)
        // A match is stored as a face-to-face verification in the same step.
        #expect(await service.safetyNumber(for: "deniz")?.isVerified == true)
        #expect(await service.safetyNumber(for: "deniz")?.method == .faceToFace)
    }

    @Test func addingByQRVerifiesOnlyWithTheKeysFingerprint() async {
        let service = makeService()
        #expect(await service.resolveContactCode(ContactCode.encode(username: "selin_n")) == .ownCode)
        #expect(await service.resolveContactCode(ContactCode.encode(username: "bilinmeyen")) == .unknown)
        #expect(await service.resolveContactCode("hello") == .notAContactCode)
        let otherKey = ContactCode.encode(username: "lale", keyFingerprint: String(repeating: "0", count: 32))
        #expect(
            await service.resolveContactCode(otherKey) == .keyMismatch,
            "a code for another key is a key mismatch (a swapped key), never an unknown code")
        let nobody = ContactCode.encode(username: "bilinmeyen", keyFingerprint: String(repeating: "0", count: 32))
        #expect(await service.resolveContactCode(nobody) == .unknown, "a name nobody has stays unknown")
        // Without the key's fingerprint the code proves nothing: added, not verified (no 6.3).
        #expect(await service.addContact(fromScannedCode: ContactCode.encode(username: "lale")) == .added("lale"))
        #expect(service.chat("lale")?.peer.isVerified == false)
        #expect(service.chat("lale")?.peer.offersVerification == true, "the chat offers to verify instead")
        #expect(await service.safetyNumber(for: "lale")?.isVerified == false)
        #expect(service.messages(in: "lale").isEmpty)
        // With it, the contact is verified face to face.
        let code = ContactCode.encode(username: "lale", keyFingerprint: DesignFixturesService.fixtureKeyFingerprint("lale"))
        #expect(await service.addContact(fromScannedCode: code) == .verified("lale"))
        #expect(service.chat("lale")?.peer.isVerified == true)
        #expect(service.chat("lale")?.peer.offersVerification == false)
        #expect(await service.safetyNumber(for: "lale")?.method == .faceToFace)
        #expect(service.messages(in: "lale").last?.content == .system(.safetyNumberVerified))
    }

    @Test func markingIsBoundToTheComparedNumber() async throws {
        let service = makeService()
        let shown = try #require(await service.safetyNumber(for: "deniz")?.scannablePayload)
        #expect(await service.markVerified("deniz", comparing: Data("another number".utf8)) == false)
        #expect(await service.safetyNumber(for: "deniz")?.isVerified == false)
        #expect(await service.markVerified("deniz", comparing: shown))
        #expect(await service.safetyNumber(for: "deniz")?.isVerified == true)
        #expect(await service.safetyNumber(for: "deniz")?.method == .manual)
    }

    @Test func aChangedNumberCanBeAcceptedWithoutVerifying() async throws {
        let service = makeService()
        await service.setSafetyNumberChanged("deniz")
        #expect(service.chat("deniz")?.peer.safetyNumberChanged == true)
        let shown = try #require(await service.safetyNumber(for: "deniz")?.scannablePayload)
        #expect(await service.acceptSafetyNumberChange("deniz", showing: Data("another number".utf8)) == false)
        #expect(await service.acceptSafetyNumberChange("deniz", showing: shown))
        // Accepted, which is not verified: no seal, no change pending.
        #expect(service.chat("deniz")?.peer.safetyNumberChanged == false)
        #expect(service.chat("deniz")?.peer.isVerified == false)
        let info = await service.safetyNumber(for: "deniz")
        #expect(info?.hasUnverifiedChange == false)
        #expect(info?.isVerified == false)
        #expect(service.messages(in: "deniz").last?.content == .system(.safetyNumberAccepted))
        #expect(await service.acceptSafetyNumberChange("deniz", showing: shown) == false, "nothing left to accept")
    }

    @Test func removingAVerificationClearsTheSeal() async {
        let service = makeService()
        await service.markVerified("deniz", method: .manual)
        #expect(await service.safetyNumber(for: "deniz")?.isVerified == true)
        await service.clearVerification("deniz")
        #expect(await service.safetyNumber(for: "deniz")?.isVerified == false)
        #expect(service.chat("deniz")?.peer.isVerified == false)
    }

    @Test func aChangedNumberIsUnverified() async {
        let service = makeService()
        await service.setSafetyNumberChanged("deniz")
        let info = await service.safetyNumber(for: "deniz")
        #expect(info?.hasUnverifiedChange == true)
        await service.markVerified("deniz", method: .faceToFace)
        #expect(await service.safetyNumber(for: "deniz")?.hasUnverifiedChange == false)
    }
}

@MainActor
@Suite("Messenger fixtures · settings & restore")
struct SettingsFixtureTests {
    @Test func codeChangeNeedsTheCurrentCode() async throws {
        let service = makeService()
        await #expect(throws: ChangeSecretCodeError.wrongCurrentCode) {
            try await service.changeSecretCode(current: "135790", new: "471958")
        }
        try await service.changeSecretCode(current: DesignFixtures.demoSecretCode, new: "471958")
        #expect(await service.unlock(code: "471958") == .unlocked)
        #expect(await service.backupPasswordIsSecretCode("471958"))
    }

    @Test func restoreAfterEraseBringsTheAccountBack() async throws {
        let service = makeService()
        await service.eraseEverything()
        #expect(service.account == nil)
        #expect(service.chats.isEmpty)
        try await service.completeRestore(secretCode: "471958", faceIDEnabled: false)
        #expect(service.account?.username == DesignFixtures.ownUsername)
        #expect(!service.chats.isEmpty)
        #expect(await service.unlock(code: "471958") == .unlocked)
    }
}

// MARK: - View-model rules

@MainActor
@Suite("04 · New chat lookup")
struct NewChatModelTests {
    @Test func exactLookupAfterThePause() async throws {
        let model = NewChatModel(messenger: makeService(), debounce: .milliseconds(10))
        model.query = "de"
        #expect(model.phase == .idle, "still too short: nothing is sent")
        model.query = "mert_k"
        #expect(model.phase == .pending)
        try await Task.sleep(for: .milliseconds(200))
        guard case .found(let peer, let existing) = model.phase else {
            Issue.record("expected a result, got \(model.phase)")
            return
        }
        #expect(peer.username == "mert_k")
        #expect(existing == "mert_k")
    }

    @Test func ownNameAndUnknownNames() async throws {
        let model = NewChatModel(messenger: makeService(), debounce: .milliseconds(10))
        model.query = "selin_n"
        #expect(model.phase == .ownName)
        model.query = "deniz_yilmaz"
        try await Task.sleep(for: .milliseconds(200))
        #expect(model.phase == .notFound)
    }
}

@MainActor
@Suite("04 · Request content and times")
struct RequestContentTests {
    @Test func linksAreRecognisedLocally() {
        #expect(RequestContent.classify("slides.kuzey.dev/meetup") == .link)
        #expect(RequestContent.classify("Ödülünü almak için tıkla: bit.ly/3xQ") == .containsLink)
        #expect(RequestContent.classify("Merhaba! Nasılsın?") == .plain)
    }

    @Test func requestAges() {
        let tr = Locale(identifier: "tr_TR")
        let now = reference
        #expect(ChatsFormat.requestAge(now.addingTimeInterval(-2 * 3_600), now: now, locale: tr) == "2 sa")
        #expect(ChatsFormat.requestAge(now.addingTimeInterval(-3 * 86_400), now: now, locale: tr) == "3 gün")
    }
}

@MainActor
@Suite("05 · Bubble runs and status sentences")
struct ConversationRuleTests {
    @Test func tailsAndSentences() {
        let messages = DesignFixtures.denizMessages(english: false)
        let tails = messages.indices.filter { ConversationViewModel.isTail($0, in: messages) }.map { messages[$0].id }
        #expect(tails == ["d2", "d3", "d4", "d6"], "the last bubble of each run; the system line has none")
        #expect(ConversationViewModel.statusSentenceIDs(in: messages) == ["d6"])
    }
}

// MARK: - Built-in stickers (PROTOCOL §8.8)

/// Waits (briefly) for an asynchronous state change.
@MainActor
private func stickerEventually(_ condition: @MainActor () -> Bool) async {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(3))
    while !condition(), clock.now < deadline { try? await Task.sleep(for: .milliseconds(20)) }
}

@MainActor
@Suite("05 · Built-in stickers")
struct StickerIntegrationTests {
    private func selection(_ id: UInt32, _ language: BrandStickerLanguage = .turkish) throws -> BrandStickerSelection {
        BrandStickerSelection(try #require(BrandStickerCatalog.sticker(id: id)), language: language)
    }

    @Test func aSentStickerCarriesItsIdsAndALanguageOnlyForWords() throws {
        let words = try selection(103, .english)
        let hello = try selection(1, .english)
        let onMyWay = try selection(302, .turkish)
        #expect(words.messageContent == .sticker(packID: "us2us.words", stickerID: 103, emoji: "🫂", lang: "en"))
        #expect(hello.messageContent == .sticker(packID: "us2us.cups", stickerID: 1, emoji: "👋", lang: ""))
        #expect(onMyWay.messageContent == .sticker(packID: "us2us.mood", stickerID: 302, emoji: "🏃", lang: "tr"))
    }

    @Test func fixturesSendAStickerAndPutItFirstInTheRecents() async throws {
        let service = makeService()
        let words = try selection(103, .english)
        await service.send(sticker: words, replyTo: nil, in: "deniz")
        let last = try #require(service.messages(in: "deniz").last)
        #expect(last.isOutgoing)
        #expect(last.content == .sticker(packID: "us2us.words", stickerID: 103, emoji: "🫂", lang: "en"))
        #expect(service.chats.first?.id == "deniz")
        #expect(service.chat("deniz")?.lastMessage?.kind == .sticker)
        let recents = await service.recentStickers()
        #expect(recents.first == "103.en")
        #expect(recents == BrandStickerRecents.adding(words, to: DesignFixtures.stickerRecents(english: false)))
        await service.eraseEverything()
        #expect(await service.recentStickers().isEmpty)
    }

    @Test func recentsAreIdsOnlyNewestFirstWithoutRepeatsAndAtMostTwelve() throws {
        var ids: [String] = []
        for sticker in BrandStickerCatalog.all.prefix(14) {
            ids = BrandStickerRecents.adding(BrandStickerSelection(sticker, language: .turkish), to: ids)
        }
        #expect(ids == ["103.tr", "102.tr", "101.tr", "11.-", "10.-", "9.-", "8.-", "7.-", "6.-", "5.-", "4.-", "3.-"])
        let wow = try selection(5, .english)
        ids = BrandStickerRecents.adding(wow, to: ids)
        #expect(ids.first == "5.-")
        #expect(ids.count == BrandStickerRecents.limit)
        #expect(ids.filter { $0 == "5.-" }.count == 1)
        // The same words in the other language are another recent.
        let hearYou = try selection(103, .english)
        ids = BrandStickerRecents.adding(hearYou, to: ids)
        #expect(ids.prefix(2) == ["103.en", "5.-"])
        #expect(ids.contains("103.tr"))
    }

    @Test func storedRecentsSkipWhatThisBuildDoesNotKnow() {
        let stored = ["103.en", "1.-", "999.tr", "garbage", "103.en", "1.tr", "205.de", ""]
        let selections = BrandStickerRecents.selections(from: stored, ui: .turkish)
        #expect(selections.map(\.id) == ["103.en", "1.-", "205.tr"])
        #expect(selections.first?.language == .english)
        #expect(selections.first?.wireLanguage == "en")
        #expect(selections[1].wireLanguage == "", "a wordless sticker sends no language")
    }

    @Test func aReplyToAStickerQuotesWhatItShows() throws {
        func quote(_ content: MessageContent, _ language: String = "tr") -> QuotedMessage.Kind? {
            Message(id: "q", direction: .incoming, sentAt: reference, content: content)
                .quote(ownUsername: "selin_n", peerUsername: "mert_k", locale: Locale(identifier: language))?.kind
        }
        let words = try selection(103, .english).messageContent
        let hello = try selection(1).messageContent
        #expect(quote(words) == .text("🫂 I hear you"), "the words as drawn")
        #expect(quote(hello) == .text("👋 El sallayan bardak"))
        #expect(quote(hello, "en") == .text("👋 Cup waving hello"))
        #expect(quote(.sticker(packID: "us2us.future", stickerID: 9001, emoji: "🫶", lang: "")) == .text("🫶"))
        #expect(quote(.sticker(packID: "us2us.future", stickerID: 9002, emoji: "", lang: "")) == .text("Sticker"))
        #expect(quote(.textSticker("tamam!")) == .text("tamam!"))
    }

    @Test func thePickerOpensWithTheRecentsAndClosesOnSend() async throws {
        let service = makeService()
        let model = ConversationViewModel(chatID: "mert_k", messenger: service)
        model.openStickerPicker()
        #expect(model.showsStickerPicker)
        let expected = DesignFixtures.stickerRecents(english: false)
        await stickerEventually { model.stickerRecents.map(\.id) == expected }
        #expect(model.stickerRecents.map(\.id) == expected)

        let busy = try selection(303, .english)
        model.send(sticker: busy)
        #expect(!model.showsStickerPicker, "a sticker sent closes the picker")
        #expect(model.stickerRecents.first == busy)
        await stickerEventually { service.messages(in: "mert_k").last?.content == busy.messageContent }
        #expect(service.messages(in: "mert_k").last?.content == .sticker(packID: "us2us.mood", stickerID: 303, emoji: "⏳", lang: "en"))
        #expect(await service.recentStickers().first == "303.en")
    }

    @Test func aStickerSentWhileReplyingIsTheReply() async throws {
        let service = makeService()
        let model = ConversationViewModel(chatID: "mert_k", messenger: service)
        let quote = QuotedMessage(author: "mert_k", kind: .text("Geliyor musun?"))
        model.replyTo = quote
        let onMyWay = try selection(302)
        model.send(sticker: onMyWay)
        #expect(model.replyTo == nil, "the quote leaves with the sticker")
        await stickerEventually { service.messages(in: "mert_k").last?.content == onMyWay.messageContent }
        let sent = try #require(service.messages(in: "mert_k").last)
        #expect(sent.content == onMyWay.messageContent)
        #expect(sent.replyTo == quote)
    }

    @Test func fixturesShowBrandStickersInTheDemoChat() {
        let stickers = DesignFixtures.mertMessages(english: true).compactMap { message -> BrandStickerResolution? in
            if case .sticker(_, _, _, _) = message.content { return message.content.stickerResolution(ui: .turkish) }
            return nil
        }
        #expect(stickers.count >= 3)
        for resolution in stickers {
            guard case .sticker(let selection) = resolution else {
                Issue.record("every demo sticker is in the catalog")
                continue
            }
            if selection.sticker.isText { #expect(selection.language == .english, "the demo's own language") }
        }
        #expect(DesignFixtures.mertMessages(english: false).last?.content == .textSticker("tamam!"), "the 5.2 sticker stays last")
    }

    @Test func thePickerStringsAreInTheConversationTableInBothLanguages() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/Features/Conversation/Conversation.xcstrings")
        let data = try Data(contentsOf: url)
        let catalog = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let strings = try #require(catalog["strings"] as? [String: [String: Any]])
        let keys = [
            "sticker.picker.title", "sticker.picker.recent", "sticker.picker.language", "sticker.picker.language.hint",
            "sticker.picker.language.tr", "sticker.picker.language.en", "sticker.picker.hint", "sticker.picker.preview",
            "sticker.preview.send",
        ] + BrandStickerCatalog.packs.map(\.titleKey)
        #expect(keys.count == 13)
        for key in keys {
            let localizations = strings[key]?["localizations"] as? [String: Any]
            #expect(localizations?["tr"] != nil, "\(key) has no Turkish")
            #expect(localizations?["en"] != nil, "\(key) has no English")
        }
    }
}

// MARK: - CSS layouts

@MainActor
@Suite("CSS layouts")
struct CSSLayoutTests {
    @Test func flexRowSplitsLikeCSSWithBorders() {
        // 4.3 decision bar: 340 wide, gap 8, 1 : 1 : 1.3 with the outlined “Kabul et”.
        let bar = U2FlexRow.widths(width: 340, spacing: 8, items: [(1, 0), (1, 0), (1.3, 2)])
        #expect(abs(bar[0] - 97.58) < 0.01)
        #expect(abs(bar[1] - 97.58) < 0.01)
        #expect(abs(bar[2] - 128.84) < 0.01)
        // Without borders it is the plain split.
        let plain = U2FlexRow.widths(width: 340, spacing: 8, items: [(1, 0), (1, 0), (1.3, 0)])
        #expect(abs(plain[2] - 127.64) < 0.01)
    }

    @Test func lineClampEndsAfterAWord() {
        let font = U2TextStyle.callout.uiFont()
        let text = "Merhaba! Dünkü meetup’taki sunumunu çok beğendim, slaytları paylaşabilir misin? Bağlantıyı bırakıyorum."
        let clamped = CSSLineClamp.clamp(text, font: font, width: 200, lines: 2)
        #expect(clamped.hasSuffix("…"))
        #expect(!clamped.dropLast().hasSuffix(" "))
        #expect(text.hasPrefix(String(clamped.dropLast())))
        #expect(CSSLineClamp.clamp("Kısa.", font: font, width: 200, lines: 2) == "Kısa.")
    }
}

// MARK: - Glyphs

@MainActor
@Suite("System lines")
struct SystemLineTests {
    /// Every system line draws (a glyph that is not bundled in its weight
    /// trips `PhosphorIcon`'s assertion).
    @Test func everySystemLineRenders() {
        let events: [SystemEvent] = [
            .ghostEnabled(.oneDay), .ghostDisabled, .disappearingChanged(.fiveMinutes), .screenshotTaken(by: "deniz"),
            .safetyNumberChanged, .safetyNumberAccepted, .safetyNumberVerified,
        ]
        for event in events {
            let renderer = ImageRenderer(content: SystemEventPill(event: event).frame(width: 360))
            #expect(renderer.uiImage != nil)
        }
    }
}
