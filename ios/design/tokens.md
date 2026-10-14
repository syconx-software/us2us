# us2us iOS — design tokens

Machine-readable twin: `tokens.json` (same names; the Swift design system mirrors them 1:1). Extracted from the Nocturne `styles.css` (dark) and the light remap in the design file, cross-checked against Chrome's computed styles and the reference PNG pixels. Units are points; 1 CSS px on the 402×874 frame = 1 pt.

**Naming.** Colour tokens are lowerCamel without dashes: `--color-accent-300` → `accent300`, `--u-danger` → `danger`. Opacity variants are written `text·68%` (token at 68 % alpha) — in Swift `Color.u2.text.opacity(0.68)` or the semantic alias (`textSecondary`). Every view takes colours from tokens only; the two themes are two value sets for the same names.

## 1 · Colour — base tokens

| token | CSS source | dark | light | note |
|---|---|---|---|---|
| `bg` | `--color-bg` | `#161826` | `#f3f5fe` |  |
| `surface` | `--color-surface` | `#232532` | `#e4e7f5` |  |
| `text` | `--color-text` | `#e9e9ed` | `#292b31` |  |
| `accent` | `--color-accent` | `#9184d9` | `#796cbf` |  |
| `accent100` | `--color-accent-100` | `#f5f4ff` | `#2b2741` |  |
| `accent200` | `--color-accent-200` | `#e7e5fe` | `#423a6a` |  |
| `accent300` | `--color-accent-300` | `#d2cefd` | `#5d5294` |  |
| `accent400` | `--color-accent-400` | `#b5abfc` | `#796cbf` |  |
| `accent500` | `--color-accent-500` | `#968ae0` | `#968ae0` | not remapped by the light theme (same value in both themes); unused by the iOS screens |
| `accent600` | `--color-accent-600` | `#796cbf` | `#b5abfc` |  |
| `accent700` | `--color-accent-700` | `#5d5294` | `#d2cefd` |  |
| `accent800` | `--color-accent-800` | `#423a6a` | `#e7e5fe` |  |
| `accent900` | `--color-accent-900` | `#2b2741` | `#f5f4ff` |  |
| `neutral100` | `--color-neutral-100` | `#f3f5fe` | `#292b31` |  |
| `neutral200` | `--color-neutral-200` | `#e4e7f5` | `#3f424d` |  |
| `neutral300` | `--color-neutral-300` | `#cfd3e5` | `#595d6c` |  |
| `neutral400` | `--color-neutral-400` | `#b2b6ca` | `#75798c` |  |
| `neutral500` | `--color-neutral-500` | `#9397ab` | `#9397ab` | not remapped by the light theme (same value in both themes); unused by the iOS screens |
| `neutral600` | `--color-neutral-600` | `#75798c` | `#b2b6ca` |  |
| `neutral700` | `--color-neutral-700` | `#595d6c` | `#cfd3e5` |  |
| `neutral800` | `--color-neutral-800` | `#3f424d` | `#dfe2f0` |  |
| `neutral900` | `--color-neutral-900` | `#292b31` | `#f3f5fe` |  |
| `online` | `--u-online` | `#52cd86` | `#009351` | oklch(0.76 0.15 155) / oklch(0.58 0.15 155). light value is slightly outside sRGB; hex is the per-channel clip Chrome rendered (verified against the reference PNG) |
| `danger` | `--u-danger` | `#f2716a` | `#c53637` | oklch(0.7 0.16 25) / oklch(0.55 0.18 25).  |
| `accent2` | `--color-accent-2` | `#a7a1db` | `#a7a1db` | Nocturne stand-in for a second accent; unused by the iOS screens |
| `divider` | `--color-divider` | `#e9e9ed@16%` | `#292b31@16%` |  |

Light remap rule (designer note 08): “the tonal steps are inverted” — the light theme maps `neutral100↔900`, `accent100↔900` etc. (with `neutral800` = `#dfe2f0`, a value that is not in the dark ramp). `accent500`/`neutral500` and `accent2*` are not remapped and are not used by the iOS screens.

## 2 · Colour — semantic aliases (what the screens actually use)

