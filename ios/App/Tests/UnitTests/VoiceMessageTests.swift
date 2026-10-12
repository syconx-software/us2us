import Foundation
import Testing

@testable import us2us

// Voice messages (5.1 mic, proposed): the waveform, the recording rules,
// the mic's hold / lock / cancel state machine and the recorder built on
// it (with a fake microphone), the recording's temporary file, and the
// sending and playing paths of the fixtures and the live mapping.

// MARK: - Waveform

@Suite("Voice · waveform")
struct VoiceWaveformTests {
    @Test func meterReadingsBecomeLevelsAboveTheFloor() {
        #expect(VoiceWaveform.level(decibels: 0) == 1)
        #expect(VoiceWaveform.level(decibels: 3) == 1, "above full scale is full scale")
        #expect(VoiceWaveform.level(decibels: -25) == 0.5)
        #expect(VoiceWaveform.level(decibels: -50) == 0)
        #expect(VoiceWaveform.level(decibels: -160) == 0)
        #expect(VoiceWaveform.level(decibels: .nan) == 0)
        #expect(VoiceWaveform.level(decibels: -.infinity) == 0)
    }

    @Test func aLongRecordingIsDownsampledEvenlyToAHundredSamples() {
        // 420 levels (42 s at ten a second): 100 stretches of 4–5 levels.
        let levels = (0..<420).map { Double($0 % 2) }
        let samples = VoiceWaveform.samples(levels)
        #expect(samples.count == 100)
        #expect(samples.count == VoiceWaveform.maxSamples)
        for sample in samples {
            #expect((102...153).contains(sample), "each sample is the mean of its stretch (0.4–0.6)")
        }
        // A rising ramp stays rising: the stretches are in order.
        let ramp = VoiceWaveform.samples((0..<1_000).map { Double($0) / 999 })
        #expect(ramp.count == 100)
        #expect(ramp == ramp.sorted())
        #expect(ramp.first ?? 255 < 5)
        #expect(ramp.last ?? 0 > 250)
    }

    @Test func aShortRecordingKeepsEveryLevel() {
        let samples = VoiceWaveform.samples([0, 0.5, 1, 1.4, -0.2])
        #expect(samples == [0, 128, 255, 255, 0], "one for one, clamped to 0…255")
        #expect(VoiceWaveform.samples([]).isEmpty)
        #expect(VoiceWaveform.data([0.5, 1]) == Data([128, 255]))
    }

    @Test func resamplingTakesTheMeanOfEqualStretches() {
        #expect(VoiceWaveform.resample([0, 1, 0, 1], count: 2) == [0.5, 0.5])
        #expect(VoiceWaveform.resample([0, 0.5, 1], count: 6) == [0, 0, 0.5, 0.5, 1, 1])
        #expect(VoiceWaveform.resample([1, 2, 3], count: 0).isEmpty)
        #expect(VoiceWaveform.resample([], count: 4).isEmpty)
    }

    @Test func theBubbleDrawsTheDesignsThirtyBars() {
        let flat = VoiceWaveform.bars(nil, count: 30)
        #expect(flat == Array(repeating: 0.3, count: 30), "no samples: an even, quiet line")
        let bars = VoiceWaveform.bars(Data([0, 255]), count: 4)
        #expect(bars == [0.08, 0.08, 1, 1], "never lower than a sliver")
        #expect(LiveMapping.waveform(Data(repeating: 200, count: 100)).count == 30)
    }

    @Test func theDurationTravelsInMilliseconds() {
        #expect(VoiceRecording(audio: Data(), duration: 4.2, waveform: Data()).durationMilliseconds == 4_200)
        #expect(VoiceRecording(audio: Data(), duration: 0.0004, waveform: Data()).durationMilliseconds == 0)
        #expect(VoiceRecording(audio: Data(), duration: -1, waveform: Data()).durationMilliseconds == 0)
    }

