import Foundation
import Us2usKit

// 5.1 › mic (proposed: the design draws the voice bubble and the mic, not
// the recording): what a voice message is, the rules of recording one, the
// mic's press-and-hold and the player's speeds. Plain values and pure
// functions — the recorder, the player and the views build on them, unit
// tests drive them directly. Nothing in this file touches the disk.

/// A voice message recorded on this phone, held in memory only: the AAC
/// (M4A) bytes, its length and the waveform it is sent with.
struct VoiceRecording: Hashable, Sendable {
    /// AAC in an M4A container (brp.proto `Attachment`, kind VOICE).
    let audio: Data
    let duration: TimeInterval
    /// Up to 100 amplitude samples, 0…255 (brp.proto `Attachment.waveform`).
    let waveform: Data

    static let contentType = "audio/mp4"

    /// `Attachment.duration_ms`.
    var durationMilliseconds: UInt32 {
        UInt32(clamping: Int((max(0, duration) * 1000).rounded()))
    }
}

/// The rules of a voice message (modelled on WhatsApp / iMessage).
enum VoiceNoteRules {
    /// Shorter recordings are thrown away (the hold hint shows instead).
    static let minimumDuration: TimeInterval = 1
    /// Recording stops by itself here; the recording then waits to be sent
    /// (held: on release; locked: with “Gönder”).
    static let maximumDuration: TimeInterval = 5 * 60
    /// The mic must be held this long before recording starts: a tap only
    /// shows the hint and never opens the microphone.
    static let holdDelay: Duration = .milliseconds(200)
    /// Sliding the held mic this far left cancels (points).
    static let cancelDistance: CGFloat = 110
    /// Sliding the held mic this far up locks the recording (hands-free).
    static let lockDistance: CGFloat = 80
    /// A received voice message larger than this is not played (five
    /// minutes at 64 kbit/s are about 2.4 MB): decrypting it into memory
    /// would only exhaust it.
    static let maximumPlayableBytes: UInt64 = 16 * 1024 * 1024

    static func isLongEnough(_ duration: TimeInterval) -> Bool {
        duration >= minimumDuration
    }

    static func hasReachedMaximum(_ elapsed: TimeInterval) -> Bool {
        elapsed >= maximumDuration
    }
}

/// The waveform of a voice message: input levels (0…1) while recording, up
/// to 100 samples (0…255) on the wire, bars (0…1) in the bubble.
enum VoiceWaveform {
    /// brp.proto: “up to 100 amplitude samples”.
    static let maxSamples = ContentLimits.maxWaveformSamples
    /// The quietest level drawn (dBFS); anything below is the floor.
    static let floorDecibels: Float = -50

    /// A meter reading (dBFS, 0 = full scale) as a level 0…1, linear in
    /// decibels above the floor.
    static func level(decibels: Float) -> Double {
        guard decibels.isFinite else { return 0 }
        let clamped = min(0, max(floorDecibels, decibels))
        return Double((clamped - floorDecibels) / -floorDecibels)
    }

    /// Levels (0…1) evenly downsampled to at most `maxCount` samples — the
    /// mean of each of the equal stretches — as bytes 0…255. Fewer levels
    /// than `maxCount` are kept one for one.
    static func samples(_ levels: [Double], maxCount: Int = maxSamples) -> [UInt8] {
        guard !levels.isEmpty, maxCount > 0 else { return [] }
        return resample(levels, count: min(maxCount, levels.count)).map { level in
            UInt8((min(1, max(0, level)) * 255).rounded())
        }
    }

    /// `samples` as the attachment's `waveform` bytes.
    static func data(_ levels: [Double]) -> Data {
        Data(samples(levels))
    }

    /// `values` as `count` values: the mean of each of `count` equal
    /// stretches (with fewer values than `count`, each value repeats over
    /// its share).
    static func resample(_ values: [Double], count: Int) -> [Double] {
        guard !values.isEmpty, count > 0 else { return [] }
        let total = values.count
        return (0..<count).map { index in
            let start = index * total / count
            let end = min(total, max(start + 1, (index + 1) * total / count))
            let stretch = values[start..<end]
            return stretch.reduce(0, +) / Double(stretch.count)
        }
    }

    /// The bubble's bars (heights 0…1) from the samples a message carries:
    /// never lower than a sliver; without samples an even, quiet line.
    static func bars(_ data: Data?, count: Int) -> [Double] {
        guard count > 0 else { return [] }
        guard let data, !data.isEmpty else { return Array(repeating: 0.3, count: count) }
        return resample(data.map { Double($0) / 255 }, count: count).map { max(0.08, $0) }
    }
}

