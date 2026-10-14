import SwiftUI

/// A community's members and management (proposed, full-height sheet): the
/// owner reviews join requests with the identity key each asks to add,
/// removes members and makes one-use invitations; a member can leave; the
/// owner deletes the community. Irreversible steps are confirmed on the
/// same sheet, in the app's destructive confirmation.
struct CommunityManageSheet: View {
    let communityID: CommunityID
    let onClose: () -> Void
    /// The community is gone from this phone (left, deleted).
    let onGone: () -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @State private var invite: String?
    @State private var busy: Set<String> = []
    @State private var failure: CommunityProblem?
    @State private var confirmation: CommunityConfirmation?
    @State private var isConfirming = false
    /// The last name shown, kept while the sheet closes after a leave or delete.
    @State private var knownName = ""

    private var service: (any CommunitiesService)? { model.messenger.communities }

    var body: some View {
        ZStack(alignment: .top) {
            if let confirmation {
                CommunityConfirmationSheet(confirmation: confirmation, isWorking: isConfirming) { confirm(confirmation) } onCancel: {
                    self.confirmation = nil
                }
                .transition(.chatsPage(forward: true))
            } else {
                page
                    .transition(.chatsPage(forward: false))
            }
        }
        .u2Animation(U2Motion.state, value: confirmation)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .onChange(of: service?.row(communityID)?.name, initial: true) { _, name in
            knownName = CommunityNames.remembered(name, known: knownName)
        }
    }

    private var page: some View {
        let row = service?.row(communityID)
        let isOwner = row?.phase == .owner
        let members = service?.members(of: communityID) ?? []
        return VStack(spacing: 0) {
            SheetCloseHeader(title: Text(verbatim: CommunityNames.title(row?.name, known: knownName, locale: locale)), onClose: onClose)
                .padding(.horizontal, 18)
                .padding(.top, 12)
            ScrollView {
                VStack(alignment: .leading, spacing: U2Spacing.sectionGap) {
                    if let failure {
                        CommunityProblemNote(problem: failure) { self.failure = nil }
                    }
                    if isOwner { requests(isFull: members.count >= CommunityLimits.maxMembers) }
                    memberList(members, isOwner: isOwner)
                    if isOwner { invitations }
                    leaveOrDelete(isOwner: isOwner)
                }
                .padding(EdgeInsets(top: 14, leading: 18, bottom: 28, trailing: 18))
            }
            .scrollIndicators(.hidden)
        }
    }

    // MARK: Requests (owner)

