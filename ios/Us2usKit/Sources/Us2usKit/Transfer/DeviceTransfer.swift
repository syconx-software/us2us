import CryptoKit
import Foundation
import LibSignalClient

// Direct transfer to a new phone (PROTOCOL.md §5.6): the invitation in the QR
// code, the keys, the frames, and both sides of the exchange over any stream.
// The network itself is the app's (`DeviceTransferNetwork` in Us2usRuntime).

/// What the new phone shows as a QR code.
public struct DeviceTransferInvite: Equatable, Sendable {
    public struct Endpoint: Equatable, Sendable {
        public let host: String
        public let port: UInt16

        public init(host: String, port: UInt16) {
            self.host = host
            self.port = port
        }
    }

    static let prefix = "us2us-transfer:1:"
    public static let maxEndpoints = 4

    public let receiverKey: Data
    public let secret: Data
    public let endpoints: [Endpoint]

    public init(receiverKey: Data, secret: Data, endpoints: [Endpoint]) {
        self.receiverKey = receiverKey
        self.secret = secret
        self.endpoints = endpoints
    }

    /// `nil` for anything but a valid invitation of this version.
    public init?(text: String) {
        guard text.hasPrefix(Self.prefix) else { return nil }
        let parts = text.dropFirst(Self.prefix.count).split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 3,
            let key = Data(base64URLEncoded: String(parts[0])), Self.isKey(key),
            let secret = Data(base64URLEncoded: String(parts[1])), secret.count == 32
        else { return nil }
        let list = parts[2].split(separator: ",", omittingEmptySubsequences: false)
        guard (1...Self.maxEndpoints).contains(list.count) else { return nil }
        var endpoints: [Endpoint] = []
        for item in list {
            let pieces = item.split(separator: ":", omittingEmptySubsequences: false)
            guard pieces.count == 2, Self.isIPv4(pieces[0]), let port = UInt16(pieces[1]), port > 0 else { return nil }
            endpoints.append(Endpoint(host: String(pieces[0]), port: port))
        }
        self.init(receiverKey: key, secret: secret, endpoints: endpoints)
    }

    public var text: String {
        let list = endpoints.map { "\($0.host):\($0.port)" }.joined(separator: ",")
        return Self.prefix + receiverKey.base64URLEncodedString() + ":" + secret.base64URLEncodedString() + ":" + list
    }

    static func isKey(_ key: Data) -> Bool {
        key.count == 33 && key.first == 0x05 && (try? PublicKey(key)) != nil
    }

    private static func isIPv4(_ host: Substring) -> Bool {
        let octets = host.split(separator: ".", omittingEmptySubsequences: false)
        return octets.count == 4
            && octets.allSatisfy { octet in
                octet.count <= 3 && octet.allSatisfy(\.isASCIIDigit) && (UInt8(octet) != nil)
            }
    }
}

extension Character {
    fileprivate var isASCIIDigit: Bool { isASCII && isNumber }
}

public enum DeviceTransferError: Error, Equatable, Sendable {
    /// Not the expected opening, or a frame out of order or over a limit.
    case badMessage
    /// A frame did not open: not the phone in the QR code, or not its secret.
    case notAuthentic
    case tooLarge
    /// The connection ended before the exchange did.
    case closed
    case timedOut
    /// The new phone has no room for what was offered, and said so.
    case noSpace
}

public enum DeviceTransferFrame: UInt8, Sendable {
    case ready = 1
    case offer = 2
    case chunk = 3
    case end = 4
    case received = 5
    /// One `TransferRecord` of the history stage.
    case record = 6
    case accept = 7
    case refused = 8
}

public enum DeviceTransferLimits {
    public static let maxFrameBytes = 1 << 20
    public static let chunkBytes = 256 * 1024
    public static let maxFileBytes: UInt64 = 64 * 1024 * 1024
    /// Room a new phone keeps free besides what it was offered.
    public static let spareBytes: UInt64 = 100 * 1024 * 1024
    /// `REFUSED`'s reason: no room for what was offered.
    static let refusedNoSpace: UInt8 = 1
}

