import Foundation
import LibSignalClient
import SwiftProtobuf

@testable import Us2usKit

/// Checks vectors produced by any implementation against this one. Each
/// function returns human-readable failures (empty = all vectors pass).
enum VectorVerifier {
    static func username(_ vectors: [UsernameVector]) -> [String] {
        vectors.compactMap { vector in
            let username = try? NormalizedUsername(vector.input)
            if username?.value != vector.normalized {
                return "username \(vector.input.debugDescription): normalized \(String(describing: username?.value))"
            }
            if username?.hash().hexString != vector.hash {
                return "username \(vector.input.debugDescription): hash differs"
            }
            return nil
        }
    }

    static func pow(_ vectors: [PowVector]) -> [String] {
        vectors.compactMap { vector in
            guard let nonce = UInt64(vector.nonce) else { return "pow: nonce \(vector.nonce) is not a uint64" }
            let valid = ProofOfWork.isValid(token: vector.token, nonce: nonce, difficulty: vector.difficulty)
            return valid == vector.valid
                ? nil : "pow: \(vector.token) nonce \(nonce) d\(vector.difficulty) valid=\(valid)"
        }
    }

    static func padding(_ vectors: [PaddingVector]) -> [String] {
        vectors.compactMap { vector in
            let plaintext = vector.plaintext.hexBytes
            let padded = vector.padded.hexBytes
            guard ContentPadding.pad(plaintext) == padded else { return "padding: pad(\(plaintext.count) bytes)" }
            guard (try? ContentPadding.unpad(padded)) == plaintext else {
                return "padding: unpad(\(padded.count) bytes)"
            }
            return nil
        }
    }

    static func attachmentPadding(_ vectors: [AttachmentPaddingVector]) -> [String] {
        vectors.compactMap { vector in
            let padded = AttachmentPadding.paddedSize(for: vector.size)
            return padded == vector.paddedSize ? nil : "attachment-padding: P(\(vector.size)) = \(padded)"
        }
    }

    static func attachment(_ vectors: [AttachmentVector]) -> [String] {
        vectors.compactMap { vector in
            let plaintext = vector.plaintext.hexBytes
            let key = vector.key.hexBytes
            let blob = vector.blob.hexBytes
            guard let (ours, info) = try? AttachmentCipher.encrypt(plaintext, key: key, iv: vector.iv.hexBytes) else {
                return "attachment: encryption failed"
            }
            guard ours == blob else { return "attachment: blob for \(plaintext.count) bytes differs" }
            guard info.digest == vector.digest.hexBytes else { return "attachment: digest differs" }
            let decrypted = try? AttachmentCipher.decrypt(
                blob, key: key, digest: vector.digest.hexBytes, size: UInt64(plaintext.count))
            return decrypted == plaintext ? nil : "attachment: decryption of \(plaintext.count) bytes failed"
        }
    }

    static func sealed(_ vectors: [SealedVector]) -> [String] {
        vectors.enumerated().compactMap { index, vector in
            guard let identity = try? IdentityKeyPair(bytes: vector.recipientIdentityKeyPair.hexBytes) else {
                return "sealed[\(index)]: bad recipientIdentityKeyPair"
            }
            let opened = try? SealedEnvelope.open(vector.envelope.hexBytes, with: identity)
            guard vector.valid ?? true else {
                return opened == nil ? nil : "sealed[\(index)]: tampered envelope opened"
            }
            guard let opened else { return "sealed[\(index)]: open failed" }
            let expected = vector.sealedContent
            guard opened.senderAccountID.hexString == expected.senderAccountId.lowercased(),
                opened.senderDeviceID == expected.senderDeviceId, opened.type == expected.type,
                opened.ciphertext.hexString == expected.ciphertext.lowercased(),
                opened.senderUsername == (expected.senderUsername ?? "")
            else { return "sealed[\(index)]: fields differ" }
            return nil
        }
    }

