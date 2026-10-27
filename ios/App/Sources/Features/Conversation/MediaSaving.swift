import Photos
import SwiftUI
import UIKit

// Saving and handing on a photo, video or file from our viewers (proposed,
// PROTOCOL §8.4 SAVED): “Kaydet” in the photo viewer and the player (to
// Photos, add-only), “Dosyalara kaydet” (the Files exporter) and the file
// preview's share sheet all go through `MediaSaver`. Before the first save of
// something received, a one-time floating sheet says that the sender will see
// it (“<kişi> fotoğrafı kaydettiğini görecek” · Kaydet · Vazgeç); once it is
// acknowledged it never comes back. Whenever something did leave the app the
// messenger is told (`markSaved`): the sender gets a SAVED receipt — always,
// whatever the read-receipt setting — and the message shows as saved here.
// View-once and disappearing media never leave the app.

// MARK: - The one-time explanation, remembered

/// Remembers that the one-time save explanation was acknowledged.
@MainActor
protocol SaveNoticeMemory: AnyObject {
    var hasAcknowledgedSaveNotice: Bool { get set }
}

/// Where the acknowledgement lives: the app's device preferences
/// (`AppPreferences`, UserDefaults — readable without the vault, like theme
/// and language). It says only that the explanation was seen.
enum SaveNoticePreference {
    static let key = "u2.saveNotice.acknowledged"

    static func isAcknowledged(in defaults: UserDefaults) -> Bool {
        defaults.bool(forKey: key)
    }

