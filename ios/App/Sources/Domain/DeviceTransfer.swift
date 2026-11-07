import Foundation

/// Moving the account to a new phone next to this one (PROTOCOL.md §5.6).
enum DeviceTransferFailure: Error, Equatable, Sendable {
    /// This phone is on no local network: no Wi-Fi, no hotspot.
    case noLocalNetwork
    /// None of the addresses in the code answered: not on the same network.
    case unreachable
    /// The connection broke on the way.
    case interrupted
    /// The account could not be prepared; nothing left this phone.
    case notPrepared
    /// The user left the screen or the app.
    case cancelled
    /// What is moving does not fit on the new phone.
    case noSpace
}

/// What the new phone received: a backup file and the password that opens it.
struct DeviceTransferDeliveryInfo: Hashable, Sendable {
    let file: URL
    let password: String
}

enum DeviceTransferEvent: Equatable, Sendable {
    /// A phone that read the code connected.
    case started
    case progress(Double)
    /// That phone's connection broke; the code still works.
    case interrupted
    /// What that phone offered does not fit on this one.
    case noSpace
}

/// The new phone's side while its code is on screen.
@MainActor
protocol DeviceTransferIncoming: AnyObject {
    /// The text of the QR code.
    var invitation: String { get }
    var onEvent: (@MainActor (DeviceTransferEvent) -> Void)? { get set }
    /// The first complete file; connections that fail do not end the wait.
    func waitForDelivery() async throws -> DeviceTransferDeliveryInfo
    /// Ends the invitation. What is underway stops.
    func stop()
    /// The received file, once it has been handed on or given up.
    func removeFiles()
}

/// The device preferences a direct transfer carries along (PROTOCOL.md §5.6), by their raw names.
struct DevicePreferencesSnapshot: Equatable, Sendable {
    var theme: String
    var language: String
    var keepScreenOn: Bool
}
