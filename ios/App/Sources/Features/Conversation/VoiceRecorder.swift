import AVFoundation
import SwiftUI

// 5.1 › mic (proposed): recording a voice message. `VoiceComposer` is the
// composer's state (hold · locked · review) over a capture device; the
// system device records with AVAudioRecorder into `VoiceRecordingFiles`
// and hands the bytes over in memory the moment recording stops.

/// Whether the app may use the microphone.
enum VoicePermission: Equatable, Sendable {
    case undetermined, granted, denied
}

enum VoiceCaptureError: Error, Equatable {
    /// The microphone or the audio session is not available right now.
    case unavailable
}

/// One recording in progress.
@MainActor
protocol VoiceCapture: AnyObject {
    /// Starts recording (the audio session is set up here).
    func start() throws(VoiceCaptureError)
    /// Seconds recorded so far.
    var currentTime: TimeInterval { get }
    /// The microphone still records: `false` once the system or an error
    /// stopped it without being asked (a full disk, an encoder error, a
    /// route change that paused it).
    var isRecording: Bool { get }
    /// The input level right now, 0…1.
    func level() -> Double
    /// Stops and returns the recorded bytes; nothing of it is left on disk.
    func finish() -> Data?
    /// Stops and throws the recording away.
    func discard()
    /// The system took the audio away (a call, another app's recording), or
    /// the recorder stopped by itself (an encoder error, the media services
    /// restarting).
    var onInterrupted: (() -> Void)? { get set }
}

/// Where recordings come from: the microphone, or (DEBUG harness) a
/// simulated one.
@MainActor
protocol VoiceCaptureDevice {
    var permission: VoicePermission { get }
    /// Asks once (the system prompt); `true` when allowed.
    func requestPermission() async -> Bool
    func makeCapture() -> any VoiceCapture
}

// MARK: - The microphone

/// The phone's microphone (NSMicrophoneUsageDescription: only while the
/// user records a voice message).
struct SystemVoiceDevice: VoiceCaptureDevice {
    var permission: VoicePermission {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: .granted
        case .denied: .denied
        case .undetermined: .undetermined
        @unknown default: .denied
        }
    }

    func requestPermission() async -> Bool {
        await AVAudioApplication.requestRecordPermission()
    }

    func makeCapture() -> any VoiceCapture {
        AudioRecorderCapture()
    }
}

/// AVAudioRecorder: AAC, mono, 24 kHz, about 32 kbit/s, metering on — into
/// a fresh file of `VoiceRecordingFiles` (complete protection, no backup),
/// which `finish()` reads into memory and deletes at once and `discard()`
/// deletes (and so does the capture going away without either). The audio
/// session is play-and-record while it records and back to what it was
/// afterwards. An encoder error or a stop nobody asked for counts as an
/// interruption (what was recorded goes to review); the media services
/// restarting leave nothing usable, so the recording is thrown away.
@MainActor
final class AudioRecorderCapture: VoiceCapture {
    var onInterrupted: (() -> Void)?

    private var recorder: AVAudioRecorder?
    private var recorderDelegate: VoiceRecorderDelegate?
    private var url: URL?
    /// Deletes the file if this capture goes away without `finish()` or `discard()`.
    private var fileGuard: VoiceRecordingFileGuard?
    private var holdsAudio = false
    private var observers: [any NSObjectProtocol] = []

    var currentTime: TimeInterval { recorder?.currentTime ?? 0 }

    var isRecording: Bool { recorder?.isRecording ?? false }

    func start() throws(VoiceCaptureError) {
        guard recorder == nil, let url = VoiceRecordingFiles.makeURL() else { throw VoiceCaptureError.unavailable }
        guard PlaybackAudioSession.activate(for: .recording) else {
            VoiceRecordingFiles.discard(url)
            throw VoiceCaptureError.unavailable
        }
        holdsAudio = true
        guard let recorder = try? AVAudioRecorder(url: url, settings: Self.settings()) else {
            fail(url)
            throw VoiceCaptureError.unavailable
        }
        let delegate = VoiceRecorderDelegate { [weak self] in
            Task { @MainActor in self?.recorderFailed() }
        }
        recorder.delegate = delegate
        recorder.isMeteringEnabled = true
        guard recorder.prepareToRecord(), recorder.record() else {
            recorder.delegate = nil
            recorder.stop()
            fail(url)
            throw VoiceCaptureError.unavailable
        }
        self.recorder = recorder
        recorderDelegate = delegate
        self.url = url
        fileGuard = VoiceRecordingFileGuard(url: url)
        observeInterruptions()
    }

