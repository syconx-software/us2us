import SwiftUI

/// 01 · Karşılama — the three welcome pages (1.1 → 1.3).
///
/// The pages cross-fade with a 16 pt drift (0.5 s, the design curve) — by
/// button or by a horizontal swipe that follows the finger and settles
/// without bounce. The chrome is shared: the wordmark stays, the pager label
/// and the CTA label cross-fade, the active page dot morphs (0.36 s), and the
/// bottom block glides between its 1.1 position (with “Yedekten geri
/// yükle”) and its 1.2/1.3 position. Reduce Motion: fades only.
struct WelcomePagerView: View {
    let pager: WelcomePagerModel
    let onCreateAccount: () -> Void
    let onRestore: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.u2IsLayerVisible) private var isLayerVisible
    @Environment(\.u2LayerMotion) private var enclosingMotion
    @Environment(\.locale) private var locale

    var body: some View {
        GeometryReader { proxy in
            let width = max(proxy.size.width, 1)
            U2Screen {
                ZStack(alignment: .top) {
                    page(0) { WelcomeKarsilamaPage() }
                    page(1) { WelcomeNoNotificationsPage() }
                    page(2) { WelcomeSilenceErasesPage() }
                    chrome
                }
                .contentShape(Rectangle())
                .gesture(swipe(width: width))
                // VoiceOver: a three-finger swipe pages like the finger does.
                .accessibilityScrollAction { edge in
                    switch edge {
                    case .trailing: pager.go(to: pager.page + 1)
                    case .leading: pager.go(to: pager.page - 1)
                    default: break
                    }
                }
            }
        }
        .ignoresSafeArea()
        .onAppear { pager.motion.parent = enclosingMotion }
    }

    // MARK: Pages

    private func page<Content: View>(_ index: Int, @ViewBuilder content: () -> Content) -> some View {
        let distance = Double(index) - pager.position
        let isCurrent = index == pager.page
        let isVisible = abs(distance) < 1
        return content()
            .environment(\.u2IsLayerVisible, isLayerVisible && isVisible)
            .environment(\.u2LayerMotion, pager.motion)
            .modifier(WelcomePageLayer(distance: distance, reduceMotion: reduceMotion))
            .allowsHitTesting(isCurrent)
            .accessibilityHidden(!isCurrent)
    }

    // MARK: Chrome

    private var chrome: some View {
        VStack(spacing: 0) {
            OnboardingHeader(page: pager.page + 1, pageCount: WelcomePagerModel.pageCount)
                .contentTransition(.opacity)
            Spacer(minLength: 0)
            if reduceMotion {
                // Nothing moves: the 1.1 block and the 1.2/1.3 block cross-fade in place.
                ZStack(alignment: .bottom) {
                    bottomBlock(showsRestore: true, isActive: pager.page == 0)
                        .opacity(pager.page == 0 ? 1 : 0)
                        .allowsHitTesting(pager.page == 0)
                        .accessibilityHidden(pager.page != 0)
                    bottomBlock(showsRestore: false, isActive: pager.page != 0)
                        .opacity(pager.page == 0 ? 0 : 1)
                        .allowsHitTesting(pager.page != 0)
                        .accessibilityHidden(pager.page == 0)
                }
            } else {
                // The active dot morphs and the block glides between the 1.1
                // and the 1.2/1.3 positions while the restore button fades.
                bottomBlock(showsRestore: pager.page == 0)
            }
        }
    }

    /// `isActive`: under Reduce Motion two blocks cross-fade; only the one
    /// shown carries the CTA's identifier (UI tests also see hidden views).
    private func bottomBlock(showsRestore: Bool, isActive: Bool = true) -> some View {
        VStack(spacing: 10) {
            PageDots(count: WelcomePagerModel.pageCount, current: pager.page)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.bottom, 16)
            Button(action: primaryAction) {
                ZStack {
                    ForEach(0..<WelcomePagerModel.pageCount, id: \.self) { index in
                        StringTable.onboarding.text(ctaKey(index), locale: locale)
                            .opacity(index == pager.page ? 1 : 0)
                            .accessibilityHidden(index != pager.page)
                    }
                }
            }
            .buttonStyle(.u2Primary)
            .accessibilityIdentifier(isActive ? ctaIdentifier : ctaIdentifier + ".hidden")
            if showsRestore {
                Button(action: onRestore) { LocalizedText("onboarding.welcome.restore", table: .onboarding) }
                    .buttonStyle(.u2Text)
                    .accessibilityIdentifier("welcome.restore")
                    .transition(.u2Fade)
            }
        }
        .padding(.top, 26)
        .padding(.horizontal, U2Spacing.screenGutterL)
        .u2Bottom(U2Spacing.ctaBottomOnboarding)
    }

    private func ctaKey(_ index: Int) -> String.LocalizationValue {
        switch index {
        case 0: "onboarding.welcome.start"
        case 1: "onboarding.notifications.cta"
        default: "onboarding.ghost.cta"
        }
    }

    /// Kept from the foundation (UI tests).
    private var ctaIdentifier: String {
        switch pager.page {
        case 0: "welcome.start"
        case 1: "welcome.page2.cta"
        default: "welcome.page3.cta"
        }
    }

    private func primaryAction() {
        if pager.page < WelcomePagerModel.pageCount - 1 {
            pager.advance()
        } else {
            onCreateAccount()
        }
    }

    // MARK: Swipe

    /// A mostly horizontal drag pages; the content follows the finger
    /// (cross-fading) and settles on the critically damped spring.
    private func swipe(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 14, coordinateSpace: .local)
            .onChanged { value in
                guard abs(value.translation.width) > abs(value.translation.height) || pager.dragProgress != 0 else {
                    return
                }
                var progress = -Double(value.translation.width / (width * 0.75))
                // Rubber band past the first and last page.
                if (pager.page == 0 && progress < 0) || (pager.page == WelcomePagerModel.pageCount - 1 && progress > 0)
                {
                    progress *= 0.18
                }
                pager.dragProgress = min(max(progress, -1), 1)
            }
            .onEnded { value in
                guard pager.dragProgress != 0 else { return }
                pager.endDrag(
                    predictedProgress: -Double(value.predictedEndTranslation.width / (width * 0.75)),
                    velocity: -Double(value.velocity.width / (width * 0.75)))
            }
    }
}

