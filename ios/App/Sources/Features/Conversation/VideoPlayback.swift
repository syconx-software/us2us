import AVFoundation
import SwiftUI
import UIKit

/// The in-app player's state: one decrypted clip played from memory
/// (`MemoryAssetLoader`), play / pause, seeking, mute, the audio session.
///
/// * No AirPlay, no picture in picture: the decrypted video stays on this
///   phone and on this screen.
/// * The audio session switches to playback (sound with the silent switch,
///   like any video) only once something plays, and goes back to what it was
///   when the player closes; an interruption or a lost route (headphones
///   unplugged) pauses.
/// * `close()` drops the player, the item and the bytes at once.
@Observable
@MainActor
final class VideoPlaybackModel {
    enum Phase: Equatable {
        /// Downloading, decrypting or preparing.
        case loading
        case ready
        /// Played to the end: the centre button plays again.
        case ended
        case failed
    }

    private(set) var phase: Phase = .loading
    private(set) var isPlaying = false
    private(set) var isMuted = false
    /// Seconds.
    private(set) var currentTime: Double = 0
    private(set) var duration: Double
    /// Seconds loaded ahead (from memory: soon the whole clip).
    private(set) var buffered: Double = 0
    /// The first frame is on screen (the poster can go).
    var isShowingVideo = false
    private(set) var isScrubbing = false
    @ObservationIgnored private(set) var player: AVPlayer?

    @ObservationIgnored private var loader: MemoryAssetLoader?
    @ObservationIgnored private var timeObserver: Any?
    @ObservationIgnored private var observations: [NSKeyValueObservation] = []
    @ObservationIgnored private var notificationTokens: [any NSObjectProtocol] = []
    /// This player switched the audio session to playback.
    @ObservationIgnored private var holdsAudio = false
    /// Closed: a payload that arrives late starts nothing.
    @ObservationIgnored private var isClosed = false
    @ObservationIgnored private var resumesAfterScrub = false
    @ObservationIgnored private var chaseTime: CMTime = .zero
    @ObservationIgnored private var isSeeking = false
    @ObservationIgnored private var pendingStart: (time: Double, autoplay: Bool)?

    /// `duration`: the length the message announced (shown while loading).
    init(duration: Double) {
        self.duration = duration
    }

    var fraction: Double { duration > 0 ? min(1, max(0, currentTime / duration)) : 0 }
    var bufferedFraction: Double { duration > 0 ? min(1, max(0, buffered / duration)) : 0 }

    // MARK: Loading

    /// Starts playing `payload` (or fails without one), at `startTime`.
    func start(_ payload: MediaPayload?, startTime: Double? = nil, autoplay: Bool = true) {
        guard !isClosed, player == nil, phase == .loading else { return }
        guard let payload, !payload.data.isEmpty else {
            phase = .failed
            return
        }
        let loader = MemoryAssetLoader(data: payload.data, contentType: payload.contentType)
        self.loader = loader
        let item = AVPlayerItem(asset: loader.makeAsset())
        let player = AVPlayer(playerItem: item)
        player.allowsExternalPlayback = false
        player.isMuted = isMuted
        self.player = player
        pendingStart = (startTime ?? 0, autoplay)
        observe(item, player: player)
    }

    private func observe(_ item: AVPlayerItem, player: AVPlayer) {
        observations = [
            item.observe(\.status, options: [.initial, .new]) { @Sendable [weak self] _, _ in
                Task { @MainActor in self?.itemStatusChanged() }
            },
            player.observe(\.timeControlStatus, options: [.initial, .new]) { @Sendable [weak self] _, _ in
                Task { @MainActor in self?.refreshPlaying() }
            },
            item.observe(\.loadedTimeRanges, options: [.new]) { @Sendable [weak self] _, _ in
                Task { @MainActor in self?.refreshBuffered() }
            },
        ]
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(value: 1, timescale: 30), queue: .main
        ) { @Sendable [weak self] time in
            let seconds = time.seconds
            MainActor.assumeIsolated { self?.tick(seconds) }
        }
        let center = NotificationCenter.default
        notificationTokens = [
            center.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) {
                @Sendable [weak self] _ in
                MainActor.assumeIsolated { self?.didPlayToEnd() }
            },
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

