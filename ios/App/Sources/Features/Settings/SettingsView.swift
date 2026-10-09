import SwiftUI

/// 07 · Ayarlar — 7.1, what the main stack shows for `AppRoute.settings`.
struct SettingsView: View {
    var body: some View {
        SettingsRootView()
    }
}

/// A settings page beyond 7.1 — the destination of `AppRoute.settingsPage`.
struct SettingsPageView: View {
    let page: SettingsPage
    @Environment(AppModel.self) private var model

    var body: some View {
        switch page {
        case .backupExport:
            BackupExportView()
        case .changeCode:
            ChangeCodeView()
        case .about:
            AboutView()
        case .restore:
            RestoreBackupView(onClose: model.router.pop, onFinished: model.router.pop)
        }
    }
}

// MARK: - 7.1

/// 7.1 · Ayarlar: profile card and the C13 groups (Gizlilik, Güvenlik,
/// Sohbetler, Veri ve görünüm — exactly as drawn), then Uygulama (Hakkında)
/// and Hesap (Her şeyi sil) below the fold. Scrolls.
struct SettingsRootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale

    @State private var ownCode: MyCodePresentation?
    @State private var showsErase = false
    @State private var showsInvite = false
    @State private var isErasing = false
    @State private var erasesAfterSheet = false
    /// The Face ID switch while the system prompt decides (optimistic).
    @State private var pendingFaceID: Bool?

    private var flow: SettingsFlow { SettingsFlow.of(model) }
    private var messenger: any MessengerService { flow.messenger }

    var body: some View {
        @Bindable var flow = flow
        let settings = messenger.settings
        U2Screen {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack {
                            U2GlassIconButton(.caretLeft, label: Text("a11y.back"), action: model.router.pop)
                                .accessibilityIdentifier("settings.back")
                            Spacer()
                        }
                        .padding(.horizontal, U2Spacing.screenGutterS)
                        .frame(height: U2Size.iconButton)
                        .u2Top(U2Spacing.headerTopButtons)

                        Text("settings.title", table: .settings)
                            .u2Font(.displayTitle)
                            .foregroundStyle(Color.u2.text)
                            .padding(.top, 12)
                            // The reference draws the content 0.5 pt lower than
                            // the 38 pt line box suggests (r.1 is 50.25 high).
                            .padding(.bottom, 0.5)
                            .padding(.horizontal, U2Spacing.screenGutter)
                            .accessibilityAddTraits(.isHeader)

                        VStack(spacing: U2Spacing.sectionGap) {
                            profileCard
                            GroupedSection(Text("settings.section.privacy", table: .settings)) {
                                pickerRow(.circleHalf, Text("settings.presence", table: .settings), detail: presenceText(settings.presenceVisibility), picker: .presence)
                                GroupedSeparator()
                                GroupedToggleRow(
                                    tile: IconTile(content: .glyph(.checks)), title: Text("settings.readReceipts", table: .settings),
                                    isOn: binding(\.readReceipts))
                                    .accessibilityIdentifier("settings.readReceipts")
                            }
                            GroupedSection(Text("settings.section.security", table: .settings)) {
                                GroupedToggleRow(
                                    tile: IconTile(content: .faceID), title: Text("settings.faceID", table: .settings),
                                    isOn: faceIDBinding(settings.faceIDEnabled))
                                    .accessibilityIdentifier("settings.faceID")
                                GroupedSeparator()
                                pickerRow(.lockSimple, Text("settings.autoLock", table: .settings), detail: Text("settings.autoLock.immediately", table: .settings), picker: .autoLock)
                                GroupedSeparator()
                                GroupedNavigationRow(tile: IconTile(content: .glyph(.password)), title: Text("settings.changeCode", table: .settings)) {
                                    flow.open(.changeCode, in: model)
                                }
                                .accessibilityIdentifier("settings.changeCode")
                            }
                            GroupedSection(Text("settings.section.chats", table: .settings)) {
                                pickerRow(.ghost, Text("settings.defaultGhost", table: .settings), detail: ghostText(settings.defaultGhost), picker: .defaultGhost)
                                GroupedSeparator()
                                pickerRow(.timer, Text("settings.defaultDisappearing", table: .settings), detail: disappearingText(settings.defaultDisappearing), picker: .defaultDisappearing)
                            }
                            GroupedSection(Text("settings.section.data", table: .settings)) {
                                GroupedNavigationRow(
                                    tile: IconTile(content: .glyph(.fileLock)), title: Text("settings.backup", table: .settings),
                                    detail: backupText(settings.lastBackupAt)
                                ) { flow.open(.backupExport, in: model) }
                                .accessibilityIdentifier("settings.backup")
                                GroupedSeparator()
                                pickerRow(.moon, Text("settings.theme", table: .settings), detail: themeText(model.preferences.theme), picker: .theme)
                                GroupedSeparator()
                                pickerRow(.translate, Text("settings.language", table: .settings), detail: languageText(model.preferences.language), picker: .language)
                            }
                            GroupedSection(Text("settings.section.app", table: .settings)) {
                                InviteSettingsRow { showsInvite = true }
                                GroupedSeparator()
                                GroupedNavigationRow(
                                    tile: IconTile(content: .glyph(.info)), title: Text("settings.about", table: .settings),
                                    detail: Text(verbatim: AboutView.version)
                                ) { flow.open(.about, in: model) }
                                .accessibilityIdentifier("settings.about")
                            }
                            VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
                                SectionLabel(Text("settings.section.account", table: .settings))
                                GroupedCard { eraseRow }
                                Text("settings.erase.footnote", table: .settings)
                                    .u2Font(.footnote)
                                    .foregroundStyle(Color.u2.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .padding(.horizontal, 16)
                            }
                            .id("end")
                        }
                        .padding(EdgeInsets(top: 18, leading: 16, bottom: 0, trailing: 16))
                        .u2Bottom(U2Spacing.ctaBottom)
                    }
                }
                .scrollIndicators(.hidden)
                .overlay(alignment: .top) { SettingsStatusBarFade() }
                .onAppear { applyHarness(proxy: proxy) }
            }
        }
        .u2Toast($flow.toast)
        .u2Sheet(item: $ownCode) { presentation in
            MyCodeSheet(username: model.messenger.ownUsername, payload: presentation.payload) { ownCode = nil }
        }
        .u2Sheet(isPresented: $showsInvite) {
            InviteSheet(username: model.messenger.ownUsername, onClose: { showsInvite = false })
        }
        .u2Sheet(isPresented: $showsErase, style: .floating, isDismissible: !isErasing, onDismiss: eraseAfterSheet) {
            EraseConfirmation(
                isWorking: isErasing, identifiers: ("settings.confirm.destructive", "settings.confirm.cancel"),
                onConfirm: erase, onCancel: { showsErase = false }
            )
            .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
        }
    }

    // MARK: Pieces

    private var profileCard: some View {
        let username = model.messenger.account?.username ?? DesignFixtures.ownUsername
        return HStack(spacing: 14) {
            Avatar(username: username, palette: .d, size: .settings)
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: "@" + username)
                    .u2Font(.monoUsernameL)
                    .foregroundStyle(Color.u2.text)
                    .lineLimit(1)
                Text("settings.profile.caption", table: .settings)
                    .u2Font(.caption)
                    .foregroundStyle(Color.u2.textSecondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            U2GlassIconButton(
                .qrCode, diameter: U2Size.iconButtonChat, glyphSize: 20, tint: Color.u2.accent300,
                label: Text("a11y.ownQR", table: .settings)
            ) { openOwnCode() }
            .accessibilityIdentifier("settings.qr")
        }
        .padding(EdgeInsets(top: 12, leading: 14, bottom: 12, trailing: 14))
        .background(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous).fill(Color.u2.surface))
        .accessibilityElement(children: .contain)
    }

    private var eraseRow: some View {
        Button { showsErase = true } label: {
            HStack(spacing: U2Spacing.rowGap) {
                IconTile(content: .glyph(.trash), tone: .danger)
                Text("settings.erase", table: .settings)
                    .u2Font(.rowTitle)
                    .foregroundStyle(Color.u2.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                DisclosureDetail()
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(GroupedRowButtonStyle())
        .accessibilityIdentifier("settings.erase")
    }

    private func pickerRow(_ glyph: PhosphorGlyph, _ title: Text, detail: Text, picker: SettingsPicker) -> some View {
        GroupedNavigationRow(tile: IconTile(content: .glyph(glyph)), title: title, detail: detail) {
            model.router.push(.settingsPicker(picker))
        }
        .accessibilityIdentifier("settings.\(picker.rawValue)")
    }

    // MARK: Actions

    private func binding(_ keyPath: WritableKeyPath<AppSettings, Bool>) -> Binding<Bool> {
        Binding {
            messenger.settings[keyPath: keyPath]
        } set: { value in
            var settings = messenger.settings
            settings[keyPath: keyPath] = value
            Task { await messenger.updateSettings(settings) }
        }
    }

    /// Face ID: turning it on asks for Face ID first; the switch moves at once
    /// and glides back if the prompt fails or the device has no Face ID.
    private func faceIDBinding(_ actual: Bool) -> Binding<Bool> {
        Binding {
            pendingFaceID ?? actual
        } set: { value in
            pendingFaceID = value
            let reason = StringTable.settings.string("settings.faceID.reason", locale: locale)
            Task {
                let result = await messenger.setBiometricUnlock(value, reason: reason)
                withU2Animation(U2Motion.state) { pendingFaceID = nil }
                if value && !result {
                    flow.toast = Text("settings.faceID.unavailable", table: .settings)
                }
            }
        }
    }

    /// The code is generated before the sheet rises, so it rises complete.
    private func openOwnCode() {
        Task {
            ownCode = MyCodePresentation(payload: await messenger.preparedOwnContactCode())
        }
    }

    private func erase() {
        isErasing = true
        Task {
            // Let the sheet go first (its dismissal calls `eraseAfterSheet`);
            // then the wipe cross-fades to 1.1.
            try? await Task.sleep(for: .milliseconds(250))
            erasesAfterSheet = true
            showsErase = false
        }
    }

    /// The erase sheet is completely gone: now wipe (never under a sheet
    /// that is still on screen).
    private func eraseAfterSheet() {
        guard erasesAfterSheet else { return }
        erasesAfterSheet = false
        Task {
            await model.eraseEverything()
            isErasing = false
        }
    }

    private func applyHarness(proxy: ScrollViewProxy) {
        #if DEBUG
            if let state = SettingsHarness.take(prefix: "7.1") {
                if state == "7.1-end" {
                    Task {
                        try? await Task.sleep(for: .milliseconds(100))
                        proxy.scrollTo("end", anchor: .bottom)
                    }
                }
            }
            // Sheets wait for the launch push to settle.
            if SettingsHarness.take(prefix: "qr") != nil {
                Task {
                    try? await Task.sleep(for: .milliseconds(800))
                    openOwnCode()
                }
            }
            if SettingsHarness.take(prefix: "erase") != nil {
                Task {
                    try? await Task.sleep(for: .milliseconds(800))
                    showsErase = true
                }
            }
            SettingsHarness.apply(model: model, flow: flow)
        #endif
    }

    // MARK: Texts

    private func presenceText(_ value: PresenceVisibility) -> Text {
        value == .acceptedContacts ? Text("settings.presence.accepted", table: .settings) : Text("settings.presence.nobody", table: .settings)
    }

    private func ghostText(_ value: GhostPeriod?) -> Text {
        value.map { Text($0.longKey) } ?? Text("disappearing.off")
    }

    private func disappearingText(_ value: DisappearingTimer) -> Text {
        Text(value.settingsLongKey, table: .settings)
    }

    private func backupText(_ date: Date?) -> Text {
        guard let date else { return Text("settings.backup.never", table: .settings) }
        return Text("settings.backup.last \(U2Format.dayMonth(date, locale: locale))", table: .settings)
    }

    private func themeText(_ value: ThemePreference) -> Text {
        Text(value.settingsKey, table: .settings)
    }

    private func languageText(_ value: AppLanguage) -> Text {
        switch value {
        case .system:
            model.locale.language.languageCode?.identifier == "tr" ? Text(verbatim: "Türkçe") : Text(verbatim: "English")
        case .turkish: Text(verbatim: "Türkçe")
        case .english: Text(verbatim: "English")
        }
    }
}

extension ThemePreference {
    /// “Sistem”, “Koyu”, “Açık”.
    var settingsKey: LocalizedStringKey {
        switch self {
        case .system: "settings.theme.system"
        case .dark: "settings.theme.dark"
        case .light: "settings.theme.light"
        }
    }
}

extension DisappearingTimer {
    /// Long labels for 7.1 and its picker: “Kapalı”, “30 saniye” … “1 gün”.
    var settingsLongKey: LocalizedStringKey {
        switch self {
        case .off: "settings.disappearing.off"
        case .thirtySeconds: "settings.disappearing.30s"
        case .fiveMinutes: "settings.disappearing.5m"
        case .oneHour: "settings.disappearing.1h"
        case .oneDay: "settings.disappearing.1d"
        }
    }
}

// MARK: - Pickers

/// A 7.1 picker (proposed: the options in a C13 card, the selected one with a
/// `ph:check` in `accent300`), with an explanation under the card where the
/// choice has consequences (presence is reciprocal; auto-lock is fixed).
struct SettingsPickerView: View {
    let picker: SettingsPicker
    @Environment(AppModel.self) private var model

    private var messenger: any MessengerService { SettingsFlow.of(model).messenger }

    var body: some View {
        U2Screen {
            ScrollView {
                VStack(spacing: 0) {
                    CenteredTitleHeader(title) {
                        U2GlassIconButton(.caretLeft, label: Text("a11y.back"), action: model.router.pop)
                            .accessibilityIdentifier("picker.back")
                    }
                    VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
                        GroupedCard {
                            ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                                if index > 0 { GroupedSeparator(inset: 14) }
                                Button {
                                    guard !option.isSelected else { return }
                                    U2Haptics.selection()
                                    option.select()
                                } label: {
                                    GroupedRow(tile: nil, title: option.title, subtitle: option.subtitle) {
                                        PhosphorIcon(.check, size: 18)
                                            .foregroundStyle(Color.u2.accent300)
                                            .opacity(option.isSelected ? 1 : 0)
                                            .scaleEffect(option.isSelected ? 1 : 0.8)
                                    }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(GroupedRowButtonStyle())
                                .accessibilityAddTraits(option.isSelected ? .isSelected : [])
                                .accessibilityIdentifier("picker.option.\(index)")
                            }
                        }
                        if let footnote {
                            footnote
                                .u2Font(.footnote)
                                .foregroundStyle(Color.u2.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(.horizontal, 16)
                        }
                    }
                    .padding(EdgeInsets(top: 18, leading: 16, bottom: 0, trailing: 16))
                    .u2Bottom(U2Spacing.ctaBottom)
                    .u2Animation(U2Motion.micro, value: options.map(\.isSelected))
                }
            }
            .scrollIndicators(.hidden)
            .overlay(alignment: .top) { SettingsStatusBarFade() }
        }
    }

    private struct Option {
        let title: Text
        var subtitle: Text? = nil
        let isSelected: Bool
        let select: () -> Void
    }

    private var title: Text {
        switch picker {
        case .presence: Text("settings.presence", table: .settings)
        case .autoLock: Text("settings.autoLock", table: .settings)
        case .defaultGhost: Text("settings.defaultGhost", table: .settings)
        case .defaultDisappearing: Text("settings.defaultDisappearing", table: .settings)
        case .theme: Text("settings.theme", table: .settings)
        case .language: Text("settings.language", table: .settings)
        }
    }

    private var footnote: Text? {
        switch picker {
        case .presence: Text("settings.presence.footnote", table: .settings)
        case .autoLock: Text("settings.autoLock.footnote", table: .settings)
        case .defaultGhost: Text("settings.defaultGhost.footnote", table: .settings)
        case .defaultDisappearing: Text("settings.defaultDisappearing.footnote", table: .settings)
        case .theme, .language: nil
        }
    }

    private var options: [Option] {
        let settings = messenger.settings
        let preferences = model.preferences
        let messenger = messenger
        func update(_ change: @escaping (inout AppSettings) -> Void) -> () -> Void {
            {
                var copy = messenger.settings
                change(&copy)
                Task { await messenger.updateSettings(copy) }
            }
        }
        switch picker {
        case .presence:
            return [
                Option(
                    title: Text("settings.presence.accepted", table: .settings),
                    subtitle: Text("settings.presence.accepted.subtitle", table: .settings),
                    isSelected: settings.presenceVisibility == .acceptedContacts,
                    select: update { $0.presenceVisibility = .acceptedContacts }),
                Option(
                    title: Text("settings.presence.nobody", table: .settings),
                    subtitle: Text("settings.presence.nobody.subtitle", table: .settings),
                    isSelected: settings.presenceVisibility == .nobody,
                    select: update { $0.presenceVisibility = .nobody }),
            ]
        case .autoLock:
            return [Option(title: Text("settings.autoLock.immediately", table: .settings), isSelected: true, select: {})]
        case .defaultGhost:
            return [Option(title: Text("disappearing.off"), isSelected: settings.defaultGhost == nil, select: update { $0.defaultGhost = nil })]
                + GhostPeriod.allCases.map { period in
                    Option(title: Text(period.longKey), isSelected: settings.defaultGhost == period, select: update { $0.defaultGhost = period })
                }
        case .defaultDisappearing:
            return DisappearingTimer.allCases.map { timer in
                Option(
                    title: Text(timer.settingsLongKey, table: .settings), isSelected: settings.defaultDisappearing == timer,
                    select: update { $0.defaultDisappearing = timer })
            }
        case .theme:
            return ThemePreference.allCases.map { theme in
                Option(
                    title: Text(theme.settingsKey, table: .settings),
                    isSelected: preferences.theme == theme,
                    select: { withU2Animation(U2Motion.screen) { preferences.theme = theme } })
            }
        case .language:
            return AppLanguage.allCases.map { language in
                Option(
                    title: language == .system ? Text("settings.language.system", table: .settings) : Text(verbatim: language == .turkish ? "Türkçe" : "English"),
                    isSelected: preferences.language == language,
                    select: { withU2Animation(U2Motion.state) { preferences.language = language } })
            }
        }
    }
}

#Preview("7.1 Ayarlar") {
    let model = AppModel.preview()
    return SettingsView()
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}
