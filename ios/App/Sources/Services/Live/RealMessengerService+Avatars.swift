import CoreGraphics
import Foundation
import Us2usKit
import Us2usRuntime

/// Profile pictures (PROTOCOL.md §8.9, §8.10) over the runtime: the own
/// choice and “Aramada göster”, a 4.2 result's search copy, and a request's
/// picture after “Profil resmini göster”. Copies found by search stay in memory
/// only and go when the app locks (`clearCaches`).
extension RealMessengerService {
    var ownAvatar: OwnAvatarState {
        LiveMapping.ownAvatar(runtime.ownAvatar.map(LiveOwnAvatar.init))
    }

    func setAvatar(_ edit: AvatarEdit) async throws(AvatarEditError) {
        let selection = try Self.selection(for: edit)
        do {
            // “Aramada göster” stays as stored (never assumed on).
            try await runtime.setAvatar(selection)
        } catch is AvatarPhotoProcessingError {
            throw .unusablePhoto
        } catch {
            throw .failed
        }
    }

    /// What the runtime is asked to set: only presets this build's catalog
    /// lists are ever sent (§8.9).
    nonisolated static func selection(for edit: AvatarEdit) throws(AvatarEditError) -> AvatarSelection {
        switch edit {
        case .initials:
            return .initials
        case .preset(let id):
            guard BrandAvatarCatalog.preset(id: id) != nil else { throw .failed }
            return .preset(id)
        case .photo(let data, let crop):
            guard !data.isEmpty, crop.width > 0, crop.height > 0 else { throw .unusablePhoto }
            return .photo(data, crop: crop)
        }
    }

    @discardableResult
    func setAvatarShownInSearch(_ shown: Bool) async -> Bool {
        do {
            try await runtime.setAvatarShownInSearch(shown)
        } catch {
            // Locked meanwhile: nothing changed.
        }
        return runtime.ownAvatar?.showInSearch ?? shown
    }

    func searchAvatar(username: String) async -> AvatarPicture {
        guard let normalized = try? NormalizedUsername(username),
            let account = avatarAccounts[normalized.value]
        else { return .initials }
        let lookup = await runtime.avatar(forUsername: normalized.value, accountId: account)
        let avatar = LiveAvatar(lookup.picture)
        // Kept for the user's own request to this account (§8.9), this session only.
        if lookup.source == .search || lookup.source == .initialLetter {
            searchedAvatars[account.string] = avatar
        }
        return LiveMapping.avatar(avatar)
    }

    func revealAvatar(of request: MessageRequest) async -> AvatarPicture {
        guard request.offersAvatar, let peer = AccountId(string: request.id) else { return .initials }
        // Once per request and session: the relay is not asked again.
        if let revealed = revealedAvatars[request.id] { return LiveMapping.avatar(revealed) }
        let lookup = await runtime.requestAvatar(of: peer, userConfirmed: true)
        let avatar = LiveAvatar(lookup.picture)
        revealedAvatars[request.id] = avatar
        return LiveMapping.avatar(avatar)
    }

    func refreshOwnAvatar() async {
        _ = await runtime.currentAvatar()
    }

    /// An exact lookup found `username` (4.2): its picture may be fetched next.
    func rememberLookup(_ username: String, account: AccountId) {
        guard let normalized = try? NormalizedUsername(username) else { return }
        avatarAccounts[normalized.value] = account
    }

    /// The user's own message request opened (5.1) without a search copy
    /// from this session: its record is looked up once per session (§8.9 —
    /// the account of the user's own request shows its search copy; none
    /// found is remembered as the initial). Accepted contacts, incoming
    /// requests and blocked contacts cost nothing here.
    func loadRequestAvatar(of peer: AccountId) async {
        guard let summary = runtime.chats.first(where: { $0.id == peer }),
            summary.contact.requestState == .outgoingRequest, searchedAvatars[peer.string] == nil
        else { return }
        let lookup = await runtime.avatar(of: peer)
        searchedAvatars[peer.string] = lookup.source == .search ? LiveAvatar(lookup.picture) : .initials
    }
}
