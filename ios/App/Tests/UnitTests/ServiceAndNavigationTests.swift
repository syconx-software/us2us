import Us2usKit
import XCTest

@testable import us2us

@MainActor
final class DesignFixturesServiceTests: XCTestCase {
    private func makeService(hasAccount: Bool = true) -> DesignFixturesService {
        DesignFixturesService(hasAccount: hasAccount, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
    }

    func testServesTheDesignsChatList() {
        let service = makeService()
        XCTAssertEqual(service.chats.map(\.peer.username), ["deniz", "mert_k", "ayla_", "kaan", "ece_y", "bora", "oya_t"])
        XCTAssertEqual(service.chats.map(\.peer.palette), [.a, .b, .c, .d, .a, .b, .c])
        XCTAssertTrue(service.chats[0].peer.isVerified)
        XCTAssertTrue(service.chats[1].peer.isOnline)
        XCTAssertEqual(service.chats[1].unreadCount, 2)
        XCTAssertEqual(service.chats[0].ghost.remaining(at: DesignFixtures.referenceDate), 23 * 3600 + 41 * 60 + 7)
        XCTAssertEqual(service.chats[4].lastMessage?.status, .failed)
        XCTAssertEqual(service.messages(in: "deniz").count, 7)
        XCTAssertEqual(service.account?.username, "selin_n")
    }

    func testUnlockFollowsTheProtocolDelays() async {
        let service = makeService()
        // First two wrong codes are free, the copy counts what is left.
        guard case .wrongCode(let first) = await service.unlock(code: "111112") else { return XCTFail() }
        XCTAssertEqual(first.upcoming?.attemptsLeft, 2)
        XCTAssertEqual(first.upcoming?.delay, 60)
        XCTAssertNil(first.nextAttemptAllowedAt)
        guard case .wrongCode(let second) = await service.unlock(code: "111113") else { return XCTFail() }
        XCTAssertEqual(second.upcoming?.attemptsLeft, 1)
        // The third costs a minute.
        guard case .wrongCode(let third) = await service.unlock(code: "111114") else { return XCTFail() }
        XCTAssertNotNil(third.nextAttemptAllowedAt)
        guard case .throttled = await service.unlock(code: DesignFixtures.demoSecretCode) else { return XCTFail("must wait") }
        let result1 = await service.unlock(code: "12a")
        XCTAssertEqual(result1, .malformed)
    }

    func testRightCodeUnlocksAndResets() async {
        let service = makeService()
        _ = await service.unlock(code: "111112")
        let result2 = await service.unlock(code: DesignFixtures.demoSecretCode)
        XCTAssertEqual(result2, .unlocked)
        XCTAssertEqual(service.unlockStatus.failures, 0)
    }

    func testSecretCodeRulesComeFromUs2usKit() {
        let service = makeService()
        XCTAssertEqual(service.secretCodeProblem("123456"), .sequence)
        XCTAssertEqual(service.secretCodeProblem("000000"), .repeatedDigit)
        XCTAssertEqual(service.secretCodeProblem("12345"), .notSixDigits)
        XCTAssertNil(service.secretCodeProblem(DesignFixtures.demoSecretCode))
    }

    func testOnboardingCreatesTheAccount() async throws {
        let service = makeService(hasAccount: false)
        XCTAssertNil(service.account)
        let result3 = await service.usernameAvailability("selin_n")
        XCTAssertEqual(result3, .available)
        let result4 = await service.usernameAvailability("deniz")
        XCTAssertEqual(result4, .taken)
        let result5 = await service.usernameAvailability("ay")
        XCTAssertEqual(result5, .invalid(.length))
        let result7 = await service.usernameAvailability("selin.n")
        XCTAssertEqual(result7, .invalid(.characters))
        // Meets the rules, then turns out taken at setup (the relay's 409).
        let result8 = await service.usernameAvailability(DesignFixtures.takenAtSetupUsername)
        XCTAssertEqual(result8, .meetsRules)
        do {
            try await service.createAccount(
                username: DesignFixtures.takenAtSetupUsername, secretCode: "975318", faceIDEnabled: false)
            XCTFail("the name is taken")
        } catch {
            XCTAssertEqual(error as? AccountCreationFailure, .usernameTaken)
        }
        XCTAssertNil(service.account)
        try await service.createAccount(username: "selin_n", secretCode: "975318", faceIDEnabled: false)
        XCTAssertEqual(service.account?.username, "selin_n")
        let result6 = await service.unlock(code: "975318")
        XCTAssertEqual(result6, .unlocked)
    }

    func testSendingRestartsGhostAndMovesTheChatUp() async {
        let service = makeService()
        await service.send(text: "Selam", replyTo: nil, in: "kaan")
        XCTAssertEqual(service.chats.first?.id, "kaan")
        XCTAssertEqual(service.chats.first?.ghost.remaining(at: DesignFixtures.referenceDate), GhostPeriod.sevenDays.duration)
        XCTAssertEqual(service.messages(in: "kaan").last?.status, .sent)
    }

    func testGhostChangesPostSystemLines() async {
        let service = makeService()
        let before = service.messages(in: "deniz").count
        await service.updateGhost(GhostState(isEnabled: true, period: .oneHour, deadline: nil), disappearing: .fiveMinutes, in: "deniz")
        let added = service.messages(in: "deniz").dropFirst(before).map(\.content)
        XCTAssertEqual(added, [.system(.ghostEnabled(.oneHour)), .system(.disappearingChanged(.fiveMinutes))])
    }

    func testRequestsAndVerification() async {
        let service = makeService()
        XCTAssertEqual(service.requests.count, 3)
        let request = service.requests[0]
        let chat = await service.accept(request)
        XCTAssertEqual(chat, "kuzey_dev")
        XCTAssertEqual(service.requests.count, 2)
        XCTAssertEqual(service.chats.first?.id, "kuzey_dev")
        await service.markVerified("deniz", method: .faceToFace)
        let info = await service.safetyNumber(for: "deniz")
        XCTAssertEqual(info?.groups, DesignFixtures.safetyNumberGroups)
        XCTAssertEqual(info?.method, .faceToFace)
    }

    func testEraseEverything() async {
        let service = makeService()
        await service.eraseEverything()
        XCTAssertNil(service.account)
        XCTAssertTrue(service.chats.isEmpty)
    }
}

@MainActor
final class NavigationTests: XCTestCase {
    func testRouterPushPopReplace() {
        let router = U2Router<AppRoute>()
        XCTAssertTrue(router.isAtRoot)
        router.push(.requests)
        router.push(.requestPreview("kuzey_dev"))
        XCTAssertEqual(router.path, [.requests, .requestPreview("kuzey_dev")])
        router.replaceTop(with: .conversation("kuzey_dev"))
        XCTAssertEqual(router.path, [.requests, .conversation("kuzey_dev")])
        router.pop()
        XCTAssertEqual(router.top, .requests)
        router.popToRoot()
        XCTAssertTrue(router.isAtRoot)
        router.setPath([.settings, .settingsPicker(.theme)])
        XCTAssertEqual(router.path.count, 2)
    }

