import Foundation
import Security
import Testing

@testable import Us2usKit

@Suite("Secret code rules (§5.2)")
struct SecretCodeTests {
    @Test("acceptable codes", arguments: ["472913", "830561", "200590", "200000", "012346", "123457", "135792"])
    func accepted(_ code: String) throws {
        try SecretCode.validate(code)
    }

    @Test(
        "rejected codes",
        arguments: [
            ("", SecretCodeError.notSixDigits),
            ("47291", .notSixDigits),
            ("4729130", .notSixDigits),
            (" 472913", .notSixDigits),  // never trimmed
            ("47 913", .notSixDigits),
            ("47291a", .notSixDigits),
            ("４７２９１３", .notSixDigits),  // fullwidth digits are not ASCII
            ("٤٧٢٩١٣", .notSixDigits),  // nor are Arabic-Indic ones
            ("000000", .repeatedDigit),
            ("777777", .repeatedDigit),
            ("123456", .sequence),
            ("654321", .sequence),
            ("890123", .sequence),  // wraps 9 → 0
            ("321098", .sequence),
            ("098765", .sequence),
            ("121212", .tooCommon),
            ("112233", .tooCommon),
            ("123123", .tooCommon),
            ("696969", .tooCommon),
            ("111222", .tooCommon),
            ("159753", .tooCommon),
            ("147258", .tooCommon),
            ("102030", .tooCommon),
        ])
    func rejected(_ code: String, _ error: SecretCodeError) {
        #expect(throws: error) { try SecretCode.validate(code) }
    }

    @Test("exactly 10 repeated and 20 sequence codes exist")
    func ruleSizes() {
        var repeated = 0
        var sequences = 0
        for value in 0..<1_000_000 {
            let code = String(format: "%06d", value)
            do {
                try SecretCode.validate(code)
            } catch .repeatedDigit {
                repeated += 1
            } catch .sequence {
                sequences += 1
            } catch {}
        }
        #expect(repeated == 10)
        #expect(sequences == 20)
    }

    @Test func confirmation() throws {
        try SecretCode.validate("472913", confirmation: "472913")
        #expect(throws: SecretCodeError.mismatch) { try SecretCode.validate("472913", confirmation: "472914") }
        #expect(throws: SecretCodeError.mismatch) { try SecretCode.validate("472913", confirmation: "４７２９１３") }
        #expect(throws: SecretCodeError.repeatedDigit) { try SecretCode.validate("000000", confirmation: "000000") }
    }

    @Test("the key material is the six ASCII bytes")
    func keyMaterial() {
        #expect(SecretCode.keyMaterial("012345") == Data([0x30, 0x31, 0x32, 0x33, 0x34, 0x35]))
        #expect(SecretCode.isWellFormed("012345"))
        #expect(!SecretCode.isWellFormed("01234"))
        #expect(!SecretCode.isWellFormed("０１２３４５"))
    }

    @Test("the bundled list is identical to spec/common-secret-codes.txt and only lists six-digit codes")
    func bundledListMatchesSpec() throws {
        let spec = BundledList.parse(try String(contentsOf: RepositoryPaths.commonSecretCodes, encoding: .utf8))
        #expect(SecretCode.commonCodes == Set(spec))
        #expect(spec == spec.sorted() && Set(spec).count == spec.count, "sorted, no duplicates")
        #expect(spec.count >= 50)
        for code in spec {
            #expect(SecretCode.isWellFormed(code), "\(code)")
            // Rules 2 and 3 never apply to a listed code, so the rule order cannot matter.
            #expect(throws: SecretCodeError.tooCommon, "\(code)") { try SecretCode.validate(code) }
        }
    }
}

@Suite("Backup password rules (§5.5)")
struct BackupPasswordTests {
    @Test(
        "acceptable passwords",
        arguments: ["correct horse", "Gizli kod 2026", "ğüşıöç1234", "123456789x", "abcdefghik", "😀😃😄😁😆😅🤣😂😀😃"])
    func accepted(_ password: String) throws {
        try BackupPassword.validate(password)
    }

    @Test(
        "rejected passwords",
        arguments: [
            ("short pas", BackupPasswordError.tooShort),  // 9 code points
            ("   abc12345   ", .tooShort),  // trimmed before counting
            ("472913", .tooShort),  // a secret code can never be a backup password
            ("1234567890", .tooCommon),
            ("PASSWORD123", .tooCommon),  // compared lowercased
            ("ｐａｓｓｗｏｒｄ１２３", .tooCommon),  // and after NFKC
            ("aaaaaaaaaaaa", .repeatedCharacter),
            ("abcdefghij", .simpleSequence),
            ("jihgfedcba", .simpleSequence),
            ("POIUYTREWQ", .simpleSequence),  // reversed keyboard row, any case
            ("0987654321", .simpleSequence),  // reversed number row
            ("asdfghjklşi", .simpleSequence),  // Turkish Q row
        ] as [(String, BackupPasswordError)])
    func rejected(_ password: String, _ error: BackupPasswordError) {
        #expect(throws: error) { try BackupPassword.validate(password) }
    }

