import Foundation
import Us2usKit
import Us2usRuntime
import Us2usStorage

/// A chat's own background over the runtime's encrypted storage: nothing of
/// it reaches the relay or the other person.
extension RealMessengerService {
    func chatBackground(of chat: ChatID) async -> ChatBackground {
        let stored: ChatBackgroundChoice?
        if let community = Self.communityAccount(listID: chat) {
            stored = try? await runtime.chatBackground(ofCommunity: community)
        } else if let peer = AccountId(string: chat) {
            stored = try? await runtime.chatBackground(with: peer)
        } else {
            stored = nil
        }
        switch stored {
        case .preset(let id): return ChatBackgroundPreset(rawValue: id).map(ChatBackground.preset) ?? .standard
        case .photo(let jpeg): return .photo(jpeg)
        case nil: return .standard
        }
    }

    func setChatBackground(_ background: ChatBackground, for chat: ChatID) async -> Bool {
        let choice: ChatBackgroundChoice? =
            switch background {
            case .standard: nil
            case .preset(let preset): .preset(preset.rawValue)
            case .photo(let jpeg): .photo(jpeg)
            }
        do {
            if let community = Self.communityAccount(listID: chat) {
                try await runtime.setChatBackground(choice, ofCommunity: community)
            } else if let peer = AccountId(string: chat) {
                try await runtime.setChatBackground(choice, with: peer)
            } else {
                return false
            }
        } catch {
            return false
        }
        chatBackgroundRevision += 1
        return true
    }

    private static func communityAccount(listID: ChatID) -> AccountId? {
        let prefix = CommunityChatRow.listID("")
        guard listID.hasPrefix(prefix) else { return nil }
        return AccountId(string: String(listID.dropFirst(prefix.count)))
    }
}
