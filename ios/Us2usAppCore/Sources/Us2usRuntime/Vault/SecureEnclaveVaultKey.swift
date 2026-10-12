import CryptoKit
import Foundation
import Security
import Us2usKit

/// The hardware step of vault format v2 on iOS (PROTOCOL.md §5.2): a P-256
/// key-agreement key generated inside the Secure Enclave, evaluated with
/// Us2usKit's ``VaultCurvePRF`` (`hw = SHA-256(ECDH(k, H2C(codeKey)))`) once
/// for every unlock attempt.
///
/// * The private key never leaves the Secure Enclave. What is stored is its
///   `dataRepresentation`, a blob that only this phone's Secure Enclave can
///   use, as the Keychain item `vault-prf-key` of the app's
///   ``SecureItemStore`` (`WhenUnlockedThisDeviceOnly`, never synchronized,
///   never in a backup). Deleting the store's items ("erase everything", the
///   optional wipe, leftovers of an earlier installation) therefore destroys
///   the key with the device secret.
/// * Access control: `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` with
///   `.privateKeyUsage` only. No biometry or user-presence flag: the code path
///   must not depend on the enrolled faces or fingers (those changes would
///   destroy the key and the vault). Not `WhenPasscodeSetThisDeviceOnly`:
///   turning the phone's passcode off would delete the key and with it every
///   way into the vault.
/// * Where there is no Secure Enclave (some Simulators), ``isAvailable`` is
///   false and new vaults stay v1.
public struct SecureEnclaveVaultKey: VaultHardwareKey {
    static let itemName = "vault-prf-key"
    public let store: any SecureItemStore

    public init(store: any SecureItemStore) {
        self.store = store
    }

    public var isAvailable: Bool { SecureEnclave.isAvailable }

    public func createKey() throws {
        guard SecureEnclave.isAvailable else { throw VaultHardwareKeyError.unavailable }
        var error: Unmanaged<CFError>?
        guard
            let access = SecAccessControlCreateWithFlags(
                nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, [.privateKeyUsage], &error)
        else {
            throw error.map { $0.takeRetainedValue() as Error } ?? VaultHardwareKeyError.unavailable
        }
        let key = try SecureEnclave.P256.KeyAgreement.PrivateKey(accessControl: access)
        try store.write(key.dataRepresentation, for: Self.itemName)
    }

    public func evaluate(codeKey: Data) throws -> Data {
        guard let blob = try store.read(Self.itemName) else { throw VaultHardwareKeyError.keyMissing }
        let key = try SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: blob)
        return try VaultCurvePRF.evaluate(codeKey: codeKey) { point in
            try key.sharedSecretFromKeyAgreement(with: point)
        }
    }

    public func deleteKey() throws {
        try store.delete(Self.itemName)
    }
}

/// Which hardware step ``VaultController`` gives the vault (§5.2).
public enum VaultHardwareKeyChoice: Sendable, Equatable {
    /// ``SecureEnclaveVaultKey`` in the controller's Keychain store: format v2
    /// where the phone has a Secure Enclave, v1 elsewhere (some Simulators).
    case secureEnclave
    /// No hardware step: new vaults are v1, and a v2 vault cannot be opened.
    case disabled
}