    @Test("10 code points are enough")
    func minimumLength() throws {
        try BackupPassword.validate("short pass")
        try BackupPassword.validate("\u{1C}abc123456")  // U+001C is not White_Space: it counts
    }

    @Test func confirmation() throws {
        try BackupPassword.validate("yedek parolam 2026", confirmation: "yedek parolam 2026")
        #expect(throws: BackupPasswordError.mismatch) {
            try BackupPassword.validate("yedek parolam 2026", confirmation: "yedek parolam 2027")
        }
    }

    @Test("the key material is UTF-8(NFKC(password)) without trimming")
    func keyMaterial() {
        #expect(BackupPassword.keyMaterial("ｓｅｃｒｅｔ") == Data("secret".utf8))
        #expect(BackupPassword.keyMaterial(" a ") == Data(" a ".utf8))
        // "é" precomposed and decomposed derive the same key.
        #expect(BackupPassword.keyMaterial("caf\u{E9}") == BackupPassword.keyMaterial("cafe\u{301}"))
    }

    @Test("the bundled list is identical to spec/common-backup-passwords.txt")
    func bundledListMatchesSpec() throws {
        let spec = BundledList.parse(
            try String(contentsOf: RepositoryPaths.commonBackupPasswords, encoding: .utf8))
        #expect(BackupPassword.commonPasswords == Set(spec.map(BackupPassword.comparisonForm)))
        #expect(spec.count > 50)
        for entry in spec {
            #expect(entry.nfkc.unicodeScalars.count >= BackupPassword.minimumLength, "\(entry)")
            #expect(throws: BackupPasswordError.tooCommon, "\(entry)") { try BackupPassword.validate(entry) }
        }
    }
}

@Suite("Vault format (§5.2)")
struct VaultFormatTests {
    let dek = Data.pattern(32, seed: 1)
    let deviceSecret = Data.pattern(32, seed: 2)
    let salt = Data.pattern(16, seed: 3)
    let nonce = Data.pattern(12, seed: 4)

    @Test("layout: header(25) ‖ nonce(12) ‖ ciphertext(32) ‖ tag(16)")
    func layout() throws {
        let vault = try VaultFormat.seal(
            dek: dek, code: "472913", deviceSecret: deviceSecret, salt: salt, iterations: 1000, nonce: nonce)
        #expect(vault.count == 25 + 12 + 32 + 16)
        #expect(vault.prefix(5) == Data("BRPV".utf8) + [0x01])
        #expect(vault[5..<21] == salt)
        #expect(vault[21..<25] == Data([0x00, 0x00, 0x03, 0xE8]))
        #expect(vault[25..<37] == nonce)

        // KEK = HKDF(PBKDF2(six ASCII digits) ‖ deviceSecret, salt, "BRP/vault/v1").
        let codeKey = PBKDF2.sha256(password: Data("472913".utf8), salt: salt, iterations: 1000)
        let kek = HKDFSHA256.derive(
            inputKeyMaterial: codeKey + deviceSecret, salt: salt, info: Data("BRP/vault/v1".utf8))
        #expect(try AES256GCM.open(vault[37...], key: kek, nonce: nonce, associatedData: vault.prefix(25)) == dek)
        #expect(try VaultFormat.open(vault, code: "472913", deviceSecret: deviceSecret) == dek)
    }

