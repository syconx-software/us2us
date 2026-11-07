import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

/// PROTOCOL.md §5.6: the invitation, the keys and the frames against
/// spec/test-vectors/device-transfer.json (Android verifies the same file), and
/// both sides of the exchange over an in-memory connection.
@Suite("Direct transfer to a new phone")
struct DeviceTransferTests {
    struct Vectors: Decodable {
        struct Frame: Decodable {
            let direction: String
            let counter: Int
            let type: UInt8
            let bodyHex: String
            let frameHex: String
        }
        let receiverPrivateHex: String
        let receiverKeyHex: String
        let senderPrivateHex: String
        let senderKeyHex: String
        let secretHex: String
        let endpoints: [String]
        let invite: String
        let helloHex: String
        let senderToReceiverKeyHex: String
        let receiverToSenderKeyHex: String
        let frames: [Frame]
        let invalidInvites: [String]
        let historyRecords: HistoryRecords
        struct HistoryRecords: Decodable {
            let messageHex: String
            let filePartHex: String
        }
    }

    private func vectors() throws -> Vectors {
        let url = RepositoryPaths.testVectors.appendingPathComponent("device-transfer.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: url))
    }

    private func hex(_ string: String) throws -> Data {
        var data = Data()
        var index = string.startIndex
        while index < string.endIndex {
            let next = string.index(index, offsetBy: 2)
            data.append(try #require(UInt8(string[index..<next], radix: 16)))
            index = next
        }
        return data
    }

    /// Two ends joined back to back, like a TCP connection.
    private final class Pipe: @unchecked Sendable {
        final class Direction: @unchecked Sendable {
            private let lock = NSLock()
            private var buffer = Data()
            private var isClosed = false
            private var waiter: (count: Int, continuation: CheckedContinuation<Data, any Error>)?
            private(set) var total = 0

            func append(_ data: Data) {
                lock.lock()
                buffer.append(data)
                total += data.count
                let resume = takeIfReady()
                lock.unlock()
                resume?()
            }

            func close() {
                lock.lock()
                isClosed = true
                let resume = takeIfReady()
                lock.unlock()
                resume?()
            }

            func read(_ count: Int) async throws -> Data {
                try await withCheckedThrowingContinuation { continuation in
                    lock.lock()
                    waiter = (count, continuation)
                    let resume = takeIfReady()
                    lock.unlock()
                    resume?()
                }
            }

            private func takeIfReady() -> (() -> Void)? {
                guard let waiter else { return nil }
                if buffer.count >= waiter.count {
                    let data = Data(buffer.prefix(waiter.count))
                    buffer = Data(buffer.dropFirst(waiter.count))
                    self.waiter = nil
                    return { waiter.continuation.resume(returning: data) }
                }
                if isClosed {
                    self.waiter = nil
                    return { waiter.continuation.resume(throwing: DeviceTransferError.closed) }
                }
                return nil
            }
        }

        struct End: DeviceTransferStream {
            let incoming: Direction
            let outgoing: Direction
            func read(_ count: Int) async throws -> Data { try await incoming.read(count) }
            func write(_ data: Data) async throws { outgoing.append(data) }
            func close() {
                incoming.close()
                outgoing.close()
            }
        }

        let toReceiver = Direction()
        let toSender = Direction()
        var sender: End { End(incoming: toSender, outgoing: toReceiver) }
        var receiver: End { End(incoming: toReceiver, outgoing: toSender) }
    }

    private func temporaryFile(_ bytes: Data? = nil) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        if let bytes { try bytes.write(to: url) }
        return url
    }

    // MARK: Shared vectors

    @Test("the invitation reads and writes as the vectors say, and anything else is refused")
    func invitation() throws {
        let vectors = try vectors()
        let invite = try #require(DeviceTransferInvite(text: vectors.invite))
        #expect(invite.receiverKey == (try hex(vectors.receiverKeyHex)))
        #expect(invite.secret == (try hex(vectors.secretHex)))
        #expect(invite.endpoints.map { "\($0.host):\($0.port)" } == vectors.endpoints)
        #expect(invite.text == vectors.invite)
        for text in vectors.invalidInvites {
            #expect(DeviceTransferInvite(text: text) == nil, "\(text)")
        }
    }

    @Test("both phones derive the vectors' keys and frames")
    func keysAndFrames() throws {
        let vectors = try vectors()
        let receiver = try PrivateKey(try hex(vectors.receiverPrivateHex))
        let sender = try PrivateKey(try hex(vectors.senderPrivateHex))
        #expect(receiver.publicKey.serialize() == (try hex(vectors.receiverKeyHex)))
        #expect(sender.publicKey.serialize() == (try hex(vectors.senderKeyHex)))
        #expect(DeviceTransferHello.make(senderKey: try hex(vectors.senderKeyHex)) == (try hex(vectors.helloHex)))
        #expect(try DeviceTransferHello.senderKey(in: try hex(vectors.helloHex)) == (try hex(vectors.senderKeyHex)))

        for shared in [
            receiver.keyAgreement(with: sender.publicKey), sender.keyAgreement(with: receiver.publicKey),
        ] {
            let keys = DeviceTransferKeys(
                shared: shared, secret: try hex(vectors.secretHex), receiverKey: try hex(vectors.receiverKeyHex),
                senderKey: try hex(vectors.senderKeyHex))
            #expect(keys.senderToReceiver == (try hex(vectors.senderToReceiverKeyHex)))
            #expect(keys.receiverToSender == (try hex(vectors.receiverToSenderKeyHex)))
        }

        var sealS2R = DeviceTransferCipher(key: try hex(vectors.senderToReceiverKeyHex))
        var sealR2S = DeviceTransferCipher(key: try hex(vectors.receiverToSenderKeyHex))
        var openS2R = DeviceTransferCipher(key: try hex(vectors.senderToReceiverKeyHex))
        var openR2S = DeviceTransferCipher(key: try hex(vectors.receiverToSenderKeyHex))
        for frame in vectors.frames {
            let type = try #require(DeviceTransferFrame(rawValue: frame.type))
            let body = try hex(frame.bodyHex), wire = try hex(frame.frameHex)
            let toReceiver = frame.direction == "senderToReceiver"
            #expect(try (toReceiver ? sealS2R.seal(type, body) : sealR2S.seal(type, body)) == wire)
            let opened = try toReceiver ? openS2R.open(wire.dropFirst(4)) : openR2S.open(wire.dropFirst(4))
            #expect(opened.type == type)
            #expect(opened.body == body)
        }
    }

    @Test("the history's records read as the vectors say, and write back the same")
    func historyRecords() throws {
        let vectors = try vectors()
        let wire = try hex(vectors.historyRecords.messageHex)
        let record = try BRPTransferRecord(serializedBytes: wire)
        guard case .message(let message)? = record.record else {
            Issue.record("not a message")
            return
        }
        #expect(message.messageID == Data(repeating: 0x11, count: 16))
        #expect(message.outgoing)
        #expect(message.kind == 1)
        #expect(message.sentAt == 1000)
        #expect(message.status == 1)
        #expect(message.acceptedAt == 2000)
        #expect(message.reactions.first?.reactor == Data(repeating: 0x22, count: 16))
        #expect(message.reactions.first?.emoji == "👍")
        #expect(message.reactions.first?.sentAt == 3000)
        #expect(message.attachments.first?.state == 12)
        #expect(message.attachments.first?.byteCount == 3)
        #expect(message.attachments.first?.hasFile_p == true)
        #expect(message.attachments.first?.fileBytes == 3)
        #expect(try record.serializedBytes() == wire)
        let part = try BRPTransferRecord(serializedBytes: try hex(vectors.historyRecords.filePartHex))
        #expect(part.filePart.data == Data([1, 2, 3]))
    }

    @Test("a frame out of order does not open")
    func outOfOrder() throws {
        let key = Data(repeating: 7, count: 32)
        var seal = DeviceTransferCipher(key: key)
        var open = DeviceTransferCipher(key: key)
        let first = try seal.seal(.chunk, Data([1])), second = try seal.seal(.chunk, Data([2]))
        #expect(throws: DeviceTransferError.notAuthentic) { _ = try open.open(second.dropFirst(4)) }
        #expect(try open.open(first.dropFirst(4)).body == Data([1]))
    }

    // MARK: The exchange

    @Test("the file and its password arrive whole")
    func exchange() async throws {
        let bytes = Data((0..<700_000).map { UInt8(truncatingIfNeeded: $0 &* 31) })
        let source = try temporaryFile(bytes), destination = try temporaryFile()
        defer {
            try? FileManager.default.removeItem(at: source)
            try? FileManager.default.removeItem(at: destination)
        }
        let receiver = DeviceTransferReceiver()
        let invite = receiver.invite(endpoints: [.init(host: "192.168.1.2", port: 5000)])
        let pipe = Pipe()
        async let password = receiver.receive(over: pipe.receiver, into: destination)
        try await DeviceTransferSender.send(file: source, password: "k7mq-x2pd", invite: invite, over: pipe.sender)
        #expect(try await password == "k7mq-x2pd")
        #expect(try Data(contentsOf: destination) == bytes)
    }

    private struct Records: DeviceTransferHistorySource {
        let records: [Data]
        func estimatedBytes() async throws -> UInt64 { records.reduce(0) { $0 + UInt64($1.count) } }
        func export(_ emit: @Sendable (Data) async throws -> Void) async throws {
            for record in records { try await emit(record) }
        }
    }

    private final class Collected: DeviceTransferHistorySink, @unchecked Sendable {
        private let lock = NSLock()
        private var stored: [Data] = []
        var finished = false
        var fits = true
        var records: [Data] { lock.withLock { stored } }
        func canHold(bytes: UInt64) -> Bool { fits }
        func append(_ record: Data) throws { lock.withLock { stored.append(record) } }
        func finish() throws { finished = true }
    }

    @Test("the history follows the file, in order, and only then is it received")
    func exchangeWithHistory() async throws {
        let source = try temporaryFile(Data("backup".utf8)), destination = try temporaryFile()
        defer {
            try? FileManager.default.removeItem(at: source)
            try? FileManager.default.removeItem(at: destination)
        }
        let records = (0..<5).map { Data(repeating: UInt8($0), count: 1000 * ($0 + 1)) }
        let receiver = DeviceTransferReceiver()
        let collected = Collected()
        let pipe = Pipe()
        async let password = receiver.receive(over: pipe.receiver, into: destination, history: collected)
        try await DeviceTransferSender.send(
            file: source, password: "p", invite: receiver.invite(endpoints: [.init(host: "192.168.1.2", port: 5000)]),
            over: pipe.sender, history: Records(records: records))
        #expect(try await password == "p")
        #expect(collected.records == records)
        #expect(collected.finished)
    }

    @Test("a new phone without room says so, and nothing is written")
    func noRoom() async throws {
        let source = try temporaryFile(Data("backup".utf8)), destination = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: source) }
        let receiver = DeviceTransferReceiver()
        let collected = Collected()
        collected.fits = false
        let pipe = Pipe()
        async let received: Void = {
            await #expect(throws: DeviceTransferError.noSpace) {
                _ = try await receiver.receive(over: pipe.receiver, into: destination, history: collected)
            }
        }()
        await #expect(throws: DeviceTransferError.noSpace) {
            try await DeviceTransferSender.send(
                file: source, password: "p", invite: receiver.invite(endpoints: [.init(host: "192.168.1.2", port: 5000)]),
                over: pipe.sender, history: Records(records: [Data([1])]))
        }
        await received
        #expect(!FileManager.default.fileExists(atPath: destination.path))
        #expect(collected.records.isEmpty)
    }

    @Test("the spool gives back what it was given, across its files, and removes them as it goes")
    func spool() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let spool = try DeviceTransferSpool(directory: directory)
        let records = (0..<300).map { Data(repeating: UInt8($0 % 251), count: 256 * 1024) }
        for record in records { try spool.append(record) }
        try spool.finish()
        let files = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        #expect(files.count >= 2, "75 MiB takes more than one file")
        // The bytes on disk are not the records.
        let first = try Data(contentsOf: directory.appendingPathComponent("history-1"))
        #expect(first.range(of: Data(repeating: 5, count: 1024)) == nil)
        let reader = spool.reader()
        var read: [Data] = []
        while let record = try reader.next() { read.append(record) }
        #expect(read == records)
        #expect(reader.fraction == 1)
        #expect(try FileManager.default.contentsOfDirectory(atPath: directory.path).isEmpty)
    }

    @Test("someone without the QR code's secret gets nothing written, and no password is accepted")
    func withoutTheSecret() async throws {
        let source = try temporaryFile(Data("backup".utf8)), destination = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: source) }
        let receiver = DeviceTransferReceiver()
        let forged = DeviceTransferInvite(
            receiverKey: receiver.receiverKey, secret: Data(repeating: 9, count: 32),
            endpoints: [.init(host: "192.168.1.2", port: 5000)])
        let pipe = Pipe()
        async let attacker: Void = {
            // The attacker cannot open READY with the wrong secret either.
            _ = try? await DeviceTransferSender.send(file: source, password: "x", invite: forged, over: pipe.sender)
            pipe.sender.close()
        }()
        await #expect(throws: DeviceTransferError.closed) {
            _ = try await receiver.receive(over: pipe.receiver, into: destination)
        }
        await attacker
        #expect(!FileManager.default.fileExists(atPath: destination.path))
    }

    @Test("the old phone sends nothing secret to a phone that is not the one in the QR code")
    func notTheScannedPhone() async throws {
        let source = try temporaryFile(Data("backup".utf8)), destination = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: source) }
        let scanned = DeviceTransferReceiver()
        let impostor = DeviceTransferReceiver(privateKey: PrivateKey.generate(), secret: scanned.secret)
        let invite = scanned.invite(endpoints: [.init(host: "192.168.1.2", port: 5000)])
        let pipe = Pipe()
        async let other = try? await impostor.receive(over: pipe.receiver, into: destination)
        await #expect(throws: DeviceTransferError.notAuthentic) {
            try await DeviceTransferSender.send(file: source, password: "secret", invite: invite, over: pipe.sender)
        }
        // The hello and nothing after it.
        #expect(pipe.toReceiver.total == DeviceTransferHello.length)
        pipe.sender.close()
        #expect(await other == nil)
    }

    @Test("a transfer cut short leaves no file")
    func cutShort() async throws {
        let destination = try temporaryFile()
        let receiver = DeviceTransferReceiver()
        let pipe = Pipe()
        async let received = try? await receiver.receive(over: pipe.receiver, into: destination)

        let priv = PrivateKey.generate()
        let senderKey = priv.publicKey.serialize()
        let keys = DeviceTransferKeys(
            shared: priv.keyAgreement(with: try PublicKey(receiver.receiverKey)), secret: receiver.secret,
            receiverKey: receiver.receiverKey, senderKey: senderKey)
        var cipher = DeviceTransferCipher(key: keys.senderToReceiver)
        try await pipe.sender.write(DeviceTransferHello.make(senderKey: senderKey))
        try await pipe.sender.write(
            try cipher.seal(.offer, DeviceTransferCipher.bigEndian(UInt64(10)) + DeviceTransferCipher.bigEndian(UInt64(0)) + Data("p".utf8)))
        try await pipe.sender.write(try cipher.seal(.chunk, Data([1, 2, 3])))
        try await pipe.sender.write(try cipher.seal(.end, Data()))
        #expect(await received == nil)
        #expect(!FileManager.default.fileExists(atPath: destination.path))
    }

    @Test("an offer over the limit is refused before anything is written")
    func tooLarge() async throws {
        let destination = try temporaryFile()
        let receiver = DeviceTransferReceiver()
        let pipe = Pipe()
        async let received: Void = {
            await #expect(throws: DeviceTransferError.tooLarge) {
                _ = try await receiver.receive(over: pipe.receiver, into: destination)
            }
        }()
        let priv = PrivateKey.generate()
        let senderKey = priv.publicKey.serialize()
        let keys = DeviceTransferKeys(
            shared: priv.keyAgreement(with: try PublicKey(receiver.receiverKey)), secret: receiver.secret,
            receiverKey: receiver.receiverKey, senderKey: senderKey)
        var cipher = DeviceTransferCipher(key: keys.senderToReceiver)
        try await pipe.sender.write(DeviceTransferHello.make(senderKey: senderKey))
        try await pipe.sender.write(
            try cipher.seal(
                .offer,
                DeviceTransferCipher.bigEndian(DeviceTransferLimits.maxFileBytes + 1) + DeviceTransferCipher.bigEndian(UInt64(0))
                    + Data("p".utf8)))
        await received
        #expect(!FileManager.default.fileExists(atPath: destination.path))
    }
}
