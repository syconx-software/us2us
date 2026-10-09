import CommonCrypto
import CryptoKit
import Foundation

public enum AttachmentError: Error, Equatable, Sendable {
    case invalidKey
    /// The blob is shorter than `iv ‖ one block ‖ mac` or not block aligned.
    case malformedBlob
    /// Digest or HMAC mismatch: the blob is corrupt or was tampered with.
    case integrityCheckFailed
    /// More or fewer plaintext bytes than announced, or `size` exceeds the
    /// decrypted length.
    case sizeMismatch
    case cryptorFailure(Int32)
    case io(String)
}

/// Size padding of attachments (PROTOCOL.md §7), so blob sizes only reveal a
/// bucket that grows in ~5 % steps.
public enum AttachmentPadding {
    static let minimumSize: UInt64 = 541

    /// `P(n)`: 541 for n ≤ 541, else the smallest `s_i ≥ n` of
    /// `s_0 = 541, s_{i+1} = s_i + max(1, floor(s_i / 20))`.
    public static func paddedSize(for size: UInt64) -> UInt64 {
        var bucket = minimumSize
        while bucket < size {
            bucket += max(1, bucket / 20)
        }
        return bucket
    }
}

/// The result of encrypting an attachment; the values go into `Attachment`.
public struct EncryptedAttachmentInfo: Sendable, Equatable {
    /// 64 bytes: AES-256 key ‖ HMAC-SHA256 key.
    public let key: Data
    /// SHA-256 of the blob.
    public let digest: Data
    /// Plaintext size before padding (`Attachment.size`).
    public let plaintextSize: UInt64
    public let blobSize: UInt64
}

/// Attachment encryption (PROTOCOL.md §7):
///
///     blob   = iv ‖ AES-256-CBC-PKCS7(aesKey, iv, plaintext ‖ 0x00·(P(n)−n)) ‖ HMAC-SHA256(macKey, iv ‖ ciphertext)
///     digest = SHA-256(blob)
///
/// Encryption and decryption stream in fixed-size chunks, so large videos
/// never have to fit in memory.
public enum AttachmentCipher {
    public static let keyLength = 64
    static let ivLength = 16
    static let macLength = 32
    static let blockLength = kCCBlockSizeAES128
    static let chunkLength = 64 * 1024

    /// Total blob size for a plaintext of `size` bytes, to check upload limits
    /// before encrypting.
    public static func blobSize(forPlaintextSize size: UInt64) -> UInt64 {
        let padded = AttachmentPadding.paddedSize(for: size)
        let ciphertext = (padded / UInt64(blockLength) + 1) * UInt64(blockLength)  // PKCS7 always pads
        return UInt64(ivLength) + ciphertext + UInt64(macLength)
    }

    // MARK: In memory

    /// Encrypts with a fresh random key and IV.
    public static func encrypt(_ plaintext: Data) throws -> (blob: Data, info: EncryptedAttachmentInfo) {
        try encrypt(plaintext, key: SecureRandom.bytes(keyLength), iv: SecureRandom.bytes(ivLength))
    }

    static func encrypt(_ plaintext: Data, key: Data, iv: Data) throws -> (blob: Data, info: EncryptedAttachmentInfo) {
        var blob = Data()
        let info = try encrypt(
            source: DataSource(plaintext), key: key, iv: iv, sink: { blob.append($0) })
        return (blob, info)
    }

    /// Verifies digest and HMAC in constant time, then decrypts and truncates
    /// to `size`.
    public static func decrypt(_ blob: Data, key: Data, digest: Data, size: UInt64) throws -> Data {
        var plaintext = Data()
        try decrypt(source: DataSource(blob), key: key, digest: digest, size: size, sink: { plaintext.append($0) })
        return plaintext
    }

    // MARK: Files

    /// Encrypts the file at `input` into a new file at `output`. On failure
    /// no partial blob is left behind.
    public static func encryptFile(at input: URL, to output: URL) throws -> EncryptedAttachmentInfo {
        let source = try FileSource(url: input)
        defer { source.close() }
        return try FileSink.write(to: output) { sink in
            try encrypt(
                source: source, key: SecureRandom.bytes(keyLength), iv: SecureRandom.bytes(ivLength),
                sink: sink.write)
        }
    }

    /// Verifies the blob file at `input` (first pass), then writes the
    /// plaintext to `output` (second pass). Nothing is written if verification
    /// fails, and a failure during decryption removes the partial output.
    public static func decryptFile(at input: URL, to output: URL, key: Data, digest: Data, size: UInt64) throws {
        let source = try FileSource(url: input)
        defer { source.close() }
        try verify(source: source, key: key, digest: digest)
        try FileSink.write(to: output) { sink in
            try decryptVerified(source: source, key: key, size: size, sink: sink.write)
        }
    }

