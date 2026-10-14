import SwiftUI

/// A community's conversation (proposed): 4.3's centred-title header (back,
/// the name, “…” for members and management) over C15 bubbles — incoming
/// ones under their sender's name — and a text-only composer. The order is
/// the order this phone received them; the sender's clock only labels them.
/// A send clears the field once the message is queued for every member.
struct CommunityConversationView: View {
    let communityID: CommunityID
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @State private var text = ""
    @State private var isSending = false
    @State private var failure: CommunityProblem?
    @State private var showsManage = false
    /// The last name shown, kept while the screen pops after a leave or delete.
    @State private var knownName = ""

    private var service: (any CommunitiesService)? { model.messenger.communities }

    var body: some View {
        let row = service?.row(communityID)
        U2Screen {
            ZStack(alignment: .top) {
                messages(row: row)
                ChatsHeaderBackdrop(isVisible: true)
                CenteredTitleHeader(Text(verbatim: CommunityNames.title(row?.name, known: knownName, locale: locale))) {
                    U2GlassIconButton(.caretLeft, label: Text("a11y.back"), action: model.router.pop)
                        .accessibilityIdentifier("community.back")
                } trailing: {
                    U2GlassIconButton(.dotsThree, label: Text("a11y.manage", table: .communities)) { showsManage = true }
                        .accessibilityIdentifier("community.manage")
                        .disabled(row?.isOpenable != true)
                }
            }
        }
        .onChange(of: row?.name, initial: true) { _, name in knownName = CommunityNames.remembered(name, known: knownName) }
        .task(id: communityID) {
            do throws(CommunityProblem) { try await service?.open(communityID) } catch { failure = error }
        }
        .onAppear { service?.screenAppeared() }
        .onDisappear {
            service?.screenDisappeared()
            service?.close(communityID)
        }
        .u2Sheet(isPresented: $showsManage) {
            CommunityManageSheet(communityID: communityID) { showsManage = false } onGone: {
                showsManage = false
                model.router.pop()
            }
            .environment(\.locale, locale)
            .environment(\.u2Clock, model.clock)
        }
        .privacySensitive()
    }

    private func messages(row: CommunityRow?) -> some View {
        let items = service?.openMessages ?? []
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: U2Spacing.bubbleGap) {
                    SystemPill(glyph: .shieldCheck, text: Text("conversation.intro", table: .communities))
                        .padding(.bottom, 10)
                    if let problem = failure ?? service?.problem {
                        CommunityProblemNote(problem: problem) {
                            if failure != nil { failure = nil } else { service?.dismissProblem() }
                        }
                        .padding(.bottom, 8)
                        .transition(.u2Fade)
                    }
                    ForEach(Array(items.enumerated()), id: \.element.id) { index, message in
                        let showsSender = !message.isOutgoing && (index == 0 || items[index - 1].senderID != message.senderID)
                        CommunityMessageRow(message: message, showsSender: showsSender)
                            .id(message.id)
                    }
                }
                .padding(.horizontal, U2Spacing.chatGutter)
                .u2Top(116)
                .padding(.bottom, 12)
                .u2Animation(U2Motion.state, value: failure ?? service?.problem)
            }
            .defaultScrollAnchor(.bottom)
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: items.last?.id) { _, last in
                if let last { withAnimation(U2Motion.state) { proxy.scrollTo(last, anchor: .bottom) } }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if row?.isOpenable == true {
                    CommunityComposer(text: $text, isSending: isSending, onSend: send)
                } else if row != nil {
                    Text("conversation.ended", table: .communities)
                        .u2Font(.callout)
                        .foregroundStyle(Color.u2.textSecondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, U2Spacing.screenGutter)
                        .u2Bottom(U2Spacing.ctaBottom)
                        .accessibilityIdentifier("community.ended")
                }
            }
        }
    }

    /// The field clears only once the message is stored and queued (and
    /// only if it still holds what was sent).
    private func send() {
        guard let service, !isSending else { return }
        let body = text
        guard !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, body.utf8.count <= CommunityLimits.maxTextBytes else { return }
        isSending = true
        Task {
            defer { isSending = false }
            do throws(CommunityProblem) {
                try await service.send(body, to: communityID)
                if text == body { text = "" }
            } catch {
                failure = error
            }
        }
    }
}

