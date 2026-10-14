package com.syconx.us2us.feature.chats.invite

import com.syconx.us2us.domain.UsernameRules
import java.net.URI
import java.net.URISyntaxException

/**
 * Invite links: `https://us2us.app/u/<username>` (docs/INVITES.md).
 *
 * The link carries the inviter's username and nothing else: no account id, no key, no tracking
 * parameter. The website serves a small page for it (relay/app/content/invite.ts) that never
 * checks whether the name exists; with the app installed, the link is an App Link (verified for
 * `us2us.app`, path prefix `/u/`) and opens 4.2 Yeni sohbet with the name searched: still the exact
 * lookup, still a message request.
 *
 * App Links only: no custom URL scheme, so no other app can hand the app a link that did not come
 * through the domain check.
 */
object InviteLink {
    const val HOST = "us2us.app"
    const val PATH_PREFIX = "/u/"

    /**
     * The username as a link carries it: [UsernameRules.normalize] (white space and one leading “@”
     * dropped, NFKC, lower case without a locale: “I” → “i”, never “ı”) and valid by
     * [UsernameRules]. Nothing is stripped or converted, so “şeyma” or “de niz” give `null`
     * instead of another name.
     */
    fun normalizedUsername(input: String): String? {
        val name = UsernameRules.normalize(input)
        return if (UsernameRules.isValid(name)) name else null
    }

    /** `https://us2us.app/u/<username>`, or `null` when [username] is not a valid one. */
    fun url(username: String): String? = normalizedUsername(username)?.let { "https://$HOST$PATH_PREFIX$it" }

    /** The link without the scheme, as the sheet shows it: “us2us.app/u/selin_n”. */
    fun displayString(username: String): String? = normalizedUsername(username)?.let { "$HOST$PATH_PREFIX$it" }

    /**
     * The username of an invite link, or `null` for anything else. Only the canonical form is
     * accepted: `https`, exactly `us2us.app`, no user, password or port, the path `/u/<name>` (one
     * optional trailing slash) with the name already normalized (`normalize(name) == name`), valid
     * and not percent-encoded. A query (the website's `?lang=`) or fragment is ignored.
     */
    fun username(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.isOpaque || !uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.port != -1) return null
        if (!uri.rawAuthority.equals(HOST, ignoreCase = true)) return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith(PATH_PREFIX)) return null
        val name = path.removePrefix(PATH_PREFIX).removeSuffix("/")
        if (UsernameRules.normalize(name) != name || !UsernameRules.isValid(name)) return null
        return name
    }
}