| alias | = | dark | light | used for |
|---|---|---|---|---|
| `textPrimary` | `text` | `#e9e9ed` | `#292b31` | body text, titles, usernames |
| `textSecondary` | `text` · 68 % | `#e9e9ed@68%` | `#292b31@68%` | the workhorse muted text: captions, previews, timestamps, meta, icons in lists (≈430 uses) |
| `textStrong` | `text` · 84 % | `#e9e9ed@84%` | `#292b31@84%` | request previews, link text in bubbles |
| `textClock` | `text` · 86 % | `#e9e9ed@86%` | `#292b31@86%` | 1.2 big clock |
| `textSafetyDigits` | `text` · 88 % | `#e9e9ed@88%` | `#292b31@88%` | 6.1 safety-number digits |
| `textQuiet` | `text` · 80 % | `#e9e9ed@80%` | `#292b31@80%` | text-only secondary actions (“Yedekten geri yükle”, “Doğrulandı olarak işaretle”) |
| `textComposerIcon` | `text` · 55 % | `#e9e9ed@55%` | `#292b31@55%` | timer/sticker icons inside the composer field |
| `textPlaceholder` | `text` · 50 % | `#e9e9ed@50%` | `#292b31@50%` | composer placeholder “Mesaj” |
| `textPending` | `text` · 45 % | `#e9e9ed@45%` | `#292b31@45%` | not-yet-started step (7.3) |
| `textTertiary` | `text` · 40 % | `#e9e9ed@40%` | `#292b31@40%` | chevrons (caret-right), clear (x-circle) glyph, empty code-dot ring |
| `textDisabled` | `text` · 35 % | `#e9e9ed@35%` | `#292b31@35%` | disabled menu row (“Kopyala” on disappearing messages) |
| `fillGlass` | `text` · 9 % | `#e9e9ed@9%` | `#292b31@9%` | round icon buttons, keypad keys, “+” buttons |
| `fillSubtle` | `text` · 6 % | `#e9e9ed@6%` | `#292b31@6%` | system pills (“Ghost mod açık”), verified-at pill, menu group gap band |
| `strokeGlass` | `text` · 18 % | `#e9e9ed@18%` | `#292b31@18%` | 0.5pt inset hairline of round icon buttons |
| `strokeField` | `text` · 14 % | `#e9e9ed@14%` | `#292b31@14%` | 0.5pt inset hairline of the composer field |
| `strokeCard` | `text` · 10 % | `#e9e9ed@10%` | `#292b31@10%` | QR card / app-switcher card hairline |
| `divider` | `text` · 16 % | `#e9e9ed@16%` | `#292b31@16%` | Nocturne divider: 1pt rules, outline buttons, segmented chips |
| `dotRule` | `text` · 22 % | `#e9e9ed@22%` | `#292b31@22%` | dotted row separators (5pt pitch) |
| `dotRuleHeader` | `text` · 20 % | `#e9e9ed@20%` | `#292b31@20%` | dotted rule under chat headers |
| `dotRuleDate` | `text` · 28 % | `#e9e9ed@28%` | `#292b31@28%` | dotted rule of the date separator (4.4) |
| `dotRuleTimeline` | `text` · 32 % | `#e9e9ed@32%` | `#292b31@32%` | vertical dotted connector in the message-info timeline (5.4) |
| `dotGrid` | `text` · 16 % | `#e9e9ed@16%` | `#292b31@16%` | background dot grid on 1.1 (3.1/6.3 use 14%) |
| `dotGridSoft` | `text` · 14 % | `#e9e9ed@14%` | `#292b31@14%` | background dot grid on 3.1 / 6.3 |
| `dotTrack` | `text` · 22 % | `#e9e9ed@22%` | `#292b31@22%` | unlit dots of dotted progress bars (1.3 ghost meter, 7.3 restore) |
| `dotTrackStrength` | `text` · 25 % | `#e9e9ed@25%` | `#292b31@25%` | unlit dots of the password-strength meter (7.2) |
| `pageDotInactive` | `text` · 26 % | `#e9e9ed@26%` | `#292b31@26%` | inactive onboarding page dots |
| `grabber` | `text` · 25 % | `#e9e9ed@25%` | `#292b31@25%` | sheet grabber 38×5 |
| `dashedStroke` | `text` · 30 % | `#e9e9ed@30%` | `#292b31@30%` | dashed outlines in 1.2 illustration |
| `dashedDots` | `text` · 34 % | `#e9e9ed@34%` | `#292b31@34%` | dotted skeleton lines in 1.2 illustration |
| `accentGlow` | `accent` · 70 % | `#9184d9@70%` | `#796cbf@70%` | outer glow of primary buttons, mic button, filled code dots |
| `accentGlowSoft` | `accent` · 60 % | `#9184d9@60%` | `#796cbf@60%` | Face ID disc glow, QR card glow |
| `accentGlowSeal` | `accent` · 55 % | `#9184d9@55%` | `#796cbf@55%` | verified seal glow (6.3) |
| `accentGlowPacket` | `accent` · 50 % | `#9184d9@50%` | `#796cbf@50%` | light packet glow on the line |
| `accentGlowLive` | `accent` · 80 % | `#9184d9@80%` | `#796cbf@80%` | live-location dot glow |
| `accentLineMid` | `accent` · 30 % | `#9184d9@30%` | `#796cbf@30%` | mid stop of the line gradient |
| `dangerTint` | `danger` · 14 % | `#f2716a@14%` | `#c53637@14%` | urgent ghost pill fill, danger icon tile |
| `dangerStroke` | `danger` · 45 % | `#f2716a@45%` | `#c53637@45%` | urgent banners / chips inset stroke |
| `dangerStrokeSoft` | `danger` · 40 % | `#f2716a@40%` | `#c53637@40%` | “İletilemedi ne demek?” note stroke (5.4) |
| `bubbleIncoming` | `surface` | `#232532` | `#e4e7f5` | incoming bubble fill |
| `bubbleOutgoing` | `accent800` | `#423a6a` | `#e7e5fe` | outgoing bubble fill (text = accent100) |
| `onBubbleOutgoing` | `accent100` | `#f5f4ff` | `#2b2741` | outgoing bubble text |
| `quoteFill` | `bg` · 32 % | `#161826@32%` | `#f3f5fe@32%` | reply quote block inside an outgoing bubble |
| `fileIconFill` | `bg` · 35 % | `#161826@35%` | `#f3f5fe@35%` | file-type tile inside a bubble |
| `inlineButtonFill` | `bg` · 55 % | `#161826@55%` | `#f3f5fe@55%` | “Durdur” pill inside the live-location bubble |
| `headerFill` | `bg` · 80 % | `#161826@80%` | `#f3f5fe@80%` | translucent chat header (with 20pt backdrop blur, saturate 140%) |
| `coverTint` | `bg` · 45 % | `#161826@45%` | `#f3f5fe@45%` | app-switcher privacy cover tint |
| `floatingFill` | `surface` · 92 % | `#232532@92%` | `#e4e7f5@92%` | floating banners (urgent ghost, screenshot warning) |
| `menuFill` | `surface` · 94 % | `#232532@94%` | `#e4e7f5@94%` | context menu (5.3) |
| `sheetBackdrop` | `color-mix(in srgb, var(--color-bg) 55%, #05060c)` | `#0e101a` | `#888991` | dimmed backdrop behind bottom sheets (4.2, 5.4, 5.5) |

## 3 · Colour — fixed (theme-independent)

| token | value | used for |
|---|---|---|
| `toggleKnob` | `#f5f4ff` | switch knob (both themes) |
| `onAccentInverse` | `bg` | text/icons on solid accent fills (badge numbers, checkbox tick) use the bg token |
| `qrPaper` | `#f3f5fe` | QR card background (6.1); 6.2 uses #f3f5fe@88% |
| `qrInk` | `#292b31` | QR modules |
| `scrimBase` | `#05060c` | mixed 45% into bg for sheet backdrops |
| `lockOverlay` | `#06070e@55%` | overlay over the dimmed lock screen behind the 3.3 sheet |
| `actionsOverlay` | `#06070e@35%` | overlay over the blurred chat behind the 5.3 menu |
| `sheetShadow` | `#000000@35%` | 3.3 floating sheet shadow 0 -12 40 |
| `cameraBg` | `#0c0d14` | 6.2 scanner background; radial #2a2d3e→#0c0d14 vignette, 6pt dot grid #e9e9ed@8% |
| `mediaViewerBg` | `#0d0e19` | 5.6 view-once viewer background (photo full-bleed on top) |
| `switcherBg` | `#0b0c14` | 3.4 home-screen/app-switcher mock (system UI, not drawn by the app) |
| `onMedia` | `#f5f4ff` | text/icons over photos & camera (5.6, 6.2), video play glyph; muted variants at 72–80% |
| `onMediaGlass` | `#0c0d16@40–55%` | round buttons / pills over media (rgba(12,13,22,.4/.45/.55)) |
| `onCameraGlass` | `#f5f4ff@12%` | round buttons / pill over the camera (6.2) |
| `mediaTopShade` | `linear #05060c@70% → transparent, 190pt` | 5.6 top gradient |
| `mediaBottomShade` | `linear transparent → #05060c@80%, 220pt` | 5.6 bottom gradient |
| `scanCorner` | `#d2cefd` | QR viewfinder corners + scan line (6.2) |
| `scanGlow` | `#9184d9@70%` | scan line glow 0 0 18 3 |
| `stickerRing` | `#f5f4ff` | 4pt ring around sticker (5.2) |
| `appIconGradient` | `radial(90% 90% at 30% 15%) #423a6a → #161826 72%` | 3.4 app icon mock |

