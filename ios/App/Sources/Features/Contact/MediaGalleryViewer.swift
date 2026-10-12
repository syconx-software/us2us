import SwiftUI
import UIKit

/// The contact screen's photos and videos full screen (proposed): the photo
/// viewer and the player as pages, swiped sideways in the grid's order
/// (newest first — a swipe to the left goes back in time). Each page keeps
/// its own chrome and gestures: a tap shows or hides it, pinch and
/// double-tap zoom a photo, a swipe up or down closes; “Kaydet” saves the
/// page on screen (the sender is told). Only the page on screen plays — the
/// player is made when its page arrives and lets go of its clip when it
/// leaves; the next page of the grid loads as the end comes near. A page
/// whose message goes (deleted, expired) closes the gallery.
struct MediaGalleryViewer: View {
    let model: ContactMediaModel
    let gallery: ContactMediaModel.GalleryItem
    var themeScheme: ColorScheme = .dark
    let onClose: () -> Void

    @State private var currentID: MessageID?

    var body: some View {
        let pages = model.galleryPages(gallery)
        let current = currentID ?? gallery.startID
        TabView(selection: $currentID) {
            ForEach(pages) { message in
                page(message, isCurrent: message.id == current)
                    .tag(Optional(message.id))
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .ignoresSafeArea()
        .onAppear { if currentID == nil { currentID = gallery.startID } }
        .onChange(of: currentID) { _, id in
            if let id, gallery.pages == .visual { model.itemAppeared(id, in: .visual) }
        }
        .onChange(of: pages.map(\.id)) { _, ids in
            if !ids.contains(current) { onClose() }
        }
        .accessibilityAction(.escape, onClose)
    }

    @ViewBuilder private func page(_ message: Message, isCurrent: Bool) -> some View {
        let sender = model.sender(of: message)
        switch message.content {
        case .photo(let source, let caption):
            MediaViewer(
                item: ConversationViewModel.ViewerItem(
                    kind: .photo, messageID: message.id, sender: sender, sentAt: message.sentAt, media: source,
                    isVideo: false, caption: caption),
                viewModel: model, themeScheme: themeScheme, onClose: onClose
            )
            .accessibilityHidden(!isCurrent)
        case .video(let poster, let duration, _):
            if isCurrent {
                VideoPlayerScreen(
                    item: ConversationViewModel.ViewerItem(
                        kind: .video, messageID: message.id, sender: sender, sentAt: message.sentAt, media: poster,
                        isVideo: true, duration: duration),
                    viewModel: model, themeScheme: themeScheme, onClose: onClose
                )
                .id(message.id)
            } else {
                VideoPosterPage(poster: poster)
            }
        case .file(let name, _, _, _) where MediaFileKind.opensInPhotoViewer(fileName: name):
            // An image sent as a file (Belgeler): the photo viewer, only itself.
            MediaViewer(
                item: ConversationViewModel.ViewerItem(
                    kind: .photo, messageID: message.id, sender: sender, sentAt: message.sentAt, media: .memory(Data()),
                    isVideo: false),
                viewModel: model, themeScheme: themeScheme, onClose: onClose
            )
            .accessibilityHidden(!isCurrent)
        case .file:
            // A video sent as a file (Belgeler): only itself.
            if isCurrent {
                VideoPlayerScreen(
                    item: ConversationViewModel.ViewerItem(
                        kind: .video, messageID: message.id, sender: sender, sentAt: message.sentAt,
                        media: .memory(Data()), isVideo: true),
                    viewModel: model, themeScheme: themeScheme, onClose: onClose
                )
                .id(message.id)
            } else {
                VideoPosterPage(poster: .memory(Data()))
            }
        default:
            EmptyView()
        }
    }
}

/// A video's page while it is not the one on screen: its poster, fitted, on
/// the viewer's backdrop — what the player shows first, so the swipe ends
/// without a jump when the player takes over.
private struct VideoPosterPage: View {
    let poster: MediaSource
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.u2.mediaViewerBg
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
        .task { image = await MediaImageDecoder.image(from: poster, maxPixel: 1_280) }
    }
}
