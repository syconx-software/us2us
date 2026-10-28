import SwiftUI

/// Spacing used by the design (tokens.json › spacing). The iOS screens use
/// literal point values; the named ones are the recurring relationships.
enum U2Spacing {
    /// Text blocks on onboarding / setup / lock.
    static let screenGutterL: CGFloat = 24
    /// CTA buttons, list titles.
    static let screenGutter: CGFloat = 20
    /// Cards, headers, lists.
    static let screenGutterS: CGFloat = 16
    /// Bubble column side padding.
    static let chatGutter: CGFloat = 14
    /// Composer side padding.
    static let chatComposerGutter: CGFloat = 12
    /// y of 44 pt header buttons on the 402 × 874 frame.
    static let headerTopButtons: CGFloat = 56
    /// Top padding of the translucent chat header.
    static let headerTopChat: CGFloat = 54
    /// y of the onboarding wordmark row.
    static let onboardingHeaderTop: CGFloat = 66
    /// Bottom padding under the primary CTA (setup, verify, backup), from the physical bottom.
    static let ctaBottom: CGFloat = 44
    /// Bottom padding under onboarding CTAs / text buttons.
    static let ctaBottomOnboarding: CGFloat = 40
    static let bubbleGap: CGFloat = 6
    static let rowGap: CGFloat = 13
    static let sectionGap: CGFloat = 18
    static let sectionLabelGap: CGFloat = 7
    static let titleToBodyGap: CGFloat = 12
    static let iconTextGap: CGFloat = 10
    static let listRowInsetLeading: CGFloat = 16
    static let listRowInsetTrailing: CGFloat = 18
    static let listSeparatorInset: CGFloat = 81
    static let groupedSeparatorInset: CGFloat = 57
}

/// Corner radii (tokens.json › radius). All are continuous-corner rounded
/// rectangles except circles and capsules (`pill` = height / 2).
enum U2Radius {
    static let button: CGFloat = 16
    static let buttonS: CGFloat = 12
    static let buttonSheet: CGFloat = 14
    static let field: CGFloat = 16
    static let composerField: CGFloat = 22
    static let card: CGFloat = 18
    static let cardL: CGFloat = 20
    static let group: CGFloat = 18
    static let iconTile: CGFloat = 8
    static let iconTileL: CGFloat = 10
    static let iconTileXL: CGFloat = 12
    static let checkbox: CGFloat = 7
    static let bubble: CGFloat = 20
    static let bubbleTail: CGFloat = 6
    static let bubbleMedia: CGFloat = 15
    static let bubbleMediaS: CGFloat = 14
    static let bubbleQuote: CGFloat = 14
    static let quoteThumb: CGFloat = 8
    static let chip: CGFloat = 12
    static let pillTag: CGFloat = 11
    static let systemPill: CGFloat = 14
    static let countdownChip: CGFloat = 17
    static let reactionBar: CGFloat = 28
    static let sheet: CGFloat = 38
    static let sheetFloating: CGFloat = 42
    static let sheetBar: CGFloat = 30
    static let sticker: CGFloat = 22
    static let qrCard: CGFloat = 26
    static let qrCardScan: CGFloat = 16
    static let scanCorner: CGFloat = 18
    static let menu: CGFloat = 18
    static let pageDot: CGFloat = 3
    static let caret: CGFloat = 1
    static let grabber: CGFloat = 3
}

/// Component sizes (tokens.json › size).
enum U2Size {
    /// The design frame: iPhone 17 Pro, 402 × 874 pt.
    static let designScreen = CGSize(width: 402, height: 874)
    /// Status-bar band the design assumes; coordinates are measured from the
    /// physical top, so a design y maps to `safeTop + (y − 62)`.
    static let designStatusBar: CGFloat = 62
    /// Home-indicator band the design assumes.
    static let designHomeIndicator: CGFloat = 34

    static let buttonPrimary: CGFloat = 54
    static let buttonInCard: CGFloat = 48
    static let buttonBar: CGFloat = 50
    static let buttonSmall: CGFloat = 40
    static let textButton: CGFloat = 44
    static let textButtonS: CGFloat = 46
    static let iconButton: CGFloat = 44
    /// The smallest area a control answers to (HIG: 44 × 44 pt), whatever it draws.
    static let minimumHitTarget: CGFloat = 44
    static let iconButtonChat: CGFloat = 40
    static let iconButtonSheet: CGFloat = 36
    static let composer: CGFloat = 44
    static let keypadKey: CGFloat = 78
    static let keypadColumnGap: CGFloat = 26
    static let keypadRowGap: CGFloat = 16
    static let codeDot: CGFloat = 15
    static let codeDotGap: CGFloat = 20
    static let toggleWidth: CGFloat = 51
    static let toggleHeight: CGFloat = 31
    static let toggleKnob: CGFloat = 27
    static let togglePadding: CGFloat = 2
    static let checkbox: CGFloat = 24
    static let grabberWidth: CGFloat = 38
    static let grabberHeight: CGFloat = 5
    static let faceIdDisc: CGFloat = 116
    static let lockIcon: CGFloat = 52
    static let pageDotActive = CGSize(width: 22, height: 6)
    static let pageDotInactive = CGSize(width: 6, height: 6)
    static let pageDotGap: CGFloat = 6
    static let fieldUsername: CGFloat = 60
    static let fieldSearch: CGFloat = 56
    static let fieldPassword: CGFloat = 56
    static let iconTileSettings: CGFloat = 30
    static let iconTileRequests: CGFloat = 42
    static let iconTileSheetOption: CGFloat = 40
    static let fileTile = CGSize(width: 46, height: 56)
}

/// Opacity tokens (tokens.json › opacity).
enum U2Opacity {
    /// Disabled controls: the whole control at 45 %.
    static let disabled: Double = 0.45
    /// Lock content behind the 3.3 sheet.
    static let lockBehindSheet: Double = 0.3
    /// Chat behind the 5.3 action menu.
    static let contextBackdropContent: Double = 0.55
    /// Snapshot content under the 3.4 privacy cover.
    static let coverContent: Double = 0.75
}

// MARK: - Safe-area mapping of design coordinates

/// The device's safe-area insets, published by `U2Screen` so components can
/// place themselves at design coordinates (which assume a 62 pt status bar and
/// a 34 pt home indicator) on any iPhone.
struct U2SafeArea: Equatable, Sendable {
    var top: CGFloat
    var bottom: CGFloat

    static let design = U2SafeArea(top: U2Size.designStatusBar, bottom: U2Size.designHomeIndicator)

    /// Maps a design y (from the physical top) to this device.
    func top(_ designY: CGFloat) -> CGFloat { top + (designY - U2Size.designStatusBar) }

    /// Maps a design bottom padding (from the physical bottom) to this device.
    func bottom(_ designPadding: CGFloat) -> CGFloat { bottom + (designPadding - U2Size.designHomeIndicator) }
}

private struct U2SafeAreaKey: EnvironmentKey {
    static let defaultValue = U2SafeArea.design
}

extension EnvironmentValues {
    /// Safe-area insets of the current `U2Screen`.
    var u2SafeArea: U2SafeArea {
        get { self[U2SafeAreaKey.self] }
        set { self[U2SafeAreaKey.self] = newValue }
    }
}
