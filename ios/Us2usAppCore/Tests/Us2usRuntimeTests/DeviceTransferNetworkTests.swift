import Foundation
import Network
import Testing
import Us2usKit

@testable import Us2usRuntime

/// PROTOCOL.md §5.6 over real TCP on this machine: the listener, the sender and
/// what either does when the other is not who, or where, it should be.
@Suite("Direct transfer over the network")
struct DeviceTransferNetworkTests {
    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("transfer-\(UUID().uuidString)", isDirectory: true)
    }

    private func file(_ bytes: Data) throws -> URL {
        let url = temporaryDirectory().appendingPathExtension("u2u")
        try bytes.write(to: url)
        return url
    }

    private struct EstimateFails: DeviceTransferHistorySource {
        struct Failure: Error {}
        func estimatedBytes() async throws -> UInt64 { throw Failure() }
        func export(_ emit: @Sendable (Data) async throws -> Void) async throws {}
    }

    @Test("the estimate is made before connecting: the new phone's seconds are not spent on it")
    func theEstimateComesFirst() async throws {
        let source = try file(Data([1]))
        defer { try? FileManager.default.removeItem(at: source) }
        // Nobody answers here: reaching for it first would fail differently.
        let invitation = DeviceTransferReceiver().invite(endpoints: [.init(host: "10.255.255.1", port: 9)]).text
        await #expect(throws: EstimateFails.Failure.self) {
            try await DeviceTransferSending.send(
                file: source, password: "p", invitation: invitation, history: EstimateFails(), progress: { _ in })
        }
    }

    @Test("the file and its password arrive from the first address that answers")
    func transfer() async throws {
        let directory = temporaryDirectory()
        let bytes = Data((0..<900_000).map { UInt8(truncatingIfNeeded: $0 &* 17) })
        let source = try file(bytes)
        defer { try? FileManager.default.removeItem(at: source) }
        let listening = try DeviceTransferListening(directory: directory)
        defer {
            listening.stop()
            listening.removeFiles()
        }
        try await listening.start()
        let invite = try #require(DeviceTransferInvite(text: listening.invitation))
        #expect(!invite.endpoints.isEmpty)

        async let delivery = listening.waitForDelivery()
        try await DeviceTransferSending.send(file: source, password: "k7mq-x2pd", invitation: listening.invitation) { _ in }
        let delivered = try await delivery
        #expect(delivered.password == "k7mq-x2pd")
        #expect(try Data(contentsOf: delivered.file) == bytes)
    }

    @Test("a stranger's connection does not end the invitation")
    func strangerDoesNotEndIt() async throws {
        let directory = temporaryDirectory()
        let source = try file(Data("backup".utf8))
        defer { try? FileManager.default.removeItem(at: source) }
        let listening = try DeviceTransferListening(directory: directory)
        defer {
            listening.stop()
            listening.removeFiles()
        }
        try await listening.start()
        let invite = try #require(DeviceTransferInvite(text: listening.invitation))
        let endpoint = try #require(invite.endpoints.first)

        // Someone on the network connects and talks nonsense, then leaves.
        let stranger = NWConnection(
            host: NWEndpoint.Host(endpoint.host), port: try #require(NWEndpoint.Port(rawValue: endpoint.port)), using: .tcp)
        let stream = DeviceTransferNetworkStream(stranger)
        try await stream.waitUntilReady()
        try await stream.write(Data(repeating: 0x41, count: 64))
        await #expect(throws: (any Error).self) { _ = try await stream.read(1) }
        stream.close()

        async let delivery = listening.waitForDelivery()
        try await DeviceTransferSending.send(file: source, password: "secret", invitation: listening.invitation) { _ in }
        #expect(try await delivery.password == "secret")
    }

    @Test("nobody at the code's address: it says so instead of waiting forever")
    func unreachable() async throws {
        let source = try file(Data("backup".utf8))
        defer { try? FileManager.default.removeItem(at: source) }
        let listening = try DeviceTransferListening(directory: temporaryDirectory())
        try await listening.start()
        let invite = try #require(DeviceTransferInvite(text: listening.invitation))
        listening.stop()
        let gone = DeviceTransferInvite(
            receiverKey: invite.receiverKey, secret: invite.secret, endpoints: [.init(host: "127.0.0.1", port: 9)])
        await #expect(throws: DeviceTransferNetworkError.unreachable) {
            try await DeviceTransferSending.send(file: source, password: "x", invitation: gone.text) { _ in }
        }
    }

    @Test("stopping ends the wait and leaves nothing behind")
    func stopping() async throws {
        let directory = temporaryDirectory()
        let listening = try DeviceTransferListening(directory: directory)
        try await listening.start()
        async let delivery: Void = {
            await #expect(throws: CancellationError.self) { _ = try await listening.waitForDelivery() }
        }()
        listening.stop()
        await delivery
        listening.removeFiles()
        #expect(!FileManager.default.fileExists(atPath: directory.path))
    }

    @Test("only private addresses go into a code")
    func addresses() {
        for address in ["10.0.0.5", "172.16.0.1", "172.31.255.1", "192.168.1.20", "169.254.3.4"] {
            #expect(DeviceTransferAddresses.isPrivate(address), "\(address)")
        }
        for address in ["8.8.8.8", "172.32.0.1", "192.169.0.1", "100.64.0.1", "fe80::1"] {
            #expect(!DeviceTransferAddresses.isPrivate(address), "\(address)")
        }
    }

    @Test("a code that points at the internet is never connected to")
    func publicAddress() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let listening = try DeviceTransferListening(directory: directory)
        try await listening.start()
        defer {
            listening.stop()
            listening.removeFiles()
        }
        let invite = try #require(DeviceTransferInvite(text: listening.invitation))
        let trap = DeviceTransferInvite(
            receiverKey: invite.receiverKey, secret: invite.secret, endpoints: [.init(host: "8.8.8.8", port: 443)])
        let source = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("backup".utf8).write(to: source)
        defer { try? FileManager.default.removeItem(at: source) }
        await #expect(throws: DeviceTransferNetworkError.unreachable) {
            try await DeviceTransferSending.send(file: source, password: "p", invitation: trap.text, progress: { _ in })
        }
    }

    @Test("stopping ends a transfer underway: the old phone does not hear “received”")
    func stoppingEndsATransferUnderway() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let listening = try DeviceTransferListening(directory: directory)
        try await listening.start()
        defer { listening.removeFiles() }
        listening.onStarted = { listening.stop() }
        let source = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data(repeating: 7, count: 3_000_000).write(to: source)
        defer { try? FileManager.default.removeItem(at: source) }
        await #expect(throws: (any Error).self) {
            try await DeviceTransferSending.send(
                file: source, password: "p", invitation: listening.invitation, progress: { _ in })
        }
    }
}
