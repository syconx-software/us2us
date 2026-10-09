import Foundation

@testable import Us2usKit

// JSON shapes of spec/test-vectors/README.md. Binary values are lowercase hex.

struct VectorFile<Vector: Codable>: Codable {
    var version = 1
    var generator: String
    var category: String
    var vectors: [Vector]
}

struct UsernameVector: Codable {
    var input: String
    var normalized: String?
    var hash: String?

    /// `normalized` and `hash` are written as explicit nulls for invalid input.
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(input, forKey: .input)
        try container.encode(normalized, forKey: .normalized)
        try container.encode(hash, forKey: .hash)
    }
}

struct PowVector: Codable {
    var token: String
    var nonce: String
    var difficulty: Int
    var valid: Bool
}

struct PaddingVector: Codable {
    var plaintext: String
    var padded: String
}

struct AttachmentPaddingVector: Codable {
    var size: UInt64
    var paddedSize: UInt64
}

struct AttachmentVector: Codable {
    var plaintext: String
    var key: String
    var iv: String
    var blob: String
    var digest: String
}

struct SealedVector: Codable {
    struct Content: Codable {
        var senderAccountId: String
        var senderDeviceId: UInt32
        var type: UInt32
        var ciphertext: String
        var senderUsername: String?
    }

    var recipientIdentityKeyPair: String
    var envelope: String
    var sealedContent: Content
    /// Absent means valid.
    var valid: Bool?
}

struct ContentVector: Codable {
    var caseName: String
    var protobuf: String

    enum CodingKeys: String, CodingKey {
        case caseName = "case"
        case protobuf
    }
}

struct VaultVector: Codable {
    var code: String
    var deviceSecret: String
    var salt: String
    var iterations: Int
    var nonce: String
    var dek: String
    var vault: String
}

struct BackupVector: Codable {
    var password: String
    var salt: String
    var iterations: Int
    var nonce: String
    var accountBackup: String
    var file: String
}

struct SecretCodeVector: Codable {
    var code: String
    /// `null` (acceptable), `format`, `repeated`, `sequence` or `common` (§5.2).
    var problem: String?

    /// `problem` is written as an explicit null for acceptable codes.
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(code, forKey: .code)
        try container.encode(problem, forKey: .problem)
    }
}

struct BackupPasswordVector: Codable {
    var password: String
    /// `null` (acceptable), `too_short`, `common`, `repeated` or `sequence` (§5.5).
    var problem: String?

    /// `problem` is written as an explicit null for acceptable passwords.
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(password, forKey: .password)
        try container.encode(problem, forKey: .problem)
    }
}

extension SecretCodeError {
    /// The rule's name in PROTOCOL.md §5.2 and `secret-code.json`.
    var specName: String {
        switch self {
        case .notSixDigits: return "format"
        case .repeatedDigit: return "repeated"
        case .sequence: return "sequence"
        case .tooCommon: return "common"
        case .mismatch: return "mismatch"  // not a rule; never in a vector
        }
    }
}

extension SecretCode {
    /// The first §5.2 rule `code` breaks, by its spec name, or `nil`.
    static func problem(of code: String) -> String? {
        do {
            try validate(code)
            return nil
        } catch {
            return error.specName
        }
    }
}

extension BackupPasswordError {
    /// The rule's name in PROTOCOL.md §5.5 and `backup-password.json`.
    var specName: String {
        switch self {
        case .tooShort: return "too_short"
        case .tooCommon: return "common"
        case .repeatedCharacter: return "repeated"
        case .simpleSequence: return "sequence"
        case .mismatch: return "mismatch"  // not a rule; never in a vector
        }
    }
}

extension BackupPassword {
    /// The first §5.5 rule `password` breaks, by its spec name, or `nil`.
    static func problem(of password: String) -> String? {
        do {
            try validate(password)
            return nil
        } catch {
            return error.specName
        }
    }
}

struct KeySlotVector: Codable {
    var dek: String
    var slotId: String
    var ck: String
    var nonce: String
    var slotFile: String
}

struct ItemVector: Codable {
    var ck: String
    var itemId: String
    var nonce: String
    var plaintext: String
    var item: String
}

struct FingerprintVector: Codable {
    var localId: String
    var localKey: String
    var remoteId: String
    var remoteKey: String
    var displayable: String
    var scannable: String
}

enum VectorCategory: String, CaseIterable, Sendable {
    case username, pow, padding
    case attachmentPadding = "attachment-padding"
    case attachment, sealed, content, vault, backup
    case secretCode = "secret-code"
    case backupPassword = "backup-password"
    case keyslot, item, fingerprint
}

/// Reading and writing `spec/test-vectors/<generator>/<category>.json`.
enum VectorStore {
    static func url(generator: String, category: VectorCategory) -> URL {
        RepositoryPaths.testVectors.appendingPathComponent(generator).appendingPathComponent(
            "\(category.rawValue).json")
    }

    static func exists(generator: String, _ category: VectorCategory) -> Bool {
        FileManager.default.fileExists(atPath: url(generator: generator, category: category).path)
    }

    static func read<V: Codable>(_ type: V.Type, generator: String, category: VectorCategory) throws -> [V] {
        let data = try Data(contentsOf: url(generator: generator, category: category))
        let file = try JSONDecoder().decode(VectorFile<V>.self, from: data)
        precondition(file.version == 1 && file.category == category.rawValue, "unexpected vector file header")
        return file.vectors
    }

    /// Writes deterministically (sorted keys) and only when the content changed,
    /// so regenerating does not touch unchanged files.
    static func write<V: Codable>(_ vectors: [V], category: VectorCategory) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let file = VectorFile(generator: "swift", category: category.rawValue, vectors: vectors)
        let data = try encoder.encode(file) + Data("\n".utf8)
        let url = url(generator: "swift", category: category)
        if (try? Data(contentsOf: url)) == data { return }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }
}

extension String {
    /// Hex field of a vector as bytes; malformed hex fails the test loudly.
    var hexBytes: Data {
        guard let data = Data(hexString: self) else { preconditionFailure("malformed hex in vector: \(prefix(16))…") }
        return data
    }
}
