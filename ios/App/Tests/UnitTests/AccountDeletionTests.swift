import Foundation
import Testing
import Us2usKit

@testable import us2us

// 7.1 › Hesap › “Her şeyi sil”: the account is deleted on the relay first,
// the phone is erased only once the relay confirmed (or the user chose this
// phone only) and the sheet has gone. The design fixtures stand in for the
// relay (`failsAccountDeletion`: unreachable).

@MainActor
private func makeApp() -> (AppModel, DesignFixturesService) {
    var configuration = AppConfiguration()
    configuration.start = .chats
    let defaults = UserDefaults(suiteName: "us2us.tests.\(UUID().uuidString)")!
    let service = DesignFixturesService(
        hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
    let app = AppModel(configuration: configuration, preferences: AppPreferences(defaults: defaults), messenger: service)
    return (app, service)
}

/// Waits (briefly) for an asynchronous state change.
@MainActor
private func eventually(_ condition: @MainActor () -> Bool) async {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(3))
    while !condition(), clock.now < deadline { try? await Task.sleep(for: .milliseconds(20)) }
}

@MainActor
@Suite("07 · Account deletion (7.1 › Her şeyi sil)")
struct AccountDeletionTests {
    @Test func theRelayDeletesTheAccountBeforeThePhoneIsErased() async {
        let (app, service) = makeApp()
        let erasure = EraseAccountModel(messenger: service, pause: .zero, armDelay: .zero)
        erasure.present()
        #expect(erasure.isPresented)
        #expect(erasure.step == .confirm)

        await erasure.deleteAccount()
        #expect(service.accountDeletionsOnRelay == 1)
        #expect(erasure.erasure == .accountDeleted)
        #expect(!erasure.isPresented, "the sheet goes down first")
        #expect(erasure.isWorking, "the button keeps spinning while it goes")
        #expect(service.account != nil, "nothing is erased under the sheet")
        #expect(app.phase == .unlocked)

        await erasure.sheetDidDismiss { await app.eraseEverything() }
        #expect(service.account == nil)
        #expect(service.chats.isEmpty)
        #expect(app.phase == .onboarding)
        #expect(app.onboardingEntry == .welcome)
        #expect(erasure.step == .confirm)
        #expect(!erasure.isWorking)
        #expect(erasure.erasure == nil)
    }

    @Test func aRefusedDeletionErasesNothingAndCanBeRetried() async {
        let (app, service) = makeApp()
        service.failsAccountDeletion = true
        let erasure = EraseAccountModel(messenger: service, pause: .zero, armDelay: .milliseconds(200))
        erasure.present()

        await erasure.deleteAccount()
        #expect(erasure.step == .failed)
        #expect(erasure.isPresented, "the error shows in the same sheet")
        #expect(!erasure.isWorking)
        #expect(erasure.erasure == nil)
        #expect(erasure.failures == 1)
        #expect(service.accountDeletionsOnRelay == 0)
        #expect(service.account != nil, "nothing was erased")
        #expect(!service.chats.isEmpty)

        // A tap straight through the cross-fade does nothing.
        await erasure.deleteAccount()
        await erasure.eraseThisPhoneOnly()
        #expect(erasure.failures == 1)
        #expect(erasure.erasure == nil)

        await eventually { erasure.isArmed }
        await erasure.deleteAccount()
        #expect(erasure.failures == 2, "still unreachable: still nothing erased")
        #expect(erasure.step == .failed)
        #expect(service.account != nil)

        service.failsAccountDeletion = false
        await erasure.deleteAccount()
        #expect(service.accountDeletionsOnRelay == 1)
        #expect(erasure.erasure == .accountDeleted)
        #expect(!erasure.isPresented)
        await erasure.sheetDidDismiss { await app.eraseEverything() }
        #expect(service.account == nil)
        #expect(app.phase == .onboarding)
    }

    @Test func thisPhoneOnlyLeavesTheAccountOnTheRelay() async {
        let (app, service) = makeApp()
        service.failsAccountDeletion = true
        let erasure = EraseAccountModel(messenger: service, pause: .zero, armDelay: .zero)
        erasure.present()

        await erasure.eraseThisPhoneOnly()
        #expect(erasure.erasure == nil, "offered only once the relay has refused")
        #expect(erasure.isPresented)

        await erasure.deleteAccount()
        await eventually { erasure.isArmed }
        await erasure.eraseThisPhoneOnly()
        #expect(erasure.erasure == .thisPhoneOnly)
        #expect(!erasure.isPresented)
        #expect(service.account != nil, "nothing is erased under the sheet")

        await erasure.sheetDidDismiss { await app.eraseEverything() }
        #expect(service.account == nil)
        #expect(service.accountDeletionsOnRelay == 0, "the account stays on the relay")
        #expect(app.phase == .onboarding)
    }

    @Test func cancellingOrSwipingAwayErasesNothing() async {
        let (app, service) = makeApp()
        service.failsAccountDeletion = true
        let erasure = EraseAccountModel(messenger: service, pause: .zero, armDelay: .zero)
        var erased = false

        erasure.present()
        erasure.cancel()
        #expect(!erasure.isPresented)
        await erasure.sheetDidDismiss { erased = true }

        // After a failure the sheet can be swiped away too; it starts over.
        erasure.present()
        await erasure.deleteAccount()
        erasure.isPresented = false
        await erasure.sheetDidDismiss { erased = true }
        #expect(erasure.step == .confirm)
        #expect(erasure.failures == 0)

        #expect(!erased)
        #expect(service.account != nil)
        #expect(service.accountDeletionsOnRelay == 0)
        #expect(app.phase == .unlocked)
    }

