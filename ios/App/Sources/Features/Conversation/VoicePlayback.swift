import AVFoundation
import SwiftUI

/// The one voice message that plays in the open conversation (5.1 voice
/// bubble, proposed beyond the design's paused state: play / pause, the
/// progress over the waveform, drag to scrub, 1× · 1,5× · 2×).
///
/// * Its bytes come decrypted into memory (`MessengerService.mediaPayload`,
///   downloaded first if they are still on the relay) and play with
///   `AVAudioPlayer(data:)` — never from a file.
/// * One at a time: playing another message, recording or opening other
///   media stops it; so do closing the chat, the app leaving the foreground
///   and its message going (`ConversationViewModel.stopVoice`,
///   `playingVoiceIsGone`). Where a message was left stays for this visit
///   only, in memory.
/// * The audio session switches to spoken-audio playback (sound with the
///   silent switch, like any voice message) only while it plays. Paused or
///   finished, it goes back to what it was two seconds later — so another
///   app's music does not come back between two voice messages or for a
///   short pause — and at once when playback stops; an interruption (a
///   call) or a lost route (headphones unplugged) pauses.
/// * `stop()` drops the player and the bytes at once.
/// * Bytes that cannot be played (or a session that cannot be had) stop
///   it with `onPlaybackFailure`, which the conversation turns into a hint.
@Observable
@MainActor
final class VoicePlayback {
    /// The message playing, paused, or being loaded.
    private(set) var activeID: MessageID?
    private(set) var isPlaying = false
    /// The active message's bytes are being downloaded or decrypted.
    private(set) var isLoading = false
    /// Where the active message is, 0…1.
    private(set) var progress: Double = 0
    /// The active message's length in seconds, once it is loaded.
    private(set) var duration: TimeInterval = 0
    private(set) var rate: VoicePlaybackRate = .normal
    /// Where messages played earlier in this visit were left, 0…1.
    private(set) var positions: [MessageID: Double] = [:]
    private(set) var isScrubbing = false

    /// The active message could not be played.
    @ObservationIgnored var onPlaybackFailure: (() -> Void)?

    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private let delegate = VoicePlayerDelegate()
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var notificationTokens: [any NSObjectProtocol] = []
    /// This player switched the audio session to playback.
    @ObservationIgnored private var holdsAudio = false
    /// Paused or finished: lets the session go shortly (`releaseAudioSoon`).
    @ObservationIgnored private var idleRelease: Task<Void, Never>?
    @ObservationIgnored private var startFraction: Double = 0
    @ObservationIgnored private var resumesAfterScrub = false

    /// Cheap: the conversation's view model creates one up front.
    init() {
        delegate.onFinish = { [weak self] in self?.didFinish() }
        delegate.onFailure = { [weak self] in self?.fail() }
    }

    func isActive(_ id: MessageID) -> Bool { activeID == id }

    /// What the bubble of `id` shows as played: the live position while it
    /// is the active message, where it was left in this visit, or `fallback`.
    func shownFraction(of id: MessageID, fallback: Double) -> Double {
        activeID == id ? progress : positions[id] ?? fallback
    }

    /// The active message's position in seconds.
    var currentTime: TimeInterval { duration * progress }

    // MARK: Playing

    /// `id` becomes the active message while its bytes are on their way
    /// (`play(_:for:)` follows). What played before stops. It starts where
    /// it was left in this visit, else at `fallback` (0…1).
    func begin(_ id: MessageID, startFraction fallback: Double = 0) {
        // The session stays a moment for the next message's bytes.
        halt(keepingAudio: true)
        activeID = id
        isLoading = true
        let start = positions[id] ?? fallback
        startFraction = start < 0.999 ? max(0, start) : 0
        progress = startFraction
        duration = 0
    }

    /// Plays the bytes of the active message `id`. `false` — and nothing
    /// plays — when another message became active meanwhile, or the bytes
    /// cannot be played.
    @discardableResult
    func play(_ payload: MediaPayload?, for id: MessageID) -> Bool {
        guard activeID == id, isLoading else { return false }
        isLoading = false
        guard let payload, !payload.data.isEmpty,
            let player = try? AVAudioPlayer(data: payload.data, fileTypeHint: Self.fileTypeHint(payload.contentType))
        else {
            fail()
            return false
        }
        player.delegate = delegate
        player.enableRate = true
        player.rate = rate.value
        _ = player.prepareToPlay()
        if startFraction > 0, player.duration > 0 { player.currentTime = startFraction * player.duration }
        guard activateAudio() else {
            fail()
            return false
        }
        guard player.play() else {
            fail()
            return false
        }
        self.player = player
        duration = player.duration
        progress = fraction(of: player)
        isPlaying = true
        observeSession()
        startTicking()
        return true
    }

    /// The active message: pauses it while it plays, plays it on while paused.
    func togglePause() {
        guard let player else { return }
        if player.isPlaying {
            pause()
        } else {
            resume()
        }
    }

    func pause() {
        guard let player else { return }
        player.pause()
        isPlaying = false
        progress = fraction(of: player)
        stopTicking()
        releaseAudioSoon()
    }

    private func resume() {
        guard let player, activateAudio() else { return }
        if fraction(of: player) >= 0.999 { player.currentTime = 0 }
        guard player.play() else { return }
        isPlaying = true
        startTicking()
    }

    /// 1× → 1,5× → 2× → 1× (kept for the next messages of this visit).
    func cycleRate() {
        rate = rate.next
        player?.rate = rate.value
    }

    // MARK: Seeking

    func beginScrub(_ id: MessageID) {
        guard activeID == id, let player, !isScrubbing else { return }
        isScrubbing = true
        resumesAfterScrub = player.isPlaying
        player.pause()
        stopTicking()
    }

