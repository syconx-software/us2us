import CoreGraphics
import Foundation
import Us2usKit
import Us2usStorage

/// What the user picked as their profile picture (PROTOCOL.md §8.9).
public enum AvatarSelection: Sendable, Equatable {
    /// The initial letter of the username (the default).
    case initials
    /// A built-in preset of the app's catalog, by its stable id (never 0).
    /// Only ids the catalog lists may be sent (§8.9).
    case preset(UInt32)
    /// A photo as picked or taken (any format ImageIO reads), and the
    /// square the user cropped in the photo as displayed (pixels,
    /// orientation applied, origin top left; `nil`: the centred square).
    /// It is scaled, stripped of all metadata and re-encoded on the phone
    /// (``AvatarPhotoProcessor``); the original is not kept.
    case photo(Data, crop: CGRect? = nil)
}

/// Profile pictures (§8.9, §8.10): the own choice with its "Show in search"
/// setting, and the pictures of other accounts. Additive; each needs an
/// unlocked vault.
extension AppRuntime {
    /// Sets the own picture and "Aramada göster" / "Show in search", in one
    /// step: contacts get a new picture end to end in a `ProfileUpdate`
    /// (not for the setting alone), and the avatar record for search is
    /// published, replaced or deleted on the relay. A relay that cannot be
    /// reached now never fails this: ``ownAvatar``'s `searchRecord` says
    /// whether the record is in line, and the write is made again at the
    /// next connection.
    ///
    /// - Throws: ``RuntimeError/locked``, ``AvatarPhotoProcessingError`` for
    ///   a photo that cannot be read or encoded, `MessengerError` for preset
    ///   id 0.
    public func setAvatar(_ selection: AvatarSelection, showInSearch: Bool) async throws {
        guard lockState == .unlocked, let session else { throw RuntimeError.locked }
        let picture: ProfilePicture
        switch selection {
        case .initials:
            picture = .initials
        case .preset(let id):
            picture = .preset(id)
        case .photo(let source, let crop):
            let photo = try await Task.detached(priority: .userInitiated) {
                try AvatarPhotoProcessor.prepare(source, crop: crop)
            }.value
            picture = .photo(photo)
        }
        let own = try await session.engine.setOwnAvatar(picture, showInSearch: showInSearch)
        if isCurrent(session) { ownAvatar = own }
    }

    /// Changes the own picture and keeps "Show in search" as the vault has
    /// it (never as a screen last saw it, which may not be loaded yet): a
    /// user who turned it off never publishes the new picture for search.
    ///
    /// - Throws: as ``setAvatar(_:showInSearch:)``.
    public func setAvatar(_ selection: AvatarSelection) async throws {
        guard lockState == .unlocked, let session else { throw RuntimeError.locked }
        let current = await session.engine.ownAvatar()
        guard isCurrent(session) else { throw RuntimeError.locked }
        try await setAvatar(selection, showInSearch: current.showInSearch)
    }

    /// Turns "Show in search" on or off and keeps the picture: off deletes
    /// the avatar record from the relay, on publishes it again (for a preset
    /// or a photo). Contacts are not told: they have the picture either way.
    public func setAvatarShownInSearch(_ shown: Bool) async throws {
        guard lockState == .unlocked, let session else { throw RuntimeError.locked }
        let current = await session.engine.ownAvatar()
        let own = try await session.engine.setOwnAvatar(current.picture, showInSearch: shown)
        if isCurrent(session) { ownAvatar = own }
    }

    /// The own picture and setting as stored in the vault (also refreshes
    /// ``ownAvatar``); `nil` while locked.
    public func currentAvatar() async -> OwnProfilePicture? {
        guard lockState == .unlocked, let session else { return nil }
        let own = await session.engine.ownAvatar()
        if isCurrent(session) { ownAvatar = own }
        return own
    }

    /// Makes a search-record write that is still owed now (e.g. a "try
    /// again" next to the setting); also refreshes ``ownAvatar``.
    @discardableResult
    public func retryAvatarPublishing() async -> AvatarRecordStatus? {
        guard lockState == .unlocked, let session else { return nil }
        let status = await session.engine.syncAvatarRecord()
        let own = await session.engine.ownAvatar()
        if isCurrent(session) { ownAvatar = own }
        return status
    }

    /// The picture to show next to a search result (4.2 search, §8.10):
    /// call it after ``lookupAccount(username:)`` found `account` for
    /// exactly this `username` — never while the user is still typing.
    /// Shows the result's initial letter until it returns. Accepted
    /// contacts' pictures come from their `ProfileUpdate` without a request;
    /// anyone else's avatar record is fetched, decrypted with the key of the
    /// username and cached in memory. Never throws: every failure (no
    /// record, no network, locked) is the initial letter.
    public func avatar(forUsername rawUsername: String, accountId: AccountId) async -> AvatarLookup {
        guard let username = try? NormalizedUsername(rawUsername), lockState == .unlocked, let session else {
            return .initials
        }
        let lookup = await session.engine.avatar(forUsername: username, account: accountId)
        return isCurrent(session) ? lookup : .initials
    }

    /// The picture of a conversation's contact (§8.9): accepted contacts
    /// from their last `ProfileUpdate` (also in ``ContactInfo/avatar``, no
    /// request); our own outgoing request shows the record of the account we
    /// wrote to. An incoming message request is the initial letter, with
    /// nothing fetched or decoded; ``AvatarLookup/availableOnRequest`` says
    /// the app may offer "Show picture", which calls
    /// ``requestAvatar(of:userConfirmed:)``. Safe for list rows. Never throws.
    public func avatar(of peer: AccountId) async -> AvatarLookup {
        guard lockState == .unlocked, let session else { return .initials }
        let lookup = await session.engine.avatar(of: peer)
        return isCurrent(session) ? lookup : .initials
    }

    /// An incoming message request's picture after the user tapped "Show
    /// picture" on the opened request (`userConfirmed`; never for a list row
    /// or anything shown automatically): its avatar record is fetched and
    /// opened under the username the sender verified (§6.3, §8.9). Without
    /// `userConfirmed`, and for any other contact, this is ``avatar(of:)``.
    /// Never throws.
    public func requestAvatar(of peer: AccountId, userConfirmed: Bool) async -> AvatarLookup {
        guard lockState == .unlocked, let session else { return .initials }
        let lookup = await session.engine.requestAvatar(of: peer, userConfirmed: userConfirmed)
        return isCurrent(session) ? lookup : .initials
    }
}
