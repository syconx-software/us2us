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
    /// restored onto another device) or, for a v2 vault, its hardware key, so
    /// the vault can never be opened here. Not a wrong code (§5.2).
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
///     v1: KEK = HKDF-SHA256(ikm = codeKey ‖ deviceSecret, salt = salt, info = "BRP/vault/v1", 32)
///     v2: hw  = HWPRF(codeKey)                                            // per guess, in hardware
///         KEK = HKDF-SHA256(ikm = codeKey ‖ deviceSecret ‖ hw, salt = salt, info = "BRP/vault/v2", 32)
///     vault   = header ‖ nonce(12) ‖ AES-256-GCM(KEK, nonce, aad = header, DEK) ‖ tag(16)
///     header  = "BRPV" ‖ version (0x01 or 0x02) ‖ salt(16) ‖ uint32_be(iterations)
///
/// Because `deviceSecret` never leaves the device, a copied vault file alone
/// cannot be brute-forced elsewhere. In v1, a device secret extracted from the
/// phone as well lets every code be tried offline within minutes (§9); v2
/// closes that, since every guess needs the phone's hardware key
/// (``VaultHardwareKey``).
///
/// Intermediates (the code bytes, `codeKey`, `hw`, the HKDF input and `KEK`)
/// are zeroed after use, as far as Swift allows (`Data.zeroize()`). In v2
/// the hashed point `P = H2C(codeKey)` is held by CryptoKit and cannot be
/// wiped; it confirms a guessed code as well as `codeKey` does, so the
/// zeroing is best effort against a later read of process memory.
public enum VaultFormat {
    public static let defaultIterations = 600_000
    static let header = FileHeader(
        magic: "BRPV", versions: Set(VaultFormatVersion.allCases.map(\.rawValue)))
    static let info = Data("BRP/vault/v1".utf8)
    static let infoV2 = Data("BRP/vault/v2".utf8)
    static let keyLength = 32

    /// Seals the DEK as v2 when `hardwareKey` is given, otherwise as v1. For
    /// v2 the hardware is evaluated twice and must agree, so a vault is never
    /// written under a key the hardware cannot reproduce.
    static func seal(
        dek: Data, code: String, deviceSecret: Data, hardwareKey: (any VaultHardwareKey)? = nil, salt: Data,
        iterations: Int, nonce: Data
    ) throws(VaultError) -> Data {
        guard SecretCode.isWellFormed(code) else { throw .malformedCode }
        guard dek.count == keyLength, deviceSecret.count == keyLength else { throw .corrupted }
        let version: VaultFormatVersion = hardwareKey == nil ? .v1 : .v2
        let header = Self.header.encode(salt: salt, iterations: iterations, version: version.rawValue)
        var codeKey = deriveCodeKey(code: code, salt: salt, iterations: iterations)
        defer { codeKey.zeroize() }
        var hardwareOutput = Data()
        defer { hardwareOutput.zeroize() }
        if let hardwareKey {
            hardwareOutput = try evaluate(hardwareKey, codeKey: codeKey)
            var check = try evaluate(hardwareKey, codeKey: codeKey)
            defer { check.zeroize() }
            guard ConstantTime.equals(check, hardwareOutput) else { throw .storage("hardware key: not reproducible") }
        }
        var kek = deriveKEK(
            version: version, codeKey: codeKey, deviceSecret: deviceSecret, hardwareOutput: hardwareOutput, salt: salt)
        defer { kek.zeroize() }
        do {
            return header + nonce + (try AES256GCM.seal(dek, key: kek, nonce: nonce, associatedData: header))
        } catch {
            throw .corrupted
        }
    }

