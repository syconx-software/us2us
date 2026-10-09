import Foundation

// The two QR payloads the app shows and reads. Both are plain text, because
// AVFoundation hands QR contents back as text; nothing scanned is ever
// stored or logged.

// MARK: - Contact code

/// The user's contact code — 4.1 / 4.2 / 6.2 “Kodumu göster”, the 7.1
/// profile QR — and what “QR kodla ekle” reads (flows.md › By QR).
///
/// `us2us:@<username>#<fingerprint>`: the exact username and the identity
/// key's fingerprint (lower-case hex). Scanning it side by side adds the
/// contact *and* verifies it, so the live implementation checks the
/// fingerprint against the key the relay serves; the fixtures accept any.
/// A code without `#…` (older fixtures) still names the user.
enum ContactCode {
    static let prefix = "us2us:@"

    static func encode(username: String, keyFingerprint: String? = nil) -> String {
        guard let keyFingerprint, !keyFingerprint.isEmpty else { return prefix + username }
        return prefix + username + "#" + keyFingerprint
    }

    /// The username of a us2us contact code, or `nil` for anything else.
    static func username(from text: String) -> String? {
        decode(text)?.username
    }

    /// Username and fingerprint of a us2us contact code, or `nil` for anything else.
    static func decode(_ text: String) -> (username: String, keyFingerprint: String?)? {
        guard text.hasPrefix(prefix), text.count <= 512 else { return nil }
        let parts = text.dropFirst(prefix.count).split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)
        let name = String(parts.first ?? "")
        guard UsernameRules.isValid(name) else { return nil }
        let fingerprint = parts.count > 1 ? String(parts[1]) : nil
        if let fingerprint, !fingerprint.allSatisfy(\.isHexDigit) { return nil }
        return (name, fingerprint?.isEmpty == true ? nil : fingerprint)
    }
}

// MARK: - Safety-number code

/// How a safety number travels in its QR code (6.1 card, 6.2 scan).
///
/// The scannable fingerprint is binary (libsignal); QR contents come back as
/// text. So the payload is written as `U2SN:` + RFC 4648 base32 (upper case,
/// no padding) — entirely in the QR alphanumeric set, which keeps the code
/// small (37 × 37 modules for the fixture's 60 bytes) and unambiguous to
/// recognise: anything without the prefix is not ours.
enum SafetyQRCodec {
    static let prefix = "U2SN:"
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

    static func encode(_ payload: Data) -> String {
        var output = prefix
        var buffer = 0
        var bits = 0
        for byte in payload {
            buffer = (buffer << 8) | Int(byte)
            bits += 8
            while bits >= 5 {
                output.append(alphabet[(buffer >> (bits - 5)) & 31])
                bits -= 5
            }
        }
        if bits > 0 {
            output.append(alphabet[(buffer << (5 - bits)) & 31])
        }
        return output
    }

    /// The payload of a us2us safety-number code, or `nil` for anything else.
    static func decode(_ text: String) -> Data? {
        guard text.hasPrefix(prefix) else { return nil }
        let body = text.dropFirst(prefix.count)
        guard !body.isEmpty, body.count <= 512 else { return nil }
        var lookup: [Character: Int] = [:]
        for (index, character) in alphabet.enumerated() { lookup[character] = index }
        var data = Data()
        var buffer = 0
        var bits = 0
        for character in body {
            guard let value = lookup[character] else { return nil }
            buffer = ((buffer << 5) | value) & 0xFFFF
            bits += 5
            if bits >= 8 {
                data.append(UInt8((buffer >> (bits - 8)) & 0xFF))
                bits -= 8
            }
        }
        return data
    }

    /// Byte equality without an early exit (the time does not reveal where
    /// two payloads differ).
    static func constantTimeEquals(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var difference: UInt8 = 0
        for (a, b) in zip(lhs, rhs) { difference |= a ^ b }
        return difference == 0
    }
}
