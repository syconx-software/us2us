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
      Stickers/                  the built-in stickers (PROTOCOL §8.8): BrandStickerCatalog (ids, TR/EN
                                 words and labels, resolve), drawing + mascot + art, BrandStickerView,
                                 BrandStickerPicker, BrandStickerMessage (the chat), recents (ids only),
                                 BrandStickerGallery (DEBUG, -U2Gallery stickers)
      Avatars/                   profile pictures (PROTOCOL §8.9): BrandAvatarCatalog (preset ids, TR/EN
                                 labels), the cup art, AvatarChoice, AvatarPresetPicker, AvatarPhotoFace
                                 (photos decoded in memory), BrandAvatarGallery (DEBUG, -U2Gallery avatars)
      Gallery/                   ComponentGallery (DEBUG, -U2Gallery YES)
    Domain/                      UI models, MessengerService (the whole data surface), QR payloads
                                 (ContactCode, SafetyQRCodec), UsernameRules, voice messages
                                 (VoiceNotes: rules, waveform, the mic's hold gesture)
    Services/                    DesignFixtures (the design's data) + DesignFixturesService
    Features/
      Onboarding/                1.1–1.3 pager, 2.1–2.3 setup, 7.3 hand-off      Onboarding.xcstrings
      Lock/                      3.1 Face ID, 3.2 code, 3.3 forgot, 3.4 cover    Lock.xcstrings
      Chats/                     4.1 list, 4.2 new chat, 4.3 requests, 4.4 preview  Chats.xcstrings
      Conversation/              5.1–5.6                                          Conversation.xcstrings
      Verification/              6.1–6.3, add a contact by QR                     Verification.xcstrings
      Contact/                   the contact screen (5.1 › the person): Doğrulama · Fotoğraflar ·
                                 Belgeler, “…” › share · Bildir · Engelle / Engeli kaldır   Contact.xcstrings
      Report/                    “Bildir”: the report sheet and its e-mail (Mail, mailto:, or the
                                 address to copy)                                  Report.xcstrings
      Settings/                  7.1 + pickers, 7.2 export, 7.3 restore, code change, about,
                                 profile picture (full-screen picker, framing screen, “Aramada göster”),
                                 Engellenenler (everyone blocked, “Engeli kaldır”)  Settings.xcstrings
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
this phone through 3.3 (the account stays on the relay), restores it (the bot
is back in 4.1, without messages) and then deletes the account with 7.1 ›
“Her şeyi sil” (the username is free again for the next run). Run on
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
| `-U2Gallery stickers` | | every built-in sticker in both languages and themes, the picker and the received fallbacks (DEBUG) |
| `-U2Gallery avatars` | | every preset avatar at 28 / 44 / 96 pt in both themes, a photo, the loading ring and the full-screen picker (DEBUG) |
| `-U2ResetPreferences YES` | | forgets the saved theme / language |
| `-U2PickBackup YES` | | DEBUG: 7.3 “picks” a stand-in `.u2u` instead of showing the system document picker (UI tests) |
| `-U2ChatsDemo <action>` · `-U2ConversationDemo <action>` | | DEBUG: one interaction plays by itself ~1.5 s after the screen appears (for recording transitions); see `ChatsHarness` / `ConversationHarness` (`push-pop`, `sheet-cycle`: five rounds each) |
| `-U2StressChats <n>` · `-U2StressTicks YES` · `-U2StressAutoScroll <cycles>` | | DEBUG: 4.1 with `n` chats (the design's first) · the list keeps changing (rows move up, counters and statuses tick) · flings itself (3 down, 3 up per cycle) and prints the frame probe |
| `-U2FrameProbe YES` · `-U2FrameProbeLog YES` | | DEBUG: counts main-thread frame drops (read by `FluidityPerformanceUITests`) · prints them after every transition (`xcrun simctl launch --console-pty …`) |
| `-U2LockDemo forgot` · `unlock` | | DEBUG: the lock opens 3.3 by itself · types the demo code |
| `-U2Fixtures YES` | | DEBUG: the design's data instead of the live service (UI tests, screenshots; required by every route state) |
| `-U2RelayURL <url>` | | DEBUG: a local relay instead of `https://relay.us2us.app` |
| `-U2ReportMail address` | | DEBUG: “Bildir” › “E-postayla gönder” shows the support address to copy, whatever the phone has (UI tests; the simulator has no Mail, so it goes there anyway) |

| `-U2Route` | states |
|---|---|
| `onboarding:<state>` | `1.1` `1.2` `1.3` · `2.1` `2.1-blurred` `2.1-rules` `2.1-empty` `2.1-checking` `2.1-taken` `2.1-short` `2.1-invalid` `2.1-turkish` `2.1-firstLetter` · `2.2` `2.2-empty` `2.2-confirm` `2.2-weak` `2.2-mismatch` · `2.3` `2.3-unchecked` `2.3-error` `2.3-kept` `2.3-taken` · `7.3` `restore-2.2` `restore-2.3` `restore-2.3-gone` `restore-2.3-damaged` `restore-2.3-lock` `restore-2.3-toLock` · `demo` `demo-restore` `demo-back` (the flow plays itself) |
| `lock:<state>` | `faceid` `touchid` · `code` `typing` `wrong` `lastchance` `throttled` · `unavailable` `unusable` · `wiped` · `forgot` `erase` `restore` · `cover` `switcher` |
| `chats` · `chats:<state>` | `4.1` `4.1-connecting` `4.1-closed` `4.1-empty` `4.1-qr` `4.1-mycode` · `4.2` `4.2-keyboard` `4.2-empty` `4.2-searching` `4.2-notfound` `4.2-existing` `4.2-self` `4.2-mycode` · `4.3` `4.3-empty` `4.3-block` · `4.4` `4.4-media` `4.4-link` `4.4-report` · `4.1-blocked` (bora blocked) |
| `newChat` · `newChat:<username>` | the 4.2 sheet (with that query) |
| `requests` · `request:<id>` | 4.3 · 4.4 over 4.3 (`kuzey_dev` `n_aksoy` `hediye_kazan`) |
| `conversation:<chat>[/<state>]` | `deniz/…` `5.1` `5.1-failed` `5.1-reply` `5.1-attach` `5.1-stickers` (the sticker picker) `5.1-blocked` (the bar with “Engeli kaldır”) `5.1-viewer` `5.1-viewer-zoomed` `5.3` `5.3-incoming` `5.3-confirm` `5.4` `5.4-pending` `5.4-failed` `5.5` `5.6` `5.6-clean` `5.6-captured` · `mert_k/…` `5.2` `5.2-ended` `5.2-opened` `5.2-player` (paused at 0:07) `5.2-player-loading` `5.2-file` (Quick Look) `5.2-file-actions` (5.3 › Dosyalara kaydet) `5.2-remote` `5.2-downloading` `5.2-unavailable` `5.2-preparing` (a video from “+” while it is prepared) `5.2-prepare-failed` (“Tekrar gönder”) `5.2-file-disappearing` (Quick Look without sharing) `5.2-saved` (mert_k saved the PDF: the “Kaydetti” chip) `5.4-saved` (its info, ending in “Kaydedildi”) · `deniz/5.1-save-notice` (the one-time “deniz fotoğrafı kaydettiğini görecek” sheet over the viewer) `5.2-place` (the “Haritada aç” chooser) `5.2-map` (the live-location map: mert_k's demo share and selin_n's) `5.2-map-peer` (mert_k's only, selin_n's phone on this device only) `5.2-map-ask` · `5.2-map-denied` (no location access: the gentle hint) `5.5` (Ghost off) · voice (`deniz/…`, no microphone needed): `5.1-voice-hold` (held, sliding to cancel) `5.1-voice-locked` (hands-free) `5.1-voice-review` (stopped: listen, delete or send) `5.1-voice-hint` (“Kaydetmek için basılı tut”) `5.1-voice-permission` (“Ayarları aç”) `5.1-voice-unplayed` · demos (`-U2ConversationDemo`): `sticker` (a sticker leaves and settles in) `openPhoto` `playVideo` `openFile` `downloadFile` `sendVideo` `sendPhotos` (three photos picked at once, the draft on the first) `playVoice` `openPlace` `openMap` |
| `safety:<chat>[/<state>]` | `safety` · the contact screen's other tabs: `photos` `documents` · its “…”: `menu` `block` (the confirmation) `blocked` `report` `report-address` (no e-mail: the address, the block followed) · `safety-verified` `changed` `confirm-accept` `confirm` `confirm-remove` · `scan` `mismatch` `mycode` `denied` `torch` · `verified` `verified-manual` · add by QR: `contact-scan` `contact-unknown` `contact-own` `contact-mycode` `contact-verified` · scripted: `flow-match` `flow-mismatch` `flow-manual` `flow-remove` `flow-contact` |
| `settings` · `settings:<state>` | `7.1` `7.1-end` `7.1-access` `7.1-access-checking` `7.1-access-unanswered` `7.1-blocked` (bora and lale blocked: Engellenenler · 2) `qr` `erase` `erase-failed` · `blocked` (7.1 › Engellenenler: bora with a chat, lale without) `blocked-empty` · `avatar` `avatar-crop` (full-screen profile picture picker, framing screen) · pickers `presence` `autolock` `ghost` `disappearing` `theme` `language` · `7.2` `7.2-empty` `7.2-weak` `7.2-generated` `7.2-revealed` `7.2-copied` `7.2-confirm` `7.2-mismatch` `7.2-exporting` · `7.3` `7.3-pick` `7.3-password` `7.3-wrong` `7.3-done` `7.3-cancel` · `code` `code-wrong` `code-new` `code-weak` `code-confirm` `code-mismatch` · `faceid` · `about` |
| `backup` | 7.2 over 7.1 |
| `invite` · `invite:<state>` | the invite sheet over 4.1: `edit` `edited` `copied` `sent` · `link` `link-unknown` (an invite link for deniz / deniz_yilmaz arrives → 4.2 searched); `-U2OpenURL <url>` hands any URL to the link inbox (DEBUG) |

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
| `MessageBubble` + `BubbleShape` (tails), `TextBubble`, `ReplyBubble`/`ReplyQuote`, `PhotoBubble`, `VideoBubble`, `VoiceBubble`/`Waveform`, `FileBubble`, `LocationBubble`, `LiveLocationBubble` / `EndedLiveLocationBubble`, `DottedMap`, `TombstoneBubble`, `ViewOnceBubble`/`ViewOnceBadge`, `TextStickerMessage`, `BrandStickerMessage` (a built-in sticker, its fallbacks), `ReactionChip`, `BubbleMeta`, `MessageBubbleView` (any message, with its taps: media, view once, a place, a live location, “Durdur”), `SystemEventPill` | C15, 5.1, 5.2 |
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
  backdrop and modal accessibility). What a sheet or cover shows reaches it
  live (an `@Observable` model) or as its `item:` — never as the presenting
  view's `@State` that its body does not read: SwiftUI evaluates the content
  with that state as of the view's last body pass (1.0.7's profile picture
  sheet rose empty the first time). System pickers rise from
  `SystemPresenter`, once nothing there is being presented or dismissed.
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
  from the lock) → 2.3 → 4.1. If the relay refuses the backup's token
  (401 / 404: its account was deleted, or the backup was restored on another
  phone), 2.3 says the backup can no longer be used and that a new account
  can be created (`RestoreFailure.backupNoLongerValid`), instead of asking
  to check the connection.
