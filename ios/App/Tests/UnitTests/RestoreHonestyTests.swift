import Foundation
import Testing
import Us2usKit
import Us2usRuntime

@testable import us2us

// 3.3 › 7.3 › 2.2 › 2.3: a backup brings back the account and the contacts,
// never message history (PROTOCOL §5.5). The screens say so, a dead or
// damaged backup is named as such (never as a connection problem), and the
// live 7.3 shows no made-up progress.

/// Waits (briefly) for an asynchronous state change.
@MainActor
private func eventually(_ condition: @MainActor () -> Bool) async {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(3))
    while !condition(), clock.now < deadline { try? await Task.sleep(for: .milliseconds(20)) }
}

@MainActor
@Suite("07 · Restore says what it really does")
struct RestoreHonestyTests {
    @Test("2.3: a refused or damaged backup is said as such; only the rest asks to try again")
    func restoreFailures() {
        #expect(RealMessengerService.restoreFailure(for: RuntimeError.backupRevoked) == .backupNoLongerValid)
        #expect(RealMessengerService.restoreFailure(for: BackupRestoreError.backupNoLongerValid) == .backupNoLongerValid)
        #expect(
            RealMessengerService.restoreFailure(for: RelayError.server(status: 401, code: nil, message: nil))
                == .backupNoLongerValid)
        #expect(RealMessengerService.restoreFailure(for: AccountBackupError.invalidFile) == .backupDamaged)
        #expect(RealMessengerService.restoreFailure(for: MessengerError.invalidBackup) == .backupDamaged)
        #expect(RealMessengerService.restoreFailure(for: RuntimeError.unreadableBackup) == .backupDamaged)
        // Nothing changed after these: trying again can work.
        #expect(RealMessengerService.restoreFailure(for: RelayError.network(.notConnectedToInternet)) == nil)
        #expect(RealMessengerService.restoreFailure(for: RelayError.rateLimited(retryAfter: 30)) == nil)
        #expect(RealMessengerService.restoreFailure(for: RelayError.server(status: 503, code: nil, message: nil)) == nil)
        #expect(RealMessengerService.restoreFailure(for: BackupRestoreError.tokenRefused) == nil)
    }

    @Test("7.3: opening a backup checks everything a restore needs")
    func openResults() {
        #expect(RealMessengerService.openResult(for: AccountBackupError.wrongPassword) == .wrongPassword)
        #expect(RealMessengerService.openResult(for: AccountBackupError.invalidFile) == .damaged)
        #expect(RealMessengerService.openResult(for: RuntimeError.unreadableBackup) == .damaged)
        #expect(RealMessengerService.openResult(for: RuntimeError.backupRevoked) == .noLongerValid)
        #expect(RealMessengerService.openResult(for: RelayError.network(.timedOut)) == .unverified)
        #expect(RealMessengerService.openResult(for: RelayError.rateLimited(retryAfter: nil)) == .unverified)
    }

    @Test("2.3 names a damaged backup in its own sentence")
    func damagedOnTwoPointThree() async {
        let finish = NoWayBackStepModel()
        finish.acknowledged = true
        finish.onSubmit = { .backupDamaged }
        finish.submit()
        await eventually { finish.failure != nil }
        #expect(finish.failure == .backupDamaged)
        #expect(!finish.isWorking)

        let tr = Locale(identifier: "tr")
        let en = Locale(identifier: "en")
        let damaged = NoWayBackStepView.errorKey(.backupDamaged)
        let network = NoWayBackStepView.errorKey(.network)
        #expect(StringTable.onboarding.string(damaged, locale: tr).contains("bozuk"))
        #expect(StringTable.onboarding.string(damaged, locale: en).contains("damaged"))
        #expect(StringTable.onboarding.string(damaged, locale: en) != StringTable.onboarding.string(network, locale: en))
        #expect(!StringTable.onboarding.string(damaged, locale: tr).contains("Bağlantını kontrol"))
    }

    @Test("3.3 promises the account and contacts, not the chats, and says this phone's messages go")
    func forgotCodeCopy() {
        let tr = StringTable.lock.string("lock.forgot.restore.body", locale: Locale(identifier: "tr"))
        let en = StringTable.lock.string("lock.forgot.restore.body", locale: Locale(identifier: "en"))
        #expect(tr.contains("hesabın ve kişilerin geri gelir"))
        #expect(tr.contains("Mesaj geçmişi yedekte yok"))
        #expect(tr.contains("bu telefondaki mesajlar silinir"))
        #expect(!tr.contains("sohbetlerin geri gelir"))
        #expect(en.contains("account and contacts come back"))
        #expect(en.contains("Message history isn't in the backup"))
        #expect(en.contains("the messages on this phone are erased"))
        #expect(!en.contains("chats come back"))
    }

    @Test("7.3 and 2.3 say the same in both languages")
    func restoreCopy() {
        let tr = Locale(identifier: "tr")
        let en = Locale(identifier: "en")
        for key: String.LocalizationValue in ["restore.note.account", "restore.note.account.replacing"] {
            #expect(StringTable.settings.string(key, locale: tr).contains("mesaj geçmişi yedekte yok"))
            #expect(StringTable.settings.string(key, locale: en).contains("message history isn't in the backup"))
        }
        #expect(
            StringTable.settings.string("restore.note.account.replacing", locale: tr)
                .contains("bu telefondaki mesajlar silinir"))
        #expect(
            StringTable.settings.string("restore.note.account.replacing", locale: en)
                .contains("the messages on this phone are erased"))
        #expect(
            StringTable.onboarding.string("onboarding.noWayBack.replacesThisPhone", locale: tr)
                .contains("bu telefondaki mesajlar"))
        #expect(
            StringTable.onboarding.string("onboarding.noWayBack.replacesThisPhone", locale: en)
                .contains("messages and media on this phone are permanently erased"))
        for key: String.LocalizationValue in [
            "restore.error.damaged", "restore.error.noLongerValid", "restore.password.unverified",
        ] {
            #expect(StringTable.settings.string(key, locale: tr) != StringTable.settings.string(key, locale: en))
        }
    }

    @Test("the live 7.3 lists only the verified keys; the design's planned backup keeps its steps")
    func liveSteps() {
        let live = RestoreProgress(
            fraction: 1, stage: .keys, messagesDone: 0, messagesTotal: 0, mediaBytes: 0, estimatedRemaining: 0,
            includesHistory: false)
        #expect(live.stages == [.keys])
        #expect(DesignFixtures.restoreProgress.stages == RestoreProgress.Stage.allCases)
    }

    @Test("2.3 warns only when the restore replaces this phone's vault")
    func replacementWarning() {
        let service = DesignFixturesService(
            hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        let flow = OnboardingFlowModel(messenger: service, onFinished: {})
        #expect(!flow.finish.replacesThisPhone)
        flow.continueAfterRestore()
        #expect(flow.finish.replacesThisPhone)
        flow.restoreFinished()
        #expect(!flow.finish.replacesThisPhone)
        flow.continueAfterRestore()
        flow.startSetup()
        #expect(!flow.finish.replacesThisPhone)
    }
}
