import Foundation
import Us2usKit
import Us2usRuntime

/// Profile pictures in the fixtures: the own choice in memory, photos made
/// by the real pipeline (crop, ≤ 256 px, no metadata), search results and
/// “Profil resmini göster” after the simulated latency (DesignFixtures+Avatars.swift).
extension DesignFixturesService {
    func setAvatar(_ edit: AvatarEdit) async throws(AvatarEditError) {
        let picture: AvatarPicture
        switch edit {
        case .initials:
            picture = .initials
        case .preset(let id):
            guard BrandAvatarCatalog.preset(id: id) != nil else { throw .failed }
            picture = .preset(id)
        case .photo(let data, let crop):
            let prepared = await Task.detached(priority: .userInitiated) {
                try? AvatarPhotoProcessor.prepare(data, crop: crop)
            }.value
            guard let prepared else { throw .unusablePhoto }
            picture = .photo(AvatarPhotoData(data: prepared.data, contentType: prepared.format.contentType))
        }
        guard account != nil else { throw .failed }
        ownAvatar.picture = picture
        ownAvatar.searchCopy = .inSync
    }

    @discardableResult
    func setAvatarShownInSearch(_ shown: Bool) async -> Bool {
        ownAvatar.showInSearch = shown
        ownAvatar.searchCopy = .inSync
        return shown
    }

    func searchAvatar(username: String) async -> AvatarPicture {
        let name = UsernameRules.normalize(username)
        if name == account?.username { return ownAvatar.picture }
        // An accepted contact's picture is already here (no request).
        if let chat = chats.first(where: { $0.peer.username == name }), chat.peer.isAcceptedContact {
            return chat.peer.avatar
        }
        try? await Task.sleep(for: latency)
        return DesignFixtures.searchAvatar(for: name)
    }

    func revealAvatar(of request: MessageRequest) async -> AvatarPicture {
        guard request.offersAvatar else { return .initials }
        try? await Task.sleep(for: latency)
        return DesignFixtures.requestAvatar(for: request.sender)
    }
}
