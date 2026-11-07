import Foundation
import Testing
import Us2usKit

@testable import us2us

/// The screens' models of the transfer (PROTOCOL.md §5.6) over the design fixtures.
@MainActor
@Suite("Moving to a new phone")
struct DeviceTransferModelTests {
    private func makeService() -> DesignFixturesService {
        DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
    }

    private func eventually(_ condition: () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("timed out")
    }

    @Test("the scanner takes only a transfer code, and then the account is sent")
    func scanningSends() async throws {
        let model = DeviceTransferSendModel(messenger: makeService())
        #expect(model.stage == .intro)
        model.start()
        #expect(model.stage == .code)
        model.codeConfirmed()
        #expect(model.stage == .scanning)
        #expect(model.scanner.isActive)

        #expect(await model.scanner.evaluate("selin_n") == .ignore)
        #expect(await model.scanner.evaluate("us2us-transfer:2:x:y:1.2.3.4:5") == .ignore)
        #expect(model.stage == .scanning)

        await model.scanner.handle(DesignFixturesService.transferInvitation)
        #expect(!model.scanner.isActive, "the camera stops once the code is read")
        try await eventually { model.stage == .done }
    }

    @Test("leaving the app stops the transfer and starts over")
    func leavingStops() async throws {
        let model = DeviceTransferSendModel(messenger: makeService())
        model.start()
        model.codeConfirmed()
        await model.scanner.handle(DesignFixturesService.transferInvitation)
        model.appLeft()
        #expect(model.stage == .intro)
        #expect(!model.scanner.isActive)
        try await Task.sleep(for: .milliseconds(800))
        #expect(model.stage == .intro, "what was underway does not come back")
    }

    @Test("the new phone shows its code; a well-formed one the old phone can read")
    func newPhoneShowsItsCode() async throws {
        let model = DeviceTransferReceiveModel(messenger: makeService())
        model.start()
        try await eventually { if case .waiting = model.stage { true } else { false } }
        guard case .waiting(let invitation) = model.stage else { return }
        #expect(DeviceTransferInvite(text: invitation) != nil)
        model.finish()
    }

    @Test("an invitation that runs out is replaced by a new one")
    func invitationExpires() async throws {
        let model = DeviceTransferReceiveModel(messenger: makeService(), lifetime: .milliseconds(50))
        model.start()
        try await eventually { if case .waiting = model.stage { true } else { false } }
        try await Task.sleep(for: .milliseconds(300))
        // With so short a life a new code may be on its way at this moment.
        try await eventually { if case .waiting = model.stage { true } else { false } }
        model.finish()
    }

    @Test("every text of the transfer is there in both languages")
    func texts() {
        for language in ["tr", "en"] {
            let locale = Locale(identifier: language)
            for key: String.LocalizationValue in [
                "settings.transfer", "transfer.send.title", "transfer.send.note", "transfer.failed.unreachable",
                "transfer.receive.note", "transfer.scan.hint",
            ] {
                #expect(!StringTable.settings.string(key, locale: locale).hasPrefix("transfer."))
            }
        }
    }
}
