import Foundation

public enum VaultError: Error, Equatable, Sendable {
    /// GCM authentication failed: wrong code (or a different device secret).
    case wrongCode
    /// The input is not six ASCII digits, so it cannot be the code. Refused
    /// before anything is derived and not counted as an attempt (§5.2).
    case malformedCode
    /// Too many wrong attempts; the next attempt is allowed at the given time.
    case lockedOut(until: Date)
    /// The optional "wipe after N wrong attempts" fired; everything was crypto-shredded.
    case wiped
    /// The Keychain no longer holds the device secret (e.g. the app was
    /// restored onto another device), so the vault can never be opened here.
    case deviceSecretMissing
    case corrupted
    case alreadyExists
    case notFound
    case invalidNewCode(SecretCodeError)
    case storage(String)
}

/// The vault file (PROTOCOL.md §5.2), which wraps the data encryption key (DEK):
///
///     codeKey = PBKDF2-HMAC-SHA256(UTF-8(code), salt, iterations, 32)     // code = six ASCII digits
///     KEK     = HKDF-SHA256(ikm = codeKey ‖ deviceSecret, salt = salt, info = "BRP/vault/v1", 32)
///     vault   = header ‖ nonce(12) ‖ AES-256-GCM(KEK, nonce, aad = header, DEK) ‖ tag(16)
///     header  = "BRPV" ‖ 0x01 ‖ salt(16) ‖ uint32_be(iterations)
///
/// Because `deviceSecret` never leaves the device, a copied vault file cannot
/// be brute-forced elsewhere, which is what makes six digits enough.
public enum VaultFormat {
    public static let defaultIterations = 600_000
    static let header = FileHeader(magic: "BRPV")
    static let info = Data("BRP/vault/v1".utf8)
    static let keyLength = 32

    static func seal(
        dek: Data, code: String, deviceSecret: Data, salt: Data, iterations: Int, nonce: Data
    ) throws(VaultError) -> Data {
        guard SecretCode.isWellFormed(code) else { throw .malformedCode }
        guard dek.count == keyLength, deviceSecret.count == keyLength else { throw .corrupted }
        let header = Self.header.encode(salt: salt, iterations: iterations)
        let kek = deriveKEK(code: code, deviceSecret: deviceSecret, salt: salt, iterations: iterations)
        do {
            return header + nonce + (try AES256GCM.seal(dek, key: kek, nonce: nonce, associatedData: header))
        } catch {
            throw .corrupted
        }
    }

    /// Returns the DEK.
    static func open(_ vault: Data, code: String, deviceSecret: Data) throws(VaultError) -> Data {
        guard SecretCode.isWellFormed(code) else { throw .malformedCode }
        guard let parsed = header.decode(vault) else { throw .corrupted }
        let kek = deriveKEK(
            code: code, deviceSecret: deviceSecret, salt: parsed.salt, iterations: parsed.iterations)
        let dek: Data
        do {
            dek = try AES256GCM.open(
                parsed.ciphertext, key: kek, nonce: parsed.nonce, associatedData: parsed.header)
        } catch {
            throw .wrongCode
        }
        guard dek.count == keyLength else { throw .corrupted }
        return dek
    }

    static func deriveKEK(code: String, deviceSecret: Data, salt: Data, iterations: Int) -> Data {
        let codeKey = PBKDF2.sha256(password: SecretCode.keyMaterial(code), salt: salt, iterations: iterations)
        return HKDFSHA256.derive(inputKeyMaterial: codeKey + deviceSecret, salt: salt, info: info)
    }
}

/// `magic(4) ‖ 0x01 ‖ salt(16) ‖ uint32_be(iterations)`, shared by the vault
/// (`BRPV`) and the account backup (`BRPB`) formats.
struct FileHeader {
    static let length = 25
    static let saltLength = 16
    static let version: UInt8 = 0x01
    /// Iteration counts a reader accepts (§5.2, §5.5): checked before anything
    /// is derived, so a crafted file can neither make the device spin for
    /// hours nor offer a trivially weak key.
    static let iterationRange = 1000...10_000_000

    let magic: String

    struct Parsed {
        let header: Data
        let salt: Data
        let iterations: Int
        let nonce: Data
        let ciphertext: Data
    }

    func encode(salt: Data, iterations: Int) -> Data {
        precondition(salt.count == Self.saltLength && Self.iterationRange.contains(iterations))
        var header = Data(magic.utf8)
        header.append(Self.version)
        header.append(salt)
        header.appendBigEndian(UInt32(iterations))
        return header
    }

    /// Splits `header ‖ nonce ‖ ciphertext ‖ tag`, or `nil` if malformed.
    func decode(_ file: Data) -> Parsed? {
        let file = Data(file)  // zero-based indices
        let minimum = Self.length + AES256GCM.nonceLength + AES256GCM.tagLength
        guard file.count >= minimum, file.prefix(4) == Data(magic.utf8), file[4] == Self.version else { return nil }
        let iterations = Int(file.bigEndianUInt32(at: 21))
        guard Self.iterationRange.contains(iterations) else { return nil }
        let nonceEnd = Self.length + AES256GCM.nonceLength
        return Parsed(
            header: file.prefix(Self.length),
            salt: file[5..<21],
            iterations: iterations,
            nonce: file[Self.length..<nonceEnd],
            ciphertext: file[nonceEnd...])
    }
}

extension VaultError: LocalizedError {
    public var errorDescription: String? {
        let localizer = Localizer.current
        switch self {
        case .wrongCode: return localizer.string(.vaultWrongCode)
        case .malformedCode: return localizer.string(.secretCodeNotSixDigits)
        case .lockedOut(let until):
            let seconds = Int(until.timeIntervalSinceNow.rounded(.up))
            return localizer.string(.vaultLockedOut, localizer.duration(seconds: seconds))
        case .wiped: return localizer.string(.vaultWiped)
        case .deviceSecretMissing: return localizer.string(.vaultDeviceSecretMissing)
        case .corrupted, .storage: return localizer.string(.vaultCorrupted)
        case .alreadyExists: return localizer.string(.vaultAlreadyExists)
        case .notFound: return localizer.string(.vaultNotFound)
        case .invalidNewCode(let reason): return reason.errorDescription
        }
    }
}
