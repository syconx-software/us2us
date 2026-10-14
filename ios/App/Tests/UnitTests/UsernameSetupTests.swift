import Foundation
import Testing
import Us2usKit

@testable import us2us

// 2.1 Kullanıcı adı and the name found taken at 2.3 (PROTOCOL §3.1,
// spec/username-rules.json).

@MainActor
private func fixtures() -> DesignFixturesService {
    DesignFixturesService(hasAccount: false, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
}

/// Waits (briefly) for an asynchronous state change.
@MainActor
private func eventually(_ condition: @MainActor () -> Bool) async {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(3))
    while !condition(), clock.now < deadline { try? await Task.sleep(for: .milliseconds(10)) }
}

private let tr = Locale(identifier: "tr")
private let en = Locale(identifier: "en")

@MainActor
@Suite("2.1 · username rules while typing")
struct UsernameStepTests {
    private func makeModel() -> UsernameStepModel {
        let model = UsernameStepModel(messenger: fixtures())
        model.debounce = .milliseconds(10)
        return model
    }

    @Test("a Turkish letter stays in the name and is reported, nothing is asked")
    func turkishLetterIsReported() async {
        let model = makeModel()
        model.text = "Kağan"
        #expect(model.text == "Kağan", "the typed text is never rewritten by the model")
        #expect(model.name == "kağan")
        #expect(model.violations == [.turkishCharacters])
        #expect(model.status == .invalid(.turkishCharacters))
        #expect(model.isBroken(.turkishCharacters))
        #expect(!model.isBroken(.characters) && !model.isBroken(.firstLetter) && !model.isBroken(.length))
        #expect(!model.canContinue)
        try? await Task.sleep(for: .milliseconds(80))
        #expect(model.status == .invalid(.turkishCharacters), "an invalid name is never checked")
    }

    @Test("every broken rule is marked; the status names the first")
    func everyBrokenRuleIsMarked() {
        let model = makeModel()
        model.text = "1.ş"
        #expect(model.violations == [.turkishCharacters, .characters, .firstLetter])
        #expect(model.status == .invalid(.turkishCharacters))
        for rule in [UsernameRules.Violation.turkishCharacters, .characters, .firstLetter] {
            #expect(model.isBroken(rule))
        }
        model.text = "selin.n"
        #expect(model.status == .invalid(.characters))
        model.text = String(repeating: "a", count: 25)
        #expect(model.status == .invalid(.length))
        #expect(model.isBroken(.length), "too long shows at once")
        #expect(model.counter == "25 / 24")
    }

    @Test("too short is said only once typing pauses")
    func tooShortWaitsForThePause() async {
        let model = makeModel()
        model.text = "se"
        #expect(model.status == .typing)
        #expect(!model.isBroken(.length))
        await eventually { model.status == .tooShort }
        #expect(model.status == .tooShort)
        #expect(model.isBroken(.length))
        #expect(model.counter == "2 / 24")
    }

    @Test("white space and one “@” around a valid name are not part of it")
    func normalizedName() async {
        let model = makeModel()
        model.text = " @Selin_K "
        #expect(model.name == "selin_k")
        await eventually { model.status == .available }
        #expect(model.canContinue)
    }

    @Test("a name the live service can only check by the rules may continue")
    func meetsRulesContinues() async {
        let model = makeModel()
        model.text = DesignFixtures.takenAtSetupUsername
        await eventually { model.status == .meetsRules }
        #expect(model.status == .meetsRules)
        #expect(model.fieldStatus == .positive)
        #expect(model.canContinue)
        #expect(model.canShowOwnExample)
    }

    @Test("a name found taken at setup stays taken in this setup")
    func takenAtSetupIsRemembered() async {
        let model = makeModel()
        model.text = DesignFixtures.takenAtSetupUsername
        await eventually { model.status == .meetsRules }
        model.markTaken(model.name)
        #expect(model.status == .taken)
        #expect(!model.canContinue)
        model.text = "ilgaz_2"
        await eventually { model.status == .available }
        model.text = DesignFixtures.takenAtSetupUsername
        await eventually { model.status == .taken }
        #expect(model.status == .taken, "not offered again")
    }

