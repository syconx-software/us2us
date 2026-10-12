import Foundation
import Testing
import Us2usKit
import Us2usRuntime

@testable import us2us

// What the user is told when something other than a wrong code stops the
// lock, 2.3 or the add-by-QR scanner (PROTOCOL §5, §5.2 › Errors, §5.5):
// never “Kod yanlış” for a vault the hardware could not read, never “try
// again” when a vault on this phone makes trying again fail, and never
// “Kod tanınmadı” for a key the server swapped.

/// Waits (briefly) for an asynchronous state change.
@MainActor
private func eventually(_ condition: @MainActor () -> Bool) async {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(3))
    while !condition(), clock.now < deadline { try? await Task.sleep(for: .milliseconds(20)) }
}

private let tr = Locale(identifier: "tr")
private let en = Locale(identifier: "en")

@MainActor
@Suite("03 · 2.3 · 6.2 · failures that are not the user's")
struct LockAndSetupRecoveryTests {
    @Test("only a code the vault rejected is a wrong code")
    func unlockErrors() {
        let status = UnlockStatus(
            failures: 1, nextAttemptAllowedAt: nil, upcoming: UnlockThrottle.standard.upcomingDelay(afterFailures: 1))
        let until = Date(timeIntervalSince1970: 1_791_000_000)
        #expect(RealMessengerService.attemptResult(UnlockError.vault(.wrongCode), status: status) == .wrongCode(status))
        #expect(RealMessengerService.attemptResult(UnlockError.vault(.malformedCode), status: status) == .malformed)
        #expect(
            RealMessengerService.attemptResult(UnlockError.vault(.lockedOut(until: until)), status: status)
                == .throttled(until: until))
        // The Secure Enclave or the Keychain refused, another unlock ran, the
        // store did not open: not counted, the code may be right.
        #expect(
            RealMessengerService.attemptResult(UnlockError.vault(.storage("secure enclave")), status: status)
                == .vaultUnavailable)
        #expect(RealMessengerService.attemptResult(UnlockError.busy, status: status) == .vaultUnavailable)
        #expect(RealMessengerService.attemptResult(RuntimeError.locked, status: status) == .vaultUnavailable)
        // The device secret or the hardware key is gone, the file is damaged:
        // only a backup helps.
        #expect(
            RealMessengerService.attemptResult(UnlockError.vault(.deviceSecretMissing), status: status)
                == .vaultUnusable)
        #expect(RealMessengerService.attemptResult(UnlockError.vault(.corrupted), status: status) == .vaultUnusable)
    }

    @Test("3.2 and 7.1 say what happened in both languages, without “Kod yanlış”")
    func unlockErrorCopy() {
        let lockKeys: [(key: String.LocalizationValue, raw: String)] = [
            ("lock.code.unavailable", "lock.code.unavailable"), ("lock.code.unusable", "lock.code.unusable"),
        ]
        for entry in lockKeys {
            let turkish = StringTable.lock.string(entry.key, locale: tr)
            let english = StringTable.lock.string(entry.key, locale: en)
            #expect(turkish != entry.raw && english != entry.raw && turkish != english)
            #expect(!turkish.contains("Kod yanlış"))
            #expect(!english.contains("Wrong code"))
        }
        #expect(StringTable.lock.string("lock.code.unavailable", locale: tr).contains("Kodun doğru olabilir"))
        #expect(StringTable.lock.string("lock.code.unusable", locale: tr).contains("Kodu unuttum"))
        let settingsKeys: [(key: String.LocalizationValue, raw: String)] = [
            ("code.unavailable", "code.unavailable"), ("code.unusable", "code.unusable"),
        ]
        for entry in settingsKeys {
            let turkish = StringTable.settings.string(entry.key, locale: tr)
            let english = StringTable.settings.string(entry.key, locale: en)
            #expect(turkish != entry.raw && english != entry.raw && turkish != english)
        }
    }

    @Test("2.3: a failure that left a vault on this phone leads to the lock, never to “try again”")
    func setupInterruptions() async {
        for failure in [NoWayBackStepModel.Failure.accountKept, .previousVaultKept] {
            let finish = NoWayBackStepModel()
            var returned = 0
            finish.onReturnToLock = { returned += 1 }
            finish.acknowledged = true
            finish.onSubmit = { failure }
            finish.submit()
            await eventually { finish.failure != nil }
            #expect(finish.failure == failure)
            #expect(finish.returnsToLock)
            // The CTA works whatever the checkbox says, and goes to the lock.
            finish.acknowledged = false
            #expect(finish.canFinish)
            finish.submit()
            #expect(returned == 1)
            #expect(finish.failure == failure, "nothing is retried")
        }
        // The others stay on 2.3 with their own sentence.
        for failure in [NoWayBackStepModel.Failure.network, .backupGone, .backupDamaged] {
            let finish = NoWayBackStepModel()
            finish.acknowledged = true
            finish.onSubmit = { failure }
            finish.submit()
            await eventually { finish.failure != nil }
            #expect(!finish.returnsToLock)
        }
    }

    @Test("2.3's sentences for a kept vault: no new account offered, the lock named")
    func setupInterruptionCopy() {
        let kept = NoWayBackStepView.errorKey(.accountKept)
        let previous = NoWayBackStepView.errorKey(.previousVaultKept)
        let gone = NoWayBackStepView.errorKey(.backupGone)
        for key in [kept, previous] {
            let turkish = StringTable.onboarding.string(key, locale: tr)
            #expect(!turkish.contains("Yeni bir hesap oluşturabilirsin"))
            #expect(turkish != StringTable.onboarding.string(gone, locale: tr))
            #expect(!StringTable.onboarding.string(key, locale: en).contains("You can create a new account"))
        }
        #expect(StringTable.onboarding.string(kept, locale: tr).contains("Yeni kodunla aç"))
        #expect(StringTable.onboarding.string(previous, locale: tr).contains("verilerine dokunulmadı"))
        #expect(StringTable.onboarding.string("onboarding.noWayBack.toLock", locale: tr) == "Kilit ekranına dön")
        #expect(StringTable.onboarding.string("onboarding.noWayBack.toLock", locale: en) == "Back to the lock screen")
    }

    @Test("6.2: a swapped key is a mismatch and a failed lookup is not “nobody”")
    func contactScanWarnings() {
        let mismatch = StringTable.verification.string("scan.contact.mismatch.message", locale: tr)
        #expect(mismatch.contains("Kimse eklenmedi"))
        #expect(mismatch != StringTable.verification.string("scan.mismatch.message", locale: tr))
        #expect(StringTable.verification.string("scan.lookupFailed.title", locale: en) == "Couldn't look up the contact")
        #expect(
            StringTable.verification.string("scan.lookupFailed.message", locale: tr)
                != StringTable.verification.string("scan.unrecognized.message", locale: tr))
    }

    @Test("lines written by builds 4–6 for a verification still read as one")
    func legacyAcceptedNotices() {
        #expect(LiveNotice(SystemNotice.safetyNumberAccepted) == .safetyNumberVerified)
        #expect(LiveNotice(SystemNotice.safetyNumberVerified) == .safetyNumberVerified)
        #expect(LiveNotice(SystemNotice.safetyNumberAcceptedUnverified) == .safetyNumberAccepted)
    }
}
