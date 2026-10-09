import Foundation
import Testing

@testable import Us2usKit

/// Writes `spec/test-vectors/swift/*.json` and checks them against this
/// implementation (spec/test-vectors/README.md).
@Suite("Test vectors: Swift generator")
struct SwiftVectorGenerationTests {
    @Test("generate and self-verify", arguments: VectorCategory.allCases)
    func generate(_ category: VectorCategory) throws {
        switch category {
        case .username: try VectorStore.write(VectorGenerator.username(), category: category)
        case .pow: try VectorStore.write(try VectorGenerator.pow(), category: category)
        case .padding: try VectorStore.write(VectorGenerator.padding(), category: category)
        case .attachmentPadding: try VectorStore.write(VectorGenerator.attachmentPadding(), category: category)
        case .attachment: try VectorStore.write(try VectorGenerator.attachment(), category: category)
        case .sealed:
            let existing =
                VectorStore.exists(generator: "swift", .sealed)
                ? try? VectorStore.read(SealedVector.self, generator: "swift", category: .sealed) : nil
            try VectorStore.write(try VectorGenerator.sealed(existing: existing), category: category)
        case .content: try VectorStore.write(try VectorGenerator.content(), category: category)
        case .vault: try VectorStore.write(try VectorGenerator.vault(), category: category)
        case .backup: try VectorStore.write(try VectorGenerator.backup(), category: category)
        case .secretCode: try VectorStore.write(try VectorGenerator.secretCode(), category: category)
        case .backupPassword: try VectorStore.write(try VectorGenerator.backupPassword(), category: category)
        case .keyslot: try VectorStore.write(try VectorGenerator.keySlot(), category: category)
        case .item: try VectorStore.write(try VectorGenerator.item(), category: category)
        case .fingerprint: try VectorStore.write(try VectorGenerator.fingerprint(), category: category)
        }
        let failures = try VectorVerifier.verify(generator: "swift", category)
        #expect(failures.isEmpty, "\(failures)")
    }

    @Test("the README's coverage requirements hold")
    func coverage() throws {
        let usernames = VectorGenerator.username()
        #expect(usernames.count >= 12)
        #expect(usernames.contains { $0.normalized == nil } && usernames.contains { $0.normalized != nil })
        let pow = try VectorGenerator.pow()
        #expect(Set(pow.filter(\.valid).map(\.difficulty)).isSuperset(of: [0, 1, 8, 12, 16]))
        #expect(pow.contains { !$0.valid })

        // Secret codes: every repeated and sequence code, every list entry, both outcomes.
        let codes = try VectorGenerator.secretCode()
        #expect(codes.filter { $0.problem == "repeated" }.count == 10)
        #expect(codes.filter { $0.problem == "sequence" }.count == 20)
        #expect(Set(codes.filter { $0.problem == "common" }.map(\.code)) == SecretCode.commonCodes)
        #expect(codes.contains { $0.problem == "format" } && codes.contains { $0.problem == nil })
        let vaults = try VectorGenerator.vault()
        #expect(vaults.allSatisfy { SecretCode.isWellFormed($0.code) })
        #expect(vaults.contains { $0.code.hasPrefix("0") })
        #expect(Set(vaults.map(\.iterations)) == [1000, VaultFormat.defaultIterations])
        let passwords = try VectorGenerator.backupPassword()
        #expect(Set(passwords.map(\.problem)) == [nil, "too_short", "common", "repeated", "sequence"])
    }
}

/// Verifies `spec/test-vectors/kotlin/*.json`. Each category is skipped until
/// the Kotlin implementation has written it.
@Suite("Test vectors: Kotlin files verified by Swift")
struct KotlinVectorVerificationTests {
    private func verify(_ category: VectorCategory) throws {
        let failures = try VectorVerifier.verify(generator: "kotlin", category)
        #expect(failures.isEmpty, "\(failures)")
    }

    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .username))) func username() throws {
        try verify(.username)
    }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .pow))) func pow() throws { try verify(.pow) }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .padding))) func padding() throws {
        try verify(.padding)
    }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .attachmentPadding)))
    func attachmentPadding() throws { try verify(.attachmentPadding) }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .attachment))) func attachment() throws {
        try verify(.attachment)
    }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .sealed))) func sealed() throws { try verify(.sealed) }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .content))) func content() throws {
        try verify(.content)
    }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .vault))) func vault() throws { try verify(.vault) }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .backup))) func backup() throws { try verify(.backup) }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .secretCode))) func secretCode() throws {
        try verify(.secretCode)
    }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .backupPassword))) func backupPassword() throws {
        try verify(.backupPassword)
    }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .keyslot))) func keySlot() throws {
        try verify(.keyslot)
    }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .item))) func item() throws { try verify(.item) }
    @Test(.enabled(if: VectorStore.exists(generator: "kotlin", .fingerprint)))
    func fingerprint() throws { try verify(.fingerprint) }
}
