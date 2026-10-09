import SwiftUI
import UniformTypeIdentifiers

/// State of 7.3 · Yedekten geri yükle.
///
/// pick file (system document picker) → backup password → restoring (keys →
/// messages → media, percent + ETA) → done (→ the host continues with a new
/// secret code: 2.2 ×2 → 2.3 → 4.1). Wrong password: danger ring + “Parola
/// yanlış”. Decryption happens only on the device.
@Observable
@MainActor
final class RestoreBackupModel {
    enum Stage: Equatable {
        /// No file yet.
        case pick
        /// Reading the picked file's header.
        case inspecting
        /// The file is fine; waiting for its password.
        case password
        /// Checking the password.
        case checking
        case wrongPassword
        case restoring(RestoreProgress)
        case done(RestoreProgress)
        /// The picked file can't be restored.
        case failed(BackupFileError)
    }

    private(set) var stage: Stage = .pick
    private(set) var file: BackupFileInfo?
    var password = "" {
        didSet { if stage == .wrongPassword, password != oldValue { withU2Animation(U2Motion.state) { stage = .password } } }
    }

    private let messenger: any MessengerService
    private var restoreTask: Task<Void, Never>?

    init(messenger: any MessengerService) {
        self.messenger = messenger
    }

    var isRestoring: Bool {
        if case .restoring = stage { return true }
        return false
    }

    var progress: RestoreProgress? {
        switch stage {
        case .restoring(let progress), .done(let progress): progress
        default: nil
        }
    }

    /// The password was accepted (restoring or done).
    var isPasswordAccepted: Bool { progress != nil }

    func pick(_ url: URL) async {
        withU2Animation(U2Motion.state) { stage = .inspecting }
        do {
            let info = try await messenger.inspectBackup(at: url)
            withU2Animation(U2Motion.state) {
                file = info
                password = ""
                stage = .password
            }
        } catch {
            U2Haptics.error()
            withU2Animation(U2Motion.state) {
                file = nil
                stage = .failed(error)
            }
        }
    }

    var canSubmitPassword: Bool {
        (stage == .password || stage == .wrongPassword) && !password.isEmpty && file != nil
    }

    func submitPassword() async {
        guard canSubmitPassword, let file else { return }
        withU2Animation(U2Motion.state) { stage = .checking }
        switch await messenger.openBackup(file, password: password) {
        case .opened:
            U2Haptics.success()
            startRestore(file)
        case .wrongPassword:
            U2Haptics.error()
            withU2Animation(U2Motion.state) { stage = .wrongPassword }
        case .damaged:
            U2Haptics.error()
            withU2Animation(U2Motion.state) { stage = .failed(.notABackup) }
        }
    }

    private func startRestore(_ file: BackupFileInfo) {
        let stream = messenger.restoreBackup(file)
        withU2Animation(U2Motion.state) {
            stage = .restoring(
                RestoreProgress(
                    fraction: 0, stage: .keys, messagesDone: 0, messagesTotal: 0, mediaBytes: 0, estimatedRemaining: 0))
        }
        restoreTask = Task { [weak self] in
            var last: RestoreProgress?
            do {
                for try await progress in stream {
                    last = progress
                    withU2Animation(U2Motion.micro) { self?.stage = .restoring(progress) }
                }
                guard var final = last else { return }
                final.fraction = 1
                final.messagesDone = final.messagesTotal
                final.estimatedRemaining = 0
                U2Haptics.success()
                withU2Animation(U2Motion.state) { self?.stage = .done(final) }
            } catch {
                // Cancelled: the screen is going away.
            }
        }
    }

    func cancelRestore() async {
        restoreTask?.cancel()
        restoreTask = nil
        await messenger.cancelRestore()
    }