    @Test func speedsCycleAndSpeakTheLanguage() {
        #expect(VoicePlaybackRate.normal.next == .oneAndHalf)
        #expect(VoicePlaybackRate.oneAndHalf.next == .double)
        #expect(VoicePlaybackRate.double.next == .normal)
        #expect(VoicePlaybackRate.oneAndHalf.value == 1.5)
        #expect(VoicePlaybackRate.oneAndHalf.label(locale: Locale(identifier: "tr_TR")) == "1,5×")
        #expect(VoicePlaybackRate.oneAndHalf.label(locale: Locale(identifier: "en_TR")) == "1.5×")
        #expect(VoicePlaybackRate.double.label(locale: Locale(identifier: "tr")) == "2×")
    }
}

// MARK: - Rules and the hold gesture

@Suite("Voice · recording rules")
struct VoiceRuleTests {
    @Test func aRecordingNeedsASecondAndStopsAtFiveMinutes() {
        #expect(!VoiceNoteRules.isLongEnough(0.99))
        #expect(VoiceNoteRules.isLongEnough(1))
        #expect(VoiceNoteRules.isLongEnough(299))
        #expect(!VoiceNoteRules.hasReachedMaximum(299.9))
        #expect(VoiceNoteRules.hasReachedMaximum(300))
        #expect(VoiceNoteRules.maximumDuration == 300)
    }
}

@Suite("Voice · hold, lock, cancel")
struct VoiceHoldGestureTests {
    // The mutating steps run outside `#expect`; it checks their results.

    @Test func aTapOnlyShowsTheHint() {
        var gesture = VoiceHoldGesture()
        let pressed = gesture.press()
        #expect(pressed)
        #expect(gesture.phase == .pressed)
        let released = gesture.release(recorded: 0)
        #expect(released == .hint)
        #expect(gesture.phase == .idle)
    }

    @Test func holdingRecordsAndReleasingSends() {
        var gesture = VoiceHoldGesture()
        gesture.press()
        let started = gesture.holdDelayPassed()
        #expect(started == .startRecording)
        #expect(gesture.phase == .holding)
        let slid = gesture.slide(CGSize(width: -20, height: -10))
        #expect(slid == nil)
        let released = gesture.release(recorded: 4.2)
        #expect(released == .send)
        #expect(gesture.phase == .idle)
        #expect(gesture.translation == .zero)
    }

    @Test func aRecordingUnderASecondIsNotSent() {
        var gesture = VoiceHoldGesture()
        gesture.press()
        _ = gesture.holdDelayPassed()
        let released = gesture.release(recorded: 0.6)
        #expect(released == .hint)
    }

    @Test func slidingLeftCancels() {
        var gesture = VoiceHoldGesture()
        gesture.press()
        _ = gesture.holdDelayPassed()
        let halfway = gesture.slide(CGSize(width: -55, height: 0))
        #expect(halfway == nil)
        #expect(abs(gesture.cancelProgress - 0.5) < 0.001)
        #expect(gesture.lockProgress == 0)
        let far = gesture.slide(CGSize(width: -VoiceNoteRules.cancelDistance, height: 0))
        #expect(far == .cancel)
        #expect(gesture.phase == .idle)
        let released = gesture.release(recorded: 3)
        #expect(released == nil, "the release after a cancel sends nothing")
    }

    @Test func slidingUpLocksAndTheFingerIsFree() {
        var gesture = VoiceHoldGesture()
        gesture.press()
        _ = gesture.holdDelayPassed()
        let halfway = gesture.slide(CGSize(width: 0, height: -40))
        #expect(halfway == nil)
        #expect(abs(gesture.lockProgress - 0.5) < 0.001)
        let far = gesture.slide(CGSize(width: -10, height: -VoiceNoteRules.lockDistance))
        #expect(far == .lock)
        #expect(gesture.phase == .locked)
        let released = gesture.release(recorded: 3)
        #expect(released == nil, "locked: lifting the finger changes nothing")
        #expect(gesture.phase == .locked)
        let sent = gesture.sendLocked(recorded: 3)
        #expect(sent == .send)
        #expect(gesture.phase == .idle)
    }

    @Test func lockedItCanBeDeletedOrBeTooShortToSend() {
        var gesture = VoiceHoldGesture(phase: .locked)
        let deleted = gesture.deleteLocked()
        #expect(deleted == .cancel)
        let again = gesture.deleteLocked()
        #expect(again == nil)
        gesture = VoiceHoldGesture(phase: .locked)
        let short = gesture.sendLocked(recorded: 0.4)
        #expect(short == .hint)
    }