/// What the old phone sends after the account: the history stage's `TransferRecord`s.
public protocol DeviceTransferHistorySource: Sendable {
    /// About how many bytes `export` will emit, for the new phone's room and both meters.
    func estimatedBytes() async throws -> UInt64
    /// Emits every record in order; a failing `emit` ends it.
    func export(_ emit: @Sendable (Data) async throws -> Void) async throws
}

/// Where the new phone keeps the records until a vault can take them.
public protocol DeviceTransferHistorySink: Sendable {
    /// Whether what was offered fits on this phone.
    func canHold(bytes: UInt64) -> Bool
    func append(_ record: Data) throws
    /// All records arrived and are stored.
    func finish() throws
}

/// One direction of the channel: frames in order, each under its own nonce.
struct DeviceTransferCipher {
    private let key: SymmetricKey
    private var counter: UInt64 = 0

    init(key: Data) {
        self.key = SymmetricKey(data: key)
    }

    /// `uint32_be(n) ‖ AES-256-GCM(key, nonce, type ‖ body)`; n bytes follow the length.
    mutating func seal(_ type: DeviceTransferFrame, _ body: Data) throws -> Data {
        let sealed = try AES.GCM.seal(Data([type.rawValue]) + body, using: key, nonce: nonce())
        counter += 1
        let ciphertext = sealed.ciphertext + sealed.tag
        guard ciphertext.count <= DeviceTransferLimits.maxFrameBytes else { throw DeviceTransferError.tooLarge }
        return Self.bigEndian(UInt32(ciphertext.count)) + ciphertext
    }

    /// The bytes after the length.
    mutating func open(_ ciphertext: Data) throws -> (type: DeviceTransferFrame, body: Data) {
        guard ciphertext.count >= 17 else { throw DeviceTransferError.badMessage }
        let plain: Data
        do {
            let box = try AES.GCM.SealedBox(
                nonce: nonce(), ciphertext: ciphertext.dropLast(16), tag: ciphertext.suffix(16))
            plain = try AES.GCM.open(box, using: key)
        } catch {
            throw DeviceTransferError.notAuthentic
        }
        counter += 1
        guard let type = plain.first.flatMap(DeviceTransferFrame.init(rawValue:)) else {
            throw DeviceTransferError.badMessage
        }
        return (type, Data(plain.dropFirst()))
    }

    private func nonce() throws -> AES.GCM.Nonce {
        try AES.GCM.Nonce(data: Self.bigEndian(UInt32(0)) + Self.bigEndian(counter))
    }

    static func bigEndian<T: FixedWidthInteger>(_ value: T) -> Data {
        withUnsafeBytes(of: value.bigEndian) { Data($0) }
    }
}

/// The two directions' keys of one connection.
struct DeviceTransferKeys {
    let senderToReceiver: Data
    let receiverToSender: Data

    init(shared: Data, secret: Data, receiverKey: Data, senderKey: Data) {
        let okm = HKDFSHA256.derive(
            inputKeyMaterial: shared + secret, salt: receiverKey + senderKey, info: Data("BRP/transfer/v1".utf8), length: 64)
        senderToReceiver = Data(okm.prefix(32))
        receiverToSender = Data(okm.suffix(32))
    }
}

/// The connection between the two phones, however it is carried.
public protocol DeviceTransferStream: Sendable {
    /// Exactly `count` bytes; throws `DeviceTransferError.closed` when they never come.
    func read(_ count: Int) async throws -> Data
    func write(_ data: Data) async throws
    func close()
}

enum DeviceTransferHello {
    static let magic = Data("BRPT".utf8) + Data([0x01])
    static let length = magic.count + 33

    static func make(senderKey: Data) -> Data { magic + senderKey }

    static func senderKey(in hello: Data) throws -> Data {
        guard hello.count == length, hello.prefix(magic.count) == magic else { throw DeviceTransferError.badMessage }
        let key = Data(hello.suffix(33))
        guard DeviceTransferInvite.isKey(key) else { throw DeviceTransferError.badMessage }
        return key
    }
}

private func readFrame(_ stream: any DeviceTransferStream, _ cipher: inout DeviceTransferCipher) async throws
    -> (type: DeviceTransferFrame, body: Data)
{
    let length = try await stream.read(4).reduce(0) { ($0 << 8) | Int($1) }
    guard length <= DeviceTransferLimits.maxFrameBytes else { throw DeviceTransferError.tooLarge }
    return try cipher.open(try await stream.read(length))
}

