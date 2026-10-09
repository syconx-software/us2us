import SwiftUI

/// State of 7.2 · Yedeği dışa aktar.
///
/// The backup password is separate from the secret code (owner decision) and
/// asked twice: the first entry is checked (strength at least “Orta”, not the
/// secret code), then a confirmation field rises under it; only a matching
/// confirmation exports. The passwords live only in this model, which goes
/// away with the screen.
@Observable
@MainActor
final class BackupExportModel {
    enum Phase: Equatable {
        case editing
        /// Encrypting on the device (0…1).
        case exporting(Double)
        /// The system share sheet is up.
        case sharing
    }

    enum Problem: Equatable {
        /// Strength below “Orta”.
        case weak
        /// Equal to the secret code (owner rule).
        case sameAsSecretCode
        /// The confirmation differs.
        case mismatch
    }

    /// The switches. Media only ever goes with messages: with “Mesajlar” off
    /// the media switch shows off and is disabled, but keeps the user's choice
    /// for when messages are switched back on (`BackupContents.includesMedia`).
    var contents = BackupContents()

    /// The “Fotoğraf ve videolar” switch as shown.
    var includesMedia: Bool {
        get { contents.includesMedia }
        set { contents.media = newValue }
    }

    var password = "" {
        didSet {
            guard password != oldValue else { return }
            strength = PasswordStrength.estimate(password)
            if problem == .weak || problem == .sameAsSecretCode { problem = nil }
            if isConfirming, !confirmation.isEmpty { confirmation = "" }
            if problem == .mismatch { problem = nil }
        }
    }

    var confirmation = "" {
        didSet { if problem == .mismatch, confirmation != oldValue { problem = nil } }
    }

    private(set) var strength = PasswordStrength.empty
    /// The confirmation field is shown.
    private(set) var isConfirming = false
    private(set) var problem: Problem?
    private(set) var phase: Phase = .editing
    private(set) var estimate: BackupEstimate?
    /// The reference state draws the ring on the password field without the keyboard.
    var highlightsPassword = false

    private let messenger: any MessengerService

    init(messenger: any MessengerService) {
        self.messenger = messenger
    }

    var confirmationMatches: Bool { isConfirming && !confirmation.isEmpty && confirmation == password }

    var canSubmit: Bool {
        guard phase == .editing, !contents.isEmpty else { return false }
        return isConfirming ? !confirmation.isEmpty : !password.isEmpty
    }

    func load() async {
        guard estimate == nil else { return }
        let value = await messenger.backupEstimate()
        withU2Animation(U2Motion.state) { estimate = value }
    }

    /// Bytes the chosen contents take (the design's estimate: all on = 184 MB).
    var estimatedBytes: Int64? {
        guard let estimate else { return nil }
        var bytes: Int64 = 0
        if contents.messages { bytes += estimate.totalBytes - estimate.mediaBytes }
        if contents.includesMedia { bytes += estimate.mediaBytes }
        return bytes
    }

    /// “Dışa aktar” / return key. Returns the exported file when it is ready to share.
    func submit(fileName: String) async -> URL? {
        guard canSubmit else { return nil }
        if !isConfirming {
            guard strength.isAcceptable else {
                fail(.weak)
                return nil
            }
            if password.count == 6, password.allSatisfy(\.isASCII), password.allSatisfy(\.isNumber),
                await messenger.backupPasswordIsSecretCode(password)
            {
                fail(.sameAsSecretCode)
                return nil
            }
            withU2Animation(U2Motion.state) {
                problem = nil
                isConfirming = true
            }
            return nil
        }
        guard confirmation == password else {
            fail(.mismatch)
            return nil
        }
        withU2Animation(U2Motion.state) { phase = .exporting(0) }
        do {
            let url = try await messenger.exportBackup(contents, password: password, fileName: fileName) { [weak self] value in
                self?.phase = .exporting(value)
            }
            phase = .sharing
            return url
        } catch {
            withU2Animation(U2Motion.state) { phase = .editing }
            return nil
        }
    }

    /// The share sheet closed; the temporary file is deleted either way.
    func finishSharing(_ url: URL, saved: Bool) async {
        await messenger.finishExport(at: url, saved: saved)
        if saved {
            password = ""
            confirmation = ""
        }
        withU2Animation(U2Motion.state) { phase = .editing }
    }