    @Test func theCopySaysWhatHappens() {
        let tr = Locale(identifier: "tr")
        let en = Locale(identifier: "en")
        #expect(StringTable.settings.string("settings.erase.confirm.title", locale: tr) == "Hesabın silinsin mi?")
        #expect(StringTable.settings.string("settings.erase.confirm.title", locale: en) == "Delete your account?")
        #expect(StringTable.settings.string("settings.erase.confirm.action", locale: tr) == "Hesabı ve her şeyi sil")
        #expect(
            StringTable.settings.string("settings.erase.confirm.action", locale: en) == "Delete account and everything")
        // The relay may have deleted the account just before the connection
        // dropped: only this phone is known to be untouched.
        #expect(StringTable.settings.string("settings.erase.failed.body", locale: tr).contains("Bu telefonda hiçbir şey"))
        #expect(StringTable.settings.string("settings.erase.failed.body", locale: en).contains("Nothing on this phone"))
        let body = StringTable.settings.string("settings.erase.confirm.body", locale: tr)
        #expect(body.contains("kullanıcı adın") && body.contains("geri alınamaz"))
        #expect(StringTable.settings.string("settings.erase.confirm.body", locale: en).contains("No one will be able to reach you"))
        #expect(StringTable.settings.string("settings.erase.failed.title", locale: tr) == "Hesap silinemedi.")
        #expect(StringTable.settings.string("settings.erase.retry", locale: tr) == "Tekrar dene")
        #expect(StringTable.settings.string("settings.erase.retry", locale: en) == "Try again")
        #expect(StringTable.settings.string("settings.erase.thisPhone.title", locale: tr) == "Yalnızca bu telefondan sil")
        #expect(StringTable.settings.string("settings.erase.thisPhone.title", locale: en) == "Erase from this phone only")
        #expect(StringTable.settings.string("settings.erase.thisPhone.body", locale: tr).contains("180 gün"))
        #expect(StringTable.settings.string("settings.erase.thisPhone.body", locale: en).contains("180 days"))
        // 3.3 (code forgotten) cannot sign in to the relay: its confirmation
        // promises no account deletion.
        #expect(L10n.string("erase.confirm.body", locale: tr).contains("Hesabın sunucuda kalır"))
        #expect(L10n.string("erase.confirm.body", locale: en).contains("Your account stays on the server"))
    }
}

// A backup of a deleted account — or one already restored on another phone —
// is refused by the relay (its token is revoked): 2.3 says so instead of
// asking to check the connection.
@MainActor
@Suite("07 · A backup that can no longer be used (2.3)")
struct DeadBackupTests {
    @Test func aRevokedTokenMeansTheBackupIsGone() {
        #expect(RealMessengerService.refusesBackup(.server(status: 401, code: nil, message: nil)))
        #expect(RealMessengerService.refusesBackup(.server(status: 404, code: nil, message: nil)))
        #expect(RealMessengerService.refusesBackup(.notAuthenticated))
        #expect(!RealMessengerService.refusesBackup(.server(status: 500, code: nil, message: nil)))
        #expect(!RealMessengerService.refusesBackup(.network(.notConnectedToInternet)))
        #expect(!RealMessengerService.refusesBackup(.rateLimited(retryAfter: nil)))
    }

    @Test func twoPointThreeSaysWhyItFailed() async {
        let finish = NoWayBackStepModel()
        finish.acknowledged = true
        finish.onSubmit = { NoWayBackStepModel.Failure.backupGone }
        finish.submit()
        await eventually { finish.failure != nil }
        #expect(finish.failure == .backupGone)
        #expect(finish.failed)
        #expect(!finish.isWorking)

        finish.onSubmit = { NoWayBackStepModel.Failure.network }
        finish.submit()
        await eventually { finish.failure == .network }
        #expect(finish.failure == .network)
    }

    @Test func theCopySaysWhatToDo() {
        let tr = Locale(identifier: "tr")
        let en = Locale(identifier: "en")
        let gone = NoWayBackStepView.errorKey(.backupGone)
        #expect(StringTable.onboarding.string(gone, locale: tr).contains("Yeni bir hesap oluşturabilirsin"))
        #expect(StringTable.onboarding.string(gone, locale: en).contains("You can create a new account"))
        let network = NoWayBackStepView.errorKey(.network)
        #expect(StringTable.onboarding.string(network, locale: tr).contains("Bağlantını kontrol"))
    }
}

@Suite("07 · Hakkında › Web sitesi")
struct WebsiteLinkTests {
    @Test func thePagesOpenInTheAppsLanguage() {
        let tr = Locale(identifier: "tr")
        let en = Locale(identifier: "en")
        #expect(WebsitePage.privacy.url(for: tr)?.absoluteString == "https://us2us.app/tr/gizlilik")
        #expect(WebsitePage.privacy.url(for: en)?.absoluteString == "https://us2us.app/en/privacy")
        #expect(WebsitePage.support.url(for: tr)?.absoluteString == "https://us2us.app/tr/destek")
        #expect(WebsitePage.support.url(for: Locale(identifier: "de"))?.absoluteString == "https://us2us.app/en/support")
    }
}
