import Foundation
import Security

/// Holds the 32-byte device secret that is mixed into the vault key (PROTOCOL.md
/// §5.2). It must never be exported, synced or included in backups.
public protocol DeviceSecretProvider: Sendable {
    func loadDeviceSecret() throws -> Data?
    /// Stores `secret`, replacing any previous one.
    func storeDeviceSecret(_ secret: Data) throws
    func deleteDeviceSecret() throws
}

public enum KeychainError: Error, Equatable, Sendable {
    case unexpectedStatus(OSStatus)
}

/// Keychain-backed device secret: a generic password item in the data
/// protection keychain with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`
/// and never synchronizable, so it is excluded from iCloud Keychain, backups
/// and device-to-device migration, and readable only while the phone is
/// unlocked, like the vault file (complete file protection). The app uses the
/// same attributes through its own Keychain store (Us2usRuntime
/// `DeviceSecretItem`).
public struct KeychainDeviceSecretProvider: DeviceSecretProvider {
    public let service: String
    public let account: String
    /// Set to share the secret with app extensions (`group.com.syconx.us2us`
    /// with the team prefix, as listed in the entitlements).
    public let accessGroup: String?

    public init(
        service: String = "com.syconx.us2us.vault", account: String = "device-secret", accessGroup: String? = nil
    ) {
        self.service = service
        self.account = account
        self.accessGroup = accessGroup
    }

    private var baseQuery: [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
            kSecUseDataProtectionKeychain as String: true,
        ]
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        return query
    }

    public func loadDeviceSecret() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess: return result as? Data
        case errSecItemNotFound: return nil
        default: throw KeychainError.unexpectedStatus(status)
        }
    }

    public func storeDeviceSecret(_ secret: Data) throws {
        try deleteDeviceSecret()
        var attributes = baseQuery
        attributes[kSecValueData as String] = secret
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.unexpectedStatus(status) }
    }

    public func deleteDeviceSecret() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unexpectedStatus(status)
        }
    }
}

/// Device secret held in memory, for tests and previews.
public final class InMemoryDeviceSecretProvider: DeviceSecretProvider {
    private let secret = Locked<Data?>(nil)

    public init(secret: Data? = nil) {
        self.secret.withLock { $0 = secret }
    }

    public func loadDeviceSecret() throws -> Data? { secret.withLock { $0 } }
    public func storeDeviceSecret(_ secret: Data) throws { self.secret.withLock { $0 = secret } }
    public func deleteDeviceSecret() throws { secret.withLock { $0 = nil } }
}
