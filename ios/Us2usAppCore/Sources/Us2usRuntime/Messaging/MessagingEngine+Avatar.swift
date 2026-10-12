import Foundation
import Us2usKit
import Us2usStorage

/// A picture to draw for one account, and where it came from (PROTOCOL.md
/// §8.9, "Which picture is shown").
public struct AvatarLookup: Sendable, Equatable {
    public enum Source: Sendable, Equatable {
        /// The user's own choice.
        case own
        /// An accepted contact's last `ProfileUpdate`.
        case contact
        /// The account's avatar record for search (§8.10).
        case search
        /// Nothing to show but the initial letter.
        case initialLetter
    }

    public let picture: ProfilePicture
    public let source: Source
    /// An incoming message request whose sender's username is verified:
    /// nothing was fetched or decoded (§8.9) — a stranger's photo is content
    /// nobody asked for, and decoding it hands attacker-chosen bytes to the
    /// image decoders. Draw the initial letter (or a neutral placeholder)
    /// and offer "Show picture"; on that tap call
    /// ``MessagingEngine/requestAvatar(of:userConfirmed:)``.
    public let availableOnRequest: Bool

    public init(picture: ProfilePicture, source: Source, availableOnRequest: Bool = false) {
        self.picture = picture
        self.source = source
        self.availableOnRequest = availableOnRequest
    }

    public static let initials = AvatarLookup(picture: .initials, source: .initialLetter)
}

extension MessagingEngine {
    // MARK: Own picture (§8.9, §8.10)

    /// The own picture, "Show in search" and the state of the search record.
    public func ownAvatar() async -> OwnProfilePicture {
        await backend.ownAvatar()
    }

    /// Sets the own picture and "Show in search" (saved in the vault with the
    /// account). A new picture goes to every accepted contact that has our
    /// profile, in a complete `ProfileUpdate` through the outbox (§8.7);
    /// turning the setting on or off alone sends nothing to contacts. Then
    /// the avatar record on the relay is brought in line (§8.10): published,
    /// replaced or deleted. Its outcome is in the result's `searchRecord`; a
    /// write that could not be made is made again at the next connection.
    public func setOwnAvatar(_ picture: ProfilePicture, showInSearch: Bool) async throws -> OwnProfilePicture {
        let before = await backend.ownAvatar()
        try await backend.setOwnAvatar(picture, showInSearch: showInSearch)
        if picture != before.picture { try announceProfileChange() }
        _ = await backend.syncAvatarRecord()
        return await backend.ownAvatar()
    }

    /// Makes an avatar-record write still owed now.
    @discardableResult
    public func syncAvatarRecord() async -> AvatarRecordStatus {
        await backend.syncAvatarRecord()
    }

    // MARK: Other accounts' pictures (§8.9)

    /// The picture of a search result (§8.10 "Search"): `account` is what
    /// the directory returned for exactly `username`. Our own account shows
    /// our own choice; an accepted contact whose `ProfileUpdate` arrived
    /// shows the picture from it, with no request to the relay (step 3); a
    /// blocked one the initial letter; anyone else the avatar record, fetched
    /// now. Every failure is the initial letter, never an error.
    public func avatar(forUsername username: NormalizedUsername, account: AccountId) async -> AvatarLookup {
        if account == me { return AvatarLookup(picture: await backend.ownAvatar().picture, source: .own) }
        if let contact = try? read({ try $0.contacts.get(account) }) {
            if contact.requestState == .blocked { return .initials }
            if contact.requestState == .accepted, contact.profile.updatedAt > 0 {
                return AvatarLookup(picture: contact.profile.avatar, source: .contact)
            }
        }
        let picture = await backend.searchAvatar(username: username, account: account)
        return AvatarLookup(picture: picture, source: picture == .initials ? .initialLetter : .search)
    }

    /// The picture of a conversation's contact (§8.9): an accepted contact's
    /// last `ProfileUpdate`; the account of our own message request its
    /// avatar record; anyone else the initial letter. An incoming message
    /// request is the initial letter too, with nothing fetched — marked
    /// ``AvatarLookup/availableOnRequest`` when its sender's username is
    /// verified, so the app can offer "Show picture"
    /// (``requestAvatar(of:userConfirmed:)``). Safe for list rows: accepted
    /// contacts and requests never cost a request to the relay.
    public func avatar(of peer: AccountId) async -> AvatarLookup {
        await avatar(of: peer, fetchRequest: false)
    }

    /// The picture of an incoming message request's sender, fetched only
    /// because the user asked for it: `userConfirmed` is an explicit tap such
    /// as "Show picture" on the opened request — never a list row or any
    /// other automatic display (§8.9). The record is opened under the
    /// username the sender verified (§6.3) and shown as is. Without
    /// `userConfirmed`, and for every other contact, this is
    /// ``avatar(of:)``.
    public func requestAvatar(of peer: AccountId, userConfirmed: Bool) async -> AvatarLookup {
        await avatar(of: peer, fetchRequest: userConfirmed)
    }

    private func avatar(of peer: AccountId, fetchRequest: Bool) async -> AvatarLookup {
        if peer == me { return AvatarLookup(picture: await backend.ownAvatar().picture, source: .own) }
        guard let contact = try? read({ try $0.contacts.get(peer) }), !contact.pendingForget else { return .initials }
        switch contact.requestState {
        case .accepted:
            guard contact.profile.updatedAt > 0 else { return .initials }
            return AvatarLookup(picture: contact.profile.avatar, source: .contact)
        case .outgoingRequest, .incomingRequest:
            // Only a name the directory confirmed for this account (our own
            // lookup, or a verified `sender_username`).
            guard let username = contact.profile.username, contact.profile.usernameStatus == .verified else {
                return .initials
            }
            if contact.requestState == .incomingRequest, !fetchRequest {
                return AvatarLookup(picture: .initials, source: .initialLetter, availableOnRequest: true)
            }
            let picture = await backend.searchAvatar(username: username, account: peer)
            guard picture != .initials else { return .initials }
            return AvatarLookup(picture: picture, source: .search)
        case .blocked:
            return .initials
        }
    }
}