    private func requests(isFull: Bool) -> some View {
        let pending = service?.openRequests ?? []
        return VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            SectionLabel(Text("manage.requests", table: .communities))
            if pending.isEmpty {
                Text("manage.requests.empty", table: .communities)
                    .u2Font(.callout)
                    .foregroundStyle(Color.u2.textSecondary)
                    .padding(.horizontal, 16)
            } else {
                if isFull {
                    Text("manage.full", table: .communities)
                        .u2Font(.captionCompact)
                        .foregroundStyle(Color.u2.danger)
                        .padding(.horizontal, 16)
                }
                ForEach(pending) { request in
                    U2Card(fill: Color.u2.bg) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(verbatim: CommunityNames.member(username: request.username, shortID: request.shortID, locale: locale))
                                .u2Font(.monoUsername)
                                .foregroundStyle(Color.u2.text)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("manage.request.key", table: .communities)
                                    .u2Font(.captionCompact)
                                    .foregroundStyle(Color.u2.textSecondary)
                                Text(verbatim: request.keyFingerprint)
                                    .u2Font(.monoMeta)
                                    .foregroundStyle(Color.u2.text)
                                    .textSelection(.enabled)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            U2FlexRow(spacing: 8) {
                                Button { act(request.id) { try await $0.reject(request.id, in: communityID) } } label: {
                                    Text("action.reject", table: .communities)
                                }
                                .buttonStyle(U2DecisionButtonStyle(kind: .destructive))
                                Button { act(request.id) { try await $0.approve(request.id, in: communityID) } } label: {
                                    Text("action.approve", table: .communities)
                                }
                                .buttonStyle(U2DecisionButtonStyle(kind: .accept))
                                .disabled(isFull)
                                .u2Flex(1.3, border: 2)
                            }
                            .disabled(busy.contains(request.id))
                        }
                    }
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("manage.request.\(request.id)")
                }
            }
        }
    }

    // MARK: Members

    private func memberList(_ members: [CommunityMemberRow], isOwner: Bool) -> some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            SectionLabel(Text("manage.members \(members.count)", table: .communities))
            GroupedCard(fill: Color.u2.bg) {
                ForEach(Array(members.enumerated()), id: \.element.id) { index, member in
                    VStack(spacing: 0) {
                        if index > 0 { GroupedSeparator(inset: 14) }
                        CommunityMemberListRow(member: member, canRemove: isOwner && !member.isOwner && !member.isSelf) {
                            confirmation = .removeMember(
                                member.id,
                                name: CommunityNames.member(username: member.username, shortID: member.shortID, locale: locale),
                                in: communityID)
                        }
                    }
                }
            }
        }
    }

    // MARK: Invitations (owner)

    private var invitations: some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            SectionLabel(Text("manage.invites", table: .communities))
            U2Card(fill: Color.u2.bg) {
                VStack(alignment: .leading, spacing: 12) {
                    Text("manage.invites.hint", table: .communities)
                        .u2Font(.captionCompact)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let invite {
                        Text(verbatim: invite)
                            .u2Font(.monoMeta)
                            .foregroundStyle(Color.u2.text)
                            .textSelection(.enabled)
                            .lineLimit(3)
                            .truncationMode(.middle)
                            .privacySensitive()
                            .accessibilityIdentifier("manage.invite.code")
                        ShareLink(item: invite) {
                            U2ButtonLabel(Text("action.shareInvite", table: .communities), glyph: .export)
                        }
                        .buttonStyle(.u2PrimaryInCard)
                    } else {
                        Button { makeInvite() } label: {
                            U2ButtonLabel(Text("action.createInvite", table: .communities), glyph: .shareNetwork, isWorking: busy.contains("invite"))
                        }
                        .buttonStyle(.u2PrimaryInCard)
                        .disabled(busy.contains("invite"))
                        .accessibilityIdentifier("manage.invite")
                    }
                    Button {
                        act("revoke") { try await $0.revokeInvites(communityID) }
                        invite = nil
                    } label: {
                        Text("action.revokeInvites", table: .communities)
                    }
                    .buttonStyle(U2TextButtonStyle(tone: .danger, height: U2Size.iconButton))
                    .disabled(busy.contains("revoke"))
                }
            }
        }
    }

    // MARK: Leave / delete

    private func leaveOrDelete(isOwner: Bool) -> some View {
        Button {
            confirmation = isOwner ? .delete(communityID) : .leave(communityID)
        } label: {
            U2ButtonLabel(
                isOwner ? Text("action.delete", table: .communities) : Text("action.leave", table: .communities),
                glyph: isOwner ? .trash : .linkBreak)
        }
        .buttonStyle(.u2Danger)
        .accessibilityIdentifier(isOwner ? "manage.delete" : "manage.leave")
    }

    // MARK: Actions

    private func act(_ key: String, _ action: @escaping @MainActor (any CommunitiesService) async throws -> Void) {
        guard let service, !busy.contains(key) else { return }
        busy.insert(key)
        Task {
            defer { busy.remove(key) }
            do { try await action(service) } catch { failure = error as? CommunityProblem ?? .failed }
        }
    }

    private func makeInvite() {
        guard let service, !busy.contains("invite") else { return }
        busy.insert("invite")
        Task {
            defer { busy.remove("invite") }
            do throws(CommunityProblem) { invite = try await service.invite(communityID) } catch { failure = error }
        }
    }

    private func confirm(_ item: CommunityConfirmation) {
        guard let service, !isConfirming else { return }
        isConfirming = true
        Task {
            defer { isConfirming = false }
            do throws(CommunityProblem) {
                try await item.perform(on: service)
                confirmation = nil
                switch item {
                case .leave, .delete, .forget, .withdraw: onGone()
                case .removeMember: break
                }
            } catch {
                confirmation = nil
                failure = error
            }
        }
    }
}

// MARK: - Member row

/// A member in “Üyeler”: always a title and a subtitle, so every row is the
/// same height (62) with or without “Çıkar”. The title is “Sen”, the
/// username or “Üye · 1a2b3c4d”; the subtitle is the owner's role, “not in
/// your contacts” for an unnamed member, else the short id the others see.
struct CommunityMemberListRow: View {
    let member: CommunityMemberRow
    let canRemove: Bool
    let onRemove: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        GroupedRow(tile: nil, title: title, subtitle: subtitle) {
            if canRemove {
                Button(action: onRemove) {
                    Text("action.removeMember", table: .communities)
                }
                .buttonStyle(U2TextButtonStyle(tone: .danger, height: U2Size.iconButton, fillsWidth: false))
                .fixedSize()
                .accessibilityIdentifier("manage.remove.\(member.id)")
            }
        }
    }

    private var title: Text {
        if member.isSelf { return Text("member.you", table: .communities) }
        return Text(verbatim: CommunityNames.member(username: member.username, shortID: member.shortID, locale: locale))
    }

    private var subtitle: Text {
        if member.isOwner { return Text("member.owner", table: .communities) }
        if member.username == nil, !member.isSelf { return Text("member.notContact", table: .communities) }
        return Text(verbatim: CommunityNames.member(username: nil, shortID: member.shortID, locale: locale))
    }
}
