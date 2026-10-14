@preconcurrency import AVFoundation
import ImageIO
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// A photo picked or taken for the profile picture, ready to be framed:
/// the orientation applied to the pixels and at most 2048 px on its longer
/// side, held in memory only — no file is written, and the picked original
/// is not kept. `jpeg` is the same pixels with nothing else (no EXIF, no
/// location), which the messenger crops, scales to ≤ 256 px and re-encodes.
struct AvatarSourceImage: Identifiable, Equatable, @unchecked Sendable {
    /// The longer side a picked photo is reduced to before framing.
    static let maxSide = 2_048

    let id = UUID()
    let image: CGImage
    let jpeg: Data

    var pixelSize: CGSize { CGSize(width: image.width, height: image.height) }

    static func == (lhs: AvatarSourceImage, rhs: AvatarSourceImage) -> Bool { lhs.id == rhs.id }

    /// From a picked file's bytes (any format ImageIO reads): the system's
    /// downsampling decoder applies the orientation and never decodes more
    /// than `maxSide`.
    nonisolated static func make(from data: Data) -> AvatarSourceImage? {
        guard
            let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
            CGImageSourceGetCount(source) > 0,
            let image = CGImageSourceCreateThumbnailAtIndex(
                source, 0,
                [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: maxSide,
                    kCGImageSourceShouldCacheImmediately: true,
                ] as CFDictionary)
        else { return nil }
        return make(from: image)
    }

    /// From a camera photo: drawn upright at ≤ `maxSide`.
    nonisolated static func make(from photo: UIImage) -> AvatarSourceImage? {
        let pixels = CGSize(width: photo.size.width * photo.scale, height: photo.size.height * photo.scale)
        guard pixels.width >= 1, pixels.height >= 1 else { return nil }
        let factor = min(1, CGFloat(maxSide) / max(pixels.width, pixels.height))
        let target = CGSize(width: (pixels.width * factor).rounded(), height: (pixels.height * factor).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let upright = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            photo.draw(in: CGRect(origin: .zero, size: target))
        }
        guard let image = upright.cgImage else { return nil }
        return make(from: image)
    }

    /// The pixels as a JPEG with no properties at all.
    nonisolated private static func make(from image: CGImage) -> AvatarSourceImage? {
        let output = NSMutableData()
        guard
            let destination = CGImageDestinationCreateWithData(
                output as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.92] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return AvatarSourceImage(image: image, jpeg: output as Data)
    }
}

/// Where a profile photo comes from (7.1 › Profil görseli): the system photo
/// picker and the camera. Both are replaceable, so the editor's model runs in
/// tests without UIKit.
struct AvatarPhotoSourcing {
    enum CameraAccess: Equatable {
        case available
        /// The user turned camera access off for us2us (Settings).
        case denied
        /// Restricted on this device (Screen Time, a device profile).
        case restricted
        /// No camera (the simulator).
        case unavailable
    }

    /// The picked photo, or `nil` when the picker was cancelled or the file
    /// could not be read.
    var pick: @MainActor () async -> AvatarSourceImage?
    /// Whether the camera can be opened (asks for access the first time).
    var cameraAccess: @MainActor () async -> CameraAccess
    /// The photo taken, or `nil` when the camera was closed.
    var take: @MainActor () async -> AvatarSourceImage?
    /// Camera access is off: why, and the way to Settings.
    var explainCameraDenied: @MainActor (_ locale: Locale) -> Void = { _ in }

    @MainActor static let system = AvatarPhotoSourcing(
        pick: { await AvatarSystemPickers.pickPhoto() },
        cameraAccess: { await AvatarSystemPickers.cameraAccess() },
        take: { await AvatarSystemPickers.takePhoto() },
        explainCameraDenied: { locale in CameraAccessSheet.present(locale: locale) })
}

