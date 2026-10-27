import Foundation
import Observation

/// A full-screen, app-presented system picker is temporary coverage, not leaving the chat.
/// Actual backgrounding still locks and cancels the picker, including its pending result.
@MainActor
@Observable
final class SystemPickerActivity {
    static let shared = SystemPickerActivity()
    private var token: UUID?
    @ObservationIgnored private var cancellation: (() -> Void)?
    var isPresented: Bool { token != nil }

    func begin(cancel: @escaping () -> Void) -> UUID? {
        guard token == nil else { return nil }
        let token = UUID()
        self.token = token
        cancellation = cancel
        return token
    }

    func end(_ token: UUID?) {
        guard let token, self.token == token else { return }
        self.token = nil
        cancellation = nil
    }

    func cancel() {
        let action = cancellation
        token = nil
        cancellation = nil
        action?()
    }
}
