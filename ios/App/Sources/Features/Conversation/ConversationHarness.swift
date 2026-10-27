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
    /// | `deniz` | `5.1-stickers` | the sticker picker (proposed), with the recents |
    /// | `deniz` | `5.1-blocked` | deniz blocked (proposed): “Engellendi” in the header, the bar with “Engeli kaldır” in the composer's place |
    /// | `mert_k` | — / `5.2` | 5.2 media and location (Ghost off) |
    /// | `mert_k` | `5.2-ended` | live location stopped (proposed ended state) |
    /// | `mert_k` | `5.2-opened` | the view-once bubble after viewing (“Açıldı”) |
    /// | `mert_k` | `5.2-place` | a tap on “Moda İskelesi”: the “Haritada aç” chooser (proposed) |
    /// | `mert_k` | `5.2-map` · `5.2-map-peer` | the live-location map (proposed): mert_k's demo share and selin_n's · mert_k's only, selin_n's phone shown on this device only |
    /// | `mert_k` | `5.2-map-ask` · `5.2-map-denied` | mert_k's share only, the gentle hint: location not asked yet (“İzin ver”) · refused (“Ayarları aç”) |
    /// | `deniz` | `5.3` | actions on “Kapı kodu 4471” (own, disappearing) |
    /// | `deniz` | `5.3-incoming` | actions on deniz's photo (incoming, with reaction) |
    /// | `deniz` | `5.3-confirm` | the “Herkesten sil” confirmation (floating sheet) |
    /// | `deniz` | `5.1-viewer` · `5.1-viewer-zoomed` | the photo viewer (tap a photo; proposed) · zoomed in 2.5× |
    /// | `mert_k` | `5.2-player` · `5.2-player-loading` | the video player paused at 0:07 · while the clip loads |
    /// | `mert_k` | `5.2-file` · `5.2-file-actions` | Quick Look on “kira-sozlesmesi.pdf” · 5.3 with “Dosyalara kaydet” |
    /// | `mert_k` | `5.2-remote` · `5.2-downloading` · `5.2-unavailable` | the video and an incoming file still on the relay · downloading (40 % / 62 %) · gone |
    /// | `mert_k` | `5.2-preparing` · `5.2-prepare-failed` | a video sent from “+” while it is prepared (“0:18 · Hazırlanıyor”) · preparing it failed (“Tekrar gönder”) |
    /// | `mert_k` | `5.2-file-disappearing` | the PDF as a disappearing message: Quick Look in the app's screen, without sharing (proposed) |
    /// | `mert_k` | `5.2-saved` · `5.4-saved` | mert_k saved selin_n's PDF: the “Kaydetti” chip under it · its info, ending in “Kaydedildi” (proposed) |
    /// | `deniz` | `5.1-save-notice` | the photo viewer with the one-time “deniz fotoğrafı kaydettiğini görecek” sheet (proposed) |
    /// | `deniz` | `5.4` | info of “Çok güzelmiş! 8 gibi olur mu?” (read) |
    /// | `deniz` | `5.4-pending` · `5.4-failed` | info of “Kapı kodu 4471” sent only · not delivered |
    /// | `deniz` | `5.5` | Ghost sheet (on, 24 sa) — `conversation:mert_k` for Ghost off |
    /// | `deniz` | `5.6` | view-once viewer after a screenshot (warning shown) |
    /// | `deniz` | `5.6-clean` · `5.6-captured` | viewer without warning · while recording |
    /// | `deniz` | `5.1-voice-hold` · `5.1-voice-locked` · `5.1-voice-review` | recording a voice message (proposed, no microphone): held at 0:07, sliding left · locked, hands-free · stopped, to listen to, delete or send |
    /// | `deniz` | `5.1-voice-hint` · `5.1-voice-permission` · `5.1-voice-unplayed` | the mic tapped (“Kaydetmek için basılı tut”) · the microphone refused (“Ayarları aç”) · an incoming voice message not played yet |
    ///
    /// `-U2ConversationDemo <action>` performs one interaction by itself ~1.5 s
    /// after the screen appears, so transitions can be recorded and checked
    /// with screenshots at intervals: `send`, `sticker` (words.hearYou leaves
    /// and settles in), `status`, `react`, `reply`,
    /// `deleteEveryone`, `deleteMe`, `ghostSave`, `stopLive`, `actions`,
    /// `viewOnce`, `attach`, `type`; media: `openPhoto` (the 5.1 photo),
    /// `playVideo` (the 5.2 video), `openFile` (Quick Look), `downloadFile`
    /// (an incoming file downloads, then opens), `sendVideo` (a video from “+”
    /// is prepared, then sent, in one bubble), `sendPhotos` (three photos
    /// picked at once, the composer's text on the first: every bubble at
    /// once, then sent one after another), `playVoice` (deniz's voice
    /// message plays); location: `openPlace` (the
    /// chooser), `openMap` (the live-location map); and for the column's motion: `typing`
    /// (deniz writes, then her message arrives), `incoming`, `sendLong` (a
    /// three-line message), `prepend` (200 older messages load above).
    /// `-U2ConversationDemoDelay <seconds>` changes the wait;
    /// `-U2ConversationScrollTo <message id>` scrolls the column to that
    /// message first (e.g. `stress-4990` with `-U2StressMessages 5000`).
    enum ConversationHarness {
        enum Screen: String, CaseIterable, Sendable {
            case photoAlbum = "5.1-album"
            case chat = "5.1"
            case chatFailed = "5.1-failed"
            case chatReply = "5.1-reply"
            case chatAttach = "5.1-attach"
            case chatStickers = "5.1-stickers"
            case chatBlocked = "5.1-blocked"
            case media = "5.2"
            case mediaEnded = "5.2-ended"
            case mediaOpened = "5.2-opened"
            case place = "5.2-place"
            case liveMap = "5.2-map"
            case liveMapPeer = "5.2-map-peer"
            case liveMapAsk = "5.2-map-ask"
            case liveMapDenied = "5.2-map-denied"
            case actions = "5.3"
            case actionsConfirm = "5.3-confirm"
            case viewer = "5.1-viewer"
            case viewerZoomed = "5.1-viewer-zoomed"
            case player = "5.2-player"
            case playerLoading = "5.2-player-loading"
            case file = "5.2-file"
            case fileActions = "5.2-file-actions"
            case mediaRemote = "5.2-remote"
            case mediaDownloading = "5.2-downloading"
            case mediaUnavailable = "5.2-unavailable"
            case videoPreparing = "5.2-preparing"
            case videoPrepareFailed = "5.2-prepare-failed"
            case fileDisappearing = "5.2-file-disappearing"
            case mediaSaved = "5.2-saved"
            case infoSaved = "5.4-saved"
            case saveNotice = "5.1-save-notice"
            case actionsIncoming = "5.3-incoming"
            case info = "5.4"
            case infoPending = "5.4-pending"
            case infoFailed = "5.4-failed"
            case ghost = "5.5"
            case viewOnce = "5.6"
            case viewOnceClean = "5.6-clean"
            case viewOnceCaptured = "5.6-captured"
            case voiceHold = "5.1-voice-hold"
            case voiceLocked = "5.1-voice-locked"
            case voiceReview = "5.1-voice-review"
            case voiceHint = "5.1-voice-hint"
            case voicePermission = "5.1-voice-permission"
            case voiceUnplayed = "5.1-voice-unplayed"
        }

        enum Demo: String, Sendable {
            case send, sticker, status, react, reply, deleteEveryone, deleteMe, ghostSave, stopLive, actions, viewOnce
            case attach
            case openPhoto, playVideo, openFile, downloadFile, sendVideo, sendPhotos, playVoice
            case openPlace, openMap
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
            let pagedCount = UserDefaults.standard.integer(forKey: "U2PagedHistory")
            if pagedCount > 0, let fixtures {
                let first = fixtures.messages(in: viewModel.chatID).first?.sentAt ?? Date()
                fixtures.installHistory(ConversationStressFixture.messages(count: min(50_000, pagedCount), before: first), in: viewModel.chatID)
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
            case .photoAlbum:
                guard let fixtures else { return }
                await fixtures.updateGhost(.off, disappearing: .off, in: viewModel.chatID)
                await fixtures.send(pickedMedia: PickedMediaPlan.messages(for: DesignFixtures.pickedPhotos, caption: "Album caption"), in: viewModel.chatID)
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
            case .chatStickers:
                try? await Task.sleep(for: .milliseconds(300))
                viewModel.openStickerPicker()
            case .chatBlocked:
                fixtures?.setBlocked(viewModel.chatID)
            case .mediaEnded:
                await fixtures?.stopLiveLocation("m4", in: viewModel.chatID)
            case .mediaOpened:
                await fixtures?.markViewOnceOpened("m6", in: viewModel.chatID)
            case .place:
                try? await Task.sleep(for: .milliseconds(300))
                if let place = viewModel.message("m3") { viewModel.openLocation(place) }
            case .liveMap, .liveMapPeer, .liveMapAsk, .liveMapDenied:
                guard let fixtures else { return }
                if screen == .liveMapAsk { fixtures.ownPositionOverride = .needsPermission }
                if screen == .liveMapDenied { fixtures.ownPositionOverride = .denied }
                let peerShare = fixtures.startDemoLiveLocation(in: viewModel.chatID)
                // Only mert_k shares: selin_n's own share has ended.
                if screen != .liveMap { await fixtures.stopLiveLocation("m4", in: viewModel.chatID) }
                try? await Task.sleep(for: .milliseconds(300))
                if let share = viewModel.message(screen == .liveMap ? "m4" : peerShare) { viewModel.openLiveMap(share) }
            case .actions:
                viewModel.openActionsWhenReady(for: "d6")
            case .actionsIncoming:
                viewModel.openActionsWhenReady(for: "d2")
            case .actionsConfirm:
                try? await Task.sleep(for: .milliseconds(600))
                viewModel.deleteForEveryoneCandidate = "d6"
            case .viewer:
                if let photo = viewModel.message("d2") { viewModel.openMedia(photo) }
            case .viewerZoomed:
                if let photo = viewModel.message("d2"), case .photo(let source, let caption) = photo.content {
                    viewModel.viewer = ConversationViewModel.ViewerItem(
                        kind: .photo, messageID: photo.id, sender: viewModel.peerUsername, sentAt: photo.sentAt,
                        media: source, isVideo: false, caption: caption, startsZoomed: true)
                }
            case .player:
                // Paused at 0:07, the controls shown.
                if let video = viewModel.message("m2"), case .video(let poster, let duration, _) = video.content {
                    viewModel.viewer = ConversationViewModel.ViewerItem(
                        kind: .video, messageID: video.id, sender: viewModel.peerUsername, sentAt: video.sentAt,
                        media: poster, isVideo: true, duration: duration, startTime: 7)
                }
            case .playerLoading:
                fixtures?.holdsMediaPayloads = true
                if let video = viewModel.message("m2") { viewModel.openMedia(video) }
            case .file:
                try? await Task.sleep(for: .milliseconds(400))
                if let file = viewModel.message("m1") { viewModel.openMedia(file) }
            case .fileActions:
                viewModel.openActionsWhenReady(for: "m1")
            case .mediaRemote, .mediaDownloading, .mediaUnavailable:
                let video: MediaAvailability
                let file: MediaAvailability
                switch screen {
                case .mediaRemote: (video, file) = (.remote, .remote)
                case .mediaDownloading: (video, file) = (.downloading(0.4), .downloading(0.62))
                default: (video, file) = (.unavailable, .unavailable)
                }
                fixtures?.setMediaAvailability(video, of: "m2", in: viewModel.chatID)
                fixtures?.addIncomingFile(in: viewModel.chatID, availability: file)
            case .videoPreparing, .videoPrepareFailed:
                fixtures?.addOutgoingVideo(in: viewModel.chatID, failed: screen == .videoPrepareFailed)
            case .fileDisappearing:
                fixtures?.setDisappearing(.fiveMinutes, of: "m1", in: viewModel.chatID)
                try? await Task.sleep(for: .milliseconds(400))
                if let file = viewModel.message("m1") { viewModel.openMedia(file) }
            case .mediaSaved:
                fixtures?.setSavedByPeer("m1", in: viewModel.chatID)
            case .infoSaved:
                fixtures?.setSavedByPeer("m1", in: viewModel.chatID)
                viewModel.info = .init(id: "m1")
            case .saveNotice:
                try? await Task.sleep(for: .milliseconds(300))
                guard let photo = viewModel.message("d2") else { return }
                viewModel.openMedia(photo)
                try? await Task.sleep(for: .milliseconds(700))
                _ = await viewModel.mediaSaver.askNotice(viewModel.peerUsername, .photo, viewModel.presentationLocale)
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
            case .voiceHold:
                viewModel.voice.showDemo(.holding, elapsed: 7, slide: CGSize(width: -34, height: -18))
            case .voiceLocked:
                viewModel.voice.showDemo(.locked, elapsed: 12)
            case .voiceReview:
                viewModel.voice.showDemo(.review, elapsed: 12)
            case .voiceHint:
                try? await Task.sleep(for: .milliseconds(300))
                viewModel.voice.showDemoHint()
            case .voicePermission:
                try? await Task.sleep(for: .milliseconds(300))
                viewModel.voice.showsPermissionNotice = true
            case .voiceUnplayed:
                fixtures?.addIncomingVoice(in: viewModel.chatID)
            }
        }

        @MainActor
        private static func run(_ demo: Demo, on viewModel: ConversationViewModel, fixtures: DesignFixturesService?) async {
            let chat = viewModel.chatID
            switch demo {
            case .send:
                viewModel.draft = "Görüşürüz 👋"
                viewModel.sendDraft()
            case .sticker:
                guard let sticker = BrandStickerCatalog.sticker(id: 103) else { return }
                viewModel.send(
                    sticker: BrandStickerSelection(sticker, language: BrandStickerLanguage(locale: viewModel.presentationLocale)))
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
            case .openPhoto:
                if let photo = viewModel.message("d2") { viewModel.openMedia(photo) }
            case .openPlace:
                if let place = viewModel.message("m3") { viewModel.openLocation(place) }
            case .openMap:
                if let share = viewModel.message("m4") { viewModel.openLiveMap(share) }
            case .playVideo:
                if let video = viewModel.message("m2") { viewModel.openMedia(video) }
            case .openFile:
                if let file = viewModel.message("m1") { viewModel.openMedia(file) }
            case .downloadFile:
                guard let fixtures else { return }
                let id = fixtures.addIncomingFile(in: chat, availability: .remote)
                try? await Task.sleep(for: .seconds(1))
                if let file = viewModel.message(id) { viewModel.openMedia(file) }
            case .sendVideo:
                fixtures?.simulateVideoSend(in: chat)
            case .sendPhotos:
                viewModel.draft = "Dünkü yürüyüşten"
                viewModel.sendDemoAttachment(.photo)
            case .playVoice:
                viewModel.toggleVoice("d4")
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
