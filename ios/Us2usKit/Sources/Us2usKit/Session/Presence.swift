import Foundation

/// The rules of "active now" presence (PROTOCOL.md §8.6). Presence is end to
/// end encrypted, always ephemeral, and reciprocal.
public enum PresencePolicy {
    /// `valid_for_seconds` of an ONLINE announcement.
    public static let validForSeconds: UInt32 = 300
    /// How often ONLINE is refreshed to the contacts currently seen online.
    public static let refreshInterval: TimeInterval = 240
    /// After OFFLINE: "Active just now" below this, then "Active N min ago"…
    static let justNowWindow: TimeInterval = 5 * 60
    /// …until this, then nothing. No long-term "last seen" exists.
    static let recentWindow: TimeInterval = 60 * 60

    /// Only accepted contacts, and only if **both** sides share presence.
    public static func isEligible(state: ConversationRequestState, weShare: Bool, theyShare: Bool) -> Bool {
        state == .accepted && weShare && theyShare
    }

    /// ONLINE, valid for 300 s. `reply` marks the answer to someone's ONLINE.
    public static func online(reply: Bool = false) -> BRPContent {
        var presence = BRPPresenceMessage()
        presence.state = .online
        presence.validForSeconds = validForSeconds
        presence.reply = reply
        return BRPContent(presence: presence)
    }

    public static func offline() -> BRPContent {
        var presence = BRPPresenceMessage()
        presence.state = .offline
        return BRPContent(presence: presence)
    }

    /// An eligible contact's ONLINE is answered once with ONLINE(reply); a
    /// reply is never answered, so announcements cannot ping-pong.
    public static func shouldReply(to presence: BRPPresenceMessage, eligible: Bool) -> Bool {
        eligible && presence.state == .online && !presence.reply
    }
}

/// What to show for a contact (§8.6 "Display").
public enum PresenceStatus: Equatable, Sendable {
    /// Green dot, "Active".
    case active
    /// "Active just now" (less than 5 minutes since going offline).
    case activeJustNow
    /// "Active N min ago", up to 60 minutes.
    case activeMinutesAgo(Int)
    /// Nothing is shown.
    case none
}

/// In-memory view of the contacts' presence, fed by received
/// `PresenceMessage`s. It deliberately keeps nothing older than an hour.
public struct PresenceTracker: Sendable {
    private enum Entry {
        case online(lastSeen: Date, until: Date)
        case offline(since: Date)
    }

    private var entries: [AccountId: Entry] = [:]

    public init() {}

    public mutating func record(_ presence: BRPPresenceMessage, from contact: AccountId, at time: Date = Date()) {
        switch presence.state {
        case .online:
            let validFor = TimeInterval(min(presence.validForSeconds, PresencePolicy.validForSeconds))
            entries[contact] = .online(lastSeen: time, until: time.addingTimeInterval(validFor))
        case .offline:
            entries[contact] = .offline(since: time)
        case .unspecified, .UNRECOGNIZED:
            break
        }
    }

    /// Forget a contact (presence sharing turned off, blocked, wiped).
    public mutating func forget(_ contact: AccountId) {
        entries[contact] = nil
    }

    public func status(of contact: AccountId, at now: Date = Date()) -> PresenceStatus {
        let offlineSince: Date
        switch entries[contact] {
        case .online(let lastSeen, let until)?:
            if now < until { return .active }
            // Expired without OFFLINE (crash, lost network): last proof of activity.
            offlineSince = lastSeen
        case .offline(let since)?:
            offlineSince = since
        case nil:
            return .none
        }
        let elapsed = now.timeIntervalSince(offlineSince)
        if elapsed < PresencePolicy.justNowWindow { return .activeJustNow }
        if elapsed < PresencePolicy.recentWindow { return .activeMinutesAgo(Int(elapsed / 60)) }
        return .none
    }

    /// Contacts currently seen online: the targets of the 240 s refresh and of OFFLINE.
    public func onlineContacts(at now: Date = Date()) -> [AccountId] {
        entries.compactMap { contact, entry in
            if case .online(_, let until) = entry, now < until { return contact }
            return nil
        }
    }

    /// Drops entries that can no longer be displayed.
    public mutating func prune(at now: Date = Date()) {
        entries = entries.filter { status(of: $0.key, at: now) != .none }
    }
}

extension Localizer {
    /// "Aktif" / "Active", "Az önce aktifti" / "Active just now", "N dk önce
    /// aktifti" / "Active N min ago", or `nil` when nothing is shown.
    public func text(for status: PresenceStatus) -> String? {
        switch status {
        case .active: return string(.presenceActive)
        case .activeJustNow: return string(.presenceActiveJustNow)
        case .activeMinutesAgo(let minutes): return string(.presenceActiveMinutesAgo, minutes)
        case .none: return nil
        }
    }
}
