import Foundation

// Binary encodings used on the wire and in test vectors (PROTOCOL.md §2).

extension Data {
    /// RFC 4648 §5 base64url **without padding**, the encoding of every binary
    /// value in relay JSON.
    public func base64URLEncodedString() -> String {
        var encoded = base64EncodedString()
        encoded = encoded.replacingOccurrences(of: "+", with: "-")
        encoded = encoded.replacingOccurrences(of: "/", with: "_")
        while encoded.hasSuffix("=") { encoded.removeLast() }
        return encoded
    }

    /// Strict base64url decoding: only the URL-safe alphabet, no padding, no
    /// whitespace. Returns `nil` for anything else.
    public init?(base64URLEncoded string: String) {
        let isAlphabet: (UInt8) -> Bool = { byte in
            switch byte {
            case UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "a")...UInt8(ascii: "z"),
                UInt8(ascii: "0")...UInt8(ascii: "9"), UInt8(ascii: "-"), UInt8(ascii: "_"):
                return true
            default:
                return false
            }
        }
        let utf8 = string.utf8
        // A single trailing sextet cannot encode a whole byte.
        guard utf8.allSatisfy(isAlphabet), utf8.count % 4 != 1 else { return nil }

        var standard = string.replacingOccurrences(of: "-", with: "+")
        standard = standard.replacingOccurrences(of: "_", with: "/")
        standard += String(repeating: "=", count: (4 - utf8.count % 4) % 4)
        guard let decoded = Data(base64Encoded: standard) else { return nil }
        self = decoded
    }

    /// Lowercase hexadecimal, the encoding of binary values in test vectors.
    public var hexString: String {
        let digits = Array("0123456789abcdef".utf8)
        var out = [UInt8]()
        out.reserveCapacity(count * 2)
        for byte in self {
            out.append(digits[Int(byte >> 4)])
            out.append(digits[Int(byte & 0x0F)])
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Decodes upper- or lowercase hexadecimal; `nil` on odd length or a
    /// non-hex character.
    public init?(hexString: String) {
        let utf8 = Array(hexString.utf8)
        guard utf8.count % 2 == 0 else { return nil }
        func nibble(_ c: UInt8) -> UInt8? {
            switch c {
            case UInt8(ascii: "0")...UInt8(ascii: "9"): return c - UInt8(ascii: "0")
            case UInt8(ascii: "a")...UInt8(ascii: "f"): return c - UInt8(ascii: "a") + 10
            case UInt8(ascii: "A")...UInt8(ascii: "F"): return c - UInt8(ascii: "A") + 10
            default: return nil
            }
        }
        var bytes = [UInt8]()
        bytes.reserveCapacity(utf8.count / 2)
        var index = 0
        while index < utf8.count {
            guard let high = nibble(utf8[index]), let low = nibble(utf8[index + 1]) else { return nil }
            bytes.append(high << 4 | low)
            index += 2
        }
        self.init(bytes)
    }
}

extension Data {
    mutating func appendBigEndian(_ value: UInt32) {
        Swift.withUnsafeBytes(of: value.bigEndian) { append(contentsOf: $0) }
    }

    mutating func appendBigEndian(_ value: UInt64) {
        Swift.withUnsafeBytes(of: value.bigEndian) { append(contentsOf: $0) }
    }

    /// Reads a big-endian `UInt32` at `offset` (relative to `startIndex`).
    func bigEndianUInt32(at offset: Int) -> UInt32 {
        let start = startIndex + offset
        return self[start..<start + 4].reduce(0) { $0 << 8 | UInt32($1) }
    }
}
