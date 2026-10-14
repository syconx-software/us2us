import SwiftUI
import Us2usKit

// Pieces shared by the communities screens (Features/Communities).

// MARK: - Entry card (4.1)

/// 4.1 › Topluluklar (proposed): a card in the requests card's shape
/// (`surface` r18, pad 13 14, gap 13; 42 pt round `accent900` tile with
/// `ph:chat-circle-dots`; title `rowTitleEmphasis`, subtitle `caption`;
/// chevron). It opens the communities screen.
struct CommunitiesCard: View {
    let count: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 13) {
                PhosphorIcon(.chatCircleDots, size: 21)
                    .foregroundStyle(Color.u2.accent300)
                    .frame(width: 42, height: 42)
                    .background(Circle().fill(Color.u2.accent900))
                    .overlay(Circle().strokeBorder(Color.u2.accent800, lineWidth: 1))
                VStack(alignment: .leading, spacing: 2) {
                    Text("card.title", table: .communities)
                        .u2Font(.rowTitleEmphasis)
                        .foregroundStyle(Color.u2.text)
                    Group {
                        if count == 0 {
                            Text("card.subtitle.empty", table: .communities)
                        } else {
                            LocalizedText("card.subtitle \(count)", table: .communities)
                        }
                    }
                    .u2Font(.caption)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                PhosphorIcon(.caretRight, size: 15)
                    .foregroundStyle(Color.u2.textTertiary)
            }
            .padding(EdgeInsets(top: 13, leading: 14, bottom: 13, trailing: 14))
            .background(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous).fill(Color.u2.surface))
            .contentShape(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous))
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Problems

extension CommunityProblem {
    /// What to say, in the screen's language.
    func message(locale: Locale) -> String {
        let table = StringTable.communities
        switch self {
        case .offline: return table.string("problem.offline", locale: locale)
        case .unsupported: return table.string("problem.unsupported", locale: locale)
        case .verification: return table.string("problem.verification", locale: locale)
        case .memberKey(let name, let shortID):
            let who = CommunityNames.member(username: name, shortID: shortID, locale: locale)
            return table.string("problem.memberKey \(who)", locale: locale)
        case .stale: return table.string("problem.stale", locale: locale)
        case .full: return table.string("problem.full", locale: locale)
        case .notFound: return table.string("problem.notFound", locale: locale)
        case .rateLimited: return table.string("problem.rateLimited", locale: locale)
        case .invalidName: return table.string("problem.invalidName", locale: locale)
        case .invalidInvite: return table.string("problem.invalidInvite", locale: locale)
        case .ownerCannotLeave: return table.string("problem.ownerCannotLeave", locale: locale)
        case .failed: return table.string("problem.failed", locale: locale)
        }
    }
}

/// A problem shown above a screen's content (`NoteCard`, danger ring); a tap dismisses it.
struct CommunityProblemNote: View {
    let problem: CommunityProblem
    let onDismiss: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        Button(action: onDismiss) {
            NoteCard(
                glyph: .warningCircle, title: Text("problem.title", table: .communities),
                message: Text(verbatim: problem.message(locale: locale)))
                .contentShape(Rectangle())
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityHint(Text("a11y.problem.dismiss", table: .communities))
        .accessibilityIdentifier("communities.problem")
    }
}

// MARK: - Names

enum CommunityNames {
    /// A community's title: its name, or “Gizli topluluk” without one.
    static func title(_ name: String, locale: Locale) -> String {
        name.isEmpty ? StringTable.communities.string("community.unnamed", locale: locale) : name
    }

    /// The title while a screen is up: the community's name, else the last
    /// one this screen showed — leaving or deleting drops the row (and a
    /// private community's name) before the screen has gone.
    static func title(_ name: String?, known: String, locale: Locale) -> String {
        title(name.flatMap { $0.isEmpty ? nil : $0 } ?? known, locale: locale)
    }

    /// The name to remember for ``title(_:known:locale:)``.
    static func remembered(_ name: String?, known: String) -> String {
        guard let name, !name.isEmpty else { return known }
        return name
    }

    /// A member: the username this phone knows, else “Üye · 1a2b3c4d”.
    static func member(username: String?, shortID: String, locale: Locale) -> String {
        username ?? StringTable.communities.string("member.unknown \(shortID)", locale: locale)
    }
}

// MARK: - Disclosure

/// What the relay learns, shown before creating, joining or redeeming:
/// unlike one-to-one chats it sees members and who sends to whom, and
/// public searches reach it.
struct CommunityDisclosure: View {
    var isPrivate = false

