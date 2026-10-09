import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// The system photo and document pickers for “+” (live service). Both run
/// out of process: the photo picker needs no photo-library permission and
/// hands over only what the user picked. The picked file is copied into a
/// protected temporary directory (complete file protection) that the runtime
/// prepares it from (metadata stripped, re-encoded) and that is emptied on
/// every pick.
@MainActor
enum SystemPickers {
    static func pickPhoto() async -> URL? {
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1
        configuration.preferredAssetRepresentationMode = .compatible
        let picker = PHPickerViewController(configuration: configuration)
        let delegate = PhotoDelegate()
        picker.delegate = delegate
        return await withCheckedContinuation { continuation in
            delegate.continuation = continuation
            guard present(picker) else {
                delegate.finish(nil)
                return
            }
            objc_setAssociatedObject(picker, &delegateKey, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        }
    }

    static func pickDocument() async -> URL? {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.allowsMultipleSelection = false
        let delegate = DocumentDelegate()
        picker.delegate = delegate
        return await withCheckedContinuation { continuation in
            delegate.continuation = continuation
            guard present(picker) else {
                delegate.finish(nil)
                return
            }
            objc_setAssociatedObject(picker, &delegateKey, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        }
    }

    private nonisolated(unsafe) static var delegateKey: UInt8 = 0

    private static func present(_ controller: UIViewController) -> Bool {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let window = scene?.windows.first(where: { $0.windowLevel == .normal && !$0.isHidden }),
            var top = window.rootViewController
        else { return false }
        while let presented = top.presentedViewController { top = presented }
        top.present(controller, animated: true)
        return true
    }

    /// A fresh protected directory for the picked copy.
    nonisolated static func pickDirectory() -> URL? {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("picked", isDirectory: true)
        try? FileManager.default.removeItem(at: directory)
        do {
            try FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete])
        } catch {
            return nil
        }
        return directory
    }

    nonisolated static func copyIntoPickDirectory(_ url: URL, name: String? = nil) -> URL? {
        guard let directory = pickDirectory() else { return nil }
        let destination = directory.appendingPathComponent(name ?? url.lastPathComponent)
        do {
            try FileManager.default.copyItem(at: url, to: destination)
        } catch {
            return nil
        }
        return destination
    }

    @MainActor private final class PhotoDelegate: NSObject, PHPickerViewControllerDelegate {
        var continuation: CheckedContinuation<URL?, Never>?

        func finish(_ url: URL?) {
            continuation?.resume(returning: url)
            continuation = nil
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            guard let provider = results.first?.itemProvider,
                provider.hasItemConformingToTypeIdentifier(UTType.image.identifier)
            else {
                finish(nil)
                return
            }
            provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
                // The provider's file goes away when this returns: copy it now.
                let copy = url.flatMap { SystemPickers.copyIntoPickDirectory($0) }
                Task { @MainActor in self.finish(copy) }
            }
        }
    }

    @MainActor private final class DocumentDelegate: NSObject, UIDocumentPickerDelegate {
        var continuation: CheckedContinuation<URL?, Never>?

        func finish(_ url: URL?) {
            continuation?.resume(returning: url)
            continuation = nil
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            finish(urls.first.flatMap { SystemPickers.copyIntoPickDirectory($0) })
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            finish(nil)
        }
    }
}
