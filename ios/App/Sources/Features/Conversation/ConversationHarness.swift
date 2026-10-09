#if DEBUG
    import SwiftUI
    import UIKit

    /// DEBUG-only screen harness of 05 · Sohbet: opens every screen and state
    /// directly, in its exact design state, for screenshots and design
    /// comparisons. Use with the foundation's launch arguments:
    ///
    /// ```sh
    /// xcrun simctl launch <sim> com.syconx.us2us -U2FrozenClock YES \
    ///   -U2Theme dark -U2Language tr -U2Route conversation:deniz/5.3
    /// ```
    ///
    /// | `-U2Route conversation:` | `/<state>` (or `-U2ConversationScreen`) | shows |
    /// |---|---|---|
    /// | `deniz` | — / `5.1` | 5.1 chat (`-U2GhostUrgent YES`: last hour + banner) |
    /// | `deniz` | `5.1-failed` | newest message not delivered: “İletilemedi” + “Tekrar gönder” |
    /// | `deniz` | `5.1-reply` | composer with the reply quote (after “Yanıtla”) |
    /// | `deniz` | `5.1-attach` | the “+” menu (proposed) |
    /// | `mert.k` | — / `5.2` | 5.2 media and location (Ghost off) |
    /// | `mert.k` | `5.2-ended` | live location stopped (proposed ended state) |
    /// | `mert.k` | `5.2-opened` | the view-once bubble after viewing (“Açıldı”) |
    /// | `deniz` | `5.3` | actions on “Kapı kodu 4471” (own, disappearing) |
    /// | `deniz` | `5.3-incoming` | actions on deniz's photo (incoming, with reaction) |
    /// | `deniz` | `5.3-confirm` | the “Herkesten sil” confirmation (floating sheet) |
    /// | `deniz` | `5.1-viewer` | the photo viewer (tap a photo; proposed) |
    /// | `deniz` | `5.4` | info of “Çok güzelmiş! 8 gibi olur mu?” (read) |
    /// | `deniz` | `5.4-pending` · `5.4-failed` | info of “Kapı kodu 4471” sent only · not delivered |
    /// | `deniz` | `5.5` | Ghost sheet (on, 24 sa) — `conversation:mert.k` for Ghost off |
    /// | `deniz` | `5.6` | view-once viewer after a screenshot (warning shown) |
    /// | `deniz` | `5.6-clean` · `5.6-captured` | viewer without warning · while recording |
    ///
    /// `-U2ConversationDemo <action>` performs one interaction by itself ~1.5 s
    /// after the screen appears, so transitions can be recorded and checked
    /// with screenshots at intervals: `send`, `status`, `react`, `reply`,
    /// `deleteEveryone`, `deleteMe`, `ghostSave`, `stopLive`, `actions`,
    /// `viewOnce`, `attach`, `type`; and for the column's motion: `typing`
    /// (deniz writes, then her message arrives), `incoming`, `sendLong` (a
    /// three-line message), `prepend` (200 older messages load above).
    /// `-U2ConversationDemoDelay <seconds>` changes the wait;
    /// `-U2ConversationScrollTo <message id>` scrolls the column to that
    /// message first (e.g. `stress-4990` with `-U2StressMessages 5000`).
    enum ConversationHarness {
        enum Screen: String, CaseIterable, Sendable {
            case chat = "5.1"
            case chatFailed = "5.1-failed"
            case chatReply = "5.1-reply"
            case chatAttach = "5.1-attach"
            case media = "5.2"
            case mediaEnded = "5.2-ended"
            case mediaOpened = "5.2-opened"
            case actions = "5.3"
            case actionsConfirm = "5.3-confirm"
            case viewer = "5.1-viewer"
            case actionsIncoming = "5.3-incoming"
            case info = "5.4"
            case infoPending = "5.4-pending"
            case infoFailed = "5.4-failed"
            case ghost = "5.5"
            case viewOnce = "5.6"
            case viewOnceClean = "5.6-clean"
            case viewOnceCaptured = "5.6-captured"
        }

        enum Demo: String, Sendable {
            case send, status, react, reply, deleteEveryone, deleteMe, ghostSave, stopLive, actions, viewOnce, attach
            /// Focuses the composer (keyboard up) and types a draft.
            case type
            case typing, incoming, sendLong, prepend
        }

        /// `-U2ConversationScrollTo <message id>`.
        static var scrollTarget: MessageID? {
            UserDefaults.standard.string(forKey: "U2ConversationScrollTo")
        }

        /// `-U2Route conversation:<chat>/<id>`, or `-U2ConversationScreen <id>`.
        static var screen: Screen? {
            let id = UserDefaults.standard.string(forKey: "U2ConversationScreen") ?? LaunchRoute.current?.state(for: .conversation)
            return id.flatMap(Screen.init(rawValue:))
        }

        static var demo: Demo? {
            UserDefaults.standard.string(forKey: "U2ConversationDemo").flatMap(Demo.init(rawValue:))
        }

        /// Applied once per launch (re-entering the chat shows it as it is).
        @MainActor static var didApply = false

        @MainActor
        static func apply(to viewModel: ConversationViewModel) async {
            guard !didApply else { return }
            didApply = true
            let fixtures = viewModel.messenger as? DesignFixturesService
            if let screen {
                await apply(screen, to: viewModel, fixtures: fixtures)
            }
            if let demo {
                let delay = UserDefaults.standard.double(forKey: "U2ConversationDemoDelay")
                try? await Task.sleep(for: .seconds(delay > 0 ? delay : 1.5))
                await run(demo, on: viewModel, fixtures: fixtures)
            }
        }

        @MainActor
        private static func apply(_ screen: Screen, to viewModel: ConversationViewModel, fixtures: DesignFixturesService?) async {
            let noAnimation: Transaction = {
                var transaction = Transaction()
                transaction.disablesAnimations = true
                return transaction
            }()
            switch screen {
            case .chat, .media:
                break
            case .chatFailed:
                fixtures?.setStatus(.failed, of: "d6", in: viewModel.chatID, at: DesignFixtures.referenceDate)
            case .chatReply:
                if let photo = viewModel.message("d2") {
                    withTransaction(noAnimation) {
                        viewModel.replyTo = photo.quote(
                            ownUsername: viewModel.ownUsername, peerUsername: viewModel.peerUsername, locale: .current)
                    }
                }
            case .chatAttach:
                try? await Task.sleep(for: .milliseconds(300))
                withTransaction(noAnimation) { viewModel.showsAttachMenu = true }
            case .mediaEnded:
                await fixtures?.stopLiveLocation("m4", in: viewModel.chatID)
            case .mediaOpened:
                await fixtures?.markViewOnceOpened("m6", in: viewModel.chatID)
            case .actions:
                viewModel.openActionsWhenReady(for: "d6")
            case .actionsIncoming:
                viewModel.openActionsWhenReady(for: "d2")
            case .actionsConfirm:
                try? await Task.sleep(for: .milliseconds(600))
                viewModel.deleteForEveryoneCandidate = "d6"
            case .viewer:
                if let photo = viewModel.message("d2") { viewModel.openMedia(photo) }
            case .info:
                viewModel.info = .init(id: "d3")
            case .infoPending:
                viewModel.info = .init(id: "d6")
            case .infoFailed:
                fixtures?.setStatus(.failed, of: "d6", in: viewModel.chatID, at: DesignFixtures.referenceDate)
                viewModel.info = .init(id: "d6")
            case .ghost:
                viewModel.showsGhostSettings = true
            case .viewOnce, .viewOnceClean, .viewOnceCaptured:
                // The reference: deniz's view-once photo of 18:40, after a screenshot.
                viewModel.viewer = ConversationViewModel.ViewerItem(
                    kind: .viewOnce, messageID: nil, sender: "deniz", sentAt: DesignFixtures.date(day: 8, 18, 40),
                    media: .designArtwork(.nightPier), isVideo: false,
                    showsScreenshotWarning: screen == .viewOnce, simulatesCapture: screen == .viewOnceCaptured)
            }
        }

        @MainActor
        private static func run(_ demo: Demo, on viewModel: ConversationViewModel, fixtures: DesignFixturesService?) async {
            let chat = viewModel.chatID
            switch demo {
            case .send:
                viewModel.draft = "Görüşürüz 👋"
                viewModel.sendDraft()
            case .status:
                guard let id = ConversationViewModel.lastOutgoingID(in: viewModel.messages) else { return }
                fixtures?.setStatus(.delivered, of: id, in: chat)
                try? await Task.sleep(for: .seconds(2))
                fixtures?.setStatus(.read, of: id, in: chat)
            case .react:
                viewModel.openActionsWhenReady(for: "d5")
                try? await Task.sleep(for: .seconds(1.2))
                viewModel.react("❤️", to: "d5")
            case .reply:
                viewModel.openActionsWhenReady(for: "d1")
                try? await Task.sleep(for: .seconds(1.2))
                viewModel.reply(to: "d1", locale: .current)
            case .deleteEveryone:
                await viewModel.messenger.deleteForEveryone("d5", in: chat)
            case .deleteMe:
                await viewModel.messenger.deleteForMe("d1", in: chat)
            case .ghostSave:
                viewModel.saveGhost(enabled: true, period: .sixHours, disappearing: .fiveMinutes)
            case .stopLive:
                viewModel.stopLiveLocation("m4")
            case .actions:
                viewModel.openActionsWhenReady(for: viewModel.messages.last { !$0.isSystem }?.id ?? "")
            case .viewOnce:
                if let message = viewModel.message("m6") { viewModel.openViewOnce(message) }
            case .attach:
                viewModel.toggleAttachMenu()
            case .type:
                guard let field = firstTextInput(in: UIApplication.shared.connectedScenes) else { return }
                field.becomeFirstResponder()
                try? await Task.sleep(for: .seconds(1))
                viewModel.draft = "Yarın da buluşalım mı?"
            case .typing:
                viewModel.debugPeerTyping = true
                try? await Task.sleep(for: .seconds(2.5))
                viewModel.debugPeerTyping = false
                viewModel.debugSuffix.append(incoming("Geldim, iskelenin başındayım 🙂", after: viewModel.messages.last))
            case .incoming:
                viewModel.debugSuffix.append(incoming("Geldim, iskelenin başındayım 🙂", after: viewModel.messages.last))
            case .sendLong:
                viewModel.draft = "Tamam, o zaman sekizde iskelede buluşalım. Ben biraz erken gelip vapurun saatine de bakarım, gecikirse haber veririm."
                viewModel.sendDraft()
            case .prepend:
                prependCount += 1
                let first = viewModel.messages.first?.sentAt ?? Date()
                let older = ConversationStressFixture.messages(count: 200, before: first).map { message in
                    Message(
                        id: "older\(prependCount)-" + message.id, direction: message.direction, sentAt: message.sentAt,
                        content: message.content,
                        status: message.status, replyTo: message.replyTo, reactions: message.reactions)
                }
                viewModel.debugPrefix.insert(contentsOf: older, at: 0)
            }
        }

        @MainActor private static var incomingCount = 0
        @MainActor private static var prependCount = 0

        /// A message from deniz, a minute after `previous`.
        @MainActor
        private static func incoming(_ text: String, after previous: Message?) -> Message {
            incomingCount += 1
            return Message(
                id: "demo-in-\(incomingCount)", direction: .incoming,
                sentAt: (previous?.sentAt ?? DesignFixtures.referenceDate).addingTimeInterval(60), content: .text(text))
        }
    }

    /// `-U2StressMessages <n>`: a long conversation for scrolling and
    /// opening measurements (`ConversationPerformanceUITests`): `n` older
    /// messages, deterministic, before the chat's own — runs of text of 1–4
    /// lines, photos, replies, voice, files, traces, reactions and a few
    /// system lines, about 97 s apart. They live in the view model only
    /// (actions on them are no-ops for the fixtures service).
    enum ConversationStressFixture {
        static var requestedCount: Int {
            min(50_000, max(0, UserDefaults.standard.integer(forKey: "U2StressMessages")))
        }

        static func messages(count: Int, before end: Date, english: Bool = false) -> [Message] {
            guard count > 0 else { return [] }
            var random = SplitMix64(seed: 0x75_32_75_73)
            let sentences =
                english
                ? [
                    "On my way.", "Did you see the pier last night? The lights were all on.", "OK",
                    "Let's meet at eight, I'll bring the book.", "Haha, really?",
                    "I left the keys with the neighbour, the door code is the same as before.", "👍",
                    "Can you send me the photo from yesterday?", "Sure.",
                    "The ferry was late again, so I walked along the coast instead. It was lovely, honestly.",
                    "Talk later", "What time does it start?",
                ]
                : [
                    "Yoldayım.", "Dün akşam iskeleyi gördün mü? Bütün ışıklar yanıyordu.", "Tamam",
                    "Sekizde buluşalım, kitabı getiririm.", "Haha, gerçekten mi?",
                    "Anahtarları komşuya bıraktım, kapı kodu eskisiyle aynı.", "👍",
                    "Dünkü fotoğrafı atar mısın?", "Olur.",
                    "Vapur yine gecikti, ben de sahilden yürüdüm. Çok güzeldi açıkçası.",
                    "Sonra konuşuruz", "Kaçta başlıyor?",
                ]
            var result: [Message] = []
            result.reserveCapacity(count)
            var direction = MessageDirection.incoming
            let start = end.addingTimeInterval(-97 * Double(count + 1))
            for index in 0..<count {
                if random.next() % 3 == 0 { direction = direction == .incoming ? .outgoing : .incoming }
                let sentAt = start.addingTimeInterval(97 * Double(index))
                let roll = random.next() % 100
                let text = sentences[Int(random.next() % UInt64(sentences.count))]
                let content: MessageContent
                var replyTo: QuotedMessage?
                switch roll {
                case 0..<6:
                    content = .photo(.designArtwork(.nightPier), caption: roll < 3 ? text : nil)
                case 6..<10:
                    content = .text(text)
                    replyTo = QuotedMessage(author: direction == .incoming ? DesignFixtures.ownUsername : "deniz", kind: .text(sentences[index % sentences.count]))
                case 10..<13:
                    content = .voice(duration: 12 + Double(roll), waveform: DesignFixtures.voiceWaveform, playedFraction: 0)
                case 13..<15:
                    content = .file(name: "belge-\(index).pdf", byteCount: 400_000 + Int64(index) * 37, typeLabel: "PDF", pageCount: 2)
                case 15:
                    content = .deleted
                case 16:
                    content = .system(.disappearingChanged(.off))
                case 17..<40:
                    // Two sentences: 2–4 lines.
                    content = .text(text + " " + sentences[Int(random.next() % UInt64(sentences.count))])
                default:
                    content = .text(text)
                }
                let isSystem = if case .system = content { true } else { false }
                result.append(
                    Message(
                        id: "stress-\(index)", direction: isSystem ? .outgoing : direction, sentAt: sentAt, content: content,
                        status: direction == .outgoing && !isSystem ? .read : nil, replyTo: replyTo,
                        reactions: random.next() % 14 == 0 && !isSystem ? [Reaction(emoji: "❤️")] : []))
            }
            return result
        }
    }

    /// A small deterministic generator (the stress fixture is the same on every run).
    struct SplitMix64 {
        private var state: UInt64
        init(seed: UInt64) { state = seed }
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }

    /// The composer's text input (a `UITextView` behind SwiftUI's vertical `TextField`).
    @MainActor
    private func firstTextInput(in scenes: Set<UIScene>) -> UIView? {
        func search(_ view: UIView) -> UIView? {
            if view is UITextView || view is UITextField { return view }
            for sub in view.subviews { if let found = search(sub) { return found } }
            return nil
        }
        for case let scene as UIWindowScene in scenes {
            for window in scene.windows where !window.isHidden {
                if let found = search(window) { return found }
            }
        }
        return nil
    }

    /// Runs the harness when the conversation appears.
    struct ConversationHarnessModifier: ViewModifier {
        let viewModel: ConversationViewModel

        func body(content: Content) -> some View {
            content.task {
                await ConversationHarness.apply(to: viewModel)
            }
        }
    }
#endif
