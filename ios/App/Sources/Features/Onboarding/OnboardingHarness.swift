#if DEBUG
    import Foundation

    /// DEBUG screen harness for 01–02: opens every onboarding screen and state
    /// directly, in its exact design state, for screenshots and comparisons.
    ///
    ///     xcrun simctl launch <sim> com.syconx.us2us -U2Route onboarding:<state> \
    ///         -U2Theme dark|light -U2Language tr|en -U2FrozenClock YES
    ///
    /// | `onboarding:` | design | state |
    /// |---|---|---|
    /// | `1.1` | 1.1 | Karşılama (the reference) |
    /// | `1.2` | 1.2 | Bildirim yok (the reference) |
    /// | `1.3` | 1.3 | Sessizlik siler (the reference) |
    /// | `2.1` | 2.1 | “selin_n” available, focused (the reference) |
    /// | `2.1-blurred` | 2.1 | the same, unfocused (no keyboard over the card) |
    /// | `2.1-rules` | 2.1 | “selin_n” meets the rules (the live service: “Kurallara uygun”) |
    /// | `2.1-empty` | 2.1 | nothing typed, Devam at 45 % |
    /// | `2.1-checking` | 2.1 | “selin_n”, checking (spinner) |
    /// | `2.1-taken` | 2.1 | “selin” taken |
    /// | `2.1-short` | 2.1 | “se” — the length rule in `danger` |
    /// | `2.1-invalid` | 2.1 | “selin.n” — a dot: “Geçersiz karakter” |
    /// | `2.1-turkish` | 2.1 | “kağan” — the Turkish rule in `danger` |
    /// | `2.1-firstLetter` | 2.1 | “1selin” — must start with a letter |
    /// | `2.2` | 2.2 | set step, four digits (the reference) |
    /// | `2.2-empty` | 2.2 | set step, no digits |
    /// | `2.2-confirm` | 2.2 | confirm step, two digits |
    /// | `2.2-weak` | 2.2 | a weak code: red dots + sentence |
    /// | `2.2-mismatch` | 2.2 | confirmation differs: red dots + sentence |
    /// | `2.3` | 2.3 | acknowledged (the reference) |
    /// | `2.3-unchecked` | 2.3 | not acknowledged, CTA at 45 % |
    /// | `2.3-error` | 2.3 | account creation failed |
    /// | `2.3-taken` | 2.3 | the name was taken at setup: “Başka bir ad seç” |
    /// | `7.3` | 7.3 | the restore hand-off from 1.1 |
    /// | `restore-2.2` | 2.2 | new code after a restore (“1 / 2”) |
    /// | `restore-2.3` | 2.3 | finish after a restore (“2 / 2”) |
    /// | `restore-2.3-gone` | 2.3 | the restored backup's account is gone (deleted, or restored on another phone) |
    /// | `restore-2.3-damaged` | 2.3 | the restored backup can't be read or is damaged |
    /// | `restore-2.3-lock` | 2.3 | restoring inside the lock (3.3): this phone's messages are erased when it finishes |
    /// | `restore-2.3-toLock` | 2.3 | restoring inside the lock was refused before anything was erased: “Kilit ekranına dön” |
    /// | `2.3-kept` | 2.3 | the account was saved, then setup failed: “Kilit ekranına dön” |
    /// | `demo` | 1.1 → 4.1 | plays the whole flow by itself (for screen recordings of the motion) |
    /// | `demo-restore` | 1.1 → 7.3 → 2.2 → 2.3 → 4.1 | plays the restore path by itself |
    /// | `demo-back` | 2.3 → 2.2 → 2.1 → 1.3 → 1.1 | plays every way back (pop resets the code, confirm → set, swipe back through the pages) |
    ///
    /// The harness holds the drawn state (no automatic transitions). Only
    /// `2.1` focuses the field (with a hardware keyboard attached the
    /// simulator shows no software keyboard, like the reference). Release
    /// builds compile none of this.
    struct OnboardingHarness: Equatable {
        let state: String

        static let states = [
            "1.1", "1.2", "1.3",
            "2.1", "2.1-blurred", "2.1-rules", "2.1-empty", "2.1-checking", "2.1-taken", "2.1-short", "2.1-invalid",
            "2.1-turkish", "2.1-firstLetter",
            "2.2", "2.2-empty", "2.2-confirm", "2.2-weak", "2.2-mismatch",
            "2.3", "2.3-unchecked", "2.3-error", "2.3-kept", "2.3-taken",
            "7.3", "restore-2.2", "restore-2.3", "restore-2.3-gone", "restore-2.3-damaged", "restore-2.3-lock",
            "restore-2.3-toLock",
            "demo", "demo-restore", "demo-back",
        ]

        static func fromLaunchArguments() -> OnboardingHarness? {
            guard let state = LaunchRoute.current?.state(for: .onboarding), states.contains(state) else { return nil }
            return OnboardingHarness(state: state)
        }

        /// The 2.1 reference is drawn focused (ring, glow, caret); the other
        /// states are shown unfocused so no keyboard covers them.
        var focusesUsernameField: Bool { state == "2.1" }

        @MainActor
        func apply(to flow: OnboardingFlowModel) {
            let demoCode = DesignFixtures.demoSecretCode
            switch state {
            case "1.1": flow.pager.setForHarness(page: 0)
            case "1.2": flow.pager.setForHarness(page: 1)
            case "1.3": flow.pager.setForHarness(page: 2)
            case "2.1", "2.1-blurred", "2.1-rules", "2.1-empty", "2.1-checking", "2.1-taken", "2.1-short", "2.1-invalid",
                "2.1-turkish", "2.1-firstLetter":
                flow.pager.setForHarness(page: 2)
                flow.setForHarness(mode: .create, path: [.username])
                switch state {
                case "2.1-rules": flow.username.setForHarness(name: "selin_n", status: .meetsRules)
                case "2.1-empty": flow.username.setForHarness(name: "", status: .empty)
                case "2.1-checking": flow.username.setForHarness(name: "selin_n", status: .checking)
                case "2.1-taken": flow.username.setForHarness(name: "selin", status: .taken)
                case "2.1-short": flow.username.setForHarness(name: "se", status: .tooShort)
                case "2.1-invalid": flow.username.setForHarness(name: "selin.n", status: .invalid(.characters))
                case "2.1-turkish": flow.username.setForHarness(name: "kağan", status: .invalid(.turkishCharacters))
                case "2.1-firstLetter": flow.username.setForHarness(name: "1selin", status: .invalid(.firstLetter))
                default: flow.username.setForHarness(name: "selin_n", status: .available)
                }
            case "2.2", "2.2-empty", "2.2-confirm", "2.2-weak", "2.2-mismatch":
                flow.pager.setForHarness(page: 2)
                flow.username.setForHarness(name: "selin_n", status: .available)
                flow.setForHarness(mode: .create, path: [.username, .secretCode])
                switch state {
                case "2.2-empty": flow.code.setForHarness(step: .set, entered: 0)
                case "2.2-confirm": flow.code.setForHarness(step: .confirm, entered: 2)
                case "2.2-weak": flow.code.setForHarness(step: .set, entered: 6, problem: .weak)
                case "2.2-mismatch": flow.code.setForHarness(step: .confirm, entered: 6, problem: .mismatch)
                default: flow.code.setForHarness(step: .set, entered: 4)
                }
            case "2.3", "2.3-unchecked", "2.3-error", "2.3-kept", "2.3-taken":
                flow.pager.setForHarness(page: 2)
                flow.username.setForHarness(name: "selin_n", status: .available)
                flow.setForHarness(mode: .create, path: [.username, .secretCode, .noWayBack], confirmedCode: demoCode)
                flow.code.setForHarness(step: .confirm, entered: 6)
                var failure: NoWayBackStepModel.Failure?
                if state == "2.3-error" { failure = .network }
                if state == "2.3-kept" { failure = .accountKept }
                if state == "2.3-taken" {
                    failure = .usernameTaken
                    flow.username.markTaken("selin_n")
                }
                flow.finish.setForHarness(acknowledged: state != "2.3-unchecked", failure: failure)
            case "demo-back":
                flow.pager.setForHarness(page: 2)
                flow.username.setForHarness(name: "selin_n", status: .available)
                flow.setForHarness(mode: .create, path: [.username, .secretCode, .noWayBack], confirmedCode: demoCode)
                flow.code.setForHarness(step: .confirm, entered: 6)
                flow.code.isFrozenForHarness = false
                flow.finish.setForHarness(acknowledged: true)
            case "7.3":
                flow.setForHarness(mode: .create, path: [.restore])
            case "restore-2.2":
                flow.setForHarness(mode: .restore, path: [.restore, .secretCode])
                flow.code.setForHarness(step: .set, entered: 0)
            case "restore-2.3", "restore-2.3-gone", "restore-2.3-damaged", "restore-2.3-lock", "restore-2.3-toLock":
                flow.setForHarness(mode: .restore, path: [.restore, .secretCode, .noWayBack], confirmedCode: demoCode)
                flow.code.setForHarness(step: .confirm, entered: 6)
                // Restoring inside the lock (3.3 › 7.3): the warning that this phone's messages go.
                flow.finish.replacesThisPhone = state == "restore-2.3-lock" || state == "restore-2.3-toLock"
                switch state {
                case "restore-2.3-gone": flow.finish.setForHarness(acknowledged: true, failure: .backupGone)
                case "restore-2.3-damaged": flow.finish.setForHarness(acknowledged: true, failure: .backupDamaged)
                case "restore-2.3-toLock": flow.finish.setForHarness(acknowledged: true, failure: .previousVaultKept)
                default: flow.finish.setForHarness(acknowledged: false)
                }
            default:
                break
            }
        }

        var isDemo: Bool { state.hasPrefix("demo") }

        /// Plays the flow through the view models with human timing: page
        /// changes (one by a simulated swipe), typing with the availability
        /// check (a taken name first), a weak code, a mismatch, the right code
        /// twice, the acknowledgement and “Kurulumu tamamla”.
        @MainActor
        func runDemo(_ flow: OnboardingFlowModel) async {
            func wait(_ seconds: Double) async { try? await Task.sleep(for: .milliseconds(Int(seconds * 1000))) }
            func type(_ code: String) async {
                for digit in code {
                    flow.code.type(Int(String(digit)) ?? 0)
                    await wait(0.22)
                }
            }
            await wait(2.0)
            if state == "demo-back" {
                flow.router.pop()  // 2.3 → 2.2: the code starts over
                await wait(1.4)
                await type("246813")  // → confirm
                await wait(1.2)
                flow.codeBack()  // confirm → set
                await wait(1.2)
                flow.codeBack()  // 2.2 → 2.1
                await wait(1.2)
                flow.router.pop()  // 2.1 → 1.3
                await wait(1.4)
                // 1.3 → 1.2 by a swipe to the right, then 1.2 → 1.1 by a quick flick.
                for step in 1...12 {
                    flow.pager.dragProgress = -0.4 * Double(step) / 12
                    await wait(0.025)
                }
                flow.pager.endDrag(predictedProgress: -0.6)
                await wait(1.4)
                for step in 1...4 {
                    flow.pager.dragProgress = -0.1 * Double(step) / 4
                    await wait(0.025)
                }
                flow.pager.endDrag(predictedProgress: -0.9)
                await wait(1.4)
                // A short drag that does not commit settles back.
                for step in 1...8 {
                    flow.pager.dragProgress = -0.15 * Double(step) / 8
                    await wait(0.025)
                }
                flow.pager.endDrag(predictedProgress: -0.2)
                return
            }
            if state == "demo-restore" {
                flow.startRestore()
                await wait(1.6)
                flow.restoreFinished()
            } else {
                flow.pager.advance()  // 1.1 → 1.2 by the button
                await wait(2.0)
                // 1.2 → 1.3 by a swipe: the finger drags 45 % across, then lifts.
                for step in 1...12 {
                    flow.pager.dragProgress = 0.45 * Double(step) / 12
                    await wait(0.025)
                }
                flow.pager.endDrag(predictedProgress: 0.7)
                await wait(2.0)
                flow.startSetup()
                await wait(1.4)
                for character in "selin" {
                    flow.username.text += String(character)
                    await wait(0.16)
                }
                await wait(1.6)  // “Alınmış”
                for character in "_n" {
                    flow.username.text += String(character)
                    await wait(0.16)
                }
                await wait(1.4)  // “Kullanılabilir”
                flow.usernameContinue()
                await wait(1.2)
            }
            await type("111111")  // weak
            await wait(1.6)
            await type("246813")  // → confirm
            await wait(0.8)
            await type("246814")  // mismatch → set
            await wait(1.8)
            await type("246813")
            await wait(0.8)
            await type("246813")  // → 2.3
            await wait(1.6)
            flow.finish.acknowledged = true
            await wait(1.0)
            flow.finish.submit()
        }
    }
#endif