    @Test func onlyLeftAndUpCount() {
        var gesture = VoiceHoldGesture()
        gesture.press()
        _ = gesture.holdDelayPassed()
        let slid = gesture.slide(CGSize(width: 300, height: 300))
        #expect(slid == nil)
        #expect(gesture.translation == .zero)
        #expect(gesture.cancelProgress == 0)
        #expect(gesture.lockProgress == 0)
    }

    @Test func nothingStartsTwiceAndSlidesBeforeRecordingDoNothing() {
        var gesture = VoiceHoldGesture()
        let first = gesture.press()
        let second = gesture.press()
        #expect(first)
        #expect(!second)
        let slid = gesture.slide(CGSize(width: -500, height: 0))
        #expect(slid == nil, "not recording yet")
        #expect(gesture.phase == .pressed)
        gesture.reset()
        #expect(gesture.phase == .idle)
        let started = gesture.holdDelayPassed()
        #expect(started == nil)
    }
}

// MARK: - The recorder (fake microphone)

@MainActor
final class FakeVoiceDevice: VoiceCaptureDevice {
    var permission: VoicePermission
    var grants = true
    var failsToStart = false
    private(set) var permissionRequests = 0
    private(set) var captures: [FakeVoiceCapture] = []

    init(permission: VoicePermission = .granted) {
        self.permission = permission
    }

    func requestPermission() async -> Bool {
        permissionRequests += 1
        permission = grants ? .granted : .denied
        return grants
    }

    func makeCapture() -> any VoiceCapture {
        let capture = FakeVoiceCapture(failsToStart: failsToStart)
        captures.append(capture)
        return capture
    }
}

@MainActor
final class FakeVoiceCapture: VoiceCapture {
    var onInterrupted: (() -> Void)?
    var currentTime: TimeInterval = 0
    let failsToStart: Bool
    /// The recorder stopped underneath (a full disk, an encoder error).
    var stopsBySystem = false
    /// What `finish()` reads back (`nil`: nothing readable was recorded).
    var audio: Data? = Data("aac".utf8)
    private(set) var started = false
    private(set) var finished = false
    private(set) var discarded = false

    init(failsToStart: Bool) {
        self.failsToStart = failsToStart
    }

    var isRecording: Bool { started && !finished && !discarded && !stopsBySystem }

    func start() throws(VoiceCaptureError) {
        if failsToStart { throw VoiceCaptureError.unavailable }
        started = true
    }

    func level() -> Double { 0.5 }

    func finish() -> Data? {
        finished = true
        return audio
    }

    func discard() {
        discarded = true
    }
}

@Suite("Voice · recorder", .serialized)
@MainActor
struct VoiceComposerTests {
    private func makeComposer(_ device: FakeVoiceDevice) -> (VoiceComposer, Box) {
        let composer = VoiceComposer(player: VoicePlayback(), device: device)
        let box = Box()
        composer.onSend = { box.sent.append($0) }
        return (composer, box)
    }

    final class Box {
        var sent: [VoiceRecording] = []
    }