    private func fail(_ problem: Problem) {
        U2Haptics.error()
        withU2Animation(U2Motion.state) { self.problem = problem }
    }

    #if DEBUG
        /// DEBUG harness states (`-U2Route settings:7.2…`).
        func applyHarness(_ state: String) {
            let demo = DesignFixturesService.demoBackupPassword
            switch state {
            case "7.2":
                password = demo
                highlightsPassword = true
            case "7.2-weak":
                password = "kedi2026"
                problem = .weak
                highlightsPassword = true
            case "7.2-confirm":
                password = demo
                isConfirming = true
                confirmation = demo
            case "7.2-mismatch":
                password = demo
                isConfirming = true
                confirmation = String(demo.dropLast(2))
                problem = .mismatch
            case "7.2-exporting":
                password = demo
                isConfirming = true
                confirmation = demo
                phase = .exporting(0.4)
            default:
                break
            }
        }
    #endif
}

/// 7.2 · Yedeği dışa aktar — Export backup.
struct BackupExportView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @Environment(\.u2Clock) private var clock
    @State private var viewModel: BackupExportModel?
    @FocusState private var focus: PasswordFieldFocus?

    var body: some View {
        U2Screen {
            VStack(spacing: 0) {
                CenteredTitleHeader(Text("backup.title", table: .settings)) {
                    U2GlassIconButton(.caretLeft, label: Text("a11y.back"), action: model.router.pop)
                        .accessibilityIdentifier("backup.back")
                }
                if let viewModel {
                    ScrollView {
                        content(viewModel)
                            .padding(EdgeInsets(top: 18, leading: 16, bottom: 24, trailing: 16))
                    }
                    .scrollIndicators(.hidden)
                    .scrollDismissesKeyboard(.interactively)
                    cta(viewModel)
                } else {
                    Spacer()
                }
            }
        }
        .onAppear(perform: makeModel)
        .task { await viewModel?.load() }
    }

    private func makeModel() {
        guard viewModel == nil else { return }
        let created = BackupExportModel(messenger: SettingsFlow.of(model).messenger)
        #if DEBUG
            if let state = SettingsHarness.take(prefix: "7.2") { created.applyHarness(state) }
        #endif
        viewModel = created
    }

    // MARK: Content

    @ViewBuilder private func content(_ vm: BackupExportModel) -> some View {
        @Bindable var vm = vm
        VStack(alignment: .leading, spacing: 18) {
            BackupFileCard(name: Text(verbatim: fileName), caption: estimateCaption(vm))

            VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
                SectionLabel(Text("backup.contents", table: .settings))
                GroupedCard {
                    GroupedToggleRow(
                        tile: IconTile(content: .glyph(.chatCircleDots)), title: Text("backup.contents.messages", table: .settings),
                        subtitle: messagesSubtitle(vm), isOn: $vm.contents.messages)
                        .accessibilityIdentifier("backup.contents.messages")
                    GroupedSeparator()
                    GroupedToggleRow(
                        tile: IconTile(content: .glyph(.image)), title: Text("backup.contents.media", table: .settings),
                        subtitle: vm.estimate.map { Text(verbatim: U2Format.byteCount($0.mediaBytes, locale: locale)) },
                        isOn: $vm.includesMedia)
                        .disabled(!vm.contents.messages)
                        .accessibilityIdentifier("backup.contents.media")
                    GroupedSeparator()
                    GroupedToggleRow(
                        tile: IconTile(content: .glyph(.gearSix)), title: Text("backup.contents.settings", table: .settings),
                        isOn: $vm.contents.settingsAndContacts)
                        .accessibilityIdentifier("backup.contents.settings")
                }
                Text("backup.contents.footnote", table: .settings)
                    .u2Font(.footnote)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16)
            }

            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(Text("backup.password", table: .settings))
                PasswordField(
                    text: $vm.password, focus: $focus, field: .password,
                    isError: vm.problem == .weak || vm.problem == .sameAsSecretCode,
                    isHighlighted: vm.highlightsPassword && focus == nil,
                    isDisabled: vm.phase != .editing,
                    submitLabel: .next, contentType: .newPassword,
                    onSubmit: { submit(vm) }
                )
                .accessibilityIdentifier("backup.password")
                strengthRow(vm)
                if let problem = vm.problem, problem != .mismatch {
                    problemCaption(problem)
                }
                if vm.isConfirming {
                    PasswordField(
                        text: $vm.confirmation, focus: $focus, field: .confirmation,
                        prompt: Text("backup.password.confirm", table: .settings),
                        trailing: vm.confirmationMatches ? .accepted : .reveal,
                        isError: vm.problem == .mismatch,
                        isDisabled: vm.phase != .editing,
                        submitLabel: .done, contentType: .newPassword,
                        onSubmit: { submit(vm) }
                    )
                    .accessibilityIdentifier("backup.password.confirm")
                    .transition(.u2Rise)
                    if vm.problem == .mismatch {
                        problemCaption(.mismatch)
                    }
                }
            }

            HStack(alignment: .top, spacing: 12) {
                PhosphorIcon(.warning, size: 18)
                    .foregroundStyle(Color.u2.accent300)
                    .frame(height: U2TextStyle.captionTight.lineHeight)
                Text("backup.password.warning", table: .settings)
                    .u2Font(.captionTight)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 6)
            .accessibilityElement(children: .combine)
        }
        .u2Animation(U2Motion.state, value: vm.isConfirming)
        .u2Animation(U2Motion.state, value: vm.problem)
        .onChange(of: vm.isConfirming) { _, confirming in
            if confirming { focus = .confirmation }
        }
    }

    private func strengthRow(_ vm: BackupExportModel) -> some View {
        let strength = vm.strength
        let color: Color =
            switch strength.level {
            case .weak: Color.u2.danger
            case .fair: Color.u2.accent400
            case .strong, .veryStrong: Color.u2.online
            }
        return HStack(spacing: 10) {
            DottedMeter(
                progress: vm.password.isEmpty ? 0 : strength.meterFraction, pattern: .strength,
                trackColor: Color.u2.dotTrackStrength, litColor: color
            )
            .u2Animation(U2Motion.state, value: strength.meterFraction)
            if !vm.password.isEmpty {
                strengthLabel(strength.level)
                    .u2Font(.meta)
                    .foregroundStyle(strength.level == .weak ? Color.u2.danger : Color.u2.text)
                    .contentTransition(.opacity)
                    .transition(.u2Fade)
            }
        }
        .frame(height: U2TextStyle.meta.lineHeight)
        .padding(.horizontal, 6)
        .u2Animation(U2Motion.state, value: strength.level)
        .u2Animation(U2Motion.state, value: vm.password.isEmpty)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("a11y.backup.strength", table: .settings))
        .accessibilityValue(vm.password.isEmpty ? Text(verbatim: "") : strengthLabel(strength.level))
    }

    private func strengthLabel(_ level: PasswordStrength.Level) -> Text {
        switch level {
        case .weak: Text("backup.strength.weak", table: .settings)
        case .fair: Text("backup.strength.fair", table: .settings)
        case .strong: Text("backup.strength.strong", table: .settings)
        case .veryStrong: Text("backup.strength.veryStrong", table: .settings)
        }
    }

    private func problemCaption(_ problem: BackupExportModel.Problem) -> some View {
        Group {
            switch problem {
            case .weak: Text("backup.problem.weak", table: .settings)
            case .sameAsSecretCode: Text("backup.problem.secretCode", table: .settings)
            case .mismatch: Text("backup.problem.mismatch", table: .settings)
            }
        }
        .u2Font(.caption)
        .foregroundStyle(Color.u2.danger)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, 6)
        .transition(.u2Drop)
        .accessibilityAddTraits(.updatesFrequently)
    }

    // MARK: CTA

    private func cta(_ vm: BackupExportModel) -> some View {
        let isWorking: Bool = if case .exporting = vm.phase { true } else { false }
        return Button { submit(vm) } label: {
            U2ButtonLabel(exportTitle(vm.phase),
                glyph: .export, isWorking: isWorking)
        }
        .buttonStyle(.u2Primary)
        .disabled(!vm.canSubmit && vm.phase == .editing)
        .allowsHitTesting(vm.phase == .editing)
        .accessibilityIdentifier("backup.export")
        .padding(.horizontal, U2Spacing.screenGutter)
        .padding(.top, 8)
        .modifier(KeyboardAwareBottom(isKeyboardUp: focus != nil))
    }

    /// “Dışa aktar”, then “Şifreleniyor… %40” while the file is encrypted.
    private func exportTitle(_ phase: BackupExportModel.Phase) -> Text {
        guard case .exporting(let fraction) = phase else { return Text("backup.export", table: .settings) }
        return Text("backup.exporting.percent \(Int((fraction * 100).rounded(.down)))", table: .settings)
    }

    private func submit(_ vm: BackupExportModel) {
        let name = fileName
        Task {
            guard let url = await vm.submit(fileName: name) else { return }
            focus = nil
            // Places a backup should not go (it is a file for Files / AirDrop).
            SystemShareSheet.present([url], excluding: [
                .assignToContact, .addToReadingList, .copyToPasteboard, .markupAsPDF, .openInIBooks, .print,
                .saveToCameraRoll, .postToFacebook, .postToTwitter, .postToWeibo, .postToVimeo, .postToFlickr,
                .postToTencentWeibo,
            ]) { saved in
                Task {
                    await vm.finishSharing(url, saved: saved)
                    if saved {
                        SettingsFlow.of(model).toast = Text("backup.done.toast", table: .settings)
                        model.router.pop()
                    }
                }
            }
        }
    }

    // MARK: Texts

    /// “us2us-yedek-2026-10-08.u2u” / “us2us-backup-2026-10-08.u2u”.
    private var fileName: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        let date = formatter.string(from: clock.now)
        return StringTable.settings.string("backup.fileName \(date)", locale: locale)
    }

    private func estimateCaption(_ vm: BackupExportModel) -> Text {
        guard let estimate = vm.estimate, let bytes = vm.estimatedBytes else { return Text(verbatim: " ") }
        if !vm.contents.messages {
            return vm.contents.settingsAndContacts ? Text("backup.estimate.settingsOnly", table: .settings) : Text("backup.estimate.nothing", table: .settings)
        }
        let size = U2Format.byteCount(bytes, locale: locale)
        let messages = SettingsCount.messages(estimate.messageCount, locale: locale)
        return Text("backup.estimate \(size) \(messages)", table: .settings)
    }

    private func messagesSubtitle(_ vm: BackupExportModel) -> Text? {
        guard let estimate = vm.estimate else { return nil }
        let chats = SettingsCount.chats(estimate.chatCount, locale: locale)
        let messages = SettingsCount.messages(estimate.messageCount, locale: locale)
        return Text(verbatim: "\(chats) · \(messages)")
    }
}

