import Foundation
import Testing
import UIKit

@testable import us2us

// Features/Invite (docs/INVITES.md): links, the message, channel URLs,
// the pasteboard and the link inbox.

@Suite("Invite links")
struct InviteLinkTests {
    @Test func buildsTheCanonicalLink() {
        #expect(InviteLink.url(for: "selin_n")?.absoluteString == "https://us2us.app/u/selin_n")
        #expect(InviteLink.url(for: "ayla_")?.absoluteString == "https://us2us.app/u/ayla_")
        // Normalized: white space, one “@”, case (without Turkish İ/ı mapping).
        #expect(InviteLink.url(for: " @Deniz\n")?.absoluteString == "https://us2us.app/u/deniz")
        #expect(InviteLink.url(for: "KAAN2")?.absoluteString == "https://us2us.app/u/kaan2")
        #expect(InviteLink.displayString(for: "selin_n") == "us2us.app/u/selin_n")
    }

    @Test(arguments: [
        "", "ab", String(repeating: "a", count: 25), "şeyma", "ırmak", "İsmail", "de niz", "de/niz", "de%20niz", "deniz?x",
        "deniz#x", "@@deniz", "de-niz", "de+niz", "...", "_._", "selin.n", "1abc", "_abc", "deniz\u{0}",
        "dеniz",  // a Cyrillic “е”
    ])
    func refusesWhatIsNotAUsername(_ name: String) {
        #expect(InviteLink.url(for: name) == nil)
        #expect(InviteLink.normalizedUsername(name) == nil)
    }

    @Test(arguments: ["deniz", "selin_n", "mert_k", "ayla_", "kaan2", "abc", "a_b_c", String(repeating: "z", count: 24)])
    func roundTrips(_ name: String) throws {
        let url = try #require(InviteLink.url(for: name))
        #expect(InviteLink.username(from: url) == name)
        // The website's language switch and a trailing slash are fine.
        #expect(InviteLink.username(from: try #require(URL(string: url.absoluteString + "?lang=en"))) == name)
        #expect(InviteLink.username(from: try #require(URL(string: url.absoluteString + "/"))) == name)
    }

    @Test(arguments: [
        "http://us2us.app/u/deniz",  // not https
        "https://www.us2us.app/u/deniz",  // another host
        "https://us2us.app.evil.example/u/deniz",
        "https://evil.example/u/deniz",
        "https://relay.us2us.app/u/deniz",
        "https://us2us.app:8443/u/deniz",  // a port
        "https://someone@us2us.app/u/deniz",  // user info
        "https://us2us.app/u/Deniz",  // not normalized
        "https://us2us.app/u/%64eniz",  // percent-encoded
        "https://us2us.app/u/de%20niz",
        "https://us2us.app/u/deniz/x",  // more segments
        "https://us2us.app/u//deniz",
        "https://us2us.app/u/",
        "https://us2us.app/u/ab",
        "https://us2us.app/u/selin.n",  // a dot is not a username character
        "https://us2us.app/u/1abc",
        "https://us2us.app/U/deniz",
        "https://us2us.app/tr",
        "https://us2us.app/user/deniz",
        "us2us://u/deniz",  // no custom scheme
        "us2us:@deniz",  // the QR payload is not a link
        "mailto:deniz@us2us.app",
        "https://us2us.app/u/%C5%9Feyma",
    ])
    func refusesEverythingElse(_ string: String) throws {
        let url = try #require(URL(string: string))
        #expect(InviteLink.username(from: url) == nil)
    }
}

@Suite("Invite message")
struct InviteMessageTests {
    private let tr = Locale(identifier: "tr_TR")
    private let en = Locale(identifier: "en_US")

    @Test func turkishTemplate() throws {
        let message = try #require(InviteMessage.make(username: "selin_n", locale: tr))
        #expect(message.link.absoluteString == "https://us2us.app/u/selin_n")
        #expect(message.text.hasPrefix("Merhaba! Seninle us2us’ta konuşmak istiyorum"))
        #expect(message.text.contains("Kullanıcı adım: @selin_n"))
        #expect(message.text.hasSuffix("\nhttps://us2us.app/u/selin_n"))
        #expect(message.subject == "us2us’ta konuşalım mı?")
        #expect(!message.text.contains("%"))
    }

    @Test func englishTemplate() throws {
        let message = try #require(InviteMessage.make(username: "deniz", locale: en))
        #expect(message.text.hasPrefix("Hi! I’d like to talk with you on us2us"))
        #expect(message.text.contains("My username: @deniz"))
        #expect(message.text.hasSuffix("\nhttps://us2us.app/u/deniz"))
        #expect(message.subject == "Shall we talk on us2us?")
    }

    @Test func normalizesAndRefuses() {
        #expect(InviteMessage.make(username: "@Deniz", locale: tr)?.username == "deniz")
        #expect(InviteMessage.make(username: "şeyma", locale: tr) == nil)
        #expect(InviteMessage.make(username: "", locale: en) == nil)
    }

    @Test func clampsLongEdits() {
        let long = String(repeating: "ğ", count: InviteMessage.maxLength + 50)
        #expect(InviteMessage.clamped(long).count == InviteMessage.maxLength)
        #expect(InviteMessage.clamped("kısa") == "kısa")
    }
}

