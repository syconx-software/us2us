import SwiftUI

/// Conversation order, with only the visible photo decrypted and decoded at viewer resolution.
struct ConversationPhotoViewer: View {
    let item: ConversationViewModel.ViewerItem
    let viewModel: ConversationViewModel
    let themeScheme: ColorScheme
    let onClose: () -> Void
    @State private var selectedID: MessageID?

    var body: some View {
        let photos = viewModel.messages.filter(ConversationPhotos.isBrowsable)
        let currentID = selectedID ?? item.messageID
        if let index = photos.firstIndex(where: { $0.id == currentID }) {
            let message = photos[index]
            if case .photo(let source, let caption) = message.content {
                MediaViewer(item: ConversationViewModel.ViewerItem(kind: .photo, messageID: message.id,
                    sender: viewModel.senderName(of: message), sentAt: message.sentAt, media: source, isVideo: false,
                    caption: caption, startsZoomed: message.id == item.messageID && item.startsZoomed),
                    viewModel: viewModel, themeScheme: themeScheme, onClose: onClose,
                    photoPosition: index, photoCount: photos.count,
                    onPrevious: index > 0 ? { selectedID = photos[index - 1].id } : nil,
                    onNext: index + 1 < photos.count ? { selectedID = photos[index + 1].id } : nil)
                    .id(message.id)
            }
        } else if selectedID == nil, let id = item.messageID,
            let message = viewModel.message(id), case .file = message.content {
            MediaViewer(item: item, viewModel: viewModel, themeScheme: themeScheme, onClose: onClose)
        } else {
            Color.u2.mediaViewerBg.ignoresSafeArea().onAppear(perform: onClose)
        }
    }
}