## 4 · Gradients & patterns

| name | definition (token form) | where |
|---|---|---|
| `lineGradient` | linear 90°: `accent400` → `accent·30%` at 50 % → `accent400` | the bar of the line mark (4.1 header, 6.1, 6.3) |
| `linePacket` | 44×4 capsule, linear 90°: transparent → `accent200` → transparent, glow `0 0 12px 2px accent·50%` | packets travelling on the line |
| `composerFade` | linear to bottom: transparent → `bg` at 30 % | composer background (5.x) |
| `mediaTopShade` / `mediaBottomShade` | `#05060c@70%` → transparent (190 pt) / transparent → `#05060c@80%` (220 pt) | 5.6 viewer |
| `cameraVignette` | radial 60 %×45 % at 50 %/45 %: `#2a2d3e` → `#0c0d14` at 80 % | 6.2 (design stand-in for the camera) |
| `dotGrid` | dots r 1 (edge to 1.6) every 18×18, `text·16%` (1.1) / `text·14%` (3.1, 6.3), radial mask (see inventory C24) | backdrops |
| `dotRule` | dots r 0.75 (edge 1.1) every 5 pt, 2 pt high, `text·22%` (`text·20%` under chat headers) | list separators |
| `dateRule` | dots r 0.8 (edge 1.2) every 5 pt, `text·28%` | date separator (4.4) |
| `timelineRule` | vertical dots r 0.9 (edge 1.3) every 5 pt, 2 pt wide, `text·32%` | 5.4 |
| `progressDots` | dots r 2.5 (edge 3) every 12 pt, 8 pt high; track `text·22%`, lit `accent400` | 1.3 hour meter, 7.3 restore |
| `strengthDots` | dots r 2 (edge 2.5) every 8 pt, 6 pt high; track `text·25%`, lit `online` | 7.2 password strength |
| `skeletonDots` | dots r 2 (edge 2.5) every 8 pt, 6 pt high, `text·34%` | 1.2 illustration |
| `dashedOutline` | 1.5 pt dashed `text·30%` (r22 card, r10 square) | 1.2 illustration |
| `viewOnceRing` | 1.5 pt dashed `accent300` circle with “1” | 1.3, 5.2 |

Dots are anti-aliased discs: solid to radius r, fading to transparent at the “edge” radius. Draw with `Canvas`/`Path` (not dashed strokes) so the pitch and phase match: the first dot centre sits at pitch/2 from the start.

## 5 · Typography

### Families

| family | role | weights | metrics (UPM · asc · desc · natural line) | notes |
|---|---|---|---|---|
| Doto | brand moments, titles, counters (never body text) | 800, 900 | 1000 · 950 · -250 · 1.2 | Google Fonts css2 “Doto:ROND,wght@0..100,500..900” (variable; axes ROND 0–100, wght 100–900, default wght 900) |
| Martian Mono | labels, usernames, codes, counters, timestamps in pills | 400, 500 | 1000 · 1000 · -200 · 1.2 | Google Fonts css2 “Martian Mono:wght@400;500” — the served instance is “Martian Mono SemiExpanded” (default width). Bundle the same instance or the variable font pinned to the same width so advances match (≈0.70 em per glyph at 11 pt: “1 / 3” = 38.5 pt wide). |
| Inter | all interface text; also the trailing “.” of Doto headlines (Inter 700) | 400, 500, 600, 700 | 2048 · 1984 · -494 · 1.21 | Google Fonts css2 “Inter:wght@400;500;600;700” (Inter 4, text optical size — not “Inter Display”) |
| SF Pro | only the system status bar (drawn by iOS, not the app) | — | — | system |

* **Doto** is a variable font with a `ROND` (roundness) axis: always set `ROND = 100` (`font-variation-settings: "ROND" 100`) and `wght` 800/900. In Core Text use the variation attribute with axis tags `ROND` = 0x524F4E44 and `wght` = 0x77676874. Without ROND = 100 the dots render square — wrong.
* A Doto headline that ends with “.” sets the full stop in **Inter 700** at the same size (`displayPunctuation`) — this is deliberate in every Doto title (1.1, 1.2, 1.3, 2.3, 3.3, 6.3).
* **Martian Mono** is used at the instance Google Fonts serves by default (named “Martian Mono SemiExpanded”, ≈0.70 em advance). Bundle exactly that instance (or the variable font pinned to the same `wdth`) — a narrower instance changes every measured width.
* **Inter** = Inter 4 text cut (opsz 14 / “Inter”, not “Inter Display”).
* Default line-height for Inter/Mono text is **1.55 × size** (Nocturne body). Doto styles mostly use 1.0–1.04.
* **Line-height in SwiftUI.** CSS line-height L, size S, natural height N = S × natural ratio (Inter 1.21, Mono 1.2, Doto 1.2): use `.lineSpacing(L − N)` for multi-line text and add `(L − N)/2` padding above the first and below the last line so the block height equals L × lines. For L < N (Doto at 1.0) wrap the text in a frame of height L × lines; glyphs may overflow the frame vertically exactly as in CSS.
* Dynamic Type: the design is fixed-size; support Dynamic Type for body/caption/row styles by scaling from these base sizes with `UIFontMetrics` relative to `.body` (cap at accessibility XL for list rows). Display (Doto) styles stay fixed.
* Numbers that tick (`HH:MM:SS`, percentages, times in lists) use monospaced digits (`tnum`): `.monospacedDigit()` for Inter; Martian Mono and Doto are monospaced already.

### Styles (every text in the design maps to exactly one)