    private func itemStatusChanged() {
        guard let item = player?.currentItem else { return }
        switch item.status {
        case .readyToPlay:
            guard phase == .loading else { return }
            let seconds = item.duration.seconds
            if seconds.isFinite, seconds > 0 { duration = seconds }
            phase = .ready
            refreshBuffered()
            if let start = pendingStart {
                pendingStart = nil
                if start.time > 0 {
                    currentTime = min(start.time, duration)
                    player?.seek(
                        to: CMTime(seconds: currentTime, preferredTimescale: 600), toleranceBefore: .zero,
                        toleranceAfter: .zero)
                }
                if start.autoplay { play() }
            }
        case .failed:
            phase = .failed
            releasePlayer()
        default:
            break
        }
    }

    private func refreshPlaying() {
        isPlaying = player?.timeControlStatus != .paused && player != nil
    }

    private func refreshBuffered() {
        guard let item = player?.currentItem else { return }
        let ranges = item.loadedTimeRanges.map(\.timeRangeValue)
        buffered = ranges.map { ($0.start + $0.duration).seconds }.filter(\.isFinite).max() ?? buffered
    }

    private func tick(_ seconds: Double) {
        guard !isScrubbing, seconds.isFinite else { return }
        currentTime = max(0, min(seconds, duration))
    }

    private func didPlayToEnd() {
        currentTime = duration
        phase = .ended
        refreshPlaying()
    }

    // MARK: Controls

    func togglePlay() {
        isPlaying ? pause() : play()
    }

    func play() {
        guard let player, phase == .ready || phase == .ended else { return }
        if phase == .ended {
            phase = .ready
            currentTime = 0
            player.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero)
        }
        activateAudio()
        player.play()
        isPlaying = true
    }

    func pause() {
        player?.pause()
        isPlaying = false
    }

    func toggleMute() {
        isMuted.toggle()
        player?.isMuted = isMuted
    }

    /// Jumps by `seconds` (VoiceOver's adjustable scrubber).
    func skip(by seconds: Double) {
        guard phase == .ready || phase == .ended else { return }
        if phase == .ended, seconds < 0 { phase = .ready }
        let target = max(0, min(duration, currentTime + seconds))
        currentTime = target
        seek(to: target)
    }

    // MARK: Scrubbing

    func beginScrub() {
        guard !isScrubbing, phase == .ready || phase == .ended else { return }
        isScrubbing = true
        resumesAfterScrub = isPlaying
        player?.pause()
        if phase == .ended { phase = .ready }
    }

    func scrub(to fraction: Double) {
        guard isScrubbing else { return }
        currentTime = max(0, min(1, fraction)) * duration
        seek(to: currentTime)
    }

    func endScrub() {
        guard isScrubbing else { return }
        isScrubbing = false
        if resumesAfterScrub { play() } else { refreshPlaying() }
        resumesAfterScrub = false
    }

    /// Seeks exactly, one seek at a time: while one runs, only the latest
    /// target is kept (a drag never queues up stale seeks).
    private func seek(to seconds: Double) {
        chaseTime = CMTime(seconds: seconds, preferredTimescale: 600)
        guard !isSeeking else { return }
        seekToChaseTime()
    }

    private func seekToChaseTime() {
        guard let player else {
            isSeeking = false
            return
        }
        isSeeking = true
        let target = chaseTime
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { @Sendable [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if CMTimeCompare(target, self.chaseTime) == 0 {
                    self.isSeeking = false
                } else {
                    self.seekToChaseTime()
                }
            }
        }
    }

    // MARK: Audio session

    private func activateAudio() {
        guard !holdsAudio else { return }
        holdsAudio = true
        PlaybackAudioSession.activate()
    }

    private func restoreAudio() {
        guard holdsAudio else { return }
        holdsAudio = false
        PlaybackAudioSession.release()
    }

    // MARK: Closing

    /// Stops at once and lets go of everything decrypted.
    func close() {
        isClosed = true
        pause()
        releasePlayer()
        restoreAudio()
    }

    private func releasePlayer() {
        if let timeObserver { player?.removeTimeObserver(timeObserver) }
        timeObserver = nil
        observations.forEach { $0.invalidate() }
        observations = []
        let center = NotificationCenter.default
        notificationTokens.forEach { center.removeObserver($0) }
        notificationTokens = []
        player?.replaceCurrentItem(with: nil)
        player = nil
        loader = nil
        isPlaying = false
    }
}