// MARK: - 1.1 · Karşılama

/// 1.1 — dot grid, the 3D scene (402 × 330 at y 84), “İki uç. Bir hat.” and
/// the promise; the CTA block is the pager's chrome (bottom 206 pt).
struct WelcomeKarsilamaPage: View {
    var body: some View {
        ZStack(alignment: .top) {
            DotGridBackdrop(mask: .welcome, color: Color.u2.dotGrid)
                .ignoresSafeArea()
            WelcomeSceneHook()
                .allowsHitTesting(false)
                .u2Top(84)
            VStack(alignment: .leading, spacing: 16) {
                Spacer(minLength: 0)
                U2DisplayText(StringTable.onboarding.resource("onboarding.welcome.title"), style: .displayHero)
                LocalizedText("onboarding.welcome.body", table: .onboarding)
                    .u2Font(.bodyL)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .padding(.horizontal, U2Spacing.screenGutterL)
            .u2Bottom(206)
        }
    }
}

// MARK: - 1.2 · Bildirim yok. Bilerek.

/// 1.2 — the empty lock-screen illustration (live time and date, the dashed
/// ghost notification), the title, the paragraph and the four delivery
/// states with the chat's own icons.
struct WelcomeNoNotificationsPage: View {
    var body: some View {
        // Between the pager's header (bottom at y 97) and its CTA block (152
        // from the bottom), so very large text scrolls in that band only.
        OnboardingFitOrScroll {
            VStack(spacing: 0) {
                LockScreenIllustration()
                    .padding(.top, 34)
                GhostNotificationCard()
                    .padding(.top, 26)
                    .padding(.horizontal, 20)
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 14) {
                    U2DisplayText(StringTable.onboarding.resource("onboarding.notifications.title"), style: .displayTitleL)
                    LocalizedText("onboarding.notifications.body", table: .onboarding)
                        .u2Font(.body)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    VStack(alignment: .leading, spacing: 9) {
                        ForEach([MessageStatus.sent, .delivered, .read, .failed], id: \.self) { status in
                            MessageStatusLegendRow(status: status)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, U2Spacing.screenGutterL)
            }
        }
        .u2Top(97)
        .u2Bottom(152)
    }
}

/// “09:41” (`displayClock`, Doto 800 76, `text·86%`) over “Perşembe, 8 Ekim”
/// (`label15`, `text·68%`), gap 6 — the device's own time and date (this is
/// “your lock screen”, so it follows the wall clock, not the demo data),
/// updated each minute. With the design clock frozen it shows the
/// reference's 09:41 on 8 October.
private struct LockScreenIllustration: View {
    @Environment(\.locale) private var locale
    @Environment(\.u2Clock) private var clock

    var body: some View {
        if clock.isFrozen {
            content(Self.referenceTime)
        } else {
            TimelineView(.everyMinute) { context in
                content(context.date)
            }
        }
    }