* **Last backup** (7.1 › Yedek · “Son: …”): recorded only when the share
  sheet reports the exported file saved, as a time alone, in the vault's
  encrypted runtime state (`AppRuntime.recordBackupSaved`, never
  UserDefaults), so it survives relaunches. A share that finishes after the
  app has locked and closed its store is recorded at the next unlock. “Her
  şeyi sil” erases it with everything else. A restored account starts with
  “Hiç alınmadı”: the restore rotates the token, so the restored file, like
  every earlier one, no longer works.
* **Account deletion** (App Review 5.1.1(v)): 7.1 › Hesap › “Her şeyi sil”
  deletes the account on the relay too — no separate row. After the
  confirmation (“Hesabın silinsin mi?” › “Hesabı ve her şeyi sil”: account
  and username deleted, nobody can reach you, the username is free at once,
  cannot be undone) the danger button spins while
  `MessengerService.deleteAccountOnRelay()` runs
  (`DELETE /v1/accounts/me`, authenticated; the relay deletes the account,
  its keys, mailbox, uploaded media and username hashes, PROTOCOL §4.3).
  Only when the relay confirms does the sheet go down and
  `AppModel.eraseEverything` wipe the phone (the preview folder included)
  and cross-fade to 1.1 (`EraseAccountModel`, `EraseAccountSheet`). If the
  relay does not confirm (offline, a timeout, a server error) nothing is
  erased on this phone (the relay may still have deleted the account just
  before the connection dropped; “Tekrar dene” then gets 404 and counts it as
  deleted): the sheet cross-fades to “Hesap silinemedi.” with “Tekrar dene”
  and “Yalnızca bu telefondan sil”, whose copy says the account stays on the
  relay — messages sent to it never arrive — until it is removed after 180
  days without a connection (§4.10). A deleted account cannot come back from
  a backup (the restore rotates its token on the relay); exported backup
  files stay where the user saved them, unusable. 3.3 “Kodu unuttum” › “Her
  şeyi sil” stays local only — the vault is locked, so nothing can
  authenticate — and its confirmation (“Her şey silinsin mi?”) says the
  account stays on the relay for those 180 days.
