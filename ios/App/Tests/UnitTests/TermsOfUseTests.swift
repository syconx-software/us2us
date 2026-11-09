import Foundation
import Testing

@testable import us2us

// The Terms of Use (App Review 1.2): the accepted version kept on this
// phone, the gate before 1.1–1.3's ways in (accepted → on; “Vazgeç” or the
// sheet pulled down → nothing goes on), the terms over the app for an
// account that never accepted them, and the full text's address.

@MainActor
private func freshDefaults() -> UserDefaults {
    UserDefaults(suiteName: "us2us.tests.\(UUID().uuidString)")!
}

/// What the gate asked for, in order.
@MainActor
private final class Starts {
    var taken: [OnboardingStart] = []
}

@MainActor
private func makeGate(_ preferences: AppPreferences) -> (OnboardingTermsGate, Starts) {
    let starts = Starts()
    let gate = OnboardingTermsGate(
        isAccepted: { preferences.hasAcceptedCurrentTerms }, accept: { preferences.acceptTerms() },
        perform: { starts.taken.append($0) })
    return (gate, starts)
}

@Suite("Terms of Use · acceptance on this phone")
@MainActor
struct TermsAcceptanceTests {
    @Test func acceptingKeepsTheCurrentVersionAcrossLaunches() {
        let defaults = freshDefaults()
        let preferences = AppPreferences(defaults: defaults)
        #expect(preferences.acceptedTermsVersion == nil)
        #expect(!preferences.hasAcceptedCurrentTerms)
        preferences.acceptTerms()
        #expect(preferences.acceptedTermsVersion == TermsOfUse.version)
        let relaunched = AppPreferences(defaults: defaults)
        #expect(relaunched.hasAcceptedCurrentTerms, "not asked again on this phone")
    }

    @Test func anOlderVersionAsksAgain() {
        let defaults = freshDefaults()
        defaults.set("2025-01-01", forKey: "u2.acceptedTerms")
        #expect(!AppPreferences(defaults: defaults).hasAcceptedCurrentTerms)
    }

    @Test func resettingPreferencesForgetsTheAcceptance() {
        let defaults = freshDefaults()
        let preferences = AppPreferences(defaults: defaults)
        preferences.acceptTerms()
        preferences.reset()
        #expect(!preferences.hasAcceptedCurrentTerms)
        #expect(defaults.string(forKey: "u2.acceptedTerms") == nil)
    }

    /// An account here (an older build) without the terms: they cover the
    /// app once it is unlocked, until accepted.
    @Test func anAccountWithoutTheTermsIsAskedAfterUnlockingUntilItAccepts() {
        var configuration = AppConfiguration()
        configuration.start = .chats
        configuration.terms = .ask
        let app = AppModel(
            configuration: configuration, preferences: AppPreferences(defaults: freshDefaults()),
            messenger: DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate)))
        #expect(app.phase == .unlocked)
        #expect(app.requiresTermsAcceptance)
        app.preferences.acceptTerms()
        #expect(!app.requiresTermsAcceptance)
    }

    @Test func notWhileLockedNorDuringOnboarding() {
        for start in [AppConfiguration.Start.locked, .welcome] {
            var configuration = AppConfiguration()
            configuration.start = start
            configuration.terms = .ask
            let app = AppModel(
                configuration: configuration, preferences: AppPreferences(defaults: freshDefaults()),
                messenger: DesignFixturesService(
                    hasAccount: start != .welcome, clock: .frozen(at: DesignFixtures.referenceDate)))
            #expect(!app.requiresTermsAcceptance, "\(start): the lock or 1.1's own gate comes first")
        }
    }

    /// The design's data starts with the terms accepted (screenshots, UI
    /// tests), unless the launch asks for them.
    @Test func fixturesStartAcceptedUnlessAsked() {
        var fixtures = AppConfiguration()
        fixtures.usesFixtures = true
        #expect(fixtures.termsOverride == .accepted)
        fixtures.terms = .ask
        #expect(fixtures.termsOverride == .ask)
        #expect(AppConfiguration().termsOverride == nil, "the live service keeps what was saved")
    }

    @Test func theFullTermsOpenInTheAppsLanguage() {
        #expect(WebsitePage.terms.url(for: Locale(identifier: "tr_TR"))?.absoluteString == "https://us2us.app/tr/kullanim-kosullari")
        #expect(WebsitePage.terms.url(for: Locale(identifier: "en_TR"))?.absoluteString == "https://us2us.app/en/terms")
        #expect(WebsitePage.terms.url(for: Locale(identifier: "de"))?.absoluteString == "https://us2us.app/en/terms")
    }

    @Test func theZeroToleranceRuleIsStatedInBothLanguages() {
        #expect(
            StringTable.terms.string("terms.zeroTolerance", locale: Locale(identifier: "tr"))
                == "us2us'ta uygunsuz içeriğe ve kötü niyetli kullanıcılara hiçbir şekilde tolerans gösterilmez.")
        #expect(
            StringTable.terms.string("terms.zeroTolerance", locale: Locale(identifier: "en"))
                == "There is no tolerance for objectionable content or abusive users on us2us.")
        #expect(StringTable.terms.string("terms.agree", locale: Locale(identifier: "tr")) == "Kabul ediyorum")
        #expect(StringTable.terms.string("terms.agree", locale: Locale(identifier: "en")) == "I agree")
    }
}