    @Test("wrong code, other device secret and tampering all fail")
    func failures() throws {
        let vault = try VaultFormat.seal(
            dek: dek, code: "472913", deviceSecret: deviceSecret, salt: salt, iterations: 1000, nonce: nonce)
        #expect(throws: VaultError.wrongCode) {
            try VaultFormat.open(vault, code: "472914", deviceSecret: deviceSecret)
        }
        #expect(throws: VaultError.wrongCode) {
            try VaultFormat.open(vault, code: "472913", deviceSecret: deviceSecret.flippingBit(at: 0))
        }
        for index in [5, 22, 30, vault.count - 1] {  // salt, iterations (aad), nonce, tag
            #expect(throws: VaultError.self) {
                try VaultFormat.open(vault.flippingBit(at: index), code: "472913", deviceSecret: deviceSecret)
            }
        }
        #expect(throws: VaultError.corrupted) {
            try VaultFormat.open(vault.flippingBit(at: 0), code: "472913", deviceSecret: deviceSecret)
        }
        #expect(throws: VaultError.corrupted) {
            try VaultFormat.open(vault.prefix(40), code: "472913", deviceSecret: deviceSecret)
        }
    }

    @Test(
        "iteration counts outside 1000…10000000 are refused before deriving anything",
        arguments: [0, 1, 999, 10_000_001, 0xFFFF_FFFF] as [UInt32])
    func iterationBound(_ iterations: UInt32) throws {
        var vault = try VaultFormat.seal(
            dek: dek, code: "472913", deviceSecret: deviceSecret, salt: salt, iterations: 1000, nonce: nonce)
        vault.replaceSubrange(21..<25, with: withUnsafeBytes(of: iterations.bigEndian) { Data($0) })
        #expect(throws: VaultError.corrupted) {
            try VaultFormat.open(vault, code: "472913", deviceSecret: deviceSecret)
        }
        var backup = try AccountBackupFile.encrypt(
            Data([1, 2, 3]), password: "x", salt: salt, iterations: 1000, nonce: nonce)
        backup.replaceSubrange(21..<25, with: withUnsafeBytes(of: iterations.bigEndian) { Data($0) })
        #expect(throws: AccountBackupError.invalidFile) { try AccountBackupFile.decrypt(backup, password: "x") }
    }

    @Test("the lower bound itself is accepted")
    func iterationLowerBound() throws {
        let vault = try VaultFormat.seal(
            dek: dek, code: "472913", deviceSecret: deviceSecret, salt: salt, iterations: 1000, nonce: nonce)
        #expect(throws: VaultError.wrongCode) {
            try VaultFormat.open(vault, code: "472914", deviceSecret: deviceSecret)
        }
    }

    @Test("only six ASCII digits are a code: anything else is refused before deriving anything")
    func malformedCodes() throws {
        for code in ["", "47291", "4729130", "47291a", "４７２９１３", " 472913"] {
            #expect(throws: VaultError.malformedCode) {
                try VaultFormat.seal(
                    dek: dek, code: code, deviceSecret: deviceSecret, salt: salt, iterations: 1000, nonce: nonce)
            }
        }
        let vault = try VaultFormat.seal(
            dek: dek, code: "012345", deviceSecret: deviceSecret, salt: salt, iterations: 1000, nonce: nonce)
        #expect(throws: VaultError.malformedCode) {
            try VaultFormat.open(vault, code: "０１２３４５", deviceSecret: deviceSecret)
        }
        #expect(try VaultFormat.open(vault, code: "012345", deviceSecret: deviceSecret) == dek)
    }
}

@Suite("Local vault (§5.2)")
struct LocalVaultTests {
    /// Records the persisted failure counter each time the vault reads the
    /// device secret, i.e. just before the code is checked.
    final class AttemptSpy: DeviceSecretProvider {
        private let inner = InMemoryDeviceSecretProvider()
        private let attemptsFile: URL
        private let seen = Locked<[Int]>([])

        init(attemptsFile: URL) {
            self.attemptsFile = attemptsFile
        }

        var failuresOnDisk: [Int] { seen.withLock { $0 } }

        func loadDeviceSecret() throws -> Data? {
            let attempts = try? JSONDecoder().decode(UnlockAttempts.self, from: Data(contentsOf: attemptsFile))
            seen.withLock { $0.append(attempts?.failures ?? 0) }
            return try inner.loadDeviceSecret()
        }

        func storeDeviceSecret(_ secret: Data) throws { try inner.storeDeviceSecret(secret) }
        func deleteDeviceSecret() throws { try inner.deleteDeviceSecret() }
    }

    /// A wall clock the user could set and a time-since-boot that only runs
    /// forward (and restarts at a reboot).
    final class Clock: Sendable {
        private struct State {
            var wall = Date(timeIntervalSince1970: 1_800_000_000)
            var uptime: TimeInterval = 50_000
        }

        private let state = Locked(State())
        var now: Date { state.withLock { $0.wall } }
        var uptime: TimeInterval { state.withLock { $0.uptime } }

        /// Real time passes: both clocks advance.
        func advance(_ seconds: TimeInterval) {
            state.withLock {
                $0.wall += seconds
                $0.uptime += seconds
            }
        }

        /// Someone changes the date in Settings.
        func setWallClock(by seconds: TimeInterval) { state.withLock { $0.wall += seconds } }

        /// The phone restarts after `seconds`: the time since boot starts again.
        func reboot(after seconds: TimeInterval, uptimeAfterBoot: TimeInterval) {
            state.withLock {
                $0.wall += seconds
                $0.uptime = uptimeAfterBoot
            }
        }
    }

    static let code = "472913"
    static let wrong = "000001"

