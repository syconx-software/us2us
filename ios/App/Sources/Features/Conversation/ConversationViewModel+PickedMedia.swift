import SwiftUI
import UIKit

// MARK: “+” › Fotoğraf ve video

extension ConversationViewModel {
    /// A pick from the photo picker (up to ten photos and videos): every
    /// item leaves as a message of its own, in the order it was selected,
    /// and every bubble shows at once (“Hazırlanıyor”); they are prepared one
    /// at a time and one that fails (“Tekrar gönder”) never holds up the
    /// rest. Text in the composer becomes the first photo's caption and the
    /// field clears — unless the pick has no photo or the text is too long
    /// for a caption, when it stays. VoiceOver hears how many are on their
    /// way. A reply set in the composer stays for the next text.
    func send(picked items: [PickedMediaItem]) {
        let messages = PickedMediaPlan.messages(for: items, caption: draft)
        // Beyond the limit (the picker never hands more over): not kept.
        SystemPickers.discard(items.dropFirst(messages.count))
        guard !messages.isEmpty else { return }
        ConversationHaptics.send()
        if messages.contains(where: { $0.caption != nil }) {
            // The field shrinks back to one line as the caption leaves it.
            withU2Animation(U2Motion.micro) { draft = "" }
        }
        announceSending(Self.sendingAnnouncement(for: messages.map(\.item.kind), locale: presentationLocale))
        let messenger = messenger
        let chatID = chatID
        Task { await messenger.send(pickedMedia: messages, in: chatID) }
    }

    /// VoiceOver's “5 fotoğraf gönderiliyor” / “Sending 5 photos” (videos
    /// and a mix of both are named as such).
    static func sendingAnnouncement(for kinds: [PickedMediaItem.Kind], locale: Locale) -> String {
        let count = kinds.count
        if kinds.allSatisfy({ $0 == .photo }) {
            return StringTable.conversation.string("a11y.media.sending.photos \(count)", locale: locale)
        }
        if kinds.allSatisfy({ $0 == .video }) {
            return StringTable.conversation.string("a11y.media.sending.videos \(count)", locale: locale)
        }
        return StringTable.conversation.string("a11y.media.sending.mixed \(count)", locale: locale)
    }

    /// Said once the picker has gone: VoiceOver drops an announcement made
    /// while the screen changes under it.
    private func announceSending(_ text: String) {
        guard UIAccessibility.isVoiceOverRunning else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(700))
            AccessibilityNotification.Announcement(text).post()
        }
    }
}
