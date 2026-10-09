import Foundation
import LibSignalClient
import SwiftProtobuf

@testable import Us2usKit

/// Builds the Swift vectors from fixed inputs (spec/test-vectors/README.md).
/// Everything is deterministic except HPKE in `sealed`.
enum VectorGenerator {
    static func username() -> [UsernameVector] {
        let inputs = [
            "alice", "Alice_01", "  bob  ", "\tdave\n", "ｃａｒｏｌ", "ＡＬＩＣＥ", "ILGAZ", "abc",
            "abcdefghijklmnopqrstuvwx", "ab", "abcdefghijklmnopqrstuvwxy", "1alice", "_alice", "al-ice", "al.ice",
            "şule", "ılgaz", "İstanbul", "Kağan", "user name", "",
            // Trimming is by Unicode White_Space: NEL and ideographic space go, U+001C stays (and is invalid).
            "\u{85}white_space\u{3000}", "\u{1C}not_space",
        ]
        return inputs.map { input in
            let username = try? NormalizedUsername(input)
            return UsernameVector(input: input, normalized: username?.value, hash: username?.hash().hexString)
        }
    }

    static func pow() throws -> [PowVector] {
        var vectors: [PowVector] = []
        func add(_ token: String, _ nonce: UInt64, _ difficulty: Int) {
            vectors.append(
                PowVector(
                    token: token, nonce: String(nonce), difficulty: difficulty,
                    valid: ProofOfWork.isValid(token: token, nonce: nonce, difficulty: difficulty)))
        }
        for difficulty in [0, 1, 8, 12, 16] {
            let token =
                "v1.message.8f14e45f-ceea-467a-9f0b-6d3e2a1b4c5d.1760000600.AAECAwQFBgcICQoLDA0ODw.d\(difficulty)"
            let nonce = ProofOfWork.search(token: token, difficulty: difficulty, start: 0, stride: 1)!
            add(token, nonce, difficulty)  // the smallest solution: valid
            add(token, nonce, difficulty + 1)  // usually not enough zeros
            add(token, nonce + 1, difficulty)  // off by one
            if nonce > 0 { add(token, nonce - 1, difficulty) }  // below the smallest solution: invalid
        }
        // Nonces beyond 2^53 must survive JSON as decimal strings (§2).
        let token = "v1.register.-.1760000600.AAECAwQFBgcICQoLDA0ODw.big"
        add(token, .max, 0)
        add(token, .max - 1, 2)
        return vectors
    }

    static func padding() -> [PaddingVector] {
        [0, 1, 158, 159, 160, 161, 319, 320, 1000].map { length in
            let plaintext = Data.pattern(length, seed: 1)
            return PaddingVector(plaintext: plaintext.hexString, padded: ContentPadding.pad(plaintext).hexString)
        }
    }

    static func attachmentPadding() -> [AttachmentPaddingVector] {
        [0, 1, 541, 542, 568, 569, 1000, 10000, 1_048_576, 52_428_800].map {
            AttachmentPaddingVector(size: $0, paddedSize: AttachmentPadding.paddedSize(for: $0))
        }
    }

    static func attachment() throws -> [AttachmentVector] {
        try [0, 1, 541, 600, 5000].enumerated().map { index, length in
            let plaintext = Data.pattern(length, seed: UInt8(10 + index))
            let key = Data.pattern(64, seed: UInt8(20 + index))
            let iv = Data.pattern(16, seed: UInt8(30 + index))
            let (blob, info) = try AttachmentCipher.encrypt(plaintext, key: key, iv: iv)
            return AttachmentVector(
                plaintext: plaintext.hexString, key: key.hexString, iv: iv.hexString, blob: blob.hexString,
                digest: info.digest.hexString)
        }
    }

    static let sealedRecipientLabel = "BRP test vector: sealed recipient"