    let directory: TemporaryDirectory
    let secrets = InMemoryDeviceSecretProvider()
    let clock = Clock()

    init() throws {
        directory = try TemporaryDirectory()
    }

    private func vault(wipeAfter: Int? = nil) -> LocalVault {
        let clock = self.clock
        return LocalVault(
            directory: directory.url, deviceSecrets: secrets,
            configuration: .init(iterations: 1000, wipeAfterFailures: wipeAfter), now: { clock.now },
            uptime: { clock.uptime })
    }

    @Test func createUnlockChange() throws {
        let vault = vault()
        #expect(!vault.exists)
        #expect(throws: VaultError.notFound) { try vault.unlock(code: Self.code) }
        let dek = try vault.create(code: Self.code, confirmation: Self.code)
        #expect(dek.count == 32)
        #expect(vault.exists)
        #expect(try secrets.loadDeviceSecret()?.count == 32)
        #expect(throws: VaultError.alreadyExists) { try vault.create(code: "830561", confirmation: "830561") }
        #expect(try vault.unlock(code: Self.code) == dek)

        try vault.changeCode(current: Self.code, new: "830561", confirmation: "830561")
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.code) }
        #expect(try vault.unlock(code: "830561") == dek)  // same DEK: nothing re-encrypted
    }

    @Test("everything hangs off the secret code: a code change keeps all data, a wrong code opens nothing")
    func keyHierarchy() throws {
        let vault = vault()
        let slots = KeySlotStore(directory: directory.url.appendingPathComponent("slots"))
        let dek = try vault.create(code: Self.code, confirmation: Self.code)
        let (slotId, conversationKey) = try slots.createSlot(dek: dek)
        let messageId = Data.id(1)
        let item = try ItemCipher.seal(Data("Merhaba".utf8), itemId: messageId, conversationKey: conversationKey)
        let databaseKey = LocalKeyDerivation.databaseKey(dek: dek)

        try vault.changeCode(current: Self.code, new: "830561", confirmation: "830561")
        let reopened = try vault.unlock(code: "830561")
        #expect(LocalKeyDerivation.databaseKey(dek: reopened) == databaseKey)
        let ck = try slots.conversationKey(slotId: slotId, dek: reopened)
        #expect(try ItemCipher.open(item, itemId: messageId, conversationKey: ck) == Data("Merhaba".utf8))

        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.code) }
        #expect(throws: LocalStorageError.integrityCheckFailed) {
            try slots.conversationKey(slotId: slotId, dek: Data(count: 32))
        }
    }

    @Test func newCodesAreValidated() throws {
        let vault = vault()
        #expect(throws: VaultError.invalidNewCode(.sequence)) { try vault.create(code: "123456", confirmation: "123456") }
        #expect(throws: VaultError.invalidNewCode(.notSixDigits)) {
            try vault.create(code: "Gizli kod 2026", confirmation: "Gizli kod 2026")
        }
        _ = try vault.create(code: Self.code, confirmation: Self.code)
        #expect(throws: VaultError.invalidNewCode(.mismatch)) {
            try vault.changeCode(current: Self.code, new: "830561", confirmation: "830562")
        }
        #expect(throws: VaultError.invalidNewCode(.tooCommon)) {
            try vault.changeCode(current: Self.code, new: "121212", confirmation: "121212")
        }
    }

    @Test("delays after wrong attempts: 3rd → 1 min, 4th → 5 min, …, 7th+ → 1 h")
    func throttle() throws {
        let vault = vault()
        let dek = try vault.create(code: Self.code, confirmation: Self.code)
        #expect(vault.status() == UnlockStatus(failures: 0, nextAttemptAllowedAt: nil, upcoming: .init(attemptsLeft: 3, delay: 60)))

        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.wrong) }
        // The design's sentence: "Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir."
        #expect(vault.status() == UnlockStatus(failures: 1, nextAttemptAllowedAt: nil, upcoming: .init(attemptsLeft: 2, delay: 60)))
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.wrong) }
        #expect(vault.nextAttemptAllowedAt() == nil)
        #expect(vault.status().upcoming == UpcomingDelay(attemptsLeft: 1, delay: 60))

        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.wrong) }  // 3rd
        let until = clock.now.addingTimeInterval(60)
        #expect(vault.nextAttemptAllowedAt() == until)
        #expect(throws: VaultError.lockedOut(until: until)) { try vault.unlock(code: Self.code) }
        #expect(vault.status().failures == 3, "a refused attempt is not counted")
        #expect(vault.status().upcoming == UpcomingDelay(attemptsLeft: 1, delay: 300))

        clock.advance(60)
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.wrong) }  // 4th
        #expect(vault.nextAttemptAllowedAt() == clock.now.addingTimeInterval(300))
        clock.advance(300)
        #expect(try vault.unlock(code: Self.code) == dek)
        #expect(vault.nextAttemptAllowedAt() == nil)  // success resets the counter
        #expect(vault.status().failures == 0)

        let throttle = UnlockThrottle.standard
        #expect(
            [0, 1, 2, 3, 4, 5, 6, 7, 20].map(throttle.delay(afterFailures:))
                == [0, 0, 0, 60, 300, 900, 1800, 3600, 3600])
        #expect(throttle.upcomingDelay(afterFailures: 6) == UpcomingDelay(attemptsLeft: 1, delay: 3600))
        #expect(throttle.upcomingDelay(afterFailures: 40) == UpcomingDelay(attemptsLeft: 1, delay: 3600))
        #expect(UnlockThrottle(delays: [0]).upcomingDelay(afterFailures: 3) == nil)
    }

    @Test("changing the date or restarting the phone never shortens a delay")
    func clockManipulation() throws {
        let vault = vault()
        let dek = try vault.create(code: Self.code, confirmation: Self.code)
        for _ in 1...4 {
            _ = try? vault.unlock(code: Self.wrong)
            clock.advance(300)
        }
        #expect(vault.status().failures == 4)
        // Waited 300 s after the 4th failure: the 5 min delay is over. The 5th starts 15 min.
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.wrong) }

        // Moving the date a day ahead does not help.
        clock.setWallClock(by: 86_400)
        #expect(throws: VaultError.lockedOut(until: clock.now.addingTimeInterval(900))) {
            try vault.unlock(code: Self.code)
        }
        // Moving it back does not extend the wait either.
        clock.setWallClock(by: -2 * 86_400)
        clock.advance(900)
        #expect(vault.nextAttemptAllowedAt() == nil)
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.wrong) }  // 6th: 30 min

        // A restart 10 minutes later: only the 2 minutes since boot count.
        clock.reboot(after: 600, uptimeAfterBoot: 120)
        #expect(vault.nextAttemptAllowedAt() == clock.now.addingTimeInterval(1800 - 120))
        clock.advance(1800 - 120)
        #expect(try vault.unlock(code: Self.code) == dek)
    }

    @Test("the time waited never goes backwards, also when the new boot's uptime passes the old reading")
    func waitedIsMonotonic() throws {
        let vault = vault()
        _ = try vault.create(code: Self.code, confirmation: Self.code)
        for _ in 1...3 { _ = try? vault.unlock(code: Self.wrong) }  // 3rd → 1 min
        let reading = clock.uptime
        // Restarted and up for almost as long as before: all of it counts.
        clock.reboot(after: 7200, uptimeAfterBoot: reading - 30)
        #expect(vault.nextAttemptAllowedAt() == nil)
        // Later the uptime passes the old reading; the wait is still over.
        clock.advance(60)
        #expect(vault.nextAttemptAllowedAt() == nil)
        #expect(vault.status().failures == 3)
    }

    @Test("a restart nobody observed counts only the time after the old reading (a lower bound)")
    func unobservedRestart() throws {
        let vault = vault()
        _ = try vault.create(code: Self.code, confirmation: Self.code)
        for _ in 1...3 { _ = try? vault.unlock(code: Self.wrong) }  // 3rd → 1 min
        clock.reboot(after: 7200, uptimeAfterBoot: clock.uptime + 10)
        #expect(vault.nextAttemptAllowedAt() == clock.now.addingTimeInterval(50))
    }

    @Test("input that is not six digits is refused before anything else and never counted")
    func malformedInput() throws {
        let vault = vault()
        _ = try vault.create(code: Self.code, confirmation: Self.code)
        for input in ["", "47291", "4729130", "４７２９１３", "47291a"] {
            #expect(throws: VaultError.malformedCode) { try vault.unlock(code: input) }
        }
        #expect(vault.status().failures == 0)
    }

    @Test("optional wipe after N wrong attempts crypto-shreds the vault")
    func wipe() throws {
        let vault = vault(wipeAfter: 3)
        _ = try vault.create(code: Self.code, confirmation: Self.code)
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: "000001") }
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: "000002") }
        #expect(throws: VaultError.wiped) { try vault.unlock(code: "000003") }
        #expect(!vault.exists)
        #expect(try secrets.loadDeviceSecret() == nil)
        #expect(LocalVault.Configuration.wipeOptionFailures == 10)
    }

    @Test("the wipe option fires on the 10th wrong code in a row, waiting out every delay")
    func wipeAfterTen() throws {
        let vault = vault(wipeAfter: LocalVault.Configuration.wipeOptionFailures)
        _ = try vault.create(code: Self.code, confirmation: Self.code)
        for _ in 1...9 {
            #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.wrong) }
            clock.advance(3600)
        }
        #expect(vault.exists)
        #expect(throws: VaultError.wiped) { try vault.unlock(code: Self.wrong) }
        #expect(!vault.exists)
    }

    @Test("an attempt is on disk before the code is checked, so killing the app mid-check skips no delay")
    func attemptRecordedBeforeCheck() throws {
        let spy = AttemptSpy(attemptsFile: directory.file("vault-attempts.json"))
        let clock = self.clock
        let vault = LocalVault(
            directory: directory.url, deviceSecrets: spy, configuration: .init(iterations: 1000),
            now: { clock.now }, uptime: { clock.uptime })
        let dek = try vault.create(code: Self.code, confirmation: Self.code)
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.wrong) }
        #expect(try vault.unlock(code: Self.code) == dek)
        // The device secret is read only after the pending failure was written.
        #expect(spy.failuresOnDisk == [1, 2])
        #expect(vault.nextAttemptAllowedAt() == nil)

        // A failure that is not a guess (no device secret) does not count.
        try spy.deleteDeviceSecret()
        #expect(throws: VaultError.deviceSecretMissing) { try vault.unlock(code: Self.code) }
        let attempts = try JSONDecoder().decode(
            UnlockAttempts.self, from: Data(contentsOf: directory.file("vault-attempts.json")))
        #expect(attempts.failures == 0)
    }

    @Test("a copied vault file is useless without this device's secret")
    func deviceSecretMissing() throws {
        let vault = vault()
        _ = try vault.create(code: Self.code, confirmation: Self.code)
        try secrets.deleteDeviceSecret()
        #expect(throws: VaultError.deviceSecretMissing) { try vault.unlock(code: Self.code) }
        try secrets.storeDeviceSecret(Data(count: 32))
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.code) }
    }

    @Test("erase everything")
    func destroy() throws {
        let vault = vault()
        _ = try vault.create(code: Self.code, confirmation: Self.code)
        try vault.destroy()
        #expect(!vault.exists)
        #expect(try secrets.loadDeviceSecret() == nil)
        #expect(throws: VaultError.notFound) { try vault.unlock(code: Self.code) }
    }

    @Test("the system uptime runs forward")
    func systemUptime() {
        let first = LocalVault.systemUptime()
        #expect(first > 0)
        #expect(LocalVault.systemUptime() >= first)
    }
}