    static func setAcknowledged(_ acknowledged: Bool, in defaults: UserDefaults) {
        if acknowledged {
            defaults.set(true, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }
}

/// The same preference in `UserDefaults.standard` (what `AppPreferences`
/// uses in the app), for a view model made without the app model.
@MainActor
final class StandardSaveNoticeMemory: SaveNoticeMemory {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var hasAcknowledgedSaveNotice: Bool {
        get { SaveNoticePreference.isAcknowledged(in: defaults) }
        set { SaveNoticePreference.setAcknowledged(newValue, in: defaults) }
    }
}

/// In memory only (tests, previews).
@MainActor
final class InMemorySaveNoticeMemory: SaveNoticeMemory {
    var hasAcknowledgedSaveNotice: Bool

    init(acknowledged: Bool = false) {
        hasAcknowledgedSaveNotice = acknowledged
    }
}

// MARK: - What may leave the app

enum MediaExportPolicy {
    /// A photo, video or file that is here (or can be fetched): never
    /// view-once (its own kind, never here), disappearing — a saved copy would
    /// outlive it, as copying is off for them (5.3) — deleted, from a request
    /// that is still held, gone, or a pick still being prepared.
    static func allowsExport(_ message: Message) -> Bool {
        guard !message.isDisappearing, !message.isDeleted, !message.isPendingMedia, !message.isSystem else {
            return false
        }
        switch message.content {
        case .photo, .video, .file: break
        default: return false
        }
        switch message.mediaAvailability {
        case .ready, .remote, .downloading: return true
        case .held, .unavailable, .preparing, .preparationFailed: return false
        }
    }

    /// “Kaydet” saves to Photos: photos and videos (a video sent as a file too).
    static func savesToPhotos(_ message: Message) -> Bool {
        guard allowsExport(message) else { return false }
        switch message.content {
        case .photo, .video: return true
        case .file(let name, _, _, _):
            let kind = MediaFileKind(fileName: name)
            return kind == .image || kind == .movie
        default: return false
        }
    }

    /// Saved as a video (Photos takes it from a file).
    static func isVideo(_ message: Message) -> Bool {
        switch message.content {
        case .video: true
        case .file(let name, _, _, _): MediaFileKind(fileName: name) == .movie
        default: false
        }
    }

    /// The sender is told (SAVED): something received. The user's own
    /// photo saved again is only noted here, so it never asks first. A
    /// community has no SAVED receipt: saving there is not reported.
    static func tellsSender(_ message: Message) -> Bool {
        !message.isOutgoing && message.community == nil
    }

    /// What the explanation names (“fotoğrafı / videoyu / dosyayı”).
    static func noticeKind(of message: Message) -> SaveNoticeKind {
        switch message.content {
        case .photo: .photo
        case .video: .video
        case .file(let name, _, _, _):
            switch MediaFileKind(fileName: name) {
            case .image: .photo
            case .movie: .video
            default: .file
            }
        default: .file
        }
    }
}

enum SaveNoticeKind: Equatable, Sendable {
    case photo, video, file
}

// MARK: - Saving

/// Saves and hands on the media of one chat from our viewers, and tells the
/// messenger when something left the app. Everything that shows UI is a
/// replaceable step, so the rules are tested without a screen.
@MainActor
final class MediaSaver {
    enum Outcome: Equatable, Sendable {
        /// It left the app; the messenger was told.
        case saved
        /// The share sheet is up: whatever is shared from it is told (it
        /// can stay up after one of its activities was cancelled).
        case presented
        /// “Vazgeç”, or the exporter / share sheet closed without saving.
        case cancelled
        /// Photos refused (add-only permission off).
        case denied
        /// It could not be read or written.
        case failed
        /// Never for this message (view-once, disappearing, gone …).
        case notAllowed
    }

    enum PhotosResult: Equatable, Sendable {
        case saved, denied, failed
    }

    let chatID: ChatID
    let messenger: any MessengerService
    let memory: any SaveNoticeMemory
    /// The UI language for the floating sheets (kept current by the screen).
    var locale: Locale = .current

    /// The explanation (`SaveNoticeSheet`); `true`: “Kaydet”.
    var askNotice: @MainActor (_ peer: String, _ kind: SaveNoticeKind, _ locale: Locale) async -> Bool = { peer, kind, locale in
        await SaveNoticeSheet.ask(peer: peer, kind: kind, locale: locale)
    }
    /// Adds a photo or video to Photos (add-only permission).
    var addToPhotos: @MainActor (_ payload: MediaPayload, _ isVideo: Bool) async -> PhotosResult = { payload, isVideo in
        await PhotoLibraryWriter.add(payload, isVideo: isVideo)
    }
    /// The Files exporter with the copy; `true`: saved.
    var presentExporter: @MainActor (_ url: URL) async -> Bool = { url in
        await withCheckedContinuation { continuation in
            let shown = FileExporter.present(url) { saved in continuation.resume(returning: saved) }
            if !shown { continuation.resume(returning: false) }
        }
    }
    /// The share sheet with the copy; `onShared` runs each time it went
    /// somewhere (AirDrop, another app, Save to Files, Save Image …) — the
    /// sheet stays up after a cancelled activity, so there can be several.
    /// `false`: it could not be shown.
    var presentShareSheet: @MainActor (_ url: URL, _ onShared: @escaping @MainActor () -> Void) -> Bool = { url, onShared in
        guard SystemPresenter.topViewController() != nil else { return false }
        SystemShareSheet.present([url], excluding: [.addToReadingList, .assignToContact]) { completed in
            if completed { onShared() }
        }
        return true
    }
    /// Photos refused: why, and the way to Settings.
    var presentPhotosDenied: @MainActor (_ locale: Locale) -> Void = { locale in
        PhotosAccessSheet.present(locale: locale)
    }

    init(chatID: ChatID, messenger: any MessengerService, memory: any SaveNoticeMemory) {
        self.chatID = chatID
        self.messenger = messenger
        self.memory = memory
    }

    /// Whose media this chat holds (the explanation names them); empty when
    /// the name is not known — the explanation then says “the sender”, never
    /// the account id.
    var peerName: String { messenger.peer(for: chatID)?.username ?? "" }

    /// The one-time gate: something received, before it is first saved, says
    /// that the sender will see it; “Kaydet” is remembered, “Vazgeç” not.
    func confirmFirstSave(of message: Message) async -> Bool {
        guard MediaExportPolicy.allowsExport(message) else { return false }
        guard MediaExportPolicy.tellsSender(message), !memory.hasAcknowledgedSaveNotice else { return true }
        guard await askNotice(peerName, MediaExportPolicy.noticeKind(of: message), locale) else { return false }
        memory.hasAcknowledgedSaveNotice = true
        return true
    }

    /// The viewer's and the player's “Kaydet”: to Photos (add-only), the
    /// bytes from memory (a video through a protected copy that goes at once).
    func saveToPhotos(_ message: Message) async -> Outcome {
        guard MediaExportPolicy.savesToPhotos(message) else { return .notAllowed }
        guard await confirmFirstSave(of: message) else { return .cancelled }
        guard let payload = await messenger.mediaPayload(message.id, in: chatID), !payload.data.isEmpty else {
            return .failed
        }
        switch await addToPhotos(payload, MediaExportPolicy.isVideo(message)) {
        case .saved:
            await messenger.markSaved(message.id, in: chatID)
            return .saved
        case .denied:
            presentPhotosDenied(locale)
            return .denied
        case .failed:
            return .failed
        }
    }

    /// “Dosyalara kaydet” with the message's decrypted copy at `url` (the
    /// caller deletes the copy afterwards, saved or not).
    func exportFile(_ message: Message, from url: URL) async -> Outcome {
        guard MediaExportPolicy.allowsExport(message) else { return .notAllowed }
        guard await confirmFirstSave(of: message) else { return .cancelled }
        guard await presentExporter(url) else { return .cancelled }
        await messenger.markSaved(message.id, in: chatID)
        return .saved
    }

    /// The file preview's share button: the share sheet with the copy;
    /// every share that went somewhere tells the messenger (saving twice
    /// sends one receipt — the runtime records it once).
    func shareFile(_ message: Message, from url: URL) async -> Outcome {
        guard MediaExportPolicy.allowsExport(message) else { return .notAllowed }
        guard await confirmFirstSave(of: message) else { return .cancelled }
        let messenger = messenger
        let chatID = chatID
        let id = message.id
        let shown = presentShareSheet(url) {
            Task { await messenger.markSaved(id, in: chatID) }
        }
        return shown ? .presented : .failed
    }
}

// MARK: - Photos (add-only)

/// Adds to the photo library with the add-only permission
/// (`NSPhotoLibraryAddUsageDescription`): the library is never read. Off
/// the main actor: Photos runs the change block on its own queue.
enum PhotoLibraryWriter {
    nonisolated static func add(_ payload: MediaPayload, isVideo: Bool) async -> MediaSaver.PhotosResult {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { return .denied }
        if isVideo {
            // Photos takes a video only from a file: a protected copy for the
            // moment it is added, deleted right after.
            let named = MediaPayload(
                data: payload.data, contentType: payload.contentType, fileName: payload.fileName ?? "video")
            guard let url = await ProtectedPreviewFiles.write(named) else { return .failed }
            defer { ProtectedPreviewFiles.discard(url) }
            do {
                try await PHPhotoLibrary.shared().performChanges {
                    let options = PHAssetResourceCreationOptions()
                    options.shouldMoveFile = false
                    PHAssetCreationRequest.forAsset().addResource(with: .video, fileURL: url, options: options)
                }
                return .saved
            } catch {
                return .failed
            }
        }
        let data = payload.data
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: nil)
            }
            return .saved
        } catch {
            return .failed
        }
    }
}

