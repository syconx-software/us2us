import SwiftUI
import Us2usKit

// State of 01 · Karşılama and 02 · Kayıt ve gizli kod. Every screen reads a
// view model; the models talk only to `MessengerService`, so the live
// implementation (Us2usAppCore) replaces the fixtures without touching views.

// MARK: - Flow

/// The whole onboarding: the paged welcome (1.1–1.3), the pushed setup steps
/// (2.1–2.3) and the restore hand-off (7.3 → 2.2 → 2.3).
@Observable
@MainActor
final class OnboardingFlowModel {
    enum Mode: Equatable {
        /// New account: 2.1 → 2.2 → 2.3.
        case create
        /// After a restore (7.3): 2.2 → 2.3, no username step.
        case restore
    }

    let router = U2Router<OnboardingRoute>()
    let pager = WelcomePagerModel()
    let username: UsernameStepModel
    let code: SecretCodeStepModel
    let finish: NoWayBackStepModel
    private(set) var mode: Mode = .create

    /// The confirmed code, held only until the account is created.
    private var confirmedCode: String?
    private let messenger: any MessengerService
    private let onFinished: () -> Void

    init(messenger: any MessengerService, onFinished: @escaping () -> Void) {
        self.messenger = messenger
        self.onFinished = onFinished
        username = UsernameStepModel(messenger: messenger)
        code = SecretCodeStepModel(messenger: messenger)
        finish = NoWayBackStepModel()
        code.onConfirmed = { [weak self] code in self?.codeConfirmed(code) }
        finish.onSubmit = { [weak self] in await self?.createAccount() ?? false }
    }

    /// “n / 3”, or “n / 2” after a restore (no username step).
    func stepLabel(for route: OnboardingRoute) -> (step: Int, count: Int) {
        let step = route.setupStep ?? 1
        return mode == .restore ? (max(step - 1, 1), 2) : (step, 3)
    }

    // MARK: Navigation

    /// 1.3 “Hesabımı oluştur” → 2.1.
    func startSetup() {
        mode = .create
        router.push(.username)
    }

    /// 1.1 “Yedekten geri yükle” → 7.3 (the document picker opens there).
    func startRestore() {
        router.push(.restore)
    }

    /// 3.3 › 7.3 restored a backup inside the lock: onboarding starts at
    /// 2.2 (“1 / 2”) for the new code on this device → 2.3 → 4.1.
    func continueAfterRestore() {
        mode = .restore
        code.reset()
        router.setPath([.secretCode])
    }

    /// 7.3 finished on this device → 2.2 set a new code.
    func restoreFinished() {
        mode = .restore
        code.reset()
        router.push(.secretCode)
    }

    /// 2.1 “Devam” (only when available).
    func usernameContinue() {
        guard username.canContinue else { return }
        U2Keyboard.dismiss()
        code.reset()
        router.push(.secretCode)
    }

    /// Back on 2.2: from the confirm step to the set step, else pop.
    func codeBack() {
        if code.step == .confirm {
            code.backToSet()
        } else {
            router.pop()
        }
    }

    /// Edge-swipe back is allowed everywhere except on 2.2's confirm step
    /// (flows.md › Back behaviour: back returns to the set step there).
    func allowsSwipeBack(_ route: OnboardingRoute) -> Bool {
        !(route == .secretCode && code.step == .confirm)
    }

    /// The path changed (button, swipe or harness): leaving 2.3 for 2.2
    /// starts the code over — a confirmed code is never shown again.
    func pathChanged(from old: [OnboardingRoute], to new: [OnboardingRoute]) {
        if old.last == .noWayBack, new.last == .secretCode {
            confirmedCode = nil
            code.reset()
        }
        if new.last == nil || new.last == .username {
            if mode == .restore, !new.contains(.restore) { mode = .create }
        }
    }

    private func codeConfirmed(_ code: String) {
        confirmedCode = code
        finish.prepare()
        router.push(.noWayBack)
    }

