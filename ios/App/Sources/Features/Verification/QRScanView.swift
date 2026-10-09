import SwiftUI
import UIKit

/// 6.2 · QR tarama — always dark, whatever the theme.
///
/// Camera preview full screen (the design's placeholder on the simulator),
/// top bar (close · “Kodu okut” · torch), the 256 pt viewfinder with four
/// corner brackets and the sweeping scan line, the hint and “Kendi kodumu
/// göster”. A rejected code shows a warning card under the top bar (fade +
/// 8 pt drop) and scanning goes on; an accepted one hands over to the owner
/// (6.3). “Kendi kodumu göster” cross-fades to the user's own code and stops
/// the camera.
///
/// `chromeScheme` is the app's theme outside the cover: the design draws the
/// camera buttons' 0.5 pt hairline with the themed `text·18%` (light in
/// dark, dark — invisible — in light), everything else is fixed.
struct QRScanView: View {
    let scanner: QRScannerModel
    var chromeScheme: ColorScheme = .dark
    @State private var camera = QRCameraModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        U2Screen(background: Color.u2.cameraBg) {
            ZStack(alignment: .top) {
                backdrop
                    .ignoresSafeArea()
                switch scanner.stage {
                case .scanning:
                    ScanningContent(scanner: scanner, camera: camera)
                        .transition(.u2Fade)
                case .myCode:
                    OwnCodeContent(scanner: scanner)
                        .transition(.u2Fade)
                }
                topBar
                if let warning = scanner.warning, scanner.stage == .scanning {
                    WarningCard(warning: warning, onDismiss: scanner.dismissWarning)
                        .padding(EdgeInsets(top: 16, leading: 12, bottom: 0, trailing: 12))
                        .modifier(BelowTopBar())
                        .transition(.u2Drop)
                        .zIndex(2)
                        .id(warning)
                }
            }
        }
        .environment(\.colorScheme, .dark)
        // Light status bar over the camera; released as soon as the cover starts to leave.
        .preferredColorScheme(scanner.isActive ? .dark : nil)
        .onAppear {
            camera.onCode = { [scanner] code in Task { await scanner.handle(code) } }
            #if DEBUG
                if VerificationHarness.forcesCameraDenied { camera.forceStatus(.denied) }
            #endif
        }
        .task(id: cameraShouldRun) {
            if cameraShouldRun {
                #if DEBUG
                    if VerificationHarness.forcesCameraDenied { return }
                #endif
                await camera.start()
                if scanner.torchOn { camera.setTorch(true) }
            } else {
                camera.stop()
            }
        }
        .onChange(of: scanner.torchOn) { _, on in camera.setTorch(on) }
        .onChange(of: camera.torchAvailable) { _, available in
            if !available { scanner.setTorch(false) }
        }
        .onDisappear { camera.stop() }
        .accessibilityAction(.escape, scanner.close)
    }

    /// The camera runs only while scanning, presented and the app is active.
    private var cameraShouldRun: Bool {
        scanner.isActive && scanner.stage == .scanning && scenePhase == .active
    }

    // MARK: Backdrop

    @ViewBuilder private var backdrop: some View {
        ZStack {
            CameraPlaceholderBackdrop()
            if camera.status == .running && scanner.stage == .scanning {
                QRCameraPreview(session: camera.capture.session)
                    .transition(.opacity)
                // Legibility of the white header and hint over a live image
                // (the 5.6 media shades).
                VStack(spacing: 0) {
                    Rectangle().fill(Color.u2.mediaTopShade).frame(height: 190)
                    Spacer(minLength: 0)
                    Rectangle().fill(Color.u2.mediaBottomShade).frame(height: 220)
                }
                .allowsHitTesting(false)
            }
        }
        .u2Animation(U2Motion.state, value: camera.status)
        .accessibilityHidden(true)
    }

    // MARK: Top bar

    private var topBar: some View {
        CameraTopBar(title: titleText, onClose: scanner.close) {
            if scanner.stage == .scanning && camera.status != .denied && camera.status != .unavailable {
                CameraTorchButton(isOn: scanner.torchOn, isEnabled: torchEnabled, action: scanner.toggleTorch)
                    .transition(.u2Fade)
            } else {
                HeaderSpacer()
            }
        }
        .environment(\.colorScheme, chromeScheme)
        .u2Animation(U2Motion.state, value: scanner.stage)
    }

    /// Enabled once the camera reports a torch (and while it is still starting).
    private var torchEnabled: Bool {
        camera.torchAvailable || camera.status == .idle || camera.status == .requesting
    }

    private var titleText: Text {
        scanner.stage == .scanning
            ? Text("scan.title", table: .verification)
            : Text("scan.myCode.title", table: .verification)
    }
}

