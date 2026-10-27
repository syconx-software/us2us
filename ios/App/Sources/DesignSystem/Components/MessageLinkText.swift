import SwiftUI

private struct MessageInviteActionKey: EnvironmentKey {
    static let defaultValue: (@MainActor @Sendable (String) -> Void)? = nil
}

extension EnvironmentValues {
    var messageInviteAction: (@MainActor @Sendable (String) -> Void)? {
        get { self[MessageInviteActionKey.self] }
        set { self[MessageInviteActionKey.self] = newValue }
    }
}

/// Literal message text with local, explicit tap actions; no previews or network lookups.
struct MessageLinkText: View {
    let text: String
    @Environment(\.messageInviteAction) private var invite

    var body: some View {
        let matches = MessageLinks.detect(text)
        if matches.isEmpty {
            // Keep ordinary messages on SwiftUI's plain-text layout path.
            Text(verbatim: text)
        } else {
            Text(attributed(matches))
                .environment(\.openURL, OpenURLAction { url in
                    guard url.scheme == "https", url.host == "message-link.invalid",
                          let index = Int(url.path.dropFirst()), matches.indices.contains(index) else {
                        return .discarded
                    }
                    switch matches[index].target {
                    case .web(let address): return .systemAction(address)
                    case .communityInvite(let code):
                        invite?(code)
                        return .handled
                    }
                })
        }
    }

    private func attributed(_ matches: [MessageLinks.Match]) -> AttributedString {
        let result = NSMutableAttributedString(string: text)
        for (index, match) in matches.enumerated() {
            if case .communityInvite = match.target, invite == nil { continue }
            // Only an opaque index enters SwiftUI's URL action, never the invitation token.
            result.addAttributes([
                .link: URL(string: "https://message-link.invalid/\(index)")!,
                .underlineStyle: NSUnderlineStyle.single.rawValue,
            ], range: match.range)
        }
        return AttributedString(result)
    }
}
