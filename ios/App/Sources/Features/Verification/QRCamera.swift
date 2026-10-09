@preconcurrency import AVFoundation
import SwiftUI
import UIKit

/// The camera of 6.2 — the only place the app opens it (design note: “the
/// camera opens only on this screen; the image never leaves the device”).
///
/// * Metadata output only (QR); no photo/video output, no frames kept, nothing
///   logged — the decoded text goes straight to the comparison.
/// * Runs only while 6.2 is scanning, visible and the app active; stopped on
///   close, on “Kendi kodumu göster”, and the moment the app goes inactive
///   (the privacy cover and the lock come up over a stopped camera).
/// * The session lives on its own serial queue (`startRunning` blocks).
///
/// On the simulator (no camera) the screen shows the design's placeholder
/// instead (`status == .placeholder`).
@Observable
@MainActor
final class QRCameraModel {
    enum Status: Equatable {
        /// Not started yet.
        case idle
        /// Waiting for the system camera prompt.
        case requesting
        case running
        /// Access denied or restricted → “Kamera izni gerekli”.
        case denied
        /// No usable camera.
        case unavailable
        /// The simulator: the design's static camera placeholder.
        case placeholder
    }

    private(set) var status: Status = .idle
    private(set) var torchAvailable = false
    private(set) var torchOn = false

    @ObservationIgnored let capture = QRCaptureSession()
    @ObservationIgnored private var wantsRunning = false

    /// Called on the main actor with each QR text the camera reads.
    var onCode: ((String) -> Void)? {
        get { capture.onCode }
        set { capture.onCode = newValue }
    }

    static var isSimulator: Bool {
        #if targetEnvironment(simulator)
            true
        #else
            false
        #endif
    }

    /// Starts scanning (asks for access the first time).
    func start() async {
        wantsRunning = true
        if Self.isSimulator {
            status = .placeholder
            torchAvailable = true
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            break
        case .notDetermined:
            status = .requesting
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            guard granted else {
                status = .denied
                return
            }
        default:
            status = .denied
            return
        }
        guard wantsRunning else { return }
        let started = await capture.start()
        guard wantsRunning else {
            capture.stop()
            return
        }
        status = started ? .running : .unavailable
        torchAvailable = started && capture.hasTorch
    }

    /// Stops the camera (and the torch) immediately.
    func stop() {
        wantsRunning = false
        torchOn = false
        if !Self.isSimulator {
            capture.stop()
            if status == .running { status = .idle }
        }
    }

    func setTorch(_ on: Bool) {
        guard torchAvailable else { return }
        torchOn = on
        if !Self.isSimulator { capture.setTorch(on) }
    }

    #if DEBUG
        /// Harness: show the permission-denied state.
        func forceStatus(_ status: Status) {
            self.status = status
        }
    #endif
}

/// AVFoundation plumbing for `QRCameraModel`. All session work happens on
/// `queue`; the delegate is called on the main queue.
final class QRCaptureSession: NSObject, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "com.syconx.us2us.qr-camera")
    private var isConfigured = false
    private var device: AVCaptureDevice?
    @MainActor var onCode: ((String) -> Void)?

    var hasTorch: Bool { device?.hasTorch ?? false }

    func start() async -> Bool {
        await withCheckedContinuation { continuation in
            queue.async { [self] in
                if !isConfigured { isConfigured = configure() }
                guard isConfigured else {
                    continuation.resume(returning: false)
                    return
                }
                if !session.isRunning { session.startRunning() }
                continuation.resume(returning: session.isRunning)
            }
        }
    }

    func stop() {
        queue.async { [self] in
            applyTorch(false)
            if session.isRunning { session.stopRunning() }
        }
    }

    func setTorch(_ on: Bool) {
        queue.async { [self] in applyTorch(on) }
    }

    private func applyTorch(_ on: Bool) {
        guard let device, device.hasTorch, (try? device.lockForConfiguration()) != nil else { return }
        if on, device.isTorchModeSupported(.on) {
            try? device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel)
        } else if device.isTorchModeSupported(.off) {
            device.torchMode = .off
        }
        device.unlockForConfiguration()
    }

    private func configure() -> Bool {
        guard
            let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: camera)
        else { return false }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        if session.canSetSessionPreset(.high) { session.sessionPreset = .high }
        guard session.canAddInput(input) else { return false }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return false }
        session.addOutput(output)
        guard output.availableMetadataObjectTypes.contains(.qr) else { return false }
        output.metadataObjectTypes = [.qr]
        output.setMetadataObjectsDelegate(self, queue: .main)
        if (try? camera.lockForConfiguration()) != nil {
            // Codes are held close to the lens.
            if camera.isAutoFocusRangeRestrictionSupported { camera.autoFocusRangeRestriction = .near }
            if camera.isFocusModeSupported(.continuousAutoFocus) { camera.focusMode = .continuousAutoFocus }
            camera.unlockForConfiguration()
        }
        device = camera
        return true
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection
    ) {
        let code = metadataObjects
            .compactMap { $0 as? AVMetadataMachineReadableCodeObject }
            .first { $0.type == .qr }?
            .stringValue
        guard let code else { return }
        MainActor.assumeIsolated { onCode?(code) }
    }
}

/// The live camera image, aspect-filling the screen.
struct QRCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.backgroundColor = .clear
        view.isAccessibilityElement = false
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        // swiftlint:disable:next force_cast
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}
