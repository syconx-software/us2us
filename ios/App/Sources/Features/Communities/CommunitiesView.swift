import SwiftUI
import Us2usKit

/// Topluluklar (proposed, in 7.1's language): the account's communities as
/// a C13 card, a row each — a current one opens its conversation; a
/// pending request can be withdrawn and an ended or unfinished one removed
/// from this phone (both confirmed). Under it: create, use an invitation,
/// and the public search (sent only on submit, or after a pause with at
/// least two characters: queries reach the relay). What the relay learns
/// is said before anything is created, requested or redeemed.
struct CommunitiesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @State private var query = ""
    @State private var searchedQuery: String?
    @State private var isSearching = false
    @State private var showsCreate = false
    @State private var showsRedeem = false
    @State private var joinCandidate: CommunityDirectoryEntry?
    @State private var isJoining = false
    @State private var confirmation: CommunityConfirmation?
    @State private var isConfirming = false
    @State private var failure: CommunityProblem?

    private var service: (any CommunitiesService)? { model.messenger.communities }

    var body: some View {
        U2Screen {
            ScrollView {
                VStack(spacing: 0) {
                    CenteredTitleHeader(Text("communities.title", table: .communities)) {
                        U2GlassIconButton(.caretLeft, label: Text("a11y.back"), action: model.router.pop)
                            .accessibilityIdentifier("communities.back")
                    } trailing: {
                        U2GlassIconButton(.plus, tint: Color.u2.accent300, label: Text("a11y.create", table: .communities)) {
                            showsCreate = true
                        }
                        .accessibilityIdentifier("communities.create")
                        .disabled(service?.isSupported != true)
                    }
                    if let service {
                        content(service)
                            .padding(EdgeInsets(top: 18, leading: 16, bottom: 0, trailing: 16))
                            .u2Bottom(U2Spacing.ctaBottom)
                    }
                }
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .refreshable { await service?.refresh() }
        }
        .onAppear { service?.screenAppeared() }
        .onDisappear { service?.screenDisappeared() }
        .task(id: query) { await searchAfterPause() }
        .u2Sheet(isPresented: $showsCreate) {
            CommunityCreateSheet { showsCreate = false }
                .environment(\.locale, locale)
        }
        .u2Sheet(isPresented: $showsRedeem) {
            CommunityRedeemSheet { showsRedeem = false }
                .environment(\.locale, locale)
        }
        .u2Sheet(item: $joinCandidate, style: .floating) { entry in
            CommunityJoinSheet(entry: entry, isWorking: isJoining) { join(entry) } onCancel: { joinCandidate = nil }
                .environment(\.locale, locale)
        }
        .u2Sheet(item: $confirmation, style: .floating) { item in
            CommunityConfirmationSheet(confirmation: item, isWorking: isConfirming) { confirm(item) } onCancel: { confirmation = nil }
                .environment(\.locale, locale)
        }
    }

    // MARK: Content

    private func content(_ service: any CommunitiesService) -> some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionGap) {
            if let problem = failure ?? service.problem {
                CommunityProblemNote(problem: problem) {
                    if failure != nil { failure = nil } else { service.dismissProblem() }
                }
                .transition(.u2Fade)
            }
            if !service.isSupported {
                InfoBanner(glyph: .cloudSlash, message: Text("communities.unsupported", table: .communities))
            }
            yours(service)
            GroupedCard {
                GroupedNavigationRow(
                    tile: IconTile(content: .glyph(.plus)), title: Text("create.title", table: .communities),
                    subtitle: Text("create.subtitle", table: .communities)
                ) { showsCreate = true }
                .accessibilityIdentifier("communities.create.row")
                GroupedSeparator()
                GroupedNavigationRow(
                    tile: IconTile(content: .glyph(.scan)), title: Text("redeem.title", table: .communities),
                    subtitle: Text("redeem.subtitle", table: .communities)
                ) { showsRedeem = true }
                .accessibilityIdentifier("communities.redeem.row")
            }
            .disabled(!service.isSupported)
            discover(service)
            Text("communities.footnote", table: .communities)
                .u2Font(.footnote)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
        }
        .u2Animation(U2Motion.state, value: failure ?? service.problem)
        .u2Animation(U2Motion.state, value: service.rows)
    }

    private func yours(_ service: any CommunitiesService) -> some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            SectionLabel(Text("section.yours", table: .communities))
            if service.rows.isEmpty {
                Text("communities.empty", table: .communities)
                    .u2Font(.callout)
                    .foregroundStyle(Color.u2.textSecondary)
                    .padding(.horizontal, 16)
                    .accessibilityIdentifier("communities.empty")
            } else {
                GroupedCard {
                    ForEach(Array(service.rows.enumerated()), id: \.element.id) { index, row in
                        VStack(spacing: 0) {
                            if index > 0 { GroupedSeparator() }
                            CommunityListRow(row: row) {
                                model.router.push(.community(row.id))
                            } onAction: {
                                switch row.phase {
                                case .pending: confirmation = .withdraw(row.id)
                                case .ended, .unfinished: confirmation = .forget(row.id)
                                case .member, .owner: model.router.push(.community(row.id))
                                }
                            }
                        }
                    }
                }
                .accessibilityIdentifier("communities.list")
            }
        }
    }

    private func discover(_ service: any CommunitiesService) -> some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            SectionLabel(Text("section.discover", table: .communities))
            CommunityTextField(
                text: $query, prompt: Text("search.placeholder", table: .communities), submitLabel: .search,
                onSubmit: { Task { await search() } }, fill: Color.u2.surface)
                .accessibilityIdentifier("communities.search")
            Text("search.hint", table: .communities)
                .u2Font(.captionCompact)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
            if !service.directory.isEmpty {
                GroupedCard {
                    ForEach(Array(service.directory.enumerated()), id: \.element.id) { index, entry in
                        VStack(spacing: 0) {
                            if index > 0 { GroupedSeparator() }
                            CommunityDirectoryRow(entry: entry) { joinCandidate = entry }
                        }
                    }
                }
                if service.hasMoreResults {
                    Button {
                        Task { await search(more: true) }
                    } label: {
                        U2ButtonLabel(Text("search.more", table: .communities), isWorking: isSearching)
                    }
                    .buttonStyle(.u2TextAccent)
                }
            } else if let searched = searchedQuery, !isSearching {
                Text("search.none \(searched)", table: .communities)
                    .u2Font(.callout)
                    .foregroundStyle(Color.u2.textSecondary)
                    .padding(.horizontal, 16)
            }
        }
        .disabled(!service.isSupported)
    }

    // MARK: Actions

    /// After a pause in typing, only with at least two characters.
    private func searchAfterPause() async {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard trimmed.count >= CommunityLimits.minimumQueryLength else {
            if trimmed.isEmpty { searchedQuery = nil }
            return
        }
        do { try await Task.sleep(for: CommunityLimits.searchDebounce) } catch { return }
        await search()
    }

    private func search(more: Bool = false) async {
        guard let service else { return }
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard trimmed.count >= CommunityLimits.minimumQueryLength else { return }
        isSearching = true
        defer { isSearching = false }
        do throws(CommunityProblem) {
            try await service.search(trimmed, more: more)
            searchedQuery = trimmed
        } catch {
            failure = error
        }
    }

    private func join(_ entry: CommunityDirectoryEntry) {
        guard let service, !isJoining else { return }
        isJoining = true
        Task {
            defer { isJoining = false }
            do throws(CommunityProblem) {
                try await service.requestJoin(entry.id)
                U2Haptics.selection()
                joinCandidate = nil
            } catch {
                joinCandidate = nil
                failure = error
            }
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
            } catch {
                confirmation = nil
                failure = error
            }
        }
    }
}