| style | family | weight | size | line height | ratio | tracking | case | tnum | used on | example |
|---|---|---|---|---|---|---|---|---|---|---|
| `brandWordmark` | Doto (ROND 100) | 900 | 20 | 31 | 1.55 | 0 |  |  | 1.1, 1.2, 1.3 | us2us |
| `monoMeta` | Martian Mono | 400 | 11 | 17.05 | 1.55 | 0 |  |  | 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 3.4, 4.1, 4.2, 5.4, 5.6, 6.1, 6.3, 7.3, 8.1, 9.1 | 1 / 3 |
| `displayHero` | Doto (ROND 100) | 900 | 46 | 46 | 1 | -0.46 |  |  | 1.1 | İki uç. Bir hat. |
| `displayPunctuation` | Inter | 700 | = Doto run | = Doto run | | = Doto run | | | 1.1, 1.2, 1.3, 2.3, 3.3, 6.3 | “.” |
| `bodyL` | Inter | 400 | 17 | 25.5 | 1.5 | 0 |  |  | 1.1 | us2us iki kişi arasında şifreli bi |
| `button` | Inter | 500 | 17 | 26.35 | 1.55 | 0 |  |  | 1.1, 1.2, 1.3, 2.1, 2.3, 3.1, 3.3, 4.2, 5.5, 6.1, 6.3, 7.2, 7.3 | Başla |
| `rowTitle` | Inter | 400 | 16 | 24.8 | 1.55 | 0 |  |  | 1.1, 3.2, 4.2, 4.4, 5.1, 5.2, 5.3, 6.1, 7.1, 7.2, 8.2, 8.3, 9.2 | Yedekten geri yükle |
| `displayClock` | Doto (ROND 100) | 800 | 76 | 76 | 1 | 0 |  |  | 1.2 | 09:41 |
| `label15` | Inter | 400 | 15 | 23.25 | 1.55 | 0 |  |  | 1.2, 6.2, 7.1 | Perşembe, 8 Ekim |
| `monoTag` | Martian Mono | 400 | 10.5 | 16.275 | 1.55 | 0 |  |  | 1.2, 4.1, 4.3, 5.2, 8.1, 9.1 | bildirim yok |
| `displayTitleL` | Doto (ROND 100) | 900 | 40 | 40.8 | 1.02 | 0 |  |  | 1.2, 1.3 | Bildirim yok. Bilerek. |
| `body` | Inter | 400 | 16 | 24 | 1.5 | 0 |  |  | 1.2, 1.3, 2.1, 2.2, 2.3, 6.3 | Bildirimler Apple'ın sunucularında |
| `legendEmphasis` | Inter | 500 | 14 | 18.9 | 1.35 | 0 |  |  | 1.2 | Gönderildi |
| `legend` | Inter | 400 | 14 | 18.9 | 1.35 | 0 |  |  | 1.2, 1.3 | · alıcı açınca iletilecek |
| `monoKicker` | Martian Mono | 400 | 11 | 17.05 | 1.55 | 0.88 | uppercase |  | 1.3, 2.1, 5.5 | Ghost mod · 24 saat |
| `displayCountdownL` | Doto (ROND 100) | 900 | 64 | 64 | 1 | 0 |  |  | 1.3 | 23:41:07 |
| `monoMicro` | Martian Mono | 400 | 9 | 12.15 | 1.35 | 0 |  |  | 1.3 | 1 |
| `displayTitleM` | Doto (ROND 100) | 900 | 36 | 36.72 | 1.02 | 0 |  |  | 2.1, 2.2 | Kullanıcı adını seç |
| `monoInputL` | Martian Mono | 400 | 19 | 29.45 | 1.55 | 0 |  |  | 2.1 | @ |
| `caption` | Inter | 400 | 13 | 20.15 | 1.55 | 0 |  | ✓ | 2.1, 2.3, 4.1, 4.2, 5.6, 7.1, 7.2, 7.3, 8.1, 9.1 | Kullanılabilir |
| `monoCounter` | Martian Mono | 400 | 11.5 | 17.825 | 1.55 | 0 |  |  | 2.1 | 7 / 24 |
| `bullet` | Inter | 400 | 13.5 | 18.9 | 1.4 | 0 |  |  | 2.1, 5.1 | 3–24 karakter |
| `monoMatch` | Martian Mono | 400 | 14 | 21.7 | 1.55 | 0 |  |  | 2.1 | @selin |
| `monoMatchStatus` | Martian Mono | 400 | 12.5 | 19.375 | 1.55 | 0 |  |  | 2.1 | bulunamaz |
| `captionTight` | Inter | 400 | 13 | 18.85 | 1.45 | 0 |  |  | 2.1, 5.4, 7.2, 7.3 | Arama öneri ya da otomatik tamamla |
| `keypadDigit` | Inter | 400 | 32 | 32 | 1 | 0 |  |  | 2.2, 3.2, 8.3 | 1 |
| `displayTitleS` | Doto (ROND 100) | 900 | 34 | 35.36 | 1.04 | 0 |  |  | 2.3 | Unutursan geri dönüşü yok. |
| `rowTitleS` | Inter | 400 | 15.5 | 24.025 | 1.55 | 0 |  |  | 2.3 | Kod sıfırlama yok |
| `calloutL` | Inter | 400 | 14.5 | 21.025 | 1.45 | 0 |  |  | 2.3, 3.2, 8.3 | Anladım: kodu unutursam verilerim  |
| `brandWordmarkL` | Doto (ROND 100) | 900 | 24 | 37.2 | 1.55 | 0 |  |  | 3.1, 3.4 | us2us |
| `lockStatus` | Inter | 500 | 19 | 29.45 | 1.55 | 0 |  |  | 3.1 | Face ID ile açılıyor |
| `preview` | Inter | 400 | 14.5 | 22.475 | 1.55 | 0 |  |  | 3.1, 4.1, 4.3, 8.1, 9.1 | Tanımazsa gizli kodun istenir |
| `displayTitleXS` | Doto (ROND 100) | 900 | 30 | 30 | 1 | 0 |  |  | 3.2, 3.3, 8.3 | Gizli kodunu gir |
| `displaySheetTitle` | Doto (ROND 100) | 900 | 30 | 30.9 | 1.03 | 0 |  |  | 3.3 | Kod kurtarılamaz. |
| `sheetBody` | Inter | 400 | 15.5 | 23.25 | 1.5 | 0 |  |  | 3.3 | Gizli kodun yalnızca bu cihazda, ş |
| `rowTitleEmphasis` | Inter | 500 | 16 | 24.8 | 1.55 | 0 |  |  | 3.3, 4.1, 4.4, 5.4, 5.5, 8.1, 9.1 | Yedekten geri yükle |
| `captionTighter` | Inter | 400 | 13 | 18.2 | 1.4 | 0 |  |  | 3.3, 5.4, 5.5, 5.6 | Şifreli yedeğin ve parolası varsa  |
| `switcherAppName` | Inter | 600 | 15 | 23.25 | 1.55 | 0 |  |  | 3.4 | us2us |
| `avatarInitialXS` | Martian Mono | 500 | 14 | 14 | 1 | 0 |  |  | 4.1, 4.4, 5.1, 5.2, 5.3, 8.1, 8.2, 9.1, 9.2 | S |
| `displayTitle` | Doto (ROND 100) | 900 | 38 | 38 | 1 | 0 |  |  | 4.1, 7.1, 8.1, 9.1 | Sohbetler |
| `badge` | Inter | 600 | 12.5 | 19.375 | 1.55 | 0 |  |  | 4.1, 8.1, 9.1 | 2 |
| `avatarInitialL` | Martian Mono | 500 | 19 | 19 | 1 | 0 |  |  | 4.1, 4.2, 8.1, 9.1 | D |
| `monoUsername` | Martian Mono | 500 | 15 | 23.25 | 1.55 | 0 |  |  | 4.1, 4.3, 4.4, 5.1, 5.2, 5.3, 5.6, 8.1, 8.2, 9.1, 9.2 | deniz |
| `navTitle` | Inter | 600 | 17 | 26.35 | 1.55 | 0 |  |  | 4.2, 4.3, 5.4, 6.1, 6.2, 7.2, 7.3 | Yeni sohbet |
| `monoInput` | Martian Mono | 400 | 18 | 27.9 | 1.55 | 0 |  |  | 4.2 | @ |
| `monoUsernameL` | Martian Mono | 500 | 16 | 24.8 | 1.55 | 0 |  |  | 4.2, 7.1 | deniz |
| `callout` | Inter | 400 | 13.5 | 19.575 | 1.45 | 0 |  |  | 4.2, 4.3, 4.4 | deniz seni kabul edene kadar ilk m |
| `captionCompact` | Inter | 400 | 13 | 17.55 | 1.35 | 0 |  |  | 4.2, 7.2 | Yan yanaysanız ekler ve aynı anda  |
| `avatarInitialS44` | Martian Mono | 500 | 16 | 16 | 1 | 0 |  |  | 4.3 | K |
| `meta` | Inter | 400 | 12.5 | 19.375 | 1.55 | 0 |  |  | 4.3, 4.4, 5.1, 5.2, 5.3, 5.6, 7.2, 8.2, 9.2 | 2 sa |
| `requestSnippet` | Inter | 400 | 14.5 | 20.3 | 1.4 | 0 |  |  | 4.3 | Merhaba! Dünkü meetup’taki sunumun |
| `buttonS` | Inter | 500 | 14.5 | 22.475 | 1.55 | 0 |  |  | 4.3 | Kabul et |
| `avatarInitialXL` | Martian Mono | 500 | 23 | 23 | 1 | 0 |  |  | 4.4 | K |
| `monoUsernameXL` | Martian Mono | 500 | 17 | 26.35 | 1.55 | 0 |  |  | 4.4 | kuzey_dev |
| `monoSectionLabel` | Martian Mono | 400 | 10.5 | 16.275 | 1.55 | 1.05 | uppercase |  | 4.4, 5.5, 7.1, 7.2 | Bugün 16:12 |
| `bubble` | Inter | 400 | 16 | 22.08 | 1.38 | 0 |  |  | 4.4, 5.1, 5.3, 5.4, 8.2, 9.2 | Merhaba! Dünkü meetup’taki sunumun |
| `monoLink` | Martian Mono | 400 | 13 | 17.94 | 1.38 | 0 |  |  | 4.4, 5.2 | slides.kuzey.dev/meetup |
| `metaInBubble` | Inter | 400 | 12.5 | 17.25 | 1.38 | 0 |  |  | 4.4, 5.2 | Bağlantı, kabul edene kadar açılma |
| `bubbleTime` | Inter | 400 | 11.5 | 11.5 | 1 | 0 |  | ✓ | 4.4, 5.1, 5.2, 5.3, 5.4, 8.2, 9.2 | 16:13 |
| `monoCountdownChip` | Martian Mono | 400 | 12 | 18.6 | 1.55 | 0 |  | ✓ | 5.1, 5.2, 7.3, 8.2, 9.2 | 23:41:07 |
| `monoSystemPill` | Martian Mono | 400 | 11 | 15.4 | 1.4 | 0 |  |  | 5.1, 8.2, 9.2 | Ghost mod açık · 24 saat |
| `emojiReactionChip` | Inter | 400 | 14 | 14 | 1 | 0 |  |  | 5.1, 8.2, 9.2 | ❤️ |
| `quoteAuthor` | Inter | 600 | 12.5 | 17.25 | 1.38 | 0 |  |  | 5.1, 8.2, 9.2 | deniz |
| `quoteBody` | Inter | 400 | 13.5 | 18.63 | 1.38 | 0 |  |  | 5.1, 8.2, 9.2 | Fotoğraf |
| `monoVoiceDuration` | Martian Mono | 400 | 11 | 15.18 | 1.38 | 0 |  |  | 5.1, 8.2, 9.2 | 0:42 |
| `monoTimerInBubble` | Martian Mono | 400 | 10.5 | 10.5 | 1 | 0 |  |  | 5.1, 5.3, 8.2, 9.2 | 5 dk |
| `statusLine` | Inter | 400 | 12 | 18.6 | 1.55 | 0 |  |  | 5.1, 8.2, 9.2 | Gönderildi · Alıcı uygulamayı açın |
| `statusLineEmphasis` | Inter | 500 | 12 | 18.6 | 1.55 | 0 |  |  | 5.1, 8.2, 9.2 | Gönderildi |
| `bubbleTitleS` | Inter | 500 | 15 | 20.7 | 1.38 | 0 |  |  | 5.2 | kira-sozlesmesi.pdf |
| `monoFileMeta` | Martian Mono | 400 | 10.5 | 14.49 | 1.38 | 0 |  |  | 5.2 | 2,1 MB · PDF · 4 sayfa |
| `inlineButton` | Inter | 500 | 13.5 | 18.63 | 1.38 | 0 |  |  | 5.2 | Durdur |
| `bubbleTombstone` | Inter | 400 | 14.5 | 20.01 | 1.38 | 0 |  |  | 5.2 | Bu mesaj herkesten silindi |
| `bubbleTitle` | Inter | 500 | 15.5 | 21.39 | 1.38 | 0 |  |  | 5.2 | Fotoğraf |
| `displaySticker` | Doto (ROND 100) | 900 | 32 | 28.8 | 0.9 | 0 |  |  | 5.2 | tamam! |
| `emojiReactionPicker` | Inter | 400 | 24 | 24 | 1 | 0 |  |  | 5.3 | ❤️ |
| `menuHint` | Inter | 400 | 11.5 | 17.825 | 1.55 | 0 |  |  | 5.3 | Süreli mesajda kapalı |
| `noteTitle` | Inter | 500 | 15.5 | 24.025 | 1.55 | 0 |  |  | 5.4, 5.6 | İletilemedi ne demek? |
| `displayCountdownM` | Doto (ROND 100) | 900 | 56 | 56 | 1 | 0 |  |  | 5.5 | 23:41:07 |
| `sheetCaption` | Inter | 400 | 14 | 20.3 | 1.45 | 0 |  |  | 5.5 | Sohbet bu kadar sessiz kalırsa iki |
| `chip` | Inter | 400 | 14 | 21.7 | 1.55 | 0 |  |  | 5.5 | 1 sa |
| `label15Emphasis` | Inter | 500 | 15 | 23.25 | 1.55 | 0 |  |  | 5.6 | Kapatınca silinir |
| `avatarInitialM48` | Martian Mono | 500 | 17 | 17 | 1 | 0 |  |  | 6.1, 6.3 | S |
| `monoSafetyDigits` | Martian Mono | 400 | 15 | 23.25 | 1.55 | 0 |  |  | 6.1 | 08223 |
| `cameraHint` | Inter | 400 | 15 | 21.75 | 1.45 | 0 |  |  | 6.2 | deniz’in telefonundaki güvenlik nu |
| `displayTitleLBalanced` | Doto (ROND 100) | 900 | 40 | 41.2 | 1.03 | 0 |  |  | 6.3 | Hat doğrulandı. |
| `avatarInitialM50` | Martian Mono | 500 | 18 | 18 | 1 | 0 |  |  | 7.1 | S |
| `monoFileName` | Martian Mono | 500 | 12.5 | 19.375 | 1.55 | 0 |  |  | 7.2, 7.3 | us2us-yedek-2026-10-08.u2u |
| `footnote` | Inter | 400 | 12.5 | 17.5 | 1.4 | 0 |  |  | 7.2 | Tek görüntülemelik medya ve süreli |
| `displayPercent` | Doto (ROND 100) | 900 | 76 | 76 | 1 | 0 |  |  | 7.3 | 62% |
| `displayPercentSign` | Doto (ROND 100) | 900 | 42 | 42 | 1 | 0 |  |  | 7.3 | % |
| `monoBannerCount` | Martian Mono | 400 | 13.5 | 18.9 | 1.4 | 0 |  |  | 5.1 | 43 dk |

