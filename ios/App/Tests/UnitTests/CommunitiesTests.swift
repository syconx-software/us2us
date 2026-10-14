import Foundation
import SwiftUI
import Testing

@testable import us2us

// Communities: every text in both languages, problems explained in words
// (never an error's description), members named without raw ids, and the
// fixtures without communities.

private let tr = Locale(identifier: "tr_TR")
private let en = Locale(identifier: "en_TR")

@Suite("Communities · texts")
@MainActor
struct CommunitiesTextTests {
    /// The catalog is compiled into the app; these are spot-checked by name.
    nonisolated static let knownKeys = [
        "card.title", "communities.title", "disclosure.body", "disclosure.private", "action.leave", "action.withdraw",
        "action.forget", "action.delete", "confirm.leave.title", "confirm.withdraw.title", "confirm.forget.title",
        "problem.offline", "problem.verification", "problem.full", "problem.rateLimited", "search.hint",
        "conversation.ended", "message.partial", "message.failed", "community.unnamed",
    ]

    @Test(arguments: knownKeys)
    func everyTextIsInBothLanguages(_ key: String) {
        let turkish = StringTable.communities.string(String.LocalizationValue(key), locale: tr)
        let english = StringTable.communities.string(String.LocalizationValue(key), locale: en)
        #expect(turkish != key && english != key)
        #expect(turkish != english)
    }

    @Test("the disclosure says the relay sees who sends to whom, and that searches reach it")
    func disclosureNamesTheMetadata() {
        let turkish = StringTable.communities.string("disclosure.body", locale: tr)
        let english = StringTable.communities.string("disclosure.body", locale: en)
        #expect(turkish.contains("kimin kime") && turkish.contains("sunucuya ulaşır"))
        #expect(english.contains("who sends to whom") && english.contains("reach the relay"))
        #expect(StringTable.communities.string("disclosure.private", locale: en).contains("who sends to whom"))
    }

    @Test(arguments: [
        CommunityProblem.offline, .unsupported, .verification, .memberKey(name: "deniz", shortID: "1a2b3c4d"),
        .memberKey(name: nil, shortID: "1a2b3c4d"), .stale, .full, .notFound, .rateLimited, .invalidName, .invalidInvite,
        .ownerCannotLeave, .failed,
    ])
    func everyProblemIsExplained(_ problem: CommunityProblem) {
        let turkish = problem.message(locale: tr)
        let english = problem.message(locale: en)
        #expect(!turkish.hasPrefix("problem.") && !english.hasPrefix("problem."))
        #expect(turkish != english)
        if case .memberKey(let name, let shortID) = problem {
            #expect(english.contains(name ?? "Member · \(shortID)"))
        }
    }

    @Test func membersAreNamedByUsernameElseByAShortLabel() {
        #expect(CommunityNames.member(username: "deniz", shortID: "1a2b3c4d", locale: tr) == "deniz")
        #expect(CommunityNames.member(username: nil, shortID: "1a2b3c4d", locale: tr) == "Üye · 1a2b3c4d")
        #expect(CommunityNames.member(username: nil, shortID: "1a2b3c4d", locale: en) == "Member · 1a2b3c4d")
        #expect(CommunityNames.title("", locale: tr) == "Gizli topluluk")
        #expect(CommunityNames.title("Aile 👨‍👩‍👧", locale: en) == "Aile 👨‍👩‍👧")
    }

    @Test func idsAndKeysHaveOneFormat() {
        #expect(CommunityIdentifiers.shortID("1C5C6039-41d2-4d55-9a0e-2f7c1b3d9e10") == "1c5c6039")
        #expect(CommunityIdentifiers.shortID("1c5c-6039") == "1c5c6039")
        #expect(CommunityIdentifiers.keyFingerprint(Data([0x05, 0xAB, 0xCD, 0xEF, 0x01])) == "05ab cdef 01")
        #expect(CommunityIdentifiers.keyFingerprint(Data()) == "")
        let key = Data((0..<33).map { UInt8($0 * 7 & 0xFF) })
        let groups = CommunityIdentifiers.keyFingerprint(key).split(separator: " ")
        #expect(groups.count == 17 && groups.dropLast().allSatisfy { $0.count == 4 } && groups.last?.count == 2)
        #expect(CommunityIdentifiers.keyFingerprint(key) == CommunityIdentifiers.keyFingerprint(key).lowercased())
    }

    @Test("a screen keeps the last name it showed while it goes away")
    func titleOutlivesTheRow() {
        var known = CommunityNames.remembered("Aile Grubu", known: "")
        #expect(CommunityNames.title("Aile Grubu", known: known, locale: tr) == "Aile Grubu")
        // Left or deleted: the row (or its private name) is gone first.
        known = CommunityNames.remembered(nil, known: known)
        #expect(CommunityNames.title(nil, known: known, locale: tr) == "Aile Grubu")
        #expect(CommunityNames.title("", known: known, locale: en) == "Aile Grubu")
        // Never named: the placeholder, as before.
        #expect(CommunityNames.title(nil, known: "", locale: tr) == "Gizli topluluk")
        #expect(CommunityNames.title("Kuzenler", known: "Aile Grubu", locale: tr) == "Kuzenler")
    }

    @Test("every member row has the same height, with or without “Çıkar”", arguments: ["tr", "en"])
    func memberRowsAreOneHeight(_ language: String) {
        let rows: [(CommunityMemberRow, Bool)] = [
            (CommunityMemberRow(id: "a", username: nil, shortID: "1c5c6039", isOwner: true, isSelf: false), false),
            (CommunityMemberRow(id: "b", username: nil, shortID: "9e10aa01", isOwner: false, isSelf: true), false),
            (CommunityMemberRow(id: "c", username: nil, shortID: "1c5c6039", isOwner: true, isSelf: true), false),
            (CommunityMemberRow(id: "d", username: "deniz", shortID: "2f7c1b3d", isOwner: false, isSelf: false), false),
            (CommunityMemberRow(id: "e", username: nil, shortID: "41d24d55", isOwner: false, isSelf: false), true),
            (CommunityMemberRow(id: "f", username: "deniz", shortID: "2f7c1b3d", isOwner: false, isSelf: false), true),
        ]
        let heights = rows.map { member, canRemove in
            layoutSize(CommunityMemberListRow(member: member, canRemove: canRemove) {}, width: 366, locale: language).height
        }
        // C13 with a subtitle: 62 (62.67 on the 3× pixel grid).
        #expect(heights.allSatisfy { abs($0 - heights[0]) < 0.01 }, "\(heights)")
        #expect(abs(heights[0] - 62) < 1, "\(heights)")
    }

    @Test func confirmationsAreDestructiveAndWorded() {
        let id = "6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10"
        for confirmation in [CommunityConfirmation.leave(id), .withdraw(id), .forget(id), .delete(id), .removeMember(id, name: "deniz", in: id)] {
            #expect(!confirmation.title(locale: tr).hasPrefix("confirm."))
        }
        #expect(CommunityConfirmation.removeMember(id, name: "deniz", in: id).title(locale: en) == "Remove deniz?")
    }

    @Test func theFixturesHaveNoCommunities() {
        let service = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        #expect(service.communities == nil)
    }
}
