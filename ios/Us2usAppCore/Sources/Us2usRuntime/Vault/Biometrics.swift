import Foundation
import LocalAuthentication
import Security

public enum BiometryKind: String, Sendable, Equatable, Codable {
    case none, touchID, faceID, opticID
}

/// Why a biometric unlock did not produce the key.
public enum BiometricError: Error, Equatable, Sendable {
    /// No biometric hardware, nothing enrolled, no device passcode, or no Secure Enclave.
    case unavailable
    /// The user or the system cancelled (e.g. the app went to the background).
    /// Does not count as a failed attempt.
    case cancelled
    /// The face or finger was not recognized. Counts towards the limit of 5.
    case failed
    /// The system locked biometrics after too many failures; the code is required.
    case lockout
    /// The enrolled biometrics changed or the key is gone (`.biometryCurrentSet`
    /// invalidates it); the code is required and the wrap is discarded.
    case invalidated
    /// Creating or using the key failed for another reason.
    case keyFailure
}

/// The current biometric situation of the device.
public struct BiometricAvailability: Sendable, Equatable {
    public var kind: BiometryKind
    /// Opaque hash of the enrolled faces/fingers (`LAContext.domainState`);
    /// `nil` when biometrics cannot be used right now.
    public var enrollmentState: Data?
    /// The system has locked biometrics (too many failures).
    public var lockedOut: Bool

    public init(kind: BiometryKind, enrollmentState: Data?, lockedOut: Bool = false) {
        self.kind = kind
        self.enrollmentState = enrollmentState
        self.lockedOut = lockedOut
    }

    public var isUsable: Bool { kind != .none && enrollmentState != nil && !lockedOut }
}

/// A hardware-bound key that wraps the DEK and can only be used after a
/// biometric match (PROTOCOL.md §5.2 "Biometric quick unlock").
public protocol BiometricKeyProtecting: Sendable {
    func availability() -> BiometricAvailability
    /// Creates a fresh hardware key (replacing any previous one) and returns
    /// `secret` encrypted to it.
    func wrap(_ secret: Data) throws(BiometricError) -> Data
    /// Shows the biometric prompt with `reason` and decrypts `wrapped`.
    func unwrap(_ wrapped: Data, reason: String, cancelTitle: String) async throws(BiometricError) -> Data
    func deleteKey()
}

/// The production protector: a P-256 key in the Secure Enclave with
/// `[.privateKeyUsage, .biometryCurrentSet]` and
/// `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, never synchronizable,
/// unusable on any other device and invalidated by the OS as soon as a face
/// or finger is added or removed. The DEK is wrapped with ECIES
/// (`eciesEncryptionCofactorVariableIVX963SHA256AESGCM`): encrypting needs only
/// the public key, decrypting needs the biometric match.
///
/// The prompt is shown by `LAContext.evaluatePolicy` first, and the
/// authenticated context is handed to the key operation, so error reporting is
/// exact (cancel vs. not recognized vs. lockout) and there is one prompt only.
public struct SecureEnclaveBiometricProtector: BiometricKeyProtecting {
    public let applicationTag: Data
    static let algorithm = SecKeyAlgorithm.eciesEncryptionCofactorVariableIVX963SHA256AESGCM

    public init(applicationTag: String = "com.syconx.us2us.biometric-unlock") {
        self.applicationTag = Data(applicationTag.utf8)
    }

