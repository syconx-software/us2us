import Foundation
import Testing

@testable import us2us

@Suite("Verification confirmations stay bound to the compared identity")
@MainActor
struct VerificationConfirmationTests {
    private func service() -> DesignFixturesService {
        DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
    }

    @Test func aRefreshBehindTheConfirmationCannotVerifyAnotherKey() async throws {
        let messenger = service()
        let model = VerificationModel(chatID: "deniz", messenger: messenger)
        await model.load()
        model.requestMarkVerified()
        let old = try #require(model.info)
        messenger.replaceSafetyNumberForTesting(
            SafetyNumberInfo(groups: old.groups, scannablePayload: Data("replacement-key".utf8),
                             verifiedAt: nil, method: nil, changedAt: Date()), in: "deniz")
        await model.load()
        await model.markVerifiedManually()
        #expect(!(await messenger.safetyNumber(for: "deniz")?.isVerified ?? false))
        #expect(!model.showsVerified)
        #expect(model.changedWhileOpen)
    }

    @Test func aRefreshCannotAcceptASecondChangeUsingTheFirstConfirmation() async throws {
        let messenger = service()
        await messenger.setSafetyNumberChanged("deniz")
        let model = VerificationModel(chatID: "deniz", messenger: messenger)
        await model.load()
        model.requestAccept()
        let old = try #require(model.info)
        messenger.replaceSafetyNumberForTesting(
            SafetyNumberInfo(groups: old.groups, scannablePayload: Data("second-key".utf8),
                             verifiedAt: nil, method: nil, changedAt: Date()), in: "deniz")
        var exited = false
        model.exitToChat = { exited = true }
        await model.load()
        await model.acceptChangeWithoutVerifying()
        #expect(await messenger.safetyNumber(for: "deniz")?.hasUnverifiedChange == true)
        #expect(!exited)
        #expect(model.changedWhileOpen)
    }

    @Test func aCancelledOrConsumedConfirmationCannotBeReused() async throws {
        let messenger = service()
        let model = VerificationModel(chatID: "deniz", messenger: messenger)
        await model.load()
        model.requestMarkVerified()
        model.cancelMarkVerified()
        await model.markVerifiedManually()
        #expect(!(await messenger.safetyNumber(for: "deniz")?.isVerified ?? false))
        model.requestMarkVerified()
        await model.markVerifiedManually()
        #expect(model.isVerified)
        await messenger.clearVerification("deniz")
        await model.load()
        await model.markVerifiedManually()
        #expect(!(await messenger.safetyNumber(for: "deniz")?.isVerified ?? false))
        await messenger.setSafetyNumberChanged("deniz")
        await model.load()
        model.requestAccept()
        model.cancelAccept()
        await model.acceptChangeWithoutVerifying()
        #expect(await messenger.safetyNumber(for: "deniz")?.hasUnverifiedChange == true)
    }
}