// MARK: - Rows

/// A community row: C13's geometry — tile · name + state · chevron for a
/// current community, or a C3 text action (“İsteği geri çek” / “Kaldır”).
private struct CommunityListRow: View {
    let row: CommunityRow
    let onOpen: () -> Void
    let onAction: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        if row.isOpenable {
            Button(action: onOpen) {
                GroupedRow(tile: tile, title: title, subtitle: subtitle) { DisclosureDetail() }
                    .contentShape(Rectangle())
            }
            .buttonStyle(GroupedRowButtonStyle())
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("community.\(row.id)")
        } else {
            GroupedRow(tile: tile, title: title, subtitle: subtitle) {
                Button(action: onAction) { actionTitle }
                    .buttonStyle(U2TextButtonStyle(tone: row.phase == .pending ? .accent : .danger, height: U2Size.iconButton, fillsWidth: false))
                    .fixedSize()
                    .accessibilityIdentifier("community.action.\(row.id)")
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("community.\(row.id)")
        }
    }

    private var tile: IconTile {
        IconTile(content: .glyph(row.isOpenable ? .chatCircleDots : row.phase == .pending ? .clock : .linkBreak))
    }

    private var title: Text { Text(verbatim: CommunityNames.title(row.name, locale: locale)) }

    private var subtitle: Text {
        switch row.phase {
        case .owner:
            Text("row.owner \(row.memberCount ?? 1)", table: .communities)
        case .member:
            Text("row.member \(row.memberCount ?? 1)", table: .communities)
        case .pending: Text("row.pending", table: .communities)
        case .unfinished: Text("row.unfinished", table: .communities)
        case .ended: Text("row.ended", table: .communities)
        }
    }