    /// 2.3 “Kurulumu tamamla”: create (or finish restoring) the account,
    /// then replace the root with 4.1. `false` = failed, stay on 2.3.
    private func createAccount() async -> Bool {
        guard let secret = confirmedCode else { return false }
        do {
            switch mode {
            case .create:
                try await messenger.createAccount(
                    username: username.name, secretCode: secret, faceIDEnabled: finish.faceIDEnabled)
            case .restore:
                try await messenger.completeRestore(secretCode: secret, faceIDEnabled: finish.faceIDEnabled)
            }
        } catch {
            return false
        }
        confirmedCode = nil
        onFinished()
        return true
    }

    #if DEBUG
        /// Harness: sets what the drawn state needs without running the flow.
        func setForHarness(mode: Mode, path: [OnboardingRoute], confirmedCode: String? = nil) {
            self.mode = mode
            self.confirmedCode = confirmedCode
            router.setPath(path)
        }
    #endif
}

// MARK: - 1.1 → 1.3 pager

/// The paged welcome: the committed page and the live swipe offset.
@Observable
@MainActor
final class WelcomePagerModel {
    static let pageCount = 3

    /// The page the chrome (pager label, dots, CTA) shows.
    private(set) var page = 0
    /// The swipe in pages while the finger is down (−1…1; positive = forward).
    var dragProgress: Double = 0 {
        didSet { updateMotion() }
    }
    /// Page changes still animating.
    @ObservationIgnored private var changesInFlight = 0

    /// Whether the pages are moving (a finger on them, a change settling),
    /// published to the pages as `\.u2LayerMotion`: the 3D scene on 1.1
    /// renders only while it is settled, so a page change never shares its
    /// frames with the scene's render loop.
    @ObservationIgnored let motion = U2LayerMotion()

    /// Where the pages are drawn (page + live swipe).
    var position: Double { Double(page) + dragProgress }

    private func updateMotion() {
        motion.setMoving(dragProgress != 0 || changesInFlight > 0)
    }

    /// Changes page on the design curve (0.5 s); after a swipe the change
    /// settles on the critically damped spring instead, so the motion
    /// carries on from the finger without a pause.
    func go(to newPage: Int, animation: Animation = U2Motion.screen) {
        let target = min(max(newPage, 0), Self.pageCount - 1)
        let end = U2Signposts.animation("page")
        changesInFlight += 1
        withU2Animation(animation, {
            page = target
            dragProgress = 0
        }) { [weak self] in
            end()
            guard let self else { return }
            self.changesInFlight = max(0, self.changesInFlight - 1)
            self.updateMotion()
        }
    }

    func advance() { go(to: page + 1) }

    /// Ends a swipe: commit when far or fast enough, else settle back — at
    /// the finger's speed (`velocity` in pages per second, positive = forward).
    func endDrag(predictedProgress: Double, velocity: Double = 0) {
        let forward = dragProgress > 0.3 || predictedProgress > 0.6
        let backward = dragProgress < -0.3 || predictedProgress < -0.6
        if forward, page < Self.pageCount - 1 {
            // Remaining: 1 − dragProgress pages forward.
            go(to: page + 1, animation: U2Motion.spring(initialVelocity: Self.relative(velocity, remaining: 1 - dragProgress)))
        } else if backward, page > 0 {
            go(to: page - 1, animation: U2Motion.spring(initialVelocity: Self.relative(-velocity, remaining: 1 + dragProgress)))
        } else {
            let animation = U2Motion.spring(initialVelocity: Self.relative(-velocity * (dragProgress >= 0 ? 1 : -1), remaining: abs(dragProgress)))
            changesInFlight += 1
            withU2Animation(animation, { dragProgress = 0 }) { [weak self] in
                guard let self else { return }
                self.changesInFlight = max(0, self.changesInFlight - 1)
                self.updateMotion()
            }
        }
    }