    private func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<150 where !condition() {
            try await Task.sleep(for: .milliseconds(20))
        }
        #expect(condition(), "timed out")
    }

    @Test func aTapShowsTheHintAndNeverOpensTheMicrophone() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        composer.touchEnded()
        #expect(composer.hint == .hold)
        try await Task.sleep(for: .milliseconds(350))
        #expect(device.captures.isEmpty, "released before the hold delay")
        #expect(composer.mode == .idle)
        #expect(box.sent.isEmpty)
    }

    @Test func holdingRecordsAndReleasingSends() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        let capture = try #require(device.captures.first)
        #expect(capture.started)
        #expect(composer.isRecording)
        capture.currentTime = 3.2
        try await waitUntil { !composer.levels.isEmpty }
        composer.touchEnded()
        #expect(composer.mode == .idle)
        #expect(capture.finished)
        let recording = try #require(box.sent.first)
        #expect(recording.duration == 3.2)
        #expect(recording.audio == Data("aac".utf8))
        #expect(!recording.waveform.isEmpty)
        #expect(recording.waveform.count <= VoiceWaveform.maxSamples)
    }

    @Test func underASecondIsThrownAwayWithTheHint() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        let capture = try #require(device.captures.first)
        capture.currentTime = 0.5
        composer.touchEnded()
        #expect(box.sent.isEmpty)
        #expect(capture.discarded)
        #expect(composer.hint == .hold)
        #expect(composer.mode == .idle)
    }

    @Test func slidingLeftCancels() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        let capture = try #require(device.captures.first)
        capture.currentTime = 4
        composer.touchChanged(CGSize(width: -60, height: 0))
        #expect(composer.mode == .holding)
        #expect(composer.micOffset.width == -60)
        composer.touchChanged(CGSize(width: -VoiceNoteRules.cancelDistance - 5, height: 0))
        #expect(composer.mode == .idle)
        #expect(capture.discarded)
        composer.touchEnded()
        #expect(box.sent.isEmpty, "the release after cancelling sends nothing")
    }

    @Test func slidingUpLocksThenStopReviewsAndSendSends() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        let capture = try #require(device.captures.first)
        capture.currentTime = 6
        composer.touchChanged(CGSize(width: 0, height: -VoiceNoteRules.lockDistance))
        #expect(composer.mode == .locked)
        composer.touchEnded()
        #expect(composer.mode == .locked, "the finger is free")
        #expect(box.sent.isEmpty)
        composer.stop()
        #expect(composer.mode == .review)
        #expect(capture.finished)
        #expect(composer.recording?.duration == 6)
        composer.send()
        #expect(composer.mode == .idle)
        #expect(box.sent.first?.duration == 6)
    }

    @Test func lockedItCanBeDeleted() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        composer.touchChanged(CGSize(width: 0, height: -200))
        #expect(composer.mode == .locked)
        composer.cancel()
        #expect(composer.mode == .idle)
        let capture = try #require(device.captures.first)
        #expect(capture.discarded)
        #expect(box.sent.isEmpty)
    }

    @Test func atFiveMinutesRecordingStopsAndTheReleaseSendsIt() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        let capture = try #require(device.captures.first)
        capture.currentTime = VoiceNoteRules.maximumDuration
        try await waitUntil { !composer.isCapturing }
        #expect(capture.finished)
        #expect(composer.mode == .holding, "still held: it waits for the release")
        composer.touchEnded()
        #expect(box.sent.first?.duration == VoiceNoteRules.maximumDuration)
    }

    @Test func lockedAtFiveMinutesItWaitsInReview() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        composer.touchChanged(CGSize(width: 0, height: -200))
        composer.touchEnded()
        let capture = try #require(device.captures.first)
        capture.currentTime = VoiceNoteRules.maximumDuration + 2
        try await waitUntil { composer.mode == .review }
        #expect(composer.recording?.duration == VoiceNoteRules.maximumDuration)
        #expect(box.sent.isEmpty)
    }

    @Test func aCallKeepsWhatWasRecordedForReview() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        let capture = try #require(device.captures.first)
        capture.currentTime = 2.5
        capture.onInterrupted?()
        #expect(composer.mode == .review)
        composer.touchEnded()
        #expect(box.sent.isEmpty, "the release after the interruption sends nothing by itself")
        #expect(composer.mode == .review)
    }

    @Test func aRecorderThatStopsByItselfKeepsWhatWasRecordedForReview() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        let capture = try #require(device.captures.first)
        capture.currentTime = 3
        capture.stopsBySystem = true
        try await waitUntil { composer.mode == .review }
        #expect(capture.finished)
        #expect(composer.recording?.duration == 3)
        composer.touchEnded()
        #expect(box.sent.isEmpty, "the release after it stopped sends nothing by itself")
    }

    @Test func anInterruptionWithNothingReadableSaysRecordingFailed() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        let capture = try #require(device.captures.first)
        capture.currentTime = 2
        capture.audio = nil
        capture.onInterrupted?()
        #expect(composer.mode == .idle)
        #expect(composer.hint == .unavailable)
        composer.touchEnded()
        #expect(box.sent.isEmpty)
    }

    @Test func theNextRecordingIsListenedToFromItsStart() throws {
        let (composer, _) = makeComposer(FakeVoiceDevice())
        composer.showDemo(.review, elapsed: 4)
        let audio = try #require(composer.recording?.audio)
        let preview = VoiceComposer.previewID
        composer.player.begin(preview)
        try #require(
            composer.player.play(MediaPayload(data: audio, contentType: "audio/wav", fileName: nil), for: preview),
            "the simulator plays the stand-in")
        composer.player.pause()
        composer.player.scrub(preview, to: 0.5)
        composer.player.stop()
        #expect(composer.player.shownFraction(of: preview, fallback: 0) > 0.4, "half of it was listened to")
        composer.send()
        #expect(composer.player.shownFraction(of: preview, fallback: 0) == 0, "sent: the review's place is forgotten")
        composer.showDemo(.review, elapsed: 3)
        #expect(composer.player.shownFraction(of: preview, fallback: 0) == 0)
    }

    @Test func aPlaybackOrSendingProblemIsSaidAboveTheMicButNotOverARecording() async throws {
        let device = FakeVoiceDevice()
        let (composer, _) = makeComposer(device)
        composer.report(.sendFailed)
        #expect(composer.hint == .sendFailed)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        composer.report(.unplayable)
        #expect(composer.hint == nil, "the press hid the hint; nothing shows over the recording")
        composer.discardAll()
    }

    @Test func aTouchTheSystemTakesKeepsTheRecordingForReview() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        let capture = try #require(device.captures.first)
        capture.currentTime = 2
        composer.touchFinished()
        try await waitUntil { composer.mode == .review }
        #expect(box.sent.isEmpty)
    }

    @Test func leavingThrowsTheRecordingAway() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.mode == .holding }
        composer.discardAll()
        #expect(composer.mode == .idle)
        let capture = try #require(device.captures.first)
        #expect(capture.discarded)
        #expect(composer.levels.isEmpty)
        composer.touchEnded()
        #expect(box.sent.isEmpty)
    }

    @Test func aRefusedMicrophoneLeadsToSettings() async throws {
        let device = FakeVoiceDevice(permission: .denied)
        let (composer, _) = makeComposer(device)
        composer.touchChanged(.zero)
        composer.touchEnded()
        #expect(composer.showsPermissionNotice)
        #expect(device.captures.isEmpty)
    }

    @Test func theFirstPressAsksForTheMicrophone() async throws {
        let device = FakeVoiceDevice(permission: .undetermined)
        let (composer, _) = makeComposer(device)
        composer.touchChanged(.zero)
        composer.touchEnded()
        try await waitUntil { device.permissionRequests == 1 && composer.hint == .hold }
        #expect(device.captures.isEmpty, "asking records nothing; the next press does")
        let refusing = FakeVoiceDevice(permission: .undetermined)
        refusing.grants = false
        let (refused, _) = makeComposer(refusing)
        refused.touchChanged(.zero)
        refused.touchEnded()
        try await waitUntil { refused.showsPermissionNotice }
    }

    @Test func aMicrophoneThatWillNotStartSaysSo() async throws {
        let device = FakeVoiceDevice()
        device.failsToStart = true
        let (composer, _) = makeComposer(device)
        composer.touchChanged(.zero)
        try await waitUntil { composer.hint == .unavailable }
        #expect(composer.mode == .idle)
        composer.touchEnded()
        #expect(composer.mode == .idle)
    }

    @Test func voiceOverRecordsHandsFree() async throws {
        let device = FakeVoiceDevice()
        let (composer, box) = makeComposer(device)
        composer.startHandsFree()
        #expect(composer.mode == .locked)
        let capture = try #require(device.captures.first)
        capture.currentTime = 2
        composer.send()
        #expect(box.sent.first?.duration == 2)
    }
}

