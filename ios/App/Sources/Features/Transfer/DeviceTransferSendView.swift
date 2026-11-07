import SwiftUI
import UIKit
import Us2usKit

/// The old phone's side of “Yeni telefona taşı” (PROTOCOL.md §5.6): what moves
/// and what does not → the secret code → the code on the new phone → sent.
@Observable
@MainActor
final class DeviceTransferSendModel {
    enum Stage: Equatable {
        case intro
        case code
        case scanning
        case sending(Double)
        case done
        case failed(DeviceTransferFailure)
    }

    private(set) var stage = Stage.intro
    let scanner = QRScannerModel(purpose: .deviceTransfer)

    private let messenger: any MessengerService
    private var invitation: String?
    private var sending: Task<Void, Never>?

    init(messenger: any MessengerService) {
        self.messenger = messenger
        scanner.evaluate = { [weak self] code in
            guard DeviceTransferInvite(text: code) != nil else { return .ignore }
            self?.invitation = code
            return .accept
        }
        scanner.onAccept = { [weak self] in self?.send() }
        scanner.onClose = { [weak self] in self?.show(.intro) }
        #if DEBUG
            scanner.simulatedCodes = { (DesignFixturesService.transferInvitation, "not-a-transfer-code") }
        #endif
    }

    func start() {
        show(.code)
    }

    func codeConfirmed() {
        scan()
    }

    func codeCancelled() {
        show(.intro)
    }

    /// “Yeniden dene”: the code on the new phone again.
    func scan() {
        invitation = nil
        scanner.reset()
        scanner.isActive = true
        show(.scanning)
    }

    /// The app left the foreground (it locks): nothing goes on behind the lock.
    func appLeft() {
        sending?.cancel()
        sending = nil
        scanner.isActive = false
        if stage != .done { stage = .intro }
    }

    private func send() {
        guard let invitation else { return }
        scanner.isActive = false
        show(.sending(0))
        sending = Task { [weak self, messenger] in
            do throws(DeviceTransferFailure) {
                try await messenger.sendAccount(toInvitation: invitation) { fraction in
                    guard let self, case .sending = self.stage else { return }
                    withU2Animation(U2Motion.micro) { self.stage = .sending(fraction) }
                }
                guard !Task.isCancelled else { return }
                U2Haptics.success()
                self?.show(.done)
            } catch {
                guard error != .cancelled, !Task.isCancelled else { return }
                U2Haptics.error()
                self?.show(.failed(error))
            }
        }
    }

    private func show(_ stage: Stage) {
        if stage != .scanning { scanner.isActive = false }
        withU2Animation(U2Motion.state) { self.stage = stage }
    }
}

struct DeviceTransferSendView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @State private var viewModel: DeviceTransferSendModel?
    @State private var awakeRequest = UUID()

    /// Everything moves while the app is open: the screen stays on until it has.
    private func keepAwake(_ stage: DeviceTransferSendModel.Stage?) {
        var running = false
        if case .sending? = stage { running = true }
        ScreenAwakeController.shared.setTransferRunning(running, owner: awakeRequest)
    }

    var body: some View {
        Group {
            if let viewModel {
                content(viewModel)
            } else {
                U2Screen { Color.clear }
            }
        }
        .onAppear {
            if viewModel == nil { viewModel = DeviceTransferSendModel(messenger: SettingsFlow.of(model).messenger) }
        }
        .onChange(of: locale, initial: true) { _, new in viewModel?.scanner.locale = new }
        .onChange(of: viewModel?.stage) { _, stage in keepAwake(stage) }
        .onDisappear { keepAwake(nil) }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
            viewModel?.appLeft()
        }
    }

    @ViewBuilder private func content(_ vm: DeviceTransferSendModel) -> some View {
        switch vm.stage {
        case .intro:
            intro(vm).transition(.u2Fade)
        case .code:
            ChangeCodeView(purpose: .confirmTransfer, onConfirmed: vm.codeConfirmed, onBack: vm.codeCancelled)
                .transition(.u2Fade)
        case .scanning:
            QRScanView(scanner: vm.scanner).transition(.u2Fade)
        case .sending(let fraction):
            DeviceTransferStatus(
                title: Text("transfer.send.sending", table: .settings),
                message: Text("transfer.send.sending.body", table: .settings), progress: fraction
            )
            .transition(.u2Fade)
        case .done:
            DeviceTransferStatus(
                glyph: .checkCircle, title: Text("transfer.send.done.title", table: .settings),
                message: Text("transfer.send.done.body", table: .settings),
                action: (Text("common.close"), model.router.pop), identifier: "transfer.send.done"
            )
            .transition(.u2Fade)
        case .failed(let failure):
            DeviceTransferStatus(
                glyph: .warningCircle, title: Text("transfer.failed.title", table: .settings),
                message: failure.message, action: (Text("transfer.retry", table: .settings), vm.scan),
                identifier: "transfer.send.failed"
            )
            .transition(.u2Fade)
        }
    }

    private func intro(_ vm: DeviceTransferSendModel) -> some View {
        U2Screen {
            VStack(spacing: 0) {
                CenteredTitleHeader(Text("transfer.send.title", table: .settings)) {
                    U2GlassIconButton(.caretLeft, glyphSize: 22, label: Text("a11y.back"), action: model.router.pop)
                        .accessibilityIdentifier("transfer.send.back")
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: U2Spacing.sectionGap) {
                        DeviceTransferSteps(steps: [
                            Text("transfer.send.step1", table: .settings),
                            Text("transfer.send.step2", table: .settings),
                            Text("transfer.send.step3", table: .settings),
                        ])
                        DeviceTransferNote(
                            title: Text("transfer.note.title", table: .settings),
                            message: Text("transfer.send.note", table: .settings))
                    }
                    .padding(.horizontal, U2Spacing.screenGutterL)
                    .padding(.top, 20)
                }
                .scrollIndicators(.hidden)
                Button(action: vm.start) {
                    U2ButtonLabel(Text("transfer.send.continue", table: .settings))
                }
                .buttonStyle(.u2Primary)
                .padding(.horizontal, U2Spacing.screenGutterL)
                .u2Bottom(U2Spacing.ctaBottomOnboarding)
                .accessibilityIdentifier("transfer.send.continue")
            }
        }
    }
}