Tracking values are in points (CSS px): `displayHero` −0.46 = −0.01 em; `monoKicker` 0.88 = 0.08 em; `monoSectionLabel` 1.05 = 0.1 em. Pass them to `.tracking()`.

## 6 · Spacing

The iOS design uses literal point values (the Nocturne --space-* 0.7× scale is not used by these screens). These are all the paddings/gaps/margins that occur.

All values used: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 14, 16, 18, 20, 22, 24, 26, 28, 30, 36, 40, 44, 56 pt.

| name | value | meaning |
|---|---|---|
| `screenGutterL` | 24 | text blocks on onboarding/setup/lock (24) |
| `screenGutter` | 20 | CTA buttons, title on lists (20) |
| `screenGutterS` | 16 | cards, headers, lists (16) |
| `chatGutter` | 14 | bubble column side padding |
| `chatComposerGutter` | 12 | composer side padding |
| `headerTopButtons` | 56 | y of 44 pt header buttons |
| `headerTopChat` | 54 | top padding of the translucent chat header |
| `onboardingHeaderTop` | 66 | y of the onboarding wordmark row |
| `ctaBottom` | 44 | bottom padding under the primary CTA (setup, verify, backup) |
| `ctaBottomOnboarding` | 40 | bottom padding under onboarding CTAs / text buttons |
| `ctaBottomFaceID` | 44 | bottom padding under 3.1 button |
| `bubbleGap` | 6 | gap between bubbles |
| `rowGap` | 13 | avatar ↔ text gap in rows |
| `sectionGap` | 18 | gap between settings sections / cards |
| `sectionLabelGap` | 7 | section label ↔ card |
| `titleToBodyGap` | 12 | title ↔ paragraph |
| `iconTextGap` | 10 | icon ↔ text in legend/feature lists |
| `listRowInsetLeading` | 16 | chat row leading padding |
| `listRowInsetTrailing` | 18 | chat row trailing padding |
| `listSeparatorInset` | 81 | chat list separator inset (= text column) |
| `groupedSeparatorInset` | 57 | grouped list separator inset |