    var body: some View {
        InfoBanner(
            glyph: .info,
            message: isPrivate
                ? Text("disclosure.private", table: .communities) : Text("disclosure.body", table: .communities))
        .accessibilityIdentifier("communities.disclosure")
    }
}

// MARK: - Text field

/// A C23 field (r16, `bg` on a sheet, the accent ring while focused) for a
/// community name, an invitation code or a search.
struct CommunityTextField: View {
    @Binding var text: String
    let prompt: Text
    var isMonospaced = false
    var submitLabel: SubmitLabel = .done
    var onSubmit: () -> Void = {}
    var fill: Color = Color.u2.bg

    @FocusState private var isFocused: Bool

    var body: some View {
        TextField(text: $text, prompt: prompt.foregroundStyle(Color.u2.textPlaceholder)) { prompt }
            .u2Font(isMonospaced ? .monoMeta : .rowTitle)
            .foregroundStyle(Color.u2.text)
            .tint(Color.u2.accent400)
            .textInputAutocapitalization(isMonospaced ? .never : .sentences)
            .autocorrectionDisabled(isMonospaced)
            .submitLabel(submitLabel)
            .focused($isFocused)
            .onSubmit(onSubmit)
            .padding(.horizontal, 16)
            .modifier(U2FieldChrome(isFocused: isFocused, fill: fill, height: U2Size.fieldSearch))
            .contentShape(Rectangle())
            .onTapGesture { isFocused = true }
    }
}

// MARK: - Confirmations

/// The irreversible community steps, each confirmed in the floating sheet.
enum CommunityConfirmation: Identifiable, Equatable {
    case leave(CommunityID)
    case withdraw(CommunityID)
    case forget(CommunityID)
    case delete(CommunityID)
    case removeMember(String, name: String, in: CommunityID)

    var id: String {
        switch self {
        case .leave(let id): "leave." + id
        case .withdraw(let id): "withdraw." + id
        case .forget(let id): "forget." + id
        case .delete(let id): "delete." + id
        case .removeMember(let member, _, let id): "remove." + id + "." + member
        }
    }

    func title(locale: Locale) -> String {
        let table = StringTable.communities
        switch self {
        case .leave: return table.string("confirm.leave.title", locale: locale)
        case .withdraw: return table.string("confirm.withdraw.title", locale: locale)
        case .forget: return table.string("confirm.forget.title", locale: locale)
        case .delete: return table.string("confirm.delete.title", locale: locale)
        case .removeMember(_, let name, _): return table.string("confirm.remove.title \(name)", locale: locale)
        }
    }

    var message: Text {
        switch self {
        case .leave: Text("confirm.leave.message", table: .communities)
        case .withdraw: Text("confirm.withdraw.message", table: .communities)
        case .forget: Text("confirm.forget.message", table: .communities)
        case .delete: Text("confirm.delete.message", table: .communities)
        case .removeMember: Text("confirm.remove.message", table: .communities)
        }
    }

    var action: Text {
        switch self {
        case .leave: Text("action.leave", table: .communities)
        case .withdraw: Text("action.withdraw", table: .communities)
        case .forget: Text("action.forget", table: .communities)
        case .delete: Text("action.delete", table: .communities)
        case .removeMember: Text("action.removeMember", table: .communities)
        }
    }

    var glyph: PhosphorGlyph {
        switch self {
        case .leave, .withdraw: .linkBreak
        case .forget, .delete: .trash
        case .removeMember: .prohibit
        }
    }

    /// Runs the confirmed step.
    @MainActor func perform(on service: any CommunitiesService) async throws(CommunityProblem) {
        switch self {
        case .leave(let id): try await service.leave(id)
        case .withdraw(let id): try await service.withdraw(id)
        case .forget(let id): try await service.forget(id)
        case .delete(let id): try await service.delete(id)
        case .removeMember(let member, _, let id): try await service.removeMember(member, from: id)
        }
    }
}

/// The floating sheet's content for a ``CommunityConfirmation``: the
/// app's `DestructiveConfirmation`, busy while the step runs.
struct CommunityConfirmationSheet: View {
    let confirmation: CommunityConfirmation
    let isWorking: Bool
    let onConfirm: () -> Void
    let onCancel: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        DestructiveConfirmation(
            title: confirmation.title(locale: locale), message: confirmation.message,
            confirmTitle: confirmation.action, confirmGlyph: confirmation.glyph, isWorking: isWorking,
            identifiers: ("communities.confirm", "communities.confirm.cancel"),
            onConfirm: onConfirm, onCancel: onCancel)
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
    }
}
