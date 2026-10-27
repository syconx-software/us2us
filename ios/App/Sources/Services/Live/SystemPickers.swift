import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// The system photo (photos and videos) and document pickers for “+” (live service). Both run
/// out of process: the photo picker needs no photo-library permission and
/// hands over only what the user picked — up to ten photos and videos at a
/// time, numbered in the order they are selected. Each picked file is copied
/// into a protected temporary folder of its own (complete file protection,
/// `tmp/picked/<random>/`) that the runtime prepares it from (metadata
/// stripped, re-encoded). The copy goes with its folder once it was sent or
/// given up (`discardPicked`) — an item being prepared while the next pick
/// comes in keeps its own — and every copy at launch and on “Her şeyi sil”
/// (`removeAllPicked`).
@MainActor
enum SystemPickers {
    /// The photo picker's configuration: photos and videos, at most
    /// `PickedMediaPlan.selectionLimit` (one for view-once), numbered in the
    /// order they are selected (`.ordered`: the results come back in that
    /// order). No photo library is passed, so the picker runs out of process
    /// without any photo-library permission and hands over files only — no
    /// asset identifiers.
    static func mediaConfiguration(viewOnce: Bool = false) -> PHPickerConfiguration {
        var configuration = PHPickerConfiguration()
        configuration.filter = .any(of: [.images, .videos])
        configuration.selectionLimit = PickedMediaPlan.limit(viewOnce: viewOnce)
        configuration.selection = .ordered
        // The file as it is: the runtime re-encodes photos and videos anyway
        // (and strips their metadata), so the picker's own conversion to a
        // “compatible” format would only add a wait before that.
        configuration.preferredAssetRepresentationMode = .current
        return configuration
    }

    /// Photos and videos from the library, in the order they were selected
    /// (empty when the picker was cancelled). They come back as soon as they
    /// are picked, each copy still being made, so every bubble can show at
    /// once; the copies are made side by side by the system (files only,
    /// nothing is decoded here).
    static func pickMedia(viewOnce: Bool = false) async -> [PickedMediaItem] {
        let configuration = mediaConfiguration(viewOnce: viewOnce)
        let picker = PHPickerViewController(configuration: configuration)
        let delegate = PhotoDelegate(limit: configuration.selectionLimit)
        picker.delegate = delegate
        return await withCheckedContinuation { continuation in
            delegate.continuation = continuation
            guard let token = present(picker, cancel: { [weak delegate, weak picker] in
                delegate?.finish([])
                picker?.dismiss(animated: false)
            }) else {
                delegate.finish([])
                return
            }
            delegate.presentation = token
            objc_setAssociatedObject(picker, &delegateKey, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        }
    }

    /// Picked items that are not going to be sent: their copies go as soon
    /// as they are here.
    static func discard(_ items: some Sequence<PickedMediaItem>) {
        for item in items {
            guard case .copy(let copy) = item.source else { continue }
            Task {
                if let url = await copy.value { SystemPickers.discardPicked(url) }
            }
        }
    }

    static func pickDocument() async -> URL? {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.allowsMultipleSelection = false
        let delegate = DocumentDelegate()
        picker.delegate = delegate
        return await withCheckedContinuation { continuation in
            delegate.continuation = continuation
            guard let token = present(picker, cancel: { [weak delegate, weak picker] in
                delegate?.finish(nil)
                picker?.dismiss(animated: false)
            }) else {
                delegate.finish(nil)
                return
            }
            delegate.presentation = token
            objc_setAssociatedObject(picker, &delegateKey, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        }
    }

    private nonisolated(unsafe) static var delegateKey: UInt8 = 0

    private static func present(_ controller: UIViewController, cancel: @escaping () -> Void) -> UUID? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let window = scene?.windows.first(where: { $0.windowLevel == .normal && !$0.isHidden }),
            var top = window.rootViewController
        else { return nil }
        while let presented = top.presentedViewController { top = presented }
        guard scene?.activationState == .foregroundActive, top.viewIfLoaded?.window != nil,
              !top.isBeingPresented, !top.isBeingDismissed,
              let token = SystemPickerActivity.shared.begin(cancel: cancel) else { return nil }
        controller.modalPresentationStyle = .fullScreen
        top.present(controller, animated: true)
        return token
    }

    /// Keep the activity alive through the closing transition, so late SwiftUI disappearance
    /// callbacks cannot close the community between selection and sending.
    private static func dismiss(_ controller: UIViewController, completion: @escaping () -> Void) {
        if controller.isBeingDismissed, let transition = controller.transitionCoordinator {
            transition.animate(alongsideTransition: nil) { _ in completion() }
        } else if controller.presentingViewController != nil {
            controller.dismiss(animated: true, completion: completion)
        } else { completion() }
    }

    /// Where picked copies live, one folder per pick.
    nonisolated static var pickRoot: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("picked", isDirectory: true)
    }