// MARK: - The recording's file

/// Serialised: the tests share the one voice folder. Each checks only its
/// own files, since other suites sweep the folder too (`AppModel`, erasing).
@Suite("Voice · recording file", .serialized)
struct VoiceRecordingFileTests {
    @Test func aRecordingFileLivesInAProtectedFolderAndGoesAsItIsRead() throws {
        let url = try #require(VoiceRecordingFiles.makeURL())
        #expect(url.deletingLastPathComponent().deletingLastPathComponent().path == VoiceRecordingFiles.root.path)
        #expect(!FileManager.default.fileExists(atPath: url.path), "nothing is written before recording")
        let values = try VoiceRecordingFiles.root.resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)
        try Data("aac".utf8).write(to: url)
        #expect(FileManager.default.fileExists(atPath: url.path))
        let consumed = VoiceRecordingFiles.consume(url)
        #expect(consumed == Data("aac".utf8))
        #expect(!FileManager.default.fileExists(atPath: url.path))
        #expect(!FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path), "its folder goes too")
        let folder = url.deletingLastPathComponent().lastPathComponent
        #expect(!VoiceRecordingFiles.existingFiles.contains { $0.deletingLastPathComponent().lastPathComponent == folder })
    }

    @Test func anEmptyRecordingReadsAsNothingAndStillGoes() throws {
        let url = try #require(VoiceRecordingFiles.makeURL())
        try Data().write(to: url)
        let empty = VoiceRecordingFiles.consume(url)
        #expect(empty == nil)
        #expect(!FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path))
        let missing = try #require(VoiceRecordingFiles.makeURL())
        let neverStarted = VoiceRecordingFiles.consume(missing)
        #expect(neverStarted == nil, "a recording that never started")
        #expect(!FileManager.default.fileExists(atPath: missing.deletingLastPathComponent().path))
    }

    @Test func discardTouchesNothingOutsideTheVoiceFolder() throws {
        let outside = FileManager.default.temporaryDirectory.appendingPathComponent("keep-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        let file = outside.appendingPathComponent("voice.m4a")
        try Data("x".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: outside) }
        VoiceRecordingFiles.discard(file)
        #expect(FileManager.default.fileExists(atPath: file.path))
    }

    @Test func removeAllSweepsLeftovers() throws {
        let first = try #require(VoiceRecordingFiles.makeURL())
        let second = try #require(VoiceRecordingFiles.makeURL())
        try Data([1]).write(to: first)
        try Data([2]).write(to: second)
        VoiceRecordingFiles.removeAll()
        #expect(!FileManager.default.fileExists(atPath: first.path))
        #expect(!FileManager.default.fileExists(atPath: second.path))
        #expect(!FileManager.default.fileExists(atPath: VoiceRecordingFiles.root.path))
    }
}

