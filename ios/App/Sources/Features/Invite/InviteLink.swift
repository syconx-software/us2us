import Foundation

/// Invite links — `https://us2us.app/u/<username>` (docs/INVITES.md).
///
/// The link carries the inviter's username and nothing else: no account id,
/// no key, no tracking parameter. The website serves a small page for it
/// (relay/app/content/invite.ts) that never checks whether the name exists;
/// with the app installed, the link is a universal link (Associated Domains
/// `applinks:us2us.app`, path `/u/*`) and opens 4.2 Yeni sohbet with the
/// name searched — still the exact lookup, still a message request.
///
/// Universal links only: no custom URL scheme, so no other app can hand the
/// app a link that did not come through Apple's domain check.
enum InviteLink {
    static let host = "us2us.app"
    static let pathPrefix = "/u/"

    /// The username as a link carries it: `UsernameRules.normalize` (white
    /// space and one leading “@” dropped, NFKC, lower case without a locale —
    /// “I” → “i”, never “ı”) and valid by `UsernameRules`. Nothing is
    /// stripped or converted, so “şeyma” or “de niz” give nil instead of
    /// another name.
    static func normalizedUsername(_ input: String) -> String? {
        let name = UsernameRules.normalize(input)
        return UsernameRules.isValid(name) ? name : nil
    }

    /// `https://us2us.app/u/<username>`, or nil when `username` is not a valid one.
    static func url(for username: String) -> URL? {
        guard let name = normalizedUsername(username) else { return nil }
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = pathPrefix + name
        return components.url
    }

    /// The link without the scheme, as the sheet shows it: “us2us.app/u/selin_n”.
    static func displayString(for username: String) -> String? {
        normalizedUsername(username).map { host + pathPrefix + $0 }
    }

    /// The username of an invite link, or nil for anything else. Only the
    /// canonical form is accepted — `https`, exactly `us2us.app`, no user,
    /// password or port, the path `/u/<name>` (one optional trailing slash)
    /// with the name already normalized (`normalize(name) == name`), valid
    /// and not percent-encoded. A query (the website's `?lang=`) or
    /// fragment is ignored.
    static func username(from url: URL) -> String? {
        guard
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            components.scheme?.lowercased() == "https",
            components.percentEncodedHost?.lowercased() == host,
            components.user == nil, components.password == nil, components.port == nil
        else { return nil }
        let path = components.percentEncodedPath
        guard path.hasPrefix(pathPrefix) else { return nil }
        var name = String(path.dropFirst(pathPrefix.count))
        if name.hasSuffix("/") { name.removeLast() }
        guard UsernameRules.normalize(name) == name, UsernameRules.isValid(name) else { return nil }
        return name
    }
}