/// Pads the content to start under the 6.2 top bar (design y 100).
private struct BelowTopBar: ViewModifier {
    @Environment(\.u2SafeArea) private var safeArea
    func body(content: Content) -> some View {
        content.padding(.top, safeArea.top(U2Spacing.headerTopButtons) + U2Size.iconButton)
    }
}

// MARK: - Scanning

/// Viewfinder (or the permission panel), hint and “Kendi kodumu göster”:
/// bottom stack pad 0 24 46, gap 16 (design).
private struct ScanningContent: View {
    let scanner: QRScannerModel
    let camera: QRCameraModel
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(spacing: 0) {
            Color.clear.frame(height: 0).modifier(BelowTopBar())
            ZStack {
                if camera.status == .denied {
                    CameraDeniedPanel()
                        .transition(.u2Fade)
                } else {
                    Viewfinder(showsMock: camera.status == .placeholder, scanner: scanner)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            VStack(spacing: 16) {
                if camera.status != .denied {
                    hint
                        .u2Font(.cameraHint)
                        .foregroundStyle(Color.u2.onMedia.opacity(0.8))
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 300)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button(action: scanner.showOwnCode) {
                    HStack(spacing: 8) {
                        PhosphorIcon(.qrCode, size: 18)
                        Text("scan.showMyCode", table: .verification)
                    }
                }
                .buttonStyle(U2CameraPillButtonStyle())
                .accessibilityIdentifier("scan.showMyCode")
            }
            .padding(.horizontal, U2Spacing.screenGutterL)
            .u2Bottom(46)
        }
    }

    private var hint: Text {
        switch scanner.purpose {
        case .safetyNumber(let peer):
            Text("scan.hint \(L10n.possessive(peer, locale: locale))", table: .verification)
        case .addContact:
            Text("scan.contact.hint", table: .verification)
        }
    }
}

// MARK: - Viewfinder

/// 256 × 256: four 46 pt corner brackets (3 pt `#d2cefd`, outer r18) and the
/// 220 × 2 scan line (r2, glow `0 0 18px 3px #9184d9@70%`), static at 47 %
/// in the references. On the simulator the design's phone-QR mock sits in
/// the frame (inset 42, pad 12, r16, `#f3f5fe@88%`, rotated −5°).
private struct Viewfinder: View {
    let showsMock: Bool
    let scanner: QRScannerModel

    var body: some View {
        ZStack(alignment: .topLeading) {
            if showsMock {
                SimulatorQRMock(scanner: scanner)
                    .frame(width: 172, height: 172)
                    .offset(x: 42, y: 42)
            }
            corner(rotation: 0, at: CGPoint(x: 0, y: 0))
            corner(rotation: 90, at: CGPoint(x: 210, y: 0))
            corner(rotation: 270, at: CGPoint(x: 0, y: 210))
            corner(rotation: 180, at: CGPoint(x: 210, y: 210))
            ScanLine()
                .frame(width: 256, height: 256)
                .allowsHitTesting(false)
        }
        .frame(width: 256, height: 256, alignment: .topLeading)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("scan.a11y.viewfinder", table: .verification))
    }

