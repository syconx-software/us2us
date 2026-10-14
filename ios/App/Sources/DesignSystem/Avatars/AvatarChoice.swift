import Foundation

/// What the user's avatar shows (proposed — the design has only the initial).
///
/// * `.initials` — C12's letter on the palette (the default, and what
///   every account had before);
/// * `.preset(id)` — one of `BrandAvatarCatalog`'s cups, by its stable id;
/// * `.photo` — a photo the user picked or took; its bytes are kept with the
///   profile, not here.
///
/// It is stored and sent as one short string (`storageValue`: “initials”,
/// “preset:7”, “photo”), so a choice made on a newer build survives on an
/// older one: a preset id this build does not know is kept as it is and drawn
/// as the initial (`drawn`).
enum AvatarChoice: Hashable, Sendable {
    case initials
    case preset(BrandAvatarPreset.ID)
    case photo

    static let `default` = AvatarChoice.initials

    private static let presetPrefix = "preset:"

    /// The stored and sent form.
    var storageValue: String {
        switch self {
        case .initials: "initials"
        case .preset(let id): Self.presetPrefix + String(id)
        case .photo: "photo"
        }
    }

    /// A stored or received value; anything empty, unknown or malformed is
    /// the initial (never a crash, never a guess).
    init(storageValue: String) {
        let value = storageValue.trimmingCharacters(in: .whitespacesAndNewlines)
        switch value {
        case "initials": self = .initials
        case "photo": self = .photo
        default:
            guard value.hasPrefix(Self.presetPrefix),
                let id = BrandAvatarPreset.ID(value.dropFirst(Self.presetPrefix.count)), id != 0
            else {
                self = .initials
                return
            }
            self = .preset(id)
        }
    }

    /// The preset to draw — nil for the initial, a photo, or an id this build
    /// does not know.
    var preset: BrandAvatarPreset? {
        guard case .preset(let id) = self else { return nil }
        return BrandAvatarCatalog.preset(id: id)
    }

    /// What this build draws: an unknown preset falls back to the initial.
    /// A `.photo` stays `.photo`; the caller draws the initial while it has
    /// no image for it.
    var drawn: AvatarChoice {
        if case .preset = self, preset == nil { return .initials }
        return self
    }
}

extension AvatarChoice: Codable {
    init(from decoder: any Decoder) throws {
        self.init(storageValue: try decoder.singleValueContainer().decode(String.self))
    }

    func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(storageValue)
    }
}
