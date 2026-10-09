import Foundation

/// Formatting rules of the design. Every function takes the locale explicitly
/// (the in-app language), never `Locale.current`.
enum U2Format {
    // MARK: Chat list times (4.1)

    /// Today `HH:mm`; yesterday “Dün” / “Yesterday”; this year `d MMM`
    /// (“24 Eyl” / “Sep 24”); older `d.MM.yy`.
    static func chatListTime(_ date: Date, now: Date, locale: Locale, calendar: Calendar = .current) -> String {
        var calendar = calendar
        calendar.locale = locale
        if calendar.isDate(date, inSameDayAs: now) {
            return clockTime(date, locale: locale, calendar: calendar)
        }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: yesterday) {
            return L10n.string("time.yesterday", locale: locale)
        }
        if calendar.component(.year, from: date) == calendar.component(.year, from: now) {
            return dayMonth(date, locale: locale, calendar: calendar)
        }
        return DateFormatters.shared.formatter(.fixed("d.MM.yy"), locale: locale, calendar: calendar).string(from: date)
    }

    /// `d MMM`: “24 Eyl” / “Sep 24”, “8 Eki” / “Oct 8”.
    static func dayMonth(_ date: Date, locale: Locale, calendar: Calendar = .current) -> String {
        let formatter = DateFormatters.shared.formatter(.template("dMMM"), locale: locale, calendar: calendar)
        // Turkish abbreviations come with a trailing dot in some ICU versions
        // (“Eyl.”); the design writes “24 Eyl”.
        return formatter.string(from: date).replacingOccurrences(of: ".", with: "")
    }

    /// `HH:mm` (the design uses a 24-hour clock in both languages).
    static func clockTime(_ date: Date, locale: Locale, calendar: Calendar = .current) -> String {
        DateFormatters.shared.formatter(.fixed("HH:mm"), locale: locale, calendar: calendar).string(from: date)
    }

    // MARK: Ghost countdowns (C17)

    /// The list-pill form: ≥ 1 day “N gün / N days”, ≥ 1 hour “N sa / N h”,
    /// otherwise “N dk / N min”.
    ///
    /// Days and hours are floored, minutes rounded up with a minimum of 1 —
    /// this reproduces every pair the design shows: 23:41:07 → “23 sa”,
    /// 00:42:10 → “43 dk”.
    static func ghostShort(_ remaining: TimeInterval, locale: Locale) -> String {
        let seconds = max(0, remaining)
        if seconds >= 86_400 {
            let days = Int(seconds / 86_400)
            return L10n.string("ghost.short.days \(days)", locale: locale)
        }
        if seconds >= 3_600 {
            let hours = Int(seconds / 3_600)
            return L10n.string("ghost.short.hours \(hours)", locale: locale)
        }
        let minutes = max(1, Int((seconds / 60).rounded(.up)))
        return L10n.string("ghost.short.minutes \(minutes)", locale: locale)
    }

    /// The live form `HH:MM:SS` (hours are not wrapped at 24).
    static func ghostLong(_ remaining: TimeInterval) -> String {
        let total = max(0, Int(remaining.rounded(.down)))
        return String(format: "%02d:%02d:%02d", total / 3_600, (total % 3_600) / 60, total % 60)
    }

    /// Ghost turns urgent in its last hour (C17).
    static func ghostIsUrgent(_ remaining: TimeInterval) -> Bool {
        remaining < 3_600
    }

    // MARK: Countdowns and sizes

    /// `mm:ss` for lock-out waits (3.2, proposed).
    static func minutesSeconds(_ remaining: TimeInterval) -> String {
        let total = max(0, Int(remaining.rounded(.up)))
        return String(format: "%02d:%02d", total / 60, total % 60)
    }

    /// Voice-message duration `m:ss` (“0:42”).
    static func voiceDuration(_ duration: TimeInterval) -> String {
        let total = max(0, Int(duration.rounded()))
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    /// File sizes with the language's decimal separator: TR “2,1 MB”, EN “2.1 MB”.
    static func byteCount(_ bytes: Int64, locale: Locale) -> String {
        bytes.formatted(.byteCount(style: .file).locale(numberLocale(locale)))
    }

    /// Grouped integers: TR “3.920”, EN “3,920”.
    static func integer(_ value: Int, locale: Locale) -> String {
        value.formatted(.number.locale(numberLocale(locale)))
    }

    /// Sizes and counts follow the UI language, as the design's English
    /// writes them (“3,920”, “12.4 MB”) — not the device region (an English
    /// UI on a Turkish-region device would otherwise read “3.920”). Times
    /// are formatted elsewhere and keep the 24-hour clock.
    private static func numberLocale(_ locale: Locale) -> Locale {
        locale.language.languageCode == .english ? Locale(identifier: "en_US") : locale
    }
}

/// The date formatters behind `U2Format`, made once per pattern, language,
/// calendar and time zone. Making a `DateFormatter` (above all one from a
/// template) costs far more than formatting with it, and the chat list
/// formats a time for every row that scrolls in. `DateFormatter` is
/// thread-safe for formatting; the cache itself is behind a lock.
private final class DateFormatters: @unchecked Sendable {
    static let shared = DateFormatters()

    enum Pattern: Hashable {
        /// A fixed `dateFormat` (“HH:mm”).
        case fixed(String)
        /// A localized template (“dMMM” → “d MMM” / “MMM d”).
        case template(String)
    }

    private struct Key: Hashable {
        let pattern: Pattern
        let locale: String
        let calendar: Calendar.Identifier
        let calendarLocale: String?
        let timeZone: String
        let firstWeekday: Int
    }

    private let lock = NSLock()
    private var formatters: [Key: DateFormatter] = [:]

    func formatter(_ pattern: Pattern, locale: Locale, calendar: Calendar) -> DateFormatter {
        let key = Key(
            pattern: pattern, locale: locale.identifier, calendar: calendar.identifier,
            calendarLocale: calendar.locale?.identifier, timeZone: calendar.timeZone.identifier,
            firstWeekday: calendar.firstWeekday)
        return lock.withLock {
            if let formatter = formatters[key] { return formatter }
            let formatter = DateFormatter()
            formatter.locale = locale
            formatter.calendar = calendar
            formatter.timeZone = calendar.timeZone
            switch pattern {
            case .fixed(let format): formatter.dateFormat = format
            case .template(let template): formatter.setLocalizedDateFormatFromTemplate(template)
            }
            // A handful of patterns × languages; bounded anyway.
            if formatters.count > 64 { formatters.removeAll() }
            formatters[key] = formatter
            return formatter
        }
    }
}
