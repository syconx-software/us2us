import SwiftUI

/// 5.3 · Mesaj eylemleri — long-press on a bubble.
///
/// The chat behind is blurred 14 pt at 55 % under `#06070e@35%` (drawn by the
/// screen). Here: a column centred in the screen above a 30 pt bottom margin
/// (gap 10) — the reaction bar (308 × 52, `surface`, r28, `shadowMd`, six
/// emoji + “+”), the lifted bubble (shadow `0 16px 40px #000@40%`) and the
/// action menu (250 wide, `surface·94%` + blur, r18, rows 46). The bubble
/// glides from where it was in the chat to its place in the column on the
/// critically damped spring; bar and menu fade and scale from 0.96, anchored
/// at the bubble. Dismissal reverses in 0.24 s.
struct MessageActionsOverlay: View {
    let viewModel: ConversationViewModel
    let context: ConversationViewModel.ActionsContext
    let message: Message

    @Environment(\.locale) private var locale
    @Environment(\.u2SafeArea) private var safeArea
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static let quickReactions = ["❤️", "😂", "👍", "😮", "😢", "🙏"]
    static let allReactions = [
        "❤️", "😂", "👍", "😮", "😢", "🙏", "🔥", "🎉", "👏", "😍", "🥲", "😅", "🤔", "😎",
        "🙌", "💯", "😡", "🤝", "✨", "👀", "🫶", "😴", "🤍", "💜",
    ]

    private var isVisible: Bool { viewModel.actionsVisible }
    /// The glide of the lifted bubble, on its own curve (not the lift's spring).
    @State private var isGlided = false
    private var isOutgoing: Bool { message.isOutgoing }
    /// Nothing reaches a blocked person: no reaction bar for them.
    /// Communities have no reactions.
    private var showsReactions: Bool {
        !message.isDeleted && !message.isPendingMedia && !viewModel.isBlocked && viewModel.community == nil
    }

    var body: some View {
        GeometryReader { proxy in
            let origin = proxy.frame(in: .global).origin
            let layout = ActionsLayout(
                screen: proxy.size, safeTop: safeArea.top, source: context.sourceFrame.offsetBy(dx: -origin.x, dy: -origin.y),
                isOutgoing: isOutgoing, showsReactions: showsReactions, menuHeight: FloatingMenu.height(of: menuItems))
            ZStack(alignment: .topLeading) {
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture { viewModel.dismissActions() }
                    .accessibilityHidden(true)

                // The lift shadow lies under the bar and the menu (they come
                // later in the design's column and cover it)…
                liftedLayer(layout) { size, shape in
                    Color.clear
                        .frame(width: size.width, height: size.height - (message.showsBubbleChips ? 14 : 0))
                        .u2BoxShadow(.bubbleLifted, in: shape)
                        .frame(width: size.width, height: size.height, alignment: .top)
                        .opacity(isVisible ? 1 : 0)
                }

                if showsReactions {
                    reactionBar
                        .opacity(isVisible ? 1 : 0)
                        .scaleEffect(isVisible ? 1 : 0.96, anchor: isOutgoing ? .bottomTrailing : .bottomLeading)
                        .blur(radius: isVisible ? 0 : 2)
                        .position(x: layout.reactionBar.midX, y: layout.reactionBar.midY)
                }

                FloatingMenu(items: menuItems)
                    .opacity(isVisible ? 1 : 0)
                    .scaleEffect(isVisible ? 1 : 0.96, anchor: isOutgoing ? .topTrailing : .topLeading)
                    .blur(radius: isVisible ? 0 : 2)
                    .position(x: layout.menu.midX, y: layout.menu.midY)

                // …the bubble itself is on top: it glides over the bar and the
                // menu as they fade in.
                liftedLayer(layout) { size, _ in
                    MessageBubbleView(message: message, isTail: context.isTail)
                        .frame(width: size.width, height: size.height, alignment: .top)
                }
            }
            .allowsHitTesting(isVisible)
        }
        .ignoresSafeArea()
        .onAppear(perform: viewModel.liftActions)
        // The glide runs in its own transaction: the design curve from rest
        // over 0.55 s (open), 0.24 s back (dismiss).
        .onChange(of: isVisible) { _, visible in
            withAnimation(visible ? U2Motion.soft(U2Motion.Duration.sheet) : U2Motion.micro) { isGlided = visible }
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityAction(.escape) { viewModel.dismissActions() }
    }

    // MARK: Lifted bubble

    /// The lifted copy of the bubble (or its shadow), placed and moved like
    /// the design's lift: it glides from where it was in the chat to its place
    /// in the column on the design curve — from rest, no flick — over 0.55 s,
    /// while the 1.02 lift and the fades run on the soft spring. Dismissal
    /// reverses in 0.24 s. Reduce Motion: no glide, a cross-fade in place.
    private func liftedLayer<Content: View>(
        _ layout: ActionsLayout, @ViewBuilder content: (CGSize, BubbleShape) -> Content
    ) -> some View {
        let shape = BubbleShape(direction: message.direction, isTail: context.isTail)
        let size = context.sourceFrame.size
        let target = layout.bubble
        let travel = target.minY + size.height / 2 - layout.source.midY
        return content(size, shape)
            .scaleEffect(isVisible || reduceMotion ? layout.bubbleScale : 1.02, anchor: isOutgoing ? .topTrailing : .topLeading)
            .opacity(reduceMotion && !isVisible ? 0 : 1)
            .offset(y: isGlided || reduceMotion ? travel : 0)
            .position(x: layout.source.midX, y: layout.source.midY)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    // MARK: Reaction bar

    private var reactionBar: some View {
        let own = viewModel.ownReaction(on: message)
        return ZStack {
            if viewModel.showsAllReactions {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 2) {
                        ForEach(Self.allReactions, id: \.self) { emoji in
                            emojiButton(emoji, isSelected: emoji == own)
                        }
                    }
                    .padding(.horizontal, 8)
                }
                .transition(.u2Fade)
            } else {
                HStack(spacing: 2) {
                    ForEach(Self.quickReactions, id: \.self) { emoji in
                        emojiButton(emoji, isSelected: emoji == own)
                    }
                    Button(action: viewModel.showAllReactions) {
                        PhosphorIcon(.plus, size: 18)
                            .foregroundStyle(Color.u2.text)
                            .frame(width: 40, height: 40)
                            .background(Circle().fill(Color.u2.fillGlass))
                            .contentShape(Circle())
                    }
                    .buttonStyle(CardButtonStyle())
                    .accessibilityLabel(Text("a11y.reactions.more", table: .conversation))
                }
                .padding(.horizontal, 8)
                .transition(.u2Fade)
            }
        }
        .frame(width: ActionsLayout.reactionBarWidth, height: ActionsLayout.reactionBarHeight)
        .background(Capsule().fill(Color.u2.surface))
        .clipShape(Capsule())
        .u2ShadowMd(cornerRadius: U2Radius.reactionBar)
    }