    private func content(_ date: Date) -> some View {
        VStack(spacing: 6) {
            Text(verbatim: Self.format(date, "HH:mm", locale: locale))
                .u2Font(.displayClock)
                .foregroundStyle(Color.u2.textClock)
            Text(verbatim: Self.format(date, "EEEE, d MMMM", locale: locale))
                .u2Font(.label15)
                .foregroundStyle(Color.u2.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    /// 09:41 on the design's day (the time drawn in 1.2 and the status bar).
    private static var referenceTime: Date {
        Calendar.current.date(bySettingHour: 9, minute: 41, second: 0, of: DesignFixtures.referenceDate)
            ?? DesignFixtures.referenceDate
    }

    private static func format(_ date: Date, _ pattern: String, locale: Locale) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.dateFormat = pattern
        return formatter.string(from: date)
    }
}

/// The ghost notification: 362 × 68, r22, dashed `text·30%` (1 pt as drawn), pad 14
/// 16, gap 12 — a dashed 38 × 38 r10 square, two dotted skeleton lines (46 %
/// and 82 % of the column, gap 9) and “bildirim yok” (`monoTag`).
private struct GhostNotificationCard: View {
    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            DashedBorder(shape: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .frame(width: 38, height: 38)
            GeometryReader { proxy in
                VStack(alignment: .leading, spacing: 9) {
                    DottedRule(color: Color.u2.dashedDots, pattern: .skeleton)
                        .frame(width: proxy.size.width * 0.46)
                    DottedRule(color: Color.u2.dashedDots, pattern: .skeleton)
                        .frame(width: proxy.size.width * 0.82)
                }
                .frame(maxHeight: .infinity, alignment: .center)
            }
            .frame(height: 21)
            LocalizedText("onboarding.notifications.card", table: .onboarding)
                .u2Font(.monoTag)
                .foregroundStyle(Color.u2.textSecondary)
                .lineLimit(1)
                .fixedSize()
        }
        // CSS padding sits inside the border, laid out 1 pt wide: content starts 17 in.
        .padding(.horizontal, 16 + 1)
        .frame(height: 68)
        .background(DashedBorder(shape: RoundedRectangle(cornerRadius: 22, style: .continuous)))
        .accessibilityElement(children: .combine)
    }
}

// MARK: - 1.3 · Sessizlik siler

/// 1.3 — “GHOST MOD · 24 SAAT”, the live demo countdown from 23:41:07, the
/// 24-dot hour meter, the title, the paragraph and the three features.
struct WelcomeSilenceErasesPage: View {
    @Environment(\.locale) private var locale

    var body: some View {
        OnboardingFitOrScroll {
            VStack(spacing: 0) {
                GhostDemoCountdown()
                    .padding(.top, 64)
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 14) {
                    U2DisplayText(StringTable.onboarding.resource("onboarding.ghost.title"), style: .displayTitleL)
                    LocalizedText("onboarding.ghost.body", table: .onboarding)
                        .u2Font(.body)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    VStack(alignment: .leading, spacing: 10) {
                        OnboardingFeatureRow(
                            text: StringTable.onboarding.text("onboarding.ghost.feature.disappearing", locale: locale)
                        ) {
                            PhosphorIcon(.timer, size: 18)
                        }
                        OnboardingFeatureRow(
                            text: StringTable.onboarding.text("onboarding.ghost.feature.viewOnce", locale: locale)
                        ) {
                            ViewOnceDashedBadge()
                        }
                        OnboardingFeatureRow(
                            text: StringTable.onboarding.text("onboarding.ghost.feature.deleteForEveryone", locale: locale)
                        ) {
                            PhosphorIcon(.trash, size: 18)
                        }
                    }
                    .padding(.top, 4)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, U2Spacing.screenGutterL)
            }
        }
        .u2Top(97)
        .u2Bottom(152)
    }
}

/// The 1.3 hero: kicker, a demo Ghost countdown that starts at 23:41:07 and
/// loops, and the hour meter (one dot per hour of 24, lit for the whole
/// hours still left; a dot fades out over 0.8 s when an hour passes). It
/// keeps ticking under Reduce Motion — it is information.
private struct GhostDemoCountdown: View {
    /// 23:41:07 — the design's demo value.
    static let start: TimeInterval = 23 * 3_600 + 41 * 60 + 7
    static let period: TimeInterval = 24 * 3_600

    @Environment(\.locale) private var locale
    @Environment(\.u2Clock) private var clock
    @Environment(\.u2IsLayerVisible) private var isVisible

    var body: some View {
        if isVisible {
            U2TimeView(interval: 1) { now in hero(at: now) }
        } else {
            // Off screen (another page, or a setup step on top): no ticking.
            hero(at: clock.now)
        }
    }

    private func hero(at now: Date) -> some View {
        let remaining = Self.remaining(at: now, origin: origin)
        return VStack(spacing: 16) {
            MonoKicker(title: StringTable.onboarding.text("onboarding.ghost.kicker", locale: locale), glyph: .ghost)
            Text(verbatim: U2Format.ghostLong(remaining))
                .u2Font(.displayCountdownL)
                .foregroundStyle(Color.u2.accent200)
                .frame(minHeight: 64)
                .accessibilityLabel(Text(verbatim: U2Format.ghostLong(remaining)))
            DottedHourMeter(totalHours: 24, litHours: Int(remaining / 3_600))
                .frame(width: 288, height: 8)
        }
        .frame(maxWidth: .infinity)
    }

    /// The clock's time when the demo first showed, so it starts at
    /// 23:41:07 on any clock (the live app runs on the wall clock); frozen,
    /// the design's moment.
    private static var firstShown: Date?

    private var origin: Date {
        if clock.isFrozen { return DesignFixtures.referenceDate }
        if let first = Self.firstShown { return first }
        let first = clock.now
        Self.firstShown = first
        return first
    }

    static func remaining(at now: Date, origin: Date) -> TimeInterval {
        let elapsed = max(0, now.timeIntervalSince(origin))
        let value = (start - elapsed).truncatingRemainder(dividingBy: period)
        return value <= 0 ? value + period : value
    }
}
