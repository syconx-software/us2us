#if DEBUG
    import SwiftUI

    /// DEBUG-only screen harness of 04 · Sohbetler: opens every screen and
    /// state directly, in its exact design state, for screenshots and design
    /// comparisons. Use with the foundation's launch arguments:
    ///
    /// ```sh
    /// xcrun simctl launch <sim> com.syconx.us2us -U2Route chats:4.2 -U2FrozenClock YES \
    ///   -U2Theme dark -U2Language tr
    /// ```
    ///
    /// | `-U2Route chats:<id>` (or `-U2ChatsScreen <id>`) | shows |
    /// |---|---|
    /// | `4.1` | chat list (add `-U2GhostUrgent YES` for the last-hour variant) |
    /// | `4.1-connecting` · `4.1-closed` | line connecting / closed (proposed copy, packets hidden) |
    /// | `4.1-empty` | no chats, no requests (proposed copy) |
    /// | `4.1-qr` · `4.1-mycode` | the QR menu · the own-code sheet (proposed) |
    /// | `4.2` | new chat, “deniz” found (focused look, no keyboard) |
    /// | `4.2-keyboard` | the same with the real focus (keyboard up) |
    /// | `4.2-empty` · `4.2-searching` · `4.2-notfound` · `4.2-existing` · `4.2-self` | the other lookup states |
    /// | `4.2-mycode` | “Kodumu göster” pushed inside the sheet |
    /// | `4.3` · `4.3-empty` · `4.3-block` | requests · none left · the block confirmation |
    /// | `4.4` · `4.4-media` · `4.4-link` | preview of kuzey.dev · n.aksoy (photo) · hediye_kazan (link) |
    ///
    /// `-U2Route newChat` or `newChat:<username>` opens the 4.2 sheet (sheets
    /// are not stack routes); `-U2Route requests` / `request:<id>` open 4.3 /
    /// 4.4 (see `LaunchRoute`).
    enum ChatsHarness {
        enum Screen: String, CaseIterable, Sendable {
            case list = "4.1"
            case listConnecting = "4.1-connecting"
            case listClosed = "4.1-closed"
            case listEmpty = "4.1-empty"
            case listQR = "4.1-qr"
            case listMyCode = "4.1-mycode"
            case newChat = "4.2"
            case newChatKeyboard = "4.2-keyboard"
            case newChatEmpty = "4.2-empty"
            case newChatSearching = "4.2-searching"
            case newChatNotFound = "4.2-notfound"
            case newChatExisting = "4.2-existing"
            case newChatSelf = "4.2-self"
            case newChatMyCode = "4.2-mycode"
            case requests = "4.3"
            case requestsEmpty = "4.3-empty"
            case requestsBlock = "4.3-block"
            case preview = "4.4"
            case previewMedia = "4.4-media"
            case previewLink = "4.4-link"
        }

        /// `-U2Route chats:<id>` (e.g. `chats:4.2-notfound`), or `-U2ChatsScreen <id>`.
        static var current: Screen? {
            if let id = UserDefaults.standard.string(forKey: "U2ChatsScreen") { return Screen(rawValue: id) }
            return LaunchRoute.current?.state(for: .chats).flatMap(Screen.init(rawValue:))
        }

        /// `-U2ChatsDemo <action>`: performs one interaction by itself ~1.5 s
        /// after the screen appears, so transitions can be recorded and checked
        /// without touching the device (screenshots at intervals).
        enum Demo: String, Sendable {
            /// 4.2: “Mesaj yaz” once the result is in → sheet down, 5.1 pushed.
            case write
            /// 4.4: “Kabul et” → decision bar slides down, composer fades in, 5.1.
            case accept
            /// 4.4: “Sil” → back to 4.3, the card collapses.
            case deletePreview = "delete-preview"
            /// 4.3: “Sil” on n.aksoy → the card fades and collapses.
            case delete
            /// 4.3: “Kabul et” on kuzey.dev → 5.1 replaces 4.3.
            case acceptCard = "accept-card"
            /// 4.3: open the first card → 4.4 pushed.
            case openCard = "open-card"
            /// 4.1: QR menu → “Kodumu göster”.
            case qrMyCode = "qr-mycode"
            /// 4.1: tap deniz's row → 5.1 pushed.
            case openChat = "open-chat"
            /// 4.1: tap compose → the 4.2 sheet rises.
            case compose
            /// 4.1 → 5.1 → 4.1, five times (with `-U2FrameProbe YES -U2FrameProbeLog YES`).
            case pushPop = "push-pop"
            /// The 4.2 sheet up and down, five times (frame-probe runs).
            case sheetCycle = "sheet-cycle"
        }

        static var demo: Demo? {
            UserDefaults.standard.string(forKey: "U2ChatsDemo").flatMap(Demo.init(rawValue:))
        }

        /// Waits the demo delay; `true` when `demo` is the requested one.
        static func waitForDemo(_ demo: Demo, delay: Duration = .milliseconds(1500)) async -> Bool {
            guard Self.demo == demo else { return false }
            try? await Task.sleep(for: delay)
            return !Task.isCancelled
        }

        /// `-U2StressChats <n>`: the list holds `n` chats — the design's own
        /// first (so “deniz” still opens 5.1), then synthetic ones. For the
        /// scroll / navigation performance tests.
        static var stressCount: Int? {
            let count = UserDefaults.standard.integer(forKey: "U2StressChats")
            return count > 0 ? count : nil
        }

        /// `-U2StressTicks YES` (with `-U2StressChats`): the list keeps
        /// changing like a busy evening — see `StressFeed.tick()`.
        static var stressTicks: Bool { UserDefaults.standard.bool(forKey: "U2StressTicks") }

        /// The stress list. Changes arrive without a transaction, exactly
        /// like the messenger's own updates, so the list's own animations
        /// are what is measured.
        @Observable
        @MainActor
        final class StressFeed {
            private(set) var chats: [ChatSummary]
            private(set) var tickCount = 0
            private let clock: U2Clock
            private var random = SplitMix(seed: 0x75_32_75_73)
            private var task: Task<Void, Never>?

            init(base: [ChatSummary], count: Int, clock: U2Clock) {
                self.clock = clock
                var chats = Array(base.prefix(count))
                let now = clock.now
                let kinds: [MessagePreviewKind] = [
                    .text("Yarın görüşelim mi?"), .photo, .voice, .text("Tamam, haber ver"), .sticker,
                    .file("notlar.pdf"), .text("Geldim, kapıdayım"), .location, .disappearing,
                ]
                let statuses: [MessageStatus] = [.sent, .delivered, .read]
                var index = 0
                while chats.count < count {
                    let username = String(format: "kisi.%03d", index)
                    let peer = Peer(
                        username: username, palette: .forUsername(username), isVerified: index % 7 == 0,
                        isAcceptedContact: true, presence: index % 5 == 0 ? .online : .hidden)
                    let outgoing = index % 3 != 0
                    let ghost: GhostState =
                        index % 4 == 0
                        ? GhostState(isEnabled: true, period: .oneDay, deadline: now.addingTimeInterval(Double(3_000 + index * 397)))
                        : .off
                    chats.append(
                        ChatSummary(
                            id: username, peer: peer,
                            lastMessage: MessagePreview(
                                kind: kinds[index % kinds.count], direction: outgoing ? .outgoing : .incoming,
                                status: outgoing ? statuses[index % statuses.count] : nil,
                                sentAt: now.addingTimeInterval(-Double(600 + index * 1_800))),
                            unreadCount: outgoing ? 0 : index % 4, ghost: ghost, disappearing: .off))
                    index += 1
                }
                self.chats = chats
            }

            /// Starts the ticks (every 1.2 s) until the screen goes away.
            func startTicking() {
                guard task == nil else { return }
                task = Task { [weak self] in
                    while !Task.isCancelled {
                        try? await Task.sleep(for: .milliseconds(1200))
                        guard !Task.isCancelled else { return }
                        self?.tick()
                    }
                }
            }

            func stop() {
                task?.cancel()
                task = nil
            }

            /// One change, in turn: a message arrives in a chat a few rows
            /// down (it moves to the top, its counter goes up, the preview
            /// changes); the newest outgoing message is delivered, then read;
            /// a counter in place ticks up.
            func tick() {
                tickCount += 1
                let now = clock.now
                switch tickCount % 3 {
                case 1:
                    let from = 2 + Int(random.next() % 8)
                    guard from < chats.count else { return }
                    var chat = chats.remove(at: from)
                    chat.lastMessage = MessagePreview(
                        kind: .text("Yeni mesaj \(tickCount)"), direction: .incoming, status: nil, sentAt: now)
                    chat.unreadCount += 1
                    chats.insert(chat, at: 0)
                case 2:
                    guard let index = chats.prefix(12).firstIndex(where: { $0.lastMessage?.direction == .outgoing }),
                        let status = chats[index].lastMessage?.status
                    else { return }
                    chats[index].lastMessage?.status = status == .read ? .sent : status == .sent ? .delivered : .read
                default:
                    guard let index = chats.prefix(8).firstIndex(where: { $0.unreadCount > 0 }) else { return }
                    chats[index].unreadCount += 1
                }
            }
        }

        /// `-U2StressAutoScroll <cycles>` (with `-U2FrameProbe YES`): flings
        /// the list by itself — three flicks down, three up, per cycle, each
        /// a 4 000 pt/s fling decelerating like UIKit's normal rate — then
        /// prints the frame probe (`FRAMEPROBE …`) to the console. Measures
        /// scrolling without XCUITest attached (no accessibility tree being
        /// built for the test runner): `xcrun simctl launch --console-pty …`.
        static var autoScrollCycles: Int {
            UserDefaults.standard.integer(forKey: "U2StressAutoScroll")
        }

        @MainActor
        static func runAutoScroll(cycles: Int) async {
            try? await Task.sleep(for: .seconds(2.5))
            guard let scrollView = findListScrollView() else {
                print("FRAMEPROBE no scroll view")
                return
            }
            let probe = FrameProbe.shared
            let before = probe.summary
            let start = Date()
            for _ in 0..<cycles {
                for direction in [1.0, 1.0, 1.0, -1.0, -1.0, -1.0] {
                    await Fling(scrollView: scrollView, velocity: 4_000 * direction).run()
                    try? await Task.sleep(for: .milliseconds(150))
                }
            }
            print("FRAMEPROBE before \(before)")
            print("FRAMEPROBE after \(probe.summary) seconds=\(String(format: "%.2f", Date().timeIntervalSince(start)))")
        }

        @MainActor
        private static func findListScrollView() -> UIScrollView? {
            let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
            var stack: [UIView] = windows
            while let view = stack.popLast() {
                if let scroll = view as? UIScrollView, scroll.contentSize.height > 10_000 { return scroll }
                stack.append(contentsOf: view.subviews)
            }
            return nil
        }

        /// UIKit's deceleration: the speed falls by 0.998 per millisecond.
        @MainActor
        private final class Fling: NSObject {
            let scrollView: UIScrollView
            let velocity: Double
            private var link: CADisplayLink?
            private var startTime: CFTimeInterval?
            private var startOffset: CGFloat = 0
            private var continuation: CheckedContinuation<Void, Never>?

            init(scrollView: UIScrollView, velocity: Double) {
                self.scrollView = scrollView
                self.velocity = velocity
            }

            func run() async {
                await withCheckedContinuation { continuation in
                    self.continuation = continuation
                    let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
                    link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
                    link.add(to: .main, forMode: .common)
                    self.link = link
                }
            }

            @objc private func tick(_ link: CADisplayLink) {
                if startTime == nil {
                    startTime = link.timestamp
                    startOffset = scrollView.contentOffset.y
                }
                let ms = (link.timestamp - (startTime ?? link.timestamp)) * 1000
                let rate = 0.998
                let travelled = velocity / 1000 * (1 - pow(rate, ms)) / (1 - rate)
                let minOffset = -scrollView.adjustedContentInset.top
                let maxOffset = max(0, scrollView.contentSize.height - scrollView.bounds.height + scrollView.adjustedContentInset.bottom)
                let target = startOffset + travelled
                let offset = min(max(target, minOffset), maxOffset)
                scrollView.contentOffset.y = offset
                let speed = abs(velocity) * pow(rate, ms)
                if ms > 0 && (speed < 20 || target <= minOffset || target >= maxOffset) {
                    link.invalidate()
                    self.link = nil
                    continuation?.resume()
                    continuation = nil
                }
            }
        }

        /// A tiny deterministic generator (the same sequence every run).
        struct SplitMix {
            var seed: UInt64
            mutating func next() -> UInt64 {
                seed &+= 0x9E37_79B9_7F4A_7C15
                var z = seed
                z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
                z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
                return z ^ (z >> 31)
            }
        }

        /// What `ChatsView` applies once on appear.
        struct State {
            var isApplied = false
            var hidesChats = false
            var stressFeed: StressFeed?
            var newChatQuery: String?
            var newChatDebug = NewChatModel.DebugOptions()
            var showsQRMenu = false
            var showsMyCode = false
        }

        @MainActor
        static func apply(to model: AppModel, state: inout State) {
            if let route = LaunchRoute.current, route.feature == .newChat {
                state.newChatQuery = route.target ?? ""
            }
            if let count = stressCount {
                let feed = StressFeed(base: model.messenger.chats, count: count, clock: model.clock)
                if stressTicks { feed.startTicking() }
                state.stressFeed = feed
                if autoScrollCycles > 0 {
                    Task { await runAutoScroll(cycles: autoScrollCycles) }
                }
            }
            guard let screen = current else { return }
            let messenger = model.messenger
            let fixtures = messenger as? DesignFixturesService

            func push(_ path: [AppRoute]) {
                if model.router.isAtRoot { model.router.setPath(path) }
            }

            func newChat(_ query: String, focusLook: Bool = true, searching: Bool = false, myCode: Bool = false) {
                state.newChatQuery = query
                state.newChatDebug = NewChatModel.DebugOptions(
                    freezesSearching: searching, forcesFocusChrome: focusLook, startsOnMyCode: myCode)
            }

            func removeAllRequests() {
                Task { @MainActor in
                    for request in messenger.requests { await messenger.delete(request) }
                }
            }

            switch screen {
            case .list:
                break
            case .listConnecting:
                fixtures?.setConnection(.connecting)
            case .listClosed:
                fixtures?.setConnection(.closed)
            case .listEmpty:
                state.hidesChats = true
                removeAllRequests()
            case .listQR:
                state.showsQRMenu = true
            case .listMyCode:
                state.showsMyCode = true
            case .newChat:
                newChat("deniz")
            case .newChatKeyboard:
                newChat("deniz", focusLook: false)
            case .newChatEmpty:
                newChat("")
            case .newChatSearching:
                newChat("deniz", searching: true)
            case .newChatNotFound:
                newChat("deniz.yilmaz")
            case .newChatExisting:
                newChat("mert.k")
            case .newChatSelf:
                newChat(messenger.account?.username ?? DesignFixtures.ownUsername)
            case .newChatMyCode:
                newChat("", focusLook: false, myCode: true)
            case .requests, .requestsBlock:
                push([.requests])
            case .requestsEmpty:
                push([.requests])
                removeAllRequests()
            case .preview:
                push([.requests, .requestPreview("kuzey.dev")])
            case .previewMedia:
                push([.requests, .requestPreview("n.aksoy")])
            case .previewLink:
                push([.requests, .requestPreview("hediye_kazan")])
            }
        }
    }
#endif
