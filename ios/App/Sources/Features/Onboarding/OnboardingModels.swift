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

    /// The confirmed code, held only until the account is created — also
    /// while another name is chosen after 2.3 found the first one taken.
    private var confirmedCode: String?
    private let messenger: any MessengerService
    private let onFinished: () -> Void
    private let onReturnToLock: () -> Void

    /// - Parameter onReturnToLock: 2.3 failed but left a vault on this
    ///   phone, locked (``SetupInterruption``): the lock takes over.
    init(
        messenger: any MessengerService, onFinished: @escaping () -> Void,
        onReturnToLock: @escaping () -> Void = {}
    ) {
        self.messenger = messenger
        self.onFinished = onFinished
        self.onReturnToLock = onReturnToLock
        username = UsernameStepModel(messenger: messenger)
        code = SecretCodeStepModel(messenger: messenger)
        finish = NoWayBackStepModel()
        code.onConfirmed = { [weak self] code in self?.codeConfirmed(code) }
        finish.onSubmit = { [weak self] in
            guard let self else { return .network }
            return await self.createAccount()
        }
        finish.onReturnToLock = { [weak self] in
            self?.confirmedCode = nil
            onReturnToLock()
        }
        finish.onChooseAnotherName = { [weak self] in self?.chooseAnotherName() }
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
        finish.replacesThisPhone = false
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
        // The locked vault on this phone is replaced when 2.3 finishes.
        finish.replacesThisPhone = true
        code.reset()
        router.setPath([.secretCode])
    }

    /// 7.3 finished on this device → 2.2 set a new code.
    func restoreFinished() {
        mode = .restore
        finish.replacesThisPhone = false
        code.reset()
        router.push(.secretCode)
    }

    /// 2.1 “Devam” (only when the name can be used): on to 2.2 — or, with
    /// the code kept from a setup that found the name taken, back to 2.3.
    func usernameContinue() {
        guard username.canContinue else { return }
        U2Keyboard.dismiss()
        if confirmedCode != nil {
            finish.resume()
            router.push(.noWayBack)
            return
        }
        code.reset()
        router.push(.secretCode)
    }

    /// 2.3 “Başka bir ad seç”: back to 2.1, the taken name still in the
    /// field and marked; the code chosen on 2.2 is kept.
    private func chooseAnotherName() {
        router.pop(to: .username)
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
        if route == .noWayBack, finish.returnsToLock { return false }
        return !(route == .secretCode && code.step == .confirm)
    }

    /// The path changed (button, swipe or harness): leaving 2.3 for 2.2
    /// starts the code over — a confirmed code is never shown again.
    func pathChanged(from old: [OnboardingRoute], to new: [OnboardingRoute]) {
        if old.last == .noWayBack, new.last == .secretCode {
            confirmedCode = nil
            code.reset()
        }
        if new.isEmpty, mode == .restore, finish.replacesThisPhone {
            // Backed out of a restore that began on the lock (3.3 › 7.3):
            // the vault on this phone is still there, locked, so 1.1 could
            // only offer a new account that cannot be made. Back to the
            // lock; the opened backup and its password go.
            confirmedCode = nil
            finish.replacesThisPhone = false
            mode = .create
            Task { await messenger.cancelRestore() }
            onReturnToLock()
            return
        }
        if new.last == nil || new.last == .username {
            if mode == .restore, !new.contains(.restore) { mode = .create }
        }
        // Back on the welcome pages: a code kept for another name goes.
        if new.isEmpty { confirmedCode = nil }
    }

    private func codeConfirmed(_ code: String) {
        confirmedCode = code
        finish.prepare()
        router.push(.noWayBack)
    }

    /// 2.3 “Kurulumu tamamla”: create (or finish restoring) the account,
    /// then replace the root with 4.1. A failure stays on 2.3 and says why.
    private func createAccount() async -> NoWayBackStepModel.Failure? {
        guard let secret = confirmedCode else { return .network }
        // Found taken earlier in this setup (2.3 › back to 2.2 › the code
        // again): the relay would only refuse it once more, after the keys
        // and the proof of work.
        if mode == .create, username.status == .taken { return .usernameTaken }
        do {
            switch mode {
            case .create:
                try await messenger.createAccount(
                    username: username.name, secretCode: secret, faceIDEnabled: finish.faceIDEnabled)
            case .restore:
                try await messenger.completeRestore(secretCode: secret, faceIDEnabled: finish.faceIDEnabled)
            }
        } catch RestoreFailure.backupNoLongerValid {
            return .backupGone
        } catch RestoreFailure.backupDamaged {
            return .backupDamaged
        } catch SetupInterruption.accountKept {
            confirmedCode = nil
            return .accountKept
        } catch SetupInterruption.previousVaultKept {
            confirmedCode = nil
            return .previousVaultKept
        } catch AccountCreationFailure.usernameTaken {
            // Nothing was kept: the code stays for the next name.
            username.markTaken(username.name)
            return .usernameTaken
        } catch {
            return .network
        }
        confirmedCode = nil
        onFinished()
        return nil
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

/// 2.1 state: the name as typed, the rules it breaks, the debounced
/// availability check and what the screen shows for it.
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
        /// The name breaks a rule (the first one): nothing is asked.
        case invalid(UsernameRules.Violation)
        case checking
        /// Valid; whether it is free is known only at setup (live service).
        case meetsRules
        case available
        case taken
    }

    /// What the field shows, as typed (the field shows it in lower case).
    var text = "" {
        didSet { textChanged(from: oldValue) }
    }

    private(set) var status: Status = .empty
    /// The normalized name (what is checked and registered).
    private(set) var name = ""
    /// Every rule `name` breaks (`UsernameRules.violations`).
    private(set) var violations: [UsernameRules.Violation] = []
    /// Typing paused since the last change: a short name is said only now.
    private var hasPaused = false
    /// Names 2.3 found taken in this setup (the relay's 409).
    private var takenAtSetup: Set<String> = []
    private var checkTask: Task<Void, Never>?
    private let messenger: any MessengerService
    /// The pause after typing before the relay is asked.
    var debounce: Duration = .milliseconds(400)

    init(messenger: any MessengerService) {
        self.messenger = messenger
    }

    var canContinue: Bool { (status == .available || status == .meetsRules) && UsernameRules.isValid(name) }

    /// “7 / 24”.
    var counter: String { "\(UsernameRules.length(name)) / \(UsernameRules.maxLength)" }

    var fieldStatus: UsernameFieldStatus {
        switch status {
        case .checking: return .checking
        case .available, .meetsRules: return .positive
        case .taken: return .negative
        // A broken rule shows no verdict in the field, only the danger
        // label and the tinted rule.
        default: return .none
        }
    }

    /// Whether `rule` is shown in `danger`: every rule the name breaks,
    /// except “too short” while typing goes on.
    func isBroken(_ rule: UsernameRules.Violation) -> Bool {
        guard violations.contains(rule) else { return false }
        if rule == .length, UsernameRules.isTooShort(name) { return hasPaused }
        return true
    }

    /// 2.3 found the name taken: back on 2.1 it stays in the field, marked
    /// taken, and is not offered again in this setup.
    func markTaken(_ taken: String) {
        takenAtSetup.insert(taken)
        guard taken == name else { return }
        checkTask?.cancel()
        withU2Animation(U2Motion.state) { status = .taken }
    }

    private func textChanged(from old: String) {
        guard text != old else { return }
        let normalized = UsernameRules.normalize(text)
        guard normalized != name else { return }
        name = normalized
        violations = UsernameRules.violations(normalized)
        hasPaused = false
        scheduleCheck()
    }

    private func scheduleCheck() {
        checkTask?.cancel()
        let candidate = name
        guard !candidate.isEmpty else {
            withU2Animation(U2Motion.micro) { status = .empty }
            return
        }
        // A broken rule shows at once, except “too short”: the first letters
        // of a name are not a mistake.
        let shortOnly = violations == [.length] && UsernameRules.isTooShort(candidate)
        if let first = violations.first, !shortOnly {
            if status != .invalid(first) { withU2Animation(U2Motion.state) { status = .invalid(first) } }
        } else if status != .typing {
            withU2Animation(U2Motion.micro) { status = .typing }
        }
        let valid = violations.isEmpty
        checkTask = Task { [weak self, messenger, debounce] in
            try? await Task.sleep(for: debounce)
            guard !Task.isCancelled, let self else { return }
            withU2Animation(U2Motion.state) { self.hasPaused = true }
            guard valid else {
                if shortOnly { withU2Animation(U2Motion.state) { self.status = .tooShort } }
                return
            }
            if self.takenAtSetup.contains(candidate) {
                withU2Animation(U2Motion.state) { self.status = .taken }
                return
            }
            withU2Animation(U2Motion.micro) { self.status = .checking }
            let result = await messenger.usernameAvailability(candidate)
            guard !Task.isCancelled, self.name == candidate else { return }
            withU2Animation(U2Motion.state) {
                switch result {
                case .available: self.status = .available
                case .meetsRules: self.status = .meetsRules
                case .taken: self.status = .taken
                case .invalid(.length) where UsernameRules.isTooShort(candidate): self.status = .tooShort
                case .invalid(let violation): self.status = .invalid(violation)
                case .empty: self.status = .empty
                case .checking: self.status = .checking
                }
            }
        }
    }

    #if DEBUG
        /// Harness: shows `name` in `status` without asking the relay.
        func setForHarness(name: String, status: Status) {
            text = name
            checkTask?.cancel()
            self.name = UsernameRules.normalize(name)
            violations = UsernameRules.violations(self.name)
            hasPaused = true
            self.status = status
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
    /// Why “Kurulumu tamamla” failed (the sentence above the checkbox).
    enum Failure: Equatable, Sendable {
        /// Creating or restoring did not reach the relay: check the
        /// connection and try again.
        case network
        /// After a restore: the relay refuses the backup (its account was
        /// deleted, or it was restored on another phone). Trying again
        /// cannot help; a new account can be created.
        case backupGone
        /// After a restore: the backup can't be read, or it is damaged.
        /// Trying again cannot help; another copy might.
        case backupDamaged
        /// The new account was saved, then a later step failed: it is kept,
        /// locked under the new code. Only the lock goes on from here.
        case accountKept
        /// Restoring inside the lock was refused before anything was erased:
        /// the vault this phone held is untouched. Only the lock goes on.
        case previousVaultKept
        /// The relay refused the name: someone registered it first. Nothing
        /// was kept; the CTA goes back to 2.1 for another name.
        case usernameTaken
    }

    var acknowledged = false
    var faceIDEnabled = true
    /// Restoring inside the lock (3.3 › 7.3): finishing erases the vault on
    /// this phone, with its messages, which the backup does not hold.
    var replacesThisPhone = false
    private(set) var biometry: Biometry = .none
    private(set) var isWorking = false
    private(set) var failure: Failure?

    var failed: Bool { failure != nil }

    /// Creates the account: `nil` when it is done, otherwise why it failed.
    var onSubmit: () async -> Failure? = { .network }
    /// The CTA after a failure that left a vault on this phone.
    var onReturnToLock: () -> Void = {}
    /// The CTA after the name was found taken.
    var onChooseAnotherName: () -> Void = {}

    /// A vault is on this phone, locked: trying again would fail, so the CTA
    /// goes back to the lock and the way back is closed.
    var returnsToLock: Bool { failure == .accountKept || failure == .previousVaultKept }

    /// The name was taken: the CTA goes back to 2.1 to choose another.
    var choosesAnotherName: Bool { failure == .usernameTaken }

    var canFinish: Bool { returnsToLock || choosesAnotherName || (acknowledged && !isWorking) }

    /// Called each time 2.3 is entered: unticked, Face ID on when available.
    func prepare() {
        biometry = Biometry.hardware
        acknowledged = false
        faceIDEnabled = biometry != .none
        failure = nil
        isWorking = false
    }

    /// Back on 2.3 with another name: the failure goes; the acknowledgement
    /// and the Face ID choice stay as they were.
    func resume() {
        failure = nil
        isWorking = false
    }

    func submit() {
        guard canFinish else { return }
        if returnsToLock {
            onReturnToLock()
            return
        }
        if choosesAnotherName {
            onChooseAnotherName()
            return
        }
        withU2Animation(U2Motion.state) {
            isWorking = true
            failure = nil
        }
        Task { [weak self] in
            guard let self else { return }
            if let failure = await self.onSubmit() {
                U2Haptics.error()
                withU2Animation(U2Motion.state) {
                    self.isWorking = false
                    self.failure = failure
                }
            }
        }
    }

    #if DEBUG
        func setForHarness(acknowledged: Bool, failure: Failure? = nil, biometry: Biometry = .faceID) {
            self.biometry = biometry
            self.acknowledged = acknowledged
            faceIDEnabled = biometry != .none
            self.failure = failure
            isWorking = false
        }
    #endif
}