## 7 · Radii

| name | value |
|---|---|
| `pill` | height / 2 (all circles & capsules: avatars, icon buttons, keypad keys, toggles, chips with r=h/2) |
| `button` | 16 |
| `buttonS` | 12 |
| `buttonSheet` | 14 |
| `field` | 16 |
| `composerField` | 22 |
| `card` | 18 |
| `cardL` | 20 |
| `group` | 18 |
| `iconTile` | 8 |
| `iconTileL` | 10 |
| `iconTileXL` | 12 |
| `checkbox` | 7 |
| `bubble` | 20 |
| `bubbleTail` | 6 |
| `bubbleMedia` | 15 |
| `bubbleMediaS` | 14 |
| `bubbleQuote` | 14 |
| `quoteThumb` | 8 |
| `chip` | 12 |
| `pillTag` | 11 |
| `systemPill` | 14 |
| `countdownChip` | 17 |
| `reactionBar` | 28 |
| `sheet` | 38 |
| `sheetFloating` | 42 |
| `sheetBar` | 30 |
| `sticker` | 22 |
| `qrCard` | 26 |
| `qrCardScan` | 16 |
| `scanCorner` | 18 |
| `menu` | 18 |
| `pageDot` | 3 |
| `caret` | 1 |
| `grabber` | 3 |

All radii are continuous-corner rounded rectangles in SwiftUI (`RoundedRectangle(cornerRadius:style: .continuous)`) except circles/capsules. Bubble tails are per-corner radii (`UnevenRoundedRectangle`).

## 8 · Component sizes

```json
{
  "screen": {
    "w": 402,
    "h": 874,
    "device": "iPhone 17 Pro (design frame: iPhone 16 Pro)"
  },
  "statusBarHeightInDesign": 62,
  "homeIndicatorArea": 34,
  "buttonPrimaryHeight": 54,
  "buttonInCardHeight": 48,
  "buttonBarHeight": 50,
  "buttonSmallHeight": 40,
  "textButtonHeight": 44,
  "textButtonHeightS": 46,
  "iconButton": 44,
  "iconButtonChat": 40,
  "iconButtonSheet": 36,
  "composerHeight": 44,
  "keypadKey": 78,
  "keypadColumnGap": 26,
  "keypadRowGap": 16,
  "codeDot": 15,
  "codeDotGap": 20,
  "toggle": {
    "w": 51,
    "h": 31,
    "knob": 27,
    "padding": 2
  },
  "checkbox": 24,
  "avatar": {
    "listRow": 52,
    "profile": 64,
    "settings": 50,
    "verify": 48,
    "request": 44,
    "header": 38,
    "chatsSelf": 40
  },
  "onlineDot": {
    "list": 15,
    "header": 11,
    "inline": 7
  },
  "iconTile": {
    "settings": 30,
    "requestsRow": 42,
    "sheetOption": 40,
    "file": "46×56"
  },
  "field": {
    "username": 60,
    "search": 56,
    "password": 56
  },
  "grabber": {
    "w": 38,
    "h": 5
  },
  "faceIdDisc": 116,
  "lockIcon": 52,
  "pageDot": {
    "active": "22×6",
    "inactive": "6×6",
    "gap": 6
  }
}
```

