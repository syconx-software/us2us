import Foundation
import Testing
import Us2usKit
import Us2usRuntime

@testable import us2us

// Section 07 · settings: backup password strength, the backup export flow
// and the secret code change.

@Suite("07 · Password strength (7.2)")
struct PasswordStrengthTests {
    @Test func emptyIsWeakAndUnlit() {
        let strength = PasswordStrength.estimate("")
        #expect(strength.level == .weak)
        #expect(strength.meterFraction == 0)
    }

    @Test func sixDigitCodesAreWeak() {
        for code in ["246813", "000000", "123456", "908172"] {
            #expect(PasswordStrength.estimate(code).level == .weak)
            #expect(!PasswordStrength.estimate(code).isAcceptable)
        }
    }

    @Test func designPasswordIsStrongAt82Percent() {
        let strength = PasswordStrength.estimate(DesignFixturesService.demoExportPassword)
        #expect(strength.level == .strong)
        // 14 random lower-case letters and digits count in full: 14 × log2(36).
        #expect(abs(strength.bits - 14 * log2(36.0)) < 1e-9)
        // 7.2 lights 257.23 of 313.72 pt = 82 %: 32 dots, not a sliver of the 33rd.
        #expect(abs(strength.meterFraction * 313.72 - 257.23) < 0.3)
    }

    @Test func repeatsRunsAndKnownFragmentsCountLess() {
        #expect(
            PasswordStrength.estimate("aaaaaaaaaaaaaa").bits
                < PasswordStrength.estimate(DesignFixturesService.demoExportPassword).bits)
        #expect(PasswordStrength.estimate("abcdefghijklmn").level == .weak)
        #expect(PasswordStrength.estimate("parola2026parola").bits < PasswordStrength.estimate("kedi2026yolda!x").bits)
    }

    /// Names, places, clubs and words with a year, a date or a symbol are what
    /// a guesser tries first: never “Güçlü”, and not enough to export.
    @Test(
        "name + year is never strong",
        arguments: [
            "Ahmet1985!", "Kerem.2010", "Ataturk1923!", "Elif.Kaya.1990", "Summer2026!", "kedi20262026", "Zeynep2015",
            "BJK1903bjk", "Mustafa1881", "Kagan1990!", "Galatasaray1905!", "ahmetkaya1990", "İstanbul2024!",
            "Şifrem2024", "kuzey7deniz42k",
        ])
    func namesAndYearsAreWeak(_ password: String) {
        let strength = PasswordStrength.estimate(password)
        #expect(strength.level < .strong)
        #expect(!strength.isAcceptable)
    }

    @Test(
        "keyboard walks, leet spellings and dates are weak",
        arguments: [
            "1q2w3e4r5t6y7u", "zaq12wsxcde3", "qwerty123456", "P@ssw0rd2026", "s1fr3m2024", "01011990", "19.05.1919",
        ])
    func patternsAreWeak(_ password: String) {
        #expect(PasswordStrength.estimate(password).level == .weak)
    }

    @Test func longMixedPassphrasesAreVeryStrong() {
        #expect(PasswordStrength.estimate("Gece-yarısı 3 kedi, 2 hat!").level == .veryStrong)
        // Five common words: “Güçlü”, while four are only just enough.
        #expect(PasswordStrength.estimate("kedi deniz kitap elma kalem").level == .strong)
    }

    @Test func generatedPasswordsCountAtTheirExactStrength() {
        #expect(PasswordStrength.generated.level == .veryStrong)
        #expect(abs(PasswordStrength.generated.bits - 20 * log2(31.0)) < 1e-9)
        for _ in 0..<200 {
            let password = BackupPassword.generate()
            let typed = PasswordStrength.estimate(password)
            // Typed or pasted, it never counts more than the generator gives.
            #expect(typed.bits <= PasswordStrength.generated.bits + 1e-9, "\(password)")
            #expect(typed.level == .veryStrong, "\(password)")
        }
        // The generator's shape alone is not trusted.
        #expect(PasswordStrength.estimate("aaaa-aaaa-aaaa-aaaa-aaaa").level == .weak)
    }

    @Test func everyListedCommonPasswordIsWeak() throws {
        for password in try CommonBackupPasswords.load() {
            #expect(PasswordStrength.estimate(password).level == .weak, "\(password)")
        }
    }
}