    /// A fresh protected folder for one picked copy.
    nonisolated static func pickDirectory() -> URL? {
        let manager = FileManager.default
        let directory = pickRoot.appendingPathComponent(UUID().uuidString, isDirectory: true)
        do {
            try manager.createDirectory(
                at: pickRoot, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete])
            try manager.createDirectory(
                at: directory, withIntermediateDirectories: false,
                attributes: [.protectionKey: FileProtectionType.complete])
        } catch {
            return nil
        }
        return directory
    }

    /// The picked copy was sent or given up: it goes, with its folder.
    nonisolated static func discardPicked(_ url: URL) {
        let folder = url.deletingLastPathComponent().standardizedFileURL
        if folder.deletingLastPathComponent().path == pickRoot.standardizedFileURL.path {
            try? FileManager.default.removeItem(at: folder)
        } else {
            try? FileManager.default.removeItem(at: url)
        }
    }

    /// Every picked copy (launch, “Her şeyi sil”).
    nonisolated static func removeAllPicked() {
        try? FileManager.default.removeItem(at: pickRoot)
    }

    nonisolated static func copyIntoPickDirectory(_ url: URL, name: String? = nil) -> URL? {
        guard let directory = pickDirectory() else { return nil }
        let destination = directory.appendingPathComponent(name ?? url.lastPathComponent)
        do {
            try FileManager.default.copyItem(at: url, to: destination)
        } catch {
            try? FileManager.default.removeItem(at: directory)
            return nil
        }
        return destination
    }

    /// A file the app was handed as its own copy (the document picker's
    /// `asCopy`) moves into a fresh protected folder; if it cannot be moved
    /// it is copied, and the original goes either way.
    nonisolated static func moveIntoPickDirectory(_ url: URL) -> URL? {
        defer { try? FileManager.default.removeItem(at: url) }
        guard let directory = pickDirectory() else { return nil }
        let destination = directory.appendingPathComponent(url.lastPathComponent)
        do {
            try FileManager.default.moveItem(at: url, to: destination)
        } catch {
            do {
                try FileManager.default.copyItem(at: url, to: destination)
            } catch {
                try? FileManager.default.removeItem(at: directory)
                return nil
            }
        }
        // A moved file keeps the protection it had: complete, like the folder.
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.complete], ofItemAtPath: destination.path)
        return destination
    }

    @MainActor private final class PhotoDelegate: NSObject, PHPickerViewControllerDelegate {
        let limit: Int
        var continuation: CheckedContinuation<[PickedMediaItem], Never>?
        var presentation: UUID?

        init(limit: Int) {
            self.limit = limit
        }

        func finish(_ items: [PickedMediaItem]) {
            guard let continuation else { SystemPickers.discard(items); return }
            self.continuation = nil
            SystemPickerActivity.shared.end(presentation)
            continuation.resume(returning: items)
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            var items: [PickedMediaItem] = []
            for result in results.prefix(limit) {
                let provider = result.itemProvider
                let isVideo = provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier)
                guard isVideo || provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) else { continue }
                let type = isVideo ? UTType.movie.identifier : UTType.image.identifier
                let copy = Self.pickedCopy(from: provider, type: type)
                items.append(PickedMediaItem(kind: isVideo ? .video : .photo, source: .copy(copy)))
            }
            SystemPickers.dismiss(picker) { self.finish(items) }
        }

        /// The provider's file, copied into a protected folder of its own.
        private static func pickedCopy(from provider: NSItemProvider, type: String) -> Task<URL?, Never> {
            let (copied, delivery) = AsyncStream<URL?>.makeStream()
            provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                // The provider's file goes away when this returns: copy it now
                // (this runs off the main thread).
                delivery.yield(url.flatMap { SystemPickers.copyIntoPickDirectory($0) })
                delivery.finish()
            }
            return Task<URL?, Never> {
                for await url in copied { return url }
                return nil
            }
        }
    }

    @MainActor private final class DocumentDelegate: NSObject, UIDocumentPickerDelegate {
        var continuation: CheckedContinuation<URL?, Never>?
        var presentation: UUID?

        func finish(_ url: URL?) {
            guard let continuation else {
                if let url { SystemPickers.discardPicked(url) }
                return
            }
            self.continuation = nil
            SystemPickerActivity.shared.end(presentation)
            continuation.resume(returning: url)
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let picked = urls.first else {
                SystemPickers.dismiss(controller) { self.finish(nil) }
                return
            }
            // The picker's own copy (`asCopy`) moves into the protected
            // folder — off the main thread, and no second plaintext copy
            // stays behind in the app's inbox.
            Task { @MainActor in
                let url = await Task.detached(priority: .userInitiated) {
                    SystemPickers.moveIntoPickDirectory(picked)
                }.value
                guard self.continuation != nil else {
                    if let url { SystemPickers.discardPicked(url) }
                    return
                }
                SystemPickers.dismiss(controller) { self.finish(url) }
            }
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            SystemPickers.dismiss(controller) { self.finish(nil) }
        }
    }
}
