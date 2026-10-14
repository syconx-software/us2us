#if DEBUG
    import CoreImage
    import SwiftUI
    import Us2usKit

    /// DEBUG screen harness for 03 · Kilit: opens every lock screen and state
    /// directly, in its exact design state, for screenshots and design
    /// comparisons.
    ///
    ///     xcrun simctl launch <sim> com.syconx.us2us -U2Route lock:<state> \
    ///         -U2Theme dark|light -U2Language tr|en -U2FrozenClock YES
    ///
    /// | `lock:` | design | state |
    /// |---|---|---|
    /// | `faceid` | 3.1 | Face ID scanning (rings frozen 2 s into the loop with `-U2FrozenClock`) |
    /// | `touchid` | 3.1 | the Touch ID variant |
    /// | `code` | 3.2 | idle: empty dots, no sentence |
    /// | `typing` | 3.2 | four digits entered |
    /// | `wrong` | 3.2 | the reference: first wrong code — red dots + “2 yanlış daha …” |
    /// | `lastchance` | 3.2 | second wrong code — “1 yanlış daha …” |
    /// | `throttled` | 3.2 | third wrong code — dimmed dots, keypad at 45 %, “… 01:00 sonra tekrar dene.” |
    /// | `unavailable` | 3.2 | not a wrong code: the vault could not be read right now (proposed) |
    /// | `unusable` | 3.2 | not a wrong code: this phone can no longer open the vault (proposed) |
    /// | `wiped` | 3.2 › | the optional wipe fired: “Veriler silindi.” with restore / start over (proposed) |
    /// | `forgot` | 3.3 | the floating sheet |
    /// | `erase` | 3.3 | the second confirmation |
    /// | `restore` | 3.3 › 7.3 | the restore hand-off inside the lock |
    /// | `cover` | 3.4 | the privacy cover, full size, over the blurred demo screen |
    /// | `switcher` | 3.4 | the whole app-switcher mock (system context + the cover at 0.7015) |
    ///
    /// The harness never evaluates biometrics and holds the drawn state (no
    /// automatic transitions). Release builds compile none of this.
    struct LockHarness: Equatable {
        enum Screen: String, CaseIterable {
            case faceID = "faceid"
            case touchID = "touchid"
            case code
            case typing
            case wrong
            case lastChance = "lastchance"
            case throttled
            case unavailable
            case unusable
            case wiped
            case forgot
            case erase
            case restore
            case cover
            case switcher
        }

        let screen: Screen

        static func fromLaunchArguments() -> LockHarness? {
            LaunchRoute.current?.state(for: .lock).flatMap(Screen.init(rawValue:)).map(LockHarness.init(screen:))
        }

        /// 3.4 draws over the lock.
        var replacesLock: Bool { screen == .cover || screen == .switcher }

        /// `-U2LockDemo forgot`: on 3.2, “Kodu unuttum” opens the 3.3 sheet
        /// after 1.5 s and “Tekrar dene” closes it 2.5 s later — for recording
        /// the sheet's rise and dismissal without touching the device.
        @MainActor
        static func runDemo(_ flow: LockFlowModel) async {
            switch UserDefaults.standard.string(forKey: "U2LockDemo") {
            case "forgot":
                try? await Task.sleep(for: .milliseconds(1500))
                flow.openForgot()
                try? await Task.sleep(for: .milliseconds(2500))
                flow.closeForgot()
            case "unlock":
                // `-U2LockDemo unlock`: types the demo code (frame-probe runs
                // of the dissolve into 4.1).
                try? await Task.sleep(for: .milliseconds(2500))
                for digit in DesignFixtures.demoSecretCode {
                    flow.code.type(Int(String(digit)) ?? 0)
                    try? await Task.sleep(for: .milliseconds(180))
                }
            default:
                return
            }
        }

        /// The design draws the Face ID variant (keypad key included).
        var biometry: Biometry? { screen == .touchID ? .touchID : .faceID }

        /// The fixtures' wrong code (any six digits but the demo code).
        private static let wrongCode = "111112"

        @MainActor
        func apply(to flow: LockFlowModel, messenger: any MessengerService) {
            let code = flow.code
            code.isFrozenForHarness = true
            switch screen {
            case .faceID, .touchID:
                flow.setForHarness(stage: .biometric)
            case .code, .cover, .switcher:
                flow.setForHarness(stage: .code)
            case .typing:
                flow.setForHarness(stage: .code)
                code.setEnteredForHarness("2468")
            case .wrong, .lastChance, .throttled:
                flow.setForHarness(stage: .code)
                code.holdsErrorState = screen != .throttled
                let earlier = screen == .wrong ? 0 : (screen == .lastChance ? 1 : 2)
                Task { @MainActor in
                    for _ in 0..<earlier { _ = await messenger.unlock(code: Self.wrongCode) }
                    for digit in Self.wrongCode { code.type(Int(String(digit)) ?? 0) }
                }
            case .unavailable, .unusable:
                flow.setForHarness(stage: .code)
                code.setMessageForHarness(screen == .unusable ? .unusable : .unavailable)
            case .wiped:
                flow.setForHarness(stage: .wiped)
            case .forgot:
                flow.setForHarness(stage: .code, forgot: .options)
            case .erase:
                flow.setForHarness(stage: .code, forgot: .confirmErase)
            case .restore:
                flow.setForHarness(stage: .restore(nil))
            }
        }
    }

    /// 3.4 in the harness: the cover over the blurred demo screen, alone or
    /// inside the app-switcher mock.
    struct LockHarnessScreen: View {
        let state: LockHarness
        @State private var snapshot: UIImage?
        @Environment(\.self) private var environment

        var body: some View {
            Group {
                if state.screen == .switcher {
                    LockSwitcherMock(snapshot: snapshot)
                } else {
                    PrivacyCoverView(snapshot: snapshot)
                }
            }
            .onAppear(perform: makeSnapshot)
        }

        /// The demo screen rendered and blurred exactly like `PrivacySnapshot`
        /// (half scale, Gaussian σ 13 pt).
        @MainActor
        private func makeSnapshot() {
            let renderer = ImageRenderer(
                content: LockCoverSkeleton()
                    .frame(width: U2Size.designScreen.width, height: U2Size.designScreen.height)
                    .environment(\.self, environment))
            renderer.scale = 0.5
            guard let image = renderer.uiImage else { return }
            snapshot = PrivacySnapshot.blurred(image, sigma: 13 * 0.5)
        }
    }

    /// The abstract chat list the design blurs under the cover (r.3.0, drawn
    /// at full size: thumbnail values × 1 / 0.7015).
    private struct LockCoverSkeleton: View {
        private let k: CGFloat = 1 / 0.7015

        var body: some View {
            ZStack(alignment: .topLeading) {
                Color.u2.bg
                VStack(alignment: .leading, spacing: 22 * k) {
                    RoundedRectangle(cornerRadius: 6 * k).fill(Color.u2.text.opacity(0.45))
                        .frame(width: 127.91 * k, height: 22 * k)
                    ForEach(0..<7, id: \.self) { _ in
                        HStack(spacing: 12 * k) {
                            Circle().fill(Color.u2.accent800).frame(width: 38 * k, height: 38 * k)
                            VStack(alignment: .leading, spacing: 7 * k) {
                                RoundedRectangle(cornerRadius: 5 * k).fill(Color.u2.text.opacity(0.40))
                                    .frame(width: 90.16 * k, height: 9 * k)
                                RoundedRectangle(cornerRadius: 4 * k).fill(Color.u2.text.opacity(0.22))
                                    .frame(width: 152.88 * k, height: 8 * k)
                            }
                        }
                    }
                }
                .padding(.top, 64 * k)
                .padding(.leading, 18 * k)
            }
        }
    }

    /// The whole 3.4 mock: wallpaper, the neighbouring card, the app label and
    /// the app's card (282 × 613 at 60,150, r36) showing the cover at 0.7015.
    /// Everything but the card content is system UI, drawn here only to
    /// compare with `reference/3.4-uygulama-degistirici.png`.
    private struct LockSwitcherMock: View {
        let snapshot: UIImage?
        private let scale: CGFloat = 0.7015

        var body: some View {
            // Physical-screen coordinates: top-leading, safe areas ignored.
            GeometryReader { _ in
                mock
            }
            .background(Color(red: 11 / 255, green: 12 / 255, blue: 20 / 255))
            .ignoresSafeArea()
        }

        private var mock: some View {
            ZStack(alignment: .topLeading) {
                Color(red: 11 / 255, green: 12 / 255, blue: 20 / 255)
                wallpaper
                    .blur(radius: 12)
                    .offset(x: -40, y: -40)
                neighbour
                    .offset(x: -196, y: 176)
                appLabel
                    .offset(x: 60, y: 104)
                card
                    .offset(x: 60, y: 150)
            }
            .frame(width: U2Size.designScreen.width, height: U2Size.designScreen.height, alignment: .topLeading)
            .clipped()
        }

        /// CSS: two elliptical radial gradients (50 % × 35 % at 25 % / 20 %,
        /// 55 % × 40 % at 85 % / 85 %, both fading out at 70 %) over a vertical
        /// gradient, on a 482 × 954 box blurred 12.
        private var wallpaper: some View {
            ZStack(alignment: .topLeading) {
                LinearGradient(
                    colors: [Color(red: 29 / 255, green: 31 / 255, blue: 51 / 255), Color(red: 11 / 255, green: 12 / 255, blue: 20 / 255)],
                    startPoint: .top, endPoint: .bottom)
                blob(Color(red: 53 / 255, green: 59 / 255, blue: 128 / 255).opacity(0.7), radii: CGSize(width: 0.55 * 482, height: 0.40 * 954), center: CGPoint(x: 0.85 * 482, y: 0.85 * 954))
                blob(Color(red: 145 / 255, green: 132 / 255, blue: 217 / 255).opacity(0.45), radii: CGSize(width: 0.50 * 482, height: 0.35 * 954), center: CGPoint(x: 0.25 * 482, y: 0.20 * 954))
            }
            .frame(width: 482, height: 954)
        }

        private func blob(_ color: Color, radii: CGSize, center: CGPoint) -> some View {
            EllipticalGradient(colors: [color, color.opacity(0)], center: .center, startRadiusFraction: 0, endRadiusFraction: 0.35)
                .frame(width: radii.width * 2, height: radii.height * 2)
                .position(center)
        }

        private var neighbour: some View {
            VStack(alignment: .leading, spacing: 12) {
                RoundedRectangle(cornerRadius: 4).fill(Color(red: 201 / 255, green: 203 / 255, blue: 214 / 255))
                    .frame(width: 124.8, height: 16)
                RoundedRectangle(cornerRadius: 4).fill(Color(red: 214 / 255, green: 216 / 255, blue: 225 / 255))
                    .frame(width: 187.19, height: 9)
                RoundedRectangle(cornerRadius: 4).fill(Color(red: 214 / 255, green: 216 / 255, blue: 225 / 255))
                    .frame(width: 166.39, height: 9)
            }
            .padding(EdgeInsets(top: 64, leading: 22, bottom: 64, trailing: 22))
            .frame(width: 252, height: 548, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: 32, style: .continuous).fill(Color(red: 232 / 255, green: 233 / 255, blue: 239 / 255)))
            .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
            .shadow(color: .black.opacity(0.5), radius: 30, x: 0, y: 30)
        }

        private var appLabel: some View {
            HStack(spacing: 10) {
                ZStack {
                    // radial-gradient(90% 90% at 30% 15%, #423a6a, #161826 72%)
                    ZStack(alignment: .topLeading) {
                        Color(red: 22 / 255, green: 24 / 255, blue: 38 / 255)
                        EllipticalGradient(
                            colors: [Color(red: 66 / 255, green: 58 / 255, blue: 106 / 255), Color(red: 22 / 255, green: 24 / 255, blue: 38 / 255)],
                            center: .center, startRadiusFraction: 0, endRadiusFraction: 0.36
                        )
                        .frame(width: 54, height: 54)
                        .position(x: 9, y: 4.5)
                    }
                    .frame(width: 30, height: 30)
                    .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .strokeBorder(Color(red: 233 / 255, green: 233 / 255, blue: 237 / 255).opacity(0.2), lineWidth: 0.5)
                    LineMark(
                        metrics: LineMark.Metrics(ringDiameter: 6, ringStroke: 1.5, width: 20, barHeight: 1.5),
                        color: Color(red: 181 / 255, green: 171 / 255, blue: 252 / 255))
                }
                .frame(width: 30, height: 30)
                Text(verbatim: "us2us")
                    .u2Font(.switcherAppName)
                    .foregroundStyle(Color(red: 233 / 255, green: 233 / 255, blue: 237 / 255))
            }
        }

        private var card: some View {
            // CSS corners are circular; the inset hairline (text·10%) lies
            // under the cover's bg·45% tint.
            let shape = RoundedRectangle(cornerRadius: 36)
            return PrivacyCoverView(snapshot: snapshot)
                .frame(width: U2Size.designScreen.width, height: U2Size.designScreen.height)
                .scaleEffect(scale, anchor: .topLeading)
                .frame(width: 282, height: 613, alignment: .topLeading)
                .clipShape(shape)
                .overlay(
                    shape.strokeBorder(Color.u2.text.opacity(0.10), lineWidth: 1)
                        .overlay(shape.strokeBorder(Color.u2.coverTint, lineWidth: 1)))
                .u2BoxShadow(.switcherCard, in: shape)
        }
    }
#endif