    @Test("the status lines and the new rule exist in Turkish and English")
    func copy() {
        let keys: [String.LocalizationValue] = [
            "onboarding.username.status.meetsRules", "onboarding.username.status.turkish",
            "onboarding.username.status.firstLetter", "onboarding.username.status.tooShort",
            "onboarding.username.status.tooLong", "onboarding.username.rule.firstLetter",
            "onboarding.noWayBack.error.usernameTaken", "onboarding.noWayBack.chooseAnotherName",
            "a11y.onboarding.username.rule.broken",
        ]
        for key in keys {
            let turkish = StringTable.onboarding.string(key, locale: tr)
            let english = StringTable.onboarding.string(key, locale: en)
            #expect(turkish != english && !turkish.hasPrefix("onboarding."))
        }
        #expect(StringTable.onboarding.string("onboarding.username.status.meetsRules", locale: tr) == "Kurallara uygun")
        #expect(StringTable.onboarding.string("onboarding.username.status.meetsRules", locale: en) == "Meets the rules")
        #expect(StringTable.onboarding.string("onboarding.username.rule.length", locale: tr) == "3–24 karakter")
        #expect(StringTable.onboarding.string("onboarding.username.rule.firstLetter", locale: tr) == "Harfle başlar")
        #expect(StringTable.onboarding.string("onboarding.username.rule.characters", locale: tr) == "Küçük harf, rakam ve alt çizgi")
        #expect(StringTable.onboarding.string("onboarding.noWayBack.error.usernameTaken", locale: tr) == "Bu kullanıcı adı alınmış.")
        #expect(StringTable.onboarding.string("onboarding.noWayBack.chooseAnotherName", locale: tr) == "Başka bir ad seç")
        #expect(StringTable.onboarding.string("onboarding.noWayBack.chooseAnotherName", locale: en) == "Choose another name")
        #expect(UsernameRules.isValid(StringTable.onboarding.string("onboarding.username.placeholder", locale: tr)))
        #expect(UsernameRules.isValid(StringTable.onboarding.string("onboarding.username.placeholder", locale: en)))
    }
}

@MainActor
@Suite("2.3 · the name found taken at setup")
struct UsernameTakenAtSetupTests {
    @Test("the relay's 409 username_taken becomes its own failure; other errors pass through")
    func liveMapping() {
        let taken = RealMessengerService.creationFailure(
            RelayError.server(status: 409, code: .usernameTaken, message: "taken"))
        #expect(taken as? AccountCreationFailure == .usernameTaken)
        let network = RealMessengerService.creationFailure(RelayError.network(.notConnectedToInternet))
        #expect(network as? RelayError == .network(.notConnectedToInternet))
        let other409 = RelayError.server(status: 409, code: nil, message: nil)
        #expect(RealMessengerService.creationFailure(other409) as? RelayError == other409)
    }

    @Test("2.3 offers another name: the CTA works without the checkbox and goes back to 2.1")
    func chooseAnotherNameCTA() async {
        let finish = NoWayBackStepModel()
        var chose = 0
        finish.onChooseAnotherName = { chose += 1 }
        finish.acknowledged = true
        finish.onSubmit = { .usernameTaken }
        finish.submit()
        await eventually { finish.failure != nil }
        #expect(finish.choosesAnotherName)
        #expect(!finish.returnsToLock)
        #expect(NoWayBackStepView.actionKey(finish) == "onboarding.noWayBack.chooseAnotherName")
        finish.acknowledged = false
        #expect(finish.canFinish)
        finish.submit()
        #expect(chose == 1)
        finish.resume()
        #expect(finish.failure == nil)
        #expect(!finish.acknowledged, "resuming keeps the checkbox as it was")
    }

    @Test("taken at setup: back to 2.1 with the name marked, the code kept, then setup finishes")
    func takenNameThenAnother() async {
        let service = fixtures()
        var finished = 0
        let flow = OnboardingFlowModel(messenger: service, onFinished: { finished += 1 })
        flow.username.debounce = .milliseconds(10)
        flow.startSetup()
        flow.username.text = DesignFixtures.takenAtSetupUsername
        await eventually { flow.username.canContinue }
        #expect(flow.username.status == .meetsRules)
        flow.usernameContinue()
        #expect(flow.router.path == [.username, .secretCode])

        // 2.2: the code, twice.
        for digit in DesignFixtures.demoSecretCode { flow.code.type(Int(String(digit)) ?? 0) }
        await eventually { flow.code.step == .confirm && !flow.code.isBusy }
        for digit in DesignFixtures.demoSecretCode { flow.code.type(Int(String(digit)) ?? 0) }
        await eventually { flow.router.path.last == .noWayBack }
        #expect(flow.router.path == [.username, .secretCode, .noWayBack])

        // 2.3: taken.
        flow.finish.acknowledged = true
        flow.finish.submit()
        await eventually { flow.finish.failure != nil }
        #expect(flow.finish.failure == .usernameTaken)
        #expect(flow.username.status == .taken)
        #expect(service.account == nil)

        // “Başka bir ad seç” → 2.1, the name still there.
        flow.finish.submit()
        #expect(flow.router.path == [.username])
        flow.pathChanged(from: [.username, .secretCode, .noWayBack], to: [.username])
        #expect(flow.username.text == DesignFixtures.takenAtSetupUsername)
        #expect(!flow.username.canContinue)

        // Another name → straight back to 2.3 (the code is kept) → done.
        flow.username.text = "ilgaz_k"
        await eventually { flow.username.canContinue }
        flow.usernameContinue()
        #expect(flow.router.path == [.username, .noWayBack])
        #expect(flow.finish.failure == nil)
        #expect(flow.finish.acknowledged)
        flow.finish.submit()
        await eventually { finished == 1 }
        #expect(finished == 1)
        #expect(service.account?.username == "ilgaz_k")
    }