* **Localization**: Turkish is the catalog's source language, English the
  second. The in-app language (7.1 › Dil) is applied as `\.locale` (SwiftUI
  looks strings up in that language) and passed explicitly to formatters and
  `L10n.string`; devices in other languages get English. Upper-casing always
  uses the locale (“GİZLİLİK”).
* **Theme**: System / Dark / Light (7.1 › Tema), device preference in
  UserDefaults (readable before unlocking), applied to all windows.
* **Media (5.1 / 5.2, proposed in 5.6's language)**: a photo opens in
  `MediaViewer` (fitted, pinch / double-tap zoom in a `UIScrollView`, swipe
  up or down to close), a video in `VideoPlayerScreen` (`VideoPlaybackModel`:
  AVPlayer, scrubber, mute, the audio session switched to playback only while
  it plays and restored after), a file in Quick Look (`FilePreview.swift`;
  5.3 › “Dosyalara kaydet” opens the Files exporter). The viewer and the
  player have “Kaydet” (to Photos, add-only); “Kaydet”, “Dosyalara kaydet”
  and the file preview's share button go through `MediaSaver`: before the
  first save of something received a one-time floating sheet says that the
  sender will see it (“deniz fotoğrafı kaydettiğini görecek” · Kaydet ·
  Vazgeç, remembered in `AppPreferences`), and whatever left the app is told
  (`MessengerService.markSaved` → SAVED, PROTOCOL §8.4 — independent of read
  receipts). The sender's photo, video or file then shows the “Kaydetti”
  chip next to its reaction (`SavedChip`) and “Kaydedildi” in 5.4.
  Disappearing and view-once media never leave the app. The person in the
  chat header opens the contact screen (`Features/Contact`): Doğrulama (6.1,
  unchanged), Fotoğraflar (a grid of the chat's photos and videos, newest
  first, a page at a time, swiping through them in `MediaGalleryViewer`) and
  Belgeler (its files, previewed like in the chat). Neither ever lists
  view-once or disappearing items (PROTOCOL §8.1), and both are read again
  whenever the chat's messages change while the screen is up
  (`MessengerService.mediaRevision(in:)`; the conversation is closed behind
  it, so nothing is marked read there). View-once media keeps
  `ViewOnceViewer` (5.6). The bubbles show where a file is
  (`Message.mediaAvailability`: here, on the relay, downloading with a ring,
  gone); a file tapped while on the relay downloads and opens once it is here.
  “+” › Fotoğraf ve video opens the system photo picker out of process (no
  photo-library permission, files only): up to ten photos and videos at a
  time, numbered in the order they are selected (`SystemPickers`,
  `PickedMediaPlan`). Each becomes a message of its own, in that order — not
  an album, so each one can fail, be sent again or be deleted by itself; the
  composer's text, if any, becomes the first photo's caption and the field
  clears (a pick without a photo, or a text longer than a caption may be,
  leaves it there); view-once would stay a single item without a caption.
  Every bubble shows at once (`OutgoingMedia`): the ring and “Hazırlanıyor”,
  a photo's preview at the bubble's size or a video's poster and length as
  soon as they are read from the picked copy, while the picker hands the
  files over. The items are then prepared one at a time, in the order picked
  (a photo re-encoded at ≤ 2048 px, a video re-exported at ≤ 720p, no
  metadata), so only one is ever decoded or re-exported however many were
  picked; each is stored under the id its bubble already has
  (`AppRuntime.sendMedia(_:to:messageId:)`), so the sent message takes the
  bubble's place — a later item never shows above one stored before it — and
  a sent photo keeps the photo as sent in memory, so its bubble stays sharp.
  One that fails never holds up the rest: its bubble shows the warning and
  “İletilemedi · Fotoğraf (Video) hazırlanamadı · Tekrar gönder”, which
  prepares it again after whatever is still waiting. VoiceOver hears “5
  fotoğraf gönderiliyor” once the picker has gone. The fixtures send three of
  the design's pictures as one pick (`DesignFixtures.pickedPhotos`).
  The controls' extra glyphs (pause, speaker, download, file, stop, the
  recorder's up caret) are drawn on Phosphor's grid in code (`MediaGlyph`),
  not bundled.
* **Voice messages (5.1, proposed beyond the design's paused bubble and idle
  mic)**: press and hold the mic to record (it starts after 0.2 s, so a tap
  never opens the microphone and only shows “Kaydetmek için basılı tut”),
  release to send; slide left to cancel, up to lock (hands-free: “Sil” ·
  stop · “Gönder”; stop → listen to it, then delete or send). While it
  records, the strip in the field's place shows the pulsing dot, the time,
  the live waveform and “‹ İptal için kaydır”; light haptics on start, lock,
  cancel and send (the session allows haptics while recording; start,
  cancel and the limit tap outside the microphone's time). Under a second is
  discarded with the hint; at five minutes recording stops by itself (held:
  sent on release; locked: waits to be sent). A call, any audio
  interruption or the recorder stopping by itself (an encoder error, a full
  disk) stops it and keeps the recording for review; leaving the chat or the
  foreground throws it away, so the screen stays on while a voice message
  records or plays (auto-lock would otherwise end a hands-free recording).
  The keyboard stays while the mic is held and goes when recording locks or
  stops. VoiceOver: a double tap on the mic records hands-free (the focus
  moves to the stop button); hints and the stop are announced, never while
  the microphone is open. `VoiceComposer` (state,
  `VoiceHoldGesture` for press / lock / cancel), `AudioRecorderCapture`
  (AVAudioRecorder: AAC, mono, 24 kHz, ~32 kbit/s, metering → up to 100
  waveform samples, `VoiceWaveform`), `VoiceRecordingStrip` and friends; the
  audio session is play-and-record only while recording and restored after
  (`PlaybackAudioSession`). Sent through `MessengerService.send(voice:replyTo:in:)`
  → `AppRuntime.prepareVoiceNote(_:durationMs:waveform:)` → `sendMedia`
  (as a reply too); the bubble shows at once with the usual states.
  Playing (`VoicePlayback`): one at a time, from memory
  (`mediaPayload` → `AVAudioPlayer(data:)`; a message still on the relay
  downloads first; one above 16 MB is never decrypted), play / pause, the
  progress over the waveform, a sideways drag to scrub and a tap to jump,
  1× · 1,5× · 2×; it stops when another plays, other media opens, the
  chat closes, the app leaves the foreground or the message goes; an
  interruption or unplugged headphones pause it. Paused or finished, the
  audio session goes back two seconds later (other apps' music does not
  resume between two voice messages). A voice message that cannot be played,
  or a recording that could not be stored for sending, says so above the
  mic. An incoming voice message
  not played yet shows a dot before its time; its first play marks it
  played and — only while read receipts are on — sends VIEWED (PROTOCOL
  §8.4), so the sender sees `ph:microphone` in `accent300` next to the time.
  Voice messages cannot be copied, saved or shared; disappearing, Ghost and
  “Herkesten sil” apply as for any message.
* **Blocking and reports (App Review 1.2; proposed, in 6.1's and 4.4's
  language)**: the contact screen's “…” (in 6.1's share button's place;
  the share moved into it as “Numarayı paylaş”) offers “Bildir” and
  “Engelle”. Engelle asks with the request block's alert (“deniz
  engellensin mi?” · Engelle · Bildir · Vazgeç — the same alert, with
  Bildir, on 4.3 and 4.4) and calls `MessengerService.block(chat:)` →
  `AppRuntime.block` (PROTOCOL §6.4): what the person sends is dropped on
  this phone, messages still waiting to go to them are cancelled (they
  fail: “İletilemedi”, sent again with “Tekrar gönder” after an unblock), a
  live location shared with them ends, our access key is rotated, and
  they are not told. The chat stays, read only
  (`Peer.isBlocked`): 4.1's row says “Engellendi”, 5.1's header too, its
  composer gives way to a bar in 4.4's decision-bar look with “Engeli
  kaldır”, the Ghost chip cannot be changed and 5.3 has no reactions, no
  Yanıtla and no Herkesten sil. On the contact screen the name gets
  `ph:prohibit` and Doğrulama a note with “Engeli kaldır”; the menu offers
  it in Engelle's place. Unblocking (`unblock(chat:)` → `AppRuntime.unblock`)
  needs no confirmation and returns the person to the state before the
  block — a contact gets the current access key and profile again. A
  request blocked in 4.3 / 4.4 is, in the runtime, a blocked chat: it
  stays in 4.1 with its messages (no unread count), and unblocking it
  there makes it a request again — from its contact screen too, which then
  goes back to 4.1 with the chat. A blocked chat that Ghost erased comes
  back from 4.2 › “Mesaj yaz”, still blocked, with “Engeli kaldır”.
  7.1 › Gizlilik › **Engellenenler** (proposed; the row shows how many,
  nothing when nobody is blocked) lists everyone blocked, by username —
  also those whose chat is gone (Ghost erased it, or it was deleted), so
  they no longer have to be found in 4.2 first: `MessengerService.blockedPeople`,
  observable, from `AppRuntime.blockedContacts` (every blocked contact, with
  or without a conversation; `ContactRepository.blocked()`), refreshed with
  the chats and empty while locked. Each row (C13 geometry: the initial in
  a 44 pt avatar — never a picture for someone blocked — the username,
  “Kullanıcı adı bilinmiyor” under it when a Ghost wipe with `wipe_contact`
  kept only the block, and “Engeli kaldır”) unblocks without a
  confirmation through `unblock(_:)`, which is `unblock(chat:)` for the
  person's account: the same as from the contact screen, with or without a
  chat — a contact is accepted again, a blocked request is a request again.
  Someone with nothing to go back to (no chat, and no contact before the
  block: what that Ghost wipe left) is forgotten, a stranger again — 4.2 ›
  “Mesaj yaz” then starts a new chat, never an empty request
  (`ContactRepository.unblockLeavingNothing`).
  The row collapses at once and comes back if the unblock could not be
  stored; with nobody left, “Engellediğin kimse yok.” (4.3's empty-state
  style). The runtime does not record when someone was blocked, so the
  list does not show it.
  “Bildir” (the contact screen's and 4.4's “…”, and every
  block confirmation) opens the report sheet (`Features/Report`): a reason
  (Spam · Taciz veya zorbalık · Uygunsuz içerik · Kimliğe bürünme · Diğer),
  an optional note (≤ 500 characters) and “Bu kişiyi de engelle” (on).
  “E-postayla gönder” writes an e-mail to support@us2us.app in the app's
  language (`ReportMail`): subject “us2us bildirimi: @kullanici”, body with
  the username (`MessengerService.reportSubject`: the username alone,
  marked when the directory did not confirm it — never a display name or
  account id), the reason, the note, the version and build, the time in
  UTC and the sentence that no message content is included (the user may
  attach screenshots). Nothing else is ever added: no message, key or id.
  It goes through Mail inside the app when Mail can send
  (`MFMailComposeViewController` from `SystemPresenter`, above the sheet,
  like the invite's e-mail channel — the app stays active, so neither the
  privacy cover nor the lock comes up), else another mail app through
  `mailto:` (outside the app: the lock follows on return, as always), else
  the sheet shows the address with “Kopyala” (local pasteboard, 2 min) —
  the simulator always lands there. The block follows only a report that
  left the sheet (`ReportDelivery.blocks(after:alsoBlock:)`: sent by
  Mail, handed to a mail app, or the address shown); cancelling Mail,
  keeping a draft or a failure blocks nobody and keeps the form. From 4.4
  a report that blocked the sender closes back to 4.3.