@Suite("Terms of Use · before 1.1–1.3's ways in")
@MainActor
struct OnboardingTermsGateTests {
    @Test(arguments: [OnboardingStart.create, .restore, .transfer])
    func theTermsComeFirstAndTheChosenWayGoesOnOnceAccepted(_ start: OnboardingStart) {
        let preferences = AppPreferences(defaults: freshDefaults())
        let (gate, starts) = makeGate(preferences)
        gate.request(start)
        #expect(gate.pending == start, "the terms sheet is up")
        #expect(starts.taken.isEmpty, "nothing goes on before the terms")
        gate.agree()
        #expect(preferences.hasAcceptedCurrentTerms)
        #expect(gate.pending == nil, "the sheet goes down")
        #expect(starts.taken.isEmpty, "the way in waits for the sheet to go")
        gate.sheetDidDismiss()
        #expect(starts.taken == [start])
        gate.sheetDidDismiss()
        #expect(starts.taken == [start], "taken once")
    }

    @Test func onceAcceptedTheWaysInGoOnAtOnce() {
        let preferences = AppPreferences(defaults: freshDefaults())
        preferences.acceptTerms()
        let (gate, starts) = makeGate(preferences)
        gate.request(.transfer)
        #expect(gate.pending == nil, "no sheet")
        #expect(starts.taken == [.transfer])
    }

    @Test func decliningGoesNowhereAndKeepsNothing() {
        let preferences = AppPreferences(defaults: freshDefaults())
        let (gate, starts) = makeGate(preferences)
        gate.request(.create)
        gate.decline()
        gate.sheetDidDismiss()
        #expect(gate.pending == nil)
        #expect(starts.taken.isEmpty)
        #expect(!preferences.hasAcceptedCurrentTerms)
        // Asked again on the next tap.
        gate.request(.restore)
        #expect(gate.pending == .restore)
    }

    @Test func pullingTheSheetDownIsDecliningToo() {
        let preferences = AppPreferences(defaults: freshDefaults())
        let (gate, starts) = makeGate(preferences)
        gate.request(.create)
        gate.pending = nil  // the sheet's binding, pulled down
        gate.sheetDidDismiss()
        #expect(starts.taken.isEmpty)
        #expect(!preferences.hasAcceptedCurrentTerms)
    }

    @Test func agreeingWithoutASheetDoesNothing() {
        let preferences = AppPreferences(defaults: freshDefaults())
        let (gate, starts) = makeGate(preferences)
        gate.agree()
        gate.sheetDidDismiss()
        #expect(!preferences.hasAcceptedCurrentTerms)
        #expect(starts.taken.isEmpty)
    }

    @Test func theFlowTakesEachWayIn() {
        let service = DesignFixturesService(hasAccount: false, clock: .frozen(at: DesignFixtures.referenceDate))
        let expected: [OnboardingStart: OnboardingRoute] = [
            .create: .username, .restore: .restore, .transfer: .transferReceive,
        ]
        for (start, route) in expected {
            let flow = OnboardingFlowModel(messenger: service, onFinished: {})
            flow.start(start)
            #expect(flow.router.path == [route])
        }
    }
}
