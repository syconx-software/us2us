// Keychain check for KeychainDeviceSecretProvider (PROTOCOL.md §5.2), run by
// ../keychain-smoke.sh inside a tiny Simulator app that has a keychain
// entitlement. Hostless package tests cannot do this: they run in Apple's
// xctest runner, where every SecItem call fails with errSecMissingEntitlement.
//
// Compiled together with Sources/Us2usKit/Vault/DeviceSecretProvider.swift and
// Sources/Us2usKit/Support/Security.swift. Results go to Documents/result.txt.

import Foundation
import Security

let service = "com.syconx.us2us.keychain-smoke"
let provider = KeychainDeviceSecretProvider(service: service, account: "device-secret")
let resultURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    .appendingPathComponent("result.txt")
var report = ""

@MainActor func finish(passed: Bool) -> Never {
    report += passed ? "PASS\n" : "FAIL\n"
    try? report.write(to: resultURL, atomically: true, encoding: .utf8)
    exit(passed ? 0 : 1)
}

@MainActor func check(_ condition: Bool, _ description: String) {
    report += (condition ? "ok   " : "FAIL ") + description + "\n"
    if !condition { finish(passed: false) }
}

func storedAttributes() -> [String: Any]? {
    let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecAttrAccount as String: "device-secret",
        kSecReturnAttributes as String: true,
        kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var result: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
    return result as? [String: Any]
}

do {
    try provider.deleteDeviceSecret()
    check(try provider.loadDeviceSecret() == nil, "no secret before the first store")

    let first = Data((0..<32).map { UInt8($0) })
    try provider.storeDeviceSecret(first)
    check(try provider.loadDeviceSecret() == first, "load returns the stored secret")

    let second = Data(repeating: 0xA5, count: 32)
    try provider.storeDeviceSecret(second)
    check(try provider.loadDeviceSecret() == second, "store replaces the previous secret")

    let attributes = storedAttributes()
    check(attributes != nil, "the item's attributes are readable")
    check(
        attributes?[kSecAttrAccessible as String] as? String
            == kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String,
        "accessibility is AfterFirstUnlockThisDeviceOnly (never migrated or backed up)")
    check(
        (attributes?[kSecAttrSynchronizable as String] as? NSNumber)?.boolValue != true,
        "not synchronized to iCloud Keychain")

    try provider.deleteDeviceSecret()
    check(try provider.loadDeviceSecret() == nil, "delete removes the secret")
    try provider.deleteDeviceSecret()  // throws if a missing item were an error
    check(try provider.loadDeviceSecret() == nil, "deleting a missing secret is not an error")
    finish(passed: true)
} catch {
    report += "error: \(error)\n"
    finish(passed: false)
}