    func testAppModelLockCycle() {
        var configuration = AppConfiguration()
        configuration.start = .chats
        let defaults = UserDefaults(suiteName: "us2us.tests.\(UUID().uuidString)")!
        let model = AppModel(
            configuration: configuration, preferences: AppPreferences(defaults: defaults),
            messenger: DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero))
        XCTAssertEqual(model.phase, .unlocked)
        XCTAssertEqual(model.overlay, .none)
        // App switcher: the cover appears at once, without a lock.
        model.sceneWillResignActive { nil }
        XCTAssertEqual(model.overlay, .privacyCover)
        // Background → foreground: locked.
        model.sceneDidEnterBackground()
        model.sceneWillEnterForeground()
        XCTAssertEqual(model.overlay, .lock)
        XCTAssertEqual(model.phase, .unlocked, "the stack stays alive under the lock")
    }

    func testPreferencesPersist() {
        let defaults = UserDefaults(suiteName: "us2us.tests.\(UUID().uuidString)")!
        let preferences = AppPreferences(defaults: defaults)
        preferences.theme = .light
        preferences.language = .english
        let reloaded = AppPreferences(defaults: defaults)
        XCTAssertEqual(reloaded.theme, .light)
        XCTAssertEqual(reloaded.language, .english)
    }
}