@Suite("Invite channel URLs")
struct InviteChannelURLTests {
    @Test func whatsAppEncodesEverythingButUnreserved() throws {
        let text = "Merhaba! us2us’ta: @selin_n & #1 + 2?=ş\nhttps://us2us.app/u/selin_n"
        let url = try #require(InviteChannelURL.whatsApp(text: text))
        #expect(
            url.absoluteString
                == "https://wa.me/?text=Merhaba%21%20us2us%E2%80%99ta%3A%20%40selin_n%20%26%20%231%20%2B%202%3F%3D%C5%9F%0Ahttps%3A%2F%2Fus2us.app%2Fu%2Fselin_n"
        )
        // Nothing the query could misread survives unencoded.
        let query = try #require(url.query(percentEncoded: true))
        #expect(!query.dropFirst("text=".count).contains(where: { " &#+?=/:@\n’ş".contains($0) }))
        // wa.me reads it back exactly.
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.host == "wa.me")
        #expect(components.queryItems == [URLQueryItem(name: "text", value: text)])
    }

    @Test func whatsAppCarriesTheWholeTurkishTemplate() throws {
        let message = try #require(InviteMessage.make(username: "selin_n", locale: Locale(identifier: "tr")))
        let url = try #require(InviteChannelURL.whatsApp(text: message.text))
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.queryItems?.first?.value == message.text)
        #expect(url.absoluteString.contains("Kullan%C4%B1c%C4%B1%20ad%C4%B1m%3A%20%40selin_n%0Ahttps%3A%2F%2Fus2us.app%2Fu%2Fselin_n"))
    }

    @Test func mailtoHasSubjectBodyAndCRLF() throws {
        let url = try #require(InviteChannelURL.mailto(subject: "us2us’ta konuşalım mı?", body: "Satır 1\nSatır 2 & ?\r\nson"))
        #expect(
            url.absoluteString
                == "mailto:?subject=us2us%E2%80%99ta%20konu%C5%9Fal%C4%B1m%20m%C4%B1%3F&body=Sat%C4%B1r%201%0D%0ASat%C4%B1r%202%20%26%20%3F%0D%0Ason"
        )
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.scheme == "mailto")
        #expect(components.path.isEmpty)  // no recipient: the user adds one in Mail
        #expect(components.queryItems?.first { $0.name == "subject" }?.value == "us2us’ta konuşalım mı?")
        #expect(components.queryItems?.first { $0.name == "body" }?.value == "Satır 1\r\nSatır 2 & ?\r\nson")
    }

    @Test func crlfNormalizesEveryLineBreak() {
        #expect(InviteChannelURL.crlf("a\nb\r\nc\rd") == "a\r\nb\r\nc\r\nd")
    }
}

@Suite("Invite pasteboard and inbox")
@MainActor
struct InvitePasteboardAndInboxTests {
    @Test func copiesOnlyTheLinkLocallyForTwoMinutes() throws {
        let link = try #require(InviteLink.url(for: "selin_n"))
        let now = Date(timeIntervalSince1970: 1_000_000)
        let options = InvitePasteboard.options(now: now)
        #expect(options[.localOnly] as? Bool == true)
        #expect(options[.expirationDate] as? Date == now.addingTimeInterval(120))
        #expect(InvitePasteboard.lifetime == 120)
        let items = InvitePasteboard.items(for: link)
        #expect(items.count == 1)
        #expect(items[0]["public.url"] as? URL == link)
        #expect(items[0]["public.plain-text"] as? String == "https://us2us.app/u/selin_n")

        let pasteboard = try #require(UIPasteboard(name: UIPasteboard.Name("us2us.tests.invite"), create: true))
        defer { UIPasteboard.remove(withName: pasteboard.name) }
        InvitePasteboard.copy(link, to: pasteboard)
        #expect(pasteboard.string == "https://us2us.app/u/selin_n")
    }

    @Test func inboxKeepsTheLatestInviteLinkOnce() throws {
        let inbox = InviteLinkInbox()
        let now = Date()
        #expect(!inbox.receive(try #require(URL(string: "https://example.com/u/deniz")), now: now))
        #expect(!inbox.receive(try #require(URL(string: "us2us://u/deniz")), now: now))
        #expect(inbox.pending == nil)
        #expect(inbox.receive(try #require(URL(string: "https://us2us.app/u/mert_k")), now: now))
        #expect(inbox.receive(try #require(URL(string: "https://us2us.app/u/deniz?lang=tr")), now: now))
        #expect(inbox.pending?.username == "deniz")
        #expect(inbox.take(now: now.addingTimeInterval(5)) == "deniz")
        #expect(inbox.take(now: now.addingTimeInterval(6)) == nil)
    }

    @Test func inboxDropsStaleLinks() throws {
        let inbox = InviteLinkInbox()
        let then = Date()
        inbox.receive(try #require(URL(string: "https://us2us.app/u/deniz")), now: then)
        #expect(inbox.take(now: then.addingTimeInterval(InviteLinkInbox.lifetime + 1)) == nil)
        #expect(inbox.pending == nil)
    }
}
