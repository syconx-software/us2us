import SwiftUI
import XCTest

@testable import us2us

/// Every shared component laid out with the design's data must measure what
/// the design measures (inventory.md element tables, Chrome's getBoundingClientRect).
@MainActor
final class ComponentMetricsTests: XCTestCase {
    private let deniz = DesignFixtures.denizMessages(english: false)
    private let mert = DesignFixtures.mertMessages(english: false)

    private func message(_ id: String) -> Message {
        (deniz + mert).first { $0.id == id }!
    }

    // MARK: Buttons & entry

    func testPrimaryButtonsAre54High() {
        let primary = layoutSize(Button {} label: { U2ButtonLabel(verbatim: "Başla") }.buttonStyle(.u2Primary), width: 354)
        XCTAssertMeasure(primary.height, 54)
        let secondary = layoutSize(Button {} label: { U2ButtonLabel(verbatim: "Kodla aç", glyph: .password) }.buttonStyle(.u2Secondary), width: 354)
        XCTAssertMeasure(secondary.height, 54)
        let inCard = layoutSize(Button {} label: { U2ButtonLabel(verbatim: "Mesaj yaz") }.buttonStyle(.u2PrimaryInCard), width: 338)
        XCTAssertMeasure(inCard.height, 48)
        let text = layoutSize(Button {} label: { U2ButtonLabel(verbatim: "Yedekten geri yükle") }.buttonStyle(.u2Text), width: 354)
        XCTAssertMeasure(text.height, 44)
    }

    func testCodeDotsAndKeypad() {
        let dots = layoutSize(CodeDots(filled: 4))
        XCTAssertMeasure(dots.width, 190)
        XCTAssertMeasure(dots.height, 15)
        let keypad = layoutSize(NumericKeypad(onDigit: { _ in }, onDelete: {}))
        XCTAssertMeasure(keypad.width, 286)
        XCTAssertMeasure(keypad.height, 360)
        let pageDots = layoutSize(PageDots(count: 3, current: 0))
        XCTAssertMeasure(pageDots.width, 46)
        XCTAssertMeasure(pageDots.height, 6)
    }

    // MARK: Avatars, badges, Ghost

    func testAvatarSizes() {
        XCTAssertEqual(layoutSize(Avatar(username: "deniz", palette: .a)), CGSize(width: 52, height: 52))
        XCTAssertEqual(layoutSize(Avatar(username: "deniz", palette: .a, size: .header)), CGSize(width: 38, height: 38))
        XCTAssertEqual(layoutSize(Avatar(username: "kuzey.dev", palette: .c, size: .profile)), CGSize(width: 64, height: 64))
        XCTAssertEqual(layoutSize(CountBadge(count: 2)), CGSize(width: 22, height: 22))
    }

    func testGhostPillAndChip() {
        let now = DesignFixtures.referenceDate
        let chats = DesignFixtures.chats(now: now, ghostUrgent: false, english: false)
        let pill = layoutSize(GhostPill(ghost: chats[0].ghost))
        XCTAssertMeasure(pill.width, 68.75)
        XCTAssertMeasure(pill.height, 22)
        let chip = layoutSize(GhostChip(ghost: chats[0].ghost) {})
        XCTAssertMeasure(chip.width, 111.2)
        XCTAssertMeasure(chip.height, 34)
        let off = layoutSize(GhostChip(ghost: .off) {})
        XCTAssertMeasure(off.width, 94.41)
    }

    // MARK: Rows & cards

    func testChatListRowAndRequestsCard() {
        let chat = DesignFixtures.chats(now: DesignFixtures.referenceDate, ghostUrgent: false, english: false)[0]
        XCTAssertMeasure(layoutSize(ChatListRow(chat: chat), width: 402).height, 76)
        XCTAssertMeasure(layoutSize(RequestsCard(count: 2) {}, width: 370).height, 72.94)
    }

    func testCards() {
        let banner = InfoBanner(message: Text(verbatim: "Kabul edene kadar sohbet açılmaz. Okuduğunu ve aktif olduğunu göremezler."))
        XCTAssertMeasure(layoutSize(banner, width: 370).height, 67.13)
        let option = OptionCard(
            glyph: .fileLock, title: Text(verbatim: "Yedekten geri yükle"),
            description: Text(verbatim: "Şifreli yedeğin ve parolası varsa sohbetlerin geri gelir.")
        ) {}
        XCTAssertMeasure(layoutSize(option, width: 350).height, 92.17)
        let note = NoteCard(
            title: Text(verbatim: "İletilemedi ne demek?"),
            message: Text(
                verbatim:
                    "Alıcı 14 gün içinde uygulamayı açmazsa mesaj sunucudan silinir ve ona hiç ulaşmaz. Mesajın altında “Tekrar gönder” belirir."
            ))
        XCTAssertMeasure(layoutSize(note, width: 370).height, 116.55)
        let pill = layoutSize(SystemPill(text: Text(verbatim: "Ghost mod açık · 24 saat")))
        XCTAssertMeasure(pill.width, 229.81)
        XCTAssertMeasure(pill.height, 27.39)
    }

    // MARK: Bubbles (5.1, 5.2)

