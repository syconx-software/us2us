import Foundation
import Us2usKit

// Profile pictures in the mapping layer (PROTOCOL.md §8.9, §8.10): plain
// records built from the runtime's `ProfilePicture` / `OwnProfilePicture`,
// and the pure decisions of what each screen draws.

// MARK: - Records

/// A picture as the runtime holds it: nothing decoded, nothing decided.
enum LiveAvatar: Equatable, Sendable {
    case initials
    case preset(UInt32)
    /// The photo's bytes (JPEG or HEIC, already through the kit's header checks).
    case photo(data: Data, contentType: String)
}

enum LiveAvatarSearchStatus: Equatable, Sendable {
    case inSync, pending, unavailable
}

/// The user's own picture and “Aramada göster”.
struct LiveOwnAvatar: Equatable, Sendable {
    var avatar: LiveAvatar
    var showInSearch: Bool
    var status: LiveAvatarSearchStatus
}

extension LiveAvatar {
    init(_ picture: ProfilePicture) {
        switch picture {
        case .initials: self = .initials
        case .preset(let id): self = .preset(id)
        case .photo(let photo): self = .photo(data: photo.data, contentType: photo.format.contentType)
        }
    }
}

extension LiveOwnAvatar {
    init(_ own: OwnProfilePicture) {
        let status: LiveAvatarSearchStatus =
            switch own.searchRecord {
            case .inSync: .inSync
            case .pending: .pending
            case .unavailable: .unavailable
            }
        self.init(avatar: LiveAvatar(own.picture), showInSearch: own.showInSearch, status: status)
    }
}

// MARK: - Mapping

extension LiveMapping {
    /// The picture to draw. An unknown preset id is kept (newer builds'
    /// presets); `Avatar` draws it as the initial.
    static func avatar(_ avatar: LiveAvatar) -> AvatarPicture {
        switch avatar {
        case .initials: .initials
        case .preset(let id): id == 0 ? .initials : .preset(id)
        case .photo(let data, let contentType): .photo(AvatarPhotoData(data: data, contentType: contentType))
        }
    }

    /// What a chat's avatar shows (§8.9 › Which picture is shown): an
    /// accepted contact's picture; for the user's own request, the search
    /// copy found this session; an incoming request and a blocked contact
    /// the initial, whatever the record holds — a stranger's picture is
    /// fetched and decoded only when the user asks for it on 4.4.
    static func avatar(of chat: LiveChatRecord) -> AvatarPicture {
        switch chat.requestState {
        case .accepted, .outgoingRequest: avatar(chat.avatar)
        case .incomingRequest, .blocked: .initials
        }
    }

    /// 4.4 may offer “Profil resmini göster”: an incoming request whose sender's
    /// username the directory confirmed (the copy is opened under it).
    static func offersAvatar(of chat: LiveChatRecord) -> Bool {
        guard chat.requestState == .incomingRequest, let username = chat.username, !username.isEmpty else {
            return false
        }
        return chat.usernameVerified
    }

    /// 7.1: the own picture and setting; the initial with the setting on
    /// while locked (nothing decrypted is in memory then).
    static func ownAvatar(_ own: LiveOwnAvatar?) -> OwnAvatarState {
        guard let own else { return OwnAvatarState() }
        let copy: AvatarSearchCopy =
            switch own.status {
            case .inSync: .inSync
            case .pending: .pending
            case .unavailable: .unavailable
            }
        return OwnAvatarState(picture: avatar(own.avatar), showInSearch: own.showInSearch, searchCopy: copy)
    }
}
