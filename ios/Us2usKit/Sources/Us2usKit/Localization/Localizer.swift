import Foundation

/// Every user-facing string the kit exposes. Each key exists in `en` and `tr`
/// in `Resources/Localizable.xcstrings`; a unit test enforces that.
public enum LocalizationKey: String, CaseIterable, Sendable {
    /// Generic in-app banner for a message in another conversation.
    case newMessage = "NEW_MESSAGE"

    case usernameTooShort = "error.username.too_short"
    case usernameTooLong = "error.username.too_long"
    case usernameMustStartWithLetter = "error.username.must_start_with_letter"
    case usernameInvalidCharacters = "error.username.invalid_characters"

    case secretCodeNotSixDigits = "error.secret_code.not_six_digits"
    case secretCodeRepeatedDigit = "error.secret_code.repeated_digit"
    case secretCodeSequence = "error.secret_code.sequence"
    case secretCodeTooCommon = "error.secret_code.too_common"
    case secretCodeMismatch = "error.secret_code.mismatch"

    case vaultWrongCode = "error.vault.wrong_code"
    /// "Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir."
    case vaultWrongCodeWarning = "notice.vault.wrong_code_warning"  // %lld = attempts left (≥ 2), %@ = duration
    case vaultWrongCodeWarningOne = "notice.vault.wrong_code_warning_one"  // %@ = duration
    case vaultLockedOut = "error.vault.locked_out"  // %@ = remaining time
    case vaultWiped = "error.vault.wiped"
    case vaultDeviceSecretMissing = "error.vault.device_secret_missing"
    case vaultCorrupted = "error.vault.corrupted"
    case vaultAlreadyExists = "error.vault.already_exists"
    case vaultNotFound = "error.vault.not_found"
    case storageIntegrity = "error.storage.integrity"

    case backupWrongPassword = "error.backup.wrong_password"
    case backupInvalidFile = "error.backup.invalid_file"

    case backupPasswordTooShort = "error.backup_password.too_short"
    case backupPasswordTooCommon = "error.backup_password.too_common"
    case backupPasswordRepeatedCharacter = "error.backup_password.repeated_character"
    case backupPasswordSequence = "error.backup_password.sequence"
    case backupPasswordMismatch = "error.backup_password.mismatch"

    case attachmentIntegrity = "error.attachment.integrity"
    case attachmentTooLarge = "error.attachment.too_large"

    case relayBadRequest = "error.relay.bad_request"
    case relayUnauthorized = "error.relay.unauthorized"
    case relayAccessDenied = "error.relay.access_denied"
    case relayProofOfWorkInvalid = "error.relay.pow_invalid"
    case relayNotFound = "error.relay.not_found"
    case relayUsernameTaken = "error.relay.username_taken"
    case relayPayloadTooLarge = "error.relay.payload_too_large"
    case relayRateLimited = "error.relay.rate_limited"  // %@ = remaining time
    case relayRateLimitedGeneric = "error.relay.rate_limited_generic"
    case relayServerError = "error.relay.server"
    case relayNetwork = "error.relay.network"
    case relayUnexpectedResponse = "error.relay.unexpected_response"
    case relayConnectionReplaced = "error.relay.connection_replaced"

    case sessionIdentityChanged = "error.session.identity_changed"
    case messengerContentTooLarge = "error.messenger.content_too_large"
    case messengerInvalidContent = "error.messenger.invalid_content"

    case safetyNumberChanged = "safety_number.changed"  // %@ = contact name
    case safetyNumberVerified = "safety_number.verified"  // %@ = contact name
    case safetyNumberMismatch = "safety_number.mismatch"

    case ghostModeEnabled = "notice.ghost.enabled"  // %@ = timeout
    case ghostModeEnabledWipeContact = "notice.ghost.enabled_wipe_contact"  // %@ = timeout
    case ghostModeDisabled = "notice.ghost.disabled"
    case conversationWipedGhost = "notice.conversation.wiped_ghost"
    case conversationWipedRequest = "notice.conversation.wiped_request"
    case disappearingMessagesSet = "notice.disappearing.set"  // %@ = timer
    case disappearingMessagesOff = "notice.disappearing.off"
    case screenshotTaken = "notice.screenshot"  // %@ = contact name

    case presenceActive = "presence.active"
    case presenceActiveJustNow = "presence.active_just_now"
    case presenceActiveMinutesAgo = "presence.active_minutes_ago"  // %lld = minutes
    case presenceSetting = "presence.setting"
}