    func testTextBubbles() {
        let first = layoutSize(MessageBubbleView(message: message("d1"), isTail: false).fixedSize())
        XCTAssertMeasure(first.width, 195.5)
        XCTAssertMeasure(first.height, 55.58)
        let tamam = layoutSize(MessageBubbleView(message: message("d5"), isTail: false).fixedSize())
        XCTAssertMeasure(tamam.width, 228.95)
        XCTAssertMeasure(tamam.height, 59.08)
        let kapi = layoutSize(MessageBubbleView(message: message("d6"), isTail: true).fixedSize())
        XCTAssertMeasure(kapi.width, 141.92)
        XCTAssertMeasure(kapi.height, 59.08)
    }

    func testReplyPhotoVoiceBubbles() {
        let reply = layoutSize(MessageBubbleView(message: message("d3"), isTail: true).fixedSize())
        XCTAssertMeasure(reply.width, 248.45)
        XCTAssertMeasure(reply.height, 113.08)
        // The photo bubble keeps 14 pt below for its reaction chip.
        let photo = layoutSize(MessageBubbleView(message: message("d2"), isTail: true).fixedSize())
        XCTAssertMeasure(photo.width, 268)
        XCTAssertMeasure(photo.height, 234.58 + 14)
        let voice = layoutSize(MessageBubbleView(message: message("d4"), isTail: true).fixedSize())
        XCTAssertMeasure(voice.width, 239.81)
        XCTAssertMeasure(voice.height, 69.5)
    }

    func testContentBubbles() {
        let file = layoutSize(MessageBubbleView(message: message("m1"), isTail: true).fixedSize())
        XCTAssertMeasure(file.width, 237.7, tolerance: 1)
        XCTAssertMeasure(file.height, 88)
        let location = layoutSize(MessageBubbleView(message: message("m3"), isTail: true).fixedSize())
        XCTAssertMeasure(location.width, 196.11, tolerance: 1)
        XCTAssertMeasure(location.height, 96.5)
        let live = layoutSize(MessageBubbleView(message: message("m4"), isTail: true).fixedSize())
        XCTAssertMeasure(live.width, 266)
        XCTAssertMeasure(live.height, 170.17, tolerance: 1)
        let deleted = layoutSize(MessageBubbleView(message: message("m5")).fixedSize())
        XCTAssertMeasure(deleted.width, 227.98, tolerance: 1)
        XCTAssertMeasure(deleted.height, 37)
        let viewOnce = layoutSize(MessageBubbleView(message: message("m6")).fixedSize())
        XCTAssertMeasure(viewOnce.width, 290)
        XCTAssertMeasure(viewOnce.height, 95.38)
    }

    func testStatusLines() {
        XCTAssertMeasure(layoutSize(MessageStatusSentence(status: .sent), width: 374).height, 18.59)
        XCTAssertMeasure(layoutSize(MessageStatusLegendRow(status: .sent), width: 354).height, 18.89)
    }

    // MARK: Chrome

    func testChatHeaderAndComposer() {
        let header = ChatHeader(
            username: "deniz", palette: .a, isVerified: true, onBack: {},
            status: { Text(verbatim: "Az önce aktifti") },
            trailing: { GhostChip(ghost: .off) {} })
        XCTAssertMeasure(layoutSize(header, width: 402).height, 108.63)
        struct ComposerHost: View {
            @State var text = ""
            var body: some View { Composer(text: $text, onSend: {}) }
        }
        XCTAssertMeasure(layoutSize(ComposerHost(), width: 402).height, 94)
    }

    func testFieldsAndControls() {
        struct Fields: View {
            @State var name = "selin.n"
            @State var password = "correct horse"
            @FocusState var focus: PasswordFieldFocus?
            var body: some View {
                VStack(spacing: 0) {
                    UsernameField(text: $name, status: .positive)
                    UsernameSearchField(text: $name)
                    PasswordField(text: $password, focus: $focus, field: .password)
                }
            }
        }
        XCTAssertMeasure(layoutSize(Fields(), width: 362).height, 60 + 56 + 56)
        struct Chips: View {
            @State var period = GhostPeriod.oneDay
            var body: some View { SegmentedChips(options: GhostPeriod.allCases, selection: $period) { Text($0.chipKey) } }
        }
        XCTAssertMeasure(layoutSize(Chips(), width: 366).height, 40)
        struct Switch: View {
            @State var on = true
            var body: some View { Toggle(isOn: $on) { EmptyView() }.toggleStyle(.u2) }
        }
        XCTAssertMeasure(layoutSize(Switch(), width: 51).height, 31)
    }

    func testLineAndSafetyNumber() {
        XCTAssertEqual(layoutSize(LineMark()), CGSize(width: 52, height: 14))
        XCTAssertEqual(layoutSize(LineMark(metrics: .connection, isFlowing: true)), CGSize(width: 60, height: 9))
        let line = ConnectionLine(ownUsername: "selin.n", ownLabel: Text(verbatim: "sen"), peer: DesignFixtures.deniz)
        XCTAssertMeasure(layoutSize(line, width: 402).height, 73.05)
        XCTAssertEqual(layoutSize(QRCodeCard(payload: SafetyQRCodec.encode(Data("08223".utf8)), label: Text(verbatim: "QR"))), CGSize(width: 220, height: 220))
    }
}