    private static func relative(_ pagesPerSecond: Double, remaining: Double) -> Double {
        // In points of a nominal 400 pt page: the ratio is the same, and the
        // helper's “already there” threshold (0.5) means half a point, not
        // half a page.
        U2Motion.relativeVelocity(CGFloat(pagesPerSecond * 400), remaining: CGFloat(remaining * 400))
    }

    #if DEBUG
        func setForHarness(page: Int) {
            self.page = page
            dragProgress = 0
        }
    #endif
}

// MARK: - 2.1 Kullanıcı adı

/// 2.1 state: the name as typed, local rules, the debounced availability
/// check and what the screen shows for it.
@Observable
@MainActor
final class UsernameStepModel {
    enum Status: Equatable {
        /// Nothing typed.
        case empty
        /// Typing; the check waits for a pause (≈ 400 ms).
        case typing
        /// Fewer than 3 characters after a pause.
        case tooShort
        case checking
        case available
        case taken
        /// The relay refused the name by its own rules.
        case invalid(UsernameRules.Violation)
    }

    /// What the field shows (raw keystrokes pass through here; the field
    /// itself reduces them to `[a-z0-9._]`).
    var text = "" {
        didSet { textChanged(from: oldValue) }
    }

    private(set) var status: Status = .empty
    /// A rule the last keystroke broke (the character was dropped): shows
    /// “Geçersiz karakter” and tints that rule until the next valid change.
    private(set) var rejectedInput: UsernameRules.Violation?
    /// The clean name (what is checked and registered).
    private(set) var name = ""
    private var checkTask: Task<Void, Never>?
    private var rejectionTask: Task<Void, Never>?
    private let messenger: any MessengerService
    /// The pause after typing before the relay is asked.
    var debounce: Duration = .milliseconds(400)

    init(messenger: any MessengerService) {
        self.messenger = messenger
    }

    var canContinue: Bool { status == .available && UsernameRules.isValid(name) }

    /// “7 / 20”.
    var counter: String { "\(name.count) / \(UsernameRules.maxLength)" }

    var fieldStatus: UsernameFieldStatus {
        // While “Geçersiz karakter” shows, the field shows no verdict (the
        // icon cross-fades out): only the danger label and the tinted rule.
        guard rejectedInput == nil else { return .none }
        switch status {
        case .checking: return .checking
        case .available: return .positive
        case .taken: return .negative
        default: return .none
        }
    }

    /// Which rule is shown in `danger`.
    func isBroken(_ rule: UsernameRules.Violation) -> Bool {
        if rejectedInput == rule { return true }
        switch status {
        case .tooShort: return rule == .length
        case .invalid(let violation): return rule == violation
        default: return false
        }
    }

    private func textChanged(from old: String) {
        guard text != old else { return }
        let clean = UsernameRules.sanitize(text)
        if text != clean {
            if text.contains(where: { UsernameRules.turkishCharacters.contains($0) }) {
                setRejected(.turkishCharacters)
            } else if text.unicodeScalars.contains(where: { !Self.isAllowedRaw($0) }) {
                setRejected(.characters)
            }
        } else if clean != name {
            setRejected(nil)
        }
        guard clean != name else { return }
        name = clean
        scheduleCheck()
    }

    /// Characters the field keeps (upper-case ASCII is folded, not rejected).
    private static func isAllowedRaw(_ scalar: Unicode.Scalar) -> Bool {
        ("a"..."z").contains(scalar) || ("A"..."Z").contains(scalar) || ("0"..."9").contains(scalar)
            || scalar == "." || scalar == "_"
    }

