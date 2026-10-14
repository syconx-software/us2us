import SwiftUI

/// C12 — a circle with the username's first letter in Martian Mono 500;
/// or, where the person chose one (proposed, PROTOCOL §8.9), a preset cup
/// (`BrandAvatarCanvas`) or their photo in the same circle. A preset id this
/// build does not know, a photo without its bytes and one that does not
/// decode all draw the letter; while a photo decodes (off the main thread,
/// once per app session) the letter shows and the photo fades in over it.
/// VoiceOver reads the person's name either way.
struct Avatar: View {
    /// The design's avatar sizes and their letter styles.
    enum Size: Sendable {
        /// 64 — request preview profile (4.4).
        case profile
        /// 52 — chat-list rows, 4.2 result.
        case listRow
        /// 50 — settings profile card.
        case settings
        /// 48 — verification line (6.1, 6.3).
        case verify
        /// 44 — request cards (4.3).
        case request
        /// 40 — own avatar on the chat list.
        case chatsSelf
        /// 38 — chat header.
        case header

        var diameter: CGFloat {
            switch self {
            case .profile: 64
            case .listRow: 52
            case .settings: 50
            case .verify: 48
            case .request: 44
            case .chatsSelf: 40
            case .header: 38
            }
        }

        var letterStyle: U2TextStyle {
            switch self {
            case .profile: .avatarInitialXL
            case .listRow: .avatarInitialL
            case .settings: .avatarInitialM50
            case .verify: .avatarInitialM48
            case .request: .avatarInitialS44
            case .chatsSelf, .header: .avatarInitialXS
            }
        }

        /// The online dot and its cut-out ring (list: 15 / 3; header: 11 / 2).
        var onlineDot: (diameter: CGFloat, ring: CGFloat) {
            switch self {
            case .header: (11, 2)
            default: (15, 3)
            }
        }
    }

    let username: String
    let palette: AvatarPalette
    var size: Size = .listRow
    /// Only for accepted contacts who share presence (4.1 note).
    var showsOnlineDot = false
    /// What the circle shows: the initial (C12), a preset or a photo.
    var choice: AvatarChoice = .initials
    /// The photo's bytes when `choice` is `.photo`.
    var photo: AvatarPhotoData? = nil

    var body: some View {
        face
            .frame(width: size.diameter, height: size.diameter)
            .overlay(alignment: .bottomTrailing) {
                if showsOnlineDot {
                    PresenceDot(diameter: size.onlineDot.diameter, ring: size.onlineDot.ring)
                        .transition(.u2Fade)
                }
            }
            .u2Animation(U2Motion.state, value: showsOnlineDot)
            .accessibilityElement()
            .accessibilityLabel(Text(verbatim: username))
            .accessibilityValue(showsOnlineDot ? Text("a11y.online") : Text(verbatim: ""))
    }

    @ViewBuilder private var face: some View {
        switch choice.drawn {
        case .preset:
            if let preset = choice.preset {
                BrandAvatarCanvas(preset: preset)
            } else {
                letter
            }
        case .photo:
            if let photo {
                AvatarPhotoFace(photo: photo, diameter: size.diameter) { letter }
            } else {
                letter
            }
        case .initials:
            letter
        }
    }

    /// C12 as drawn: the palette's circle and the first letter.
    private var letter: some View {
        ZStack {
            Circle().fill(fill)
            if palette == .d {
                Circle().strokeBorder(Color.u2.accent700, lineWidth: 1)
            }
            Text(verbatim: initial)
                .u2Font(size.letterStyle)
                .foregroundStyle(ink)
        }
    }

    private var initial: String {
        // Usernames are ASCII lower-case; the design shows the capital letter.
        String(username.prefix(1)).uppercased(with: Locale(identifier: "en_US_POSIX"))
    }

    private var fill: Color {
        switch palette {
        case .a: Color.u2.accent800
        case .b: Color.u2.accent700
        case .c: Color.u2.neutral800
        case .d: Color.u2.accent900
        }
    }

    private var ink: Color {
        switch palette {
        case .a, .b: Color.u2.accent100
        case .c: Color.u2.neutral100
        case .d: Color.u2.accent300
        }
    }
}

extension Avatar {
    /// The avatar of someone whose picture is known (`Peer.avatar`, the own
    /// `OwnAvatarState.picture`).
    init(
        username: String, palette: AvatarPalette, size: Size = .listRow, showsOnlineDot: Bool = false,
        picture: AvatarPicture
    ) {
        self.init(
            username: username, palette: palette, size: size, showsOnlineDot: showsOnlineDot, choice: picture.choice,
            photo: picture.photo)
    }
}

/// The green presence dot with its `bg` cut-out ring (C12). Inline status
/// lines use a 7 pt dot without a ring.
struct PresenceDot: View {
    var diameter: CGFloat = 15
    var ring: CGFloat = 3

    var body: some View {
        Circle()
            .fill(Color.u2.online)
            .frame(width: diameter, height: diameter)
            .u2OuterRing(Color.u2.bg, width: ring, in: Circle())
            .accessibilityHidden(true)
    }
}

#Preview("Avatars") {
    VStack(spacing: 20) {
        HStack(spacing: 14) {
            Avatar(username: "deniz", palette: .a)
            Avatar(username: "mert_k", palette: .b, showsOnlineDot: true)
            Avatar(username: "ayla_", palette: .c)
            Avatar(username: "kaan", palette: .d)
        }
        HStack(spacing: 14) {
            Avatar(username: "kuzey_dev", palette: .c, size: .profile)
            Avatar(username: "selin_n", palette: .d, size: .settings)
            Avatar(username: "selin_n", palette: .d, size: .verify)
            Avatar(username: "mert_k", palette: .b, size: .header, showsOnlineDot: true)
        }
    }
    .padding(30)
    .background(Color.u2.bg)
}
