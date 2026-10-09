import CommonCrypto
import CryptoKit
import Foundation
import Testing

@testable import Us2usKit

@Suite("Attachments (§7)")
struct AttachmentTests {
    let key = Data.pattern(64, seed: 1)
    let iv = Data.pattern(16, seed: 2)

    @Test(
        "padding buckets P(n)",
        arguments: [
            (UInt64(0), UInt64(541)), (1, 541), (541, 541), (542, 568), (568, 568), (569, 596), (1000, 1011),
        ])
    func paddedSize(_ size: UInt64, _ expected: UInt64) {
        #expect(AttachmentPadding.paddedSize(for: size) == expected)
    }

    @Test("P(n) is the smallest bucket ≥ n and grows by max(1, ⌊s/20⌋)")
    func paddingSequence() {
        var bucket: UInt64 = 541
        var buckets: [UInt64] = [bucket]
        while bucket < 60_000_000 {
            bucket += max(1, bucket / 20)
            buckets.append(bucket)
        }
        for n in [UInt64(10_000), 1_048_576, 52_428_800] {
            let expected = buckets.first { $0 >= n }!
            #expect(AttachmentPadding.paddedSize(for: n) == expected)
        }
    }

    @Test(
        "blob layout: iv ‖ AES-256-CBC-PKCS7(padded) ‖ HMAC-SHA256(iv ‖ ciphertext)",
        arguments: [0, 1, 541, 600, 5000, 70_000])
    func layout(_ length: Int) throws {
        let plaintext = Data.pattern(length)
        let (blob, info) = try AttachmentCipher.encrypt(plaintext, key: key, iv: iv)
        let padded = plaintext + Data(count: Int(AttachmentPadding.paddedSize(for: UInt64(length))) - length)

        #expect(blob.prefix(16) == iv)
        let ciphertext = blob.dropFirst(16).dropLast(32)
        #expect(ciphertext == referenceCBC(padded))
        let mac = HMAC<SHA256>.authenticationCode(for: blob.dropLast(32), using: SymmetricKey(data: key.suffix(32)))
        #expect(blob.suffix(32) == Data(mac))
        #expect(info.digest == blob.sha256Digest)
        #expect(info.plaintextSize == UInt64(length))
        #expect(info.blobSize == UInt64(blob.count))
        #expect(AttachmentCipher.blobSize(forPlaintextSize: UInt64(length)) == UInt64(blob.count))
        #expect(try AttachmentCipher.decrypt(blob, key: key, digest: info.digest, size: UInt64(length)) == plaintext)
    }

    @Test func randomKeysDiffer() throws {
        let (blobA, infoA) = try AttachmentCipher.encrypt(Data("same".utf8))
        let (blobB, infoB) = try AttachmentCipher.encrypt(Data("same".utf8))
        #expect(infoA.key.count == 64)
        #expect(infoA.key != infoB.key)
        #expect(blobA != blobB)
    }

    @Test("tampering anywhere is detected before decryption")
    func tamper() throws {
        let plaintext = Data.pattern(1000)
        let (blob, info) = try AttachmentCipher.encrypt(plaintext, key: key, iv: iv)
        for index in [0, 15, 16, blob.count / 2, blob.count - 33, blob.count - 1] {
            #expect(throws: AttachmentError.integrityCheckFailed) {
                try AttachmentCipher.decrypt(blob.flippingBit(at: index), key: key, digest: info.digest, size: 1000)
            }
        }
        // A forged blob with a matching digest still fails the HMAC.
        let forged = blob.flippingBit(at: 20)
        #expect(throws: AttachmentError.integrityCheckFailed) {
            try AttachmentCipher.decrypt(forged, key: key, digest: forged.sha256Digest, size: 1000)
        }
        #expect(throws: AttachmentError.integrityCheckFailed) {
            try AttachmentCipher.decrypt(blob, key: key.flippingBit(at: 40), digest: info.digest, size: 1000)
        }
        #expect(throws: AttachmentError.malformedBlob) {
            try AttachmentCipher.decrypt(blob.prefix(63), key: key, digest: info.digest, size: 1000)
        }
        #expect(throws: AttachmentError.malformedBlob) {
            try AttachmentCipher.decrypt(blob.dropLast(1), key: key, digest: info.digest, size: 1000)
        }
    }

    @Test("a size beyond the padded plaintext is rejected")
    func sizeMismatch() throws {
        let (blob, info) = try AttachmentCipher.encrypt(Data.pattern(10), key: key, iv: iv)
        #expect(throws: AttachmentError.sizeMismatch) {
            try AttachmentCipher.decrypt(blob, key: key, digest: info.digest, size: 100_000)
        }
    }

    @Test("files stream through in chunks")
    func files() throws {
        let directory = try TemporaryDirectory()
        let plaintext = Data.pattern(300_000, seed: 9)
        let input = directory.file("in.bin")
        let blob = directory.file("blob")
        let output = directory.file("out.bin")
        try plaintext.write(to: input)

        let info = try AttachmentCipher.encryptFile(at: input, to: blob)
        let blobData = try Data(contentsOf: blob)
        #expect(info.digest == blobData.sha256Digest)
        #expect(info.plaintextSize == 300_000)

        try AttachmentCipher.decryptFile(at: blob, to: output, key: info.key, digest: info.digest, size: 300_000)
        #expect(try Data(contentsOf: output) == plaintext)

        // A failure after verification (here: more bytes announced than the
        // blob holds) removes the partially written plaintext.
        #expect(throws: AttachmentError.sizeMismatch) {
            try AttachmentCipher.decryptFile(at: blob, to: output, key: info.key, digest: info.digest, size: 400_000)
        }
        #expect(!FileManager.default.fileExists(atPath: output.path))

        try blobData.flippingBit(at: 100).write(to: blob)
        #expect(throws: AttachmentError.integrityCheckFailed) {
            try AttachmentCipher.decryptFile(at: blob, to: output, key: info.key, digest: info.digest, size: 300_000)
        }
        #expect(!FileManager.default.fileExists(atPath: output.path))
    }

    private func referenceCBC(_ padded: Data) -> Data {
        var out = Data(count: padded.count + 16)
        var moved = 0
        let status = out.withUnsafeMutableBytes { outBuffer in
            padded.withUnsafeBytes { inBuffer in
                key.prefix(32).withUnsafeBytes { keyBuffer in
                    iv.withUnsafeBytes { ivBuffer in
                        CCCrypt(
                            CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding),
                            keyBuffer.baseAddress, 32, ivBuffer.baseAddress, inBuffer.baseAddress, padded.count,
                            outBuffer.baseAddress, outBuffer.count, &moved)
                    }
                }
            }
        }
        precondition(status == kCCSuccess)
        return out.prefix(moved)
    }
}