    /// Shows the broken rule; it fades back by itself after a moment (or at
    /// the next valid keystroke), since the name in the field is still fine.
    private func setRejected(_ rule: UsernameRules.Violation?) {
        rejectionTask?.cancel()
        if rejectedInput != rule {
            withU2Animation(U2Motion.state) { rejectedInput = rule }
        }
        guard rule != nil else { return }
        rejectionTask = Task { [weak self] in
            try? await Task.sleep(for: Self.rejectionHold)
            guard !Task.isCancelled, let self else { return }
            withU2Animation(U2Motion.state) { self.rejectedInput = nil }
        }
    }

    /// How long a dropped character keeps “Geçersiz karakter” on screen.
    static let rejectionHold: Duration = .milliseconds(2200)

    private func scheduleCheck() {
        checkTask?.cancel()
        let candidate = name
        guard !candidate.isEmpty else {
            withU2Animation(U2Motion.micro) { status = .empty }
            return
        }
        if status != .typing { withU2Animation(U2Motion.micro) { status = .typing } }
        checkTask = Task { [weak self, messenger, debounce] in
            try? await Task.sleep(for: debounce)
            guard !Task.isCancelled, let self else { return }
            if candidate.count < UsernameRules.minLength {
                withU2Animation(U2Motion.state) { self.status = .tooShort }
                return
            }
            withU2Animation(U2Motion.micro) { self.status = .checking }
            let result = await messenger.usernameAvailability(candidate)
            guard !Task.isCancelled, self.name == candidate else { return }
            withU2Animation(U2Motion.state) {
                switch result {
                case .available: self.status = .available
                case .taken: self.status = .taken
                case .invalid(let violation):
                    self.status = violation == .length ? .tooShort : .invalid(violation)
                case .empty: self.status = .empty
                case .checking: self.status = .checking
                }
            }
        }
    }

    #if DEBUG
        /// Harness: shows `name` in `status` without asking the relay.
        func setForHarness(name: String, status: Status, rejected: UsernameRules.Violation? = nil) {
            checkTask?.cancel()
            self.name = name
            text = name
            self.status = status
            rejectionTask?.cancel()
            rejectedInput = rejected
        }
    #endif
}

// MARK: - 2.2 Gizli kod

/// 2.2 state: six digits, auto-advance, entered twice; weak codes and a
/// mismatch flash the dots `danger` with a sentence, then start over.
@Observable
@MainActor
final class SecretCodeStepModel {
    enum Step: Equatable {
        case set
        case confirm
    }

    enum Problem: Equatable {
        /// Us2usKit refused the code (repeated digit, run, too common).
        case weak
        /// The confirmation was different.
        case mismatch
    }

    private(set) var step: Step = .set
    /// Digits typed so far (never shown, only counted).
    private(set) var entered: [UInt8] = []
    private(set) var appearance: CodeDots.Appearance = .normal
    private(set) var shakeTrigger = 0
    private(set) var problem: Problem?
    /// Keys ignored while the last dot is held or an error plays.
    private(set) var isBusy = false

    var onConfirmed: (String) -> Void = { _ in }

    private var firstCode: [UInt8]?
    private var task: Task<Void, Never>?
    private let messenger: any MessengerService
    #if DEBUG
        /// Harness: hold the drawn state (no automatic reset/advance).
        var isFrozenForHarness = false
    #endif

    /// The last dot stays visible before the code is checked (0.25 s).
    static let hold: Duration = .milliseconds(250)
    /// Error dots stay red, then the code starts over (0.8 s).
    static let errorHold: Duration = .milliseconds(800)

    init(messenger: any MessengerService) {
        self.messenger = messenger
    }

    func type(_ digit: Int) {
        guard !isBusy, entered.count < SecretCode.length, (0...9).contains(digit) else { return }
        if problem != nil { withU2Animation(U2Motion.state) { problem = nil } }
        entered.append(UInt8(digit))
        if entered.count == SecretCode.length { submit() }
    }

    func delete() {
        guard !isBusy, !entered.isEmpty else { return }
        entered.removeLast()
    }

