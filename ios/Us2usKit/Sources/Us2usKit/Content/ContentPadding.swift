import Foundation

public enum ContentPaddingError: Error, Equatable, Sendable {
    case malformedPadding
}

/// Padding of the serialized `Content` before session encryption (PROTOCOL.md
/// §5.1), so ciphertext sizes only reveal a multiple of 160 bytes.
public enum ContentPadding {
    public static let blockSize = 160

    /// Appends `0x80`, then zeros up to the next multiple of 160. A full block
    /// is added when the input already is a multiple of 160.
    public static func pad(_ plaintext: Data) -> Data {
        var padded = plaintext
        padded.append(0x80)
        let remainder = padded.count % blockSize
        if remainder != 0 {
            padded.append(Data(count: blockSize - remainder))
        }
        return padded
    }

    /// Strips trailing zeros, then exactly one `0x80`; anything else fails.
    public static func unpad(_ padded: Data) throws(ContentPaddingError) -> Data {
        guard let marker = padded.lastIndex(where: { $0 != 0 }), padded[marker] == 0x80 else {
            throw .malformedPadding
        }
        return Data(padded[padded.startIndex..<marker])
    }
}
