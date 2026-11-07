import SwiftUI
import UIKit

/// A chat background ready to draw: a photo is decoded before it reaches the
/// screen, off the main actor.
enum ChatBackgroundLook: Equatable, Sendable {
    case standard
    case preset(ChatBackgroundPreset)
    case photo(UIImage)

    /// A stored photo that no longer decodes reads as the app's own background.
    static func make(_ background: ChatBackground) async -> ChatBackgroundLook {
        switch background {
        case .standard: return .standard
        case .preset(let preset): return .preset(preset)
        case .photo(let jpeg):
            let image = await Task.detached(priority: .userInitiated) {
                UIImage(data: jpeg)?.preparingForDisplay()
            }.value
            return image.map(ChatBackgroundLook.photo) ?? .standard
        }
    }
}

extension ChatBackgroundPreset {
    /// Top and bottom of the preset's gradient, soft enough in both themes
    /// for the bubbles and the floating labels to keep their contrast.
    var stops: (top: U2ThemedColor, bottom: U2ThemedColor) {
        switch self {
        case .mist: (U2ThemedColor(dark: 0x27303a, light: 0xe4e9ef), U2ThemedColor(dark: 0x171c22, light: 0xcfd8e2))
        case .sage: (U2ThemedColor(dark: 0x24372b, light: 0xdcebdc), U2ThemedColor(dark: 0x121d16, light: 0xc2d8c2))
        case .sand: (U2ThemedColor(dark: 0x3a3123, light: 0xf1e6d3), U2ThemedColor(dark: 0x1e1913, light: 0xe2d0b2))
        case .rose: (U2ThemedColor(dark: 0x40262d, light: 0xf6dfe3), U2ThemedColor(dark: 0x211519, light: 0xecc4cc))
        case .dusk: (U2ThemedColor(dark: 0x32294a, light: 0xe5dcf4), U2ThemedColor(dark: 0x1a1428, light: 0xcfc0e9))
        case .ocean: (U2ThemedColor(dark: 0x18394a, light: 0xd4e8f2), U2ThemedColor(dark: 0x0e1d25, light: 0xb5d6e8))
        case .moss: (U2ThemedColor(dark: 0x323a22, light: 0xe2e9cf), U2ThemedColor(dark: 0x1a1f11, light: 0xcbd7a9))
        case .peach: (U2ThemedColor(dark: 0x452e1e, light: 0xf9e2d1), U2ThemedColor(dark: 0x22170f, light: 0xf2cbae))
        }
    }

    /// VoiceOver's name for the swatch (`Contact.xcstrings`).
    var nameKey: String.LocalizationValue { String.LocalizationValue("background.preset." + rawValue) }
}

/// Fills its place with the look. A photo is cropped to fill and veiled with
/// the theme's background, so what floats directly on it stays readable.
struct ChatBackgroundLayer: View {
    let look: ChatBackgroundLook

    /// How much of the theme's background covers a photo.
    private static let photoVeil = 0.35

    var body: some View {
        Group {
            switch look {
            case .standard:
                Color.u2.bg
            case .preset(let preset):
                LinearGradient(
                    colors: [Color(preset.stops.top), Color(preset.stops.bottom)],
                    startPoint: .top, endPoint: .bottom)
            case .photo(let image):
                GeometryReader { proxy in
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                }
                .overlay(Color.u2.bg.opacity(Self.photoVeil))
            }
        }
        .accessibilityHidden(true)
    }
}

private struct U2ChatHasBackgroundKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// The chat draws its own background: nothing may paint the theme's over
    /// it (the composer's fade).
    var u2ChatHasBackground: Bool {
        get { self[U2ChatHasBackgroundKey.self] }
        set { self[U2ChatHasBackgroundKey.self] = newValue }
    }
}
