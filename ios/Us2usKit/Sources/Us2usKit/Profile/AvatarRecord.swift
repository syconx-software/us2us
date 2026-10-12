import Foundation

/// Why an avatar record could not be made or opened (PROTOCOL.md §8.10).
/// Every reader-side case means "no picture": the initial letter, no error
/// shown.
public enum AvatarRecordError: Error, Equatable, Sendable {
    /// The `ProfileAvatar` is longer than 49110 bytes (no size holds it).
    case tooLarge
    /// A version outside 1 … 2^63 − 1.
    case invalidVersion
    /// The length is not one of the four sizes, or the header is not
    /// `"BRPA" ‖ 0x01 ‖ version`.
    case malformedRecord
    /// AES-GCM refused the record: another username, another account, or
    /// tampering.
    case authenticationFailed
    /// The plaintext does not end in `0x80` followed by zeros.
    case malformedPadding
}

/// The key of one account's avatar record under one username (§8.10):
///
///     seed      = PBKDF2-HMAC-SHA256(UTF-8(normalizedUsername), "BRP/avatar/v1", 100000, 32)
///     avatarKey = HKDF-SHA256(ikm = seed, salt = accountId (16 bytes), info = "BRP/avatar/key/v1", 32)
///
/// The PBKDF2 salt differs from the username hash's (`BRP/username/v1`,
/// §3.2), so the relay cannot compute `seed` from the hash it stores.
public struct AvatarRecordKey: Sendable {
    public let accountId: AccountId
    let bytes: Data

    /// From a `seed` (``AvatarRecord/seed(for:)``) that may be cached per username.
    public init(seed: Data, accountId: AccountId) {
        self.accountId = accountId
        self.bytes = HKDFSHA256.derive(
            inputKeyMaterial: seed, salt: accountId.bytes, info: AvatarRecord.keyInfo, length: 32)
    }

    /// Both steps. The PBKDF2 step takes tens of milliseconds: call it off
    /// the main thread.
    public static func derive(username: NormalizedUsername, accountId: AccountId) -> AvatarRecordKey {
        var seed = AvatarRecord.seed(for: username)
        defer { seed.zeroize() }
        return AvatarRecordKey(seed: seed, accountId: accountId)
    }
}

/// The avatar record an account keeps on the relay while "Show in search" is
/// on (PROTOCOL.md §4.12, §8.10):
///
///     header    = "BRPA" ‖ 0x01 ‖ uint64_be(version)                       // 13 bytes
///     plaintext = ProfileAvatar ‖ 0x80 ‖ 0x00 * k                          // len(record) − 41 bytes
///     record    = header ‖ nonce(12) ‖ AES-256-GCM(avatarKey, nonce, aad = header ‖ accountId, plaintext) ‖ tag(16)
///
/// `len(record)` is the smallest of 8192, 16384, 32768 and 49152 that holds
/// the `ProfileAvatar` with its padding, so the relay learns only which of
/// the four sizes it is. The tag is checked by CryptoKit in constant time;
/// nothing here compares secret values otherwise.
public enum AvatarRecord {
    /// The only record lengths (§4.12).
    public static let sizes = [8192, 16384, 32768, 49152]
    public static var maxLength: Int { sizes[sizes.count - 1] }
    public static let headerLength = 13
    static let nonceLength = 12
    static let tagLength = 16
    /// Header, nonce and tag.
    public static let overhead = headerLength + nonceLength + tagLength
    /// Versions a writer may use and a reader accepts.
    public static let versions: ClosedRange<UInt64> = 1...UInt64(Int64.max)

    static let magic = Data("BRPA".utf8)
    static let formatVersion: UInt8 = 0x01
    static let seedSalt = Data("BRP/avatar/v1".utf8)
    static let seedIterations = 100_000
    static let keyInfo = Data("BRP/avatar/key/v1".utf8)

    /// The longest `ProfileAvatar` a record of `length` holds (one byte goes
    /// to the `0x80` marker): 8150, 16342, 32726, 49110.
    public static func capacity(ofLength length: Int) -> Int { length - overhead - 1 }

    /// The record length for a `ProfileAvatar` of `count` bytes; `nil` above 49110.
    public static func length(forProfileAvatarBytes count: Int) -> Int? {
        sizes.first { count <= capacity(ofLength: $0) }
    }

    /// `seed` of §8.10: PBKDF2-HMAC-SHA256 with the avatar salt. Slow (tens
    /// of milliseconds); call it off the main thread and cache it per
    /// username in memory only.
    public static func seed(for username: NormalizedUsername) -> Data {
        PBKDF2.sha256(password: Data(username.value.utf8), salt: seedSalt, iterations: seedIterations)
    }

    static func header(version: UInt64) -> Data {
        var header = magic
        header.append(formatVersion)
        header.appendBigEndian(version)
        return header
    }

