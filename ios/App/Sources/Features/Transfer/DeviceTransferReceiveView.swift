import SwiftUI
import UIKit

/// The new phone's side of the transfer (PROTOCOL.md §5.6): its code on screen
/// until the old phone has read it and sent the account; from there on it is
/// the restore of 7.3, with a new secret code at the end.
@Observable
@MainActor
final class DeviceTransferReceiveModel {
    enum Stage: Equatable {
        case preparing
        case waiting(invitation: String)
        case receiving(Double)
        case delivered(DeviceTransferDeliveryInfo)
        case failed(DeviceTransferFailure)
    }

    /// An invitation is shown for at most ten minutes; then a new one takes its place.
    private static let invitationLifetime: Duration = .seconds(600)

    private(set) var stage = Stage.preparing
    private let messenger: any MessengerService
    private let lifetime: Duration
    private var incoming: (any DeviceTransferIncoming)?
    private var waiting: Task<Void, Never>?
    private var expiry: Task<Void, Never>?

    init(messenger: any MessengerService, lifetime: Duration = DeviceTransferReceiveModel.invitationLifetime) {
        self.messenger = messenger
        self.lifetime = lifetime
    }

    func start() {
        stop()
        show(.preparing)
        waiting = Task { [weak self, messenger] in
            let incoming: any DeviceTransferIncoming
            do throws(DeviceTransferFailure) {
                incoming = try await messenger.startReceivingAccount()
            } catch {
                self?.show(.failed(error))
                return
            }
            guard let self, !Task.isCancelled else {
                incoming.stop()
                return
            }
            self.incoming = incoming
            let invitation = incoming.invitation
            incoming.onEvent = { [weak self] event in
                guard let self else { return }
                switch event {
                case .started:
                    self.expiry?.cancel()
                    self.show(.receiving(0))
                case .progress(let fraction):
                    if case .receiving = self.stage { withU2Animation(U2Motion.micro) { self.stage = .receiving(fraction) } }
                case .interrupted:
                    self.showCode(invitation)
                case .noSpace:
                    self.stop()
                    self.show(.failed(.noSpace))
                }
            }
            self.showCode(invitation)
            do {
                let delivery = try await incoming.waitForDelivery()
                self.expiry?.cancel()
                U2Haptics.success()
                incoming.onEvent = nil
                incoming.stop()
                self.show(.delivered(delivery))
            } catch {
                // Ended on purpose: a new invitation, or the screen went away.
            }
        }
    }

    /// The code is up, and runs out in a while unless a phone connects.
    private func showCode(_ invitation: String) {
        show(.waiting(invitation: invitation))
        expiry?.cancel()
        expiry = Task { [weak self, lifetime] in
            try? await Task.sleep(for: lifetime)
            guard !Task.isCancelled, let self, case .waiting = self.stage else { return }
            self.start()
        }
    }

    /// Ends the invitation; a file already handed on stays until `finish()`.
    func stop() {
        waiting?.cancel()
        waiting = nil
        expiry?.cancel()
        expiry = nil
        // What is still on its way from this invitation no longer counts.
        incoming?.onEvent = nil
        incoming?.stop()
    }

    /// The screen goes away: nothing of the transfer stays on the phone.
    func finish() {
        stop()
        incoming?.removeFiles()
        incoming = nil
    }

    /// The app left the foreground: a new invitation on the way back (the sockets do not survive it).
    func appLeft() {
        guard !isDelivered else { return }
        stop()
    }

    func appReturned() {
        guard !isDelivered, waiting == nil else { return }
        start()
    }

    private var isDelivered: Bool {
        if case .delivered = stage { return true }
        return false
    }

    private func show(_ stage: Stage) {
        withU2Animation(U2Motion.state) { self.stage = stage }
    }
}

struct DeviceTransferReceiveView: View {
    let onCancel: () -> Void
    let onFinished: () -> Void

    @Environment(AppModel.self) private var model
    @State private var viewModel: DeviceTransferReceiveModel?
    @State private var awakeRequest = UUID()

    var body: some View {
        Group {
            if let viewModel {
                content(viewModel)
            } else {
                U2Screen { Color.clear }
            }
        }
        .onAppear {
            // The code stays readable for as long as it takes to fetch the old phone.
            ScreenAwakeController.shared.setTransferRunning(true, owner: awakeRequest)
            guard viewModel == nil else { return }
            let created = DeviceTransferReceiveModel(messenger: model.messenger)
            viewModel = created
            created.start()
        }
        .onDisappear {
            ScreenAwakeController.shared.setTransferRunning(false, owner: awakeRequest)
            viewModel?.finish()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
            viewModel?.appLeft()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            viewModel?.appReturned()
        }
    }

