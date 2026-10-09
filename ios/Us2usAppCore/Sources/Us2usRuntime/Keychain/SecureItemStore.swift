import Foundation
import Security
import Us2usKit

/// Small secrets kept outside the vault (the device secret of §5.2).
public protocol SecureItemStore: Sendable {
    func read(_ name: String) throws -> Data?
    /// Stores `data`, replacing any previous value.
    func write(_ data: Data, for name: String) throws
    func delete(_ name: String) throws
    /// Deletes every item of this store ("erase everything", stale items after a reinstall).
    func deleteAll() throws
}

public enum KeychainStoreError: Error, Equatable, Sendable {
    case unexpectedStatus(OSStatus)
}

/// Keychain generic-password items with the strictest attributes that still
/// work for a foreground-only app:
///
/// * `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`: readable only while the
///   device is unlocked, never migrated to another device, never in a backup.
/// * `kSecAttrSynchronizable = false` on every query and write: never in
///   iCloud Keychain.
///
/// Background work (overdue ghost wipes) needs no Keychain item: deleting a
/// key slot needs no key (§5.3).
public struct KeychainItemStore: SecureItemStore {
    public let service: String
    public let accessGroup: String?

    public init(service: String = "com.syconx.us2us.runtime", accessGroup: String? = nil) {
        self.service = service
        self.accessGroup = accessGroup
    }

    private func query(_ name: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
            kSecUseDataProtectionKeychain as String: true,
        ]
        if let name { query[kSecAttrAccount as String] = name }
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        return query
    }

    public func read(_ name: String) throws -> Data? {
        var query = query(name)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess: return result as? Data
        case errSecItemNotFound: return nil
        default: throw KeychainStoreError.unexpectedStatus(status)
        }
    }

    public func write(_ data: Data, for name: String) throws {
        try delete(name)
        var attributes = query(name)
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainStoreError.unexpectedStatus(status) }
    }

    public func delete(_ name: String) throws {
        let status = SecItemDelete(query(name) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainStoreError.unexpectedStatus(status)
        }
    }

    public func deleteAll() throws {
        let status = SecItemDelete(query(nil) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainStoreError.unexpectedStatus(status)
        }
    }

    /// The accessibility and synchronizable attributes of a stored item, for tests.
    func attributes(of name: String) throws -> (accessible: String?, synchronizable: Bool)? {
        var query = query(name)
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let attributes = result as? [String: Any] else {
            if status == errSecItemNotFound { return nil }
            throw KeychainStoreError.unexpectedStatus(status)
        }
        let synchronizable = (attributes[kSecAttrSynchronizable as String] as? Bool) ?? false
        return (attributes[kSecAttrAccessible as String] as? String, synchronizable)
    }

    /// Whether this process may use the Keychain at all. Package tests run in
    /// Apple's `xctest` runner, which has no keychain entitlement on the
    /// Simulator (`errSecMissingEntitlement`); keychain tests skip there.
    public static func isUsable(service: String = "com.syconx.us2us.runtime.probe") -> Bool {
        let store = KeychainItemStore(service: service)
        do {
            try store.write(Data([1]), for: "probe")
            try store.delete("probe")
            return true
        } catch {
            return false
        }
    }
}

/// Items held in memory, for tests and previews.
public final class InMemorySecureItemStore: SecureItemStore {
    private let items = LockedState<[String: Data]>([:])

    public init() {}

    public func read(_ name: String) throws -> Data? { items.withLock { $0[name] } }
    public func write(_ data: Data, for name: String) throws { items.withLock { $0[name] = data } }
    public func delete(_ name: String) throws { items.withLock { $0[name] = nil } }
    public func deleteAll() throws { items.withLock { $0 = [:] } }

    public var count: Int { items.withLock { $0.count } }
}

/// The vault's device secret (§5.2) as a Keychain item of ``SecureItemStore``:
/// `WhenUnlockedThisDeviceOnly`, never synchronized, never exported. (Us2usKit's
/// own `KeychainDeviceSecretProvider` uses "after first unlock"; the app only
/// opens the vault in the foreground, so the stricter class costs nothing.)
public struct DeviceSecretItem: DeviceSecretProvider {
    static let itemName = "device-secret"
    public let store: any SecureItemStore

    public init(store: any SecureItemStore) {
        self.store = store
    }

    public func loadDeviceSecret() throws -> Data? { try store.read(Self.itemName) }
    public func storeDeviceSecret(_ secret: Data) throws { try store.write(secret, for: Self.itemName) }
    public func deleteDeviceSecret() throws { try store.delete(Self.itemName) }
}