    static func content(_ vectors: [ContentVector]) -> [String] {
        let cases = Dictionary(uniqueKeysWithValues: ContentCases.all.map { ($0.name, $0.content) })
        var failures: [String] = []
        for vector in vectors {
            let bytes = vector.protobuf.hexBytes
            if vector.caseName == ContentCases.sealedContentName {
                let ours = ContentCases.sealedContent
                if (try? ours.serializedBytes() as Data) != bytes {
                    failures.append("content: sealed_content bytes differ")
                }
                if (try? BRPSealedContent(serializedBytes: bytes)) != ours {
                    failures.append("content: sealed_content does not parse back")
                }
            } else if let ours = cases[vector.caseName] {
                if (try? ours.serializedBytes() as Data) != bytes {
                    failures.append("content: \(vector.caseName) bytes differ")
                }
                if (try? BRPContent(serializedBytes: bytes)) != ours {
                    failures.append("content: \(vector.caseName) does not parse back")
                }
            } else {
                failures.append("content: unknown case \(vector.caseName)")
            }
        }
        let missing = Set(cases.keys).union([ContentCases.sealedContentName]).subtracting(vectors.map(\.caseName))
        if !missing.isEmpty { failures.append("content: missing cases \(missing.sorted())") }
        return failures
    }

    /// Another six-digit code: the last digit changed.
    static func otherCode(_ code: String) -> String {
        String(code.dropLast()) + (code.hasSuffix("0") ? "1" : "0")
    }

    static func vault(_ vectors: [VaultVector]) -> [String] {
        vectors.compactMap { vector in
            let deviceSecret = vector.deviceSecret.hexBytes
            let vault = vector.vault.hexBytes
            let dek = vector.dek.hexBytes
            guard SecretCode.isWellFormed(vector.code) else {
                return "vault \(vector.code.debugDescription): not six ASCII digits"
            }
            let sealed = try? VaultFormat.seal(
                dek: dek, code: vector.code, deviceSecret: deviceSecret, salt: vector.salt.hexBytes,
                iterations: vector.iterations, nonce: vector.nonce.hexBytes)
            guard sealed == vault else { return "vault \(vector.code.debugDescription): create differs" }
            guard (try? VaultFormat.open(vault, code: vector.code, deviceSecret: deviceSecret)) == dek else {
                return "vault \(vector.code.debugDescription): open failed"
            }
            do throws(VaultError) {
                _ = try VaultFormat.open(vault, code: otherCode(vector.code), deviceSecret: deviceSecret)
                return "vault \(vector.code.debugDescription): wrong code opened"
            } catch {
                return error == .wrongCode ? nil : "vault: wrong code gave \(error)"
            }
        }
    }

    static func backup(_ vectors: [BackupVector]) -> [String] {
        vectors.compactMap { vector in
            let plaintext = vector.accountBackup.hexBytes
            let file = vector.file.hexBytes
            let ours = try? AccountBackupFile.encrypt(
                plaintext, password: vector.password, salt: vector.salt.hexBytes, iterations: vector.iterations,
                nonce: vector.nonce.hexBytes)
            guard ours == file else { return "backup \(vector.password.debugDescription): create differs" }
            guard (try? AccountBackupFile.decrypt(file, password: vector.password)) == plaintext else {
                return "backup \(vector.password.debugDescription): open failed"
            }
            guard (try? AccountBackupFile.import(file, password: vector.password)) != nil else {
                return "backup \(vector.password.debugDescription): AccountBackup does not parse"
            }
            guard (try? AccountBackupFile.decrypt(file, password: vector.password + "x")) == nil else {
                return "backup \(vector.password.debugDescription): wrong password opened"
            }
            return nil
        }
    }

    static func secretCode(_ vectors: [SecretCodeVector]) -> [String] {
        vectors.compactMap { vector in
            let problem = SecretCode.problem(of: vector.code)
            return problem == vector.problem
                ? nil : "secret-code \(vector.code.debugDescription): \(problem ?? "null") != \(vector.problem ?? "null")"
        }
    }

    static func backupPassword(_ vectors: [BackupPasswordVector]) -> [String] {
        vectors.compactMap { vector in
            let problem = BackupPassword.problem(of: vector.password)
            return problem == vector.problem
                ? nil
                : "backup-password \(vector.password.debugDescription): \(problem ?? "null") != \(vector.problem ?? "null")"
        }
    }