    @ViewBuilder private func content(_ vm: DeviceTransferReceiveModel) -> some View {
        switch vm.stage {
        case .preparing:
            DeviceTransferStatus(
                title: Text("transfer.receive.title", table: .settings),
                message: Text("transfer.receive.preparing", table: .settings)
            )
            .transition(.u2Fade)
        case .waiting(let invitation):
            code(invitation).transition(.u2Fade)
        case .receiving(let fraction):
            DeviceTransferStatus(
                title: Text("transfer.receive.receiving", table: .settings),
                message: Text("transfer.send.sending.body", table: .settings), progress: fraction
            )
            .transition(.u2Fade)
        case .delivered(let delivery):
            RestoreBackupView(transferred: delivery, onClose: onCancel, onFinished: onFinished)
                .transition(.u2Fade)
        case .failed(let failure):
            DeviceTransferStatus(
                glyph: .warningCircle, title: Text("transfer.failed.title", table: .settings),
                message: failure == .noSpace ? Text("transfer.failed.noSpace.here", table: .settings) : failure.message,
                action: (Text("transfer.retry", table: .settings), vm.start),
                identifier: "transfer.receive.failed"
            )
            .transition(.u2Fade)
        }
    }

    private func code(_ invitation: String) -> some View {
        U2Screen {
            VStack(spacing: 0) {
                CenteredTitleHeader(Text("transfer.receive.title", table: .settings)) {
                    U2GlassIconButton(.caretLeft, glyphSize: 22, label: Text("a11y.back"), action: onCancel)
                        .accessibilityIdentifier("transfer.receive.back")
                }
                ScrollView {
                    VStack(spacing: U2Spacing.sectionGap) {
                        QRCodeCard(payload: invitation, label: Text("transfer.receive.a11y.code", table: .settings))
                            .accessibilityIdentifier("transfer.receive.code")
                        DeviceTransferSteps(steps: [
                            Text("transfer.receive.step1", table: .settings),
                            Text("transfer.receive.step2", table: .settings),
                        ])
                        DeviceTransferNote(
                            title: Text("transfer.note.title", table: .settings),
                            message: Text("transfer.receive.note", table: .settings))
                    }
                    .padding(.horizontal, U2Spacing.screenGutterL)
                    .padding(.top, 20)
                    .u2Bottom(24)
                }
                .scrollIndicators(.hidden)
            }
        }
    }
}

/// What stands in for 7.3's file and password when the account came from the old phone.
struct TransferredAccountCard: View {
    let stage: RestoreBackupModel.Stage

    private var isProblem: Bool {
        switch stage {
        case .failed, .wrongPassword, .unverified: true
        default: false
        }
    }

    private var isOpen: Bool {
        switch stage {
        case .restoring, .done: true
        default: false
        }
    }

    private var caption: Text {
        switch stage {
        case .restoring, .done: Text("transfer.restore.ready", table: .settings)
        case .unverified: Text("transfer.restore.unverified", table: .settings)
        case .failed, .wrongPassword: Text("transfer.restore.failed", table: .settings)
        default: Text("restore.checking", table: .settings)
        }
    }

    var body: some View {
        BackupFileCard(
            name: Text("transfer.restore.arrived", table: .settings), caption: caption,
            captionColor: isProblem ? Color.u2.danger : Color.u2.textSecondary
        ) {
            Group {
                if isProblem {
                    PhosphorIcon(.warningCircle, size: 22).foregroundStyle(Color.u2.danger)
                } else if isOpen {
                    PhosphorIcon(.checkCircle, weight: .fill, size: 22).foregroundStyle(Color.u2.online)
                } else {
                    SpinningGlyph(size: 20).foregroundStyle(Color.u2.textSecondary)
                }
            }
            .frame(width: 22)
            .accessibilityHidden(true)
        }
        .accessibilityIdentifier("transfer.restore.card")
    }
}

/// 7.3's button until the account is open: nothing to pick or type, only to try again.
struct TransferredRestoreAction: View {
    let stage: RestoreBackupModel.Stage
    let onRetry: () -> Void
    let onStartOver: () -> Void

    var body: some View {
        switch stage {
        case .unverified:
            Button(action: onRetry) { U2ButtonLabel(Text("transfer.retry", table: .settings)) }
                .buttonStyle(.u2Primary)
                .accessibilityIdentifier("restore.action.submit")
        case .failed, .wrongPassword:
            Button(action: onStartOver) { U2ButtonLabel(Text("transfer.retry", table: .settings)) }
                .buttonStyle(.u2Primary)
                .accessibilityIdentifier("restore.action.startOver")
        default:
            Button {} label: { U2ButtonLabel(Text("restore.checking", table: .settings), isWorking: true) }
                .buttonStyle(.u2Primary)
                .allowsHitTesting(false)
        }
    }
}