    /// HPKE is randomized, so an existing file is kept as long as it still
    /// verifies; this avoids rewriting it on every test run.
    static func sealed(existing: [SealedVector]?) throws -> [SealedVector] {
        if let existing, existing.count == 3, VectorVerifier.sealed(existing).isEmpty {
            return existing
        }
        let recipient = try deterministicIdentity(sealedRecipientLabel)
        let whisper = BRPSealedContent.with {
            $0.senderAccountID = .id(15)
            $0.senderDeviceID = 1
            $0.type = 2
            $0.ciphertext = .pattern(40, seed: 4)
        }
        var vectors = try [ContentCases.sealedContent, whisper].map { content in
            SealedVector(
                recipientIdentityKeyPair: recipient.serialize().hexString,
                envelope: try SealedEnvelope.seal(content, to: recipient.identityKey).hexString,
                sealedContent: .init(content), valid: true)
        }
        var tampered = vectors[0]
        tampered.envelope = tampered.envelope.hexBytes.flippingBit(at: 40).hexString
        tampered.valid = false
        vectors.append(tampered)
        return vectors
    }

    static func content() throws -> [ContentVector] {
        var vectors = try ContentCases.all.map {
            ContentVector(caseName: $0.name, protobuf: (try $0.content.serializedBytes() as Data).hexString)
        }
        vectors.append(
            ContentVector(
                caseName: ContentCases.sealedContentName,
                protobuf: (try ContentCases.sealedContent.serializedBytes() as Data).hexString))
        return vectors
    }

    static func vault() throws -> [VaultVector] {
        // Codes are six ASCII digits (§5.2); the KDF input is their six bytes.
        let cases: [(String, Int)] = [
            ("472913", 1000), ("830561", 1000), ("019283", 1000), ("200590", 1000),
            ("615204", VaultFormat.defaultIterations),
        ]
        return try cases.enumerated().map { index, entry in
            let seed = UInt8(40 + index * 4)
            let deviceSecret = Data.pattern(32, seed: seed)
            let salt = Data.pattern(16, seed: seed + 1)
            let nonce = Data.pattern(12, seed: seed + 2)
            let dek = Data.pattern(32, seed: seed + 3)
            let vault = try VaultFormat.seal(
                dek: dek, code: entry.0, deviceSecret: deviceSecret, salt: salt, iterations: entry.1, nonce: nonce)
            return VaultVector(
                code: entry.0, deviceSecret: deviceSecret.hexString, salt: salt.hexString, iterations: entry.1,
                nonce: nonce.hexString, dek: dek.hexString, vault: vault.hexString)
        }
    }

    static func sampleAccountBackup() throws -> BRPAccountBackup {
        try BRPAccountBackup.with {
            $0.version = 1
            $0.accountID = .id(1)
            $0.authToken = .pattern(32, seed: 60)
            $0.identityKeyPair = try deterministicIdentity("BRP test vector: backup identity").serialize()
            $0.registrationID = 1234
            $0.username = "kagan_y"
            $0.displayName = "Kağan"
            $0.accessKey = .id(13)
            $0.contacts = [
                try BRPBackupContact.with {
                    $0.accountID = .id(2)
                    $0.username = "alice_01"
                    $0.displayName = "Alice"
                    $0.identityKey = try deterministicIdentity("BRP test vector: backup contact").identityKey
                        .serialize()
                    $0.verified = true
                    $0.theirAccessKey = .id(3)
                    $0.presenceSharing = true
                },
                // A safety-number change the user has not accepted yet (fields 8 and 9).
                try BRPBackupContact.with {
                    $0.accountID = .id(4)
                    $0.username = "bob_02"
                    $0.identityKey = try deterministicIdentity("BRP test vector: unaccepted contact").identityKey
                        .serialize()
                    $0.identityUnaccepted = true
                },
            ]
            $0.createdAt = 1_760_000_000_010
        }
    }

