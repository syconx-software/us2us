import Foundation
import Us2usKit
import Us2usRuntime

/// Direct transfer over the phones' own network (PROTOCOL.md §5.6): the backup
/// pipeline of §5.5 with a generated password nobody has to see or keep.
extension RealMessengerService {
    /// What a killed process left of a transfer cannot be opened without the key it took along.
    nonisolated static func removeLeftoverTransfer() {
        try? FileManager.default.removeItem(
            at: FileManager.default.temporaryDirectory.appendingPathComponent("device-transfer", isDirectory: true))
    }

    func startReceivingAccount() async throws(DeviceTransferFailure) -> any DeviceTransferIncoming {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("device-transfer", isDirectory: true)
        do {
            let listening = try DeviceTransferListening(directory: directory)
            try await listening.start()
            pendingTransferHistory = nil
            return LiveDeviceTransferIncoming(listening) { [weak self] history in
                self?.pendingTransferHistory = history
            }
        } catch DeviceTransferNetworkError.noLocalNetwork {
            throw .noLocalNetwork
        } catch {
            throw .unreachable
        }
    }

    func connectDevicePreferences(_ current: @escaping @MainActor () -> DevicePreferencesSnapshot) {
        currentDevicePreferences = current
    }

    func takeTransferredPreferences() -> DevicePreferencesSnapshot? {
        runtime.takeTransferredPreferences().map {
            DevicePreferencesSnapshot(theme: $0.theme, language: $0.language, keepScreenOn: $0.keepScreenOn)
        }
    }

    func sendAccount(
        toInvitation invitation: String, progress: @escaping @MainActor @Sendable (Double) -> Void
    ) async throws(DeviceTransferFailure) {
        let password = BackupPassword.generate()
        let file: URL
        let history: any DeviceTransferHistorySource
        do {
            let preferences = currentDevicePreferences?()
            history = try await runtime.transferHistory(preferences: preferences.map {
                TransferredPreferences(theme: $0.theme, language: $0.language, keepScreenOn: $0.keepScreenOn)
            })
            file = try await exportBackup(BackupContents(), password: password, fileName: "transfer.u2u") { _ in }
        } catch {
            throw .notPrepared
        }
        do {
            try await DeviceTransferSending.send(
                file: file, password: password, invitation: invitation, history: history
            ) { fraction in
                Task { @MainActor in progress(fraction) }
            }
            await finishExport(at: file, saved: false)
        } catch {
            await finishExport(at: file, saved: false)
            switch error {
            case DeviceTransferNetworkError.unreachable: throw .unreachable
            case DeviceTransferError.noSpace: throw .noSpace
            case is CancellationError: throw .cancelled
            default: throw .interrupted
            }
        }
    }
}

@MainActor
private final class LiveDeviceTransferIncoming: DeviceTransferIncoming {
    private let listening: DeviceTransferListening
    /// Hands the history on, and takes it back (`nil`) when the files go.
    private let keepHistory: @MainActor (DeviceTransferSpool?) -> Void
    var onEvent: (@MainActor (DeviceTransferEvent) -> Void)?

    init(_ listening: DeviceTransferListening, keepHistory: @escaping @MainActor (DeviceTransferSpool?) -> Void) {
        self.listening = listening
        self.keepHistory = keepHistory
        listening.onNoSpace = { [weak self] in Task { @MainActor in self?.onEvent?(.noSpace) } }
        listening.onStarted = { [weak self] in Task { @MainActor in self?.onEvent?(.started) } }
        listening.onProgress = { [weak self] fraction in Task { @MainActor in self?.onEvent?(.progress(fraction)) } }
        listening.onInterrupted = { [weak self] in Task { @MainActor in self?.onEvent?(.interrupted) } }
    }

    var invitation: String { listening.invitation }

    func waitForDelivery() async throws -> DeviceTransferDeliveryInfo {
        let delivery = try await listening.waitForDelivery()
        keepHistory(delivery.history)
        return DeviceTransferDeliveryInfo(file: delivery.file, password: delivery.password)
    }

    func stop() {
        listening.stop()
    }

    func removeFiles() {
        keepHistory(nil)
        listening.removeFiles()
    }
}
