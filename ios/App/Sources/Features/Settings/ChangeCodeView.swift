import SwiftUI
import Us2usKit

/// State of 7.1 › Gizli kodu değiştir: the current code (3.2 layout, through
/// the lock's throttle) → a new code (2.2 layout, the same rules as setup) →
/// the new code again → re-key. Codes live only in this model.
@Observable
@MainActor
final class ChangeCodeModel {
    enum Step: Int, Equatable {
        case current = 1
        case new = 2
        case confirm = 3
    }

    enum Message: Equatable {
        /// “Kod yanlış. {n} yanlış daha olursa {süre} beklemen gerekir.”
        case wrong(attemptsLeft: Int, delay: TimeInterval)
        /// “Kod yanlış.”
        case wrongPlain
        /// “Çok fazla yanlış deneme. {mm:ss} sonra tekrar dene.”
        case throttled(until: Date)
        /// The new code is too easy to guess (Us2usKit rules).
        case weak
        /// The new code equals the current one.
        case sameAsCurrent
        /// The confirmation differs — back to the new-code step.
        case mismatch
        /// Re-keying failed; the old code still works.
        case failed
    }

    private(set) var step: Step = .current
    private(set) var entered = ""
    private(set) var appearance: CodeDots.Appearance = .normal
    private(set) var shakeTrigger = 0
    private(set) var message: Message?
    private(set) var isBusy = false

    private var currentCode = ""
    private var newCode = ""
    private let messenger: any MessengerService
    private let onChanged: () -> Void

    init(messenger: any MessengerService, onChanged: @escaping () -> Void) {
        self.messenger = messenger
        self.onChanged = onChanged
        if let next = messenger.unlockStatus.nextAttemptAllowedAt, next > Date() {
            message = .throttled(until: next)
            appearance = .dimmed
        }
    }

    var isThrottled: Bool {
        if case .throttled(let until) = message { return until > Date() }
        return false
    }

    func type(_ digit: Int) {
        guard !isBusy, !isThrottled, entered.count < SecretCode.length else { return }
        if appearance == .error {
            withU2Animation(U2Motion.micro) {
                appearance = .normal
                entered = ""
            }
        }
        if message != nil, !isThrottled {
            withU2Animation(U2Motion.state) { message = nil }
        }
        entered.append(String(digit))
        if entered.count == SecretCode.length {
            Task { await complete() }
        }
    }

    func deleteLast() {
        guard !isBusy, !entered.isEmpty, appearance != .error else { return }
        entered.removeLast()
    }

    /// The header's back button: confirm → new; otherwise leave.
    func back() -> Bool {
        guard step == .confirm, !isBusy else { return false }
        advance(to: .new)
        return true
    }

    func throttleEnded() {
        guard case .throttled = message else { return }
        withU2Animation(U2Motion.state) {
            message = nil
            appearance = .normal
        }
    }

    private func complete() async {
        isBusy = true
        // Let the sixth dot be seen (setupAdvance, 0.25 s).
        try? await Task.sleep(for: .milliseconds(250))
        let code = entered
        switch step {
        case .current:
            let result = await messenger.verifySecretCode(code)
            isBusy = false
            switch result {
            case .unlocked:
                currentCode = code
                advance(to: .new)
            case .wrongCode(let status):
                if let next = status.nextAttemptAllowedAt, next > Date() {
                    fail(.throttled(until: next))
                } else if let upcoming = status.upcoming {
                    fail(.wrong(attemptsLeft: upcoming.attemptsLeft, delay: upcoming.delay))
                } else {
                    fail(.wrongPlain)
                }
            case .throttled(let until):
                withU2Animation(U2Motion.state) {
                    message = .throttled(until: until)
                    appearance = .dimmed
                    entered = ""
                }
            case .malformed:
                entered = ""
            }
        case .new:
            isBusy = false
            if messenger.secretCodeProblem(code) != nil {
                fail(.weak)
            } else if code == currentCode {
                fail(.sameAsCurrent)
            } else {
                newCode = code
                advance(to: .confirm)
            }
        case .confirm:
            guard code == newCode else {
                // Red dots on this step for 0.8 s, then back to the new code.
                fail(.mismatch)
                try? await Task.sleep(for: .milliseconds(800))
                isBusy = false
                advance(to: .new, keepMessage: true)
                return
            }
            do {
                try await messenger.changeSecretCode(current: currentCode, new: newCode)
                currentCode = ""
                newCode = ""
                U2Haptics.success()
                onChanged()
            } catch {
                fail(.failed)
                try? await Task.sleep(for: .milliseconds(800))
                isBusy = false
                advance(to: .new, keepMessage: true)
            }
        }
    }