    func level() -> Double {
        guard let recorder else { return 0 }
        recorder.updateMeters()
        return VoiceWaveform.level(decibels: recorder.averagePower(forChannel: 0))
    }

    func finish() -> Data? {
        guard let recorder, let url else { return nil }
        recorder.delegate = nil
        recorder.stop()
        self.recorder = nil
        recorderDelegate = nil
        self.url = nil
        stopObserving()
        let data = VoiceRecordingFiles.consume(url)
        fileGuard = nil
        releaseAudio()
        return data
    }

    func discard() {
        recorder?.delegate = nil
        recorder?.stop()
        recorder = nil
        recorderDelegate = nil
        if let url { VoiceRecordingFiles.discard(url) }
        url = nil
        fileGuard = nil
        stopObserving()
        releaseAudio()
    }

    private func fail(_ url: URL) {
        VoiceRecordingFiles.discard(url)
        releaseAudio()
    }

    private func releaseAudio() {
        guard holdsAudio else { return }
        holdsAudio = false
        PlaybackAudioSession.release()
    }

    /// The recorder stopped by itself (an encoder error, a full disk).
    private func recorderFailed() {
        guard recorder != nil else { return }
        onInterrupted?()
    }

    /// A call (or another app) takes the audio: recording ends there. A
    /// microphone that goes away (a headset disconnected) ends it only when
    /// the recorder could not carry on with the phone's own. The media
    /// services restarting end it and leave nothing to keep.
    private func observeInterruptions() {
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) {
                @Sendable [weak self] notification in
                let began = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                    == AVAudioSession.InterruptionType.began.rawValue
                MainActor.assumeIsolated { if began { self?.onInterrupted?() } }
            },
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) {
                @Sendable [weak self] notification in
                let lost = (notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                    == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue
                MainActor.assumeIsolated {
                    guard lost, let self, let recorder = self.recorder, !recorder.isRecording else { return }
                    self.onInterrupted?()
                }
            },
            center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) {
                @Sendable [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self, self.recorder != nil else { return }
                    self.discard()
                    self.onInterrupted?()
                }
            },
        ]
    }

    private func stopObserving() {
        let center = NotificationCenter.default
        observers.forEach { center.removeObserver($0) }
        observers = []
    }

    /// AAC in M4A, mono, 24 kHz, ~32 kbit/s: speech stays clear at ~240 KB a minute.
    private static func settings() -> [String: Any] {
        [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 24_000.0,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 32_000,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
    }
}

/// The recorder's own failures: an encoder error, or a recording that ended
/// unsuccessfully without being stopped (the capture lets go of its
/// delegate before it stops the recorder itself).
private final class VoiceRecorderDelegate: NSObject, AVAudioRecorderDelegate, Sendable {
    private let onFailure: @Sendable () -> Void

    init(onFailure: @escaping @Sendable () -> Void) {
        self.onFailure = onFailure
    }

    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        if !flag { onFailure() }
    }

    func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: (any Error)?) {
        onFailure()
    }
}

/// A recording file that is deleted when its capture goes away without
/// having read or discarded it (a composer dropped mid-recording). Deleting
/// a file that is already gone does nothing.
private final class VoiceRecordingFileGuard: Sendable {
    let url: URL

    init(url: URL) {
        self.url = url
    }

    deinit {
        VoiceRecordingFiles.discard(url)
    }
}

// MARK: - The composer's recorder

/// The composer's voice recorder (5.1 mic, proposed; WhatsApp / iMessage
/// behaviour):
///
/// * press and hold the mic → recording starts after `VoiceNoteRules.holdDelay`
///   (the pulsing dot, the time, the live waveform, “‹ İptal için kaydır”);
///   release → sent; slide left → cancelled; slide up → locked: recording
///   goes on hands-free with “Sil” · stop · “Gönder”; stop → review (listen,
///   then delete or send);
/// * a tap, or a recording shorter than a second, shows “Kaydetmek için
///   basılı tut” instead; at five minutes recording stops by itself and the
///   recording waits to be sent;
/// * the microphone is asked for on the first press; refused → a sheet
///   explains it and opens Settings;
/// * a call (or any interruption, or the recorder stopping by itself)
///   stops recording and keeps what was recorded for review; leaving the
///   chat or the foreground throws the recording away (`discardAll`) — it
///   is in memory only (the screen stays on while recording, so auto-lock
///   does not end a hands-free one);
/// * the keyboard stays while the mic is held (the mic stays under the
///   finger) and goes once recording locks or stops for review.
@Observable
@MainActor
final class VoiceComposer {
    enum Mode: Equatable {
        case idle
        /// Recording while the finger holds the mic.
        case holding
        /// Recording hands-free.
        case locked
        /// Stopped: the recording waits to be listened to, deleted or sent.
        case review
    }