    private var actionTitle: Text {
        row.phase == .pending ? Text("action.withdraw.short", table: .communities) : Text("action.forget", table: .communities)
    }
}

/// A public search result: name · “12/100 üye” · “İstek gönder” (or the
/// reason it cannot be requested).
private struct CommunityDirectoryRow: View {
    let entry: CommunityDirectoryEntry
    let onRequest: () -> Void

    var body: some View {
        GroupedRow(tile: IconTile(content: .glyph(.chatCircleDots)), title: Text(verbatim: entry.name), subtitle: subtitle) {
            if entry.isKnown {
                Text("directory.known", table: .communities)
                    .u2Font(.label15)
                    .foregroundStyle(Color.u2.textSecondary)
            } else if entry.isFull {
                Text("directory.full", table: .communities)
                    .u2Font(.label15)
                    .foregroundStyle(Color.u2.textSecondary)
            } else {
                Button(action: onRequest) { Text("action.request.short", table: .communities) }
                    .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.iconButton, fillsWidth: false))
                    .fixedSize()
                    .accessibilityIdentifier("directory.request.\(entry.id)")
            }
        }
        .accessibilityElement(children: .contain)
    }

    private var subtitle: Text {
        Text("directory.members \(entry.memberCount)", table: .communities)
    }
}

// MARK: - Sheets

/// “İstek gönderilsin mi?” (floating): the disclosure, then the request.
private struct CommunityJoinSheet: View {
    let entry: CommunityDirectoryEntry
    let isWorking: Bool
    let onConfirm: () -> Void
    let onCancel: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 10) {
                U2DisplayText(
                    verbatim: StringTable.communities.string("join.title \(entry.name)", locale: locale),
                    style: .displaySheetTitle.balanced, isHeader: true)
                Text("join.message", table: .communities)
                    .u2Font(.sheetBody)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(EdgeInsets(top: 6, leading: 4, bottom: 0, trailing: 4))
            CommunityDisclosure()
            VStack(spacing: 8) {
                Button(action: onConfirm) {
                    U2ButtonLabel(Text("action.request", table: .communities), glyph: .paperPlaneTilt, isWorking: isWorking)
                }
                .buttonStyle(.u2Primary)
                .disabled(isWorking)
                .accessibilityIdentifier("communities.join.confirm")
                Button(action: onCancel) { U2ButtonLabel(Text("common.cancel")) }
                    .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.textButtonS))
                    .disabled(isWorking)
            }
        }
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
    }
}

/// “Yeni topluluk”: a name (the shared rule, checked as typed), public or
/// private, what the relay learns, then “Oluştur”.
struct CommunityCreateSheet: View {
    let onClose: () -> Void
    @Environment(AppModel.self) private var model
    @State private var name = ""
    @State private var isPublic = false
    @State private var isWorking = false
    @State private var failure: CommunityProblem?

    private var canonical: String? { CommunityName.normalized(name) }