    /// Error state: dots `danger` + soft shake, the sentence fades in. The dots
    /// clear after 0.8 s unless the next digit already did; typing is never blocked.
    private func fail(_ message: Message) {
        U2Haptics.error()
        withU2Animation(U2Motion.micro) {
            appearance = .error
            shakeTrigger += 1
            self.message = message
        }
        let trigger = shakeTrigger
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(800))
            guard let self, self.shakeTrigger == trigger, self.appearance == .error else { return }
            withU2Animation(U2Motion.micro) {
                self.entered = ""
                self.appearance = self.isThrottled ? .dimmed : .normal
            }
        }
    }

    private func advance(to next: Step, keepMessage: Bool = false) {
        withU2Animation(U2Motion.state) {
            step = next
            entered = ""
            appearance = .normal
            if !keepMessage { message = nil }
        }
    }

    #if DEBUG
        /// DEBUG harness states (`-U2Route settings:code…`).
        func applyHarness(_ state: String) {
            switch state {
            case "code-wrong":
                entered = "135790"
                appearance = .error
                message = .wrong(attemptsLeft: 2, delay: 60)
            case "code-new":
                step = .new
                currentCode = DesignFixtures.demoSecretCode
                entered = "4719"
            case "code-weak":
                step = .new
                currentCode = DesignFixtures.demoSecretCode
                entered = "123456"
                appearance = .error
                message = .weak
            case "code-confirm":
                step = .confirm
                currentCode = DesignFixtures.demoSecretCode
                newCode = "471958"
                entered = "47"
            case "code-mismatch":
                step = .new
                currentCode = DesignFixtures.demoSecretCode
                message = .mismatch
            default:
                break
            }
        }
    #endif
}