    static func backup() throws -> [BackupVector] {
        let plaintext: Data = try sampleAccountBackup().serializedBytes()
        // Backups move between platforms, so the untrimmed, NFKC-normalized
        // key material is pinned here as well.
        let cases = [
            ("yedek parolam: Kağan 2026", 1000), ("backup password, slow", AccountBackupFile.defaultIterations),
            (" ｙｅｄｅｋ\u{00A0}parolası boşluklu ", 1000),
        ]
        return try cases.enumerated().map { index, entry in
            let salt = Data.pattern(16, seed: UInt8(70 + index))
            let nonce = Data.pattern(12, seed: UInt8(80 + index))
            let file = try AccountBackupFile.encrypt(
                plaintext, password: entry.0, salt: salt, iterations: entry.1, nonce: nonce)
            return BackupVector(
                password: entry.0, salt: salt.hexString, iterations: entry.1, nonce: nonce.hexString,
                accountBackup: plaintext.hexString, file: file.hexString)
        }
    }

    struct UnexpectedProblem: Error, CustomStringConvertible {
        let description: String
    }

    /// PROTOCOL.md §5.2 with the cases the README requires. Each code carries
    /// the problem the spec expects, so a regression fails generation instead
    /// of being written into the vectors.
    static func secretCode() throws -> [SecretCodeVector] {
        var cases: [(String, String?)] = [
            // format: never trimmed or normalized; only ASCII digits count.
            ("", "format"), ("47291", "format"), ("4729130", "format"), ("47291a", "format"), (" 472913", "format"),
            ("472913 ", "format"), ("47 913", "format"), ("472913\n", "format"), ("-47291", "format"),
            ("４７２９１３", "format"), ("٤٧٢٩١٣", "format"), ("۴۷۲۹۱۳", "format"), ("47291３", "format"),
        ]
        // repeated: all ten.
        cases += (0...9).map { (String(repeating: String($0), count: 6), "repeated") }
        // sequence: all twenty runs up and down the digit circle, wrapping 9 → 0.
        for start in 0...9 {
            let up = (0..<6).map { String((start + $0) % 10) }.joined()
            cases += [(up, "sequence"), (String(up.reversed()), "sequence")]
        }
        // common: every list entry.
        cases += SecretCode.commonCodes.sorted().map { ($0, "common") }
        // acceptable: dates, a leading zero, runs broken by one digit, steps of 2, ordinary codes.
        cases += [
            ("472913", nil), ("830561", nil), ("200590", nil), ("200000", nil), ("010190", nil), ("012346", nil),
            ("123457", nil), ("987653", nil), ("135792", nil), ("000001", nil), ("999998", nil), ("019283", nil),
        ]
        return try cases.map { code, expected in
            let problem = SecretCode.problem(of: code)
            guard problem == expected else {
                throw UnexpectedProblem(
                    description: "secret code \(code.debugDescription): expected \(expected ?? "nil"), got \(problem ?? "nil")")
            }
            return SecretCodeVector(code: code, problem: problem)
        }
    }

    /// PROTOCOL.md §5.5 with the cases the README requires.
    static func backupPassword() throws -> [BackupPasswordVector] {
        let cases: [(String, String?)] = [
            // too_short: 9 code points, with and without White_Space around them.
            ("abc123456", "too_short"), ("\u{3000}abc123456\u{85}", "too_short"), (" ab12cd345\u{A0}", "too_short"),
            ("ｓｈｏｒｔ１２３４", "too_short"), ("472913", "too_short"),
            // common: list entries in other cases and widths, also inside White_Space.
            ("PASSWORD123", "common"), ("Fenerbahce1907", "common"), ("ｐａｓｓｗｏｒｄ１２３", "common"),
            ("  1234567890\t", "common"), ("QWERTYUIOP", "common"),
            // repeated: case-insensitive and per code point (a final capital sigma stays σ).
            ("AAAAAaaaaa", "repeated"), ("zzzzzzzzzzzz", "repeated"), ("ΣΣΣΣΣΣΣΣΣΣ", "repeated"),
            ("ççççççÇÇÇÇ", "repeated"),
            // sequence: ±1 steps between code points, also beyond the BMP and after NFKC.
            ("abcdefghij", "sequence"), ("JIHGFEDCBA", "sequence"), ("ΑΒΓΔΕΖΗΘΙΚ", "sequence"),
            ("😀😁😂😃😄😅😆😇😈😉", "sequence"), ("ａｂｃｄｅｆｇｈｉｊ", "sequence"),
            // sequence: a run of every keyboard row with at least 10 code points, forward and reversed.
            ("0987654321", "sequence"), ("POIUYTREWQ", "sequence"), ("wertyuıopğ", "sequence"),
            ("üğpoıuytre", "sequence"), ("asdfghjklş", "sequence"), ("işlkjhgfds", "sequence"),
            ("gğıodrnhpq", "sequence"), ("wqphnrdoığ", "sequence"), ("ieaütkmlyş", "sequence"),
            ("xşylmktüae", "sequence"),
            // acceptable: a row run broken by one character, steps of 2, a wrapped row, ordinary passwords.
            ("qwertxuiop", nil), ("acegikmoqs", nil), ("opqwertyui", nil), ("Gizli kod 2026", nil),
            ("correct horse", nil), ("😀😃😄😁😆😅🤣😂😀😃", nil), ("short pass", nil),
            // U+001C is not White_Space, so it stays and counts.
            ("\u{1C}abc123456", nil),
            // İ lowercases to i + U+0307 (default casing, never Turkish), which breaks the row run.
            ("SDFGHJKLŞİ", nil),
        ]
        return try cases.map { password, expected in
            let problem = BackupPassword.problem(of: password)
            guard problem == expected else {
                throw UnexpectedProblem(
                    description: "backup password \(password.debugDescription): expected \(expected ?? "nil"), got \(problem ?? "nil")")
            }
            return BackupPasswordVector(password: password, problem: problem)
        }
    }