/// `spec/common-backup-passwords.txt` (PROTOCOL.md §5.5), read from the repository.
enum CommonBackupPasswords {
    static func load() throws -> [String] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // UnitTests
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // App
            .deletingLastPathComponent()  // ios
            .deletingLastPathComponent()  // repository
            .appendingPathComponent("spec/common-backup-passwords.txt")
        let lines = try String(contentsOf: url, encoding: .utf8).split(whereSeparator: \.isNewline)
        let entries = lines.map(String.init).filter { !$0.isEmpty && !$0.hasPrefix("#") }
        guard !entries.isEmpty else { throw CocoaError(.fileReadCorruptFile) }
        return entries
    }
}

/// Records what `SecurePasteboard` writes.
@MainActor
final class RecordingPasteboard: PasteboardWriting {
    private(set) var changeCount = 0
    private(set) var text: String?
    private(set) var localOnly: Bool?
    private(set) var expiresAt: Date?

    func setItems(_ items: [[String: Any]], localOnly: Bool, expiresAt: Date?) {
        changeCount += 1
        text = items.first?.values.first as? String
        self.localOnly = localOnly
        self.expiresAt = expiresAt
    }

    func clear() {
        changeCount += 1
        text = nil
    }
}

@MainActor
@Suite("07 · Backup export flow (7.2)")
struct BackupExportModelTests {
    private let demo = DesignFixturesService.demoExportPassword
    private let generated = "k7mq-x2pd-9tfa-wn3h-b8ze"

    private func makeModel(pasteboard: SecurePasteboard? = nil) -> (BackupExportModel, DesignFixturesService) {
        let messenger = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        let pasteboard = pasteboard ?? SecurePasteboard(pasteboard: RecordingPasteboard())
        return (BackupExportModel(messenger: messenger, pasteboard: pasteboard), messenger)
    }

    @Test func emptyPasswordCannotSubmit() {
        let (model, _) = makeModel()
        #expect(!model.canSubmit)
    }

    @Test func theBackupIsTheAccountOnly() {
        #expect(!BackupExportModel.contents.messages)
        #expect(!BackupExportModel.contents.includesMedia)
        #expect(BackupExportModel.contents.settingsAndContacts)
    }

    /// Every §5.5 rule is shown before the confirmation field appears; none
    /// is left to fail silently at export.
    @Test(
        "protocol rules are shown",
        arguments: [
            ("kedi12", BackupExportModel.Problem.tooShort),
            ("Ab3$xY7!q", .tooShort),
            ("cumhuriyet1923", .tooCommon),
            ("aaaaaaaaaaaa", .repeatedCharacter),
            ("abcdefghij", .sequence),
            ("poiuytrewq", .sequence),
        ] as [(String, BackupExportModel.Problem)])
    func protocolRulesAreShown(_ password: String, _ problem: BackupExportModel.Problem) async {
        let (model, _) = makeModel()
        model.password = password
        #expect(await model.submit(fileName: "x.u2u") == nil)
        #expect(model.problem == problem)
        #expect(model.problem?.concernsPassword == true)
        #expect(!model.isConfirming)
    }

    @Test func weakPasswordIsRefused() async {
        let (model, _) = makeModel()
        model.password = "Ahmet1985!"
        #expect(await model.submit(fileName: "x.u2u") == nil)
        #expect(model.problem == .weak)
        #expect(!model.isConfirming)
    }

    @Test func everyListedCommonPasswordStopsBeforeTheConfirmation() async throws {
        for password in try CommonBackupPasswords.load() {
            let (model, _) = makeModel()
            model.password = password
            #expect(await model.submit(fileName: "x.u2u") == nil, "\(password)")
            #expect(model.problem != nil, "\(password)")
            #expect(!model.isConfirming, "\(password)")
        }
    }

    @Test func passwordIsAskedTwice() async throws {
        let (model, backend) = makeModel()
        model.password = demo
        #expect(await model.submit(fileName: "x.u2u") == nil)
        #expect(model.isConfirming)

        model.confirmation = "something else"
        #expect(await model.submit(fileName: "x.u2u") == nil)
        #expect(model.problem == .mismatch)

        model.confirmation = demo
        let url = try #require(await model.submit(fileName: "us2us-test.u2u"))
        #expect(FileManager.default.fileExists(atPath: url.path))
        await model.finishSharing(url, saved: true)
        #expect(!FileManager.default.fileExists(atPath: url.path))
        #expect(backend.settings.lastBackupAt == DesignFixtures.referenceDate)
        #expect(model.password.isEmpty)
    }

