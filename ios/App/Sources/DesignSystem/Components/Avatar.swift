import SwiftUI

/// C12 — a circle with the username's first letter in Martian Mono 500.
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

    var body: some View {
        ZStack {
            Circle().fill(fill)
            if palette == .d {
                Circle().strokeBorder(Color.u2.accent700, lineWidth: 1)
            }
            Text(verbatim: initial)
                .u2Font(size.letterStyle)
                .foregroundStyle(ink)
        }
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
            Avatar(username: "mert.k", palette: .b, showsOnlineDot: true)
            Avatar(username: "ayla_", palette: .c)
            Avatar(username: "kaan", palette: .d)
        }
        HStack(spacing: 14) {
            Avatar(username: "kuzey.dev", palette: .c, size: .profile)
            Avatar(username: "selin.n", palette: .d, size: .settings)
            Avatar(username: "selin.n", palette: .d, size: .verify)
            Avatar(username: "mert.k", palette: .b, size: .header, showsOnlineDot: true)
        }
    }
    .padding(30)
    .background(Color.u2.bg)
}
