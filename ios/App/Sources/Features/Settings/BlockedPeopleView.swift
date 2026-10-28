import SwiftUI

/// 7.1 › Gizlilik › Engellenenler (proposed, in 7.1's language): everyone
/// the user blocked — also those whose chat is gone (Ghost erased it, or it
/// was deleted), who could otherwise only be found again through 4.2. One
/// C13 card, a row per person, and under it what a block means. “Engeli
/// kaldır” needs no confirmation, as on the contact screen: the row
/// collapses at once and the person is back as before the block. With
/// nobody blocked, one line in 4.3's empty-state style.
struct BlockedPeopleView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Unblocked here and not yet gone from the service's list (the live
    /// runtime reloads it a moment later): the row leaves at once.
    @State private var leaving: Set<ChatID> = []

    private var messenger: any MessengerService { SettingsFlow.of(model).messenger }

    var body: some View {
        let blocked = messenger.blockedPeople
        let people = blocked.filter { !leaving.contains($0.id) }
        U2Screen {
            ScrollView {
                VStack(spacing: 0) {
                    CenteredTitleHeader(Text("settings.blocked", table: .settings)) {
                        U2GlassIconButton(.caretLeft, label: Text("a11y.back"), action: model.router.pop)
                            .accessibilityIdentifier("blocked.back")
                    }
                    ZStack(alignment: .top) {
                        if people.isEmpty {
                            emptyState
                                .transition(.u2Fade)
                        } else {
                            list(people)
                                .transition(.opacity)
                        }
                    }
                    .padding(EdgeInsets(top: 18, leading: 16, bottom: 0, trailing: 16))
                    .u2Bottom(U2Spacing.ctaBottom)
                    .u2Animation(U2Motion.state, value: people.map(\.id))
                }
            }
            .scrollIndicators(.hidden)
            .overlay(alignment: .top) { SettingsStatusBarFade() }
        }
        .onChange(of: blocked.map(\.id)) { _, ids in
            leaving.formIntersection(ids)
        }
    }

    private func list(_ people: [BlockedPerson]) -> some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            GroupedCard {
                ForEach(Array(people.enumerated()), id: \.element.id) { index, person in
                    VStack(spacing: 0) {
                        if index > 0 { GroupedSeparator(inset: BlockedPersonRow.separatorInset) }
                        BlockedPersonRow(person: person) { unblock(person) }
                    }
                    .transition(reduceMotion ? .opacity : .asymmetric(insertion: .u2Fade, removal: .u2Collapse(cornerRadius: 0)))
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("blocked.list")
            Text("blocked.footnote", table: .settings)
                .u2Font(.footnote)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
        }
    }

    private var emptyState: some View {
        U2EmptyState(glyph: .prohibit, message: Text("blocked.empty", table: .settings), identifier: "blocked.empty")
    }

    /// No confirmation: it can be undone at once (Engelle on the contact
    /// screen). The row goes back if the unblock could not be stored.
    private func unblock(_ person: BlockedPerson) {
        U2Haptics.selection()
        leaving.insert(person.id)
        let messenger = messenger
        let announcement = StringTable.contact.string("a11y.unblocked \(person.name)", locale: locale)
        Task {
            if await messenger.unblock(person) {
                AccessibilityNotification.Announcement(announcement).post()
            } else {
                leaving.remove(person.id)
            }
        }
    }
}

/// A row: C13's geometry with the avatar in the tile's place — avatar 44
/// (the initial: no picture for someone blocked) · the name in
/// `monoUsername`, with “Kullanıcı adı bilinmiyor” in `captionCompact`
/// under it when the phone keeps none · “Engeli kaldır” (`rowTitle`
/// `accent300`). 62 high (pad 9 14), gap 13. At accessibility text sizes
/// “Engeli kaldır” goes under the name, which keeps the row's width.
private struct BlockedPersonRow: View {
    let person: BlockedPerson
    let onUnblock: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// The dotted separator starts under the name (14 + 44 + 13).
    static let separatorInset: CGFloat = 71

    var body: some View {
        HStack(spacing: U2Spacing.rowGap) {
            Avatar(username: person.name, palette: person.palette, size: .request, picture: person.avatar)
                .accessibilityHidden(true)
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 2) {
                    name
                    unblockButton
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                name
                    .frame(maxWidth: .infinity, alignment: .leading)
                unblockButton
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .frame(minHeight: 62)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("blocked.row.\(person.id)")
    }

    private var name: some View {
        let lines = dynamicTypeSize.isAccessibilitySize ? 2 : 1
        return VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: person.name)
                .u2Font(.monoUsername)
                .foregroundStyle(Color.u2.text)
                .lineLimit(lines)
                .truncationMode(.tail)
            if !person.isUsernameKnown {
                Text("blocked.unknownUsername", table: .settings)
                    .u2Font(.captionCompact)
                    .foregroundStyle(Color.u2.textSecondary)
                    .lineLimit(lines)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var unblockButton: some View {
        Button(action: onUnblock) {
            Text("block.unblock")
        }
        .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.iconButton, fillsWidth: false))
        .fixedSize()
        .accessibilityHint(Text("a11y.unblock.hint", table: .contact))
        .accessibilityIdentifier("blocked.unblock.\(person.id)")
    }
}

#Preview("7.1 › Engellenenler") {
    let model = AppModel.preview()
    (model.messenger as? DesignFixturesService)?.loadBlockedPeople()
    return BlockedPeopleView()
        .environment(model)
        .environment(\.locale, model.locale)
}