    /// Back from the confirm step: choose the code again.
    func backToSet() {
        task?.cancel()
        withU2Animation(U2Motion.state) {
            step = .set
            problem = nil
        }
        clearDots()
        firstCode = nil
        isBusy = false
    }

    /// A fresh set step (entering 2.2, or returning to it from 2.3).
    func reset() {
        task?.cancel()
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            step = .set
            problem = nil
            appearance = .normal
            entered.removeAll()
        }
        firstCode = nil
        isBusy = false
    }

    private func submit() {
        isBusy = true
        let code = entered
        task = Task { [weak self] in
            try? await Task.sleep(for: Self.hold)
            guard !Task.isCancelled, let self else { return }
            #if DEBUG
                if self.isFrozenForHarness { return }
            #endif
            self.evaluate(code)
        }
    }

    private func evaluate(_ code: [UInt8]) {
        let string = String(code.map { Character(String($0)) })
        switch step {
        case .set:
            if messenger.secretCodeProblem(string) != nil {
                fail(.weak, backToSet: false)
            } else {
                firstCode = code
                withU2Animation(U2Motion.state) { step = .confirm }
                clearDots()
                isBusy = false
            }
        case .confirm:
            if code == firstCode {
                firstCode = nil
                onConfirmed(string)
            } else {
                fail(.mismatch, backToSet: true)
            }
        }
    }

    /// Dots `danger` + soft shake + the sentence; after 0.8 s the dots clear
    /// (and a mismatch returns to the set step). The sentence stays until
    /// the next digit.
    private func fail(_ problem: Problem, backToSet: Bool) {
        U2Haptics.error()
        withU2Animation(U2Motion.micro) { appearance = .error }
        shakeTrigger += 1
        withU2Animation(U2Motion.state) { self.problem = problem }
        task = Task { [weak self] in
            try? await Task.sleep(for: Self.errorHold)
            guard !Task.isCancelled, let self else { return }
            if backToSet {
                self.firstCode = nil
                withU2Animation(U2Motion.state) { self.step = .set }
            }
            self.clearDots()
            self.isBusy = false
        }
    }

    private func clearDots() {
        withU2Animation(U2Motion.micro) {
            appearance = .normal
            entered.removeAll()
        }
    }

    #if DEBUG
        func setForHarness(step: Step, entered count: Int, problem: Problem? = nil) {
            isFrozenForHarness = true
            self.step = step
            entered = Array(repeating: 1, count: count)
            self.problem = problem
            appearance = problem == nil ? .normal : .error
            isBusy = problem != nil
        }
    #endif
}

// MARK: - 2.3 Geri dönüş yok

/// 2.3 state: the acknowledgement, the Face ID preference and the account
/// creation call.
@Observable
@MainActor
final class NoWayBackStepModel {
    var acknowledged = false
    var faceIDEnabled = true
    private(set) var biometry: Biometry = .none
    private(set) var isWorking = false
    private(set) var failed = false

    var onSubmit: () async -> Bool = { false }

    var canFinish: Bool { acknowledged && !isWorking }

    /// Called each time 2.3 is entered: unticked, Face ID on when available.
    func prepare() {
        biometry = Biometry.hardware
        acknowledged = false
        faceIDEnabled = biometry != .none
        failed = false
        isWorking = false
    }

    func submit() {
        guard canFinish else { return }
        withU2Animation(U2Motion.state) {
            isWorking = true
            failed = false
        }
        Task { [weak self] in
            guard let self else { return }
            let ok = await self.onSubmit()
            if !ok {
                U2Haptics.error()
                withU2Animation(U2Motion.state) {
                    self.isWorking = false
                    self.failed = true
                }
            }
        }
    }

    #if DEBUG
        func setForHarness(acknowledged: Bool, failed: Bool = false, biometry: Biometry = .faceID) {
            self.biometry = biometry
            self.acknowledged = acknowledged
            faceIDEnabled = biometry != .none
            self.failed = failed
            isWorking = false
        }
    #endif
}