    @Test("taken at setup, then back to 2.2 and the code again: the name is not sent a second time")
    func takenNameIsNotSentAgain() async {
        let service = fixtures()
        let flow = OnboardingFlowModel(messenger: service, onFinished: {})
        flow.username.debounce = .milliseconds(10)
        flow.startSetup()
        flow.username.text = "ilgaz_k"
        await eventually { flow.username.canContinue }
        flow.usernameContinue()
        for _ in 0..<2 {
            for digit in DesignFixtures.demoSecretCode { flow.code.type(Int(String(digit)) ?? 0) }
            await eventually { (flow.code.step == .confirm && !flow.code.isBusy) || flow.router.path.last == .noWayBack }
        }
        await eventually { flow.router.path.last == .noWayBack }
        // What the relay's 409 leaves on 2.1 (this name the fixtures would register).
        flow.username.markTaken("ilgaz_k")

        // 2.3 › back → 2.2 (the code starts over) → the code twice → 2.3.
        flow.router.pop()
        flow.pathChanged(from: [.username, .secretCode, .noWayBack], to: [.username, .secretCode])
        #expect(flow.code.step == .set)
        for _ in 0..<2 {
            for digit in DesignFixtures.demoSecretCode { flow.code.type(Int(String(digit)) ?? 0) }
            await eventually { (flow.code.step == .confirm && !flow.code.isBusy) || flow.router.path.last == .noWayBack }
        }
        await eventually { flow.router.path.last == .noWayBack }
        flow.finish.acknowledged = true
        flow.finish.submit()
        await eventually { flow.finish.failure != nil }
        #expect(flow.finish.failure == .usernameTaken)
        #expect(service.account == nil, "the relay is not asked again")
        flow.finish.submit()
        #expect(flow.router.path == [.username])
    }

    @Test("back on the welcome pages, a kept code goes: setup starts over at 2.2")
    func keptCodeGoesOnTheWelcomePages() async {
        let flow = OnboardingFlowModel(messenger: fixtures(), onFinished: {})
        flow.setForHarness(mode: .create, path: [], confirmedCode: DesignFixtures.demoSecretCode)
        flow.pathChanged(from: [.username], to: [])
        flow.startSetup()
        flow.username.setForHarness(name: "ilgaz_k", status: .available)
        flow.usernameContinue()
        #expect(flow.router.path == [.username, .secretCode])
    }
}

@MainActor
@Suite("4.2 · what is typed is searched as it is")
struct NewChatNormalizationTests {
    @Test("a leading “@”, white space and capitals are not part of the name")
    func normalizes() async {
        let model = NewChatModel(
            messenger: DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero),
            debounce: .milliseconds(10))
        model.query = " @Mert_K"
        #expect(model.query == " @Mert_K", "the field is never rewritten")
        await eventually { if case .found = model.phase { true } else { false } }
        guard case .found(let peer, _) = model.phase else {
            Issue.record("expected a result, got \(model.phase)")
            return
        }
        #expect(peer.username == "mert_k")
    }

    @Test("a name no account can have is answered with the rule it breaks, without a lookup")
    func invalidNamesNameTheirRule() async {
        let cases: [(String, UsernameRules.Violation)] = [
            ("ayşe", .turkishCharacters), ("selin.n", .characters), ("Ali Veli", .characters), ("1abc", .firstLetter),
            (String(repeating: "a", count: 25), .length), ("@@deniz", .characters),
        ]
        for (query, violation) in cases {
            let model = NewChatModel(
                messenger: DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero),
                debounce: .milliseconds(10))
            model.query = query
            #expect(model.phase == .pending)
            await eventually { model.phase == .invalid(violation) }
            #expect(model.phase == .invalid(violation), "\(query)")
            #expect(model.query == query)
            #expect(model.resultName == UsernameRules.normalize(query))
            #expect(model.resultAvatar == .noResult)
        }
    }

    @Test("the broken rule is said in Turkish and English")
    func invalidCopy() {
        let keys: [String.LocalizationValue] = [
            "newChat.invalid.turkish", "newChat.invalid.characters", "newChat.invalid.firstLetter", "newChat.invalid.tooLong",
        ]
        for key in keys {
            let turkish = StringTable.chats.string(key, locale: tr)
            let english = StringTable.chats.string(key, locale: en)
            #expect(turkish != english && !turkish.hasPrefix("newChat."))
        }
        #expect(
            StringTable.chats.string("newChat.invalid.turkish", locale: tr)
                == "Geçersiz kullanıcı adı · Türkçe karakter kullanılamaz.")
    }
}
