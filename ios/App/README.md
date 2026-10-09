# us2us — iOS app

The SwiftUI app on top of `ios/Us2usKit`. It must look **exactly** like the
us2us iOS design (the design file “us2us iOS”, Nocturne design system),
specified in [`ios/design/`](../design/README.md), and move **very softly**.

Every screen of the design (01 Onboarding … 07 Settings) is built, on the
design system, the `MessengerService` protocol and the design's own data
(`DesignFixturesService`). The app runs on the live Us2usKit / Us2usAppCore
service; the fixtures stay for previews, screenshots and UI tests.

> **Türkçe özet** en sonda.

```
ios/App/
  project.yml                    XcodeGen spec → us2us.xcodeproj (commit both)
  us2us.xcodeproj                generated; Sources/Resources/Tests are synchronized folders
  scripts/
    generate.sh                  regenerate the project (--tokens: also tokens + icons)
    generate-tokens.py           ios/design/tokens.json → DesignSystem/Tokens/*+Generated.swift
    generate-icons.py            ios/design/icons → Assets.xcassets/Phosphor + PhosphorGlyph+Generated.swift
    render-app-icon.swift        the 1024 pt app icon from the design's icon mock (3.4)
  Config/Info.plist              generated from project.yml (usage strings localized in InfoPlist.xcstrings)
  Sources/
    App/                         Us2usApp, AppModel, RootView, launch configuration (LaunchRoute),
      Navigation/                SoftNavigationStack + AppRoute / OnboardingRoute / SettingsPage
      Security/                  lock / privacy-cover window, snapshot blur
    DesignSystem/
      Tokens/                    colours (generated + fixed + gradients), metrics, shadows, backdrops
      Typography/                fonts, type styles (+ .balanced / .proportional), U2DisplayText,
                                 CSSTextLayout (SetupTitle, GreedyParagraph, CSSLineClampText, CSSBlockWidth)
      Motion/                    soft animation tokens and transitions (u2Push, u2Rise, u2Collapse …)
      Icons/                     PhosphorIcon (57 glyph/weight assets), Face ID glyph
      Components/                the shared components (see below)
      Environment/               U2Clock + haptics, Biometry, SystemBridges (keyboard, share
                                 sheet, presentation style, screen-capture probe)
      Formatting/, Localization/ formats; L10n, AppLanguage, StringTable (per-feature tables),
                                 TurkishSuffix
      Gallery/                   ComponentGallery (DEBUG, -U2Gallery YES)
    Domain/                      UI models, MessengerService (the whole data surface), QR payloads
                                 (ContactCode, SafetyQRCodec), UsernameRules
    Services/                    DesignFixtures (the design's data) + DesignFixturesService
    Features/
      Onboarding/                1.1–1.3 pager, 2.1–2.3 setup, 7.3 hand-off      Onboarding.xcstrings
      Lock/                      3.1 Face ID, 3.2 code, 3.3 forgot, 3.4 cover    Lock.xcstrings
      Chats/                     4.1 list, 4.2 new chat, 4.3 requests, 4.4 preview  Chats.xcstrings
      Conversation/              5.1–5.6                                          Conversation.xcstrings
      Verification/              6.1–6.3, add a contact by QR                     Verification.xcstrings
      Settings/                  7.1 + pickers, 7.2 export, 7.3 restore, code change, about  Settings.xcstrings
      Common/                    screens shared by features: “Kodumu göster” (MyCodeSheet),
                                 “Her şey silinsin mi?” (EraseConfirmation)
      Invite/                    “Davet et” (proposed): invite by SMS / WhatsApp / e-mail / share
                                 sheet / link, universal links → 4.2 (docs/INVITES.md)  Invite.xcstrings
      */…Harness.swift           DEBUG: every state of the feature by -U2Route
    Scene3D/                     the welcome screen's Metal scene (mounted only by Features/Onboarding/WelcomeSceneHook)
  Resources/
    Assets.xcassets              AppIcon, AccentColor, LaunchBackground, Phosphor/
    Fonts/                       Doto, Martian Mono, Inter — variable TTFs from google/fonts
    Licenses/                    OFL ×3, Phosphor MIT (libsignal's are copied in at build time)
    Localizable.xcstrings        shared strings, tr (source) + en
    InfoPlist.xcstrings          permission strings, tr + en
    PrivacyInfo.xcprivacy        no tracking; User ID only; UserDefaults, file timestamps
  Tests/UnitTests                tokens, fonts, component metrics vs. the design, formats, l10n, routes,
                                 fixtures (every MessengerService operation), feature view models
  Tests/UITests                  AppFlow, Lock, Settings, FlowWalk (every flow of flows.md, with screenshots),
                                 FluidityPerformance (scroll 500 chats, push/pop, pages, sheet, unlock)
```

