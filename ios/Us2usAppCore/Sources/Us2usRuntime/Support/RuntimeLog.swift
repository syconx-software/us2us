import os

/// The runtime's only log sink.
///
/// Privacy rules (enforced by `PrivacyLintTests`):
///
/// * Messages are fixed event names ("connected", "unlock failed"). Message
///   content, account ids, usernames, relay ids, keys, codes, file names and
///   URLs are never logged, not even with `.private`.
/// * Every interpolation states its privacy explicitly. Only a
///   ``LogLabel`` (a fixed, non-identifying category such as `"network"`) may
///   be `.public`; anything else is `.private`, which the system redacts in
///   every log that leaves the device.
/// * No `print`, `NSLog`, `debugPrint`, `dump` or `os_log` anywhere in the
///   module, and no other `Logger` than these.
/// * `debug` and `info` are not persisted by the system; `error` is used only
///   for failures the user may report, again without identifiers.
enum RuntimeLog {
    static let subsystem = "com.syconx.us2us"

    static let vault = Logger(subsystem: subsystem, category: "vault")
    static let relay = Logger(subsystem: subsystem, category: "relay")
    static let messaging = Logger(subsystem: subsystem, category: "messaging")
    static let privacy = Logger(subsystem: subsystem, category: "privacy")
    static let lifecycle = Logger(subsystem: subsystem, category: "lifecycle")
}

/// A value that may appear in a log line in clear: a fixed category that
/// says nothing about who or what (`"network"`, `"rateLimited"`).
protocol LogLabel {
    var logLabel: String { get }
}

extension Error {
    /// A non-identifying category of the error, safe for `.public` logging.
    var logLabel: String {
        switch self {
        case is CancellationError: return "cancelled"
        default: return String(describing: type(of: self))
        }
    }
}