/// The old phone's side. Sends nothing secret before the new phone has proved
/// that it holds the key from the QR code.
public enum DeviceTransferSender {
    public static func send(
        file: URL, password: String, invite: DeviceTransferInvite, over stream: any DeviceTransferStream,
        history: (any DeviceTransferHistorySource)? = nil, progress: @escaping @Sendable (Double) -> Void = { _ in }
    ) async throws {
        let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
        let size = (attributes[.size] as? NSNumber)?.uint64Value ?? 0
        guard size <= DeviceTransferLimits.maxFileBytes else { throw DeviceTransferError.tooLarge }

        let priv = PrivateKey.generate()
        let senderKey = priv.publicKey.serialize()
        let receiver: PublicKey
        do {
            receiver = try PublicKey(invite.receiverKey)
        } catch {
            throw DeviceTransferError.badMessage
        }
        let keys = DeviceTransferKeys(
            shared: priv.keyAgreement(with: receiver), secret: invite.secret, receiverKey: invite.receiverKey,
            senderKey: senderKey)
        var outgoing = DeviceTransferCipher(key: keys.senderToReceiver)
        var incoming = DeviceTransferCipher(key: keys.receiverToSender)

        try await stream.write(DeviceTransferHello.make(senderKey: senderKey))
        guard try await readFrame(stream, &incoming).type == .ready else { throw DeviceTransferError.badMessage }

        let historyBytes = try await history?.estimatedBytes() ?? 0
        try await stream.write(
            try outgoing.seal(
                .offer,
                DeviceTransferCipher.bigEndian(size) + DeviceTransferCipher.bigEndian(historyBytes) + Data(password.utf8)))
        switch try await readFrame(stream, &incoming).type {
        case .accept: break
        case .refused: throw DeviceTransferError.noSpace
        default: throw DeviceTransferError.badMessage
        }

        let meter = DeviceTransferMeter(total: size + historyBytes, report: progress)
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var sent: UInt64 = 0
        while sent < size {
            try Task.checkCancellation()
            guard let chunk = try handle.read(upToCount: DeviceTransferLimits.chunkBytes), !chunk.isEmpty else {
                throw DeviceTransferError.closed
            }
            try await stream.write(try outgoing.seal(.chunk, chunk))
            sent += UInt64(chunk.count)
            meter.add(chunk.count)
        }
        if let history {
            let sealing = DeviceTransferSealing(outgoing)
            try await history.export { record in
                try Task.checkCancellation()
                try await stream.write(try sealing.seal(.record, record))
                meter.add(record.count)
            }
            outgoing = sealing.current
        }
        try await stream.write(try outgoing.seal(.end, Data()))
        guard try await readFrame(stream, &incoming).type == .received else { throw DeviceTransferError.badMessage }
        meter.finish()
    }
}

/// The sending direction's cipher while a `@Sendable` closure writes with it, one record at a time.
private final class DeviceTransferSealing: @unchecked Sendable {
    private let lock = NSLock()
    private var cipher: DeviceTransferCipher

    init(_ cipher: DeviceTransferCipher) {
        self.cipher = cipher
    }

    func seal(_ type: DeviceTransferFrame, _ body: Data) throws -> Data {
        try lock.withLock { try cipher.seal(type, body) }
    }

    var current: DeviceTransferCipher { lock.withLock { cipher } }
}

/// How far an offer got. An estimate can be short: it never reads as finished before it is.
private final class DeviceTransferMeter: @unchecked Sendable {
    private let lock = NSLock()
    private let total: UInt64
    private var done: UInt64 = 0
    private var reported = -1
    private let report: @Sendable (Double) -> Void

    init(total: UInt64, report: @escaping @Sendable (Double) -> Void) {
        self.total = total
        self.report = report
    }

    /// Reports whole percents: a history can be hundreds of thousands of records.
    func add(_ bytes: Int) {
        let fraction = lock.withLock { () -> Double? in
            done += UInt64(bytes)
            let fraction = total == 0 ? 0 : min(0.99, Double(done) / Double(total))
            let percent = Int(fraction * 100)
            guard percent != reported else { return nil }
            reported = percent
            return fraction
        }
        if let fraction { report(fraction) }
    }