/// Resolves kit strings in English or Turkish. ``current`` follows the device
/// language; anything other than Turkish falls back to English.
public struct Localizer: Sendable {
    public static let supportedLanguages = ["en", "tr"]
    static let developmentLanguage = "en"

    public let languageCode: String

    public init(languageCode: String) {
        self.languageCode =
            Self.supportedLanguages.contains(languageCode) ? languageCode : Self.developmentLanguage
    }

    public static var current: Localizer {
        let preferred = Bundle.preferredLocalizations(
            from: supportedLanguages, forPreferences: Locale.preferredLanguages)
        return Localizer(languageCode: preferred.first ?? developmentLanguage)
    }

    public var locale: Locale { Locale(identifier: languageCode) }

    /// The localized string, or the English one if this language lacks it.
    public func string(_ key: LocalizationKey) -> String {
        if let value = Self.lookup(key.rawValue, language: languageCode) { return value }
        return Self.lookup(key.rawValue, language: Self.developmentLanguage) ?? key.rawValue
    }

    /// A localized format string (`%@` placeholders) filled with `arguments`.
    public func string(_ key: LocalizationKey, _ arguments: CVarArg...) -> String {
        String(format: string(key), locale: locale, arguments: arguments)
    }

    /// "1 hour" / "1 saat", for timers and retry delays.
    public func duration(seconds: Int) -> String {
        let formatter = DateComponentsFormatter()
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        formatter.calendar = calendar
        formatter.unitsStyle = .full
        formatter.allowedUnits = [.day, .hour, .minute, .second]
        formatter.maximumUnitCount = 2
        return formatter.string(from: TimeInterval(max(0, seconds))) ?? "\(seconds)s"
    }

    /// Returns `nil` when the language has no entry, so callers can fall back.
    static func lookup(_ key: String, language: String) -> String? {
        guard let bundle = languageBundles[language] else { return nil }
        let missing = "\u{0}"
        let value = bundle.localizedString(forKey: key, value: missing, table: nil)
        return value == missing ? nil : value
    }

    private static let languageBundles: [String: Bundle] = {
        var bundles: [String: Bundle] = [:]
        for language in supportedLanguages {
            if let path = Bundle.module.path(forResource: language, ofType: "lproj"),
                let bundle = Bundle(path: path)
            {
                bundles[language] = bundle
            }
        }
        return bundles
    }()
}

extension Localizer {
    /// System notice for a received or sent `ConversationSettings` change
    /// (PROTOCOL.md §8.1–§8.2). Returns one line per changed aspect.
    public func notices(for settings: BRPConversationSettings, previous: BRPConversationSettings?) -> [String] {
        var lines: [String] = []
        let ghostChanged = previous.map { $0.ghost != settings.ghost } ?? settings.hasGhost
        if ghostChanged {
            if settings.ghost.enabled {
                let timeout = duration(seconds: Int(settings.ghost.timeoutSeconds))
                lines.append(
                    settings.ghost.wipeContact
                        ? string(.ghostModeEnabledWipeContact, timeout) : string(.ghostModeEnabled, timeout))
            } else if previous?.ghost.enabled == true {
                lines.append(string(.ghostModeDisabled))
            }
        }
        let timer = settings.defaultExpireTimerSeconds
        if timer != (previous?.defaultExpireTimerSeconds ?? 0) {
            lines.append(
                timer > 0
                    ? string(.disappearingMessagesSet, duration(seconds: Int(timer)))
                    : string(.disappearingMessagesOff))
        }
        return lines
    }

    /// The unlock screen's sentence after a wrong code (PROTOCOL.md §5.2),
    /// e.g. "Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir."
    /// Pass ``LocalVault/status()`` read right after the failed attempt.
    public func wrongCodeNotice(_ status: UnlockStatus) -> String {
        guard let upcoming = status.upcoming else { return string(.vaultWrongCode) }
        let wait = duration(seconds: Int(upcoming.delay.rounded(.up)))
        return upcoming.attemptsLeft == 1
            ? string(.vaultWrongCodeWarningOne, wait)
            : string(.vaultWrongCodeWarning, upcoming.attemptsLeft, wait)
    }

    /// System notice shown after a `ConversationWipe` was executed.
    public func notice(for wipe: BRPConversationWipe) -> String {
        wipe.reason == .ghostTimeout ? string(.conversationWipedGhost) : string(.conversationWipedRequest)
    }
}
