import Foundation
import Testing
import Us2usKit

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
        let strength = PasswordStrength.estimate(DesignFixturesService.demoBackupPassword)
        #expect(strength.level == .strong)
        // 7.2 lights 257.23 of 313.72 pt = 82 %: 32 dots, not a sliver of the 33rd.
        #expect(abs(strength.meterFraction * 313.72 - 257.23) < 0.3)
    }

    @Test func repeatsRunsAndKnownFragmentsCountLess() {
        #expect(PasswordStrength.estimate("aaaaaaaaaaaaaa").bits < PasswordStrength.estimate("kuzey7deniz42k").bits)
        #expect(PasswordStrength.estimate("abcdefghijklmn").level == .weak)
        #expect(PasswordStrength.estimate("parola2026parola").bits < PasswordStrength.estimate("kedi2026yolda!x").bits)
    }

    @Test func longMixedPassphrasesAreVeryStrong() {
        #expect(PasswordStrength.estimate("Gece-yarısı 3 kedi, 2 hat!").level == .veryStrong)
    }
}

@MainActor
@Suite("07 · Backup export flow (7.2)")
struct BackupExportModelTests {
    private func makeModel() -> (BackupExportModel, DesignFixturesService) {
        let messenger = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        return (BackupExportModel(messenger: messenger), messenger)
    }

    @Test func emptyPasswordCannotSubmit() {
        let (model, _) = makeModel()
        #expect(!model.canSubmit)
    }

    @Test func weakPasswordIsRefused() async {
        let (model, _) = makeModel()
        model.password = "kedi12"
        #expect(await model.submit(fileName: "x.u2u") == nil)
        #expect(model.problem == .weak)
        #expect(!model.isConfirming)
    }

    @Test func passwordIsAskedTwice() async throws {
        let (model, backend) = makeModel()
        model.password = DesignFixturesService.demoBackupPassword
        #expect(await model.submit(fileName: "x.u2u") == nil)
        #expect(model.isConfirming)

        model.confirmation = "something else"
        #expect(await model.submit(fileName: "x.u2u") == nil)
        #expect(model.problem == .mismatch)

        model.confirmation = DesignFixturesService.demoBackupPassword
        let url = try #require(await model.submit(fileName: "us2us-test.u2u"))
        #expect(FileManager.default.fileExists(atPath: url.path))
        await model.finishSharing(url, saved: true)
        #expect(!FileManager.default.fileExists(atPath: url.path))
        #expect(backend.settings.lastBackupAt == DesignFixtures.referenceDate)
        #expect(model.password.isEmpty)
    }

    @Test func editingThePasswordClearsTheConfirmation() async {
        let (model, _) = makeModel()
        model.password = DesignFixturesService.demoBackupPassword
        _ = await model.submit(fileName: "x.u2u")
        model.confirmation = "abc"
        model.password += "!"
        #expect(model.confirmation.isEmpty)
    }

    @Test func mediaFollowsMessages() async {
        let (model, _) = makeModel()
        await model.load()
        model.contents.messages = false
        #expect(!model.includesMedia)
        #expect(model.estimatedBytes == 0)
        // The user's media choice comes back with the messages.
        model.contents.messages = true
        #expect(model.includesMedia)
        #expect(model.estimatedBytes == DesignFixtures.backupEstimate.totalBytes)
        model.includesMedia = false
        #expect(model.estimatedBytes == DesignFixtures.backupEstimate.totalBytes - DesignFixtures.backupEstimate.mediaBytes)
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
