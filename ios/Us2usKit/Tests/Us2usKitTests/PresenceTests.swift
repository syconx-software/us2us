import Foundation
import Testing

@testable import Us2usKit

@Suite("Presence: \"active now\" (§8.6)")
struct PresenceTests {
    let contact = AccountId(uuid: UUID())
    let start = Date(timeIntervalSince1970: 1_800_000_000)

    @Test("only accepted contacts, and only when both sides share")
    func eligibility() {
        for state in ConversationRequestState.allCases {
            for weShare in [false, true] {
                for theyShare in [false, true] {
                    #expect(
                        PresencePolicy.isEligible(state: state, weShare: weShare, theyShare: theyShare)
                            == (state == .accepted && weShare && theyShare))
                }
            }
        }
    }

    @Test("ONLINE is valid for 300 s; a reply is never answered")
    func messages() throws {
        let online = PresencePolicy.online().presence
        #expect(online.state == .online && online.validForSeconds == 300 && !online.reply)
        #expect(PresencePolicy.online(reply: true).presence.reply)
        #expect(PresencePolicy.offline().presence.state == .offline)
        try PresencePolicy.online().validateForSending()

        #expect(PresencePolicy.shouldReply(to: online, eligible: true))
        #expect(!PresencePolicy.shouldReply(to: online, eligible: false))
        #expect(!PresencePolicy.shouldReply(to: PresencePolicy.online(reply: true).presence, eligible: true))
        #expect(!PresencePolicy.shouldReply(to: PresencePolicy.offline().presence, eligible: true))
        #expect(PresencePolicy.refreshInterval < TimeInterval(PresencePolicy.validForSeconds))
    }

    @Test("display after OFFLINE: just now below 5 min, then N min ago up to an hour, then nothing")
    func offlineDisplay() {
        var tracker = PresenceTracker()
        #expect(tracker.status(of: contact, at: start) == .none)
        tracker.record(PresencePolicy.online().presence, from: contact, at: start)
        #expect(tracker.status(of: contact, at: start.addingTimeInterval(299)) == .active)
        #expect(tracker.onlineContacts(at: start.addingTimeInterval(10)) == [contact])

        tracker.record(PresencePolicy.offline().presence, from: contact, at: start.addingTimeInterval(100))
        let offlineAt = start.addingTimeInterval(100)
        #expect(tracker.onlineContacts(at: offlineAt).isEmpty)
        #expect(tracker.status(of: contact, at: offlineAt.addingTimeInterval(299)) == .activeJustNow)
        #expect(tracker.status(of: contact, at: offlineAt.addingTimeInterval(300)) == .activeMinutesAgo(5))
        #expect(tracker.status(of: contact, at: offlineAt.addingTimeInterval(59 * 60 + 59)) == .activeMinutesAgo(59))
        #expect(tracker.status(of: contact, at: offlineAt.addingTimeInterval(3600)) == .none)
    }

    @Test("an expired ONLINE counts from the last announcement; validity is capped at 300 s")
    func expiry() {
        var tracker = PresenceTracker()
        var greedy = PresencePolicy.online().presence
        greedy.validForSeconds = 86_400
        tracker.record(greedy, from: contact, at: start)
        #expect(tracker.status(of: contact, at: start.addingTimeInterval(301)) == .activeMinutesAgo(5))
        tracker.prune(at: start.addingTimeInterval(4000))
        tracker.record(PresencePolicy.online().presence, from: contact, at: start.addingTimeInterval(4000))
        tracker.forget(contact)
        #expect(tracker.status(of: contact, at: start.addingTimeInterval(4001)) == .none)
    }

    @Test("localized labels")
    func labels() {
        let tr = Localizer(languageCode: "tr")
        let en = Localizer(languageCode: "en")
        #expect(tr.text(for: .active) == "Aktif")
        #expect(en.text(for: .active) == "Active")
        #expect(tr.text(for: .activeJustNow) == "Az önce aktifti")
        #expect(en.text(for: .activeJustNow) == "Active just now")
        #expect(tr.text(for: .activeMinutesAgo(7)) == "7 dk önce aktifti")
        #expect(en.text(for: .activeMinutesAgo(7)) == "Active 7 min ago")
        #expect(tr.text(for: .none) == nil)
        #expect(tr.string(.presenceSetting) == "Aktif olduğumu göster")
    }
}