    // MARK: Core

    static func encrypt(
        source: some ByteSource, key: Data, iv: Data, sink: (Data) throws -> Void
    ) throws -> EncryptedAttachmentInfo {
        guard key.count == keyLength, iv.count == ivLength else { throw AttachmentError.invalidKey }
        let plaintextSize = source.length
        let paddedSize = AttachmentPadding.paddedSize(for: plaintextSize)
        let cryptor = try AESCBC(operation: CCOperation(kCCEncrypt), key: key.prefix(32), iv: iv)
        var mac = HMAC<SHA256>(key: SymmetricKey(data: key.suffix(32)))
        var digest = SHA256()
        var blobSize: UInt64 = 0

        func emit(_ bytes: Data, authenticated: Bool) throws {
            guard !bytes.isEmpty else { return }
            if authenticated { mac.update(data: bytes) }
            digest.update(data: bytes)
            blobSize += UInt64(bytes.count)
            try sink(bytes)
        }

        try emit(iv, authenticated: true)
        var offset: UInt64 = 0
        while offset < plaintextSize {
            let chunk = try source.read(offset: offset, count: Int(min(UInt64(chunkLength), plaintextSize - offset)))
            offset += UInt64(chunk.count)
            try emit(try cryptor.update(chunk), authenticated: true)
        }
        var remainingPadding = paddedSize - plaintextSize
        while remainingPadding > 0 {
            let count = Int(min(UInt64(chunkLength), remainingPadding))
            remainingPadding -= UInt64(count)
            try emit(try cryptor.update(Data(count: count)), authenticated: true)
        }
        try emit(try cryptor.finish(), authenticated: true)
        try emit(Data(mac.finalize()), authenticated: false)

        return EncryptedAttachmentInfo(
            key: key, digest: Data(digest.finalize()), plaintextSize: plaintextSize, blobSize: blobSize)
    }

    static func decrypt(
        source: some ByteSource, key: Data, digest: Data, size: UInt64, sink: (Data) throws -> Void
    ) throws {
        try verify(source: source, key: key, digest: digest)
        try decryptVerified(source: source, key: key, size: size, sink: sink)
    }

    /// Checks the blob digest and the HMAC, both in constant time.
    static func verify(source: some ByteSource, key: Data, digest expectedDigest: Data) throws {
        guard key.count == keyLength else { throw AttachmentError.invalidKey }
        let length = source.length
        let minimum = UInt64(ivLength + blockLength + macLength)
        guard length >= minimum, (length - UInt64(ivLength + macLength)) % UInt64(blockLength) == 0 else {
            throw AttachmentError.malformedBlob
        }
        let macOffset = length - UInt64(macLength)
        var mac = HMAC<SHA256>(key: SymmetricKey(data: key.suffix(32)))
        var digest = SHA256()
        var offset: UInt64 = 0
        while offset < macOffset {
            let chunk = try source.read(offset: offset, count: Int(min(UInt64(chunkLength), macOffset - offset)))
            offset += UInt64(chunk.count)
            mac.update(data: chunk)
            digest.update(data: chunk)
        }
        let theirMac = try source.read(offset: macOffset, count: macLength)
        digest.update(data: theirMac)

        // Evaluate both comparisons before branching so timing does not reveal which failed.
        let digestMatches = ConstantTime.equals(Data(digest.finalize()), expectedDigest)
        let macMatches = ConstantTime.equals(Data(mac.finalize()), theirMac)
        guard digestMatches && macMatches else { throw AttachmentError.integrityCheckFailed }
    }

    /// Decrypts an already verified blob, writing the first `size` plaintext bytes.
    private static func decryptVerified(
        source: some ByteSource, key: Data, size: UInt64, sink: (Data) throws -> Void
    ) throws {
        let iv = try source.read(offset: 0, count: ivLength)
        let cryptor = try AESCBC(operation: CCOperation(kCCDecrypt), key: key.prefix(32), iv: iv)
        let end = source.length - UInt64(macLength)
        var remaining = size
        func deliver(_ plaintext: Data) throws {
            let take = Int(min(UInt64(plaintext.count), remaining))
            remaining -= UInt64(take)
            if take > 0 { try sink(plaintext.prefix(take)) }
        }
        var offset = UInt64(ivLength)
        while offset < end {
            let chunk = try source.read(offset: offset, count: Int(min(UInt64(chunkLength), end - offset)))
            offset += UInt64(chunk.count)
            try deliver(try cryptor.update(chunk))
        }
        try deliver(try cryptor.finish())
        guard remaining == 0 else { throw AttachmentError.sizeMismatch }
    }
}