    enum Hint: Equatable {
        /// “Kaydetmek için basılı tut”.
        case hold
        /// “Şu anda kayıt yapılamıyor”.
        case unavailable
        /// “Bu sesli mesaj oynatılamıyor” (the conversation's player failed).
        case unplayable
        /// “Sesli mesaj gönderilemedi”.
        case sendFailed
    }

    /// The recording's id in `player` while it is listened to in review.
    static let previewID: MessageID = "voice.preview"

    private(set) var mode: Mode = .idle
    private(set) var gesture = VoiceHoldGesture()
    /// The microphone records right now (false once stopped at the maximum).
    private(set) var isCapturing = false
    /// Seconds recorded.
    private(set) var elapsed: TimeInterval = 0
    /// Input levels (0…1), ten a second: the live waveform.
    private(set) var levels: [Double] = []
    /// A finished recording waiting to be sent (review, or held past the maximum).
    private(set) var recording: VoiceRecording?
    private(set) var hint: Hint?
    /// The microphone is refused: the sheet that leads to Settings.
    var showsPermissionNotice = false

    /// Plays the recording in review (the conversation's one player).
    @ObservationIgnored let player: VoicePlayback
    /// Hands a finished recording to the conversation.
    @ObservationIgnored var onSend: ((VoiceRecording) -> Void)?

    @ObservationIgnored private let device: any VoiceCaptureDevice
    @ObservationIgnored private var capture: (any VoiceCapture)?
    @ObservationIgnored private var holdTask: Task<Void, Never>?
    @ObservationIgnored private var meterTask: Task<Void, Never>?
    @ObservationIgnored private var hintTask: Task<Void, Never>?
    @ObservationIgnored private var isAskingPermission = false
    /// A finger is on the mic (between its first move and its release).
    @ObservationIgnored private var touchActive = false
    @ObservationIgnored private var touchNumber = 0

    /// Cheap: nothing touches the microphone or the audio session before the
    /// first press.
    init(player: VoicePlayback, device: any VoiceCaptureDevice = SystemVoiceDevice()) {
        self.player = player
        self.device = device
    }

    /// Recording, or a recording waiting in review.
    var isBusy: Bool { mode != .idle }

    /// Recording right now (held or locked): nothing else may play.
    var isRecording: Bool { mode == .holding || mode == .locked }

    /// Where the held mic is drawn: it follows the finger, left and up.
    var micOffset: CGSize {
        guard mode == .holding else { return .zero }
        return CGSize(
            width: max(-VoiceNoteRules.cancelDistance, gesture.translation.width),
            height: max(-VoiceNoteRules.lockDistance, gesture.translation.height))
    }

    // MARK: The mic's touch

    /// The finger on the mic moved (the first call is the touch going down).
    func touchChanged(_ translation: CGSize) {
        if !touchActive {
            touchActive = true
            touchNumber += 1
            touchBegan()
        }
        guard mode == .holding else { return }
        switch gesture.slide(translation) {
        case .cancel?:
            cancel()
        case .lock?:
            lock()
        default:
            break
        }
    }

    /// The finger lifted.
    func touchEnded() {
        guard touchActive else { return }
        endTouch()
        let recorded = recording?.duration ?? capture?.currentTime ?? 0
        switch gesture.release(recorded: recorded) {
        case .send?:
            send()
        case .hint?:
            if mode == .holding { discardRecording() }
            showHint(.hold)
        default:
            break
        }
    }

    /// The touch is over — lifted (`touchEnded` came first and this does
    /// nothing) or taken by the system without a release (a call, Control
    /// Center): then what was recorded is kept for review.
    func touchFinished() {
        guard touchActive else { return }
        let number = touchNumber
        Task { [weak self] in
            guard let self, self.touchActive, self.touchNumber == number else { return }
            self.endTouch()
            if self.gesture.phase == .holding {
                self.gesture.reset()
                self.keepForReview()
            } else if self.gesture.phase == .pressed {
                self.gesture.reset()
            }
        }
    }

    /// VoiceOver's double tap on the mic: recording starts hands-free
    /// (stop, delete and send are then their own buttons).
    func startHandsFree() {
        guard mode == .idle else { return }
        switch device.permission {
        case .granted:
            gesture.press()
            guard gesture.holdDelayPassed() == .startRecording else { return }
            guard startRecording() else {
                gesture.reset()
                return
            }
            _ = gesture.slide(CGSize(width: 0, height: -VoiceNoteRules.lockDistance))
            lock()
        case .undetermined:
            requestPermission()
        case .denied:
            showsPermissionNotice = true
        }
    }