    private func corner(rotation: Double, at origin: CGPoint) -> some View {
        ScanCornerShape()
            .stroke(Color.u2.scanCorner, style: StrokeStyle(lineWidth: 3, lineCap: .butt))
            .frame(width: 46, height: 46)
            .rotationEffect(.degrees(rotation))
            .offset(x: origin.x, y: origin.y)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// The scan line sweeps softly up and down inside the frame (proposed:
/// 2.4 s per sweep on the design curve, ping-pong). Static at 47 % under
/// Reduce Motion, with the frozen design clock, or while the app is inactive.
private struct ScanLine: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.u2Clock) private var clock

    private static let rest: CGFloat = 0.47
    private static let low: CGFloat = 0.14
    private static let high: CGFloat = 0.80
    private static let sweep: Double = 2.4
    private static let curve = UnitCurve.bezier(
        startControlPoint: UnitPoint(x: 0.45, y: 0), endControlPoint: UnitPoint(x: 0.55, y: 1))

    @State private var start = Date()

    var body: some View {
        if reduceMotion || clock.isFrozen {
            line(at: Self.rest)
        } else {
            TimelineView(.animation(minimumInterval: 1 / 60, paused: scenePhase != .active)) { context in
                line(at: position(context.date.timeIntervalSince(start)))
            }
        }
    }

    /// Starts at 47 % moving down (half-way through a sweep), then ping-pongs.
    private func position(_ elapsed: TimeInterval) -> CGFloat {
        let half = (Self.rest - Self.low) / (Self.high - Self.low)
        let phase = (elapsed / Self.sweep + Double(half)).truncatingRemainder(dividingBy: 2)
        let leg = phase < 1 ? phase : 2 - phase
        let eased = Self.curve.value(at: leg)
        return Self.low + (Self.high - Self.low) * CGFloat(eased)
    }

    private func line(at fraction: CGFloat) -> some View {
        GeometryReader { proxy in
            let shape = RoundedRectangle(cornerRadius: 2, style: .circular)
            shape
                .fill(Color.u2.scanCorner)
                .frame(width: proxy.size.width - 36, height: 2)
                .u2BoxShadow(.glowScanLine, in: shape)
                .offset(x: 18, y: proxy.size.height * fraction)
        }
        .accessibilityHidden(true)
    }
}

/// The phone with the peer's code (simulator only — there is no camera).
/// Tap to read the matching code, touch and hold for a different one; both
/// are also accessibility actions.
private struct SimulatorQRMock: View {
    let scanner: QRScannerModel

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.qrCardScan, style: .circular)
        QRModulesView(modules: .designMock)
            .padding(12)
            .background(shape.fill(Color.u2.qrPaperScan))
            .u2BoxShadow(.qrScanCard, in: shape)
            .rotationEffect(.degrees(-5))
            .contentShape(shape)
            .onTapGesture { simulate(match: true) }
            .onLongPressGesture(minimumDuration: 0.5) { simulate(match: false) }
            .accessibilityElement()
            .accessibilityLabel(Text("scan.a11y.simulatorMock", table: .verification))
            .accessibilityAction(named: Text("scan.a11y.simulateMatch", table: .verification)) { simulate(match: true) }
            .accessibilityAction(named: Text("scan.a11y.simulateMismatch", table: .verification)) { simulate(match: false) }
            .accessibilityIdentifier("scan.simulatorMock")
    }

    private func simulate(match: Bool) {
        let codes = scanner.simulatedCodes()
        guard let code = match ? codes.match : codes.mismatch else { return }
        Task { await scanner.handle(code) }
    }
}

// MARK: - Torch