    func scrub(_ id: MessageID, to fraction: Double) {
        guard activeID == id, let player, player.duration > 0 else { return }
        let clamped = min(1, max(0, fraction))
        progress = clamped
        player.currentTime = clamped * player.duration
    }

    func endScrub(_ id: MessageID) {
        guard activeID == id, isScrubbing else { return }
        isScrubbing = false
        let resumes = resumesAfterScrub
        resumesAfterScrub = false
        if progress >= 0.999 {
            // Let go at the very end: the message is finished, not started over.
            didFinish()
        } else if resumes {
            resume()
        } else {
            pause()
        }
    }

    /// Jumps by `seconds` (VoiceOver's adjustable waveform).
    func skip(_ id: MessageID, by seconds: TimeInterval) {
        guard activeID == id, let player, player.duration > 0 else { return }
        player.currentTime = min(player.duration, max(0, player.currentTime + seconds))
        progress = fraction(of: player)
    }

    // MARK: Stopping

    /// Stops whatever plays or loads and lets go of its bytes and of the
    /// audio session; where the message was left is kept for this visit.
    func stop() {
        halt(keepingAudio: false)
    }

    /// `id` stops if it is the active one, and where it was left is
    /// forgotten (the composer's review, once its recording is sent or
    /// deleted: the next recording is listened to from its start).
    func forget(_ id: MessageID) {
        if activeID == id { stop() }
        positions[id] = nil
    }

    private func halt(keepingAudio: Bool) {
        if let id = activeID, let player {
            let left = fraction(of: player)
            positions[id] = left > 0 && left < 0.999 ? left : nil
        }
        releasePlayer(keepingAudio: keepingAudio)
        activeID = nil
        isPlaying = false
        isLoading = false
        isScrubbing = false
        resumesAfterScrub = false
        progress = 0
        duration = 0
    }

    /// `stop()`, and the remembered positions go too (the chat closed).
    func reset() {
        stop()
        positions = [:]
    }

    private func didFinish() {
        if let id = activeID { positions[id] = nil }
        releasePlayer(keepingAudio: true)
        activeID = nil
        isPlaying = false
        progress = 0
        duration = 0
    }

    private func fail() {
        releasePlayer()
        activeID = nil
        isPlaying = false
        isLoading = false
        isScrubbing = false
        progress = 0
        duration = 0
        U2Haptics.error()
        onPlaybackFailure?()
    }

    /// Drops the player; the audio session goes at once, or — between two
    /// messages, or once one finished — shortly after.
    private func releasePlayer(keepingAudio: Bool = false) {
        stopTicking()
        let center = NotificationCenter.default
        notificationTokens.forEach { center.removeObserver($0) }
        notificationTokens = []
        player?.delegate = nil
        player?.stop()
        player = nil
        if keepingAudio {
            releaseAudioSoon()
        } else {
            releaseAudio()
        }
    }

    // MARK: Progress

    private func startTicking() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(50))
                guard !Task.isCancelled, let self else { return }
                self.tick()
            }
        }
    }

    private func stopTicking() {
        ticker?.cancel()
        ticker = nil
    }

    private func tick() {
        guard let player, !isScrubbing else { return }
        progress = fraction(of: player)
    }

    private func fraction(of player: AVAudioPlayer) -> Double {
        player.duration > 0 ? min(1, max(0, player.currentTime / player.duration)) : 0
    }

    // MARK: Audio session

    private func activateAudio() -> Bool {
        idleRelease?.cancel()
        idleRelease = nil
        if holdsAudio { return true }
        guard PlaybackAudioSession.activate(for: .playback) else { return false }
        holdsAudio = true
        return true
    }

    /// Lets go of the audio session now.
    private func releaseAudio() {
        idleRelease?.cancel()
        idleRelease = nil
        guard holdsAudio else { return }
        holdsAudio = false
        PlaybackAudioSession.release()
    }

    /// Paused, finished, or between two messages: the session goes in two
    /// seconds unless something plays again before.
    private func releaseAudioSoon() {
        guard holdsAudio, idleRelease == nil else { return }
        idleRelease = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled, let self else { return }
            self.idleRelease = nil
            self.releaseAudio()
        }
    }

    private func observeSession() {
        let center = NotificationCenter.default
        notificationTokens = [
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) {
                @Sendable [weak self] notification in
                let began = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                    == AVAudioSession.InterruptionType.began.rawValue
                MainActor.assumeIsolated { if began { self?.pause() } }
            },
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) {
                @Sendable [weak self] notification in
                let lost = (notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                    == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue
                MainActor.assumeIsolated { if lost { self?.pause() } }
            },
        ]
    }

    /// The player's hint for the bytes' container (it can also tell by itself).
    private static func fileTypeHint(_ contentType: String) -> String? {
        let type = contentType.lowercased()
        if type.hasPrefix("audio/mp4") || type.hasPrefix("audio/m4a") || type.hasPrefix("audio/x-m4a") {
            return AVFileType.m4a.rawValue
        }
        if type.hasPrefix("audio/wav") || type.hasPrefix("audio/x-wav") || type.hasPrefix("audio/wave") {
            return AVFileType.wav.rawValue
        }
        return nil
    }
}

/// The player's end and decode errors (delivered on the main thread).
@MainActor
private final class VoicePlayerDelegate: NSObject, AVAudioPlayerDelegate {
    var onFinish: (() -> Void)?
    var onFailure: (() -> Void)?

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        if flag {
            onFinish?()
        } else {
            onFailure?()
        }
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: (any Error)?) {
        onFailure?()
    }
}