## Build, run, test

Requirements: Xcode 27, XcodeGen ≥ 2.45, and libsignal vendored once:

```sh
ios/scripts/fetch-libsignal.sh       # → ios/Vendor (git-ignored); see ios/README.md
ios/App/scripts/generate.sh          # only after editing project.yml
```

Open `ios/App/us2us.xcodeproj` (scheme **us2us**) or use the command line. A
dedicated derived-data folder and simulator keep command-line runs apart from
Xcode's own:

```sh
cd ios/App
xcrun simctl create us2us-dev "iPhone 17 Pro" com.apple.CoreSimulator.SimRuntime.iOS-27-0
xcodebuild -project us2us.xcodeproj -scheme us2us \
  -destination 'platform=iOS Simulator,name=us2us-dev' -derivedDataPath /tmp/us2us-dd build
xcodebuild test -project us2us.xcodeproj -scheme us2us \
  -destination 'platform=iOS Simulator,name=us2us-dev' -derivedDataPath /tmp/us2us-dd
```

Device builds and archives are signed automatically for team `C8QW8MBYQX`
(SyconX LLC, `DEVELOPMENT_TEAM` + `CODE_SIGN_STYLE: Automatic` in
project.yml); simulator builds need no signing. Releasing: see
[Releasing (TestFlight / App Store)](#releasing-testflight--app-store).

The app runs on the **live service** (`Services/Live/RealMessengerService`
over Us2usAppCore's `AppRuntime`: vault, SQLCipher storage, crypto-shredding,
the relay lifecycle) against `https://relay.us2us.app`. DEBUG builds accept
`-U2RelayURL <url>` (https, or http to loopback only) for a local relay, and
`-U2Fixtures YES` for **DesignFixturesService** — the design's own data, used
by previews, screenshots and every UI test. In fixtures mode the lock's demo
code is `246813` (shown faintly in DEBUG builds); the fixtures' backups open
with `kuzey7deniz42k`.

Always pass `-collect-test-diagnostics never` to `xcodebuild test`: when a
test fails, xcodebuild otherwise collects simulator diagnostics and waits
600 s for them.

**Live end-to-end run** (`LiveEndToEndUITests`, skipped unless configured):
the real app against a local relay and the Kotlin echo bot, started exactly
like `scripts/interop.sh` step 1 and 3 (`us2us-bot --username e2ebot …`).
`test1…` signs up, finds the bot, sends a Turkish request, a second text
(“İletildi”), a library photo, the current location (simulated by the test),
turns Ghost on, locks and unlocks; `test2…` exports a backup to Files, erases
everything and restores it (the bot is back in 4.1, without messages). Run on
a freshly installed app (uninstall + `xcrun simctl keychain <sim> reset`), test1
before test2, with the run's values as `TEST_RUNNER_` variables:

```sh
TEST_RUNNER_U2E2E_RELAY=http://127.0.0.1:<port> TEST_RUNNER_U2E2E_BOT=e2ebot \
TEST_RUNNER_U2E2E_USER=<new name> TEST_RUNNER_U2E2E_CODE=<6 digits> \
TEST_RUNNER_U2E2E_TEXT="Merhaba! Bugün hava çok güzel" \
xcodebuild test-without-building … -collect-test-diagnostics never \
  -only-testing:us2usUITests/LiveEndToEndUITests
```

The echo bot sends delivery receipts only (never “Okundu”), and none for the
request itself (PROTOCOL §8.4), so the first bubble stays “Gönderildi”.

Tests: `us2usTests` (XCTest + Swift Testing) and `us2usUITests`.
`FlowWalkUITests` walks every flow of `ios/design/flows.md` with real taps and
attaches a screenshot per step (`xcrun xcresulttool export attachments`). UI
tests take a while each — run them one class at a time with
`-only-testing:us2usUITests/<Class>`. Run UI and performance tests on a
simulator with **Reduce Motion off** (Settings › Accessibility › Motion):
with it on, the app takes its Reduce Motion paths (cross-fades, no packets or
rings) and motion tests measure something else.

Performance: `ConversationPerformanceUITests` and `FluidityPerformanceUITests`
(in `us2usUITests`, Debug, with the DEBUG stress data `-U2StressMessages` /
`-U2StressChats`), and the **us2usPerformance** scheme
(`Tests/PerformanceTests`, included from `project.yml`), which runs against
a Release build — release builds carry no stress-data hook, so it measures
the design data.

### Launch arguments (screens in an exact state)

One argument opens any screen in any drawn state: **`-U2Route
<feature>[:<target>][/<state>]`** (parsed by `LaunchRoute`; the states are
read by DEBUG builds only). Without `-U2Start` the route picks the start
(`onboarding` → welcome, `lock` → locked, everything else → chats).

| argument | values | |
|---|---|---|
| `-U2Route` | see the table below | opens a screen / state |
| `-U2Start` | `welcome` · `locked` · `chats` | first screen (overrides the route's) |
| `-U2Theme` / `-U2Language` | `system` `dark` `light` / `system` `tr` `en` | override the saved preference |
| `-U2FrozenClock YES` | | time frozen at the design's moment (8 Oct 2026 18:52): counters show 23:41:07, rings and packets pause like the references, and the welcome scene is pinned to the frame the 1.1 references captured (dark t = 4.5 s, light t = 39.95 s) |
| `-U2GhostUrgent YES` | | the “Ghost — last hour” variant |
| `-U2Gallery YES` | | every component on one page (DEBUG) |
| `-U2ResetPreferences YES` | | forgets the saved theme / language |
| `-U2PickBackup YES` | | DEBUG: 7.3 “picks” a stand-in `.u2u` instead of showing the system document picker (UI tests) |
| `-U2ChatsDemo <action>` · `-U2ConversationDemo <action>` | | DEBUG: one interaction plays by itself ~1.5 s after the screen appears (for recording transitions); see `ChatsHarness` / `ConversationHarness` (`push-pop`, `sheet-cycle`: five rounds each) |
| `-U2StressChats <n>` · `-U2StressTicks YES` · `-U2StressAutoScroll <cycles>` | | DEBUG: 4.1 with `n` chats (the design's first) · the list keeps changing (rows move up, counters and statuses tick) · flings itself (3 down, 3 up per cycle) and prints the frame probe |
| `-U2FrameProbe YES` · `-U2FrameProbeLog YES` | | DEBUG: counts main-thread frame drops (read by `FluidityPerformanceUITests`) · prints them after every transition (`xcrun simctl launch --console-pty …`) |
| `-U2LockDemo forgot` · `unlock` | | DEBUG: the lock opens 3.3 by itself · types the demo code |
| `-U2Fixtures YES` | | DEBUG: the design's data instead of the live service (UI tests, screenshots; required by every route state) |
| `-U2RelayURL <url>` | | DEBUG: a local relay instead of `https://relay.us2us.app` |

| `-U2Route` | states |
|---|---|
| `onboarding:<state>` | `1.1` `1.2` `1.3` · `2.1` `2.1-blurred` `2.1-empty` `2.1-checking` `2.1-taken` `2.1-short` `2.1-invalid` · `2.2` `2.2-empty` `2.2-confirm` `2.2-weak` `2.2-mismatch` · `2.3` `2.3-unchecked` `2.3-error` · `7.3` `restore-2.2` `restore-2.3` · `demo` `demo-restore` `demo-back` (the flow plays itself) |
| `lock:<state>` | `faceid` `touchid` · `code` `typing` `wrong` `lastchance` `throttled` · `forgot` `erase` `restore` · `cover` `switcher` |
| `chats` · `chats:<state>` | `4.1` `4.1-connecting` `4.1-closed` `4.1-empty` `4.1-qr` `4.1-mycode` · `4.2` `4.2-keyboard` `4.2-empty` `4.2-searching` `4.2-notfound` `4.2-existing` `4.2-self` `4.2-mycode` · `4.3` `4.3-empty` `4.3-block` · `4.4` `4.4-media` `4.4-link` |
| `newChat` · `newChat:<username>` | the 4.2 sheet (with that query) |
| `requests` · `request:<id>` | 4.3 · 4.4 over 4.3 (`kuzey.dev` `n.aksoy` `hediye_kazan`) |
| `conversation:<chat>[/<state>]` | `deniz/…` `5.1` `5.1-failed` `5.1-reply` `5.1-attach` `5.1-viewer` `5.3` `5.3-incoming` `5.3-confirm` `5.4` `5.4-pending` `5.4-failed` `5.5` `5.6` `5.6-clean` `5.6-captured` · `mert.k/…` `5.2` `5.2-ended` `5.2-opened` `5.5` (Ghost off) |
| `safety:<chat>[/<state>]` | `safety` `safety-verified` `changed` `confirm` `confirm-remove` · `scan` `mismatch` `mycode` `denied` `torch` · `verified` `verified-manual` · add by QR: `contact-scan` `contact-unknown` `contact-own` `contact-mycode` `contact-verified` · scripted: `flow-match` `flow-mismatch` `flow-manual` `flow-remove` `flow-contact` |
| `settings` · `settings:<state>` | `7.1` `7.1-end` `qr` `erase` · pickers `presence` `autolock` `ghost` `disappearing` `theme` `language` · `7.2` `7.2-empty` `7.2-weak` `7.2-confirm` `7.2-mismatch` `7.2-exporting` · `7.3` `7.3-pick` `7.3-password` `7.3-wrong` `7.3-done` `7.3-cancel` · `code` `code-wrong` `code-new` `code-weak` `code-confirm` `code-mismatch` · `about` |
| `backup` | 7.2 over 7.1 |
| `invite` · `invite:<state>` | the invite sheet over 4.1: `edit` `edited` `copied` `sent` · `link` `link-unknown` (an invite link for deniz / deniz.yilmaz arrives → 4.2 searched); `-U2OpenURL <url>` hands any URL to the link inbox (DEBUG) |

Examples: `-U2Route conversation:deniz/5.3`, `-U2Route safety:deniz/scan
-U2Theme light`, `-U2Route lock:forgot -U2Language en`, `-U2Route settings:7.3-wrong`.

### Comparing with the design

The references in `ios/design/reference/` are 1206 × 2622 px = an iPhone 17 Pro
screenshot. Freeze the status bar and the clock, launch the screen and diff:

```sh
xcrun simctl status_bar us2us-dev override --time 9:41 --batteryState charged --batteryLevel 100
xcrun simctl launch us2us-dev com.syconx.us2us -U2Route conversation:deniz \
  -U2Theme dark -U2Language tr -U2FrozenClock YES
xcrun simctl io us2us-dev screenshot /tmp/5.1.png      # compare with reference/5.1-sohbet.png
```

Every screen was checked this way against its reference (dark, light and,
where drawn, English) and is within about 1 pt; `ComponentMetricsTests` pins
every shared component to the design's measured sizes.

## Design system

* **Tokens** are mirrored 1:1 from `tokens.json` by `scripts/generate-tokens.py`
  (`Color.u2.<token>`, `U2TextStyle.<style>`); never edit the generated files.
  Spacing, radii, sizes, opacities, shadows and backdrops are in
  `DesignSystem/Tokens`. Dark is the design's default; every token has its light
  value (`U2ThemedColor`), resolved through the trait collection, so the in-app
  theme is applied to every window as `overrideUserInterfaceStyle`.
* **Fonts** are the variable TTFs Google Fonts serves, with every axis pinned:
  Doto **ROND 100** (round dots) wght 800/900; Martian Mono **wdth 100** (the
  instance the design was rendered with — the font's default 112.5 is 7 % wider);
  Inter **opsz 14** at every size (no automatic optical sizing). Italic is the
  same synthetic oblique the browser drew (the design loads no italic face).
  `DesignTokenTests` checks the design's measured widths (“1 / 3” = 38.5 pt …).
* **`.u2Font(style)`** applies a style with an exact CSS line box: a block is
  `lines × lineHeight` high with the glyphs centred (half-leading), instead of
  SwiftUI's natural, pixel-rounded height. Dynamic Type scales the Inter/Mono
  styles from the design size (exactly 1 at the default size); display (Doto)
  styles stay fixed. **`U2DisplayText`** sets multi-line Doto headings (line
  heights below the font's own, the Inter 700 “.”, CSS greedy wrapping) with
  real CSS line boxes (`CSSLineMetrics`, TextKit 1): font metrics rounded like
  Blink, and a line holding an Inter “.” grows like a CSS inline box — “İki uç.
  Bir hat.” is two 47 pt lines (94 pt), exactly the design's.
* **Screens** start with **`U2Screen`**: edge-to-edge `bg`, and the device's
  safe area published so `u2Top(56)` / `u2Bottom(44)` place things at design
  coordinates (the design assumes a 62 pt status bar, 34 pt home indicator).
* **Motion** (`U2Motion`) — owner requirement: *very soft*. Everything uses the
  design's curve `cubic-bezier(.45,0,.55,1)`, its ease-out, or critically damped
  springs (damping 1.0, no bounce). Pushes cross-fade with a 16 pt drift (0.5 s),
  sheets rise on the soft spring over a fading backdrop, presses scale to 0.97,
  new content fades in rising 8 pt with a slight blur. Under Reduce Motion
  movement becomes a short cross-fade, ambient motion stops, countdowns keep
  ticking. Use `withU2Animation`, `.u2Animation`, `.u2Rise/.u2Fade/.u2Pop/.u2Push`,
  `.u2Collapse()` (fade + height collapse: 4.3 card removal, 5.3 “Benden sil”),
  `.u2MenuPop`, `Animation.u2Keyboard` (content that follows the keyboard).
* **Strings**: shared texts in `Resources/Localizable.xcstrings`; each feature
  has its own table next to its code (`StringTable.chats` …):
  `Text("key", table: .chats)` in the environment's language,
  `StringTable.lock.string(_:locale:)` / `LocalizedText` resolved explicitly
  (plural variants, announcements). Every key exists in Turkish and English;
  Turkish keys that English pluralises carry the same plural variations (a
  Turkish plain string next to an English plural makes SwiftUI fall back to
  English).
* **Icons**: exactly the 57 Phosphor 2.1.1 glyph/weight pairs of `icons.md`, as
  template vectors (`PhosphorIcon(.ghost, weight: .fill, size: 12)`), plus the
  design's own Face ID glyph with its size-dependent stroke.

### Components (inventory ids)

| component | design |
|---|---|
| `U2PrimaryButtonStyle` (54 / 48 / 50, glow), `U2DangerButtonStyle` (C1 in `danger`), `U2SecondaryButtonStyle`, `U2TextButtonStyle`, `U2GlassIconButton` (44/40/36, media, camera), `U2DecisionButtonStyle`, `U2InlinePillButtonStyle`, `U2CameraPillButtonStyle`, `U2ButtonLabel` (glyph or spinner), `U2FlexRow` (CSS `flex` with fixed borders: 97.58 / 97.58 / 128.84) | C1–C4, 4.3/4.4 bars, 5.2, 6.2 |
| `OnboardingHeader`, `SetupHeader`, `CenteredTitleHeader`, `ChatHeader` (translucent, dotted rule), `SheetBar`, `SheetCloseHeader`, `SectionLabel`, `MonoKicker`, `U2Wordmark` | C5–C8, C13 |
| `PageDots`, `CodeDots` (fill, error shake), `NumericKeypad` (setup / Face ID) | C9–C11 |
| `Avatar` (7 sizes, palettes A–D), `PresenceDot`, `CountBadge` | C12 |
| `GroupedSection/Card/Row/NavigationRow/ToggleRow/Separator`, `IconTile`, `DisclosureDetail` | C13 |
| `U2ToggleStyle`, `U2CheckboxStyle`, `SegmentedChips` | C14, 2.3, C22 |
| `MessageBubble` + `BubbleShape` (tails), `TextBubble`, `ReplyBubble`/`ReplyQuote`, `PhotoBubble`, `VideoBubble`, `VoiceBubble`/`Waveform`, `FileBubble`, `LocationBubble`, `LiveLocationBubble` / `EndedLiveLocationBubble`, `DottedMap`, `TombstoneBubble`, `ViewOnceBubble`/`ViewOnceBadge`, `TextStickerMessage`, `ReactionChip`, `BubbleMeta`, `MessageBubbleView` (any message, with its taps: media, view once, “Durdur”), `SystemEventPill` | C15, 5.1, 5.2 |
| `MessageStatusIcon`, `MessageStatusSentence`, `MessageStatusLegendRow` | C16, 1.2, 5.1 |
| `GhostPill`, `GhostChip`, `GhostCountdown`, `GhostUrgentBanner` | C17, 1.3, 5.1, 5.5 |
| `.u2Sheet` (full height / content / floating), `SheetGrabber`, `.u2Toast`, `.u2FadeCover` (full-screen cover that fades, 6.2 / 5.6), `DestructiveConfirmation` | C18, 3.3, 7.1, 7.3 |
| `FloatingMenu` / `.u2Menu` (5.3 actions, “+”, the 4.1 QR and 4.3 / 4.4 “…” menus) | 5.3 |
| `QRModules`, `QRCodeCard` (every QR: 6.1, “Kodumu göster”, 7.1) | 6.1 |
| `DottedRule`, `DottedMeter`, `DottedHourMeter` | C19 |
| `LineMark`, `LineBar` + `LinePackets` (the design's keyframes), `ConnectionLine` (bar 1.5 pt under the avatars' centre, as drawn), `ConnectionStatusLine`, `VerifiedSeal` | C20, 4.1, 6.1, 6.3 |
| `Composer` | C21 |
| `UsernameField` (2.1), `UsernameSearchField` (4.2: no content type, nothing offered by the keyboard), `PasswordField` (7.2 / 7.3: dots, reveal, accepted / checking), `U2FieldChrome`, `SpinningGlyph`, `BlinkingCaret` | C23, 2.1, 4.2, 7.2, 7.3 |
| `DotGridBackdrop` | C24 |
| `U2Card`, `OptionCard`, `NoteCard`, `InfoBanner`, `FloatingBanner`, `SystemPill`, `FlagTag`, `InfoPill`, `PulseRings`, `ChatListRow`, `RequestsCard`, `DesignArtworkView` | 3.3, 4.1, 4.3, 5.4, 5.6, 6.3 … |

Every component has a SwiftUI preview with the design's data. Screens that
more than one feature shows live in `Features/Common` (`MyCodeSheet`,
`EraseConfirmation`).

## Architecture

* **`AppModel`** (`@Observable`, main actor): root phase `onboarding` /
  `locked` / `unlocked`, the security overlay, preferences, the clock and the
  main router. `SceneDelegate` forwards the UIKit scene lifecycle (the earliest
  callbacks — the privacy cover must be up before the app-switcher snapshot).
* **Lock & privacy (03)**: a separate window above everything
  (`SecurityOverlayController`, `.alert + 1`) shows the 3.4 cover instantly on
  resign-active (the current screen, blurred) and the lock on **every launch and
  every return from the background**; the main stack stays alive underneath.
  Wrong-code delays are PROTOCOL §5.2 (`UnlockThrottle.standard`: 2 free, then
  1 min, 5, 15, 30, 60).
* **Navigation**: `SoftNavigationStack` + `U2Router<AppRoute>` instead of the
  system `NavigationStack` (its push cannot be made soft): cross-fade + drift,
  interactive edge-swipe back on a critically damped spring, every screen kept
  alive, VoiceOver escape pops. Covered screens get `\.u2IsLayerVisible ==
  false`; anything with continuous work pauses on it (the welcome scene renders
  nothing while 2.1–2.3 cover it, the 4.1 packets hold under 5.1). A push
  builds the incoming screen for one frame, invisible, before it moves (like
  UIKit), so the cross-fade gets every frame; `\.u2LayerMotion` tells views
  whether their stack or pager is moving (the welcome scene and the 2.1
  keyboard wait for it). Gestures hand their speed to the settle
  (`U2Motion.spring(initialVelocity:)`: edge swipe, sheets, welcome pages).
  Sheets are `u2Sheet` (full-screen presentations with the design's opaque
  backdrop and modal accessibility).
* **Data**: screens and feature view models use only the **`MessengerService`**
  protocol (`Domain/MessengerService.swift`) — account, lock, chats and
  requests, every conversation operation (reply, attachments, reactions,
  resend, live location, view once), verification (scanned codes, adding by
  QR, removing a verification), settings, code change, backup export and
  restore. `DesignFixturesService` serves exactly the design's data and real
  rules (Us2usKit `SecretCode`, `UnlockThrottle`); the live implementation over
  Us2usKit/Us2usAppCore replaces it next. QR payloads are `ContactCode`
  (`us2us:@<username>#<key fingerprint>`) and `SafetyQRCodec` (`U2SN:` +
  base32 of the scannable fingerprint).
* **Restore**: 1.1 › “Yedekten geri yükle” and 3.3 › “Yedekten geri yükle”
  both open 7.3 (`RestoreBackupView`); when it is done onboarding continues at
  2.2 “1 / 2” for a new code on this device (`AppModel.continueAfterRestore`
  from the lock) → 2.3 → 4.1.
* **Localization**: Turkish is the catalog's source language, English the
  second. The in-app language (7.1 › Dil) is applied as `\.locale` (SwiftUI
  looks strings up in that language) and passed explicitly to formatters and
  `L10n.string`; devices in other languages get English. Upper-casing always
  uses the locale (“GİZLİLİK”).
* **Theme**: System / Dark / Light (7.1 › Tema), device preference in
  UserDefaults (readable before unlocking), applied to all windows.

## Info.plist & privacy

* Usage descriptions (tr + en, `Resources/InfoPlist.xcstrings`) only for what
  the app really uses: `NSFaceIDUsageDescription` (unlock),
  `NSCameraUsageDescription` (QR scanning only),
  `NSLocationWhenInUseUsageDescription` (“+” › Konum and live location). No
  microphone string: voice messages are not recorded yet (add
  `NSMicrophoneUsageDescription` back together with the recorder). **No
  “Always” location**; `UIBackgroundModes: location` exists only so a live
  location the user started keeps updating in the background, with the
  system's location indicator (`LocationProvider`). **No photo-library
  permission**: sending uses the out-of-process photo picker, which needs none.
* `ITSAppUsesNonExemptEncryption = YES` — the app implements end-to-end
  encryption with standard, published algorithms (libsignal's Signal
  protocol; SQLCipher's AES-256 at rest). **The owner answers the export-compliance questions in
  App Store Connect** (mass-market / standard encryption) for the first build;
  this repository does not decide the legal classification. If App Store
  Connect issues an `ITSEncryptionExportComplianceCode`, add it to the
  `info` properties in project.yml.
* `PrivacyInfo.xcprivacy`: no tracking, no tracking domains. Collected data:
  **User ID** (the random relay account id: envelope recipient, media
  uploader, directory lookups — PROTOCOL.md §9), linked, App Functionality
  only. Message content, media, contacts, profiles, presence and location are
  end-to-end encrypted and unreadable to the relay. Required-reason APIs, from
  the symbols of the Release binary: UserDefaults `CA92.1` (theme / language),
  file timestamps `C617.1` (own files) and `3B52.1` (the backup file the user
  picks). SQLCipher.framework and SwiftProtobuf ship their own manifests.
* Public API only (App Review): the translucent header and menus are a plain
  `UIVisualEffectView` / `UIBlurEffect` material under the design's tint; the
  header's tint colour is corrected for the material's lift so it matches the
  design at rest (`DesignSystem/Tokens/U2Backdrop.swift`). No KVC on system
  views, no private filters or selectors, no swizzling.
* Release builds read **no launch argument** (`AppConfiguration`,
  `LaunchRoute.current`): live service, `https://relay.us2us.app`, wall clock
  and saved preferences only. Fixtures, harness states, stress data and the
  frame probe are DEBUG-only.
* `.u2u` backups are a declared document type (`com.syconx.us2us.backup`).
* Licences shipped in the bundle: SIL OFL for Doto, Martian Mono, Inter; MIT for
  Phosphor; libsignal's licence and acknowledgements (copied from `ios/Vendor` by
  a build phase).

## Releasing (TestFlight / App Store)

Bundle id `com.syconx.us2us`, team `C8QW8MBYQX` (SyconX LLC), automatic
signing, iPhone only, portrait only, iOS 17+.

**Versions** (project.yml › `settings.base`, then `scripts/generate.sh`):
`MARKETING_VERSION` is the store version (`1.0.0`, then `1.0.1`, `1.1.0` …);
`CURRENT_PROJECT_VERSION` is the build number and must be higher for every
upload of the same version (`1`, `2`, `3` …). For a one-off build number
without editing the spec: `scripts/archive.sh --build-number 2`.

**Archive and export** (no upload):

```sh
ios/App/scripts/archive.sh                 # → ios/App/build/release/us2us.xcarchive + export/us2us.ipa
```

It runs `xcodebuild archive` (Release, generic iOS device,
`-allowProvisioningUpdates`) and `xcodebuild -exportArchive` with
`ExportOptions.plist` (`app-store-connect`, `destination export`). Automatic
signing needs the team's Apple ID in **Xcode › Settings › Accounts**; Xcode
then creates the App Store profile itself. Upload `us2us.ipa` with Xcode's
Organizer or Transporter.

Before the first upload, in App Store Connect: create the app record (iOS,
`com.syconx.us2us`, primary language Turkish), fill **App Privacy** exactly as
`PrivacyInfo.xcprivacy` says (Identifiers › User ID, App Functionality, linked,
no tracking; nothing else collected), answer the export-compliance questions,
and explain the background location mode in the review notes (user-started
live location sharing only, location indicator visible).

## Open points

1. **Usernames**: the design allows dots and 3–20 characters (“mert.k”,
   “selin.n”); PROTOCOL §3.1 / `NormalizedUsername` allow
   `^[a-z][a-z0-9_]{2,23}$` (no dot, up to 24, letter first). The app's
   `UsernameRules` follow the design; the protocol and kit need to follow (the
   design wins), or the design changes.
2. **Request count**: 4.1 says “2 kişi seninle yazışmak istiyor”, 4.3 shows three
   requests. The fixtures keep the three requests, so the card says 3.
3. **Otomatik kilit**: the row opens a picker with the single option “Hemen” and
   an explanation (the app always locks immediately).
4. The wrong-code copy (“2 yanlış daha …” after the first) and the owner's
   “delays after two wrong codes” agree with PROTOCOL §5.2 (2 free, 1 min after
   the 3rd). The design proposed 1 → 5 → 15 → 60 min; the kit has 1 → 5 → 15 →
   30 → 60.
5. **English code title**: “Enter your secret code” wraps to two lines (it needs
   396 pt of the 354 available); “Enter secret code” would fit on one.
6. **Number formats in English** keep the device region (“12,4 MB”, “3.920” on a
   Turkish-region device; the design's English writes “12.4 MB”, “3,920”).
   Formatting by the UI language would also turn times to “6:52 PM”.
7. **2.1 with the software keyboard**: the keyboard covers “Devam” (the layout
   is the design's); the keyboard's Continue key does the same.

## Türkçe özet

iOS uygulamasının tamamı burada: XcodeGen projesi, tasarım sistemi
(tasarımın `tokens.json`'undan birebir üretilen renkler ve yazı stilleri,
Doto ROND 100 / Martian Mono wdth 100 / Inter opsz 14 eksenleriyle gömülü
fontlar, 57 Phosphor ikonu, çok yumuşak — sekmeyen — animasyonlar), ortak
bileşenler, `AppModel` + kilit/gizlilik penceresi, yumuşak geçişli gezinme ve
01–07 arasındaki bütün ekranlar. Ekranlar yalnızca `MessengerService`
protokolüyle konuşur; uygulama canlı servisle (Us2usAppCore `AppRuntime`,
`https://relay.us2us.app`) çalışır, tasarımdaki verileri birebir veren
`DesignFixturesService` önizlemeler, ekran görüntüleri ve UI testleri içindir. Her ekran ve durum tek argümanla açılır:
`-U2Route conversation:deniz/5.3`, `-U2Route lock:forgot`,
`-U2Route settings:7.3-wrong` … (tablo yukarıda). Metinler Türkçe kaynak +
İngilizce; her özelliğin kendi String Catalog tablosu var. Derleme:
`ios/scripts/fetch-libsignal.sh`, sonra `us2us.xcodeproj` (şema **us2us**).
Kilit ekranındaki demo kod: `246813`; yedek parolası: `kuzey7deniz42k`.

**Yayın (TestFlight):** sürüm `MARKETING_VERSION` (1.0.0), derleme numarası
`CURRENT_PROJECT_VERSION` (her yüklemede artar). `scripts/archive.sh` arşivi
alır ve App Store Connect için `.ipa` üretir (yüklemez). Önce Xcode › Settings
› Accounts'a SyconX LLC (C8QW8MBYQX) ekibinin Apple ID'si eklenmeli. App Store
Connect'te şifreleme (export compliance) sorularını sahibi yanıtlar; App
Privacy, `PrivacyInfo.xcprivacy` ile aynı olmalı (yalnızca User ID, uygulama
işlevi).