/// Counted nouns with grouped numbers (“3.920 mesaj”, “3,920 messages”).
enum SettingsCount {
    static func chats(_ count: Int, locale: Locale) -> String {
        let number = U2Format.integer(count, locale: locale)
        return count == 1
            ? StringTable.settings.string("count.chats.one \(number)", locale: locale)
            : StringTable.settings.string("count.chats.other \(number)", locale: locale)
    }

    static func messages(_ count: Int, locale: Locale) -> String {
        let number = U2Format.integer(count, locale: locale)
        return count == 1
            ? StringTable.settings.string("count.messages.one \(number)", locale: locale)
            : StringTable.settings.string("count.messages.other \(number)", locale: locale)
    }
}

/// The CTA's bottom padding: the design's 44 pt over the home indicator, or a
/// calm 12 pt above the keyboard while typing (eased like the keyboard).
struct KeyboardAwareBottom: ViewModifier {
    let isKeyboardUp: Bool
    @Environment(\.u2SafeArea) private var safeArea

    func body(content: Content) -> some View {
        content
            .padding(.bottom, isKeyboardUp ? 12 : safeArea.bottom(U2Spacing.ctaBottom))
            .u2Animation(U2Motion.state, value: isKeyboardUp)
    }
}

#Preview("7.2 Yedek") {
    let model = AppModel.preview()
    return BackupExportView()
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}