// MARK: - The explanation (floating sheet)

/// “deniz fotoğrafı kaydettiğini görecek”: the 3.3 floating sheet's layout —
/// the Doto title, the `sheetBody` line, “Kaydet” (C1) and “Vazgeç” (C3).
struct SaveNoticeSheet: View {
    let peer: String
    let kind: SaveNoticeKind
    let onSave: @MainActor () -> Void
    let onCancel: @MainActor () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 10) {
                U2DisplayText(verbatim: title, style: .displaySheetTitle.balanced, isHeader: true)
                Text("save.notice.body", table: .conversation)
                    .u2Font(.sheetBody)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 6, leading: 4, bottom: 0, trailing: 4))
            VStack(spacing: 8) {
                Button(action: onSave) {
                    HStack(spacing: 8) {
                        MediaGlyphIcon(.download, size: 20)
                        Text("save.notice.confirm", table: .conversation)
                    }
                }
                .buttonStyle(.u2Primary)
                .accessibilityIdentifier("save.notice.confirm")
                Button(action: onCancel) {
                    U2ButtonLabel(Text("save.notice.cancel", table: .conversation))
                }
                .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.textButtonS))
                .accessibilityIdentifier("save.notice.cancel")
            }
        }
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
    }

    private var title: String {
        guard !peer.isEmpty else { return StringTable.conversation.string("save.notice.title.generic", locale: locale) }
        return switch kind {
        case .photo: StringTable.conversation.string("save.notice.title.photo \(peer)", locale: locale)
        case .video: StringTable.conversation.string("save.notice.title.video \(peer)", locale: locale)
        case .file: StringTable.conversation.string("save.notice.title.file \(peer)", locale: locale)
        }
    }

    /// Shows the sheet over whatever is on screen; `true`: “Kaydet”.
    @MainActor
    static func ask(peer: String, kind: SaveNoticeKind, locale: Locale) async -> Bool {
        await withCheckedContinuation { continuation in
            let answer = SaveNoticeAnswer()
            let shown = U2FloatingSheetPresenter.present(locale: locale) { close in
                SaveNoticeSheet(
                    peer: peer, kind: kind,
                    onSave: {
                        answer.value = true
                        close()
                    },
                    onCancel: close)
            } onDismiss: {
                continuation.resume(returning: answer.value)
            }
            if !shown { continuation.resume(returning: false) }
        }
    }
}

