import Foundation

/// What the photo viewer and the player need from where they were opened:
/// the conversation (5.1, one message) or the contact screen's photos
/// (6.1 › Fotoğraflar, swiping through the grid).
@MainActor
protocol MediaViewerContext: AnyObject {
    var chatID: ChatID { get }
    var messenger: any MessengerService { get }
    /// “Kaydet” (and every other way out of the app), telling the sender.
    var mediaSaver: MediaSaver { get }
    /// The message as it is now (where its file is, saved, deleted).
    func message(_ id: MessageID) -> Message?
}

extension ConversationViewModel: MediaViewerContext {
    /// The file preview's share button: the share sheet with the preview's
    /// copy, after the one-time explanation; the sender is told when it went
    /// somewhere. The copy stays the preview's (it goes when that closes).
    func shareFile(_ id: MessageID, from url: URL) {
        guard let message = message(id) else { return }
        let saver = mediaSaver
        Task { _ = await saver.shareFile(message, from: url) }
    }
}