/// The torch as a camera-surface C4 button: `ph:lightning` 21, filled while
/// the torch is on (proposed), the two glyphs cross-fading softly.
private struct CameraTorchButton: View {
    let isOn: Bool
    let isEnabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                PhosphorIcon(.lightning, size: 21)
                    .opacity(isOn ? 0 : 1)
                LightningFillShape()
                    .frame(width: 21, height: 21)
                    .opacity(isOn ? 1 : 0)
            }
            .u2Animation(U2Motion.state, value: isOn)
        }
        .buttonStyle(CameraGlassButtonStyle())
        .opacity(isEnabled ? 1 : U2Opacity.disabled)
        .disabled(!isEnabled)
        .accessibilityLabel(Text("scan.a11y.torch", table: .verification))
        .accessibilityValue(Text(isOn ? "a11y.on" : "a11y.off"))
        .accessibilityAddTraits(isOn ? .isSelected : [])
        .accessibilityIdentifier("scan.torch")
    }
}

/// C4 on the camera surface (same as `U2GlassIconButton(surface: .camera)`),
/// for a custom glyph: 44 circle `#f5f4ff@12%` (20 % pressed), inner 0.5 pt
/// `text·18%`, `#f5f4ff` ink, the soft press.
private struct CameraGlassButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(Color.u2.onMedia)
            .frame(width: U2Size.iconButton, height: U2Size.iconButton)
            .background(Circle().fill(Color.u2.onMedia.opacity(configuration.isPressed ? 0.2 : 0.12)))
            .u2InnerStroke(Color.u2.strokeGlass, width: 0.5, in: Circle())
            .contentShape(Circle())
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
    }
}

/// Phosphor 2.1.1 `lightning-fill` (MIT, licence bundled), drawn from its
/// SVG path on the 256 × 256 grid: icons.md bundles only the regular weight
/// (the design draws the torch off).
private struct LightningFillShape: View {
    var body: some View {
        Canvas { context, size in
            let scale = min(size.width, size.height) / 256
            var path = Path()
            // M213.85,125.46 l-112,120 a8,8,0,0,1-13.69-7 l14.66-73.33
            // L45.19,143.49 a8,8,0,0,1-3-13 l112-120 a8,8,0,0,1,13.69,7
            // L153.18,90.9 l57.63,21.61 a8,8,0,0,1,3,12.95 Z
            path.move(to: CGPoint(x: 213.85, y: 125.46))
            path.addLine(to: CGPoint(x: 101.85, y: 245.46))
            Self.arc(&path, to: CGPoint(x: 88.16, y: 238.46))
            path.addLine(to: CGPoint(x: 102.82, y: 165.13))
            path.addLine(to: CGPoint(x: 45.19, y: 143.49))
            Self.arc(&path, to: CGPoint(x: 42.19, y: 130.49))
            path.addLine(to: CGPoint(x: 154.19, y: 10.49))
            Self.arc(&path, to: CGPoint(x: 167.88, y: 17.49))
            path.addLine(to: CGPoint(x: 153.18, y: 90.9))
            path.addLine(to: CGPoint(x: 210.81, y: 112.51))
            Self.arc(&path, to: CGPoint(x: 213.81, y: 125.46))
            path.closeSubpath()
            context.scaleBy(x: scale, y: scale)
            context.fill(path, with: .foreground)
        }
        .accessibilityHidden(true)
    }

    /// SVG `a r,r,0,0,1` (small arc, positive sweep) from the current point
    /// to `end` — SVG 1.1 F.6.5 with rx = ry and no rotation.
    private static func arc(_ path: inout Path, to end: CGPoint, radius: CGFloat = 8) {
        guard let start = path.currentPoint else { return }
        let x1 = (start.x - end.x) / 2
        let y1 = (start.y - end.y) / 2
        let d2 = x1 * x1 + y1 * y1
        let r = max(radius, d2.squareRoot())
        let coefficient = ((r * r - d2) / d2).squareRoot()
        let center = CGPoint(x: coefficient * y1 + (start.x + end.x) / 2, y: -coefficient * x1 + (start.y + end.y) / 2)
        let from = atan2(start.y - center.y, start.x - center.x)
        let to = atan2(end.y - center.y, end.x - center.x)
        // Positive sweep on a y-down grid = increasing angle (`clockwise: false`).
        path.addArc(center: center, radius: r, startAngle: .radians(from), endAngle: .radians(to), clockwise: false)
    }
}

