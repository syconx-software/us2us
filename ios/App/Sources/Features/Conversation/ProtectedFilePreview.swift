import QuickLook
import SwiftUI
import UIKit

/// A file message previewed (proposed, 5.2 › File → Quick Look): the app's
/// own screen — the C7 header (close · the file's name · share) and Quick
/// Look's content under it. Quick Look puts its share button (Save to Files,
/// AirDrop, other apps) and markup only in its own navigation bar; embedded
/// like this it has neither, so everything that leaves the app goes through
/// the header's share button (`onShare`), which tells the sender (PROTOCOL
/// §8.4 SAVED) after the one-time explanation.
///
/// A file of a disappearing message has no share button (`onShare` is
/// `nil`): a line under the header says the file stays in the chat — as
/// copying is off for disappearing messages (5.3). What is on screen stays
/// possible: a screenshot, or text selected inside a document. Image files
/// never come here: they open in the app's own photo viewer
/// (`MediaFileKind.opensInPhotoViewer`), where a long press lifts nothing.
///
/// Follows the app's theme; fades in and out like the app's other covers.
struct ProtectedFilePreviewScreen: View {
    let title: String
    let coordinator: FilePreviewPresenter.Coordinator
    var onShare: (@MainActor () -> Void)? = nil
    let onClose: () -> Void

    var body: some View {
        U2Screen {
            VStack(spacing: 0) {
                CenteredTitleHeader(Text(verbatim: title)) {
                    U2GlassIconButton(.x, label: Text("a11y.close"), action: onClose)
                        .accessibilityIdentifier("file.protected.close")
                } trailing: {
                    if let onShare {
                        U2GlassIconButton(.export, label: Text("file.share", table: .conversation)) { onShare() }
                            .accessibilityHint(Text("file.share.hint", table: .conversation))
                            .accessibilityIdentifier("file.share")
                    } else {
                        HeaderSpacer()
                    }
                }
                if onShare == nil {
                    HStack(spacing: 6) {
                        PhosphorIcon(.timer, size: 14)
                        Text("file.protected.note", table: .conversation)
                            .u2Font(.meta)
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .foregroundStyle(Color.u2.textSecondary)
                    .padding(EdgeInsets(top: 10, leading: 24, bottom: 12, trailing: 24))
                    .accessibilityElement(children: .combine)
                } else {
                    Color.clear.frame(height: 12)
                }
                EmbeddedQuickLook(coordinator: coordinator)
                    .accessibilityIdentifier(onShare == nil ? "file.protected.preview" : "file.preview")
            }
        }
        .accessibilityAction(.escape, onClose)
        // However it went away, its copy goes (closing twice does nothing).
        .onDisappear { coordinator.finish() }
    }
}

/// Quick Look's content as a child of the screen (not presented, not in a
/// navigation controller): no bar, so no share button of its own.
private struct EmbeddedQuickLook: UIViewControllerRepresentable {
    let coordinator: FilePreviewPresenter.Coordinator

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = coordinator
        controller.delegate = coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}
}