    /// The version of a well-formed record: its length is one of the four,
    /// its header `"BRPA" ‖ 0x01` and its version in range. Readers check
    /// this before deriving anything (§8.10); `nil` otherwise.
    public static func version(of record: Data) -> UInt64? {
        guard sizes.contains(record.count) else { return nil }
        let bytes = [UInt8](record.prefix(headerLength))
        guard bytes.starts(with: magic), bytes[4] == formatVersion else { return nil }
        let version = bytes[5...].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        return versions.contains(version) ? version : nil
    }

    /// Seals a serialized `ProfileAvatar` (§8.10 "Record") with 12 fresh
    /// random nonce bytes.
    public static func seal(
        _ profileAvatar: Data, version: UInt64, key: AvatarRecordKey
    ) throws(AvatarRecordError) -> Data {
        try seal(profileAvatar, version: version, key: key, nonce: SecureRandom.bytes(nonceLength))
    }

    /// ``seal(_:version:key:)`` with a given 12-byte nonce, for the test
    /// vectors only (`@testable import`): a nonce used twice under one key
    /// (one username of one account) breaks AES-GCM, so this is not public.
    static func seal(
        _ profileAvatar: Data, version: UInt64, key: AvatarRecordKey, nonce: Data
    ) throws(AvatarRecordError) -> Data {
        guard versions.contains(version) else { throw .invalidVersion }
        guard let length = length(forProfileAvatarBytes: profileAvatar.count) else { throw .tooLarge }
        var plaintext = profileAvatar
        plaintext.append(0x80)
        plaintext.append(Data(count: length - overhead - plaintext.count))
        defer { plaintext.zeroize() }
        let header = header(version: version)
        let sealed: Data
        do {
            sealed = try AES256GCM.seal(
                plaintext, key: key.bytes, nonce: nonce, associatedData: header + key.accountId.bytes)
        } catch {
            throw .malformedRecord  // a nonce that is not 12 bytes
        }
        let record = header + nonce + sealed
        assert(record.count == length)
        return record
    }

    /// Seals a picture with a fresh random nonce; the initial letter has no
    /// record.
    public static func seal(
        _ picture: ProfilePicture, version: UInt64, key: AvatarRecordKey
    ) throws(AvatarRecordError) -> Data? {
        guard let serialized = picture.serializedProfileAvatar else { return nil }
        return try seal(serialized, version: version, key: key)
    }

    /// Opens a record of `key.accountId` and returns its version and the
    /// serialized `ProfileAvatar` (padding removed, not yet parsed or checked
    /// against §8.9: ``openPicture(_:key:)`` does that).
    public static func open(_ record: Data, key: AvatarRecordKey) throws(AvatarRecordError)
        -> (version: UInt64, profileAvatar: Data)
    {
        let record = Data(record)  // indices from 0
        guard let version = version(of: record) else { throw .malformedRecord }
        let header = record.prefix(headerLength)
        let nonce = record[headerLength..<headerLength + nonceLength]
        var plaintext: Data
        do {
            plaintext = try AES256GCM.open(
                record[(headerLength + nonceLength)...], key: key.bytes, nonce: Data(nonce),
                associatedData: header + key.accountId.bytes)
        } catch {
            throw .authenticationFailed
        }
        defer { plaintext.zeroize() }
        guard let marker = plaintext.lastIndex(where: { $0 != 0 }), plaintext[marker] == 0x80 else {
            throw .malformedPadding
        }
        return (version, Data(plaintext[plaintext.startIndex..<marker]))
    }

    /// Opens a record and reads its picture; a `ProfileAvatar` that does not
    /// parse or fails §8.9 is the initial letter.
    public static func openPicture(_ record: Data, key: AvatarRecordKey) throws(AvatarRecordError)
        -> (version: UInt64, picture: ProfilePicture)
    {
        let opened = try open(record, key: key)
        return (opened.version, ProfilePicture(serialized: opened.profileAvatar))
    }
}

/// The relay's answer to an avatar write (§4.12).
public enum AvatarWriteOutcome: Sendable, Equatable {
    /// `204`: stored or deleted, and the version is the account's highest now.
    case accepted
    /// `409 stale_version`: the version was not above the account's highest,
    /// which the relay names in `X-Avatar-Version` (`nil` if the header was
    /// missing or malformed). Nothing changed.
    case stale(highestVersion: UInt64?)
}

/// The relay's answer to `GET /v1/accounts/{accountId}/avatar` (§4.12).
public enum AvatarFetchResult: Sendable, Equatable {
    /// `200`: the record as stored (its version is in its header).
    case record(Data)
    /// `304`: the version named in `If-None-Match` is still the stored one.
    case notModified
    /// `404`: no record, or no such account (the relay does not say which),
    /// or a relay without avatar records.
    case notFound
}