@MainActor
private final class SaveNoticeAnswer {
    var value = false
}

/// Photos refused the add-only permission: why, and the way to Settings
/// (the voice recorder's permission sheet, for Photos).
struct PhotosAccessSheet: View {
    let onClose: @MainActor () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 10) {
                U2DisplayText(
                    StringTable.conversation.resource("save.photos.denied.title"), style: .displaySheetTitle.balanced)
                Text("save.photos.denied.body", table: .conversation)
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
                    U2ButtonLabel(Text("voice.permission.settings", table: .conversation), glyph: .gearSix)
                }
                .buttonStyle(.u2Primary)
                .accessibilityIdentifier("save.photos.settings")
                Button(action: onClose) {
                    U2ButtonLabel(Text("common.close"))
                }
                .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.textButtonS))
                .accessibilityIdentifier("save.photos.close")
            }
        }
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
    }

    @MainActor
    static func present(locale: Locale) {
        U2FloatingSheetPresenter.present(locale: locale) { close in
            PhotosAccessSheet(onClose: close)
        } onDismiss: {}
    }
}

// MARK: - The “Kaydetti” chip

enum SavedMediaChip {
    /// On the user's own photo, video or file once the other side saved it
    /// (their SAVED receipt) — next to the reaction, never on anything else.
    static func isShown(for message: Message) -> Bool {
        guard message.isOutgoing, message.savedByPeer, !message.isDeleted else { return false }
        switch message.content {
        case .photo, .video, .file: return true
        default: return false
        }
    }
}

extension Message {
    /// Chips hang under the bubble (the reaction, “Kaydetti”): it keeps
    /// 14 pt free below.
    var showsBubbleChips: Bool {
        !reactions.isEmpty || SavedMediaChip.isShown(for: self)
    }
}