/// Hostless package tests in the simulator run inside Apple's `xctest` runner,
/// which has no keychain entitlement: every SecItem call returns
/// errSecMissingEntitlement (-34018). The Keychain test therefore runs only
/// where the process may use the keychain (app-hosted tests, devices);
/// `ios/scripts/keychain-smoke.sh` checks the provider on the Simulator instead.
enum KeychainProbe {
    static let isAvailable: Bool = {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.syconx.us2us.tests.probe",
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        return SecItemCopyMatching(query as CFDictionary, nil) != errSecMissingEntitlement
    }()
}

@Suite(
    "Keychain device secret",
    .enabled(
        if: KeychainProbe.isAvailable,
        "no keychain entitlement in hostless simulator tests (-34018); run ios/scripts/keychain-smoke.sh"))
struct KeychainDeviceSecretTests {
    let directory: TemporaryDirectory

    init() throws {
        directory = try TemporaryDirectory()
    }

    @Test("store, replace, delete; WhenUnlockedThisDeviceOnly; vault on top")
    func keychain() throws {
        let provider = KeychainDeviceSecretProvider(service: "com.syconx.us2us.tests", account: UUID().uuidString)
        defer { try? provider.deleteDeviceSecret() }
        #expect(try provider.loadDeviceSecret() == nil)
        let secret = Data.pattern(32, seed: 5)
        try provider.storeDeviceSecret(secret)
        #expect(try provider.loadDeviceSecret() == secret)
        try provider.storeDeviceSecret(Data.pattern(32, seed: 6))
        #expect(try provider.loadDeviceSecret() == Data.pattern(32, seed: 6))
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: provider.service,
            kSecAttrAccount as String: provider.account,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        #expect(status == errSecSuccess)
        let attributes = result as? [String: Any]
        #expect(
            attributes?[kSecAttrAccessible as String] as? String
                == kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
        #expect((attributes?[kSecAttrSynchronizable as String] as? NSNumber)?.boolValue != true)
        try provider.deleteDeviceSecret()
        #expect(try provider.loadDeviceSecret() == nil)

        let vault = LocalVault(
            directory: directory.url, deviceSecrets: provider, configuration: .init(iterations: 1000))
        let dek = try vault.create(code: "472913", confirmation: "472913")
        #expect(try vault.unlock(code: "472913") == dek)
    }
}