    func finish() {
        report(1)
    }
}

/// The new phone's side of one connection. The key pair and the secret live as
/// long as the invitation; every connection gets keys of its own.
public struct DeviceTransferReceiver: @unchecked Sendable {
    private let priv: PrivateKey
    public let secret: Data
    public let receiverKey: Data

    /// A fresh key pair and secret for one invitation.
    public init() {
        self.init(privateKey: PrivateKey.generate(), secret: SecureRandom.bytes(32))
    }

    init(privateKey: PrivateKey, secret: Data) {
        priv = privateKey
        self.secret = secret
        receiverKey = privateKey.publicKey.serialize()
    }

    public func invite(endpoints: [DeviceTransferInvite.Endpoint]) -> DeviceTransferInvite {
        DeviceTransferInvite(receiverKey: receiverKey, secret: secret, endpoints: endpoints)
    }

    /// Writes the file to `destination`, which is created with `attributes`, keeps the
    /// history in `history`, and returns the file's password. Anything wrong throws and
    /// leaves no file behind (the history is its owner's to remove). `authenticated` is
    /// called once the other side has proved it read the QR code.
    public func receive(
        over stream: any DeviceTransferStream, into destination: URL, attributes: [FileAttributeKey: Any]? = nil,
        history: (any DeviceTransferHistorySink)? = nil, authenticated: @Sendable () -> Void = {},
        progress: @escaping @Sendable (Double) -> Void = { _ in }
    ) async throws -> String {
        let senderKey = try DeviceTransferHello.senderKey(in: try await stream.read(DeviceTransferHello.length))
        let keys = DeviceTransferKeys(
            shared: priv.keyAgreement(with: try PublicKey(senderKey)), secret: secret, receiverKey: receiverKey,
            senderKey: senderKey)
        var outgoing = DeviceTransferCipher(key: keys.receiverToSender)
        var incoming = DeviceTransferCipher(key: keys.senderToReceiver)
        try await stream.write(try outgoing.seal(.ready, Data()))

        let offer = try await readFrame(stream, &incoming)
        guard offer.type == .offer, offer.body.count > 16 else { throw DeviceTransferError.badMessage }
        let size = offer.body.prefix(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        let historyBytes = offer.body.dropFirst(8).prefix(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        guard size <= DeviceTransferLimits.maxFileBytes else { throw DeviceTransferError.tooLarge }
        guard let password = String(data: offer.body.dropFirst(16), encoding: .utf8) else {
            throw DeviceTransferError.badMessage
        }
        authenticated()

        let (needed, overflow) = size.addingReportingOverflow(historyBytes)
        guard !overflow else { throw DeviceTransferError.tooLarge }
        if let history, !history.canHold(bytes: needed) {
            try await stream.write(try outgoing.seal(.refused, Data([DeviceTransferLimits.refusedNoSpace])))
            throw DeviceTransferError.noSpace
        }
        try await stream.write(try outgoing.seal(.accept, Data()))

        guard FileManager.default.createFile(atPath: destination.path, contents: nil, attributes: attributes) else {
            throw CocoaError(.fileWriteUnknown)
        }
        do {
            let meter = DeviceTransferMeter(total: needed, report: progress)
            let handle = try FileHandle(forWritingTo: destination)
            defer { try? handle.close() }
            var written: UInt64 = 0
            var inHistory = false
            receiving: while true {
                try Task.checkCancellation()
                let frame = try await readFrame(stream, &incoming)
                switch frame.type {
                case .chunk where !inHistory:
                    written += UInt64(frame.body.count)
                    guard written <= size else { throw DeviceTransferError.tooLarge }
                    try handle.write(contentsOf: frame.body)
                    meter.add(frame.body.count)
                case .record:
                    // The whole file comes first.
                    guard written == size else { throw DeviceTransferError.badMessage }
                    inHistory = true
                    try history?.append(frame.body)
                    meter.add(frame.body.count)
                case .end:
                    break receiving
                default:
                    throw DeviceTransferError.badMessage
                }
            }
            guard written == size else { throw DeviceTransferError.closed }
            try handle.synchronize()
            try history?.finish()
        } catch {
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
        try await stream.write(try outgoing.seal(.received, Data()))
        return password
    }
}