/// 7.1 › Gizli kodu değiştir — three steps on one pushed screen. The keypad
/// stays where it is (2.2 position); only the upper block cross-fades between
/// steps (0.36 s), the dots reset softly.
struct ChangeCodeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @State private var viewModel: ChangeCodeModel?

    var body: some View {
        U2Screen {
            if let viewModel {
                content(viewModel)
            } else {
                Color.clear
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .onAppear(perform: makeModel)
    }

    private func makeModel() {
        guard viewModel == nil else { return }
        let flow = SettingsFlow.of(model)
        let router = model.router
        let created = ChangeCodeModel(messenger: flow.messenger) {
            flow.toast = Text("code.changed.toast", table: .settings)
            router.pop()
        }
        #if DEBUG
            if let state = SettingsHarness.take(prefix: "code") { created.applyHarness(state) }
        #endif
        viewModel = created
    }

    private func content(_ vm: ChangeCodeModel) -> some View {
        VStack(spacing: 0) {
            SetupHeader(step: vm.step.rawValue) {
                if !vm.back() { model.router.pop() }
            }
            ZStack(alignment: .top) {
                switch vm.step {
                case .current:
                    currentBlock(vm).transition(.u2Fade)
                case .new:
                    setupBlock(vm, title: "code.new.title", body: "code.new.body").transition(.u2Fade)
                case .confirm:
                    setupBlock(vm, title: "code.confirm.title", body: "code.confirm.body").transition(.u2Fade)
                }
            }
            .frame(maxWidth: .infinity, alignment: .top)
            Spacer(minLength: 12)
            NumericKeypad(onDigit: vm.type, onDelete: vm.deleteLast)
                .disabled(vm.isThrottled)
                .u2Bottom(56)
        }
        .u2Animation(U2Motion.state, value: vm.step)
    }

    /// Step 1 — the 3.2 composition: lock badge, centred title, dots, message.
    private func currentBlock(_ vm: ChangeCodeModel) -> some View {
        VStack(spacing: 0) {
            VStack(spacing: 16) {
                PhosphorIcon(.lockSimple, size: 24)
                    .foregroundStyle(Color.u2.accent300)
                    .frame(width: U2Size.lockIcon, height: U2Size.lockIcon)
                    .background(Circle().fill(Color.u2.accent900))
                    .overlay(Circle().strokeBorder(Color.u2.accent800, lineWidth: 1))
                    .accessibilityHidden(true)
                Text("code.current.title", table: .settings)
                    .u2Font(.displayTitleXS)
                    .foregroundStyle(Color.u2.text)
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
            }
            .padding(.top, 6)
            dotsAndMessage(vm)
                .padding(.top, 36)
        }
        .frame(maxWidth: .infinity)
    }

    /// Steps 2 and 3 — the 2.2 composition: Doto title (2 lines), paragraph, dots.
    private func setupBlock(_ vm: ChangeCodeModel, title: String.LocalizationValue, body: LocalizedStringKey) -> some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: U2Spacing.titleToBodyGap) {
                SetupTitle(StringTable.settings.resource(title), style: .displayTitleM.balanced)
                Text(body, table: .settings)
                    .u2Font(.body)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 18, leading: U2Spacing.screenGutterL, bottom: 0, trailing: U2Spacing.screenGutterL))
            dotsAndMessage(vm)
                .padding(.top, 44)
        }
    }

    private func dotsAndMessage(_ vm: ChangeCodeModel) -> some View {
        VStack(spacing: 16) {
            CodeDots(filled: vm.entered.count, appearance: vm.appearance, shakeTrigger: vm.shakeTrigger)
            ZStack(alignment: .top) {
                if let message = vm.message {
                    messageView(message, vm: vm)
                }
            }
            .frame(maxWidth: 300, minHeight: 42, alignment: .top)
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder private func messageView(_ message: ChangeCodeModel.Message, vm: ChangeCodeModel) -> some View {
        switch message {
        case .wrongPlain: sentence(Text("code.wrong", table: .settings))
        case .wrong(let attemptsLeft, let delay):
            sentence(Text("code.wrong.warning \(attemptsLeft) \(Self.duration(delay, locale: locale))", table: .settings))
        case .throttled(let until):
            U2TimeView(interval: 1) { now in
                let remaining = until.timeIntervalSince(now)
                sentence(Text("code.throttled \(U2Format.minutesSeconds(remaining))", table: .settings))
                    .onChange(of: remaining <= 0) { _, ended in
                        if ended { vm.throttleEnded() }
                    }
            }
        case .weak: sentence(Text("code.weak", table: .settings))
        case .sameAsCurrent: sentence(Text("code.same", table: .settings))
        case .mismatch: sentence(Text("code.mismatch", table: .settings))
        case .failed: sentence(Text("code.failed", table: .settings))
        }
    }

    private func sentence(_ text: Text) -> some View {
        text
            .u2Font(.calloutL)
            .foregroundStyle(Color.u2.danger)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .transition(.u2Fade)
            .accessibilityAddTraits(.updatesFrequently)
    }

    /// “1 dakika”, “5 dakika”, “1 saat” / “1 minute” …
    static func duration(_ seconds: TimeInterval, locale: Locale) -> String {
        let formatter = DateComponentsFormatter()
        var calendar = Calendar.current
        calendar.locale = locale
        formatter.calendar = calendar
        formatter.unitsStyle = .full
        formatter.allowedUnits = seconds >= 3_600 ? [.hour] : [.minute]
        return formatter.string(from: seconds) ?? ""
    }
}

#Preview("Gizli kodu değiştir") {
    let model = AppModel.preview()
    return ChangeCodeView()
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}
