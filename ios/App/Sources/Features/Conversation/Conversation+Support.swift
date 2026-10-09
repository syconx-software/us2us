import SwiftUI
import UIKit
import os

// Helpers only 05 · Sohbet uses (the reply quote, the 5.3 lift's frames).
// Bubbles, menus, the fade cover and the presentation probes are in the
// DesignSystem.

// MARK: - Message helpers

extension Message {
    /// The quote shown when replying to this message (C15 reply block). View-once
    /// media never yields a thumbnail — it has no preview anywhere (5.2 note).
    func quote(ownUsername: String, peerUsername: String, locale: Locale) -> QuotedMessage? {
        guard var quote = baseQuote(ownUsername: ownUsername, peerUsername: peerUsername, locale: locale) else {
            return nil
        }
        quote.messageID = id
        return quote
    }

    private func baseQuote(ownUsername: String, peerUsername: String, locale: Locale) -> QuotedMessage? {
        let author = isOutgoing ? ownUsername : peerUsername
        switch content {
        case .text(let text):
            return QuotedMessage(author: author, kind: .text(text))
        case .photo(let source, _):
            return QuotedMessage(author: author, kind: .photo, thumbnail: source)
        case .video(let poster, _, _):
            return QuotedMessage(author: author, kind: .video, thumbnail: poster)
        case .voice:
            return QuotedMessage(author: author, kind: .voice)
        case .file(let name, _, _, _):
            return QuotedMessage(author: author, kind: .file(name))
        case .location(let name, _, _, _):
            return QuotedMessage(author: author, kind: .text(name))
        case .liveLocation:
            return QuotedMessage(author: author, kind: .text(L10n.string("location.live", locale: locale)))
        case .textSticker(let text):
            return QuotedMessage(author: author, kind: .text(text))
        case .viewOnce(let isVideo, _):
            return QuotedMessage(author: author, kind: isVideo ? .video : .photo)
        case .deleted, .system:
            return nil
        }
    }
}

// MARK: - Bubble frames (for the 5.3 lift)

/// Where a bubble is on screen. A plain reference type — written from
/// geometry callbacks without invalidating any view — read once when a
/// long-press opens the action menu. Rows report only while a finger rests on
/// them (or the harness asks), never while the column scrolls.
@MainActor
final class BubbleFrameStore {
    private(set) var frames: [MessageID: CGRect] = [:]

    func set(_ frame: CGRect, for id: MessageID) {
        frames[id] = frame
    }

    func frame(for id: MessageID) -> CGRect? {
        frames[id]
    }

    func remove(_ id: MessageID) {
        frames[id] = nil
    }
}

// MARK: - Signposts (open-to-first-frame, measured by the performance UI tests)

/// `com.syconx.us2us` › `Conversation` › `open`: from the moment the
/// conversation screen is created to its first committed frame. Carries no
/// content (no ids, no names) — only the interval. DEBUG builds only, like
/// `U2Signposts`: release builds emit nothing.
@MainActor
enum ConversationSignposts {
    #if DEBUG
        static let signposter = OSSignposter(subsystem: U2Signposts.subsystem, category: "Conversation")
        private static var openState: OSSignpostIntervalState?
        /// Conversations on screen: SwiftUI re-creates view values while a
        /// screen is shown — only a creation for a chat not yet shown begins one.
        private static var shown: Set<ChatID> = []
    #endif

    static func beginOpen(_ chat: ChatID) {
        #if DEBUG
            guard openState == nil, !shown.contains(chat) else { return }
            openState = signposter.beginInterval("open")
        #endif
    }

    /// The screen appeared: ends the interval after the frame that is being
    /// built now has been committed.
    static func didAppear(_ chat: ChatID) {
        #if DEBUG
            shown.insert(chat)
            guard openState != nil else { return }
            DispatchQueue.main.async {
                guard let state = openState else { return }
                signposter.endInterval("open", state)
                openState = nil
            }
        #endif
    }

    static func didDisappear(_ chat: ChatID) {
        #if DEBUG
            shown.remove(chat)
        #endif
    }
}

// MARK: - Typing (optional messenger capability)

/// A messenger that can tell when the other side is writing (PROTOCOL §8.4:
/// typing messages are ephemeral, never stored, sent only in accepted chats
/// and switchable by the user). The live messenger adopts it; the design
/// fixtures do not (the DEBUG harness plays the indicator by itself).
/// Candidate for `MessengerService` once the live wiring lands.
@MainActor
protocol PeerTypingReporting: AnyObject {
    func isPeerTyping(in chat: ChatID) -> Bool
}

// MARK: - Haptics

@MainActor
enum ConversationHaptics {
    /// A light tap as a message leaves (send, attachment, resend). Haptics
    /// are not motion: Reduce Motion keeps them.
    static func send() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }
}