## 9 · Shadows & glows

CSS → SwiftUI: shadow(color, radius: blur/2, x, y). Negative spread (e.g. -8) = draw the glow from a shape inset by |spread| (or reduce radius); inset “0 0 0 Npx” = strokeBorder(N). Ring “0 0 0 Npx bg” = an outer stroke in the background colour (cut-out ring).

| name | CSS (token form) | used for |
|---|---|---|
| `glowButton` | `0 0 32px -8px accent·70%` | primary outline CTA (54 high) |
| `glowButtonAccept` | `0 0 26px -8px accent·70%` | 4.4 “Kabul et” |
| `glowMic` | `0 0 20px -6px accent·70%` | composer mic button |
| `glowField` | `inset 0 0 0 1.5px accent, 0 0 26px -10px accent` | focused text field (2.1, 4.2) |
| `glowFieldPlain` | `inset 0 0 0 1.5px accent` | focused password field (7.2) |
| `glowCodeDot` | `0 0 12px accent·70%` | filled code dot |
| `glowFaceID` | `inset 0 0 0 1px accent800, 0 0 70px -12px accent·60%` | 3.1 Face ID disc (fill accent900) |
| `glowQRCard` | `0 0 0 1px text·10%, 0 20px 50px -20px accent·60%` | 6.1 QR card |
| `glowSeal` | `0 0 0 1px accent800, 0 0 24px accent·55%` | 6.3 verified seal on the line |
| `glowPacket` | `0 0 12px 2px accent·50%` | light packet on the line |
| `glowLiveDot` | `0 0 0 3px bg, 0 0 18px accent·80%` | live-location dot |
| `glowScanLine` | `0 0 18px 3px #9184d9@70%` | 6.2 scan line |
| `hairlineGlass` | `inset 0 0 0 0.5px text·18%` | round glass icon buttons |
| `hairlineField` | `inset 0 0 0 0.5px text·14%` | composer field |
| `ringAccent` | `inset 0 0 0 1px accent800 | accent700` | accent900 tiles/avatars (800 for tiles, 700 for self-avatar) |
| `ringDivider` | `inset 0 0 0 1px divider` | secondary outline buttons, chips |
| `ringSelected` | `inset 0 0 0 1.5px accent` | selected segmented chip (fill accent900, text accent300) |
| `cutoutRing` | `0 0 0 3px bg (list) / 2px bg (header) / 2.5px bg (reaction chip)` | online dot & reaction chip separation ring |
| `toggleKnob` | `0 2px 6px #000@25%` | switch knob |
| `sheetFloating` | `0 -12px 40px #000@35%` | 3.3 floating sheet |
| `bubbleLifted` | `0 16px 40px #000@40%` | 5.3 lifted bubble |
| `floatingBanner` | `inset 0 0 0 1px danger·45%, 0 10px 30px #000@30%` | 5.1 urgent banner |
| `floatingWarning` | `inset 0 0 0 1px danger·45%, 0 14px 40px #000@45%` | 5.6 screenshot warning |
| `sticker` | `0 0 0 4px #f5f4ff, 0 12px 26px #000@35%` | 5.2 sticker |
| `mapPin` | `drop-shadow(0 4px 6px #000@40%)` | map pin glyph |
| `qrScanCard` | `0 20px 40px #000@45%` | 6.2 phone QR mock |
| `shadowSm` | dark: `0 0 0 1px #3f424d` · light: `0 0 0 1px #cfd3e5` |  |
| `shadowMd` | dark: `0 0 0 1px #595d6c, 0 6px 18px #000@55%` · light: `0 0 0 1px #b2b6ca, 0 6px 18px #292b31@10%` | 5.3 reaction bar + action menu |
| `shadowLg` | dark: `0 0 0 1px #9397ab, 0 16px 40px #000@65%` · light: `0 0 0 1px #b2b6ca, 0 16px 40px #292b31@14%` | unused on iOS screens |

## 10 · Blur & translucency

CSS blur(N) ≈ SwiftUI .blur(radius: N) for content blur; backdrop blurs map to a Material-free custom blur of the same radius over the given tint (do not use system materials — they shift hue).

| name | definition | used for |
|---|---|---|
| `header` | backdrop-filter: blur(20px) saturate(140%) over bg·80% | chat / request header (z above messages) |
| `menu` | backdrop-filter: blur(20px) over surface·94% | 5.3 action menu |
| `banner` | backdrop-filter: blur(16px) over surface·92% | 5.1 urgent banner |
| `warning` | backdrop-filter: blur(20px) over surface·92% | 5.6 screenshot warning |
| `mediaButton` | backdrop-filter: blur(8px) over #0c0d16@45% | video play button |
| `contextBackdrop` | filter: blur(14px); opacity .55 + overlay #06070e@35% | 5.3 chat behind the menu |
| `privacyCover` | content filter: blur(9px); opacity .75; tint bg·45% | 3.4 snapshot cover |
| `switcherWallpaper` | filter: blur(12px) | 3.4 mock wallpaper (system) |

Opacity tokens: `disabled` 0.45, `lockBehindSheet` 0.3, `contextBackdropContent` 0.55, `coverContent` 0.75 (disabled controls = 45 % of the whole control).

## 11 · Motion

**Principle.** Design rule: every animation very soft — no bounce, no overshoot, long gentle ease-in-out; nothing snaps. Reduce Motion: stop all infinite/ambient motion and replace movement with short cross-fades.

### From the design (exact)

* **`linePacket`** — keyframes: u2-packet-r / u2-packet-l; duration: 6.0; curve: [0.45, 0, 0.55, 1]; repeat: forever; delays: {"toRight": 0, "toLeft": 3}; timeline: 0–6%: fade in (opacity 0→1) · 0–60%: travel 0→100% of the line (left→right, or right→left) · 54–60%: fade out · 60–100%: idle; element: 44×4 capsule, linear gradient transparent→accent200→transparent, glow 0 0 12 2 accent·50%, centred on the line (margin −22/−2); where: ["4.1/8.1/9.1 header line", "6.1 & 6.3 sen↔deniz line"]; reduceMotion: hidden
* **`pulseRing`** — keyframes: u2-pulse; duration: 2.8; curve: ease-out = cubic-bezier(0, 0, 0.58, 1); repeat: forever; stagger: 1.4; count: 2; from: {"scale": 0.15, "opacity": 0.9}; to: {"scale": 1.0, "opacity": 0}; element: circle 116 (Face ID) with 1.5pt accent400 stroke; on the live-location dot the same ring (116) centred on the dot; where: ["3.1 Face ID disc", "5.2 live-location dot"]; reduceMotion: hidden (“Hareketi azalt açıksa halkalar durur”)
* **`countdownTick`** — interval: 1.0; where: ["1.3", "5.1 header chip", "5.5", "4.1 pills (minute granularity)"]; format: HH:MM:SS (tabular)
* **`scene3D`** — where: 1.1; sway: world.rotation.y = −0.42 + 0.07·sin(0.3·t) (period ≈ 20.9 s); rotation.x = 0.03·sin(0.21·t) (≈ 29.9 s); pulses: 4 forward pulses, speed 0.17 line/s (≈ 5.9 s per crossing); 4 backward at 0.14 (≈ 7.1 s), amplitude 0.8; lattice: tube lattice spins about the line at 0.25 rad/s; scan hex-ring sweeps every 3.125 s; dust: 420 motes drift upward 0.05 u/s; reduceMotion: design note: show a still frame (scene time frozen)

