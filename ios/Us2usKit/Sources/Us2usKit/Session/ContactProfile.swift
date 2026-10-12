import Foundation

/// What a contact told us about itself in its last `ProfileUpdate` (§8.7),
/// and whether its username claim was checked against the directory (§3.3).
/// The app stores one per contact.
public struct ContactProfile: Sendable, Equatable {
    /// What the directory says about ``username``.
    public enum UsernameStatus: String, Sendable, Equatable, Codable {
        /// Only claimed (a `ProfileUpdate` or a backup). Show it as unverified
        /// until ``Messenger/usernameOwner(_:)`` was asked.
        case unchecked
        /// The directory maps it to this contact: our own lookup, a verified
        /// `sender_username` (§6.3) or a lazy check.
        case verified
        /// The directory maps it to nobody or to another account. Never show
        /// it as this contact's name.
        case mismatch
    }

    public var username: NormalizedUsername?
    public var usernameStatus: UsernameStatus
    public var displayName: String?
    /// The contact shares "active now" status. False until its first
    /// `ProfileUpdate` arrives (§8.6).
    public var presenceSharing: Bool
    /// The contact's last `ProfileUpdate.profile_avatar` (§8.9), checked when
    /// it arrived; the initial letter without one or for a malformed one.
    public var avatar: ProfilePicture
    /// `ProfileUpdate.updated_at` of the profile shown (ms since the epoch).
    public var updatedAt: UInt64

    public init(
        username: NormalizedUsername? = nil, usernameStatus: UsernameStatus = .unchecked, displayName: String? = nil,
        presenceSharing: Bool = false, avatar: ProfilePicture = .initials, updatedAt: UInt64 = 0
    ) {
        self.username = username
        self.usernameStatus = usernameStatus
        self.displayName = displayName
        self.presenceSharing = presenceSharing
        self.avatar = avatar
        self.updatedAt = updatedAt
    }

    /// The profile after receiving `update`. Every `ProfileUpdate` is complete
    /// and replaces the stored profile; an empty or malformed field means
    /// "none" (§8.7). Its username is only a claim: a new one is unchecked,
    /// while repeating the current one keeps the earlier result. The picture
    /// is `profile_avatar` (field 6); the deprecated `avatar` (field 2) is
    /// ignored, and a malformed picture is the initial letter while the other
    /// fields still apply.
    public func applying(_ update: BRPProfileUpdate) -> ContactProfile {
        let claimed = (try? NormalizedUsername(update.username)).flatMap { $0.value == update.username ? $0 : nil }
        let displayName =
            update.displayName.isEmpty || update.displayName.utf8.count > ContentLimits.maxDisplayNameBytes
            ? nil : update.displayName
        return ContactProfile(
            username: claimed, usernameStatus: claimed == username ? usernameStatus : .unchecked,
            displayName: displayName, presenceSharing: update.presenceSharing,
            avatar: ProfilePicture(update.hasProfileAvatar ? update.profileAvatar : nil), updatedAt: update.updatedAt)
    }

    /// Whether the username claim still needs a directory check.
    public var needsUsernameCheck: Bool { username != nil && usernameStatus == .unchecked }

    /// Records a directory answer for `username`: ``UsernameStatus/verified``
    /// if `owner` is `contact`, else ``UsernameStatus/mismatch``. An answer
    /// for a username the contact no longer claims changes nothing.
    public func recordingUsernameCheck(
        _ username: NormalizedUsername, owner: AccountId?, contact: AccountId
    ) -> ContactProfile {
        guard username == self.username else { return self }
        var profile = self
        profile.usernameStatus = owner == contact ? .verified : .mismatch
        return profile
    }

    /// A username already shown to point at this contact: our own directory
    /// lookup found it, or it came as a verified `sender_username` (§6.3).
    public func confirming(_ username: NormalizedUsername) -> ContactProfile {
        var profile = self
        profile.username = username
        profile.usernameStatus = .verified
        return profile
    }
}