// MARK: - CommonCrypto AES-256-CBC with PKCS#7

private final class AESCBC {
    private var cryptor: CCCryptorRef?

    init(operation: CCOperation, key: Data, iv: Data) throws {
        let status = key.withUnsafeBytes { keyBuffer in
            iv.withUnsafeBytes { ivBuffer in
                CCCryptorCreate(
                    operation, CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding),
                    keyBuffer.baseAddress, kCCKeySizeAES256, ivBuffer.baseAddress, &cryptor)
            }
        }
        guard status == kCCSuccess else { throw AttachmentError.cryptorFailure(status) }
    }

    deinit {
        CCCryptorRelease(cryptor)
    }

    func update(_ input: Data) throws -> Data {
        var output = Data(count: CCCryptorGetOutputLength(cryptor, input.count, false))
        var moved = 0
        let status = output.withUnsafeMutableBytes { outBuffer in
            input.withUnsafeBytes { inBuffer in
                CCCryptorUpdate(
                    cryptor, inBuffer.baseAddress, input.count, outBuffer.baseAddress, outBuffer.count, &moved)
            }
        }
        guard status == kCCSuccess else { throw AttachmentError.cryptorFailure(status) }
        return output.prefix(moved)
    }

    func finish() throws -> Data {
        var output = Data(count: CCCryptorGetOutputLength(cryptor, 0, true))
        var moved = 0
        let status = output.withUnsafeMutableBytes { outBuffer in
            CCCryptorFinal(cryptor, outBuffer.baseAddress, outBuffer.count, &moved)
        }
        // A verified blob with bad PKCS#7 padding means the sender is broken.
        guard status == kCCSuccess else { throw AttachmentError.cryptorFailure(status) }
        return output.prefix(moved)
    }
}

// MARK: - Byte sources and sinks

protocol ByteSource {
    var length: UInt64 { get }
    func read(offset: UInt64, count: Int) throws -> Data
}

private struct DataSource: ByteSource {
    let data: Data
    init(_ data: Data) { self.data = data }
    var length: UInt64 { UInt64(data.count) }
    func read(offset: UInt64, count: Int) throws -> Data {
        let start = data.startIndex + Int(offset)
        return data.subdata(in: start..<min(start + count, data.endIndex))
    }
}

private final class FileSource: ByteSource {
    private let handle: FileHandle
    let length: UInt64

    init(url: URL) throws {
        do {
            handle = try FileHandle(forReadingFrom: url)
            length = try handle.seekToEnd()
        } catch {
            throw AttachmentError.io(error.localizedDescription)
        }
    }

    func read(offset: UInt64, count: Int) throws -> Data {
        do {
            try handle.seek(toOffset: offset)
            let data = try handle.read(upToCount: count) ?? Data()
            guard data.count == count else { throw AttachmentError.io("unexpected end of file") }
            return data
        } catch let error as AttachmentError {
            throw error
        } catch {
            throw AttachmentError.io(error.localizedDescription)
        }
    }

    func close() { try? handle.close() }
}

private final class FileSink {
    private let handle: FileHandle
    private var isOpen = true

    private init(url: URL) throws {
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else {
            throw AttachmentError.io("cannot create \(url.lastPathComponent)")
        }
        do {
            handle = try FileHandle(forWritingTo: url)
        } catch {
            throw AttachmentError.io(error.localizedDescription)
        }
    }

    /// Creates `url`, lets `body` fill it and closes it. If anything fails,
    /// including the final close, the file is removed.
    static func write<Result>(to url: URL, _ body: (FileSink) throws -> Result) throws -> Result {
        let sink = try FileSink(url: url)
        do {
            let result = try body(sink)
            try sink.close()
            return result
        } catch {
            if sink.isOpen { try? sink.close() }
            try? FileManager.default.removeItem(at: url)
            throw error
        }
    }

    func write(_ data: Data) throws {
        do {
            try handle.write(contentsOf: data)
        } catch {
            throw AttachmentError.io(error.localizedDescription)
        }
    }

    private func close() throws {
        isOpen = false
        do {
            try handle.close()
        } catch {
            throw AttachmentError.io(error.localizedDescription)
        }
    }
}

extension AttachmentError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .integrityCheckFailed, .malformedBlob, .sizeMismatch:
            return Localizer.current.string(.attachmentIntegrity)
        case .invalidKey, .cryptorFailure, .io:
            return Localizer.current.string(.storageIntegrity)
        }
    }
}