    #if DEBUG
        /// DEBUG harness states (`-U2Route settings:7.3…`).
        func applyHarness(_ state: String, clock: U2Clock) {
            let info = BackupFileInfo(
                url: URL(fileURLWithPath: "/dev/null"), fileName: DesignFixtures.backupEstimate.fileName,
                byteCount: DesignFixtures.backupEstimate.totalBytes, createdAt: DesignFixturesService.designBackupDate)
            let demo = DesignFixturesService.demoBackupPassword
            switch state {
            case "7.3", "7.3-cancel":
                file = info
                password = demo
                stage = .restoring(DesignFixtures.restoreProgress)
            case "7.3-password":
                file = info
            case "7.3-wrong":
                file = info
                password = String(demo.dropLast(3))
                stage = .wrongPassword
            case "7.3-done":
                file = info
                password = demo
                var done = DesignFixtures.restoreProgress
                done.fraction = 1
                done.stage = .media
                done.messagesDone = done.messagesTotal
                done.estimatedRemaining = 0
                stage = .done(done)
            default:
                return
            }
            if case .wrongPassword = stage { return }
            if stage == .pick { stage = .password }
        }
    #endif
}

/// 7.3 · Yedekten geri yükle — Restore from backup.
///
/// Entry points: 1.1 “Yedekten geri yükle” (new device) and 3.3 “Yedekten geri
/// yükle” (forgot the code). Push it; without a `file` it opens the system
/// document picker first (flows: picker → 7.3). `onFinished` continues with a
/// new secret code on this device (2.2 ×2 → 2.3 → 4.1).
struct RestoreBackupView: View {
    var file: URL? = nil
    var opensPickerOnAppear = true
    let onClose: () -> Void
    let onFinished: () -> Void

    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    /// Covered on a stack (2.2 over it after a restore): never opens the picker.
    @Environment(\.u2IsLayerVisible) private var isLayerVisible
    @State private var viewModel: RestoreBackupModel?
    @State private var showsPicker = false
    @State private var showsCancel = false
    @State private var closesAfterCancelSheet = false
    @State private var isCancelling = false
    @FocusState private var focus: PasswordFieldFocus?

    init(file: URL? = nil, opensPickerOnAppear: Bool = true, onClose: @escaping () -> Void, onFinished: @escaping () -> Void) {
        self.file = file
        self.opensPickerOnAppear = opensPickerOnAppear
        self.onClose = onClose
        self.onFinished = onFinished
    }

    var body: some View {
        U2Screen {
            VStack(spacing: 0) {
                CenteredTitleHeader(Text("restore.title", table: .settings)) {
                    U2GlassIconButton(.x, label: Text("a11y.close"), action: close)
                        .accessibilityIdentifier("restore.close")
                }
                if let viewModel {
                    screen(viewModel)
                } else {
                    Spacer()
                }
            }
        }
        .onAppear(perform: makeModel)
        .fileImporter(isPresented: $showsPicker, allowedContentTypes: Self.backupTypes) { result in
            guard case .success(let url) = result, let viewModel else { return }
            Task {
                await viewModel.pick(url)
                if viewModel.stage == .password { focus = .password }
            }
        }
        .u2Sheet(isPresented: $showsCancel, style: .floating, isDismissible: !isCancelling, onDismiss: closeAfterCancelSheet) {
            DestructiveConfirmation(
                title: StringTable.settings.string("restore.cancel.title", locale: locale),
                message: Text("restore.cancel.body", table: .settings),
                confirmTitle: Text("restore.cancel.action", table: .settings),
                confirmGlyph: nil,
                cancelTitle: Text("restore.cancel.keepGoing", table: .settings),
                isWorking: isCancelling,
                identifiers: ("restore.cancel.confirm", "restore.cancel.keepGoing"),
                onConfirm: confirmCancel,
                onCancel: { showsCancel = false }
            )
            .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
        }
    }

    static let backupTypes: [UTType] = [.us2usBackup]

    private func makeModel() {
        guard viewModel == nil else { return }
        let created = RestoreBackupModel(messenger: SettingsFlow.of(model).messenger)
        viewModel = created
        #if DEBUG
            if let state = SettingsHarness.take(prefix: "7.3") {
                created.applyHarness(state, clock: model.clock)
                if state == "7.3-cancel" {
                    Task {
                        try? await Task.sleep(for: .milliseconds(800))
                        showsCancel = true
                    }
                }
                return
            }
        #endif
        if let file {
            Task { await created.pick(file) }
        } else if opensPickerOnAppear && isLayerVisible {
            presentPicker()
        }
    }