    private func emojiButton(_ emoji: String, isSelected: Bool) -> some View {
        Button {
            viewModel.react(emoji, to: message.id)
        } label: {
            Text(verbatim: emoji)
                .u2Font(.emojiReactionPicker, matchLineBox: false)
                // The design's glyph rows (❤️ 249.0–270.67 in the 234–288 bar).
                .offset(y: -4.0 / 3.0)
                .frame(width: 40, height: 40)
                .background(Circle().fill(Color.u2.accent900).opacity(isSelected ? 1 : 0))
                .contentShape(Circle())
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityLabel(Text(verbatim: emoji))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    // MARK: Menu

    /// Own messages: Yanıtla · Bilgi · Kopyala ‖ Herkesten sil · Benden sil.
    /// Incoming: Yanıtla · Kopyala ‖ Benden sil. A deleted trace: Benden sil.
    /// Kopyala exists only for text and is off for disappearing messages.
    /// A photo or video still being prepared (or that failed to be) is only
    /// on this phone: Benden sil, nothing else. With a blocked person
    /// neither Yanıtla nor Herkesten sil (nothing reaches them).
    private var menuItems: [FloatingMenuItem] {
        let id = message.id
        if message.isPendingMedia {
            return [
                FloatingMenuItem(id: "deleteMe", title: Text("actions.deleteMe", table: .conversation), glyph: .trashSimple, tone: .destructive) {
                    viewModel.deleteForMe(id)
                }
            ]
        }
        if viewModel.community != nil { return communityMenuItems }
        var items: [FloatingMenuItem] = []
        let isBlocked = viewModel.isBlocked
        if !message.isDeleted {
            if !isBlocked {
                items.append(
                    FloatingMenuItem(id: "reply", title: Text("actions.reply", table: .conversation), glyph: .arrowBendUpLeft) {
                        viewModel.reply(to: id, locale: locale)
                    })
            }
            if message.isOutgoing {
                items.append(
                    FloatingMenuItem(id: "info", title: Text("actions.info", table: .conversation), glyph: .info) {
                        viewModel.showInfo(for: id)
                    })
            }
            if message.copyableText != nil {
                items.append(
                    FloatingMenuItem(
                        id: "copy", title: Text("actions.copy", table: .conversation),
                        hint: message.isDisappearing ? Text("actions.copy.disabled", table: .conversation) : nil, glyph: .copy,
                        isEnabled: !message.isDisappearing
                    ) {
                        viewModel.copy(id)
                    })
            }
            // A file that is here: “Dosyalara kaydet” (proposed). Like copying,
            // off for disappearing messages — a saved copy would outlive them.
            // On something received its second line says that the sender
            // will see it (SAVED, PROTOCOL §8.4), every time.
            if case .file = message.content, message.mediaAvailability.isReady {
                let saveHint: Text? =
                    message.isDisappearing
                    ? Text("actions.copy.disabled", table: .conversation)
                    : message.isOutgoing ? nil : Text("viewer.save.hint", table: .conversation)
                items.append(
                    FloatingMenuItem(
                        id: "saveToFiles", title: Text("file.saveToFiles", table: .conversation),
                        hint: saveHint, glyph: .export,
                        isEnabled: !message.isDisappearing
                    ) {
                        viewModel.saveToFiles(id)
                    })
            }
        }
        if message.isOutgoing && !message.isDeleted && !isBlocked {
            items.append(
                FloatingMenuItem(
                    id: "deleteEveryone", title: Text("actions.deleteEveryone", table: .conversation), glyph: .trash, tone: .destructive,
                    startsGroup: !items.isEmpty
                ) {
                    viewModel.askDeleteForEveryone(id)
                })
            items.append(
                FloatingMenuItem(id: "deleteMe", title: Text("actions.deleteMe", table: .conversation), glyph: .trashSimple, tone: .destructive) {
                    viewModel.deleteForMe(id)
                })
        } else {
            items.append(
                FloatingMenuItem(
                    id: "deleteMe", title: Text("actions.deleteMe", table: .conversation), glyph: .trashSimple, tone: .destructive,
                    startsGroup: !items.isEmpty
                ) {
                    viewModel.deleteForMe(id)
                })
        }
        return items
    }
}

extension MessageActionsOverlay {
    /// A community message (no reactions or replies): own ones — Bilgi ·
    /// Kopyala ‖ Herkesten sil · Benden sil; others' — Kopyala ‖ Benden sil,
    /// and for the owner also Herkesten sil (moderation). A file that is here
    /// can be saved to Files (saving is not reported in a community).
    var communityMenuItems: [FloatingMenuItem] {
        let id = message.id
        var items: [FloatingMenuItem] = []
        if !message.isDeleted {
            if message.isOutgoing {
                items.append(
                    FloatingMenuItem(id: "info", title: Text("actions.info", table: .conversation), glyph: .info) {
                        viewModel.showInfo(for: id)
                    })
            }
            if message.copyableText != nil {
                items.append(
                    FloatingMenuItem(id: "copy", title: Text("actions.copy", table: .conversation), glyph: .copy) {
                        viewModel.copy(id)
                    })
            }
            if case .file = message.content, message.mediaAvailability.isReady {
                items.append(
                    FloatingMenuItem(id: "saveToFiles", title: Text("file.saveToFiles", table: .conversation), glyph: .export) {
                        viewModel.saveToFiles(id)
                    })
            }
        }
        let deletesForEveryone = viewModel.canDeleteForEveryone(message)
        if deletesForEveryone {
            items.append(
                FloatingMenuItem(
                    id: "deleteEveryone", title: Text("actions.deleteEveryone", table: .conversation), glyph: .trash,
                    tone: .destructive, startsGroup: !items.isEmpty
                ) {
                    viewModel.askDeleteForEveryone(id)
                })
        }
        items.append(
            FloatingMenuItem(
                id: "deleteMe", title: Text("actions.deleteMe", table: .conversation), glyph: .trashSimple, tone: .destructive,
                startsGroup: !deletesForEveryone && !items.isEmpty
            ) {
                viewModel.deleteForMe(id)
            })
        return items
    }
}

/// Where the 5.3 column goes: centred in the screen above a 30 pt margin,
/// right-aligned (outgoing) or left-aligned (incoming) at the 14 pt gutter. A
/// bubble too tall to fit is scaled down so the bar and the menu stay on screen.
private struct ActionsLayout {
    static let reactionBarWidth: CGFloat = 308
    static let reactionBarHeight: CGFloat = 52
    static let menuWidth: CGFloat = 250
    static let gap: CGFloat = 10
    static let bottomMargin: CGFloat = 30
    static let gutter: CGFloat = 14

    let source: CGRect
    let reactionBar: CGRect
    let bubble: CGRect
    let bubbleScale: CGFloat
    let menu: CGRect

    init(screen: CGSize, safeTop: CGFloat, source: CGRect, isOutgoing: Bool, showsReactions: Bool, menuHeight: CGFloat) {
        self.source = source
        let barHeight = showsReactions ? Self.reactionBarHeight + Self.gap : 0
        let available = screen.height - Self.bottomMargin
        let minTop = safeTop + 8
        let room = available - minTop - barHeight - Self.gap - menuHeight
        let scale = source.height > room ? max(0.4, room / source.height) : 1
        let bubbleHeight = source.height * scale
        let column = barHeight + bubbleHeight + Self.gap + menuHeight
        let top = max(minTop, (available - column) / 2)

        func aligned(width: CGFloat, y: CGFloat, height: CGFloat) -> CGRect {
            let x = isOutgoing ? screen.width - Self.gutter - width : Self.gutter
            return CGRect(x: x, y: y, width: width, height: height)
        }

        reactionBar = aligned(width: Self.reactionBarWidth, y: top, height: Self.reactionBarHeight)
        let bubbleY = top + barHeight
        bubble = CGRect(x: source.minX, y: bubbleY, width: source.width * scale, height: bubbleHeight)
        bubbleScale = scale
        menu = aligned(width: Self.menuWidth, y: bubbleY + bubbleHeight + Self.gap, height: menuHeight)
    }
}
