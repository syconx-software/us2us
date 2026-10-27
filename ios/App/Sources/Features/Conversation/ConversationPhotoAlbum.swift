import SwiftUI
import UIKit

/// A compact bubble with independently accessible photos and their original message actions.
struct ConversationPhotoAlbum: View {
    let row: ConversationRow
    let viewModel: ConversationViewModel

    private var columns: Int { row.album.count >= 5 ? 3 : 2 }
    private var edge: CGFloat { (258 - 3 * CGFloat(columns - 1)) / CGFloat(columns) }

    var body: some View {
        MessageBubble(direction: row.message.direction, isTail: row.isTail,
            padding: EdgeInsets(top: 5, leading: 5, bottom: 8, trailing: 5)) {
            VStack(alignment: .leading, spacing: 3) {
                ForEach(0..<((row.album.count + columns - 1) / columns), id: \.self) { line in
                    let items = Array(row.album.dropFirst(line * columns).prefix(columns))
                    HStack(spacing: 3) {
                        ForEach(items) { message in
                            ConversationPhotoTile(message: message, width: (258 - 3 * CGFloat(items.count - 1)) / CGFloat(items.count),
                                edge: edge, index: row.album.firstIndex(of: message) ?? 0,
                                count: row.album.count, isTail: row.isTail, viewModel: viewModel)
                        }
                    }
                }
            }
            if case .photo(_, let caption) = row.message.content, let caption, !caption.isEmpty {
                BubbleCaption(text: caption, isOutgoing: row.message.isOutgoing)
                    .padding(.horizontal, 8)
                    .frame(maxWidth: 258, alignment: .leading)
            }
            BubbleMeta(time: row.album.last!.sentAt, status: ConversationPhotos.status(row.album))
                .padding(.trailing, 8).u2BubbleTrailing()
        }
        .accessibilityIdentifier("photoAlbum.\(row.id)")
    }
}

private struct ConversationPhotoTile: View {
    let message: Message
    let width: CGFloat
    let edge: CGFloat
    let index: Int
    let count: Int
    let isTail: Bool
    let viewModel: ConversationViewModel
    @Environment(\.locale) private var locale

    var body: some View {
        if case .photo(let source, _) = message.content {
            ZStack {
                BubbleMediaView(source: source, size: CGSize(width: width, height: edge))
                    .frame(width: width, height: edge)
                    .overlay {
                        if !message.mediaAvailability.isReady {
                            MediaGlassCircle { MediaStateGlyph(availability: message.mediaAvailability, readyGlyph: .none) }
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: U2Radius.bubbleMedia, style: .continuous))
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                PhotoAlbumTouchTarget(
                    label: String(format: StringTable.conversation.string("album.photo %lld %lld", locale: locale),
                        locale: locale, Int64(index + 1), Int64(count)),
                    state: stateDescription, identifier: "message.\(message.id)",
                    actionsLabel: StringTable.conversation.string("a11y.message.actions", locale: locale),
                    onTap: { if let current = viewModel.message(message.id) { viewModel.openMedia(current) } },
                    onLongPress: { frame in
                        guard let current = viewModel.message(message.id) else { return }
                        viewModel.frames.set(frame, for: message.id)
                        viewModel.openActions(for: current, isTail: isTail)
                    })
            }
            .frame(width: width, height: edge)
            .opacity(viewModel.actions?.messageID == message.id ? 0 : 1)
        }
    }

    private var stateDescription: String {
        let key: String.LocalizationValue
        switch message.mediaAvailability {
        case .ready: return ""
        case .remote: key = "a11y.media.remote"
        case .downloading: key = "a11y.media.downloading"
        case .held: key = "file.held"
        case .unavailable: key = "file.unavailable"
        case .preparing: key = "video.preparing"
        case .preparationFailed: key = "video.preparationFailed"
        }
        return StringTable.conversation.string(key, locale: locale)
    }
}

/// UIKit recognizer failure ordering keeps tap and long press exclusive,
/// while a drag still belongs to the surrounding conversation scroll view.
private struct PhotoAlbumTouchTarget: UIViewRepresentable {
    let label: String
    let state: String
    let identifier: String
    let actionsLabel: String
    let onTap: () -> Void
    let onLongPress: (CGRect) -> Void

    func makeUIView(context: Context) -> PhotoAlbumTouchView { PhotoAlbumTouchView() }
    func updateUIView(_ view: PhotoAlbumTouchView, context: Context) {
        view.onTap = onTap
        view.onLongPress = onLongPress
        view.accessibilityLabel = label
        view.accessibilityValue = state
        view.accessibilityIdentifier = identifier
        view.accessibilityCustomActions = [UIAccessibilityCustomAction(name: actionsLabel) { [weak view] _ in
            view?.openActions()
            return view != nil
        }]
    }
}

private final class PhotoAlbumTouchView: UIView {
    var onTap: () -> Void = {}
    var onLongPress: (CGRect) -> Void = { _ in }

    init() {
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityTraits = [.button]
        let press = UILongPressGestureRecognizer(target: self, action: #selector(pressed(_:)))
        press.minimumPressDuration = 0.4
        press.allowableMovement = 12
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped))
        tap.require(toFail: press)
        addGestureRecognizer(press)
        addGestureRecognizer(tap)
    }

    required init?(coder: NSCoder) { nil }
    @objc private func tapped() { onTap() }
    @objc private func pressed(_ recognizer: UILongPressGestureRecognizer) {
        if recognizer.state == .began { openActions() }
    }
    func openActions() { onLongPress(convert(bounds, to: nil)) }
    override func accessibilityActivate() -> Bool { onTap(); return true }
}