* **Location (5.2, proposed: the design draws only the bubbles)**: a tap on
  a place bubble opens the floating “Haritada aç” chooser
  (`LocationOpenSheet`): Apple Maps (`maps.apple.com`, `ll` + the place's
  name) or Google Maps (`comgooglemaps://` when the app is installed,
  otherwise google.com/maps in the browser; coordinates only), with the hint
  that the location is handed to the chosen app (`MapLinks`, `MapLauncher`).
  A tap on a running live-location bubble opens `LiveLocationMapView`, a
  full-screen fade cover with a SwiftUI MapKit map: the contact's latest live
  position and the user's own, the remaining time, “Paylaşımı durdur” for
  the user's own share, “İkinizi de göster” / “Konumuma git”, and “Haritada
  aç” for the contact's position. The camera keeps both in view and follows
  every tick until the user moves the map (`LiveMapFraming`); this device's
  position, while it is not shared, is taken into the framing only after
  “Konumuma git” or “İkinizi de göster”. A place from a disappearing message
  gets one more line in the chooser: the chosen app may keep it in its own
  history. The positions
  come from `MessengerService.liveLocations(in:)` (observable, memory only)
  and `ownPositionUpdates()`; the bubbles stay the design's dotted-map art.

## Info.plist & privacy

* Usage descriptions (tr + en, `Resources/InfoPlist.xcstrings`) only for what
  the app really uses: `NSFaceIDUsageDescription` (unlock),
  `NSCameraUsageDescription` (QR scanning only),
  `NSLocationWhenInUseUsageDescription` (“+” › Konum, live location, and the
  user's own position on the live-location map — shown on this device only),
  `NSMicrophoneUsageDescription` (only while the user records a voice
  message; asked on the first press of the mic; refused → a floating sheet
  explains it and opens Settings). **No
  “Always” location**; `UIBackgroundModes: location` exists only so a live
  location the user started keeps updating in the background, with the
  system's location indicator (`LocationProvider`). **No photo-library read
  access**: sending uses the out-of-process photo picker (photos and videos),
  which needs none. `NSPhotoLibraryAddUsageDescription` (**add-only**) exists
  for the viewer's and the player's “Kaydet” and because the file preview's
  share sheet offers “Save Image” for a received image file, and iOS
  terminates an app that saves without it; iOS asks the first time the user
  saves, the app can only add, never read, and the description says that the
  sender of something received sees that it was saved.
* **Location privacy.** `LSApplicationQueriesSchemes` lists only
  `comgooglemaps`: the chooser asks whether the Google Maps app is installed,
  nothing else is queried. A place leaves the app only when the user picks
  Apple Maps or Google Maps, and only its coordinates (plus, for Apple Maps,
  the sender's name for it); from there the chosen app's privacy rules apply,
  as the chooser says; for a place in a disappearing message the chooser
  adds that the chosen app may keep it in its own history (Apple Maps'
  Recents, Google's history), which us2us cannot remove. The live-location
  map loads Apple's map tiles (MapKit) only while it is on screen, so Apple
  can see the area shown — the map says so in its card; nothing from the
  chat is sent to Apple, and the bubbles never load tiles (they are drawn
  art). The camera frames the contact, and the user only while they share
  their position or once they tap “Konumuma git” / “İkinizi de göster”, so
  no tiles around the user are loaded unasked. The system's MapKit may cache
  the map images of the area shown on this phone, outside the app's
  encrypted storage; they show places, not whose they are. The contact's
  positions reach the map from the runtime's open conversation, in memory
  only: the app keeps no copy, logs nothing, and the map drops them when it
  closes, when the app goes to the background and on lock. (The runtime itself keeps only each
  share's latest position, sealed inside its bubble in SQLCipher and replaced
  in place — PROTOCOL §8.3.) The user's own position on the map comes from a
  separate `CLLocationManager` (`DevicePositionWatcher`): when-in-use, never
  in the background, never sent, stopped when the map closes or the app
  leaves the foreground; without permission the map shows only the contact
  and a gentle hint (“İzin ver” asks once; “Ayarları aç” after a refusal).
* **Media privacy — plaintext only in short-lived, protected copies.**
  Received and sent media are stored encrypted by the runtime
  (Us2usAppCore, per conversation key) and decrypted into memory only where
  they are shown: the bubbles and the photo viewer decode images from
  memory (`MessengerService.mediaPayload`); the video player plays from
  memory through an `AVAssetResourceLoaderDelegate`
  (`MemoryAssetLoader`, a private `u2u-memory:` URL — no file, no AirPlay, no
  picture in picture). Quick Look and the Files exporter accept only file
  URLs, so a file message is previewed or saved from one named copy in
  `tmp/preview/<random>/<name>` (`ProtectedPreviewFiles`): complete file
  protection on the folder and the file, excluded from backup, the
  sender's name sanitised (no path, no control or bidi-override characters).
  The copy is deleted when the preview or the export closes, when its message
  disappears, when the app goes to the background (the lock follows on
  return), on “Her şeyi sil” and at every launch. Photos and videos open
  without any copy; web content (HTML, XML, SVG, web archives) is never
  previewed (it could load remote resources) but can be saved to Files.
  An image sent as a file opens in the app's own photo viewer (a long press
  lifts nothing there); every other file opens in Quick Look embedded in the
  app's own screen
  (`ProtectedFilePreviewScreen`): Quick Look shows its share button only in
  its own navigation bar, so the only way out is the screen's own share
  button, which tells the sender (SAVED). A file of a **disappearing
  message** has no share button — no Save to Files,
  AirDrop or other apps — and nothing it cannot preview can be saved either.
  The screen's line says what the app offers (“paylaşma ve kaydetme seçeneği
  yok”), not that copying is impossible (open point 8).
  View-once media is unchanged: 5.6 only, memory only, no zoom, no saving.
* **Voice recordings** exist on disk only while they are recorded:
  AVAudioRecorder writes only to a file, so it gets one in
  `tmp/voice/<random>/voice.m4a` (`VoiceRecordingFiles`: complete file
  protection on the folders, excluded from backup). When recording stops the
  bytes are read into memory and the file goes with its folder at once; a
  cancelled recording is deleted; whatever is left (a crash, a kill) goes at
  every launch, when the app goes to the background and on “Her şeyi sil”.
  Sending hands the bytes to the runtime, which stages them in its own
  protected staging folder only until they are moved into encrypted storage
  (the same step). Received and sent voice messages play from memory
  (`AVAudioPlayer(data:)`); no decrypted audio is written anywhere.
* **Picked photos, videos and files** are copied into a protected folder of
  their own (`tmp/picked/<random>/`, complete file protection) that the
  runtime prepares them from; the copy goes with its folder once it is sent
  (a video: once it is stored, encrypted, by the runtime), when its bubble
  is removed, when a video that failed to be prepared is left in the
  background, on “Her şeyi sil” and at every launch. A video's poster while it
  is prepared is a JPEG in memory. A document picked from Files moves (not
  copies) into that folder, so no second plaintext copy stays in the inbox.
* `ITSAppUsesNonExemptEncryption = NO` — the app implements end-to-end
  encryption with standard, published algorithms only (libsignal's Signal
  protocol; SQLCipher's AES-256 at rest) and is not distributed in France.
  Apple requires no export compliance documentation for that case (App Store
  Connect Help › Export compliance documentation for encryption), and the key
  is `NO` for encryption that is exempt from documentation. Distributing in
  France needs a French encryption declaration: upload it in App Store
  Connect, set the key to `YES` and add the `ITSEncryptionExportComplianceCode`
  Apple issues to the `info` properties in project.yml. Because the key is
  `NO`, **France must be removed** in App Store Connect › Pricing and
  Availability (new apps are offered in every territory, France included).
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

It first runs `ios/scripts/fetch-libsignal.sh --verify` and stops unless the
vendored libsignal matches the committed `ios/scripts/libsignal-vendor.sha256`
(ios/README.md › Vendor libsignal). It then runs `xcodebuild archive` (Release,
generic iOS device, `-allowProvisioningUpdates`,
`-onlyUsePackageVersionsFromResolvedFile`) and `xcodebuild -exportArchive` with
`ExportOptions.plist` (`app-store-connect`, `destination export`), and writes
`provenance.txt` next to the archive (what went into the build; keep it with
every upload). Automatic
signing needs the team's Apple ID in **Xcode › Settings › Accounts**; Xcode
then creates the App Store profile itself. Upload `us2us.ipa` with Xcode's
Organizer or Transporter.

Before the first upload, in App Store Connect: create the app record (iOS,
`com.syconx.us2us`, primary language Turkish), fill **App Privacy** exactly as
`PrivacyInfo.xcprivacy` says (Identifiers › User ID, App Functionality, linked,
no tracking; nothing else collected), set the **Privacy Policy URL**
(`https://us2us.app/en/privacy`; the app links it in 7.1 › Hakkında), remove
France from **Pricing and Availability** (`ITSAppUsesNonExemptEncryption` is
`NO`: standard algorithms only, no France; distributing there needs the
French declaration and `YES`), and explain the background location mode in
the review notes (user-started live location sharing only, location
indicator visible).

**App Review notes** (paste into App Store Connect › App Review Information ›
Notes):

> Blocking and reporting (Guideline 1.2): to get a chat, open Chats › the
> new-chat button (top right) › type us2us_echo › “Write a message” and
> send anything: this test account answers every message. Then tap its
> name at the top of the chat › “…” › “Report” or “Block” (“Block” then
> becomes “Unblock”); the blocked chat says “Blocked” and shows an
> “Unblock” bar in place of the text field. Settings › Privacy › “Blocked”
> lists everyone the user blocked, also people whose chat was erased, each
> with “Unblock”. Message requests have “Block”
> on every request and “Report” in the request's “…” menu and in the block
> confirmation. A report is an e-mail to support@us2us.app with the
> username, the chosen reason, the user's note, the app version and the
> time (messages are end-to-end encrypted, so no message content is
> included; the user may attach screenshots); “Also block this person” is
> on by default. Contact: support@us2us.app (also on
> https://us2us.app/en/support, linked in Settings › About).
>
> Account deletion: Chats › gear (Settings) › Account › “Erase everything” →
> “Delete account and everything”. This deletes the account, its username,
> keys, mailbox and uploaded media on our server (DELETE /v1/accounts/me),
> then erases the phone. If the server cannot confirm, nothing is erased and
> the user can retry. Privacy policy: https://us2us.app/en/privacy (also in
> Settings › About).

## Open points

1. **Usernames** (settled): one rule on every screen and platform, PROTOCOL
   §3.1 — the normalized name matches `^[a-z][a-z0-9_]{2,23}$` (3–24
   characters, a letter first, then `a-z 0-9 _`; no dot). `UsernameRules`
   trims `White_Space`, drops one leading “@” and applies the kit's
   `NormalizedUsername` normalization (NFKC, locale-independent lower case),
   so 2.1, 4.2 and invite links check, look up and register byte for byte
   what the kit hashes. What was typed is never turned into another name
   (“Kağan”, “Ali Veli”, “İsmail” stay and are reported); the broken rules
   are, in order, Turkish characters, other characters, first letter,
   length. The shared case table `spec/username-rules.json` is run by the
   app's unit tests and by the Kotlin core's tests (`UsernameRules`,
   `android/core`). The live service cannot ask the relay about a name
   before the account exists (§4.4): 2.1 shows “Kurallara uygun” for a name
   that meets the rules, and a name taken at registration (409
   `username_taken`) shows on 2.3 as “Bu kullanıcı adı alınmış.” with “Başka
   bir ad seç”: back to 2.1 with the name marked “Alınmış” and the
   confirmed code kept, so “Devam” returns to 2.3. ios/design/inventory.md
   writes the design's dotted example names with `_`.
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
8. **Files of disappearing messages**: 5.3 › “Dosyalara kaydet” is off for them
   (like “Kopyala”), and they preview in Quick Look embedded in the app's
   screen, without Quick Look's share button. What remains, as for any
   message, is what is on screen: a screenshot, or text selected inside a
   document (Quick Look's own Copy / Share…); image files open in the app's
   photo viewer instead, where nothing can be lifted. Office and iWork files are previewed too; whether
   Quick Look loads images they link from the web (which would show the
   sender's server the reader's address) is still to be checked on a device.
9. **Media screens not in the design**: the photo viewer, the video player,
   the file states and the voice recorder / player extend 5.6 / 5.2 / 5.1
   (proposed) and have no reference image; the harness states (`5.1-viewer`,
   `5.2-player`, `5.2-file`, `5.2-remote`, `5.1-voice-hold` …) open each of
   them for review.
10. **Location screens not in the design**: the “Haritada aç” chooser and the
    live-location map extend 3.3 / 5.6 (proposed; `5.2-place`, `5.2-map` …).
    The bubbles keep the drawn dotted-map art instead of a real map preview:
    a snapshot would load Apple's tiles of the contact's position every time
    the chat opens, without the user asking. A place in a disappearing
    message can still be opened in Apple Maps or Google Maps; the chooser
    then says that the chosen app may keep it in its own history.

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
`-U2Route settings:7.3-wrong` … (tablo yukarıda). Medya: fotoğraf ekrana
sığarak açılır ve yakınlaştırılır, video sohbetin içindeki oynatıcıda oynar,
dosya indirilip Quick Look'ta açılır ya da Dosyalar'a kaydedilir; şifresi
çözülen medya bellekte kalır, Quick Look için açılan adlı kopya kapanınca,
uygulama arka plana geçince ve her açılışta silinir. “+” ile seçilen videonun
balonu hemen görünür (“Hazırlanıyor”, poster ve süre okunur okunmaz), video
hazırlanıp aynı balonla gönderilir; hazırlanamazsa “Tekrar gönder” çıkar.
Süreli mesajdaki dosya, paylaş düğmesi olmayan gömülü bir Quick Look'ta açılır.
Görüntüleyicide ve oynatıcıda “Kaydet” Fotoğraflar'a kaydeder; alınan bir şey
ilk kez kaydedilmeden önce “deniz fotoğrafı kaydettiğini görecek” bir kez
sorulur, kaydedilen ya da paylaşılan her şey gönderene bildirilir ve onun
balonunda “Kaydetti” görünür. Sohbet başlığındaki kişiye dokununca kişi
ekranı açılır: Doğrulama (6.1), Fotoğraflar ve Belgeler sekmeleri.
Kişi ekranındaki “…” menüsünde “Bildir” ve “Engelle” var (engellenen
kişide “Engeli kaldır”). Engellenen kişinin yazdıkları bu telefona
ulaşmaz, ona bir şey gitmez, kendisine söylenmez; sohbet salt okunur
kalır, yazma alanının yerinde “Engeli kaldır” çıkar. “Bildir” bir neden,
isteğe bağlı bir not ve “Bu kişiyi de engelle” (açık) sorar ve
support@us2us.app adresine bir e-posta hazırlar: yalnızca kullanıcı adı,
neden, not, sürüm ve UTC saat; mesaj içeriği asla eklenmez. E-posta yoksa
adres kopyalanmak üzere gösterilir. Engel yalnızca bildirim gönderilince
(ya da e-posta uygulamasına geçince, adres gösterilince) gelir;
vazgeçilirse gelmez. Mesaj isteklerinde de engelleme onayında ve 4.4'ün
“…” menüsünde “Bildir” var. Ayarlar › Gizlilik › “Engellenenler” engellenen
herkesi listeler (satırda kaç kişi olduğu yazar), sohbeti silinmiş olanları
da; her satırdaki “Engeli kaldır” onay sormadan kişi ekranındakiyle aynı
işi yapar. Kimse yoksa “Engellediğin kimse yok.” görünür.
Sesli mesaj: mikrofona basılı tutunca kayıt başlar, bırakınca gider; sola
kaydırınca iptal, yukarı kaydırınca kilitlenir (eller serbest: sil · durdur ·
gönder). Mikrofon izni ilk basışta istenir. Kayıt yalnızca kaydedilirken
korumalı geçici bir dosyada durur; durunca belleğe alınır ve dosya hemen
silinir. Sesli mesajlar bellekten oynatılır (oynat/duraklat, ilerleme,
sürükleyerek sarma, 1× · 1,5× · 2×); ilk dinleme, okundu bilgisi açıksa
gönderene “dinlendi” olarak gider.
Konum balonuna dokununca “Apple Haritalar’da aç” / “Google Maps’te aç”
seçenekleri çıkar (konum seçilen uygulamaya aktarılır); canlı konum balonu
uygulama içi haritayı açar: karşı tarafın son konumu ve senin konumun
(yalnızca bu cihazda, izin varsa), kalan süre, “Paylaşımı durdur”. Uygulama
konumların ayrı bir kopyasını tutmaz ve hiçbir şeyi log'lamaz; çalışma zamanı
her paylaşımın yalnızca son konumunu balonun içinde SQLCipher'da şifreli tutar
(PROTOCOL §8.3); harita görüntüleri Apple'dan, yalnızca harita açıkken
yüklenir. Hesap silme: Ayarlar › Hesap › “Her şeyi sil” önce hesabı ve
kullanıcı adını sunucudan siler (kullanıcı adı hemen serbest kalır), sunucu
onaylayınca telefonu siler; onaylamazsa telefonda hiçbir şey silinmez, “Tekrar
dene” ve “Yalnızca bu telefondan sil” çıkar. “Kodu unuttum” yolu yalnızca
telefonu siler. Gizlilik politikası ve destek sayfası Ayarlar › Hakkında'dan
açılır. Metinler Türkçe kaynak +
İngilizce; her özelliğin kendi String Catalog tablosu var. Derleme:
`ios/scripts/fetch-libsignal.sh`, sonra `us2us.xcodeproj` (şema **us2us**).
Kilit ekranındaki demo kod: `246813`; yedek parolası: `kuzey7deniz42k`.

**Yayın (TestFlight):** sürüm `MARKETING_VERSION` (1.0.8), derleme numarası
`CURRENT_PROJECT_VERSION` (11; her yüklemede artar). `scripts/archive.sh` arşivi
alır ve App Store Connect için `.ipa` üretir (yüklemez). Önce Xcode › Settings
› Accounts'a SyconX LLC (C8QW8MBYQX) ekibinin Apple ID'si eklenmeli. App Store
Connect'te Fiyat ve Bulunabilirlik'ten Fransa çıkarılmalı
(`ITSAppUsesNonExemptEncryption = NO`: yalnızca standart algoritmalar, Fransa
yok; Fransa için Fransız beyanı ve `YES` gerekir); App Privacy,
`PrivacyInfo.xcprivacy` ile aynı olmalı (yalnızca User ID, uygulama işlevi);
Gizlilik Politikası URL'si `https://us2us.app/tr/gizlilik` (İngilizce:
`/en/privacy`). App Review notlarına hesap silmenin yeri yazılır (yukarıdaki
İngilizce metin): Ayarlar › Hesap › “Her şeyi sil” › “Hesabı ve her şeyi
sil”. Engelleme ve bildirim için notlar incelemeciye bir sohbet yolu da
verir: Sohbetler › yeni sohbet › `us2us_echo` › “Mesaj yaz”; yankı botu her
mesaja yanıt verir, sonra başlıktaki ada dokunup “…” › “Bildir” / “Engelle”;
engellenenler Ayarlar › Gizlilik › “Engellenenler”de.
Göndermeden önce bot ayakta olmalı (`scripts/deploy-bot.sh`).