// MARK: - Warning card

/// The 5.6 floating card (`surface·92%` + blur, r20, inner `danger·45%`,
/// `0 14px 40px #000@45%`, pad 14 16, gap 12) with `ph:warning-circle` 22
/// `danger`, `noteTitle` and `captionTighter`. Tap to dismiss.
private struct WarningCard: View {
    let warning: QRScannerModel.Warning
    let onDismiss: () -> Void

    var body: some View {
        let texts = QRScannerModel.texts(for: warning)
        FloatingBanner(.warning) {
            HStack(alignment: .top, spacing: 12) {
                PhosphorIcon(.warningCircle, size: 22)
                    .foregroundStyle(Color.u2.danger)
                VStack(alignment: .leading, spacing: 3) {
                    Text(texts.title)
                        .u2Font(.noteTitle)
                        .foregroundStyle(Color.u2.text)
                    Text(texts.message)
                        .u2Font(.captionTighter)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onDismiss)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isStaticText)
        .accessibilityAction(named: Text("a11y.close"), onDismiss)
        .accessibilityIdentifier("scan.warning")
    }
}

// MARK: - Camera permission denied

/// “Kamera izni gerekli” (proposed): message and a pill that opens Settings.
private struct CameraDeniedPanel: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: 16) {
            PhosphorIcon(.camera, size: 28)
                .foregroundStyle(Color.u2.onMedia)
                .frame(width: 64, height: 64)
                .background(Circle().fill(Color.u2.onCameraGlass))
                .u2InnerStroke(Color.u2.strokeGlass, width: 0.5, in: Circle())
            VStack(spacing: 8) {
                Text("scan.denied.title", table: .verification)
                    .u2Font(.navTitle)
                    .foregroundStyle(Color.u2.onMedia)
                    .accessibilityAddTraits(.isHeader)
                Text("scan.denied.message", table: .verification)
                    .u2Font(.cameraHint)
                    .foregroundStyle(Color.u2.onMedia.opacity(0.8))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 300)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            } label: {
                HStack(spacing: 8) {
                    PhosphorIcon(.gearSix, size: 18)
                    Text("scan.denied.openSettings", table: .verification)
                }
            }
            .buttonStyle(U2CameraPillButtonStyle())
            .padding(.top, 4)
        }
        .padding(.horizontal, U2Spacing.screenGutterL)
    }
}

// MARK: - Own code

/// “Kendi kodumu göster” (proposed: the 6.1 layout without the peer): own
/// avatar and name, the QR card, the digits (safety number only), a hint and
/// “Kodu okut” back to the camera. The camera is off while it shows.
private struct OwnCodeContent: View {
    let scanner: QRScannerModel

    var body: some View {
        VStack(spacing: 0) {
            Color.clear.frame(height: 0).modifier(BelowTopBar())
            if let code = scanner.ownCode {
                MyCodeView(username: code.username, modules: code.modules)
                if let groups = code.groups {
                    SafetyDigitsGrid(groups: groups)
                        .padding(.top, 24)
                }
            }
            Spacer(minLength: 16)
            VStack(spacing: 16) {
                hint
                    .u2Font(.cameraHint)
                    .foregroundStyle(Color.u2.onMedia.opacity(0.8))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 300)
                    .fixedSize(horizontal: false, vertical: true)
                Button(action: scanner.showScanning) {
                    HStack(spacing: 8) {
                        PhosphorIcon(.scan, size: 18)
                        Text("scan.myCode.scan", table: .verification)
                    }
                }
                .buttonStyle(U2CameraPillButtonStyle())
                .accessibilityIdentifier("scan.backToScanning")
            }
            .padding(.horizontal, U2Spacing.screenGutterL)
            .u2Bottom(46)
        }
    }

    private var hint: Text {
        switch scanner.purpose {
        case .safetyNumber(let peer):
            Text("scan.myCode.hint \(peer)", table: .verification)
        case .addContact:
            Text("scan.contact.myCode.hint", table: .verification)
        }
    }
}