/// The app's audio session while a video or a voice message plays, or a
/// voice message is recorded: playback (sound with the silent switch, movie
/// or spoken-audio mode) or play-and-record, and what it was before once
/// the player or the recorder stops, with the other apps' audio told to
/// resume. Right after a player stops the session can still be busy for a
/// moment; then it is let go shortly after — unless something new started
/// meanwhile. One user at a time: the conversation stops a voice message
/// before a video opens or a recording starts.
@MainActor
enum PlaybackAudioSession {
    /// What a voice message needs the session for.
    enum VoiceUse: Sendable {
        /// Playing a voice message out loud (spoken audio).
        case playback
        /// Recording one (anything heard on the speaker, Bluetooth headsets'
        /// microphones allowed, haptics still felt).
        case recording
    }

    private static var saved: (category: AVAudioSession.Category, mode: AVAudioSession.Mode, options: AVAudioSession.CategoryOptions)?
    private static var pendingRelease: Task<Void, Never>?

    static func activate() {
        pendingRelease?.cancel()
        pendingRelease = nil
        let session = AVAudioSession.sharedInstance()
        if saved == nil { saved = (session.category, session.mode, session.categoryOptions) }
        try? session.setCategory(.playback, mode: .moviePlayback, options: [])
        try? session.setActive(true)
    }

    /// Sets the session up for a voice message and activates it. `false`
    /// when it could not be (a phone call holds the audio, for instance):
    /// the session is then put back as it was.
    @discardableResult
    static func activate(for use: VoiceUse) -> Bool {
        pendingRelease?.cancel()
        pendingRelease = nil
        let session = AVAudioSession.sharedInstance()
        if saved == nil { saved = (session.category, session.mode, session.categoryOptions) }
        do {
            switch use {
            case .playback:
                try session.setCategory(.playback, mode: .spokenAudio, options: [])
            case .recording:
                try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
                // iOS mutes haptics while the microphone is open unless asked:
                // the recorder's lock tap must still be felt.
                try? session.setAllowHapticsAndSystemSoundsDuringRecording(true)
            }
            try session.setActive(true)
            return true
        } catch {
            release()
            return false
        }
    }

    static func release() {
        guard let previous = saved else { return }
        saved = nil
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(previous.category, mode: previous.mode, options: previous.options)
        do {
            try session.setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            pendingRelease = Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }
                PlaybackAudioSession.pendingRelease = nil
                try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            }
        }
    }
}

/// The video surface: an `AVPlayerLayer`, aspect-fit, reporting when its
/// first frame is ready.
struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer?
    let onReadyForDisplay: (Bool) -> Void

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        view.onReadyForDisplay = onReadyForDisplay
        view.setPlayer(player)
        return view
    }

    func updateUIView(_ view: LayerView, context: Context) {
        view.onReadyForDisplay = onReadyForDisplay
        view.setPlayer(player)
    }

    static func dismantleUIView(_ view: LayerView, coordinator: ()) {
        view.setPlayer(nil)
    }

    final class LayerView: UIView {
        var onReadyForDisplay: (Bool) -> Void = { _ in }
        private var observation: NSKeyValueObservation?

        override static var layerClass: AnyClass { AVPlayerLayer.self }

        private var playerLayer: AVPlayerLayer? { layer as? AVPlayerLayer }

        override init(frame: CGRect) {
            super.init(frame: frame)
            backgroundColor = .clear
            isUserInteractionEnabled = false
            playerLayer?.videoGravity = .resizeAspect
            isAccessibilityElement = false
        }

        required init?(coder: NSCoder) { nil }

        func setPlayer(_ player: AVPlayer?) {
            guard let playerLayer, playerLayer.player !== player else { return }
            playerLayer.player = player
            // Let go (the view is being taken down): nothing more to report.
            guard player != nil else {
                observation = nil
                return
            }
            observation = playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) {
                @Sendable [weak self] layer, _ in
                let ready = layer.isReadyForDisplay
                Task { @MainActor in self?.onReadyForDisplay(ready) }
            }
        }
    }
}
