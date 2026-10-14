import Foundation
import Testing

@testable import Us2usKit

@Suite("Avatar records for search (§8.10)")
struct AvatarRecordTests {
    let username = try! NormalizedUsername("kagan_y")
    /// 01 02 … 10.
    let account = AccountId(bytes: Data((1...16).map { UInt8($0) }))!
    let nonce = Data((0xA0...0xAB).map { UInt8($0) })

    /// `ProfileAvatar{photo: AvatarPhoto{content_type: "image/jpeg", data}}`
    /// serialized to exactly `length` bytes (filler data, not an image).
    static func photoAvatar(serializedLength length: Int) throws -> Data {
        // The field headers take at most 20 bytes; try the data lengths below.
        for dataLength in max(0, length - 20)...length {
            let candidate = try BRPProfileAvatar.with {
                $0.photo = BRPAvatarPhoto.with {
                    $0.contentType = "image/jpeg"
                    $0.data = .pattern(dataLength, seed: 3)
                }
            }.serializedBytes() as Data
            if candidate.count == length { return candidate }
        }
        throw AvatarRecordError.tooLarge
    }

    @Test("known answer: seed, key and record match an independent implementation")
    func knownAnswer() throws {
        // Computed with Python's hashlib (PBKDF2) and `cryptography` (HKDF, AES-GCM).
        let seed = AvatarRecord.seed(for: username)
        #expect(seed.hexString == "fb0f72181f38d613a7086ff53bb8d0ed7cd2e42143ebb04fefee61a6e7fa129f")
        // Not the username hash (§3.2): the relay cannot get the seed from what it stores.
        #expect(seed != username.hash())
        #expect(username.hash().hexString == "d4810e60c27fa5c9fec08c2e5d53e83feeeb319ffa2a8c1373898881fd363340")
        let key = AvatarRecordKey(seed: seed, accountId: account)
        #expect(key.bytes.hexString == "85f27680054fe8f412ce33d375d778458b80f45a5e679c9c6b5120d82511463d")
        #expect(AvatarRecordKey.derive(username: username, accountId: account).bytes == key.bytes)

        let record = try AvatarRecord.seal(Data([0x08, 0x01]), version: 1, key: key, nonce: nonce)
        #expect(record.count == 8192)
        #expect(
            record.prefix(64).hexString
                == "42525041010000000000000001a0a1a2a3a4a5a6a7a8a9aaab35cc27b8f094d45f851c0d9ae8547c2906f6f7eec5bc9e58479e2c3178669c755929c6740e7e5a"
        )
        #expect(record.suffix(32).hexString == "9267bf152989e535957d86cb7fa30d03f715dab253890ea983642f10f826e64d")
        #expect(record.sha256Digest.hexString == "cb5205ed18fb9114024a13da6a1a54649749759f403ce140c550e0e578785612")