/// The system photo picker (out of process: no photo-library permission;
/// one photo, handed over as bytes in memory) and the camera
/// (`UIImagePickerController`, still photos only; nothing is saved to the
/// library).
@MainActor
enum AvatarSystemPickers {
    static func pickPhoto() async -> AvatarSourceImage? {
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1
        configuration.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: configuration)
        let delegate = PhotoDelegate()
        picker.delegate = delegate
        return await run(picker, delegate: delegate)
    }

    static func cameraAccess() async -> AvatarPhotoSourcing.CameraAccess {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else { return .unavailable }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return .available
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video) ? .available : .denied
        case .denied: return .denied
        case .restricted: return .restricted
        @unknown default: return .unavailable
        }
    }

    static func takePhoto() async -> AvatarSourceImage? {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else { return nil }
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = [UTType.image.identifier]
        picker.cameraCaptureMode = .photo
        if UIImagePickerController.isCameraDeviceAvailable(.front) { picker.cameraDevice = .front }
        picker.allowsEditing = false
        let delegate = CameraDelegate()
        picker.delegate = delegate
        return await run(picker, delegate: delegate)
    }

    private nonisolated(unsafe) static var delegateKey: UInt8 = 0

    /// Presents the picker from the top of the window (over “Profil görseli”'s
    /// cover), as soon as nothing there is moving — on the first tap, never
    /// dropped — and waits for its result. `nil` when it could not be shown.
    private static func run(_ controller: UIViewController, delegate: PickerDelegate) async -> AvatarSourceImage? {
        // UIKit holds delegates weakly: the picker keeps its own.
        objc_setAssociatedObject(controller, &delegateKey, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        return await withCheckedContinuation { continuation in
            delegate.continuation = continuation
            Task { @MainActor in
                let shown = await SystemPresenter.present(controller)
                if !shown { delegate.finish(nil) }
            }
        }
    }

    /// Hands the result over once the picker is completely gone *and* the
    /// photo is read, so the framing screen never rises while the picker is
    /// still going down.
    @MainActor private class PickerDelegate: NSObject {
        var continuation: CheckedContinuation<AvatarSourceImage?, Never>?
        private var isDismissed = false
        private var result: AvatarSourceImage??

        func finish(_ image: AvatarSourceImage?) {
            continuation?.resume(returning: image)
            continuation = nil
        }

        func dismiss(_ controller: UIViewController) {
            controller.dismiss(animated: true) {
                MainActor.assumeIsolated { self.didDismiss() }
            }
        }

        private func didDismiss() {
            isDismissed = true
            finishIfReady()
        }

        func deliver(_ image: AvatarSourceImage?) {
            result = .some(image)
            finishIfReady()
        }

        private func finishIfReady() {
            guard isDismissed, let result else { return }
            finish(result)
        }
    }

    @MainActor private final class PhotoDelegate: PickerDelegate, PHPickerViewControllerDelegate {
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            dismiss(picker)
            guard let provider = results.first?.itemProvider,
                provider.hasItemConformingToTypeIdentifier(UTType.image.identifier)
            else {
                deliver(nil)
                return
            }
            // The bytes in memory (no file of ours), read and reduced off the main thread.
            provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                let image = data.flatMap { AvatarSourceImage.make(from: $0) }
                Task { @MainActor in self.deliver(image) }
            }
        }
    }

    @MainActor private final class CameraDelegate: PickerDelegate, UIImagePickerControllerDelegate,
        UINavigationControllerDelegate
    {
        func imagePickerController(
            _ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            dismiss(picker)
            guard let photo = info[.originalImage] as? UIImage else {
                deliver(nil)
                return
            }
            Task { @MainActor in
                let image = await Task.detached(priority: .userInitiated) { AvatarSourceImage.make(from: photo) }.value
                self.deliver(image)
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            dismiss(picker)
            deliver(nil)
        }
    }
}

/// Camera access is off (7.1 › Profil görseli › Kamera): why, and the way to
/// Settings — the voice recorder's and Photos' permission sheet, for the
/// camera.
struct CameraAccessSheet: View {
    let onClose: @MainActor () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 10) {
                U2DisplayText(
                    StringTable.settings.resource("avatar.camera.denied.title"), style: .displaySheetTitle.balanced)
                Text("avatar.camera.denied.body", table: .settings)
                    .u2Font(.sheetBody)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 6, leading: 4, bottom: 0, trailing: 4))
            VStack(spacing: 8) {
                Button {
                    onClose()
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                } label: {
                    U2ButtonLabel(Text("avatar.camera.settings", table: .settings), glyph: .gearSix)
                }
                .buttonStyle(.u2Primary)
                .accessibilityIdentifier("avatar.camera.settings")
                Button(action: onClose) {
                    U2ButtonLabel(Text("common.close"))
                }
                .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.textButtonS))
                .accessibilityIdentifier("avatar.camera.close")
            }
        }
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
    }

    @MainActor
    static func present(locale: Locale) {
        U2FloatingSheetPresenter.present(locale: locale) { close in
            CameraAccessSheet(onClose: close)
        } onDismiss: {}
    }
}