@Suite("Key slots, items and the database key (§5.3)")
struct ConversationKeyTests {
    let dek = Data.pattern(32, seed: 7)
    let ck = Data.pattern(32, seed: 8)
    let slotId = Data.id(9)

    @Test func keySlotLayout() throws {
        let nonce = Data.pattern(12, seed: 1)
        let slot = try KeySlot.seal(conversationKey: ck, slotId: slotId, dek: dek, nonce: nonce)
        #expect(slot.count == 5 + 12 + 32 + 16)
        #expect(slot.prefix(5) == Data("BRPS".utf8) + [0x01])
        let slotKey = HKDFSHA256.derive(inputKeyMaterial: dek, salt: Data(), info: Data("BRP/slot/v1".utf8))
        #expect(
            try AES256GCM.open(
                slot[17...], key: slotKey, nonce: nonce, associatedData: Data("BRPS".utf8) + [0x01] + slotId)
                == ck)
        #expect(try KeySlot.open(slot, slotId: slotId, dek: dek) == ck)
    }

    @Test("slots are bound to their id and the DEK")
    func keySlotTamper() throws {
        let slot = try KeySlot.seal(conversationKey: ck, slotId: slotId, dek: dek)
        #expect(throws: LocalStorageError.integrityCheckFailed) { try KeySlot.open(slot, slotId: .id(10), dek: dek) }
        #expect(throws: LocalStorageError.integrityCheckFailed) {
            try KeySlot.open(slot, slotId: slotId, dek: dek.flippingBit(at: 0))
        }
        #expect(throws: LocalStorageError.integrityCheckFailed) {
            try KeySlot.open(slot.flippingBit(at: 30), slotId: slotId, dek: dek)
        }
        #expect(throws: LocalStorageError.malformed) {
            try KeySlot.open(slot.flippingBit(at: 4), slotId: slotId, dek: dek)
        }
    }

    @Test func items() throws {
        let itemId = Data.id(3)
        let item = try ItemCipher.seal(Data("Merhaba".utf8), itemId: itemId, conversationKey: ck)
        #expect(item.first == 0x01)
        #expect(item.count == 1 + 12 + 7 + 16)
        #expect(try ItemCipher.open(item, itemId: itemId, conversationKey: ck) == Data("Merhaba".utf8))
        // Items cannot be moved to another row.
        #expect(throws: LocalStorageError.integrityCheckFailed) {
            try ItemCipher.open(item, itemId: .id(4), conversationKey: ck)
        }
        #expect(throws: LocalStorageError.integrityCheckFailed) {
            try ItemCipher.open(item.flippingBit(at: 15), itemId: itemId, conversationKey: ck)
        }
        #expect(throws: LocalStorageError.malformed) {
            try ItemCipher.open(Data([0x02]) + item.dropFirst(), itemId: itemId, conversationKey: ck)
        }
        #expect(
            try ItemCipher.open(
                try ItemCipher.seal(Data(), itemId: itemId, conversationKey: ck), itemId: itemId, conversationKey: ck
            ).isEmpty)
    }

    @Test("dbKey = HKDF-SHA256(DEK, empty salt, \"BRP/db/v1\")")
    func databaseKey() {
        let key = LocalKeyDerivation.databaseKey(dek: dek)
        #expect(key.count == 32)
        // An empty HKDF salt equals HashLen zero bytes (RFC 5869 §2.2).
        #expect(key == HKDFSHA256.derive(inputKeyMaterial: dek, salt: Data(count: 32), info: Data("BRP/db/v1".utf8)))
        #expect(key != LocalKeyDerivation.slotKey(dek: dek))
    }

    @Test("HKDF-SHA256 matches RFC 5869 test case 1")
    func hkdfKnownAnswer() {
        let okm = HKDFSHA256.derive(
            inputKeyMaterial: Data(repeating: 0x0B, count: 22), salt: Data(hex: "000102030405060708090a0b0c"),
            info: Data(hex: "f0f1f2f3f4f5f6f7f8f9"), length: 42)
        #expect(okm.hexString == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    }

    @Test("ghost schedule: expired slots are destroyed without the DEK (§5.3, §8.2)")
    func slotStore() throws {
        let directory = try TemporaryDirectory()
        let store = KeySlotStore(directory: directory.url)
        let (first, firstKey) = try store.createSlot(dek: dek)
        let (second, _) = try store.createSlot(dek: dek)
        #expect(Set(try store.slotIds()) == [first, second])
        #expect(try store.conversationKey(slotId: first, dek: dek) == firstKey)

        let now = Date(timeIntervalSince1970: 1_800_000_000)
        try store.setDeadline(now.addingTimeInterval(-1), for: first)
        try store.setDeadline(now.addingTimeInterval(3600), for: second)
        #expect(try store.deadlines().count == 2)

        // A fresh instance, as a background task would create, sees the schedule.
        let background = KeySlotStore(directory: directory.url)
        #expect(try background.destroyExpiredSlots(now: now) == [first])
        #expect(try store.slotIds() == [second])
        #expect(throws: LocalStorageError.self) { try store.conversationKey(slotId: first, dek: dek) }
        #expect(Array(try store.deadlines().keys) == [second])

        let schedule = try String(contentsOf: directory.file("schedule.json"), encoding: .utf8)
        #expect(schedule.contains(second.hexString))
        #expect(!schedule.contains(first.hexString))

        try store.setDeadline(nil, for: second)
        try store.destroySlot(second)
        #expect(try store.slotIds().isEmpty)
        #expect(try store.deadlines().isEmpty)
    }
}