    static func keySlot(_ vectors: [KeySlotVector]) -> [String] {
        vectors.compactMap { vector in
            let dek = vector.dek.hexBytes
            let slotId = vector.slotId.hexBytes
            let ck = vector.ck.hexBytes
            let file = vector.slotFile.hexBytes
            guard
                (try? KeySlot.seal(conversationKey: ck, slotId: slotId, dek: dek, nonce: vector.nonce.hexBytes)) == file
            else { return "keyslot \(vector.slotId): create differs" }
            return (try? KeySlot.open(file, slotId: slotId, dek: dek)) == ck
                ? nil : "keyslot \(vector.slotId): open failed"
        }
    }

    static func item(_ vectors: [ItemVector]) -> [String] {
        vectors.compactMap { vector in
            let ck = vector.ck.hexBytes
            let itemId = vector.itemId.hexBytes
            let plaintext = vector.plaintext.hexBytes
            let item = vector.item.hexBytes
            guard
                (try? ItemCipher.seal(plaintext, itemId: itemId, conversationKey: ck, nonce: vector.nonce.hexBytes))
                    == item
            else { return "item \(vector.itemId): create differs" }
            return (try? ItemCipher.open(item, itemId: itemId, conversationKey: ck)) == plaintext
                ? nil : "item \(vector.itemId): open failed"
        }
    }

    static func fingerprint(_ vectors: [FingerprintVector]) -> [String] {
        vectors.compactMap { vector in
            guard let local = AccountId(bytes: vector.localId.hexBytes),
                let remote = AccountId(bytes: vector.remoteId.hexBytes),
                let localKey = try? IdentityKey(bytes: vector.localKey.hexBytes),
                let remoteKey = try? IdentityKey(bytes: vector.remoteKey.hexBytes),
                let number = try? SafetyNumber(
                    local: local, localIdentity: localKey, remote: remote, remoteIdentity: remoteKey)
            else { return "fingerprint \(vector.localId): bad inputs" }
            guard number.digits == vector.displayable else {
                return "fingerprint \(vector.localId): displayable differs"
            }
            return number.scannable.hexString == vector.scannable.lowercased()
                ? nil : "fingerprint \(vector.localId): scannable differs"
        }
    }

    /// Reads one category of `generator` and verifies it.
    static func verify(generator: String, _ category: VectorCategory) throws -> [String] {
        switch category {
        case .username:
            return username(try VectorStore.read(UsernameVector.self, generator: generator, category: category))
        case .pow: return pow(try VectorStore.read(PowVector.self, generator: generator, category: category))
        case .padding:
            return padding(try VectorStore.read(PaddingVector.self, generator: generator, category: category))
        case .attachmentPadding:
            return attachmentPadding(
                try VectorStore.read(AttachmentPaddingVector.self, generator: generator, category: category))
        case .attachment:
            return attachment(try VectorStore.read(AttachmentVector.self, generator: generator, category: category))
        case .sealed: return sealed(try VectorStore.read(SealedVector.self, generator: generator, category: category))
        case .content:
            return content(try VectorStore.read(ContentVector.self, generator: generator, category: category))
        case .vault: return vault(try VectorStore.read(VaultVector.self, generator: generator, category: category))
        case .backup: return backup(try VectorStore.read(BackupVector.self, generator: generator, category: category))
        case .secretCode:
            return secretCode(try VectorStore.read(SecretCodeVector.self, generator: generator, category: category))
        case .backupPassword:
            return backupPassword(
                try VectorStore.read(BackupPasswordVector.self, generator: generator, category: category))
        case .keyslot:
            return keySlot(try VectorStore.read(KeySlotVector.self, generator: generator, category: category))
        case .item: return item(try VectorStore.read(ItemVector.self, generator: generator, category: category))
        case .fingerprint:
            return fingerprint(try VectorStore.read(FingerprintVector.self, generator: generator, category: category))
        }
    }
}
