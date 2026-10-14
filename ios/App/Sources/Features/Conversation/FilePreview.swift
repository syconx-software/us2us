import QuickLook
import SwiftUI
import UIKit
import UniformTypeIdentifiers

// 5.2 “File → Quick Look” and “Dosyalara kaydet”: Quick Look's content in the
// app's own screen (with the app's share button — Save to Files, AirDrop,
// open in another app — so the sender is told, PROTOCOL §8.4 SAVED) and the
// Files exporter, both over a named copy in `ProtectedPreviewFiles` that is
// deleted the moment they close. A disappearing message's file gets neither
// the share button nor the exporter (`ProtectedFilePreviewScreen`).

/// Which files open in Quick Look. Everything that renders as a document
/// (PDF, images, plain text, RTF, CSV, iWork, Office, audio) does; web
/// content (HTML, XML, SVG, web archives) never does — Quick Look renders it
/// in a web view that could fetch remote resources and reveal the address.
/// Those, and whatever Quick Look cannot show, can still be saved to Files.
enum FilePreviewPolicy {
    private static let blocked: [UTType] = [.html, .xml, .svg, .webArchive, .javaScript]

    private static let allowed: [UTType] = [
        .pdf, .image, .plainText, .rtf, .rtfd, .commaSeparatedText, .tabSeparatedText, .spreadsheet, .presentation,
        .audio, .movie,
    ] + [
        "com.apple.iwork.pages.sffpages", "com.apple.iwork.numbers.sffnumbers", "com.apple.iwork.keynote.sffkey",
        "org.openxmlformats.wordprocessingml.document", "com.microsoft.word.doc",
        "org.openxmlformats.spreadsheetml.sheet", "com.microsoft.excel.xls",
        "org.openxmlformats.presentationml.presentation", "com.microsoft.powerpoint.ppt",
    ].compactMap { UTType($0) }

    static func allowsPreview(fileName: String) -> Bool {
        guard let type = UTType(filenameExtension: (fileName as NSString).pathExtension) else { return false }
        if blocked.contains(where: { type.conforms(to: $0) }) { return false }
        return allowed.contains { type.conforms(to: $0) }
    }
}

/// Quick Look over the app: no markup editing (nothing is written back),
/// the copy deleted when it closes. Over full screen, so the conversation
/// underneath stays in place.
@MainActor
enum FilePreviewPresenter {
    private static var current: (controller: UIViewController, coordinator: Coordinator)?

    /// Quick Look can show this file.
    static func canPreview(_ url: URL) -> Bool {
        FilePreviewPolicy.allowsPreview(fileName: url.lastPathComponent)
            && QLPreviewController.canPreview(PreviewItem(url: url, title: nil))
    }

    /// Quick Look inside the app's own screen (`ProtectedFilePreviewScreen`,
    /// fading in like the app's other covers) instead of on its own: Quick
    /// Look's share button lives only in its own navigation bar, so there is
    /// none — whatever leaves the app goes through `onShare` (the app's share
    /// button, which tells the sender, PROTOCOL §8.4 SAVED). Without
    /// `onShare` (a disappearing message) nothing can be handed on and the
    /// screen says the file stays in the chat. `locale`: the UI language.
    @discardableResult
    static func present(
        _ url: URL, title: String, onShare: (@MainActor () -> Void)?, locale: Locale = .current,
        onDismiss: @escaping @MainActor () -> Void
    ) -> Bool {
        dismiss(animated: false)
        guard let presenter = SystemPresenter.topViewController() else { return false }
        let coordinator = Coordinator(item: PreviewItem(url: url, title: title)) {
            current = nil
            onDismiss()
        }
        let screen = ProtectedFilePreviewScreen(title: title, coordinator: coordinator, onShare: onShare) {
            FilePreviewPresenter.dismiss()
        }
        let controller: UIViewController = UIHostingController(rootView: screen.environment(\.locale, locale))
        controller.modalTransitionStyle = .crossDissolve
        controller.modalPresentationStyle = .overFullScreen
        controller.modalPresentationCapturesStatusBarAppearance = true
        current = (controller, coordinator)
        presenter.present(controller, animated: true)
        return true
    }

