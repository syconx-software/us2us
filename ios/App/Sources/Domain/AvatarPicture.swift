import CoreGraphics
import CryptoKit
import Foundation

// Profile pictures (PROTOCOL.md §8.9, §8.10), UI-shaped: what C12's circle
// draws instead of the initial, the user's own choice with “Aramada göster”,
// and a change made on 7.1 › Profil görseli. Independent of Us2usKit's wire
// types; the live service maps the runtime's `ProfilePicture` into these.

/// A profile photo's bytes exactly as they arrived (or were made on this
/// phone): JPEG or HEIC, at most 256 × 256 pixels, held in memory only —
/// never written to a file or an image cache on disk. `Avatar` decodes it
/// (after the decoder's own size check) and keeps the decoded image in
/// memory until the app leaves the foreground.
///
/// Two values are equal when their bytes are: the comparison uses a SHA-256
/// digest taken once, so a chat list that redraws compares 32 bytes, not the
/// photo.
struct AvatarPhotoData: Hashable, Sendable {
    let data: Data
    /// `image/jpeg` or `image/heic` (§8.9).
    let contentType: String
    /// SHA-256 of `data`: identity for equality and for the decoded-image cache.
    let digest: Data

    init(data: Data, contentType: String) {
        self.data = data
        self.contentType = contentType
        digest = Data(SHA256.hash(data: data))
    }

    static func == (lhs: AvatarPhotoData, rhs: AvatarPhotoData) -> Bool {
        lhs.digest == rhs.digest && lhs.contentType == rhs.contentType
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(digest)
    }

    /// The digest as a short string (the decoded-image cache's key).
    var cacheKey: String { digest.map { String(format: "%02x", $0) }.joined() }
}

/// What a person's avatar shows (§8.9): the initial (C12, the default), a
/// built-in preset or a photo. A preset id this build does not know, and a
/// photo without its bytes or that does not decode, are drawn as the
/// initial — never as an error.
struct AvatarPicture: Hashable, Sendable {
    var choice: AvatarChoice
    /// The bytes of a `.photo`; `nil` for the other choices.
    var photo: AvatarPhotoData?

    init(choice: AvatarChoice, photo: AvatarPhotoData? = nil) {
        self.choice = choice
        self.photo = choice == .photo ? photo : nil
    }

    static let initials = AvatarPicture(choice: .initials)

    static func preset(_ id: BrandAvatarPreset.ID) -> AvatarPicture {
        AvatarPicture(choice: .preset(id))
    }

    static func photo(_ photo: AvatarPhotoData) -> AvatarPicture {
        AvatarPicture(choice: .photo, photo: photo)
    }

    /// The initial letter (or something this build draws as it).
    var isInitials: Bool { choice.drawn == .initials || (choice == .photo && photo == nil) }

    var isPhoto: Bool { choice == .photo && photo != nil }
}

/// Where the copy for search (§8.10) stands, as 7.1 says it.
enum AvatarSearchCopy: Hashable, Sendable {
    /// The relay holds what the picture and “Aramada göster” ask for: the
    /// encrypted copy, or none.
    case inSync
    /// A change has not reached the relay yet; it is tried again at the next
    /// connection.
    case pending
    /// This relay keeps no pictures for search yet; contacts still get the
    /// picture end to end.
    case unavailable
}

/// The user's own picture and “Aramada göster” (7.1).
struct OwnAvatarState: Hashable, Sendable {
    var picture: AvatarPicture = .initials
    /// “Aramada göster” / “Show in search” — on by default (§8.10). Kept
    /// while the picture is the initial, for the next choice.
    var showInSearch = true
    var searchCopy: AvatarSearchCopy = .inSync

    /// Someone who types the exact username sees the picture: a preset or a
    /// photo, with the setting on.
    var isShownInSearch: Bool { showInSearch && !picture.isInitials }
}

/// A new own picture, chosen on 7.1 › Profil görseli.
enum AvatarEdit: Equatable, Sendable {
    case initials
    /// A preset of `BrandAvatarCatalog` (only ids it lists are ever sent, §8.9).
    case preset(BrandAvatarPreset.ID)
    /// A photo as picked or taken (any format ImageIO reads; in memory
    /// only) and the square the user framed in it, in pixels of the photo as
    /// displayed. It is scaled to at most 256 × 256, stripped of all
    /// metadata and re-encoded on the phone before it goes anywhere; the
    /// original is not kept.
    case photo(Data, crop: CGRect)
}

/// Why a new picture was not set; the previous one stays.
enum AvatarEditError: Error, Equatable, Sendable {
    /// The photo could not be read, or not made small enough.
    case unusablePhoto
    /// The app locked meanwhile, the preset is not one this build may send,
    /// or saving failed.
    case failed
}