    static func keySlot() throws -> [KeySlotVector] {
        try (0..<3).map { index in
            let seed = UInt8(90 + index * 4)
            let dek = Data.pattern(32, seed: seed)
            let slotId = Data.pattern(16, seed: seed + 1)
            let ck = Data.pattern(32, seed: seed + 2)
            let nonce = Data.pattern(12, seed: seed + 3)
            let slotFile = try KeySlot.seal(conversationKey: ck, slotId: slotId, dek: dek, nonce: nonce)
            return KeySlotVector(
                dek: dek.hexString, slotId: slotId.hexString, ck: ck.hexString, nonce: nonce.hexString,
                slotFile: slotFile.hexString)
        }
    }

    static func item() throws -> [ItemVector] {
        try [Data(), Data("Merhaba 👋 dünya".utf8), Data.pattern(1000, seed: 7)].enumerated().map { index, plaintext in
            let seed = UInt8(110 + index * 3)
            let ck = Data.pattern(32, seed: seed)
            let itemId = Data.pattern(16, seed: seed + 1)
            let nonce = Data.pattern(12, seed: seed + 2)
            let item = try ItemCipher.seal(plaintext, itemId: itemId, conversationKey: ck, nonce: nonce)
            return ItemVector(
                ck: ck.hexString, itemId: itemId.hexString, nonce: nonce.hexString, plaintext: plaintext.hexString,
                item: item.hexString)
        }
    }

    static func fingerprint() throws -> [FingerprintVector] {
        try (0..<3).map { index in
            let local = AccountId(bytes: .pattern(16, seed: UInt8(130 + index)))!
            let remote = AccountId(bytes: .pattern(16, seed: UInt8(140 + index)))!
            let localKey = try deterministicIdentity("BRP test vector: fingerprint local \(index)").identityKey
            let remoteKey = try deterministicIdentity("BRP test vector: fingerprint remote \(index)").identityKey
            let number = try SafetyNumber(
                local: local, localIdentity: localKey, remote: remote, remoteIdentity: remoteKey)
            return FingerprintVector(
                localId: local.bytes.hexString, localKey: localKey.serialize().hexString,
                remoteId: remote.bytes.hexString, remoteKey: remoteKey.serialize().hexString,
                displayable: number.digits, scannable: number.scannable.hexString)
        }
    }
}

extension SealedVector.Content {
    init(_ content: BRPSealedContent) {
        self.init(
            senderAccountId: content.senderAccountID.hexString, senderDeviceId: content.senderDeviceID,
            type: content.type, ciphertext: content.ciphertext.hexString, senderUsername: content.senderUsername)
    }
}