    /// Closes the preview (the app went to the background, the message is gone).
    static func dismiss(animated: Bool = true) {
        guard let (controller, coordinator) = current else { return }
        current = nil
        controller.dismiss(animated: animated)
        coordinator.finish()
    }

    @MainActor
    final class Coordinator: NSObject, QLPreviewControllerDataSource, @preconcurrency QLPreviewControllerDelegate {
        private let item: PreviewItem
        private var onDismiss: (@MainActor () -> Void)?

        init(item: PreviewItem, onDismiss: @escaping @MainActor () -> Void) {
            self.item = item
            self.onDismiss = onDismiss
        }

        func finish() {
            let action = onDismiss
            onDismiss = nil
            action?()
        }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }

        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> any QLPreviewItem {
            item
        }

        func previewController(_ controller: QLPreviewController, editingModeFor previewItem: any QLPreviewItem)
            -> QLPreviewItemEditingMode
        {
            .disabled
        }

        func previewControllerDidDismiss(_ controller: QLPreviewController) {
            finish()
        }
    }

    final class PreviewItem: NSObject, QLPreviewItem, Sendable {
        let previewItemURL: URL?
        let previewItemTitle: String?

        init(url: URL, title: String?) {
            previewItemURL = url
            previewItemTitle = title
        }
    }
}

/// “Dosyalara kaydet”: the Files exporter with a copy of the file (the
/// app's own copy is deleted when it closes, saved or not).
@MainActor
enum FileExporter {
    private static var current: (controller: UIDocumentPickerViewController, coordinator: Coordinator)?

    @discardableResult
    static func present(_ url: URL, onFinish: @escaping @MainActor (_ saved: Bool) -> Void) -> Bool {
        dismiss(animated: false)
        guard let presenter = SystemPresenter.topViewController() else { return false }
        let coordinator = Coordinator { saved in
            current = nil
            onFinish(saved)
        }
        let controller = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        controller.delegate = coordinator
        current = (controller, coordinator)
        presenter.present(controller, animated: true)
        return true
    }

    static func dismiss(animated: Bool = true) {
        guard let (controller, coordinator) = current else { return }
        current = nil
        controller.dismiss(animated: animated)
        coordinator.finish(saved: false)
    }

    @MainActor
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private var onFinish: (@MainActor (Bool) -> Void)?

        init(onFinish: @escaping @MainActor (Bool) -> Void) {
            self.onFinish = onFinish
        }

        func finish(saved: Bool) {
            let action = onFinish
            onFinish = nil
            action?(saved)
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            finish(saved: true)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            finish(saved: false)
        }
    }
}

/// A file Quick Look will not show (or that failed): the floating sheet
/// says so and offers what is possible (C18 floating, 3.3's layout).
struct FileNoticeSheet: View {
    let notice: ConversationViewModel.FileNotice
    let onSave: () -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 10) {
                U2DisplayText(verbatim: title, style: .displaySheetTitle.balanced, isHeader: true)
                Text(message, table: .conversation)
                    .u2Font(.sheetBody)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 6, leading: 4, bottom: 0, trailing: 4))
            VStack(spacing: 8) {
                if notice.savableURL != nil {
                    Button(action: onSave) {
                        U2ButtonLabel(Text("file.saveToFiles", table: .conversation), glyph: .export)
                    }
                    .buttonStyle(.u2Primary)
                    .accessibilityIdentifier("file.save")
                }
                Button(action: onClose) {
                    U2ButtonLabel(notice.savableURL != nil ? Text("common.cancel") : Text("common.close"))
                }
                .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.textButtonS))
                .accessibilityIdentifier("file.notice.close")
            }
        }
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
    }

    @Environment(\.locale) private var locale

    private var title: String {
        switch notice.kind {
        case .notPreviewable, .notPreviewableDisappearing: notice.fileName
        case .openFailed: StringTable.conversation.string("file.openFailed", locale: locale)
        }
    }

    private var message: LocalizedStringKey {
        switch notice.kind {
        case .notPreviewable: "file.notPreviewable"
        case .notPreviewableDisappearing: "file.notPreviewable.disappearing"
        case .openFailed: "file.openFailed.detail"
        }
    }

}
