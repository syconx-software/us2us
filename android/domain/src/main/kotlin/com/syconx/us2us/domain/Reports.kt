package com.syconx.us2us.domain

// MARK: - Reports (App Review 1.2)

/**
 * Who a report (“Bildir”) is about, and nothing else: the username as this phone knows it. No
 * message, key or account id ever goes into a report.
 */
data class ReportSubject(
    /**
     * The username; `null` when this phone has none for the person (only a display name or the
     * account id, which a report never carries).
     */
    val username: String?,
    /**
     * The directory confirmed the username (PROTOCOL §3.3); an unconfirmed one is only what the
     * sender claimed, and the report says so.
     */
    val isUsernameVerified: Boolean,
) {
    companion object {
        /** Nobody the phone can name: the report says “username unknown”. */
        val Unknown = ReportSubject(username = null, isUsernameVerified = false)
    }
}

/** Why the user reports someone (“Bildir”), in the order the sheet lists them. [raw] is the iOS raw value. */
enum class ReportReason(val raw: String) {
    Spam("spam"),
    Harassment("harassment"),
    Inappropriate("inappropriate"),
    Impersonation("impersonation"),
    Other("other"),
    ;

    companion object {
        fun fromRaw(raw: String?): ReportReason? = entries.firstOrNull { it.raw == raw }
    }
}