    /// Returns the DEK. A v2 vault needs `hardwareKey`; without it, or when
    /// its key is gone, the result is ``VaultError/deviceSecretMissing``, and
    /// any other hardware failure is ``VaultError/storage(_:)``: neither is a
    /// wrong code (§5.2).
    static func open(
        _ vault: Data, code: String, deviceSecret: Data, hardwareKey: (any VaultHardwareKey)? = nil
    ) throws(VaultError) -> Data {
        guard SecretCode.isWellFormed(code) else { throw .malformedCode }
        guard let parsed = header.decode(vault), let version = VaultFormatVersion(rawValue: parsed.version) else {
            throw .corrupted
        }
        if version == .v2, hardwareKey == nil { throw .deviceSecretMissing }
        var codeKey = deriveCodeKey(code: code, salt: parsed.salt, iterations: parsed.iterations)
        defer { codeKey.zeroize() }
        var hardwareOutput = Data()
        defer { hardwareOutput.zeroize() }
        if version == .v2, let hardwareKey {
            hardwareOutput = try evaluate(hardwareKey, codeKey: codeKey)
        }
        var kek = deriveKEK(
            version: version, codeKey: codeKey, deviceSecret: deviceSecret, hardwareOutput: hardwareOutput,
            salt: parsed.salt)
        defer { kek.zeroize() }
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

    /// The format version of a vault file, or `nil` if it is not one.
    static func version(of vault: Data) -> VaultFormatVersion? {
        header.decode(vault).flatMap { VaultFormatVersion(rawValue: $0.version) }
    }

    static func deriveCodeKey(code: String, salt: Data, iterations: Int) -> Data {
        var password = SecretCode.keyMaterial(code)
        defer { password.zeroize() }
        return PBKDF2.sha256(password: password, salt: salt, iterations: iterations)
    }

    static func deriveKEK(
        version: VaultFormatVersion, codeKey: Data, deviceSecret: Data, hardwareOutput: Data, salt: Data
    ) -> Data {
        // Reserved up front so that appending never moves the bytes and leaves
        // an unzeroed copy behind.
        var ikm = Data(capacity: codeKey.count + deviceSecret.count + hardwareOutput.count)
        defer { ikm.zeroize() }
        ikm.append(codeKey)
        ikm.append(deviceSecret)
        switch version {
        case .v1:
            return HKDFSHA256.derive(inputKeyMaterial: ikm, salt: salt, info: info)
        case .v2:
            ikm.append(hardwareOutput)
            return HKDFSHA256.derive(inputKeyMaterial: ikm, salt: salt, info: infoV2)
        }
    }

    /// One evaluation of the hardware step, its failures mapped to errors
    /// that never count as a wrong code.
    private static func evaluate(_ hardwareKey: any VaultHardwareKey, codeKey: Data) throws(VaultError) -> Data {
        let output: Data
        do {
            output = try hardwareKey.evaluate(codeKey: codeKey)
        } catch VaultHardwareKeyError.keyMissing {
            throw .deviceSecretMissing
        } catch {
            throw .storage("hardware key: \(error)")
        }
        guard output.count == keyLength else { throw .storage("hardware key: output length") }
        return output
    }
}

/// `magic(4) ‖ version ‖ salt(16) ‖ uint32_be(iterations)`, shared by the vault
/// (`BRPV`, versions 0x01 and 0x02) and the account backup (`BRPB`, 0x01)
/// formats.
struct FileHeader {
    static let length = 25
    static let saltLength = 16
    static let version: UInt8 = 0x01
    /// Iteration counts a reader accepts (§5.2, §5.5): checked before anything
    /// is derived, so a crafted file can neither make the device spin for
    /// hours nor offer a trivially weak key.
    static let iterationRange = 1000...10_000_000

    let magic: String
    /// The version bytes a reader accepts; any other is a malformed file.
    var versions: Set<UInt8> = [FileHeader.version]

    struct Parsed {
        let header: Data
        let version: UInt8
        let salt: Data
        let iterations: Int
        let nonce: Data
        let ciphertext: Data
    }

    func encode(salt: Data, iterations: Int, version: UInt8 = FileHeader.version) -> Data {
        precondition(salt.count == Self.saltLength && Self.iterationRange.contains(iterations))
        precondition(versions.contains(version))
        var header = Data(magic.utf8)
        header.append(version)
        header.append(salt)
        header.appendBigEndian(UInt32(iterations))
        return header
    }

    /// Splits `header ‖ nonce ‖ ciphertext ‖ tag`, or `nil` if malformed.
    func decode(_ file: Data) -> Parsed? {
        let file = Data(file)  // zero-based indices
        let minimum = Self.length + AES256GCM.nonceLength + AES256GCM.tagLength
        guard file.count >= minimum, file.prefix(4) == Data(magic.utf8), versions.contains(file[4]) else { return nil }
        let iterations = Int(file.bigEndianUInt32(at: 21))
        guard Self.iterationRange.contains(iterations) else { return nil }
        let nonceEnd = Self.length + AES256GCM.nonceLength
        return Parsed(
            header: file.prefix(Self.length),
            version: file[4],
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