// MARK: - Sending, playing, mapping

@Suite("Voice · messages")
@MainActor
struct VoiceMessageFlowTests {
    private let now = Date(timeIntervalSince1970: 1_791_000_000)

    private func makeService() -> DesignFixturesService {
        DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
    }

    @Test func aRecordingSentInTheFixturesShowsAtOnceAndPlaysBack() async throws {
        let service = makeService()
        let recording = VoiceRecording(audio: Data("voice".utf8), duration: 3.4, waveform: Data([10, 200, 90]))
        #expect(await service.send(voice: recording, replyTo: nil, in: "deniz"))
        let sent = try #require(service.messages(in: "deniz").last)
        #expect(sent.isOutgoing)
        #expect(sent.status == .sent)
        guard case .voice(let duration, let waveform, let played) = sent.content else {
            Issue.record("voice expected")
            return
        }
        #expect(duration == 3.4)
        #expect(waveform.count == DesignFixtures.voiceWaveform.count)
        #expect(played == 0)
        #expect(service.chat("deniz")?.lastMessage?.kind == .voice)
        let payload = try #require(await service.mediaPayload(sent.id, in: "deniz"))
        #expect(payload.data == recording.audio)
        #expect(payload.contentType == VoiceRecording.contentType)
    }

    @Test func theDesignsVoiceMessagePlaysAStandIn() async throws {
        let payload = try #require(await makeService().mediaPayload("d4", in: "deniz"))
        #expect(payload.contentType == "audio/wav")
        #expect(payload.data.prefix(4) == Data("RIFF".utf8))
        let headerBytes = 44
        let sampleBytes: Int = 42 * 8_000 * 2
        #expect(payload.data.count == headerBytes + sampleBytes)
    }