@Suite("Account backup file (§5.5)")
struct AccountBackupTests {
    private func sampleBackup() throws -> BRPAccountBackup {
        var backup = BRPAccountBackup()
        backup.version = 1
        backup.accountID = .id(1)
        backup.authToken = .pattern(32)
        backup.identityKeyPair = try deterministicIdentity("backup").serialize()
        backup.registrationID = 1234
        backup.username = "alice_01"
        backup.displayName = "Alice"
        backup.accessKey = .id(13)
        var contact = BRPBackupContact()
        contact.accountID = .id(2)
        contact.username = "bob_02"
        contact.identityKey = try deterministicIdentity("bob").identityKey.serialize()
        contact.verified = true
        contact.theirAccessKey = .id(3)
        backup.contacts = [contact]
        backup.createdAt = 1_760_000_000_000
        return backup
    }

    @Test func roundTripAndLayout() throws {
        let backup = try sampleBackup()
        let salt = Data.pattern(16)
        let nonce = Data.pattern(12)
        let password = "yedek parolam 2026"
        let file = try AccountBackupFile.export(
            backup, password: password, salt: salt, iterations: 1000, nonce: nonce)
        #expect(file.prefix(5) == Data("BRPB".utf8) + [0x01])
        #expect(file[5..<21] == salt)
        #expect(file[21..<25] == Data([0, 0, 0x03, 0xE8]))
        #expect(file[25..<37] == nonce)
        #expect(try AccountBackupFile.import(file, password: password) == backup)
        #expect(throws: AccountBackupError.wrongPassword) {
            try AccountBackupFile.import(file, password: "yedek parolam 2025")
        }
        #expect(throws: AccountBackupError.wrongPassword) {
            try AccountBackupFile.import(file.flippingBit(at: 50), password: password)
        }
        #expect(throws: AccountBackupError.invalidFile) {
            try AccountBackupFile.import(file.flippingBit(at: 1), password: password)
        }
    }

    @Test("the key is PBKDF2 over UTF-8(NFKC(password)), untrimmed, never the secret code")
    func keyDerivation() throws {
        let salt = Data.pattern(16)
        let nonce = Data.pattern(12)
        let file = try AccountBackupFile.encrypt(
            Data([1, 2, 3]), password: " ｙｅｄｅｋ parolası ", salt: salt, iterations: 1000, nonce: nonce)
        let key = PBKDF2.sha256(password: Data(" yedek parolası ".nfkc.utf8), salt: salt, iterations: 1000)
        #expect(try AES256GCM.open(file[37...], key: key, nonce: nonce, associatedData: file.prefix(25)) == Data([1, 2, 3]))
        #expect(try AccountBackupFile.decrypt(file, password: " yedek parolası ") == Data([1, 2, 3]))
        #expect(throws: AccountBackupError.wrongPassword) {
            try AccountBackupFile.decrypt(file, password: "yedek parolası")
        }
    }

    @Test("exports refuse passwords the rules reject, imports never check them")
    func passwordRules() throws {
        #expect(throws: BackupPasswordError.tooShort) {
            try AccountBackupFile.export(try sampleBackup(), password: "472913")
        }
        #expect(throws: BackupPasswordError.tooCommon) {
            try AccountBackupFile.export(try sampleBackup(), password: "Password123")
        }
        // A file made under older or other rules still opens.
        let file = try AccountBackupFile.export(
            try sampleBackup(), password: "kısa", salt: .pattern(16), iterations: 1000, nonce: .pattern(12))
        #expect(try AccountBackupFile.import(file, password: "kısa") == (try sampleBackup()))
    }

    @Test("production exports use 1,000,000 iterations")
    func defaultIterations() throws {
        let file = try AccountBackupFile.export(try sampleBackup(), password: "yedek parolam 2026")
        #expect(file.bigEndianUInt32(at: 21) == 1_000_000)
    }
}