    public func availability() -> BiometricAvailability {
        let context = LAContext()
        var error: NSError?
        let canEvaluate = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        let kind: BiometryKind
        switch context.biometryType {
        case .faceID: kind = .faceID
        case .touchID: kind = .touchID
        case .opticID: kind = .opticID
        default: kind = .none
        }
        if !canEvaluate {
            let lockedOut = (error.map { LAError.Code(rawValue: $0.code) == .biometryLockout }) ?? false
            return BiometricAvailability(kind: kind, enrollmentState: nil, lockedOut: lockedOut)
        }
        guard Self.offersQuickUnlock else { return BiometricAvailability(kind: .none, enrollmentState: nil) }
        let state: Data?
        if #available(iOS 18.0, *) {
            state = context.domainState.biometry.stateHash
        } else {
            state = context.evaluatedPolicyDomainState
        }
        return BiometricAvailability(kind: kind, enrollmentState: state)
    }

    /// Quick unlock is offered only on a device, never in a Simulator
    /// build, on purpose. Recent Simulators do report a Secure Enclave
    /// (`SecureEnclave.isAvailable` is true there, and the vault's v2
    /// hardware step uses it), but a Simulator is not a phone: that enclave
    /// is the host Mac's, and the Simulator's Face ID is a menu switch, so
    /// quick unlock there would protect nothing and would only put system
    /// biometric prompts in the way of the UI tests and the design harness.
    /// Every iPhone the app runs on has a Secure Enclave, so device builds
    /// always pass this check, and creating the key still fails cleanly
    /// (`.keyFailure`) if it cannot be made.
    static var offersQuickUnlock: Bool {
        #if targetEnvironment(simulator)
            return false
        #else
            return true
        #endif
    }

    public func wrap(_ secret: Data) throws(BiometricError) -> Data {
        deleteKey()
        var error: Unmanaged<CFError>?
        guard
            let access = SecAccessControlCreateWithFlags(
                nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, [.privateKeyUsage, .biometryCurrentSet], &error)
        else { throw .keyFailure }
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: applicationTag,
                kSecAttrAccessControl as String: access,
            ] as [String: Any],
        ]
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error),
            let publicKey = SecKeyCopyPublicKey(privateKey),
            SecKeyIsAlgorithmSupported(publicKey, .encrypt, Self.algorithm),
            let wrapped = SecKeyCreateEncryptedData(publicKey, Self.algorithm, secret as CFData, &error) as Data?
        else {
            deleteKey()
            throw .keyFailure
        }
        return wrapped
    }

    public func unwrap(_ wrapped: Data, reason: String, cancelTitle: String) async throws(BiometricError) -> Data {
        let tag = applicationTag
        let result: Result<Data, BiometricError> = await Task.detached(priority: .userInitiated) {
            let context = LAContext()
            context.localizedFallbackTitle = ""  // our own code keypad is the fallback
            context.localizedCancelTitle = cancelTitle
            do {
                guard try await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
                else { return .failure(.failed) }
            } catch let error as LAError {
                return .failure(Self.map(error.code))
            } catch {
                return .failure(.failed)
            }
            let query: [String: Any] = [
                kSecClass as String: kSecClassKey,
                kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
                kSecAttrApplicationTag as String: tag,
                kSecReturnRef as String: true,
                kSecUseAuthenticationContext as String: context,
            ]
            var item: CFTypeRef?
            let status = SecItemCopyMatching(query as CFDictionary, &item)
            guard status == errSecSuccess, let item, CFGetTypeID(item) == SecKeyGetTypeID() else {
                return .failure(status == errSecItemNotFound ? .invalidated : .keyFailure)
            }
            let privateKey = item as! SecKey  // checked above
            var error: Unmanaged<CFError>?
            guard let secret = SecKeyCreateDecryptedData(privateKey, Self.algorithm, wrapped as CFData, &error) as Data?
            else {
                let code = error.map { CFErrorGetCode($0.takeRetainedValue()) }
                // A cancel can still happen here if the OS asks again; any other
                // refusal is reported as a key failure: the code is required this
                // time and the next code unlock re-creates the key.
                return .failure(code == Int(errSecUserCanceled) ? .cancelled : .keyFailure)
            }
            return .success(secret)
        }.value
        return try result.get()
    }

    static func map(_ code: LAError.Code) -> BiometricError {
        switch code {
        case .userCancel, .systemCancel, .appCancel, .userFallback, .notInteractive: return .cancelled
        case .authenticationFailed: return .failed
        case .biometryLockout: return .lockout
        case .biometryNotAvailable, .biometryNotEnrolled, .passcodeNotSet: return .unavailable
        default: return .failed
        }
    }

    public func deleteKey() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: applicationTag,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