    private func touchBegan() {
        guard mode == .idle else { return }
        hideHint()
        switch device.permission {
        case .granted:
            // Nothing records (`mode` is idle): a press left over from a lost
            // touch starts afresh.
            if gesture.phase != .idle { gesture.reset() }
            guard gesture.press() else { return }
            holdTask?.cancel()
            holdTask = Task { [weak self] in
                try? await Task.sleep(for: VoiceNoteRules.holdDelay)
                guard !Task.isCancelled, let self else { return }
                self.holdTask = nil
                if self.gesture.holdDelayPassed() == .startRecording, !self.startRecording() {
                    self.gesture.reset()
                }
            }
        case .undetermined:
            requestPermission()
        case .denied:
            showsPermissionNotice = true
        }
    }

    /// The current touch counts no more (lifted, taken away, or its view
    /// went when recording locked or stopped); the next one starts afresh.
    private func endTouch() {
        touchActive = false
        holdTask?.cancel()
        holdTask = nil
    }

    // MARK: Recording

    /// Opens the microphone; `false` (and the hint) if it cannot.
    private func startRecording() -> Bool {
        // One sound at a time: a voice message (or the review) stops first,
        // and a review listened to earlier starts over with the next one.
        player.stop()
        player.forget(Self.previewID)
        // Felt before the microphone opens, so the tap is not recorded.
        ConversationHaptics.voice()
        let capture = device.makeCapture()
        do {
            try capture.start()
        } catch {
            U2Haptics.error()
            showHint(.unavailable)
            return false
        }
        capture.onInterrupted = { [weak self] in self?.interrupted() }
        self.capture = capture
        recording = nil
        levels = []
        elapsed = 0
        isCapturing = true
        withU2Animation(U2Motion.micro) { mode = .holding }
        startMetering()
        return true
    }

    private func lock() {
        ConversationHaptics.voice()
        // The finger is free: its touch (whose view goes now) is over for us,
        // and the keyboard (kept while the mic was held) can go.
        endTouch()
        U2Keyboard.dismiss()
        if isCapturing {
            withU2Animation(U2Motion.state) { mode = .locked }
            return
        }
        // Stopped at the maximum while held: straight to review.
        gesture.reset()
        if recording != nil {
            withU2Animation(U2Motion.state) { mode = .review }
        } else {
            returnToIdle()
        }
    }

    /// “Kaydı durdur” (locked): the recording waits in review.
    func stop() {
        guard mode == .locked else { return }
        gesture.reset()
        keepForReview()
    }

    /// Slid away, or “Sil”: the recording is gone.
    func cancel() {
        discardRecording()
        // After the microphone closed: felt, never recorded.
        ConversationHaptics.voice()
    }

    /// Released, or “Gönder”: the recording goes to the conversation.
    func send() {
        if mode == .locked { _ = gesture.sendLocked(recorded: capture?.currentTime ?? 0) }
        player.forget(Self.previewID)
        let finished = recording ?? finishCapture()
        returnToIdle()
        guard let finished else {
            showHint(.unavailable)
            return
        }
        guard VoiceNoteRules.isLongEnough(finished.duration) else {
            showHint(.hold)
            return
        }
        onSend?(finished)
    }

    /// The review's play / pause.
    func togglePreview() {
        guard mode == .review, let recording else { return }
        if player.activeID == Self.previewID {
            player.togglePause()
            return
        }
        player.begin(Self.previewID)
        player.play(
            MediaPayload(data: recording.audio, contentType: VoiceRecording.contentType, fileName: nil),
            for: Self.previewID)
    }

    /// Leaving the chat or the foreground: whatever is recorded (or waits in
    /// review) is thrown away, at once and without animation.
    func discardAll() {
        endTouch()
        hintTask?.cancel()
        hintTask = nil
        hint = nil
        showsPermissionNotice = false
        player.forget(Self.previewID)
        capture?.discard()
        capture = nil
        stopMetering()
        gesture.reset()
        isCapturing = false
        recording = nil
        levels = []
        elapsed = 0
        mode = .idle
    }

    /// A call took the microphone: what was recorded is kept for review.
    private func interrupted() {
        guard isCapturing else { return }
        gesture.reset()
        keepForReview()
    }

    /// Stops recording; at least a second goes to review, anything shorter is
    /// thrown away (with the hold hint), and nothing readable says so.
    private func keepForReview() {
        endTouch()
        let finished = recording ?? finishCapture()
        guard let finished else {
            returnToIdle()
            showHint(.unavailable)
            return
        }
        guard VoiceNoteRules.isLongEnough(finished.duration) else {
            returnToIdle()
            showHint(.hold)
            return
        }
        recording = finished
        U2Keyboard.dismiss()
        withU2Animation(U2Motion.state) { mode = .review }
    }