        let opened = try AvatarRecord.openPicture(record, key: key)
        #expect(opened.version == 1)
        #expect(opened.picture == .preset(1))
    }

    @Test("the smallest of the four sizes that holds the ProfileAvatar and its padding")
    func sizes() throws {
        #expect(AvatarRecord.sizes.map(AvatarRecord.capacity(ofLength:)) == [8150, 16342, 32726, 49110])
        #expect(AvatarRecord.length(forProfileAvatarBytes: 0) == 8192)
        #expect(AvatarRecord.length(forProfileAvatarBytes: 8150) == 8192)
        #expect(AvatarRecord.length(forProfileAvatarBytes: 8151) == 16384)
        #expect(AvatarRecord.length(forProfileAvatarBytes: 16342) == 16384)
        #expect(AvatarRecord.length(forProfileAvatarBytes: 16343) == 32768)
        #expect(AvatarRecord.length(forProfileAvatarBytes: 32726) == 32768)
        #expect(AvatarRecord.length(forProfileAvatarBytes: 32727) == 49152)
        #expect(AvatarRecord.length(forProfileAvatarBytes: 49110) == 49152)
        #expect(AvatarRecord.length(forProfileAvatarBytes: 49111) == nil)
        #expect(AvatarRecord.length(forProfileAvatarBytes: 49110) == AvatarRecord.maxLength)
        #expect(ProfilePicture.maxSerializedBytes == AvatarRecord.capacity(ofLength: AvatarRecord.maxLength))
    }

    @Test(
        "every size edge seals and opens to the same bytes",
        arguments: [(8150, 8192), (8151, 16384), (16342, 16384), (16343, 32768), (32726, 32768), (32727, 49152),
                    (49110, 49152)])
    func sizeEdges(serialized: Int, recordLength: Int) throws {
        let key = AvatarRecordKey(seed: .pattern(32, seed: 1), accountId: account)
        let avatar = try Self.photoAvatar(serializedLength: serialized)
        #expect(avatar.count == serialized)
        let record = try AvatarRecord.seal(avatar, version: 7, key: key)
        #expect(record.count == recordLength)
        let opened = try AvatarRecord.open(record, key: key)
        #expect(opened.version == 7)
        #expect(opened.profileAvatar == avatar)
    }

    @Test("a ProfileAvatar over 49110 bytes and versions out of range are refused")
    func sealRefusals() throws {
        let key = AvatarRecordKey(seed: .pattern(32), accountId: account)
        #expect(throws: AvatarRecordError.tooLarge) {
            try AvatarRecord.seal(Data(count: 49111), version: 1, key: key)
        }
        #expect(throws: AvatarRecordError.invalidVersion) { try AvatarRecord.seal(Data([8, 1]), version: 0, key: key) }
        #expect(throws: AvatarRecordError.invalidVersion) {
            try AvatarRecord.seal(Data([8, 1]), version: 1 << 63, key: key)
        }
        let highest = try AvatarRecord.seal(Data([8, 1]), version: UInt64(Int64.max), key: key)
        #expect(AvatarRecord.version(of: highest) == UInt64(Int64.max))
        // A version above 2^32 survives as big-endian 64 bit.
        let large = try AvatarRecord.seal(Data([8, 1]), version: 1 << 40 | 5, key: key)
        #expect(large.prefix(13).hexString == "4252504101" + "0000010000000005")
        #expect(try AvatarRecord.open(large, key: key).version == 1 << 40 | 5)
    }

    @Test("a fresh random nonce for every record")
    func freshNonces() throws {
        let key = AvatarRecordKey(seed: .pattern(32), accountId: account)
        let first = try AvatarRecord.seal(Data([8, 1]), version: 1, key: key)
        let second = try AvatarRecord.seal(Data([8, 1]), version: 1, key: key)
        #expect(first[13..<25] != second[13..<25])
        #expect(first != second)
    }

    @Test("another username, another account or any changed byte does not open")
    func binding() throws {
        let key = AvatarRecordKey.derive(username: username, accountId: account)
        let record = try AvatarRecord.seal(Data([8, 2]), version: 3, key: key, nonce: nonce)
        #expect(try AvatarRecord.open(record, key: key).profileAvatar == Data([8, 2]))

        let otherName = AvatarRecordKey.derive(username: try NormalizedUsername("kagan_z"), accountId: account)
        #expect(throws: AvatarRecordError.authenticationFailed) { try AvatarRecord.open(record, key: otherName) }
        let otherAccount = AvatarRecordKey.derive(username: username, accountId: AccountId(uuid: UUID()))
        #expect(throws: AvatarRecordError.authenticationFailed) { try AvatarRecord.open(record, key: otherAccount) }
        // The key from a cached seed is the same key.
        let cached = AvatarRecordKey(seed: AvatarRecord.seed(for: username), accountId: account)
        #expect(try AvatarRecord.open(record, key: cached).version == 3)

        for index in [20, 30, 4000, record.count - 1] {
            #expect(throws: AvatarRecordError.authenticationFailed) {
                try AvatarRecord.open(record.flippingBit(at: index), key: key)
            }
        }
        // The version is in the AAD: a header with another version does not open.
        var relabelled = record
        relabelled[12] = 4
        #expect(throws: AvatarRecordError.authenticationFailed) { try AvatarRecord.open(relabelled, key: key) }
    }

    @Test("length and header are checked before anything is derived")
    func headerChecks() throws {
        let key = AvatarRecordKey(seed: .pattern(32), accountId: account)
        let record = try AvatarRecord.seal(Data([8, 1]), version: 9, key: key)
        #expect(AvatarRecord.version(of: record) == 9)
        #expect(AvatarRecord.version(of: record.dropLast()) == nil)
        #expect(AvatarRecord.version(of: record + Data([0])) == nil)
        #expect(AvatarRecord.version(of: Data(count: 8192)) == nil)
        var magic = record
        magic[0] = UInt8(ascii: "X")
        #expect(AvatarRecord.version(of: magic) == nil)
        var format = record
        format[4] = 0x02
        #expect(AvatarRecord.version(of: format) == nil)
        var zero = record
        zero.replaceSubrange(5..<13, with: Data(count: 8))
        #expect(AvatarRecord.version(of: zero) == nil)
        var tooHigh = record
        tooHigh[5] = 0x80
        #expect(AvatarRecord.version(of: tooHigh) == nil)
        #expect(throws: AvatarRecordError.malformedRecord) { try AvatarRecord.open(format, key: key) }
        #expect(throws: AvatarRecordError.malformedRecord) { try AvatarRecord.open(record.prefix(100), key: key) }
        // A slice of a larger buffer reads the same.
        let embedded = (Data([1, 2, 3]) + record).dropFirst(3)
        #expect(try AvatarRecord.open(embedded, key: key).version == 9)
    }

    @Test("padding: trailing zeros, then exactly one 0x80")
    func padding() throws {
        let key = AvatarRecordKey(seed: .pattern(32), accountId: account)
        // A plaintext without the marker, sealed by hand: authentic but badly padded.
        let header = AvatarRecord.header(version: 2)
        let sealed = try AES256GCM.seal(
            Data(count: 8192 - 41), key: key.bytes, nonce: nonce, associatedData: header + account.bytes)
        #expect(throws: AvatarRecordError.malformedPadding) {
            try AvatarRecord.open(header + nonce + sealed, key: key)
        }
        // An empty ProfileAvatar is just the marker: it opens to no bytes, the initial letter.
        let empty = try AvatarRecord.seal(Data(), version: 2, key: key)
        #expect(try AvatarRecord.open(empty, key: key).profileAvatar.isEmpty)
        #expect(try AvatarRecord.openPicture(empty, key: key).picture == .initials)
        // Trailing zeros of the ProfileAvatar itself survive (only the padding goes).
        let zeros = try AvatarRecord.seal(Data([0x80, 0, 0]), version: 2, key: key)
        #expect(try AvatarRecord.open(zeros, key: key).profileAvatar == Data([0x80, 0, 0]))
    }

    @Test("a record that opens but holds no valid picture is the initial letter")
    func invalidPictureInside() throws {
        let key = AvatarRecordKey(seed: .pattern(32), accountId: account)
        let presetZero = try BRPProfileAvatar.with { $0.presetID = 0 }.serializedBytes() as Data
        #expect(try AvatarRecord.openPicture(try AvatarRecord.seal(presetZero, version: 1, key: key), key: key).picture
            == .initials)
        let garbage = Data([0xFF, 0xFF, 0xFF])
        #expect(try AvatarRecord.openPicture(try AvatarRecord.seal(garbage, version: 1, key: key), key: key).picture
            == .initials)
        let notAnImage = try Self.photoAvatar(serializedLength: 500)
        #expect(try AvatarRecord.openPicture(try AvatarRecord.seal(notAnImage, version: 1, key: key), key: key).picture
            == .initials)
    }
}
