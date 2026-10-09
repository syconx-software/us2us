import CommonCrypto
import CryptoKit
import Foundation

// Thin wrappers over platform crypto for the standard constructions the spec
// layers on top of libsignal (PROTOCOL.md §1 goal 5). Nothing here invents a
// construction; each function is one primitive with fixed parameters.

enum PBKDF2 {
    /// PBKDF2-HMAC-SHA256 (CommonCrypto).
    static func sha256(password: Data, salt: Data, iterations: Int, keyLength: Int = 32) -> Data {
        precondition(iterations > 0 && iterations <= Int(UInt32.max), "iteration count out of range")
        var derived = Data(count: keyLength)
        let status = derived.withUnsafeMutableBytes { derivedBuffer in
            password.withUnsafeBytes { passwordBuffer in
                salt.withUnsafeBytes { saltBuffer in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordBuffer.bindMemory(to: CChar.self).baseAddress, password.count,
                        saltBuffer.bindMemory(to: UInt8.self).baseAddress, salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        UInt32(iterations),
                        derivedBuffer.bindMemory(to: UInt8.self).baseAddress, keyLength)
                }
            }
        }
        // Only fails on invalid parameters, which the precondition excludes.
        precondition(status == kCCSuccess, "CCKeyDerivationPBKDF failed: \(status)")
        return derived
    }
}

enum HKDFSHA256 {
    /// RFC 5869 HKDF-SHA256. An empty salt is equivalent to HashLen zero bytes.
    static func derive(inputKeyMaterial: Data, salt: Data, info: Data, length: Int = 32) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: inputKeyMaterial),
            salt: salt,
            info: info,
            outputByteCount: length)
        return key.withUnsafeBytes { Data($0) }
    }
}

/// AES-256-GCM with an explicit 12-byte nonce and a 16-byte tag, the
/// construction used by the vault, key slots, items and backups (§5.2–§5.5).
enum AES256GCM {
    static let keyLength = 32
    static let nonceLength = 12
    static let tagLength = 16

    enum Failure: Error {
        case invalidKeyOrNonce
        case authenticationFailed
    }

    /// Returns `ciphertext ‖ tag`.
    static func seal(_ plaintext: Data, key: Data, nonce: Data, associatedData: Data) throws(Failure) -> Data {
        guard key.count == keyLength, let gcmNonce = try? AES.GCM.Nonce(data: nonce), nonce.count == nonceLength
        else { throw .invalidKeyOrNonce }
        do {
            let box = try AES.GCM.seal(
                plaintext, using: SymmetricKey(data: key), nonce: gcmNonce, authenticating: associatedData)
            return box.ciphertext + box.tag
        } catch {
            throw .invalidKeyOrNonce
        }
    }

    /// Opens `ciphertext ‖ tag`; any tampering, a wrong key or wrong
    /// associated data throws `.authenticationFailed`.
    static func open(_ ciphertextAndTag: Data, key: Data, nonce: Data, associatedData: Data) throws(Failure) -> Data {
        guard key.count == keyLength, nonce.count == nonceLength, let gcmNonce = try? AES.GCM.Nonce(data: nonce)
        else { throw .invalidKeyOrNonce }
        guard ciphertextAndTag.count >= tagLength else { throw .authenticationFailed }
        let split = ciphertextAndTag.endIndex - tagLength
        do {
            let box = try AES.GCM.SealedBox(
                nonce: gcmNonce,
                ciphertext: ciphertextAndTag[..<split],
                tag: ciphertextAndTag[split...])
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: associatedData)
        } catch {
            throw .authenticationFailed
        }
    }
}

extension Data {
    var sha256Digest: Data { Data(SHA256.hash(data: self)) }
}
