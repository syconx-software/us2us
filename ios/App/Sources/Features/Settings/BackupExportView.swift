import SwiftUI
import Us2usKit
import Us2usRuntime

/// State of 7.2 · Yedeği dışa aktar.
///
/// The backup password is separate from the secret code (by design) and
/// asked twice: the first entry is checked (the PROTOCOL.md §5.5 rules, then
/// the local strength estimate at least “Orta”), then a confirmation field
/// rises under it; only a matching confirmation exports. Every problem is
/// shown under its field. “Güçlü parola oluştur” fills in a random password
/// (`BackupPassword.generate()`) and shows its card, so that it can be saved
/// in a password manager: hidden until “Göster” (and always hidden while the
/// screen is recorded or mirrored, and again when the app leaves the
/// foreground), “Kopyala” hands it over on this device only for a minute
/// (`SecurePasteboard` `.handOff`), and “Kopyalandı · 1 dk sonra panodan
/// silinir” says so until the minute is over. The passwords live only in
/// this model, which goes away with the screen; nothing logs or stores them.
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
        /// The strength estimate is below “Orta”.
        case weak
        /// Fewer than 10 characters (§5.5).
        case tooShort
        /// In `spec/common-backup-passwords.txt` (§5.5).
        case tooCommon
        /// One character repeated (§5.5).
        case repeatedCharacter
        /// A run or a keyboard row (§5.5).
        case sequence
        /// The confirmation differs.
        case mismatch
        /// Encrypting or writing the file failed; nothing was exported.
        case exportFailed

        init(_ error: BackupPasswordError) {
            switch error {
            case .tooShort: self = .tooShort
            case .tooCommon: self = .tooCommon
            case .repeatedCharacter: self = .repeatedCharacter
            case .simpleSequence: self = .sequence
            case .mismatch: self = .mismatch
            }
        }

        /// Shown under the first field, which then draws its error ring.
        var concernsPassword: Bool {
            switch self {
            case .weak, .tooShort, .tooCommon, .repeatedCharacter, .sequence: true
            case .mismatch, .exportFailed: false
            }
        }
    }

    /// What every backup holds (PROTOCOL.md §5.5): the account, its identity
    /// key and the contacts. Messages and media are never part of it, so
    /// there is nothing to choose.
    static let contents = BackupContents(messages: false, media: false, settingsAndContacts: true)

    var password = "" {
        didSet {
            guard password != oldValue else { return }
            strength = password == generatedPassword ? .generated : PasswordStrength.estimate(password)
            if isConfirming, !confirmation.isEmpty { confirmation = "" }
            problem = nil
            endCopyConfirmation()
        }
    }

    var confirmation = "" {
        didSet {
            if confirmation != oldValue, problem == .mismatch || problem == .exportFailed { problem = nil }
        }
    }

    private(set) var strength = PasswordStrength.empty
    /// The confirmation field is shown.
    private(set) var isConfirming = false
    private(set) var problem: Problem?
    private(set) var phase: Phase = .editing
    /// The last password “Güçlü parola oluştur” made; shown while the field
    /// still holds it.
    private(set) var generatedPassword: String?
    /// “Kopyala” copied the shown password, and the copy has not expired
    /// yet: “Kopyalandı · yalnızca bu cihazda, 1 dk sonra silinir” stays under the button.
    private(set) var didCopy = false
    /// “Göster”: the generated password is shown in the clear. Off at first,
    /// and again whenever the app leaves the foreground.
    private(set) var isRevealed = false
    /// The screen is being recorded or mirrored: the password stays hidden
    /// whatever “Göster” says.
    var isScreenCaptured = false
    /// The reference state draws the ring on the password field without the keyboard.
    var highlightsPassword = false

    private let messenger: any MessengerService
    private let pasteboard: SecurePasteboard
    /// How long the confirmation stays: as long as the copy lives.
    private let copyLifetime: Duration
    @ObservationIgnored private var copyExpiry: Task<Void, Never>?

    init(
        messenger: any MessengerService, pasteboard: SecurePasteboard,
        copyLifetime: Duration = .seconds(SecurePasteboard.sensitiveLifetime)
    ) {
        self.messenger = messenger
        self.pasteboard = pasteboard
        self.copyLifetime = copyLifetime
    }

    /// The app's pasteboard, shared with the runtime (which clears what is
    /// still ours when the app locks).
    convenience init(messenger: any MessengerService) {
        self.init(messenger: messenger, pasteboard: .shared)
    }

    /// The generated password is in the field (and its card below it).
    var showsGeneratedPassword: Bool { generatedPassword != nil && password == generatedPassword }

    /// The card shows the password in the clear (not just its shape).
    var revealsGeneratedPassword: Bool { isRevealed && !isScreenCaptured }

    /// “Göster” / “Gizle”.
    func toggleReveal() {
        guard showsGeneratedPassword, !isScreenCaptured else { return }
        withU2Animation(U2Motion.micro) { isRevealed.toggle() }
    }

    /// The app leaves the foreground (or the screen gets recorded): hidden
    /// again. The copy itself stays: that is when it is pasted elsewhere.
    func hidePassword() {
        isRevealed = false
    }

    var confirmationMatches: Bool { isConfirming && !confirmation.isEmpty && confirmation == password }

    var canSubmit: Bool {
        guard phase == .editing else { return false }
        return isConfirming ? !confirmation.isEmpty : !password.isEmpty
    }

    /// “Güçlü parola oluştur”: a new random password in the field, counted at
    /// its exact strength and shown below it. A confirmation typed so far is
    /// cleared.
    func generatePassword(using generate: () -> String = BackupPassword.generate) {
        let value = generate()
        generatedPassword = value
        withU2Animation(U2Motion.state) { password = value }
    }

    /// “Kopyala”: the generated password to the pasteboard, on this device
    /// only (no Universal Clipboard) and for one minute
    /// (`SecurePasteboard.sensitiveLifetime`). The confirmation stays until
    /// the minute is over; copying again starts it over.
    @discardableResult
    func copyGeneratedPassword() -> Bool {
        guard showsGeneratedPassword, let generatedPassword,
            // Pasted into a password manager, i.e. after leaving the app:
            // it stays on this device and expires after a minute.
            pasteboard.copy(generatedPassword, sensitivity: .handOff)
        else { return false }
        U2Haptics.success()
        withU2Animation(U2Motion.state) { didCopy = true }
        copyExpiry?.cancel()
        let lifetime = copyLifetime
        copyExpiry = Task { [weak self] in
            try? await Task.sleep(for: lifetime)
            guard !Task.isCancelled, let self else { return }
            // The pasteboard has dropped it by now: say no more than is true.
            withU2Animation(U2Motion.state) { self.didCopy = false }
            self.copyExpiry = nil
        }
        return true
    }

    private func endCopyConfirmation() {
        copyExpiry?.cancel()
        copyExpiry = nil
        didCopy = false
    }

    /// “Dışa aktar” / return key. Returns the exported file when it is ready to share.
    func submit(fileName: String) async -> URL? {
        guard canSubmit else { return nil }
        // Checked at both steps: the password may have changed since the
        // confirmation field appeared.
        if let found = passwordProblem() {
            fail(found)
            return nil
        }
        guard isConfirming else {
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
        withU2Animation(U2Motion.state) {
            problem = nil
            phase = .exporting(0)
        }
        do {
            let url = try await messenger.exportBackup(Self.contents, password: password, fileName: fileName) {
                [weak self] value in
                self?.phase = .exporting(value)
            }
            phase = .sharing
            return url
        } catch let error as BackupPasswordError {
            withU2Animation(U2Motion.state) { phase = .editing }
            fail(Problem(error))
            return nil
        } catch {
            withU2Animation(U2Motion.state) { phase = .editing }
            fail(.exportFailed)
            return nil
        }
    }

    /// The share sheet closed; the temporary file is deleted either way.
    func finishSharing(_ url: URL, saved: Bool) async {
        await messenger.finishExport(at: url, saved: saved)
        if saved {
            password = ""
            confirmation = ""
            generatedPassword = nil
            isRevealed = false
        }
        withU2Animation(U2Motion.state) { phase = .editing }
    }

    /// The first problem of the password itself: the §5.5 rules (the same
    /// the export checks again), then the strength estimate. At least 10
    /// characters also keep it apart from the six-digit secret code.
    private func passwordProblem() -> Problem? {
        do throws(BackupPasswordError) {
            try BackupPassword.validate(password)
        } catch {
            return Problem(error)
        }
        return strength.isAcceptable ? nil : .weak
    }

    private func fail(_ problem: Problem) {
        U2Haptics.error()
        withU2Animation(U2Motion.state) { self.problem = problem }
    }

    #if DEBUG
        /// DEBUG harness states (`-U2Route settings:7.2…`).
        func applyHarness(_ state: String) {
            let demo = DesignFixturesService.demoExportPassword
            switch state {
            case "7.2":
                password = demo
                highlightsPassword = true
            case "7.2-weak":
                // Long enough for the §5.5 rules, weak by the estimate: the
                // state the live check produces.
                password = "Ahmet1985!"
                problem = .weak
                highlightsPassword = true
            case "7.2-generated":
                generatePassword { "k7mq-x2pd-9tfa-wn3h-b8ze" }
            case "7.2-revealed":
                generatePassword { "k7mq-x2pd-9tfa-wn3h-b8ze" }
                isRevealed = true
            case "7.2-copied":
                generatePassword { "k7mq-x2pd-9tfa-wn3h-b8ze" }
                isRevealed = true
                didCopy = true
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
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.u2Clock) private var clock
    @State private var viewModel: BackupExportModel?
    /// The screen is recorded or mirrored (kept here too: the probe may
    /// report before the model exists).
    @State private var isCaptured = false
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
        .background {
            // Recorded or mirrored: the generated password stays hidden.
            ScreenCaptureProbe { captured in
                isCaptured = captured
                viewModel?.isScreenCaptured = captured
                if captured { viewModel?.hidePassword() }
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
        .onChange(of: scenePhase) { _, phase in
            // Hidden again before the app-switcher snapshot and the lock.
            if phase != .active { viewModel?.hidePassword() }
        }
        .onAppear(perform: makeModel)
    }

    private func makeModel() {
        guard viewModel == nil else { return }
        let created = BackupExportModel(messenger: SettingsFlow.of(model).messenger)
        created.isScreenCaptured = isCaptured
        #if DEBUG
            if let state = SettingsHarness.take(prefix: "7.2") { created.applyHarness(state) }
        #endif
        viewModel = created
    }

    // MARK: Content

    @ViewBuilder private func content(_ vm: BackupExportModel) -> some View {
        @Bindable var vm = vm
        VStack(alignment: .leading, spacing: 18) {
            BackupFileCard(name: Text(verbatim: fileName), caption: Text("backup.file.caption", table: .settings))

            contentsSection

            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(Text("backup.password", table: .settings))
                PasswordField(
                    text: $vm.password, focus: $focus, field: .password,
                    isError: vm.problem?.concernsPassword == true,
                    isHighlighted: vm.highlightsPassword && focus == nil,
                    isDisabled: vm.phase != .editing,
                    submitLabel: .next, contentType: .newPassword,
                    revealAllowed: !isCaptured,
                    onSubmit: { submit(vm) }
                )
                .accessibilityIdentifier("backup.password")
                strengthRow(vm)
                if let problem = vm.problem, problem.concernsPassword {
                    problemCaption(problem)
                }
                generateButton(vm)
                if vm.showsGeneratedPassword, let generated = vm.generatedPassword {
                    generatedCard(vm, password: generated)
                }
                if vm.isConfirming {
                    PasswordField(
                        text: $vm.confirmation, focus: $focus, field: .confirmation,
                        prompt: Text("backup.password.confirm", table: .settings),
                        trailing: vm.confirmationMatches ? .accepted : .reveal,
                        isError: vm.problem == .mismatch,
                        isDisabled: vm.phase != .editing,
                        submitLabel: .done, contentType: .newPassword,
                        revealAllowed: !isCaptured,
                        onSubmit: { submit(vm) }
                    )
                    .accessibilityIdentifier("backup.password.confirm")
                    .transition(.u2Rise)
                    if vm.problem == .mismatch {
                        problemCaption(.mismatch)
                    }
                }
                if vm.problem == .exportFailed {
                    problemCaption(.exportFailed)
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
        .u2Animation(U2Motion.state, value: vm.showsGeneratedPassword)
        .onChange(of: vm.isConfirming) { _, confirming in
            if confirming { focus = .confirmation }
        }
    }

    /// “Yedeğe girecekler”: what the file holds, and (footnote) what it never
    /// holds. Nothing to switch: a backup is always the account (§5.5).
    private var contentsSection: some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            SectionLabel(Text("backup.contents", table: .settings))
            GroupedCard {
                contentsRow(.password, title: "backup.contents.account", detail: "backup.contents.account.detail")
                GroupedSeparator()
                contentsRow(.shieldCheck, title: "backup.contents.identity", detail: "backup.contents.identity.detail")
                GroupedSeparator()
                contentsRow(
                    .sealCheck, weight: .fill, title: "backup.contents.contacts",
                    detail: "backup.contents.contacts.detail")
            }
            Text("backup.contents.footnote", table: .settings)
                .u2Font(.footnote)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
                .accessibilityIdentifier("backup.contents.footnote")
        }
    }

    private func contentsRow(
        _ glyph: PhosphorGlyph, weight: PhosphorWeight = .regular, title: LocalizedStringKey,
        detail: LocalizedStringKey
    ) -> some View {
        GroupedRow(
            tile: IconTile(content: .glyph(glyph), glyphWeight: weight), title: Text(title, table: .settings),
            subtitle: Text(detail, table: .settings)
        ) {
            PhosphorIcon(.check, size: 18)
                .foregroundStyle(Color.u2.online)
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .combine)
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

    /// “Güçlü parola oluştur” (C3, `accent300`, under the meter).
    private func generateButton(_ vm: BackupExportModel) -> some View {
        Button {
            focus = nil
            vm.generatePassword()
        } label: {
            U2ButtonLabel(Text("backup.password.generate", table: .settings), glyph: .password, glyphSize: 18)
        }
        .buttonStyle(U2TextButtonStyle(tone: .accent, height: 40, fillsWidth: false))
        .disabled(vm.phase != .editing)
        .padding(.horizontal, 6)
        .accessibilityIdentifier("backup.password.generate")
    }

    /// The generated password card — a `surface` card, r18: the password
    /// in groups (`monoUsernameL`), hidden as `••••-••••-…` until the eye
    /// (the password field's 20 pt `ph:eye` in `text·68%`) shows it; the
    /// reminder to save it in a password manager; “Kopyala” and, after it,
    /// “Kopyalandı · yalnızca bu cihazda, 1 dk sonra silinir” (the 7.4 invite's status
    /// line: `ph:check` in `online`, `captionTight`) until the copy expires.
    private func generatedCard(_ vm: BackupExportModel, password: String) -> some View {
        let revealed = vm.revealsGeneratedPassword
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Text(verbatim: revealed ? password : Self.masked(password))
                    .u2Font(.monoUsernameL)
                    .foregroundStyle(revealed ? Color.u2.text : Color.u2.textSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .contentTransition(.opacity)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .privacySensitive()
                    .accessibilityLabel(Text("a11y.backup.generated", table: .settings))
                    .accessibilityValue(
                        revealed
                            ? Text(verbatim: password).speechSpellsOutCharacters()
                            : Text("a11y.backup.generated.hidden", table: .settings))
                    .accessibilityIdentifier("backup.password.generated")
                Button { vm.toggleReveal() } label: {
                    PhosphorIcon(revealed ? .eyeSlash : .eye, size: 20)
                        .foregroundStyle(Color.u2.textSecondary)
                        .frame(width: 20, height: 20)
                        .contentShape(Rectangle().inset(by: -12))
                }
                .buttonStyle(.plain)
                .disabled(vm.isScreenCaptured)
                .accessibilityLabel(revealed ? Text("a11y.password.hide") : Text("a11y.password.show"))
                .accessibilityIdentifier("backup.password.reveal")
            }
            Text("backup.password.generated.hint", table: .settings)
                .u2Font(.captionTight)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button { copy(vm) } label: {
                U2ButtonLabel(Text("backup.password.copy", table: .settings), glyph: .copy, glyphSize: 18)
            }
            .buttonStyle(U2TextButtonStyle(tone: .accent, height: 40, fillsWidth: false))
            .disabled(vm.phase != .editing)
            .accessibilityHint(Text("a11y.backup.copy.hint", table: .settings))
            .accessibilityIdentifier("backup.password.copy")
            if vm.didCopy {
                HStack(alignment: .top, spacing: 8) {
                    PhosphorIcon(.check, size: 16)
                        .foregroundStyle(Color.u2.online)
                        .frame(height: U2TextStyle.captionTight.lineHeight)
                        .accessibilityHidden(true)
                    Text("backup.password.copied", table: .settings)
                        .u2Font(.captionTight)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.bottom, 10)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("backup.password.copied")
                .transition(.u2Fade)
            }
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 4, trailing: 16))
        .background(RoundedRectangle(cornerRadius: U2Radius.group, style: .continuous).fill(Color.u2.surface))
        .u2Animation(U2Motion.state, value: vm.didCopy)
        .transition(.u2Rise)
    }

    /// `k7mq-x2pd-…` → `••••-••••-…`: the shape (and the dashes that are
    /// part of it) without the password.
    static func masked(_ password: String) -> String {
        String(password.map { $0 == "-" ? "-" : "•" })
    }

    private func copy(_ vm: BackupExportModel) {
        guard vm.copyGeneratedPassword() else { return }
        AccessibilityNotification.Announcement(StringTable.settings.string("backup.password.copied", locale: locale))
            .post()
    }

    private func problemCaption(_ problem: BackupExportModel.Problem) -> some View {
        Group {
            switch problem {
            case .weak: Text("backup.problem.weak", table: .settings)
            case .tooShort: Text("backup.problem.tooShort", table: .settings)
            case .tooCommon: Text("backup.problem.tooCommon", table: .settings)
            case .repeatedCharacter: Text("backup.problem.repeated", table: .settings)
            case .sequence: Text("backup.problem.sequence", table: .settings)
            case .mismatch: Text("backup.problem.mismatch", table: .settings)
            case .exportFailed: Text("backup.problem.exportFailed", table: .settings)
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