    /// Five minutes: recording stops; held, it is sent on release; locked,
    /// it waits in review.
    private func reachedMaximum() {
        if mode == .locked {
            gesture.reset()
            keepForReview()
        } else {
            recording = finishCapture()
        }
        // After the microphone closed: felt, never recorded.
        ConversationHaptics.voice()
    }

    /// Stops the microphone and takes the recording into memory (its file
    /// is deleted by the capture).
    private func finishCapture() -> VoiceRecording? {
        stopMetering()
        guard let capture else { return nil }
        let duration = min(capture.currentTime, VoiceNoteRules.maximumDuration)
        self.capture = nil
        isCapturing = false
        guard let audio = capture.finish() else { return nil }
        return VoiceRecording(audio: audio, duration: duration, waveform: VoiceWaveform.data(levels))
    }

    private func discardRecording() {
        player.forget(Self.previewID)
        capture?.discard()
        capture = nil
        stopMetering()
        returnToIdle()
    }

    private func returnToIdle() {
        gesture.reset()
        isCapturing = false
        recording = nil
        withU2Animation(U2Motion.state) {
            mode = .idle
        }
        levels = []
        elapsed = 0
    }

    // MARK: Metering

    private func startMetering() {
        meterTask?.cancel()
        meterTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(100))
                guard !Task.isCancelled, let self else { return }
                self.meter()
            }
        }
    }

    private func stopMetering() {
        meterTask?.cancel()
        meterTask = nil
    }

    private func meter() {
        guard let capture, isCapturing else { return }
        elapsed = min(capture.currentTime, VoiceNoteRules.maximumDuration)
        // Stopped underneath (a full disk, an encoder error, a paused
        // route): the clock would freeze, so it ends here, kept for review.
        guard capture.isRecording else {
            interrupted()
            return
        }
        levels.append(capture.level())
        if VoiceNoteRules.hasReachedMaximum(capture.currentTime) { reachedMaximum() }
    }

    // MARK: Permission and hints

    private func requestPermission() {
        guard !isAskingPermission else { return }
        isAskingPermission = true
        let device = device
        Task { [weak self] in
            let granted = await device.requestPermission()
            guard let self else { return }
            self.isAskingPermission = false
            if granted {
                self.showHint(.hold)
            } else {
                self.showsPermissionNotice = true
            }
        }
    }

    /// The conversation's voice message could not be played or sent: said
    /// above the mic like the recorder's own hints (never over a recording).
    func report(_ hint: Hint) {
        guard !isRecording else { return }
        showHint(hint)
    }

    private func showHint(_ hint: Hint) {
        hintTask?.cancel()
        withU2Animation(U2Motion.micro) { self.hint = hint }
        hintTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2.2))
            guard !Task.isCancelled, let self else { return }
            withU2Animation(U2Motion.state) { self.hint = nil }
        }
    }

    private func hideHint() {
        hintTask?.cancel()
        hintTask = nil
        guard hint != nil else { return }
        withU2Animation(U2Motion.micro) { hint = nil }
    }
}

#if DEBUG
    // MARK: - Harness

    extension VoiceComposer {
        /// DEBUG harness: shows a recording state without a microphone (the
        /// screenshots of the proposed recording UI). `review` keeps a
        /// synthesized recording, so listening works.
        func showDemo(_ mode: Mode, elapsed: TimeInterval, slide: CGSize = .zero) {
            discardAll()
            let levels = (0..<Int(elapsed * 10)).map { index in
                0.25 + 0.55 * abs(sin(Double(index) * 0.45)) * (0.6 + 0.4 * abs(cos(Double(index) * 0.13)))
            }
            self.levels = levels
            self.elapsed = elapsed
            switch mode {
            case .idle:
                return
            case .holding:
                gesture = VoiceHoldGesture(phase: .holding, translation: slide)
                isCapturing = true
            case .locked:
                gesture = VoiceHoldGesture(phase: .locked)
                isCapturing = true
            case .review:
                let waveform = VoiceWaveform.data(levels)
                recording = VoiceRecording(
                    audio: FixtureMedia.voiceWAV(duration: elapsed, waveform: waveform), duration: elapsed,
                    waveform: waveform)
            }
            self.mode = mode
        }

        /// DEBUG harness: the hint as a tap shows it.
        func showDemoHint() {
            showHint(.hold)
        }
    }
#endif