extension DeviceTransferFailure {
    var message: Text {
        switch self {
        case .noLocalNetwork: Text("transfer.failed.noLocalNetwork", table: .settings)
        case .noSpace: Text("transfer.failed.noSpace", table: .settings)
        case .unreachable: Text("transfer.failed.unreachable", table: .settings)
        case .notPrepared: Text("transfer.failed.notPrepared", table: .settings)
        case .interrupted, .cancelled: Text("transfer.failed.interrupted", table: .settings)
        }
    }
}

/// Numbered steps of either side.
struct DeviceTransferSteps: View {
    let steps: [Text]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(verbatim: "\(index + 1)")
                        .u2Font(.monoMeta)
                        .foregroundStyle(Color.u2.accent300)
                        .accessibilityHidden(true)
                    step
                        .u2Font(.body)
                        .foregroundStyle(Color.u2.text)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct DeviceTransferNote: View {
    let title: Text
    let message: Text

    var body: some View {
        U2Card {
            VStack(alignment: .leading, spacing: 6) {
                title
                    .u2Font(.noteTitle)
                    .foregroundStyle(Color.u2.text)
                message
                    .u2Font(.captionTight)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// A moment of the transfer: a spinner and a meter, or how it ended.
struct DeviceTransferStatus: View {
    @Environment(\.locale) private var locale
    var glyph: PhosphorGlyph? = nil
    let title: Text
    let message: Text
    var progress: Double? = nil
    var action: (title: Text, run: () -> Void)? = nil
    /// On the title: on the whole screen it would replace the button's own.
    var identifier: String? = nil

    var body: some View {
        U2Screen {
            VStack(spacing: 18) {
                Spacer(minLength: 0)
                if let glyph {
                    PhosphorIcon(glyph, size: 44)
                        .foregroundStyle(glyph == .warningCircle ? Color.u2.danger : Color.u2.accent300)
                        .accessibilityHidden(true)
                } else {
                    SpinningGlyph(size: 28)
                        .foregroundStyle(Color.u2.accent300)
                        .accessibilityHidden(true)
                }
                title
                    .u2Font(.displayTitleXS)
                    .foregroundStyle(Color.u2.text)
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier(identifier ?? "transfer.status.title")
                message
                    .u2Font(.body)
                    .foregroundStyle(Color.u2.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                if let progress {
                    DottedMeter(progress: progress, pattern: .progress)
                        .u2Animation(U2Motion.micro, value: progress)
                        .padding(.top, 8)
                    Text(verbatim: progress.formatted(.percent.precision(.fractionLength(0)).locale(locale)))
                        .u2Font(.monoMeta)
                        .foregroundStyle(Color.u2.textSecondary)
                        .contentTransition(.numericText())
                }
                Spacer(minLength: 0)
                if let action {
                    Button(action: action.run) { U2ButtonLabel(action.title) }
                        .buttonStyle(.u2Primary)
                        .accessibilityIdentifier("transfer.action")
                }
            }
            .padding(.horizontal, U2Spacing.screenGutterL)
            .u2Bottom(U2Spacing.ctaBottomOnboarding)
            .u2Top(U2Spacing.headerTopButtons)
        }
    }
}