    /// The system document picker for the `.u2u` file.
    private func presentPicker() {
        #if DEBUG
            if let url = DesignFixturesService.pickedBackupForTesting, let viewModel {
                Task { await viewModel.pick(url) }
                return
            }
        #endif
        showsPicker = true
    }

    // MARK: Layout

    @ViewBuilder private func screen(_ vm: RestoreBackupModel) -> some View {
        @Bindable var vm = vm
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 12) {
                fileCard(vm)
                if vm.file != nil {
                    PasswordField(
                        text: $vm.password, focus: $focus, field: .password,
                        trailing: vm.isPasswordAccepted ? .accepted : vm.stage == .checking ? .checking : .reveal,
                        isError: vm.stage == .wrongPassword,
                        isDisabled: vm.isPasswordAccepted || vm.stage == .checking,
                        submitLabel: .go, contentType: .password,
                        onSubmit: { submitPassword(vm) }
                    )
                    .accessibilityIdentifier("restore.password")
                    .transition(.u2Rise)
                    passwordCaption(vm)
                        .padding(.horizontal, 6)
                }
            }
            .padding(EdgeInsets(top: 18, leading: 16, bottom: 0, trailing: 16))

            ZStack {
                if let progress = vm.progress {
                    progressBlock(progress, isDone: { if case .done = vm.stage { true } else { false } }())
                        .padding(.horizontal, U2Spacing.screenGutterL)
                        .transition(.u2Rise)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            VStack(spacing: 12) {
                ZStack {
                    GreedyParagraph(text: note(vm), style: .captionTight, alignment: .center)
                        .fixedSize(horizontal: false, vertical: true)
                        .id(note(vm))
                        .transition(.u2Fade)
                }
                .frame(maxWidth: .infinity)
                .opacity(focus == nil ? 1 : 0)
                action(vm)
            }
            .padding(.horizontal, U2Spacing.screenGutter)
            .modifier(KeyboardAwareBottom(isKeyboardUp: focus != nil))
        }
        .u2Animation(U2Motion.state, value: vm.stage)
        .u2Animation(U2Motion.state, value: vm.file)
    }

