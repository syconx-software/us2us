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
        let code = ContactCode.encode(username: "selin.n", keyFingerprint: "0a1b2c")
        #expect(code == "us2us:@selin.n#0a1b2c")
        #expect(ContactCode.decode(code)?.username == "selin.n")
        #expect(ContactCode.decode(code)?.keyFingerprint == "0a1b2c")
        #expect(ContactCode.username(from: "us2us:@deniz") == "deniz")
    }

    @Test func foreignTextIsNotAContactCode() {
        #expect(ContactCode.username(from: "https://example.com") == nil)
        #expect(ContactCode.username(from: "us2us:@Deniz") == nil, "usernames are lower-case ASCII")
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
        #expect(TurkishSuffix.genitive("mert.k") == "mert.k’in")
        #expect(TurkishSuffix.genitive("kaan2") == "kaan2’nin")
        #expect(TurkishSuffix.genitive("bora") == "bora’nın")
        #expect(TurkishSuffix.genitive("oya.t") == "oya.t’ın", "a final consonant letter follows the last vowel")
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
        await service.send(text: "Evet!", replyTo: quote, in: "mert.k")
        #expect(service.messages(in: "mert.k").last?.replyTo == quote)
        #expect(service.chats.first?.id == "mert.k")
        #expect(service.chats.first?.lastMessage?.kind == .text("Evet!"))
    }

    @Test func attachmentsUpdateTheListPreview() async {
        let service = makeService()
        await service.send(attachment: .file(name: "a.pdf", byteCount: 1, typeLabel: "PDF", pageCount: 1), in: "deniz")
        #expect(service.chat("deniz")?.lastMessage?.kind == .file("a.pdf"))
        await service.send(attachment: .textSticker("tamam!"), in: "deniz")
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
        await service.stopLiveLocation("m4", in: "mert.k")
        guard case .liveLocation(_, let active) = service.messages(in: "mert.k").first(where: { $0.id == "m4" })?.content else {
            Issue.record("m4 is not a live location")
            return
        }
        #expect(!active)
        #expect(await service.viewOnceMedia("m6", in: "mert.k") == .designArtwork(.nightPier))
        await service.markViewOnceOpened("m6", in: "mert.k")
        #expect(await service.viewOnceMedia("m6", in: "mert.k") == nil)
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
        #expect(await service.startChat(username: "mert.k") == "mert.k")
        #expect(await service.startChat(username: "selin.n") == nil, "not with yourself")
        let new = await service.startChat(username: "yeni.kisi")
        #expect(new == "yeni.kisi")
        #expect(service.chat("yeni.kisi")?.peer.isAcceptedContact == false, "a pending request: no presence")
        #expect(service.chats.first?.id == "yeni.kisi")
    }

    @Test func ownContactCodeNamesTheUser() async {
        let service = makeService()
        let code = await service.ownContactCode()
        #expect(code.flatMap(ContactCode.username(from:)) == "selin.n")
        #expect(await makeService(hasAccount: false).ownContactCode() == nil)
    }

    @Test func scannedSafetyCodes() async {
        let service = makeService()
        let match = SafetyQRCodec.encode(Data(DesignFixtures.safetyNumberGroups.joined().utf8))
        #expect(await service.compareScannedSafetyCode(match, for: "deniz") == .match)
        #expect(await service.compareScannedSafetyCode(SafetyQRCodec.encode(Data("x".utf8)), for: "deniz") == .mismatch)
        #expect(await service.compareScannedSafetyCode("https://example.com", for: "deniz") == .notASafetyCode)
    }

    @Test func addingByQRVerifiesAtOnce() async {
        let service = makeService()
        #expect(await service.resolveContactCode(ContactCode.encode(username: "selin.n")) == .ownCode)
        #expect(await service.resolveContactCode(ContactCode.encode(username: "bilinmeyen")) == .unknown)
        #expect(await service.resolveContactCode("hello") == .notAContactCode)
        let chat = await service.addContact(fromScannedCode: ContactCode.encode(username: "kaan"))
        #expect(chat == "kaan")
        #expect(service.chat("kaan")?.peer.isVerified == true)
        #expect(await service.safetyNumber(for: "kaan")?.method == .faceToFace)
        #expect(service.messages(in: "kaan").last?.content == .system(.safetyNumberVerified))
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
        #expect(model.phase == .idle, "not yet a valid username: nothing is sent")
        model.query = "mert.k"
        #expect(model.phase == .pending)
        try await Task.sleep(for: .milliseconds(200))
        guard case .found(let peer, let existing) = model.phase else {
            Issue.record("expected a result, got \(model.phase)")
            return
        }
        #expect(peer.username == "mert.k")
        #expect(existing)
    }

    @Test func ownNameAndUnknownNames() async throws {
        let model = NewChatModel(messenger: makeService(), debounce: .milliseconds(10))
        model.query = "selin.n"
        #expect(model.phase == .ownName)
        model.query = "deniz.yilmaz"
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
            .safetyNumberChanged, .safetyNumberVerified,
        ]
        for event in events {
            let renderer = ImageRenderer(content: SystemEventPill(event: event).frame(width: 360))
            #expect(renderer.uiImage != nil)
        }
    }
}
