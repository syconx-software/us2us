import Foundation

extension String {
    /// Removes leading and trailing characters with the Unicode `White_Space`
    /// property (a locale-independent definition both platforms can match).
    func trimmingUnicodeWhitespace() -> String {
        let scalars = unicodeScalars
        guard let first = scalars.firstIndex(where: { !$0.properties.isWhitespace }),
            let last = scalars.lastIndex(where: { !$0.properties.isWhitespace })
        else { return "" }
        return String(scalars[first...last])
    }

    /// Unicode NFKC.
    var nfkc: String { precomposedStringWithCompatibilityMapping }
}