    @ViewBuilder private func fileCard(_ vm: RestoreBackupModel) -> some View {
        if let file = vm.file {
            BackupFileCard(name: Text(verbatim: file.fileName), caption: fileCaption(file)) {
                PhosphorIcon(.checkCircle, weight: .fill, size: 22)
                    .foregroundStyle(Color.u2.online)
                    .accessibilityLabel(Text("a11y.restore.fileOK", table: .settings))
                    .transition(.u2Pop)
            }
            .transition(.u2Fade)
        } else {
            Button { presentPicker() } label: {
                BackupFileCard(
                    name: vm.stage == .inspecting ? Text("restore.inspecting", table: .settings) : Text("restore.pick", table: .settings),
                    caption: pickCaption(vm),
                    captionColor: { if case .failed = vm.stage { Color.u2.danger } else { Color.u2.textSecondary } }()
                ) {
                    Group {
                        if vm.stage == .inspecting {
                            SpinningGlyph(size: 20).foregroundStyle(Color.u2.textSecondary)
                        } else {
                            PhosphorIcon(.caretRight, size: 15).foregroundStyle(Color.u2.textTertiary)
                        }
                    }
                    .frame(width: 22)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(CardButtonStyle())
            .disabled(vm.stage == .inspecting)
            .accessibilityIdentifier("restore.pick")
            .transition(.u2Fade)
        }
    }

    private func passwordCaption(_ vm: RestoreBackupModel) -> some View {
        Group {
            switch vm.stage {
            case .wrongPassword:
                Text("restore.password.wrong", table: .settings).foregroundStyle(Color.u2.danger)
            case .restoring, .done:
                Text("restore.password.ok", table: .settings).foregroundStyle(Color.u2.textSecondary)
            case .checking:
                Text("restore.password.checking", table: .settings).foregroundStyle(Color.u2.textSecondary)
            default:
                Text("restore.password.hint", table: .settings).foregroundStyle(Color.u2.textSecondary)
            }
        }
        .u2Font(.caption)
        .contentTransition(.opacity)
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityAddTraits(.updatesFrequently)
    }

    private func progressBlock(_ progress: RestoreProgress, isDone: Bool) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .bottom, spacing: 0) {
                PercentText(value: Int((progress.fraction * 100).rounded(.down)))
                Spacer(minLength: 8)
                Group {
                    if isDone {
                        Text("restore.eta.done", table: .settings)
                    } else {
                        Text("restore.eta \(max(1, Int((progress.estimatedRemaining / 60).rounded())))", table: .settings)
                    }
                }
                .u2Font(.monoMeta)
                .foregroundStyle(Color.u2.textSecondary)
                .contentTransition(.opacity)
                .padding(.bottom, 7.95)
            }
            .accessibilityElement(children: .combine)
            DottedMeter(progress: progress.fraction, pattern: .progress)
                .u2Animation(U2Motion.micro, value: progress.fraction)
            VStack(alignment: .leading, spacing: 12) {
                ForEach(RestoreProgress.Stage.allCases, id: \.self) { stage in
                    stepRow(stage, progress: progress, isDone: isDone)
                }
            }
            .padding(.top, 4)
        }
        // Centred like the reference (r.2 jc:center): the measured block sits
        // 0.67 pt lower than SwiftUI's centring of the same boxes.
        .padding(.top, 1.33)
    }

    private enum StepState: Equatable {
        case done, current, pending
    }

    private func stepRow(_ step: RestoreProgress.Stage, progress: RestoreProgress, isDone: Bool) -> some View {
        let state: StepState =
            isDone || step.rawValue < progress.stage.rawValue ? .done : step == progress.stage ? .current : .pending
        let total = U2Format.integer(progress.messagesTotal, locale: locale)
        let media = U2Format.byteCount(progress.mediaBytes, locale: locale)
        let text: Text =
            switch (step, state) {
            case (.keys, .done): Text("restore.step.keys.done", table: .settings)
            case (.keys, _): Text("restore.step.keys.current", table: .settings)
            case (.messages, .done): Text("restore.step.messages.done \(total)", table: .settings)
            case (.messages, .current):
                Text("restore.step.messages.current \(U2Format.integer(progress.messagesDone, locale: locale)) \(total)", table: .settings)
            case (.messages, .pending): Text("restore.step.messages.pending \(total)", table: .settings)
            case (.media, .current): Text("restore.step.media.current \(media)", table: .settings)
            case (.media, _): Text("restore.step.media \(media)", table: .settings)
            }
        return HStack(spacing: 10) {
            ZStack {
                switch state {
                case .done:
                    PhosphorIcon(.check, size: 15).foregroundStyle(Color.u2.online).transition(.u2Fade)
                case .current:
                    SpinningGlyph(size: 15).foregroundStyle(Color.u2.accent300).transition(.u2Fade)
                case .pending:
                    PhosphorIcon(.circle, size: 15).foregroundStyle(Color.u2.textPending).transition(.u2Fade)
                }
            }
            .frame(width: 15, height: 15)
            text
                .u2Font(.monoCountdownChip)
                .foregroundStyle(state == .done ? Color.u2.textSecondary : state == .current ? Color.u2.text : Color.u2.textPending)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .contentTransition(.opacity)
        }
        .u2Animation(U2Motion.micro, value: state)
        .accessibilityElement(children: .combine)
    }

    private func note(_ vm: RestoreBackupModel) -> String {
        switch vm.stage {
        case .restoring: StringTable.settings.string("restore.note.restoring", locale: locale)
        case .done: StringTable.settings.string("restore.note.done", locale: locale)
        default: StringTable.settings.string("restore.note.private", locale: locale)
        }
    }

    @ViewBuilder private func action(_ vm: RestoreBackupModel) -> some View {
        switch vm.stage {
        case .pick, .inspecting, .failed:
            Button { presentPicker() } label: {
                U2ButtonLabel({ if case .failed = vm.stage { Text("restore.pickAnother", table: .settings) } else { Text("restore.pick", table: .settings) } }(),
                    glyph: .fileLock, isWorking: vm.stage == .inspecting)
            }
            .buttonStyle(.u2Primary)
            .allowsHitTesting(vm.stage != .inspecting)
            .accessibilityIdentifier("restore.action.pick")
        case .password, .checking, .wrongPassword:
            Button { submitPassword(vm) } label: {
                U2ButtonLabel(vm.stage == .checking ? Text("restore.checking", table: .settings) : Text("restore.submit", table: .settings),
                    isWorking: vm.stage == .checking)
            }
            .buttonStyle(.u2Primary)
            .disabled(!vm.canSubmitPassword && vm.stage != .checking)
            .allowsHitTesting(vm.stage != .checking)
            .accessibilityIdentifier("restore.action.submit")
        case .restoring:
            Button { showsCancel = true } label: {
                U2ButtonLabel(Text("restore.cancel", table: .settings))
            }
            .buttonStyle(.u2Secondary)
            .accessibilityIdentifier("restore.action.cancel")
        case .done:
            Button(action: onFinished) {
                U2ButtonLabel(Text("restore.continue", table: .settings))
            }
            .buttonStyle(.u2Primary)
            .accessibilityIdentifier("restore.action.continue")
        }
    }

    // MARK: Actions

    private func submitPassword(_ vm: RestoreBackupModel) {
        guard vm.canSubmitPassword else { return }
        focus = nil
        Task { await vm.submitPassword() }
    }

    private func close() {
        if viewModel?.isRestoring == true {
            showsCancel = true
        } else {
            focus = nil
            onClose()
        }
    }

    private func confirmCancel() {
        isCancelling = true
        Task {
            await viewModel?.cancelRestore()
            // 7.3 closes once the sheet has gone (`closeAfterCancelSheet`).
            closesAfterCancelSheet = true
            showsCancel = false
        }
    }

    private func closeAfterCancelSheet() {
        guard closesAfterCancelSheet else { return }
        closesAfterCancelSheet = false
        isCancelling = false
        onClose()
    }

    // MARK: Texts

    /// “184 MB · 8 Ekim 2026, 21:14”.
    private func fileCaption(_ file: BackupFileInfo) -> Text {
        let size = U2Format.byteCount(file.byteCount, locale: locale)
        guard let date = file.createdAt else { return Text(verbatim: size) }
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.setLocalizedDateFormatFromTemplate("dMMMMyyyy")
        let day = formatter.string(from: date)
        let time = U2Format.clockTime(date, locale: locale)
        return Text("restore.file.caption \(size) \(day) \(time)", table: .settings)
    }

    private func pickCaption(_ vm: RestoreBackupModel) -> Text {
        switch vm.stage {
        case .failed(.notABackup): Text("restore.error.notABackup", table: .settings)
        case .failed(.unreadable): Text("restore.error.unreadable", table: .settings)
        default: Text("restore.pick.caption", table: .settings)
        }
    }
}

/// “62%”: the number in `displayPercent` (Doto 900 76) and the sign in
/// `displayPercentSign` (Doto 900 42) on one baseline, in a 76 pt line box.
private struct PercentText: View {
    let value: Int

    var body: some View {
        let number = Font(U2TextStyle.displayPercent.uiFont() as CTFont)
        let sign = Font(U2TextStyle.displayPercentSign.uiFont() as CTFont)
        (Text(verbatim: "\(value)").font(number) + Text(verbatim: "%").font(sign))
            .foregroundStyle(Color.u2.text)
            .monospacedDigit()
            .fixedSize()
            .frame(height: U2TextStyle.displayPercent.lineHeight)
            // Doto's glyphs sit 0.67 pt lower in the 76 pt box than in the browser.
            .offset(y: -0.67)
            .contentTransition(.numericText(value: Double(value)))
            .accessibilityLabel(Text(verbatim: "\(value)%"))
    }
}

#Preview("7.3 Geri yükle") {
    let model = AppModel.preview()
    return RestoreBackupView(opensPickerOnAppear: false, onClose: {}, onFinished: {})
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}