    @Test func playingAReceivedVoiceMessageMarksItPlayed() async throws {
        let service = makeService()
        let id = service.addIncomingVoice(in: "deniz")
        #expect(service.messages(in: "deniz").last?.isVoicePlayed == false)
        await service.markVoicePlayed(id, in: "deniz")
        #expect(service.messages(in: "deniz").last?.isVoicePlayed == true)
        #expect(service.messages(in: "deniz").first { $0.id == "d4" }?.isVoicePlayed == true, "the design's is played")
    }

    @Test func playbackStopsWhenItsMessageGoes() async {
        let service = makeService()
        let model = ConversationViewModel(chatID: "deniz", messenger: service)
        model.voicePlayer.begin("d4", startFraction: DesignFixtures.voicePlayedFraction)
        #expect(model.voicePlayer.isLoading)
        #expect(model.voicePlayer.shownFraction(of: "d4", fallback: 0) == DesignFixtures.voicePlayedFraction)
        #expect(!model.playingVoiceIsGone)
        await service.deleteForEveryone("d4", in: "deniz")
        #expect(model.playingVoiceIsGone, "only the trace is left")
        model.stopVoicePlayback()
        #expect(model.voicePlayer.activeID == nil)
        #expect(!model.playingVoiceIsGone)
    }

    @Test func nothingPlaysWhileRecordingAndLeavingClearsBoth() {
        let model = ConversationViewModel(chatID: "deniz", messenger: makeService())
        #expect(!model.keepsScreenAwake)
        model.voice.showDemo(.locked, elapsed: 4)
        #expect(model.keepsScreenAwake, "auto-lock would end a hands-free recording")
        model.toggleVoice("d4")
        #expect(model.voicePlayer.activeID == nil, "the microphone is open")
        model.stopVoice()
        #expect(model.voice.mode == .idle)
        #expect(model.voice.recording == nil)
        #expect(model.voicePlayer.activeID == nil)
        #expect(!model.keepsScreenAwake)
    }

    @Test func aVoiceMessageThatCannotBePlayedSaysSo() {
        let model = ConversationViewModel(chatID: "deniz", messenger: makeService())
        model.voicePlayer.begin("d4")
        #expect(!model.voicePlayer.play(nil, for: "d4"))
        #expect(model.voicePlayer.activeID == nil)
        #expect(model.voice.hint == .unplayable)
    }

    @Test func thePlayedStateComesFromTheReceipt() {
        let voice = LiveAttachment(
            kind: .voice, contentType: "audio/mp4", fileName: nil, size: 9_000, durationMs: 4_200, thumbnail: nil,
            caption: nil, waveform: Data(repeating: 128, count: 100), availability: .local)
        var incoming = LiveMessageRecord(
            id: Data(repeating: 1, count: 16), direction: .incoming, body: .attachment(voice), sentAt: now,
            quoteTarget: nil, quoteText: nil)
        var outgoing = LiveMessageRecord(
            id: Data(repeating: 2, count: 16), direction: .outgoing, body: .attachment(voice), sentAt: now,
            quoteTarget: nil, quoteText: nil, status: .delivered)
        func map(_ records: [LiveMessageRecord], showsPeerPlayback: Bool = true) -> [Message] {
            LiveMapping.messages(
                records, ownUsername: "selin", peerUsername: "deniz", now: now, media: [:],
                showsPeerPlayback: showsPeerPlayback)
        }
        let fresh = map([incoming, outgoing])
        #expect(fresh.map(\.isVoicePlayed) == [false, false])
        guard case .voice(let duration, let bars, _) = fresh[0].content else {
            Issue.record("voice expected")
            return
        }
        #expect(duration == 4.2)
        #expect(bars.count == 30)
        incoming.viewedAt = now
        outgoing.viewedAt = now
        #expect(map([incoming, outgoing]).map(\.isVoicePlayed) == [true, true])
        #expect(
            map([incoming, outgoing], showsPeerPlayback: false).map(\.isVoicePlayed) == [true, false],
            "read receipts off: the other side's play is not shown")
    }
}