// MARK: - Message row

private struct CommunityMessageRow: View {
    let message: CommunityMessage
    let showsSender: Bool
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: message.isOutgoing ? .trailing : .leading, spacing: 4) {
            if showsSender {
                Text(verbatim: CommunityNames.member(username: message.senderName, shortID: message.senderShortID, locale: locale))
                    .u2Font(message.senderName == nil ? .captionCompact : .monoMeta)
                    .foregroundStyle(Color.u2.textSecondary)
                    .padding(.horizontal, 6)
                    .padding(.top, 6)
            }
            MessageBubble(direction: message.isOutgoing ? .outgoing : .incoming) {
                Text(verbatim: message.text)
                    .u2Font(.bubble)
                    .foregroundStyle(message.isOutgoing ? Color.u2.onBubbleOutgoing : Color.u2.text)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                BubbleMeta(time: message.sentAt, status: status)
                    .u2BubbleTrailing()
            }
            if let note {
                FlagTag(glyph: .warningCircle, text: note, color: message.status == .failed ? Color.u2.danger : Color.u2.textSecondary)
                    .padding(.horizontal, 6)
                    .frame(maxWidth: 290, alignment: .trailing)
            }
        }
        .frame(maxWidth: .infinity, alignment: message.isOutgoing ? .trailing : .leading)
        .accessibilityElement(children: .combine)
    }

    private var status: MessageStatus? {
        switch message.status {
        case .received: nil
        case .sending: .sending
        case .sent: .sent
        case .partial, .failed: .failed
        }
    }

    private var note: Text? {
        switch message.status {
        case .partial: Text("message.partial", table: .communities)
        case .failed: Text("message.failed", table: .communities)
        case .received, .sending, .sent: nil
        }
    }
}

// MARK: - Composer

/// C21 without media: the field (r22 `surface`, hairline `text·14%`,
/// grows to 5 lines) and the send button (44, 1 pt `accent`, the mic glow,
/// `ph:paper-plane-tilt`), on `composerFade`.
private struct CommunityComposer: View {
    @Binding var text: String
    let isSending: Bool
    let onSend: () -> Void
    @Environment(\.u2SafeArea) private var safeArea

    private var canSend: Bool {
        !isSending && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && text.utf8.count <= CommunityLimits.maxTextBytes
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.composerField, style: .continuous)
        HStack(alignment: .bottom, spacing: 8) {
            TextField(text: $text, axis: .vertical) {
                Text("composer.placeholder").foregroundStyle(Color.u2.textPlaceholder)
            }
            .u2Font(.rowTitle)
            .foregroundStyle(Color.u2.text)
            .tint(Color.u2.accent400)
            .lineLimit(1...5)
            .padding(.vertical, (U2Size.composer - 24.8) / 2)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: U2Size.composer, alignment: .leading)
            .background(shape.fill(Color.u2.surface))
            .u2InnerStroke(Color.u2.strokeField, width: 0.5, in: shape)
            .accessibilityIdentifier("community.composer")
            Button(action: onSend) {
                Group {
                    if isSending {
                        SpinningGlyph(size: 21)
                    } else {
                        PhosphorIcon(.paperPlaneTilt, size: 21).offset(y: 2.0 / 3.0)
                    }
                }
                .foregroundStyle(Color.u2.accent300)
                .frame(width: U2Size.iconButton, height: U2Size.iconButton)
                .overlay(Circle().strokeBorder(Color.u2.accent, lineWidth: 1))
                .u2BoxShadow(.glowMic, in: Circle())
                .contentShape(Circle())
                .opacity(canSend || isSending ? 1 : U2Opacity.disabled)
            }
            .buttonStyle(CardButtonStyle())
            .disabled(!canSend)
            .accessibilityLabel(Text("a11y.composer.send"))
            .accessibilityIdentifier("community.send")
        }
        .padding(.top, 14)
        .padding(.horizontal, U2Spacing.chatComposerGutter)
        .padding(.bottom, max(12, safeArea.bottom(36)))
        .background(Color.u2.composerFade.allowsHitTesting(false))
        .u2Animation(U2Motion.micro, value: canSend)
    }
}
