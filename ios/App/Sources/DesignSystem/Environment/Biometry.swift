import LocalAuthentication

/// Which biometric unlock the device has. Only asks the system — the
/// evaluation itself (and the biometric vault key) stays behind
/// `MessengerService` (`unlockWithBiometrics`, `setBiometricUnlock`).
enum Biometry: Equatable, Sendable {
    case none
    case faceID
    case touchID

    /// The biometric hardware, enrolled or not (2.3 offers the option on an
    /// unenrolled device — the lock falls back to the code until it is
    /// enrolled). `biometryType` is set by `canEvaluatePolicy` either way.
    @MainActor static var hardware: Biometry {
        let context = LAContext()
        var error: NSError?
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        return Biometry(context.biometryType)
    }

    /// The enrolled biometry the lock can use right now (3.1), or `.none`.
    @MainActor static var enrolled: Biometry {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else { return .none }
        return Biometry(context.biometryType)
    }

    private init(_ type: LABiometryType) {
        switch type {
        case .faceID: self = .faceID
        case .touchID: self = .touchID
        default: self = .none
        }
    }
}