    @Test func editingThePasswordClearsTheConfirmation() async {
        let (model, _) = makeModel()
        model.password = demo
        _ = await model.submit(fileName: "x.u2u")
        model.confirmation = "abc"
        model.password += "!"
        #expect(model.confirmation.isEmpty)
    }

    /// The confirmation field stays up when the password changes, but the
    /// new password is checked again.
    @Test func aPasswordChangedAfterTheFirstStepIsCheckedAgain() async {
        let (model, _) = makeModel()
        model.password = demo
        _ = await model.submit(fileName: "x.u2u")
        #expect(model.isConfirming)
        model.password = "Ahmet1985!"
        model.confirmation = "Ahmet1985!"
        #expect(await model.submit(fileName: "x.u2u") == nil)
        #expect(model.problem == .weak)
        #expect(model.phase == .editing)
    }

    @Test func aFailedExportIsShown() async {
        let (model, _) = makeModel()
        model.password = demo
        _ = await model.submit(fileName: "x.u2u")
        model.confirmation = demo
        // A cancelled export throws (the fixtures' encryption steps sleep).
        let export = Task { await model.submit(fileName: "x.u2u") }
        export.cancel()
        #expect(await export.value == nil)
        #expect(model.problem == .exportFailed)
        #expect(model.phase == .editing)
        #expect(model.isConfirming)
    }

    @Test func generatedPasswordFillsTheFieldAtItsExactStrength() async throws {
        let (model, _) = makeModel()
        model.generatePassword { generated }
        #expect(model.password == generated)
        #expect(model.showsGeneratedPassword)
        #expect(model.strength == .generated)
        #expect(model.strength.level == .veryStrong)

        // The confirmation still has to be entered.
        #expect(await model.submit(fileName: "x.u2u") == nil)
        #expect(model.isConfirming)
        model.confirmation = generated
        let url = try #require(await model.submit(fileName: "us2us-generated.u2u"))
        await model.finishSharing(url, saved: true)
        #expect(!model.showsGeneratedPassword)
    }

    @Test func theRealGeneratorIsUsedByDefault() {
        let (model, _) = makeModel()
        model.generatePassword()
        #expect(BackupPassword.isGeneratedFormat(model.password))
        #expect(model.strength == .generated)
    }

    @Test func editingTheGeneratedPasswordCountsItAsTyped() {
        let (model, _) = makeModel()
        model.generatePassword { generated }
        model.password += "x"
        #expect(!model.showsGeneratedPassword)
        #expect(model.strength == PasswordStrength.estimate(generated + "x"))
    }

    @Test func copyStaysOnThisDeviceForAMinute() {
        let recorder = RecordingPasteboard()
        let now = DesignFixtures.referenceDate
        let (model, _) = makeModel(pasteboard: SecurePasteboard(pasteboard: recorder, now: { now }))
        #expect(!model.copyGeneratedPassword())  // nothing generated yet
        #expect(recorder.text == nil)

        model.generatePassword { generated }
        #expect(model.copyGeneratedPassword())
        #expect(model.didCopy)
        #expect(recorder.text == generated)
        #expect(recorder.localOnly == true)
        #expect(recorder.expiresAt == now.addingTimeInterval(60))

        // Once the field holds something else, there is nothing to copy.
        model.password = demo
        #expect(!model.didCopy)
        #expect(!model.copyGeneratedPassword())
    }
}

@MainActor
@Suite("07 · Code change")
struct ChangeCodeModelTests {
    @Test func wrongCurrentCodeCountsAsAnUnlockAttempt() async throws {
        let messenger = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        let model = ChangeCodeModel(messenger: messenger) {}
        for digit in [1, 3, 5, 7, 9, 0] { model.type(digit) }
        try await Task.sleep(for: .milliseconds(400))
        #expect(messenger.unlockStatus.failures == 1)
        #expect(model.step == .current)
    }

    @Test func fullChangeSetsTheNewCode() async throws {
        let messenger = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        var changed = false
        let model = ChangeCodeModel(messenger: messenger) { changed = true }
        for digit in [2, 4, 6, 8, 1, 3] { model.type(digit) }
        try await Task.sleep(for: .milliseconds(400))
        #expect(model.step == .new)
        for digit in [4, 7, 1, 9, 5, 8] { model.type(digit) }
        try await Task.sleep(for: .milliseconds(400))
        #expect(model.step == .confirm)
        for digit in [4, 7, 1, 9, 5, 8] { model.type(digit) }
        try await Task.sleep(for: .milliseconds(700))
        #expect(changed)
        #expect(await messenger.unlock(code: "471958") == .unlocked)
    }
}