### Proposed (to satisfy “very soft”, consistent with the design curve)

* **`curveSoft`** — bezier: [0.45, 0, 0.55, 1]; swiftUI: .timingCurve(0.45, 0, 0.55, 1, duration: d); note: the design’s own curve — default for all transitions
* **`curveOut`** — bezier: [0, 0, 0.58, 1]; swiftUI: .easeOut(duration: d); note: entrances, ring pulses
* **`springSoft`** — swiftUI: .spring(response: 0.55, dampingFraction: 1.0); note: interactive moves (sheets following the finger, toggles, reaction bar) — critically damped, zero bounce
* **`durations`** — press: 0.16; micro: 0.24; state: 0.36; screen: 0.5; sheet: 0.55; ambient: 0.8
* **`pressFeedback`** — scale 0.97 + fill brighten (text·9% → text·14% / accent tint 12%) over 0.16 s curveSoft, release 0.24 s
* **`screenPush`** — cross-fade + 16 pt horizontal drift, 0.5 s curveSoft (no parallax, no bounce)
* **`sheet`** — system sheet presentation or custom: backdrop fades 0→scrim over 0.55 s while the sheet rises with springSoft
* **`codeDotFill`** — dot fills accent400 with scale 0.6→1 and glow fade-in, 0.24 s curveSoft
* **`codeWrong`** — dots tint to danger over 0.24 s + soft horizontal shake (±6 pt, 3 damped cycles, 0.45 s), message fades in 0.36 s; dots clear after 0.8 s
* **`setupAdvance`** — 6th digit → hold 0.25 s → cross-fade to the confirm step (2.2 note: “6 hane dolunca kendiliğinden ilerler”)
* **`ghostUrgentFlip`** — pill/chip colours cross-fade to danger over 0.8 s when < 1 h remains
* **`statusChange`** — message status icon cross-fades (0.36 s) sent → delivered → read
* **`newMessage`** — bubble fades in + rises 8 pt, 0.36 s curveOut
* **`lockCover`** — privacy cover appears instantly on resign-active (no animation — must hit the snapshot); unlock dissolves it over 0.36 s

Reduce Motion (`accessibilityReduceMotion`): `linePacket` and `pulseRing` hidden, 3D scene frozen on a still frame, shakes removed, pushes become 0.3 s cross-fades, countdowns keep ticking (they are information).

## 12 · 3D scene (1.1)

```json
{
  "engine": "design: three.js r160; iOS: SceneKit or RealityKit (design note). Canvas 402×330 pt at y = 84 on 1.1, transparent background over the bg + dot grid.",
  "camera": {
    "type": "perspective",
    "fovYdeg": 34,
    "near": 0.05,
    "far": 100,
    "position": [
      0,
      0.55,
      7.8
    ],
    "lookAt": [
      0,
      0,
      0
    ],
    "aspect": "402/330"
  },
  "fixedState": {
    "wx": 0,
    "cx": 0,
    "cy": 0.55,
    "cz": 7.8,
    "tx": 0,
    "ty": 0,
    "tz": 0,
    "ry": -0.42,
    "L": 0.95,
    "flow": 1,
    "back": 0.8,
    "tunnel": 0.4,
    "seal": 0,
    "story": 0,
    "dim": 0
  },
  "cups": {
    "profile": "lathe (72 segments) through (0.001,0) (0.36,0) (0.52,1.1) (0.495,1.1) (0.335,0.025) (0.001,0.025)",
    "count": 2,
    "placement": "cup A at x = −L rotated +90° about z (opens to −x); cup B at x = +L rotated −90° (opens to +x); bottoms face each other",
    "material": "PBR, roughness 0.62, metalness 0.08, double-sided",
    "rim": "torus R 0.5075, tube 0.018, unlit edge colour",
    "wire": "18 meridians + rings at 0, 0.33H, 0.66H, unlit edge colour, opacity wireOp"
  },
  "line": "cylinder r 0.009 along x, length 2L, unlit line colour; glow: 180 soft dots size 0.24, opacity glowOp, additive (dark)",
  "pulses": "4 forward + 4 backward heads (soft sprite 0.2) with 4-sprite trails (0.16→0.10, spacing 0.014 of the line, opacity amp·(0.55 − 0.12j)); amp = smoothstep(0,.08,t)·(1 − smoothstep(.9,1,t)); colour core, additive",
  "lattice": "hexagonal tube r 0.3, 13 rings × 6, scaled (2L·0.78, 0.73, 0.73), opacity tunnel·0.42 = 0.168; scan hex-ring opacity 0.36·sin(πs)",
  "relay": "hex ring r 0.13 at the centre, spin 0.6 rad/s, opacity 0.08",
  "ends": "soft sprite 0.55 at each cup bottom, opacity 0.3",
  "dust": "420 soft points in x∈[−8,8], y∈[−5,5], z∈[−6,3], size 0.045, opacity dustOp, drifting up",
  "lights": {
    "hemisphere": "sky/ground per theme, intensity 1.1",
    "key": "directional at (−3,4,5), intensity 1.7",
    "rim": "directional at (3,1.5,−4), intensity 2.4"
  },
  "palette": {
    "dark": {
      "cup": "#2b2e3e",
      "edge": "#9184d9",
      "line": "#b5abfc",
      "core": "#e7e5fe",
      "glow": "#9184d9",
      "dust": "#9397ab",
      "sky": "#5d5294",
      "ground": "#161826",
      "key": "#e9e9ed",
      "rim": "#9184d9",
      "blending": "additive",
      "wireOp": 0.34,
      "dustOp": 0.5,
      "glowOp": 0.1
    },
    "light": {
      "cup": "#dfe2f0",
      "edge": "#796cbf",
      "line": "#5d5294",
      "core": "#5d5294",
      "glow": "#796cbf",
      "dust": "#75798c",
      "sky": "#f3f5fe",
      "ground": "#b2b6ca",
      "key": "#f3f5fe",
      "rim": "#b5abfc",
      "blending": "normal",
      "wireOp": 0.55,
      "dustOp": 0.32,
      "glowOp": 0.14
    }
  }
}
```