    var body: some View {
        VStack(spacing: 0) {
            SheetBar(title: Text("create.sheet.title", table: .communities), cancelTitle: Text("common.cancel"), onCancel: onClose)
            ScrollView {
                VStack(alignment: .leading, spacing: U2Spacing.sectionGap) {
                    VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
                        SectionLabel(Text("create.name.label", table: .communities))
                        CommunityTextField(text: $name, prompt: Text("create.name.placeholder", table: .communities))
                            .accessibilityIdentifier("communities.create.name")
                        if !name.isEmpty, canonical == nil {
                            Text("problem.invalidName", table: .communities)
                                .u2Font(.captionCompact)
                                .foregroundStyle(Color.u2.danger)
                                .padding(.horizontal, 16)
                                .transition(.u2Fade)
                        }
                    }
                    GroupedCard(fill: Color.u2.bg) {
                        GroupedToggleRow(
                            tile: IconTile(content: .glyph(isPublic ? .eye : .eyeSlash)),
                            title: Text("create.public.title", table: .communities),
                            subtitle: isPublic ? Text("create.public.on", table: .communities) : Text("create.public.off", table: .communities),
                            isOn: $isPublic)
                    }
                    CommunityDisclosure(isPrivate: !isPublic)
                    if let failure {
                        CommunityProblemNote(problem: failure) { self.failure = nil }
                    }
                    Button(action: create) {
                        U2ButtonLabel(Text("action.create", table: .communities), glyph: .plus, isWorking: isWorking)
                    }
                    .buttonStyle(.u2Primary)
                    .disabled(canonical == nil || isWorking)
                    .accessibilityIdentifier("communities.create.confirm")
                }
                .padding(EdgeInsets(top: 12, leading: 18, bottom: 28, trailing: 18))
                .u2Animation(U2Motion.state, value: canonical == nil)
            }
            .scrollIndicators(.hidden)
        }
    }

    private func create() {
        guard let service = model.messenger.communities, let canonical, !isWorking else { return }
        isWorking = true
        Task {
            defer { isWorking = false }
            do throws(CommunityProblem) {
                try await service.create(name: canonical, isPublic: isPublic)
                U2Haptics.selection()
                onClose()
            } catch {
                failure = error
            }
        }
    }
}

/// “Davet kodu”: paste a `us2us-community:v1:…` code; the request still
/// needs the owner's approval.
struct CommunityRedeemSheet: View {
    let onClose: () -> Void
    @Environment(AppModel.self) private var model
    @State private var code = ""
    @State private var isWorking = false
    @State private var failure: CommunityProblem?

    private var trimmed: String { code.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        VStack(spacing: 0) {
            SheetBar(title: Text("redeem.sheet.title", table: .communities), cancelTitle: Text("common.cancel"), onCancel: onClose)
            ScrollView {
                VStack(alignment: .leading, spacing: U2Spacing.sectionGap) {
                    VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
                        SectionLabel(Text("redeem.code.label", table: .communities))
                        CommunityTextField(text: $code, prompt: Text(verbatim: "us2us-community:v1:…"), isMonospaced: true)
                            .privacySensitive()
                            .accessibilityIdentifier("communities.redeem.code")
                        Text("redeem.hint", table: .communities)
                            .u2Font(.captionCompact)
                            .foregroundStyle(Color.u2.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 16)
                    }
                    CommunityDisclosure()
                    if let failure {
                        CommunityProblemNote(problem: failure) { self.failure = nil }
                    }
                    Button(action: redeem) {
                        U2ButtonLabel(Text("action.request", table: .communities), glyph: .paperPlaneTilt, isWorking: isWorking)
                    }
                    .buttonStyle(.u2Primary)
                    .disabled(trimmed.isEmpty || isWorking)
                    .accessibilityIdentifier("communities.redeem.confirm")
                }
                .padding(EdgeInsets(top: 12, leading: 18, bottom: 28, trailing: 18))
            }
            .scrollIndicators(.hidden)
        }
    }

    private func redeem() {
        guard let service = model.messenger.communities, !isWorking else { return }
        isWorking = true
        Task {
            defer { isWorking = false }
            do throws(CommunityProblem) {
                try await service.redeem(trimmed)
                U2Haptics.selection()
                code = ""
                onClose()
            } catch {
                failure = error
            }
        }
    }
}
