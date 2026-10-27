import Foundation

/// Local detection only. Invitation credentials never become external URLs.
enum MessageLinks {
    enum Target: Equatable, Sendable {
        case web(URL)
        case communityInvite(String)
    }
    struct Match: Equatable, Sendable {
        let range: NSRange
        let target: Target
    }
    private static let pattern = try! NSRegularExpression(
        pattern: #"(?<![\p{L}\p{N}_:/-])(?:us2us-community:v1:[A-Za-z0-9_-]+|(?i:https?://[^\s<>]+|www\.[^\s<>]+))"#)

    static func detect(_ text: String) -> [Match] {
        // Every supported target contains ':' or '.'. Most chat messages need no regex work.
        guard text.contains(":") || text.contains(".") else { return [] }
        let source = text as NSString
        return pattern.matches(in: text, range: NSRange(location: 0, length: source.length)).compactMap { match in
            var value = source.substring(with: match.range)
            if value.hasPrefix("us2us-community:v1:") {
                guard value.utf8.count < 2048 else { return nil }
                return Match(range: match.range, target: .communityInvite(value))
            }
            let pairs: [Character: Character] = [")": "(", "]": "[", "}": "{"]
            var counts: [Character: Int] = [:]
            for character in value where "()[]{}".contains(character) { counts[character, default: 0] += 1 }
            while let last = value.last {
                if ".,;:!?\"'”’".contains(last) { value.removeLast(); continue }
                if let opening = pairs[last], counts[last, default: 0] > counts[opening, default: 0] {
                    counts[last, default: 0] -= 1
                    value.removeLast(); continue
                }
                break
            }
            let address = value.lowercased().hasPrefix("www.") ? "https://" + value : value
            guard let url = URL(string: address), let host = url.host, !host.isEmpty,
                  ["https", "http"].contains(url.scheme?.lowercased()), url.user == nil, url.password == nil else { return nil }
            return Match(range: NSRange(location: match.range.location, length: (value as NSString).length), target: .web(url))
        }
    }
}