/// The mic's press-and-hold (5.1, proposed): press → (after the hold
/// delay) recording while held → release sends; slide left far enough to
/// cancel, up far enough to lock (recording goes on hands-free, with
/// delete · stop · send). A release before recording started, or of a
/// recording shorter than the minimum, shows the hint instead.
struct VoiceHoldGesture: Equatable {
    enum Phase: Equatable {
        /// Nothing pressed.
        case idle
        /// The finger is down, recording has not started yet.
        case pressed
        /// Recording while the finger stays down.
        case holding
        /// Slid up past the lock: the finger is free.
        case locked
    }

    enum Event: Equatable {
        /// The hold delay passed: start recording.
        case startRecording
        /// Slid up far enough: recording goes on hands-free.
        case lock
        /// Released (or “Gönder” when locked): send what was recorded.
        case send
        /// Slid left far enough (or “Sil” when locked): throw it away.
        case cancel
        /// Released too early: nothing to send, show “Kaydetmek için basılı tut”.
        case hint
    }

    private(set) var phase: Phase
    /// How far the finger slid since the press, leftwards and upwards only
    /// (both ≤ 0), while holding.
    private(set) var translation: CGSize

    init(phase: Phase = .idle, translation: CGSize = .zero) {
        self.phase = phase
        self.translation = translation
    }

    /// 0…1 towards cancelling (the “İptal için kaydır” label fades with it).
    var cancelProgress: Double {
        guard phase == .holding else { return 0 }
        return Double(min(1, max(0, -translation.width / VoiceNoteRules.cancelDistance)))
    }

    /// 0…1 towards locking (the lock above the mic follows it).
    var lockProgress: Double {
        guard phase == .holding else { return 0 }
        return Double(min(1, max(0, -translation.height / VoiceNoteRules.lockDistance)))
    }

    /// The finger went down. `false` if something is already going on.
    @discardableResult
    mutating func press() -> Bool {
        guard phase == .idle else { return false }
        phase = .pressed
        translation = .zero
        return true
    }

    /// The finger stayed down for the hold delay.
    mutating func holdDelayPassed() -> Event? {
        guard phase == .pressed else { return nil }
        phase = .holding
        return .startRecording
    }

    /// The finger moved; `translation` from where it went down.
    mutating func slide(_ moved: CGSize) -> Event? {
        guard phase == .holding else { return nil }
        translation = CGSize(width: min(0, moved.width), height: min(0, moved.height))
        if -translation.width >= VoiceNoteRules.cancelDistance {
            phase = .idle
            translation = .zero
            return .cancel
        }
        if -translation.height >= VoiceNoteRules.lockDistance {
            phase = .locked
            translation = .zero
            return .lock
        }
        return nil
    }

    /// The finger lifted, after `recorded` seconds of recording.
    mutating func release(recorded: TimeInterval) -> Event? {
        switch phase {
        case .pressed:
            phase = .idle
            return .hint
        case .holding:
            phase = .idle
            translation = .zero
            return VoiceNoteRules.isLongEnough(recorded) ? .send : .hint
        case .idle, .locked:
            return nil
        }
    }

    /// “Gönder” while locked.
    mutating func sendLocked(recorded: TimeInterval) -> Event? {
        guard phase == .locked else { return nil }
        phase = .idle
        return VoiceNoteRules.isLongEnough(recorded) ? .send : .hint
    }

    /// “Sil” while locked.
    mutating func deleteLocked() -> Event? {
        guard phase == .locked else { return nil }
        phase = .idle
        return .cancel
    }

    /// Back to the start, whatever happened (a cancelled touch, an
    /// interruption, the app leaving the foreground).
    mutating func reset() {
        phase = .idle
        translation = .zero
    }
}

/// The voice player's speeds, cycled by the chip under the play button.
enum VoicePlaybackRate: CaseIterable, Sendable {
    case normal, oneAndHalf, double

    var value: Float {
        switch self {
        case .normal: 1
        case .oneAndHalf: 1.5
        case .double: 2
        }
    }

    var next: VoicePlaybackRate {
        switch self {
        case .normal: .oneAndHalf
        case .oneAndHalf: .double
        case .double: .normal
        }
    }

    /// “1×”, “1,5×” (Turkish) / “1.5×” (English), “2×”.
    func label(locale: Locale) -> String {
        switch self {
        case .normal:
            return "1×"
        case .double:
            return "2×"
        case .oneAndHalf:
            let separator = locale.language.languageCode == .english ? "." : (locale.decimalSeparator ?? ",")
            return "1\(separator)5×"
        }
    }
}
