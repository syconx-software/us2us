import Foundation

#if DEBUG
    /// DEBUG harness of the invitation (proposed route `-U2Route invite[:<state>]`,
    /// read here directly so it works before `LaunchRoute` knows the feature;
    /// add `-U2Start chats` until it does):
    ///
    /// | state | shows |
    /// |---|---|
    /// | (none) | the invite sheet over 4.1 |
    /// | `edit` | the message being edited (keyboard up) |
    /// | `edited` | an edited message (the bubble shows the user's text) |
    /// | `copied` · `sent` | the row status after “Bağlantıyı kopyala” / after Messages sent it |
    /// | `link` · `link-unknown` | a universal link for `deniz` / `deniz_yilmaz` arrives: 4.2 searched (needs the 4.1 hook) |
    ///
    /// `-U2OpenURL <url>` hands any URL to the inbox as if iOS had opened the
    /// app with it (invalid ones are ignored, as in production).
    enum InviteHarness {
        enum State: String, CaseIterable {
            case sheet = ""
            case edit
            case edited
            case copied
            case sent
            case link
            case linkUnknown = "link-unknown"
        }

        struct Sheet: Identifiable, Equatable {
            let id = UUID()
            var debug = InviteDebugState()

            static func == (lhs: Sheet, rhs: Sheet) -> Bool { lhs.id == rhs.id }
        }

        /// `-U2Route invite[:<state>]`.
        static var state: State? {
            guard let route = UserDefaults.standard.string(forKey: "U2Route") else { return nil }
            let parts = route.split(separator: ":", maxSplits: 1).map(String.init)
            guard parts.first == "invite" else { return nil }
            return State(rawValue: parts.count == 2 ? parts[1] : "")
        }

        static func launchSheet() -> Sheet? {
            switch state {
            case .sheet: Sheet()
            case .edit: Sheet(debug: InviteDebugState(startsEditing: true))
            case .edited: Sheet(debug: InviteDebugState(editedText: editedSample))
            case .copied: Sheet(debug: InviteDebugState(status: .copyLink))
            case .sent: Sheet(debug: InviteDebugState(status: .sms))
            case .link, .linkUnknown, nil: nil
            }
        }

        static func launchLink() -> URL? {
            if let argument = UserDefaults.standard.string(forKey: "U2OpenURL") { return URL(string: argument) }
            switch state {
            case .link: return InviteLink.url(for: "deniz")
            case .linkUnknown: return InviteLink.url(for: "deniz_yilmaz")
            default: return nil
            }
        }

        @MainActor
        static func username(_ model: AppModel) -> String {
            let own = model.messenger.ownUsername
            return own.isEmpty ? DesignFixtures.ownUsername : own
        }

        static let editedSample = "Selam! Artık us2us’tayım, buradan yazışalım mı? @selin_n\nhttps://us2us.app/u/selin_n"
    }
#endif
