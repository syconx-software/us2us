# us2us iOS — screen inventory

Source of truth: the **us2us iOS design** (Nocturne design system). Every number below was measured in headless Google Chrome with `getBoundingClientRect` / `getComputedStyle` on the rendered design file — nothing is estimated. The app must look exactly like the reference PNGs in `reference/` and move very softly.

Files that belong to this spec:

| file | what |
|---|---|
| `reference/<id>-<slug>.png` | default dark theme, 1206×2622 px (= 402×874 pt @3x, iPhone 17 Pro), status bar / island / home indicator drawn as the design draws them, square corners |
| `reference/light/…` | the same screens rendered with the canvas switched to the light theme (section 08 shows three of them natively) |
| `reference/urgent/…` | the “Ghost — last hour” variant (canvas Tweak `ghostUrgent`) of 4.1 / 5.1 / 8.1 / 8.2 / 9.1 / 9.2 (+ light variants) |
| `reference/overview.png`, `overview-light.png` | the whole canvas at 1× (with designer notes) |
| `layout/<id>-<slug>.json` | machine-readable element tree of every screen: frame, declared style (token form), computed dark **and** light colours, typography, layout (padding/gap/alignment), shadows, blur, animation |
| `layout/urgent/<id>-<slug>-urgent.json` | the same for the Ghost-urgent variants (dark + light) |
| `tokens.json` / `tokens.md` | colours (dark + light), type styles, spacing, radii, sizes, shadows, blur, motion, 3D scene |
| `icons.md` + `icons/` | the 57 Phosphor glyphs used (regular/fill), plus the custom Face ID glyph |
| `flows.md` | navigation graph, triggers and transitions |

## How to read this document

* **Coordinates** are points on the 402×874 iPhone 17 Pro screen, origin top-left of the *physical* screen (status bar included). `x,y w×h` = frame of the element's border box.
* The design frame assumes a **62 pt status bar** and a **34 pt home-indicator area**. Headers start *inside* the status-bar band (e.g. icon buttons at y = 56 = safeTop − 6). Lay screens out from the physical top with `.ignoresSafeArea()` and add `(safeAreaTop − 62)` / `(safeAreaBottom − 34)` so smaller/larger devices keep the same relationships. The real status bar, Dynamic Island and home indicator are drawn by iOS (status bar style follows the theme: light content on dark, dark content on light).
* **Colour notation** (same as `tokens.json`): `text`, `bg`, `surface`, `accent`, `accent100…900`, `neutral100…900`, `online`, `danger`, `divider` (= `text·16%`). `X·N%` = token X at N % opacity. `mix(bg 55%, #05060c)` = opaque mix. Every screen section ends with a **colour legend** that resolves each expression to dark and light hex.
* **Type notation** in the element tables: `Family weight size/lineHeight` (+ `ls` letter-spacing in pt, `UPPER`, `ROND100` for Doto, `tnum` = monospaced digits, `…` = single line, truncated with ellipsis, `clampN` = max N lines, `center` = centred). `Mono` = Martian Mono. Icons: `ph:<name> (regular|fill)` with the glyph size in pt (the glyph box is size×size).
* **Paint notation**: `fill`, `rN` (corner radius; `a/b/c/d` = TL/TR/BR/BL), `border` (CSS border, drawn inside the frame), `shadow` (CSS box-shadow; `inset 0 0 0 Npx C` = an inner stroke of N pt), `opacity`, `filter`/`backdropFilter`.
* **Layout notation**: `row`/`col` stack, `ai:` cross-axis alignment, `jc:` main-axis distribution, `gap`, `pad T R B L`, `mar T R B L`, `flex 1` (fills remaining space), `abs` (absolutely positioned overlay), `place:center` (centred content), `minH`, `maxW`, `self:` (own alignment in the parent stack).
* Element ids (`r.4.1` …) are the DOM path under the screen root `r`; the same ids are used in `layout/*.json`.
* **Usernames** follow PROTOCOL.md §3.1 on every screen (owner decision, 2026-10-11): 3–24 characters, a letter `a-z` first, then only `a-z`, `0-9` and `_` — no dot (see 2.1). Example usernames in this document are therefore written with `_` (selin_n, mert_k, ece_y, oya_t, kuzey_dev, n_aksoy); the reference PNGs and `layout/*.json` still show the design's dotted examples, which are not valid names.
* Every Inter / Martian Mono text without its own line-height inherits **1.55 × size** (Nocturne body). Single-line labels: give them a frame of exactly that line height so vertical positions match. See `tokens.md › Typography` for the CSS→SwiftUI line-height conversion.
* EN copy: “(09)” = shown in the design's English section 09; otherwise the EN text is a proposal in the same tone. Turkish is the primary language; every string goes into the String Catalog (tr + en). Upper-casing must always pass the locale (`Text(...).textCase(.uppercase)` uses the environment locale; raw `String` conversions must use `uppercased(with: Locale(identifier: "tr_TR"))` — see 9.3).

## Screen index

| id | screen (TR) | EN | reference | layout data |
|---|---|---|---|---|
| 1.1 | Karşılama | Welcome | `reference/1.1-karsilama.png` | `layout/1.1-karsilama.json` |
| 1.2 | Bildirim yok. Bilerek. | No notifications. On purpose. | `reference/1.2-bildirim-yok.png` | `layout/1.2-bildirim-yok.json` |
| 1.3 | Sessizlik siler (Ghost mod) | Silence erases (Ghost mode) | `reference/1.3-sessizlik-siler.png` | `layout/1.3-sessizlik-siler.json` |
| 2.1 | Kullanıcı adı | Username | `reference/2.1-kullanici-adi.png` | `layout/2.1-kullanici-adi.json` |
| 2.2 | Gizli kod | Secret code | `reference/2.2-gizli-kod.png` | `layout/2.2-gizli-kod.json` |
| 2.3 | Geri dönüş yok | No way back | `reference/2.3-geri-donus-yok.png` | `layout/2.3-geri-donus-yok.json` |
| 3.1 | Face ID | Face ID | `reference/3.1-face-id.png` | `layout/3.1-face-id.json` |
| 3.2 | Kod yanlış | Wrong code | `reference/3.2-kod-yanlis.png` | `layout/3.2-kod-yanlis.json` |
| 3.3 | Kodu unuttum | Forgot the code | `reference/3.3-kodu-unuttum.png` | `layout/3.3-kodu-unuttum.json` |
| 3.4 | Uygulama değiştirici | App switcher | `reference/3.4-uygulama-degistirici.png` | `layout/3.4-uygulama-degistirici.json` |
| 4.1 | Sohbet listesi | Chat list | `reference/4.1-sohbet-listesi.png` | `layout/4.1-sohbet-listesi.json` |
| 4.2 | Yeni sohbet | New chat | `reference/4.2-yeni-sohbet.png` | `layout/4.2-yeni-sohbet.json` |
| 4.3 | Mesaj istekleri | Message requests | `reference/4.3-mesaj-istekleri.png` | `layout/4.3-mesaj-istekleri.json` |
| 4.4 | İstek önizleme | Request preview | `reference/4.4-istek-onizleme.png` | `layout/4.4-istek-onizleme.json` |
| 5.1 | Sohbet | Chat | `reference/5.1-sohbet.png` | `layout/5.1-sohbet.json` |
| 5.2 | Medya ve konum | Media and location | `reference/5.2-medya-ve-konum.png` | `layout/5.2-medya-ve-konum.json` |
| 5.3 | Mesaj eylemleri | Message actions | `reference/5.3-mesaj-eylemleri.png` | `layout/5.3-mesaj-eylemleri.json` |
| 5.4 | Mesaj bilgisi | Message info | `reference/5.4-mesaj-bilgisi.png` | `layout/5.4-mesaj-bilgisi.json` |
| 5.5 | Ghost ve süreli mesaj | Ghost and disappearing messages | `reference/5.5-ghost-ve-sureli-mesaj.png` | `layout/5.5-ghost-ve-sureli-mesaj.json` |
| 5.6 | Tek görüntülemelik | View once | `reference/5.6-tek-goruntulemelik.png` | `layout/5.6-tek-goruntulemelik.json` |
| 6.1 | Güvenlik numarası | Safety number | `reference/6.1-guvenlik-numarasi.png` | `layout/6.1-guvenlik-numarasi.json` |
| 6.2 | QR tarama | QR scan | `reference/6.2-qr-tarama.png` | `layout/6.2-qr-tarama.json` |
| 6.3 | Doğrulandı | Verified | `reference/6.3-dogrulandi.png` | `layout/6.3-dogrulandi.json` |
| 7.1 | Ayarlar | Settings | `reference/7.1-ayarlar.png` | `layout/7.1-ayarlar.json` |
| 7.2 | Yedeği dışa aktar | Export backup | `reference/7.2-yedegi-disa-aktar.png` | `layout/7.2-yedegi-disa-aktar.json` |
| 7.3 | Yedekten geri yükle | Restore from backup | `reference/7.3-yedekten-geri-yukle.png` | `layout/7.3-yedekten-geri-yukle.json` |
| 8.1 | Sohbet listesi (açık tema) | Chat list (light) | `reference/8.1-sohbet-listesi-acik.png` | `layout/8.1-sohbet-listesi-acik.json` |
| 8.2 | Sohbet (açık tema) | Chat (light) | `reference/8.2-sohbet-acik.png` | `layout/8.2-sohbet-acik.json` |
| 8.3 | Kod yanlış (açık tema) | Wrong code (light) | `reference/8.3-kod-yanlis-acik.png` | `layout/8.3-kod-yanlis-acik.json` |
| 9.1 | Chats (EN) | Chats (EN) | `reference/9.1-chats-en.png` | `layout/9.1-chats-en.json` |
| 9.2 | Chat (EN) | Chat (EN) | `reference/9.2-chat-en.png` | `layout/9.2-chat-en.json` |
| 9.3 | Büyük harf testi | Upper-case test (guidance card) | `reference/9.3-buyuk-harf.png` | `layout/9.3-buyuk-harf.json` |

## Device frame (drawn by iOS, shown in every reference PNG)

| element | frame | notes |
|---|---|---|
| status bar | 0,0 402×62 | time “9:41” SF Pro 590 17/22 at 56.4,22.8; signal 19×12, wi-fi 17×12, battery 27×13 right cluster centred at x ≈ 327; white on dark, black on light |
| Dynamic Island | 138,11 126×37 r24 #000 | hardware |
| home indicator | 131.5,861 139×5 r100 | `#ffffff@70%` on dark, `#000000@25%` on light — system |
| screen corner radius | 48 (iPhone 17 Pro) | references are rendered with square corners |

## Shared components (used by many screens — build once)

**C1 · Primary button (outline + glow).** 54 high (48 inside cards, 50 in the 4.4 decision bar), full width minus gutters, `r16`, 1 pt `accent` border, transparent fill, label `button` style (Inter 500 17) in `accent300`, optional leading 20 pt icon with 8 pt gap, outer glow `0 0 32px -8px accent·70%` (4.4: `0 0 26px -8px`). Press: fill `accent·12%` → release (Nocturne `.btn-primary:active` = `accent·22%`). Disabled: whole control at 45 % opacity (2.3 before the checkbox is ticked, 2.1 while the name is invalid).

**C2 · Secondary button.** Same geometry, no border; inner 1 pt stroke `divider` (`inset 0 0 0 1px`), label Inter 500 17 `text` (3.1 “Kodla aç”, 3.3 “Tekrar dene”, 7.3 “İptal”). Press fill `text·7%`.

**C3 · Text button.** 44–46 high, centred `rowTitle` (Inter 400 16): `text·80%` for neutral (“Yedekten geri yükle”, “Doğrulandı olarak işaretle”), `accent300` for “Kodu unuttum”, sheet “Vazgeç”.

**C4 · Glass icon button.** Circle 44 (40 in chat headers, 36 in sheets), fill `text·9%`, inner hairline `inset 0 0 0 0.5px text·18%`, icon centred: 22 pt (setup back), 21 pt (most), 20 pt (chat header), 17 pt (sheet close); icon colour `text` (or `accent300` for the compose button on 4.1 and the QR button on 7.1). Over media/camera the fill becomes `#0c0d16@40%` (5.6) or `#f5f4ff@12%` (6.2) with `#f5f4ff` icons.

**C5 · Onboarding header (1.1–1.3).** Row at y = 66, pad 0 24: wordmark “us2us” `brandWordmark` (Doto 900 20, line box 31) left; pager “n / 3” `monoMeta` (Mono 400 11) `text·68%` right (x = 339.5).

**C6 · Setup header (2.1–2.3).** Row at y = 56, pad 0 16: back C4 (44, caret-left 22) left; “n / 3” Mono 11 `text·68%` right with 8 pt trailing padding.

**C7 · Centred-title header (4.3, 6.1, 7.2, 7.3).** Row at y = 56, pad 0 16, 44 high: leading C4, trailing C4 (or an empty 44×44 spacer), title `navTitle` (Inter 600 17) centred in the band x 76…326.

**C8 · Chat header (4.4, 5.1, 5.2, 8.2, 9.2).** Overlay 0,0 402×≈109, `bg·80%` + backdrop blur 20 / saturate 140 %, row pad 54 14 10 12, gap 10: back C4-40 (caret-left 20) · avatar 38 · column (username `monoUsername` Mono 500 15 + optional verified seal `ph:seal-check fill` 15 `accent`, gap 5; status line `meta` Inter 400 12.5 `text·68%`, gap 2) · trailing control (Ghost chip 34 high, or C4-40 “…” for requests). A **dotted rule** sits at y = 104 (2 pt high, dots r 0.75 pt every 5 pt, `text·20%`) on top of the scrolling messages (z above content).

**C9 · Page dots.** Row gap 6, height 6: active 22×6 `accent400` r3, inactive 6×6 `text·26%` r3; 16 pt below the dots before the button.

**C10 · Code dots.** 6 dots 15×15, gap 20 (row width 190, centred): empty = inner stroke 1.5 pt `text·40%`; filled = `accent400` + glow `0 0 12px accent·70%`; error = solid `danger` (all six).

**C11 · Keypad.** Grid 3 × 78 columns, column gap 26, row gap 16 (total 286×360, x = 58). Keys: circles 78, fill `text·9%`, digit `keypadDigit` (Inter 400 32, line 1) `text`. Bottom row: [empty | 0 | backspace] in setup (2.2); [Face ID glyph 30 pt stroke 1.6 `text` | 0 | backspace] on the lock screen (3.2). Backspace = `ph:backspace` 28. Key press: fill to `text·14%` + scale 0.97 (proposed, soft). Haptic: light impact per digit (proposed).

**C12 · Avatar.** Circle with the first letter of the username in Martian Mono 500, line-height 1, centred. Sizes: 64 (23 pt letter), 52 (19), 50 (18), 48 (17), 44 (16), 40 (14), 38 (14). Palettes (fill / letter): A `accent800`/`accent100`; B `accent700`/`accent100`; C `neutral800`/`neutral100`; D (self, outlined) `accent900`/`accent300` + inner ring 1 pt `accent700`. Pick A–D deterministically from the username (stable hash — the demo list cycles A, B, C, D, A, B, C); unknown senders (requests) always use C; the user's own avatar is always D. Online dot: 15 pt `online` with a 3 pt `bg` cut-out ring at the bottom-right of a 52 avatar (11 pt / 2 pt ring on the 38 header avatar) — **only for accepted contacts**.

**C13 · Grouped list (settings style).** Card `surface` r18; rows ≥ 48 high (62 with a subtitle), pad 0 14 (9 14 with subtitle), gap 13: icon tile 30×30 r8 `accent900` + inner ring 1 pt `accent800` with an 18 pt `accent300` glyph · title `rowTitle` Inter 400 16 `text` (subtitle `captionCompact` Inter 400 13/17.55 `text·68%`, gap 2) · trailing detail `label15` Inter 400 15 `text·68%` + chevron `ph:caret-right` 15 `text·40%`, or a toggle. Separators: dotted rule (r 0.75 / pitch 5 / 2 high, `text·22%`) inset 57 from the card's left edge. Section label above each card: `monoSectionLabel` (Mono 400 10.5, tracking 1.05 = 0.1 em, UPPERCASE) `text·68%`, pad 0 16, 7 pt above the card.

**C14 · Toggle.** 51×31, r16, pad 2; ON: track `accent`, knob 27 `#f5f4ff` at the right, knob shadow `0 2px 6px #000@25%`. OFF is not drawn in the design — proposed: track `text·16%` (divider), knob at the left, same knob. Animate with `springSoft` (no overshoot).

**C15 · Message bubble.** Max width 290 (270 when the bubble carries a reply quote, 266 for live location), pad 9 13 8 13, gap 5 between content and the time row, font `bubble` (Inter 400 16/22.08). Incoming: fill `surface`, text `text`, aligned leading (x = 14). Outgoing: fill `accent800`, text `accent100`, aligned trailing (right edge 388). Corner radius 20; the **last bubble of a run** gets a 6 pt tail corner (bottom-left for incoming `20/20/20/6`, bottom-right for outgoing `20/20/6/20`); single/earlier bubbles are fully round. Time row (`bubbleTime` Inter 400 11.5, line 1, tabular, `text·68%`) is right-aligned inside the bubble, followed (outgoing only) by the 15 pt status icon with gap 4. Vertical gap between bubbles 6. Media bubbles use pad 5 5 8 5 and an inner media radius 15 (14 for maps).

**C16 · Delivery status icons** (identical in 1.2 legend, lists, bubbles, 5.4): Gönderildi/Sent = `ph:check` regular `text·68%`; İletildi/Delivered = `ph:checks` regular `text·68%`; Okundu/Read = `ph:checks` **fill** `accent300`; İletilemedi/Not delivered = `ph:warning-circle` regular `danger`. Only the **last outgoing** message also shows the state as a sentence under the bubble (5.1).

**C17 · Ghost indicators.** List pill: 22 high, pad 0 8, r11, gap 4, `ph:ghost` 12 + `monoTag` (Mono 400 10.5) — calm: fill `accent900`, ink `accent300`, regular ghost; urgent (< 1 h left): fill `danger·14%`, ink `danger`, **filled** ghost. Header chip: 34 high, pad 0 12 0 10, r17, gap 6, `ph:ghost` 16 + `monoCountdownChip` (Mono 400 12, tabular) — calm: `accent900` + inner ring 1 pt `accent800`, ink `accent200`; urgent: `danger·14%` + inner ring `danger·45%`, ink `danger`, filled ghost; off: no fill, inner ring `divider`, ink `text·68%`, label “kapalı”. Short format: ≥ 1 day “N gün / N days”, ≥ 1 h “N sa / N h”, else “N dk / N min” (ceil, min 1). Long format `HH:MM:SS` ticking every second.

**C18 · Bottom sheet.** Backdrop `mix(bg 55%, #05060c)` over the whole screen; sheet `surface`, top radius 38 (full-height sheets start at y = 58; content-height sheets hug content with 40 bottom padding), grabber 38×5 r3 `text·25%` 8 pt from the top, centred. 3.3 is a *floating* sheet: inset 8 on left/right/bottom, r42, shadow `0 -12px 40px #000@35%`.

**C19 · Dotted rules & meters.** All rules in this design are dotted, not solid: list separators (r .75, pitch 5, 2 high), date rule (r .8, pitch 5, `text·28%`), timeline connector (vertical, r .9, pitch 5, `text·32%`), progress meters (dots r 2.5, pitch 12, 8 high; track `text·22%`, lit `accent400`), password strength (r 2, pitch 8, 6 high; track `text·25%`, lit `online`). Draw them as repeated circles (Canvas/`Path`), not dashed strokes.

**C20 · Line mark (logo).** Two rings + a bar: ring Ø14 stroke 2 `accent400`, bar 2 high `accent400`, total width 52 (3.1); 60 wide / Ø16 (3.4 cover); Ø9 / 60 wide with a gradient bar and flowing packets (4.1 header, “Hat açık”); app-icon version Ø6 stroke 1.5 / 20 wide `#b5abfc`.

**C21 · Composer (chat).** Overlay bottom 0, height 94 (pad 14 12 36 12), background `linear-gradient(transparent → bg at 30%)`; row gap 8, items bottom-aligned: “+” C4-44 (plus 22) · field (min 44, r22, `surface`, hairline `inset 0 0 0 0.5px text·14%`, pad 0 12 0 16, placeholder “Mesaj” `rowTitle` `text·50%`, trailing `ph:timer` 21 and `ph:sticker` 21 in `text·55%`, gap 12) · mic button 44 circle, 1 pt `accent` border, `ph:microphone` 21 `accent300`, glow `0 0 20px -6px accent·70%`. The field grows with multi-line input; when text is entered the mic becomes send (proposed: `ph:paper-plane-tilt` 21, same button).

**C22 · Segmented chips (5.5).** Row of 5 equal chips, gap 6, 40 high, r12, label `chip` (Inter 400 14): unselected inner 1 pt `divider`, ink `text`; selected fill `accent900`, inner 1.5 pt `accent`, ink `accent300`.

**C23 · Focused text field.** 60 high (2.1) / 56 (4.2, 7.2), r16, fill `surface` (on a `surface` sheet: `bg`), focus ring `inset 0 0 0 1.5px accent` + outer glow `0 0 26px -10px accent` (7.2 ring only). Caret 2×24 r1 `accent400`. Username fields: Martian Mono 400 19 (18 in 4.2) with a `text·68%` “@” prefix, no autocorrect, no auto-capitalisation, ASCII keyboard.

**C24 · Dot-grid backdrop.** Dots r 1 (soft edge to 1.6) every 18 pt in `text·16%` (1.1) / `text·14%` (3.1, 6.3), faded by a radial mask (1.1: 85 %×42 % at 50 %/28 %; 3.1: 70 %×38 % at 50 %/46 %; 6.3: 70 %×36 % at 50 %/40 %; opaque centre → transparent at 75 %).

---

# 01 · Karşılama — Welcome (“Hat kuruluyor” / “Setting up the line”)

> **Section note (TR):** Üç sayfa. İlk sayfadaki 3D sahne sitedekiyle aynı: iki uç, bir hat. iOS'ta RealityKit ya da SceneKit ile kurulabilir; "Hareketi azalt" açıksa durağan görüntü gösterilir.
> **EN:** Three pages. The 3D scene on the first page is the same as on the website: two ends, one line. On iOS it can be built with RealityKit or SceneKit; when “Reduce Motion” is on, a still image is shown.

The three welcome pages are a horizontally paged onboarding (swipe or the button advances). Shared chrome: C5 header, C9 page dots at y = 694 (1.1) / 748 (1.2, 1.3), primary button C1.

## 1.1 · Karşılama — Welcome

Reference `reference/1.1-karsilama.png` · light `reference/light/1.1-karsilama.png` · data `layout/1.1-karsilama.json`

> **Note (TR):** Sahne yavaşça döner, hat üzerindeki ışıklar iki yöne akar. "Yedekten geri yükle" yeni cihaz için doğrudan içe aktarmaya gider (7.3).
> **EN:** The scene rotates slowly; the lights on the line flow in both directions. “Restore from backup” goes straight to import for a new device (7.3).

**Purpose.** First launch. Brand moment + the one-sentence promise; entry to account creation or to restoring a backup.

**Layout.**
- Background `bg` + dot grid C24 (85 %×42 % mask centred at 50 %/28 %).
- 3D canvas 0,84 402×330 (transparent) — see *3D scene* below and `tokens.json › scene3D`.
- C5 header at y 66 (“us2us”, “1 / 3”).
- Content block (pad 0 24, gap 16) pushed down by a flexible spacer; headline “İki uç. Bir hat.” `displayHero` Doto 900 46/46, tracking −0.46, two lines at 24,481.5 354×94 — each “.” is Inter 700 46 (`displayPunctuation`); paragraph `bodyL` Inter 400 17/25.5 `text·68%`, 3 lines, 24,591.5 354×76.5.
- Bottom block pad 26 24 40 24, gap 10: C9 dots (1st active) · C1 “Başla” 24,726 354×54 · C3 “Yedekten geri yükle” 24,790 354×44 `text·80%`.

**Texts.**

| TR | EN |
|---|---|
| us2us | us2us |
| 1 / 3 | 1 / 3 |
| İki uç. Bir hat. | Two ends. One line. |
| us2us iki kişi arasında şifreli bir hat kurar. Telefon numarası yok; seni yalnızca kullanıcı adını tam bilen bulur. | us2us sets up an encrypted line between two people. No phone number; only someone who knows your exact username can find you. |
| Başla | Get started |
| Yedekten geri yükle | Restore from backup |
| (a11y, canvas) İki bardak ve aralarındaki hat | Two cups and the line between them |

**States & transitions.** “Başla” → 1.2 (page swipe, see flows). “Yedekten geri yükle” → document picker → 7.3 (restore). Swiping left also advances. Headline/paragraph do not change between themes.

**Animation.** 3D scene continuously: whole world sways about Y by ±0.07 rad (≈4°) around −0.42 rad with a 20.9 s period and about X by ±0.03 rad (29.9 s); 4 light pulses travel left→right (≈5.9 s per crossing) and 4 right→left at 80 % strength (≈7.1 s), each with a fading 4-dot trail; the hex lattice around the line spins slowly (0.25 rad/s) with a scan ring sweeping every 3.1 s; dust motes drift up. Page change: cross-fade + 16 pt drift, 0.5 s `curveSoft` (proposed). **Reduce Motion:** freeze scene time (still frame), no drift.

**3D scene (summary — full parameters in `tokens.json › scene3D`).** Perspective camera FOV 34°, at (0, 0.55, 7.8) looking at the origin, aspect 402/330. Two lathe “cups” (r 0.36 → 0.52, height 1.1) lying on the X axis, bottoms facing each other at x = ±0.95, open ends outward; matte PBR (roughness .62, metalness .08) in `#2b2e3e`, unlit `accent` rim torus + 18-meridian wire overlay at 34 %. A thin unlit line (r 0.009, `#b5abfc`) joins the bottoms; a soft glow line, light pulses (`#e7e5fe`, additive), a translucent hexagonal tube lattice (tunnel 0.4), a hex relay ring at the centre and 420 dust motes. Lights: hemisphere `#5d5294`/`#161826` ×1.1, key `#e9e9ed` ×1.7 from (−3,4,5), rim `#9184d9` ×2.4 from (3,1.5,−4). Light theme palette swaps to cup `#dfe2f0`, edge `#796cbf`, line/core `#5d5294`, normal blending (see JSON).

**Data.** None (static). Needs: Reduce Motion flag, theme.

**Elements.**

<details><summary>Element table 1.1 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×874 | shape |  |  |  | fill radial-gradient(circle, text·16% 1px, transparent 1.6px) · mask radial-gradient(85% 42% at 50% 28%, #000000, #000000@0% 75%) | abs |
| r.1 | 0,84 402×330 | canvas | 3D scene canvas |  |  |  | abs |
| r.2 | 0,0 402×97 | stack |  |  |  |  | row ai:center jc:space-between pad 66 24 0 24 |
| r.2.0 | 24,66 60×31 | text | “us2us” | Doto 900 20/31 ROND100 | text |  |  |
| r.2.1 | 339.5,72.97 38.5×17.05 | text | “1 / 3” | Mono 400 11/17.05 | text·68% |  |  |
| r.3 | 0,97 402×384.5 | spacer |  |  |  |  | flex 1 |
| r.4 | 0,481.5 402×186.5 | stack |  |  |  |  | col gap 16 pad 0 24 0 24 |
| r.4.0 | 24,481.5 354×94 | text | “İki uç. Bir hat.” | Doto 900 46/46 ls-0.46 ROND100 | text |  |  |
| r.4.0.0 | 186.84,476.5 14.91×56 | text | “.” | Inter 700 46/46 ls-0.46 | text |  |  |
| r.4.0.1 | 105.42,523.5 14.91×56 | text | “.” | Inter 700 46/46 ls-0.46 | text |  |  |
| r.4.1 | 24,591.5 354×76.5 | text | “us2us iki kişi arasında şifreli bir hat kurar. Telefon numarası yok…” | Inter 400 17/25.5 | text·68% |  |  |
| r.5 | 0,668 402×206 | stack |  |  |  |  | col gap 10 pad 26 24 40 24 |
| r.5.0 | 24,694 354×22 | stack |  |  |  |  | row gap 6 pad 0 0 16 0 |
| r.5.0.0 | 24,694 22×6 | shape |  |  |  | fill accent400 · r3 |  |
| r.5.0.1 | 52,694 6×6 | shape |  |  |  | fill text·26% · r3 |  |
| r.5.0.2 | 64,694 6×6 | shape |  |  |  | fill text·26% · r3 |  |
| r.5.1 | 24,726 354×54 | text | “Başla” | Inter 500 17/26.35 | accent300 | r16 · border 1px solid accent · shadow 0 0 32px -8px accent·70% | row ai:center jc:center |
| r.5.2 | 24,790 354×44 | text | “Yedekten geri yükle” | Inter 400 16/24.8 | text·80% |  | row ai:center jc:center |

</details>

Colour legend 1.1:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·16%` | #e9e9ed@16% | #292b31@16% |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent400` | #b5abfc | #796cbf |
| `text·26%` | #e9e9ed@26% | #292b31@26% |
| `accent300` | #d2cefd | #5d5294 |
| `accent` | #9184d9 | #796cbf |
| `accent·70%` | #9184d9@70% | #796cbf@70% |
| `text·80%` | #e9e9ed@80% | #292b31@80% |

## 1.2 · Bildirim yok. Bilerek. — No notifications. On purpose.

Reference `reference/1.2-bildirim-yok.png` · light `reference/light/1.2-bildirim-yok.png` · data `layout/1.2-bildirim-yok.json`

> **Note (TR):** Boş, kesik çizgili kilit ekranı bildirimi fikri anlatır. Metin yer tutucu; hazır onboarding metinleriniz buraya girecek. Dört durum burada ilk kez, sohbettekiyle aynı ikonlarla tanıtılır.
> **EN:** The empty, dashed lock-screen notification conveys the idea. The copy is a placeholder; your finished onboarding copy goes here (see `docs/copy/no-push.md`). The four states are introduced here for the first time, with the same icons as in the chat.

**Purpose.** Explain the deliberate absence of push notifications and introduce the four delivery states.

**Layout.**
- C5 header (“2 / 3”).
- Illustration block pad-top 34, centred, gap 6: clock “09:41” `displayClock` Doto **800** 76/76 in `text·86%` (87,131 228×76); date “Perşembe, 8 Ekim” `label15` Inter 400 15 `text·68%`.
- Ghost notification card 20,262 362×68, r22, **1.5 pt dashed** border `text·30%`, pad 14 16, gap 12: dashed square 38×38 r10 (same border) · two dotted skeleton lines (6 high, dots r2 pitch 8, `text·34%`, widths 46 % and 82 % of the text column, gap 9) · “bildirim yok” `monoTag` Mono 400 10.5 `text·68%`.
- Flexible spacer, then content (pad 0 24, gap 14): title “Bildirim yok. Bilerek.” `displayTitleL` Doto 900 40/40.8 (two lines, “.” in Inter 700); paragraph `body` Inter 400 16/24 `text·68%` (4 lines); legend list (gap 9): rows of icon 17 pt in an 18-wide slot + gap 10 + `legendEmphasis` Inter 500 14 state name `text` + `legend` Inter 400 14 “· explanation” `text·68%`, line 18.9.
- Bottom block pad 26 24 40 24: C9 (2nd active) · C1 “Anladım” 24,780 354×54.

**Texts.**

| TR | EN |
|---|---|
| 09:41 | 09:41 (live device time, `HH:mm`) |
| Perşembe, 8 Ekim | Thursday, 8 October (live date, `EEEE, d MMMM`) |
| bildirim yok | no notifications |
| Bildirim yok. Bilerek. | No notifications. On purpose. |
| Bildirimler Apple'ın sunucularından geçer ve kimin ne zaman yazdığına dair iz bırakır. us2us'ta mesajlar yalnızca uygulama açıkken gelir; durumlarını her an açıkça görürsün: | Notifications pass through Apple's servers and leave a trace of who wrote when. In us2us, messages only arrive while the app is open; you can always see exactly where they are: |
| Gönderildi · alıcı açınca iletilecek | Sent · will be delivered when they open the app |
| İletildi · cihazına ulaştı | Delivered · reached their device |
| Okundu | Read |
| İletilemedi · 14 gün içinde açılmadı | Not delivered · not opened within 14 days |
| Anladım | Got it |

**States & transitions.** “Anladım” / swipe → 1.3; swipe back → 1.1. No permission prompt is ever shown (the app never requests notification permission).

**Animation.** None in the design (static illustration). Page transition as 1.1.

**Data.** Current time and date (formatted for the locale); otherwise static.

**Elements.**

<details><summary>Element table 1.2 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×97 | stack |  |  |  |  | row ai:center jc:space-between pad 66 24 0 24 |
| r.0.0 | 24,66 60×31 | text | “us2us” | Doto 900 20/31 ROND100 | text |  |  |
| r.0.1 | 339.5,72.97 38.5×17.05 | text | “2 / 3” | Mono 400 11/17.05 | text·68% |  |  |
| r.1 | 0,97 402×139.25 | stack |  |  |  |  | col ai:center gap 6 pad 34 0 0 0 |
| r.1.0 | 87,131 228×76 | text | “09:41” | Doto 800 76/76 ROND100 | text·86% |  |  |
| r.1.1 | 137.16,213 127.69×23.25 | text | “Perşembe, 8 Ekim” | Inter 400 15/23.25 | text·68% |  |  |
| r.2 | 20,262.25 362×68 | shape |  |  |  | r22 · border 1.5px dashed text·30% | row ai:center gap 12 pad 14 16 14 16 mar 26 20 0 20 |
| r.2.0 | 37,277.25 38×38 | shape |  |  |  | r10 · border 1.5px dashed text·30% |  |
| r.2.1 | 87,285.75 177.8×21 | stack |  |  |  |  | col gap 9 flex 1 |
| r.2.1.0 | 87,285.75 81.78×6 | shape |  |  |  | fill radial-gradient(circle, text·34% 2px, transparent 2.5px) 0 50% / 8px 6px repeat-x |  |
| r.2.1.1 | 87,300.75 145.78×6 | shape |  |  |  | fill radial-gradient(circle, text·34% 2px, transparent 2.5px) 0 50% / 8px 6px repeat-x |  |
| r.2.2 | 276.8,288.11 88.2×16.27 | text | “bildirim yok” | Mono 400 10.5/16.27 | text·68% |  |  |
| r.3 | 0,330.25 402×83.59 | spacer |  |  |  |  | flex 1 |
| r.4 | 0,413.84 402×308.16 | stack |  |  |  |  | col gap 14 pad 0 24 0 24 |
| r.4.0 | 24,413.84 354×81.59 | text | “Bildirim yok. Bilerek.” | Doto 900 40/40.8 ROND100 | text |  |  |
| r.4.0.0 | 312,408.84 13.36×49 | text | “.” | Inter 700 40/40.8 | text |  |  |
| r.4.0.1 | 192,449.64 13.36×49 | text | “.” | Inter 700 40/40.8 | text |  |  |
| r.4.1 | 24,509.44 354×96 | text | “Bildirimler Apple'ın sunucularından geçer ve kimin ne zaman yazdığı…” | Inter 400 16/24 | text·68% |  |  |
| r.4.2 | 24,619.44 354×102.56 | stack |  |  |  |  | col gap 9 |
| r.4.2.0 | 24,619.44 354×18.89 | stack |  |  |  |  | row ai:center gap 10 |
| r.4.2.0.0 | 24,620.38 18×17 | icon | ph:check (regular) | 17pt | text·68% |  |  |
| r.4.2.0.1 | 52,619.44 216.64×18.89 | box |  |  |  |  |  |
| r.4.2.0.1.0 | 52,619.44 68.98×17 | text | “Gönderildi” | Inter 500 14/18.9 | text |  |  |
| r.4.2.0.1.1 | 124.92,619.44 143.72×17 | text | “· alıcı açınca iletilecek” | Inter 400 14/18.9 | text·68% |  |  |
| r.4.2.1 | 24,647.33 354×18.89 | stack |  |  |  |  | row ai:center gap 10 |
| r.4.2.1.0 | 24,648.27 18×17 | icon | ph:checks (regular) | 17pt | text·68% |  |  |
| r.4.2.1.1 | 52,647.33 145.09×18.89 | box |  |  |  |  |  |
| r.4.2.1.1.0 | 52,647.33 39.56×17 | text | “İletildi” | Inter 500 14/18.9 | text |  |  |
| r.4.2.1.1.1 | 95.5,647.33 101.59×17 | text | “· cihazına ulaştı” | Inter 400 14/18.9 | text·68% |  |  |
| r.4.2.2 | 24,675.22 354×18.89 | stack |  |  |  |  | row ai:center gap 10 |
| r.4.2.2.0 | 24,676.16 18×17 | icon | ph:checks (fill) | 17pt | accent300 |  |  |
| r.4.2.2.1 | 52,675.22 52.5×18.89 | text | “Okundu” | Inter 500 14/18.9 | text |  |  |
| r.4.2.3 | 24,703.11 354×18.89 | stack |  |  |  |  | row ai:center gap 10 |
| r.4.2.3.0 | 24,704.05 18×17 | icon | ph:warning-circle (regular) | 17pt | danger |  |  |
| r.4.2.3.1 | 52,703.11 226.55×18.89 | box |  |  |  |  |  |
| r.4.2.3.1.0 | 52,703.11 68.44×17 | text | “İletilemedi” | Inter 500 14/18.9 | text |  |  |
| r.4.2.3.1.1 | 124.38,703.11 154.17×17 | text | “· 14 gün içinde açılmadı” | Inter 400 14/18.9 | text·68% |  |  |
| r.5 | 0,722 402×152 | stack |  |  |  |  | col gap 10 pad 26 24 40 24 |
| r.5.0 | 24,748 354×22 | stack |  |  |  |  | row gap 6 pad 0 0 16 0 |
| r.5.0.0 | 24,748 6×6 | shape |  |  |  | fill text·26% · r3 |  |
| r.5.0.1 | 36,748 22×6 | shape |  |  |  | fill accent400 · r3 |  |
| r.5.0.2 | 64,748 6×6 | shape |  |  |  | fill text·26% · r3 |  |
| r.5.1 | 24,780 354×54 | text | “Anladım” | Inter 500 17/26.35 | accent300 | r16 · border 1px solid accent · shadow 0 0 32px -8px accent·70% | row ai:center jc:center |

</details>

Colour legend 1.2:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `text·86%` | #e9e9ed@86% | #292b31@86% |
| `text·30%` | #e9e9ed@30% | #292b31@30% |
| `text·34%` | #e9e9ed@34% | #292b31@34% |
| `accent300` | #d2cefd | #5d5294 |
| `danger` | #f2716a | #c53637 |
| `text·26%` | #e9e9ed@26% | #292b31@26% |
| `accent400` | #b5abfc | #796cbf |
| `accent` | #9184d9 | #796cbf |
| `accent·70%` | #9184d9@70% | #796cbf@70% |

## 1.3 · Sessizlik siler — Silence erases (Ghost mod)

Reference `reference/1.3-sessizlik-siler.png` · light `reference/light/1.3-sessizlik-siler.png` · data `layout/1.3-sessizlik-siler.json`

> **Note (TR):** Sayaç canlı akar. Noktalı çizgi kalan süreyi gösterir: her nokta bir saat, söndükçe sohbet silinmeye yaklaşır.
> **EN:** The counter runs live. The dotted line shows the time left: each dot is one hour; as they go dark, the chat gets closer to being erased.

**Purpose.** Introduce Ghost mode, disappearing messages, view-once media and delete-for-everyone.

**Layout.**
- C5 header (“3 / 3”).
- Hero (pad-top 64, centred, gap 16): kicker `monoKicker` (Mono 400 11, tracking 0.88, UPPERCASE) `accent300` with `ph:ghost` 16 and gap 8 — “GHOST MOD · 24 SAAT” at y 161; countdown `displayCountdownL` Doto 900 64/64 `accent200`, min-height 64, at y 194 (glyph box 307×77); hour meter 288×8 at 57,274: 24 dots (r 2.5, pitch 12) — unlit `text·22%`, lit `accent400` for the hours still remaining (23 lit = 276 pt wide in the frame shown).
- Spacer, then content (pad 0 24, gap 14): “Sessizlik siler.” `displayTitleL`; paragraph `body` `text·68%` (3 lines); feature list (pad-top 4, gap 10): icon slot 20 (18 pt `accent300` icon; the view-once item is an 18×18 circle with a **1.5 pt dashed** `accent300` ring containing “1” in Mono 400 9) + gap 10 + `legend` Inter 400 14 `text`.
- Bottom: C9 (3rd active) · C1 “Hesabımı oluştur”.

**Texts.**

| TR | EN |
|---|---|
| Ghost mod · 24 saat | Ghost mode · 24 hours |
| 23:41:07 | 23:41:07 (live demo counter) |
| Sessizlik siler. | Silence erases. |
| Ghost modu açık bir sohbet, seçtiğin süre boyunca sessiz kalırsa iki taraftan da tamamen silinir. Her yeni mesaj sayacı baştan başlatır. | A chat with Ghost mode on is erased completely on both sides if it stays silent for the time you choose. Every new message restarts the timer. |
| Süreli mesajlar: süre dolunca iki taraftan silinir | Disappearing messages: erased on both sides when time runs out |
| 1 (view-once badge) | 1 |
| Tek görüntülemelik fotoğraf ve video | View-once photos and videos |
| Gönderdiğini herkesten silebilirsin | You can delete what you sent for everyone |
| Hesabımı oluştur | Create my account |

**States & transitions.** “Hesabımı oluştur” → 2.1. Swipe back → 1.2.

**Animation.** Countdown ticks every second (demo starts at 23:41:07 and counts down, looping). Meter: when an hour elapses the right-most lit dot fades to the track colour over 0.8 s (proposed, soft). Reduce Motion: counter still updates (it is information), no fades.

**Data.** Demo only (no real chat): a local countdown seeded at 23:41:07.

**Elements.**

<details><summary>Element table 1.3 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×97 | stack |  |  |  |  | row ai:center jc:space-between pad 66 24 0 24 |
| r.0.0 | 24,66 60×31 | text | “us2us” | Doto 900 20/31 ROND100 | text |  |  |
| r.0.1 | 339.5,72.97 38.5×17.05 | text | “3 / 3” | Mono 400 11/17.05 | text·68% |  |  |
| r.1 | 0,97 402×185.05 | stack |  |  |  |  | col ai:center gap 16 pad 64 0 0 0 |
| r.1.0 | 107.48,161 187.03×17.05 | text | “Ghost mod · 24 saat” | Mono 400 11/17.05 ls0.88 UPPER | accent300 |  | row ai:center gap 8 |
| r.1.0.0 | 107.48,161.52 16×16 | icon | ph:ghost (regular) | 16pt | accent300 |  |  |
| r.1.1 | 47.39,194.05 307.2×64 | box |  |  |  |  | minH 64 |
| r.1.1.0 | 47.39,187.05 307.2×77 | text | “23:41:07” | Doto 900 64/64 ROND100 | accent200 |  |  |
| r.1.2 | 57,274.05 288×8 | box |  |  |  |  |  |
| r.1.2.0 | 57,274.05 288×8 | shape |  |  |  | fill radial-gradient(circle, text·22% 2.5px, transparent 3px) 0 50% / 12px 8px repeat-x | abs |
| r.1.2.1 | 57,274.05 276×8 | shape |  |  |  | fill radial-gradient(circle, accent400 2.5px, transparent 3px) 0 50% / 12px 8px repeat-x | abs |
| r.2 | 0,282.05 402×177.69 | spacer |  |  |  |  | flex 1 |
| r.3 | 0,459.73 402×262.27 | stack |  |  |  |  | col gap 14 pad 0 24 0 24 |
| r.3.0 | 24,459.73 354×81.59 | text | “Sessizlik siler.” | Doto 900 40/40.8 ROND100 | text |  |  |
| r.3.0.0 | 144,495.53 13.36×49 | text | “.” | Inter 700 40/40.8 | text |  |  |
| r.3.1 | 24,555.33 354×72 | text | “Ghost modu açık bir sohbet, seçtiğin süre boyunca sessiz kalırsa ik…” | Inter 400 16/24 | text·68% |  |  |
| r.3.2 | 24,641.33 354×80.67 | stack |  |  |  |  | col gap 10 pad 4 0 0 0 |
| r.3.2.0 | 24,645.33 354×18.89 | stack |  |  |  |  | row ai:center gap 10 |
| r.3.2.0.0 | 24,645.77 20×18 | icon | ph:timer (regular) | 18pt | accent300 |  |  |
| r.3.2.0.1 | 54,645.33 302.36×18.89 | text | “Süreli mesajlar: süre dolunca iki taraftan silinir” | Inter 400 14/18.9 | text |  |  |
| r.3.2.1 | 24,674.22 354×18.89 | stack |  |  |  |  | row ai:center gap 10 |
| r.3.2.1.0 | 25,674.66 18×18 | text | “1” | Mono 400 9/12.15 | accent300 | r50% · border 1.5px dashed accent300 | grid place:center mar 0 1 0 1 |
| r.3.2.1.1 | 54,674.22 242.7×18.89 | text | “Tek görüntülemelik fotoğraf ve video” | Inter 400 14/18.9 | text |  |  |
| r.3.2.2 | 24,703.11 354×18.89 | stack |  |  |  |  | row ai:center gap 10 |
| r.3.2.2.0 | 24,703.55 20×18 | icon | ph:trash (regular) | 18pt | accent300 |  |  |
| r.3.2.2.1 | 54,703.11 223.5×18.89 | text | “Gönderdiğini herkesten silebilirsin” | Inter 400 14/18.9 | text |  |  |
| r.4 | 0,722 402×152 | stack |  |  |  |  | col gap 10 pad 26 24 40 24 |
| r.4.0 | 24,748 354×22 | stack |  |  |  |  | row gap 6 pad 0 0 16 0 |
| r.4.0.0 | 24,748 6×6 | shape |  |  |  | fill text·26% · r3 |  |
| r.4.0.1 | 36,748 6×6 | shape |  |  |  | fill text·26% · r3 |  |
| r.4.0.2 | 48,748 22×6 | shape |  |  |  | fill accent400 · r3 |  |
| r.4.1 | 24,780 354×54 | text | “Hesabımı oluştur” | Inter 500 17/26.35 | accent300 | r16 · border 1px solid accent · shadow 0 0 32px -8px accent·70% | row ai:center jc:center |

</details>

Colour legend 1.3:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent300` | #d2cefd | #5d5294 |
| `accent200` | #e7e5fe | #423a6a |
| `text·22%` | #e9e9ed@22% | #292b31@22% |
| `accent400` | #b5abfc | #796cbf |
| `text·26%` | #e9e9ed@26% | #292b31@26% |
| `accent` | #9184d9 | #796cbf |
| `accent·70%` | #9184d9@70% | #796cbf@70% |

# 02 · Kayıt ve gizli kod — Sign-up and secret code (“Numara yok, kod var” / “No number, a code”)

> **Section note (TR):** Üç adım: kullanıcı adı, gizli kod, geri dönüşsüzlük onayı. Kodun kurtarılamayacağı kurulumda iki kez ve açık dille söylenir.
> **EN:** Three steps: username, secret code, irreversibility confirmation. The fact that the code cannot be recovered is said twice during setup, in plain language.

Owner decisions that apply: username = PROTOCOL.md §3.1, the same rule on every screen and platform (shared case table `spec/username-rules.json`); secret code = 6 digits, asked twice (set + confirm); trivial codes rejected; Face ID optional; “Kurulumu tamamla” only after the checkbox; a name found taken at “Kurulumu tamamla” sends the user back to 2.1 (see 2.3).

## 2.1 · Kullanıcı adı — Username

Reference `reference/2.1-kullanici-adi.png` · light `reference/light/2.1-kullanici-adi.png` · data `layout/2.1-kullanici-adi.json`

> **Note (TR):** Uygunluk yazarken denetlenir. Öneri: kullanıcı adları ASCII küçük harfle sınırlansın; böylece İ/ı dönüşümü tam eşleşmeyi hiçbir dilde bozmaz.
> **EN:** Availability is checked while typing. Recommendation: limit usernames to ASCII lower-case so the İ/ı conversion never breaks exact matching in any language.

**Rule (owner decision, 2026-10-11).** Exactly PROTOCOL.md §3.1: a name is valid iff its normalized form matches `^[a-z][a-z0-9_]{2,23}$` — 3–24 characters, a letter `a-z` first, then only `a-z`, `0-9` and `_` (no dot). Normalized = Unicode `White_Space` trimmed at both ends, one leading “@” dropped, then the §3.1 normalization (`White_Space` trim, NFKC, locale-independent lower case), byte for byte what the username hash is made from. The typed text is never turned into another name: nothing is dropped, cut to a length or converted (“Kağan”, “Ali Veli”, “İsmail” stay as typed and are reported). The rules a name breaks, in this order: Turkish characters (ı ş ğ ü ö ç, or the “i” + U+0307 that İ becomes), other characters outside `a-z 0-9 _`, a first character that is not a letter, length. The same table of cases (`spec/username-rules.json`) tests the iOS app and the Kotlin core.

**Purpose.** Pick the (immutable) username; explain exact-match discovery.

**Layout.**
- C6 header (“1 / 3”).
- Title block pad 18 24 0, gap 12: “Kullanıcı adını seç” `displayTitleM` Doto 900 36/36.72 (2 lines, 24,118 354×73); paragraph `body` `text·68%` (2 lines).
- Field block pad 22 20 0, gap 8: C23 field 20,273 362×60 (`surface`, ring + glow): “@” `text·68%` + value Mono 400 19 `text`, caret, flexible space, status icon 22 (`ph:check-circle` fill `online` when available). Below, row pad 0 4: “Kullanılabilir” `caption` Inter 400 13 `online` left; counter “7 / 24” `monoCounter` Mono 400 11.5 `text·68%` right.
- Rules list pad 14 24 0, gap 8: 4×4 `accent400` bullet + gap 10 + `bullet` Inter 400 13.5/18.9 `text·68%`. Four rows — length, first letter, characters, Turkish characters; the reference draws three (before the owner's decision): “Harfle başlar” sits between r.3.0 and r.3.1 with the same metrics, so everything below moves down 26.89 pt (18.89 + gap 8).
- Example card 20,470 362×145, `surface` r16, pad 16, gap 12: kicker “TAM EŞLEŞME” `monoKicker` `accent300`; 2-column grid (1fr | auto, row gap 10): `monoMatch` Mono 400 14 `text` handle | status `monoMatchStatus` Mono 400 12.5 with a 16 pt icon, gap 6 (`ph:x-circle` regular `danger` “bulunamaz”; `ph:check-circle` regular `online` “bulunur”); footnote `captionTight` Inter 400 13/18.85 `text·68%`.
- Spacer; C1 “Devam” 20,776 362×54 (pad 0 20 44).

**Texts.**

| TR | EN |
|---|---|
| Kullanıcı adını seç | Choose your username |
| Telefon numarası istemiyoruz. Seni yalnızca bu adı eksiksiz yazan bulabilir. | We don't ask for a phone number. Only someone who types this name exactly can find you. |
| @ | @ |
| Kullanılabilir | Available |
| Kurallara uygun *(live service, instead of “Kullanılabilir”: see States)* | Meets the rules |
| 7 / 24 | 7 / 24 |
| 3–24 karakter *(app copy since 2026-10-11, PROTOCOL.md §3.1; the reference drew “3–20 karakter”)* | 3–24 characters |
| Harfle başlar *(added 2026-10-11)* | Starts with a letter |
| Küçük harf, rakam ve alt çizgi *(app copy since 2026-10-11; the reference drew “Küçük harf, rakam, nokta ve alt çizgi”)* | Lower-case letters, digits and underscore |
| Türkçe karakter yok: ı, İ, ş, ğ, ü, ö, ç | No Turkish characters: ı, İ, ş, ğ, ü, ö, ç |
| Tam eşleşme | Exact match |
| bulunamaz | not found |
| bulunur | found |
| Arama öneri ya da otomatik tamamlama sunmaz. | Search offers no suggestions or autocomplete. |
| Devam | Continue |
| *(proposed states)* Alınmış · Kontrol ediliyor… · Geçersiz karakter | Taken · Checking… · Invalid character |
| *(the first broken rule, app copy since 2026-10-11)* Türkçe karakter kullanılamaz · Geçersiz karakter · Harfle başlamalı · En az 3 karakter · En fazla 24 karakter | Turkish characters can't be used · Invalid character · Must start with a letter · At least 3 characters · At most 24 characters |

**States.** empty (placeholder, button disabled 45 %) → typing (debounced availability check; status icon hidden or `ph:circle-notch` `text·68%` spinning softly) → available (shown) → taken (`ph:x-circle` fill `danger`, label “Alınmış” `danger`, ring stays `accent` — proposed) → invalid (the typed text stays as typed, nothing is filtered or converted; every rule the normalized name breaks tints `danger`, the status label names the first one, nothing is looked up and “Devam” stays disabled). Live service: the relay's directory answers only signed-in accounts (PROTOCOL.md §4.4) and there is none yet, so availability cannot be asked while typing; a name that meets the rules shows “Kurallara uygun” (`online`, with the check icon) and “Devam” continues, and a taken name is reported at 2.3 › “Kurulumu tamamla”. Counter shows `n / 24` (characters of the normalized name; past 24 the length rule tints `danger`, nothing is cut). Input: §3.1 (see Rule above); keyboard `.asciiCapable`, no autocorrect/capitalisation/smart punctuation.

**Transitions.** Back → 1.3. “Devam” (enabled when available; in the live service when the name meets the rules) → 2.2. 2.3 › “Başka bir ad seç” (the name was taken) → back here with the taken name still in the field, marked “Alınmış” and not accepted again in this setup; the code confirmed on 2.2 is kept, so “Devam” then goes straight to 2.3.

**Animation.** Status icon cross-fade 0.24 s; label colour cross-fade 0.36 s. Field glow is static while focused.

**Data.** `usernameAvailability(name) async -> available | meetsRules | taken | invalid(violation)` (plus `empty` / `checking`; the live service answers from the rules alone and returns `meetsRules` for every valid name, the design fixtures answer `available` / `taken`, and `meetsRules` for the name they find taken at 2.3), local validation by `UsernameRules` (§3.1).

**Elements.**

<details><summary>Element table 2.1 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.0.0 | 16,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 27,67 22×22 | icon | ph:caret-left (regular) | 22pt | text |  |  |
| r.0.1 | 339.5,69.47 46.5×17.05 | text | “1 / 3” | Mono 400 11/17.05 | text·68% |  | pad 0 8 0 0 |
| r.1 | 0,100 402×151.44 | stack |  |  |  |  | col gap 12 pad 18 24 0 24 |
| r.1.0 | 24,118 354×73.44 | text | “Kullanıcı adını seç” | Doto 900 36/36.72 ROND100 | text |  |  |
| r.1.1 | 24,203.44 354×48 | text | “Telefon numarası istemiyoruz. Seni yalnızca bu adı eksiksiz yazan b…” | Inter 400 16/24 | text·68% |  |  |
| r.2 | 0,251.44 402×110.14 | stack |  |  |  |  | col gap 8 pad 22 20 0 20 |
| r.2.0 | 20,273.44 362×60 | shape |  |  |  | fill surface · r16 · shadow inset 0 0 0 1.5px accent, 0 0 26px -10px accent | row ai:center gap 2 pad 0 16 0 16 |
| r.2.0.0 | 36,288.72 13.31×29.44 | text | “@” | Mono 400 19/29.45 | text·68% |  |  |
| r.2.0.1 | 51.31,288.72 93.11×29.44 | text | “selin_n” | Mono 400 19/29.45 | text |  |  |
| r.2.0.2 | 147.42,291.44 2×24 | shape |  |  |  | fill accent400 · r1 | mar 0 0 0 1 |
| r.2.0.3 | 151.42,303.44 190.58×0 | spacer |  |  |  |  | flex 1 |
| r.2.0.4 | 344,292.44 22×22 | icon | ph:check-circle (fill) | 22pt | online |  |  |
| r.2.1 | 20,341.44 362×20.14 | stack |  |  |  |  | row jc:space-between pad 0 4 0 4 |
| r.2.1.0 | 24,341.44 73.17×20.14 | text | “Kullanılabilir” | Inter 400 13/20.15 | online |  |  |
| r.2.1.1 | 329.69,341.44 48.31×20.14 | text | “7 / 24” | Mono 400 11.5/17.82 | text·68% |  |  |
| r.3 | 0,361.58 402×86.67 | stack |  |  |  |  | col gap 8 pad 14 24 0 24 |
| r.3.0 | 24,375.58 354×18.89 | text | “3–24 karakter” | Inter 400 13.5/18.9 | text·68% |  | row ai:center gap 10 |
| r.3.0.0 | 24,383.02 4×4 | shape |  |  |  | fill accent400 · r50% |  |
| r.3.1 | 24,402.47 354×18.89 | text | “Küçük harf, rakam ve alt çizgi” | Inter 400 13.5/18.9 | text·68% |  | row ai:center gap 10 |
| r.3.1.0 | 24,409.91 4×4 | shape |  |  |  | fill accent400 · r50% |  |
| r.3.2 | 24,429.36 354×18.89 | text | “Türkçe karakter yok: ı, İ, ş, ğ, ü, ö, ç” | Inter 400 13.5/18.9 | text·68% |  | row ai:center gap 10 |
| r.3.2.0 | 24,436.8 4×4 | shape |  |  |  | fill accent400 · r50% |  |
| r.4 | 20,470.25 362×145.27 | shape |  |  |  | fill surface · r16 | col gap 12 pad 16 16 16 16 mar 22 20 0 20 |
| r.4.0 | 36,486.25 330×17.05 | text | “Tam eşleşme” | Mono 400 11/17.05 ls0.88 UPPER | accent300 |  |  |
| r.4.1 | 36,515.3 330×53.38 | stack |  |  |  |  | grid cols 1fr auto ai:center gap 10/0 |
| r.4.1.0 | 36,515.3 229.25×21.69 | text | “@selin” | Mono 400 14/21.7 | text |  |  |
| r.4.1.1 | 265.25,516.45 100.75×19.38 | text | “bulunamaz” | Mono 400 12.5/19.38 | danger |  | row ai:center gap 6 |
| r.4.1.1.0 | 265.25,518.14 16×16 | icon | ph:x-circle (regular) | 16pt | danger |  |  |
| r.4.1.2 | 36,546.98 229.25×21.69 | text | “@selin_n” | Mono 400 14/21.7 | text |  |  |
| r.4.1.3 | 265.25,548.14 100.75×19.38 | text | “bulunur” | Mono 400 12.5/19.38 | online |  | row ai:center gap 6 |
| r.4.1.3.0 | 265.25,549.83 16×16 | icon | ph:check-circle (regular) | 16pt | online |  |  |
| r.4.2 | 36,580.67 330×18.84 | text | “Arama öneri ya da otomatik tamamlama sunmaz.” | Inter 400 13/18.85 | text·68% |  |  |
| r.5 | 0,615.52 402×160.48 | spacer |  |  |  |  | flex 1 |
| r.6 | 0,776 402×98 | box |  |  |  |  | pad 0 20 44 20 |
| r.6.0 | 20,776 362×54 | text | “Devam” | Inter 500 17/26.35 | accent300 | r16 · border 1px solid accent · shadow 0 0 32px -8px accent·70% | row ai:center jc:center |

</details>

Colour legend 2.1:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `surface` | #232532 | #e4e7f5 |
| `accent` | #9184d9 | #796cbf |
| `accent400` | #b5abfc | #796cbf |
| `online` | #52cd86 | #009351 |
| `accent300` | #d2cefd | #5d5294 |
| `danger` | #f2716a | #c53637 |
| `accent·70%` | #9184d9@70% | #796cbf@70% |

## 2.2 · Gizli kod — Secret code

Reference `reference/2.2-gizli-kod.png` · light `reference/light/2.2-gizli-kod.png` · data `layout/2.2-gizli-kod.json`

> **Note (TR):** 6 hane dolunca kendiliğinden ilerler ve onay için ikinci kez sorulur. Öneri: 123456, 000000 gibi kolay kodlar kabul edilmesin.
> **EN:** When all 6 digits are entered it advances by itself and asks a second time to confirm. Recommendation: don't accept easy codes such as 123456 or 000000.

**Purpose.** Set the 6-digit secret code (entered twice).

**Layout.**
- C6 header (“2 / 3”).
- Title block as 2.1: “Gizli kodunu belirle” (2 lines), paragraph `body` `text·68%`.
- C10 code dots, pad-top 44 → row at y 295.4 (x 106…296); shown with 4 filled.
- Spacer; C11 keypad (setup variant: bottom-left empty) at 58,458 → keys rows y = 458, 552, 646, 740; pad-bottom 56.

**Texts.**

| TR | EN |
|---|---|
| Gizli kodunu belirle | Set your secret code |
| us2us her açılışta bu 6 haneli kodu ister. Başka hiçbir yerde kullanmadığın bir kod seç. | us2us asks for this 6-digit code every time it opens. Choose a code you don't use anywhere else. |
| *(confirm step, proposed)* Kodu tekrar gir | Enter the code again |
| *(confirm step, proposed)* Aynı 6 haneyi bir kez daha gir. | Enter the same 6 digits once more. |
| *(mismatch, proposed)* Kodlar eşleşmedi. Baştan dene. | The codes didn't match. Start again. |
| *(weak, proposed)* Bu kod çok kolay tahmin edilir. Başka bir kod seç. | This code is too easy to guess. Choose another. |

**States.** set (0–6 dots) → on 6th digit: validate weak codes (all equal, ascending/descending runs, e.g. 000000, 123456, 654321, 112233-style repeats — owner rule) → weak: dots turn `danger`, message under the dots in `calloutL` style `danger` (as 3.2), clear after 0.8 s → confirm step (same screen, title swaps to the confirm copy) → match → 2.3; mismatch → error message, back to the set step.

**Transitions.** Back → 2.1 (or from confirm back to set). Auto-advance after the 6th digit (hold 0.25 s so the last dot is seen).

**Animation.** Dot fill: scale 0.6→1 + glow fade-in 0.24 s `curveSoft`; delete: reverse. Step change: content cross-fade 0.36 s (title/paragraph), dots reset with a 0.24 s fade. Error: soft horizontal shake ±6 pt, 3 damped cycles, 0.45 s (no shake when Reduce Motion — colour only).

**Data.** Local only; the code is used to derive the vault key (never leaves the device).

**Elements.**

<details><summary>Element table 2.2 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.0.0 | 16,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 27,67 22×22 | icon | ph:caret-left (regular) | 22pt | text |  |  |
| r.0.1 | 339.5,69.47 46.5×17.05 | text | “2 / 3” | Mono 400 11/17.05 | text·68% |  | pad 0 8 0 0 |
| r.1 | 0,100 402×151.44 | stack |  |  |  |  | col gap 12 pad 18 24 0 24 |
| r.1.0 | 24,118 354×73.44 | text | “Gizli kodunu belirle” | Doto 900 36/36.72 ROND100 | text |  |  |
| r.1.1 | 24,203.44 354×48 | text | “us2us her açılışta bu 6 haneli kodu ister. Başka hiçbir yerde kulla…” | Inter 400 16/24 | text·68% |  |  |
| r.2 | 0,251.44 402×59 | stack |  |  |  |  | row jc:center gap 20 pad 44 0 0 0 |
| r.2.0 | 106,295.44 15×15 | shape |  |  |  | fill accent400 · r50% · shadow 0 0 12px accent·70% |  |
| r.2.1 | 141,295.44 15×15 | shape |  |  |  | fill accent400 · r50% · shadow 0 0 12px accent·70% |  |
| r.2.2 | 176,295.44 15×15 | shape |  |  |  | fill accent400 · r50% · shadow 0 0 12px accent·70% |  |
| r.2.3 | 211,295.44 15×15 | shape |  |  |  | fill accent400 · r50% · shadow 0 0 12px accent·70% |  |
| r.2.4 | 246,295.44 15×15 | shape |  |  |  | r50% · shadow inset 0 0 0 1.5px text·40% |  |
| r.2.5 | 281,295.44 15×15 | shape |  |  |  | r50% · shadow inset 0 0 0 1.5px text·40% |  |
| r.3 | 0,310.44 402×147.56 | spacer |  |  |  |  | flex 1 |
| r.4 | 0,458 402×416 | stack |  |  |  |  | grid cols repeat(3, 78px) jc:center gap 16/26 pad 0 0 56 0 |
| r.4.0 | 58,458 78×78 | text | “1” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.4.1 | 162,458 78×78 | text | “2” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.4.2 | 266,458 78×78 | text | “3” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.4.3 | 58,552 78×78 | text | “4” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.4.4 | 162,552 78×78 | text | “5” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.4.5 | 266,552 78×78 | text | “6” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.4.6 | 58,646 78×78 | text | “7” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.4.7 | 162,646 78×78 | text | “8” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.4.8 | 266,646 78×78 | text | “9” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.4.9 | 58,740 78×78 | box |  |  |  |  |  |
| r.4.10 | 162,740 78×78 | text | “0” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.4.11 | 266,740 78×78 | stack |  |  |  |  | grid place:center |
| r.4.11.0 | 291,765 28×28 | icon | ph:backspace (regular) | 28pt | text |  |  |

</details>

Colour legend 2.2:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent400` | #b5abfc | #796cbf |
| `accent·70%` | #9184d9@70% | #796cbf@70% |
| `text·40%` | #e9e9ed@40% | #292b31@40% |

## 2.3 · Geri dönüş yok — No way back

Reference `reference/2.3-geri-donus-yok.png` · light `reference/light/2.3-geri-donus-yok.png` · data `layout/2.3-geri-donus-yok.json`

> **Note (TR):** Onay kutusu işaretlenmeden kurulum bitmez. Tek kurtuluş yolu olan şifreli yedek burada adıyla anılır.
> **EN:** Setup cannot finish until the checkbox is ticked. The encrypted backup — the only way out — is named here explicitly.

**Purpose.** Make the irreversibility explicit; offer Face ID; require acknowledgement.

**Layout.**
- C6 header (“3 / 3”).
- Title block: “Unutursan geri dönüşü yok.” `displayTitleS` Doto 900 34/35.36 (2 lines) + paragraph `body` `text·68%` (4 lines).
- Facts card 16,317.7 370×168, `surface` r16, three rows (pad 0 16, gap 14, min-height 52 / 52 / 62): 21 pt icons — `ph:prohibit` `danger`, `ph:cloud-slash` `text·68%`, `ph:file-lock` `accent300`; titles `rowTitleS` Inter 400 15.5; third row subtitle “Ayarlar › Yedek › Dışa aktar” `caption` `text·68%`; solid 1 pt `divider` separators inset 51 (these two are **solid**, not dotted).
- Face ID card 16,497.7 370×64 (margin-top 12), `surface` r16: custom Face ID glyph 22 (stroke 1.7, `accent300`) · title “Face ID ile aç” + subtitle “Tanımazsa kod istenir” · C14 toggle (ON).
- Spacer; acknowledgement row (pad 0 24 18, gap 12, top-aligned): checkbox 24×24 r7 — checked: fill `accent`, `ph:check` 16 in `bg`; text `calloutL` Inter 400 14.5/21.03 `text`.
- C1 “Kurulumu tamamla”.

**Texts.**

| TR | EN |
|---|---|
| Unutursan geri dönüşü yok. | Forget it and there's no way back. |
| Kodun hiçbir yerde saklanmaz. Biz bilmeyiz, sıfırlayamayız. Unutursan bu telefondaki sohbetlerin kalıcı olarak kaybolur; hesabını yalnızca yedeğin geri getirir. *(app copy since 2026-10-11; the reference drew “Kodun yalnızca bu cihazda saklanır … tek yol uygulamayı silip baştan kurmak …”, which is not accurate: the code is stored nowhere, and a backup restores the account)* | Your code isn't stored anywhere. We don't know it and can't reset it. If you forget it, the chats on this phone are lost for good; only your backup can bring your account back. |
| Kod sıfırlama yok | No code reset |
| Kod sunucuya hiç gönderilmez | The code is never sent to a server |
| Tek güvencen şifreli yedek | Your only safeguard: an encrypted backup |
| Ayarlar › Yedek › Dışa aktar | Settings › Backup › Export |
| Face ID ile aç | Unlock with Face ID |
| Tanımazsa kod istenir | If it doesn't recognise you, the code is asked |
| Anladım: kodu unutursam verilerim kalıcı olarak silinir. | I understand: if I forget the code, my data is permanently erased. |
| Kurulumu tamamla | Finish setup |
| *(proposed, name taken)* Bu kullanıcı adı alınmış. | This username is already taken. |
| *(proposed, name taken)* Başka bir ad seç | Choose another name |

**States.** Checkbox unchecked (proposed: 24×24 r7, inner 1.5 pt `text·40%` stroke, no fill) → CTA disabled (45 %); checked → CTA enabled. Face ID toggle ON by default when biometrics are available; when the device has no Face ID, hide the card (proposed). Turning it on may trigger the system Face ID permission prompt (needs `NSFaceIDUsageDescription`). Name taken (proposed; the design has no state for it): in the live service availability cannot be checked on 2.1, so the relay refusing the registration with `username_taken` (409) is where a taken name shows: the inline message “Bu kullanıcı adı alınmış.” (`calloutL` `danger`) above the CTA, which becomes “Başka bir ad seç”.

**Transitions.** Back → 2.2. “Kurulumu tamamla” → account creation (keys, relay registration) → 4.1 Chats (cross-fade, 0.5 s). Errors (network) → inline message above the CTA (proposed, `calloutL` `danger`). Name taken → “Başka bir ad seç” → 2.1 (pop), the code kept; “Devam” there returns to 2.3.

**Animation.** Checkbox: fill + tick cross-fade/scale 0.24 s; CTA enable: opacity 0.45→1 over 0.36 s. Toggle `springSoft`.

**Data.** Biometry availability (`LAContext.biometryType`), Face ID preference, account creation call.

**Elements.**

<details><summary>Element table 2.3 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.0.0 | 16,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 27,67 22×22 | icon | ph:caret-left (regular) | 22pt | text |  |  |
| r.0.1 | 339.5,69.47 46.5×17.05 | text | “3 / 3” | Mono 400 11/17.05 | text·68% |  | pad 0 8 0 0 |
| r.1 | 0,100 402×197.72 | stack |  |  |  |  | col gap 12 pad 18 24 0 24 |
| r.1.0 | 24,118 354×71.72 | text | “Unutursan geri dönüşü yok.” | Doto 900 34/35.36 ROND100 | text |  |  |
| r.1.0.0 | 228,150.36 11.36×41 | text | “.” | Inter 700 34/35.36 | text |  |  |
| r.1.1 | 24,201.72 354×96 | text | “Kodun yalnızca bu cihazda saklanır. Biz bilmeyiz, sıfırlayamayız. U…” | Inter 400 16/24 | text·68% |  |  |
| r.2 | 16,317.72 370×168 | shape |  |  |  | fill surface · r16 | mar 20 16 0 16 |
| r.2.0 | 16,317.72 370×52 | stack |  |  |  |  | row ai:center gap 14 pad 0 16 0 16 minH 52 |
| r.2.0.0 | 32,333.22 21×21 | icon | ph:prohibit (regular) | 21pt | danger |  |  |
| r.2.0.1 | 67,331.7 125.77×24.02 | text | “Kod sıfırlama yok” | Inter 400 15.5/24.02 | text |  |  |
| r.2.1 | 67,369.72 319×1 | shape |  |  |  | fill divider | mar 0 0 0 51 |
| r.2.2 | 16,370.72 370×52 | stack |  |  |  |  | row ai:center gap 14 pad 0 16 0 16 minH 52 |
| r.2.2.0 | 32,386.22 21×21 | icon | ph:cloud-slash (regular) | 21pt | text·68% |  |  |
| r.2.2.1 | 67,384.7 225.41×24.02 | text | “Kod sunucuya hiç gönderilmez” | Inter 400 15.5/24.02 | text |  |  |
| r.2.3 | 67,422.72 319×1 | shape |  |  |  | fill divider | mar 0 0 0 51 |
| r.2.4 | 16,423.72 370×62 | stack |  |  |  |  | row ai:center gap 14 pad 0 16 0 16 minH 62 |
| r.2.4.0 | 32,444.22 21×21 | icon | ph:file-lock (regular) | 21pt | accent300 |  |  |
| r.2.4.1 | 67,431.64 196.02×46.16 | stack |  |  |  |  | col gap 2 |
| r.2.4.1.0 | 67,431.64 196.02×24.02 | text | “Tek güvencen şifreli yedek” | Inter 400 15.5/24.02 | text |  |  |
| r.2.4.1.1 | 67,457.66 196.02×20.14 | text | “Ayarlar › Yedek › Dışa aktar” | Inter 400 13/20.15 | text·68% |  |  |
| r.3 | 16,497.72 370×64 | shape |  |  |  | fill surface · r16 | row ai:center gap 14 pad 0 16 0 16 mar 12 16 0 16 minH 64 |
| r.3.0 | 32,518.72 22×22 | svg | custom svg (face-id glyph) |  | accent300 |  |  |
| r.3.1 | 68,506.64 237×46.16 | stack |  |  |  |  | col gap 2 flex 1 |
| r.3.1.0 | 68,506.64 237×24.02 | text | “Face ID ile aç” | Inter 400 15.5/24.02 | text |  |  |
| r.3.1.1 | 68,532.66 237×20.14 | text | “Tanımazsa kod istenir” | Inter 400 13/20.15 | text·68% |  |  |
| r.3.2 | 319,514.22 51×31 | shape |  |  |  | fill accent · r16 | row jc:flex-end pad 2 2 2 2 |
| r.3.2.0 | 341,516.22 27×27 | shape |  |  |  | fill rgb(245, 244, 255) · r50% · shadow rgba(0, 0, 0, 0.25) 0px 2px 6px |  |
| r.4 | 0,561.72 402×154.25 | spacer |  |  |  |  | flex 1 |
| r.5 | 0,715.97 402×60.03 | stack |  |  |  |  | row ai:flex-start gap 12 pad 0 24 18 24 |
| r.5.0 | 24,715.97 24×24 | shape |  |  |  | fill accent · r7 | grid place:center |
| r.5.0.0 | 28,719.97 16×16 | icon | ph:check (regular) | 16pt | bg |  |  |
| r.5.1 | 60,715.97 318×42.03 | text | “Anladım: kodu unutursam verilerim kalıcı olarak silinir.” | Inter 400 14.5/21.02 | text |  |  |
| r.6 | 0,776 402×98 | box |  |  |  |  | pad 0 20 44 20 |
| r.6.0 | 20,776 362×54 | text | “Kurulumu tamamla” | Inter 500 17/26.35 | accent300 | r16 · border 1px solid accent · shadow 0 0 32px -8px accent·70% | row ai:center jc:center |

</details>

Colour legend 2.3:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `surface` | #232532 | #e4e7f5 |
| `danger` | #f2716a | #c53637 |
| `divider` | #e9e9ed@16% | #292b31@16% |
| `accent300` | #d2cefd | #5d5294 |
| `accent` | #9184d9 | #796cbf |
| `accent·70%` | #9184d9@70% | #796cbf@70% |


# 03 · Kilit — Lock (“Kodu bilen açar” / “Whoever knows the code opens it”)

> **Section note (TR):** us2us her açılışta ve arka plandan her dönüşte kilitlenir. Face ID tanımazsa kod klavyesi açılır; kod hiçbir koşulda sıfırlanamaz. Uygulama değiştiricide içerik görünmez.
> **EN:** us2us locks on every launch and on every return from the background. If Face ID doesn't recognise you, the code keypad opens; the code can never be reset under any circumstances. No content is visible in the app switcher.

Owner decisions: lock on every launch and every return from background (no grace period); after wrong attempts, increasing delays; “Her şeyi sil” requires a second confirmation; no reset path.

## 3.1 · Face ID

Reference `reference/3.1-face-id.png` · light `reference/light/3.1-face-id.png` · data `layout/3.1-face-id.json`

> **Note (TR):** Açılışta Face ID kendiliğinden başlar; halkalar taramayı gösterir. Tanımazsa kod ekranı açılır. "Hareketi azalt" açıksa halkalar durur.
> **EN:** Face ID starts by itself at launch; the rings show the scan. If it doesn't recognise you, the code screen opens. With “Reduce Motion” on, the rings stop.

**Purpose.** Lock screen while biometric authentication runs.

**Layout.**
- Background `bg` + dot grid C24 (`text·14%`, mask 70 %×38 % at 50 %/46 %).
- Brand block (pad-top 112, centred, gap 12): C20 line mark 52×14 at 175,112; wordmark `brandWordmarkL` Doto 900 24 at 165,138.
- Centre block (flex, centred, gap 28, pad-bottom 30): Face ID disc 116×116 at 143,359.6 — fill `accent900`, inner ring 1 pt `accent800`, outer glow `0 0 70px -12px accent·60%`; custom Face ID glyph 56 pt (stroke 1.3, `accent300`) centred; two pulse rings (see Animation). Labels (gap 6, centred): “Face ID ile açılıyor” `lockStatus` Inter 500 19 `text`; “Tanımazsa gizli kodun istenir” `preview` Inter 400 14.5 `text·68%`.
- Bottom (pad 0 24 44): C2 secondary button “Kodla aç” with `ph:password` 20 icon, 24,776 354×54.

**Texts.**

| TR | EN |
|---|---|
| us2us | us2us |
| Face ID ile açılıyor | Unlocking with Face ID |
| Tanımazsa gizli kodun istenir | If it doesn't recognise you, your secret code is asked |
| Kodla aç | Unlock with code |
| *(Face ID usage description, proposed)* us2us'ı açmak için yüzünü kullanır. | Uses your face to unlock us2us. |

**States.** scanning (auto-starts on appear when Face ID is enabled) → success → content (dissolve 0.36 s) · failure/cancel → 3.2 keypad (cross-fade) · “Kodla aç” → 3.2 immediately. If Face ID is disabled in settings, launch directly into 3.2 (empty dots).

**Animation.** Pulse rings: two 116 pt circles, 1.5 pt `accent400` stroke, centred on the disc, each scaling 0.15→1 while fading 0.9→0 over 2.8 s `ease-out`, infinite, second ring delayed 1.4 s. Reduce Motion: no rings (static disc). Disc glow static.

**Data.** Face ID enabled flag, `LAContext` evaluation result.

**Elements.**

<details><summary>Element table 3.1 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×874 | shape |  |  |  | fill radial-gradient(circle, text·14% 1px, transparent 1.6px) · mask radial-gradient(70% 38% at 50% 46%, #000000, #000000@0% 75%) | abs |
| r.1 | 0,0 402×175.19 | stack |  |  |  |  | col ai:center gap 12 pad 112 0 0 0 |
| r.1.0 | 175,112 52×14 | stack |  |  |  |  | row ai:center |
| r.1.0.0 | 175,112 14×14 | shape |  |  |  | r50% · border 2px solid accent400 |  |
| r.1.0.1 | 189,118 24×2 | shape |  |  |  | fill accent400 | flex 1 |
| r.1.0.2 | 213,112 14×14 | shape |  |  |  | r50% · border 2px solid accent400 |  |
| r.1.1 | 165,138 72×37.19 | text | “us2us” | Doto 900 24/37.2 ROND100 | text |  |  |
| r.2 | 0,175.19 402×600.81 | stack |  |  |  |  | col ai:center jc:center gap 28 pad 0 0 30 0 flex 1 |
| r.2.0 | 143,359.64 116×116 | shape |  |  |  | fill accent900 · r50% · shadow inset 0 0 0 1px accent800, 0 0 70px -12px accent·60% | grid place:center |
| r.2.0.0 | 148.86,365.5 104.29×104.29 | shape |  |  |  | r50% · border 1.5px solid accent400 · opacity 0.106906 · anim u2-pulse 2.8s ease-out delay 0s ×infinite | mar -58 0 0 -58 abs matrix(0.899034, 0, 0, 0.899034, 0, 0) |
| r.2.0.1 | 176.1,392.74 49.8×49.8 | shape |  |  |  | r50% · border 1.5px solid accent400 · opacity 0.60428 · anim u2-pulse 2.8s ease-out delay 1.4s ×infinite | mar -58 0 0 -58 abs matrix(0.429291, 0, 0, 0.429291, 0, 0) |
| r.2.0.2 | 173,389.64 56×56 | svg | custom svg (face-id glyph) |  | accent300 |  |  |
| r.2.1 | 102.52,503.64 196.95×57.91 | stack |  |  |  |  | col ai:center gap 6 |
| r.2.1.0 | 118.98,503.64 164.02×29.44 | text | “Face ID ile açılıyor” | Inter 500 19/29.45 center | text |  |  |
| r.2.1.1 | 102.52,539.08 196.95×22.47 | text | “Tanımazsa gizli kodun istenir” | Inter 400 14.5/22.48 center | text·68% |  |  |
| r.3 | 0,776 402×98 | box |  |  |  |  | pad 0 24 44 24 |
| r.3.0 | 24,776 354×54 | text | “Kodla aç” | Inter 500 17/26.35 | text | r16 · shadow inset 0 0 0 1px divider | row ai:center jc:center gap 8 |
| r.3.0.0 | 152.13,793 20×20 | icon | ph:password (regular) | 20pt | text |  |  |

</details>

Colour legend 3.1:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·14%` | #e9e9ed@14% | #292b31@14% |
| `accent400` | #b5abfc | #796cbf |
| `accent900` | #2b2741 | #f5f4ff |
| `accent800` | #423a6a | #e7e5fe |
| `accent·60%` | #9184d9@60% | #796cbf@60% |
| `accent300` | #d2cefd | #5d5294 |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `divider` | #e9e9ed@16% | #292b31@16% |

## 3.2 · Kod yanlış — Wrong code

Reference `reference/3.2-kod-yanlis.png` · light `reference/light/3.2-kod-yanlis.png` (also native light: `reference/8.3-kod-yanlis-acik.png`) · data `layout/3.2-kod-yanlis.json`

> **Note (TR):** Hata, noktaların kırmızıya dönmesi ve açık bir cümleyle bildirilir. Öneri: iki yanlıştan sonra artan bekleme süresi. Face ID tuşu klavyenin sol alt köşesinde.
> **EN:** The error is shown by the dots turning red and a plain sentence. Recommendation: increasing waiting time after two wrong attempts. The Face ID key is in the bottom-left corner of the keypad.

**Purpose.** Code entry on the lock screen (shown in its error state).

**Layout.**
- Top block (pad-top 106, centred, gap 16): lock badge 52×52 at 175,106 (`accent900`, ring 1 pt `accent800`, `ph:lock-simple` 24 `accent300`); title “Gizli kodunu gir” `displayTitleXS` Doto 900 30/30 centred (57,174 288×30).
- Code block (pad-top 36, gap 16): C10 dots at y 240 (all `danger` in the error state); message `calloutL` Inter 400 14.5/21.03 `danger`, centred, max width 300 (2 lines, 51,271 300×42).
- Spacer; C11 keypad (lock variant: Face ID glyph 30 pt stroke 1.6 `text` bottom-left) at 58,414 → rows y 414, 508, 602, 696; pad-bottom 14.
- C3 “Kodu unuttum” `accent300`, 24,788 354×46 (pad 0 24 40).

**Texts.**

| TR | EN |
|---|---|
| Gizli kodunu gir | Enter your secret code |
| Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir. | Wrong code. 2 more wrong attempts and you'll have to wait 1 minute. |
| *(1 left, proposed)* Kod yanlış. 1 yanlış daha olursa 1 dakika beklemen gerekir. | Wrong code. 1 more wrong attempt and you'll have to wait 1 minute. |
| *(locked out, proposed)* Çok fazla yanlış deneme. {mm:ss} sonra tekrar dene. | Too many wrong attempts. Try again in {mm:ss}. |
| Kodu unuttum | I forgot my code |
| *(a11y)* Face ID ile aç · Sil | Unlock with Face ID · Delete |

**States.** idle (empty dots, no message) → typing (filled dots) → 6th digit verifies automatically → success: dissolve to the app → wrong: all dots `danger` + message, keypad stays active, dots clear after 0.8 s (message stays until the next digit). **Throttle (owner decision):** 1st and 2nd wrong attempts free (message counts down “2 / 1 yanlış daha”), then 1 min wait, then increasing (proposed 1 → 5 → 15 → 60 min, persisted in the vault, survives relaunch); while waiting: dots dimmed, keypad disabled at 45 %, message shows the live `mm:ss`. *The design's sentence implies the wait starts at the 3rd wrong attempt; the design note says “after two” — the app implements the copy as drawn and keep the threshold a constant.* Face ID key → 3.1 scan. “Kodu unuttum” → 3.3 sheet.

**Animation.** Error: dots tint to `danger` (0.24 s) + soft shake ±6 pt (0.45 s, 3 damped cycles), message fades in 0.36 s. Reduce Motion: no shake.

**Data.** Failure counter + lockout-until timestamp (persisted), Face ID enabled flag.

**Elements.**

<details><summary>Element table 3.2 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×204 | stack |  |  |  |  | col ai:center gap 16 pad 106 0 0 0 |
| r.0.0 | 175,106 52×52 | shape |  |  |  | fill accent900 · r50% · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.0.0.0 | 189,120 24×24 | icon | ph:lock-simple (regular) | 24pt | accent300 |  |  |
| r.0.1 | 57,174 288×30 | text | “Gizli kodunu gir” | Doto 900 30/30 ROND100 center | text |  |  |
| r.1 | 0,204 402×109.03 | stack |  |  |  |  | col ai:center gap 16 pad 36 0 0 0 |
| r.1.0 | 106,240 190×15 | stack |  |  |  |  | row jc:center gap 20 |
| r.1.0.0 | 106,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.0.1 | 141,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.0.2 | 176,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.0.3 | 211,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.0.4 | 246,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.0.5 | 281,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.1 | 51,271 300×42.03 | text | “Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir.” | Inter 400 14.5/21.02 center | danger |  | maxW 300 |
| r.2 | 0,313.03 402×100.97 | spacer |  |  |  |  | flex 1 |
| r.3 | 0,414 402×374 | box |  |  |  |  | pad 0 0 14 0 |
| r.3.0 | 0,414 402×360 | stack |  |  |  |  | grid cols repeat(3, 78px) jc:center gap 16/26 |
| r.3.0.0 | 58,414 78×78 | text | “1” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.1 | 162,414 78×78 | text | “2” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.2 | 266,414 78×78 | text | “3” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.3 | 58,508 78×78 | text | “4” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.4 | 162,508 78×78 | text | “5” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.5 | 266,508 78×78 | text | “6” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.6 | 58,602 78×78 | text | “7” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.7 | 162,602 78×78 | text | “8” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.8 | 266,602 78×78 | text | “9” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.9 | 58,696 78×78 | stack |  |  |  |  | grid place:center |
| r.3.0.9.0 | 82,720 30×30 | svg | custom svg (face-id glyph) |  | text |  |  |
| r.3.0.10 | 162,696 78×78 | text | “0” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.11 | 266,696 78×78 | stack |  |  |  |  | grid place:center |
| r.3.0.11.0 | 291,721 28×28 | icon | ph:backspace (regular) | 28pt | text |  |  |
| r.4 | 0,788 402×86 | box |  |  |  |  | pad 0 24 40 24 |
| r.4.0 | 24,788 354×46 | text | “Kodu unuttum” | Inter 400 16/24.8 | accent300 |  | row ai:center jc:center gap 8 |

</details>

Colour legend 3.2:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `accent300` | #d2cefd | #5d5294 |
| `accent900` | #2b2741 | #f5f4ff |
| `accent800` | #423a6a | #e7e5fe |
| `danger` | #f2716a | #c53637 |
| `text·9%` | #e9e9ed@9% | #292b31@9% |

## 3.3 · Kodu unuttum — Forgot the code

Reference `reference/3.3-kodu-unuttum.png` · light `reference/light/3.3-kodu-unuttum.png` · data `layout/3.3-kodu-unuttum.json`

> **Note (TR):** Kurtarma yolu yok; ekran yalnızca gerçek seçenekleri sunar. "Her şeyi sil" ikinci bir onay ister. Yeni kurulumdan sonra kişiler sohbette "güvenlik numarası değişti" uyarısı görür.
> **EN:** There is no recovery path; the screen offers only the real options. “Erase everything” asks for a second confirmation. After a fresh setup, contacts see a “safety number changed” warning in the chat.

**Purpose.** Honest dead-end: restore from backup or erase everything.

**Layout.**
- Behind: the 3.2 top block + empty dots at 30 % opacity; full-screen overlay `#06070e@55%`.
- Floating sheet (C18 floating): 8,403 386×463, `surface`, r42, shadow `0 -12px 40px #000@35%`, pad 10 18 28 18, gap 18: grabber · text block (pad 6 4 0, gap 10): “Kod kurtarılamaz.” `displaySheetTitle` Doto 900 30/30.9 + paragraph `sheetBody` Inter 400 15.5/23.25 `text·68%` (3 lines) · two option cards (gap 10), each 350×92, fill `bg`, r18, pad 14 14 14 16, gap 14: icon tile 40×40 r12 (restore: `accent900` + ring `accent800`, `ph:file-lock` 21 `accent300`; erase: `danger·14%`, `ph:trash` 21 `danger`), title `rowTitleEmphasis` Inter 500 16 (`text` / `danger`), description `captionTighter` Inter 400 13/18.2 `text·68%` (2 lines), chevron 15 `text·40%` · C2 “Tekrar dene” 350×54.

**Texts.**

| TR | EN |
|---|---|
| Kod kurtarılamaz. | The code can't be recovered. |
| Gizli kodun hiçbir yere gönderilmez, hiçbir yerde saklanmaz. Sıfırlama bağlantısı, destek talebi ya da güvenlik sorusu yok. *(app copy since 2026-10-11; the reference drew “… yalnızca bu cihazda, şifreli olarak durur.”: the code itself is stored nowhere)* | Your secret code is never sent or stored anywhere. There's no reset link, support request or security question. |
| Yedekten geri yükle | Restore from backup |
| Şifreli yedeğin ve parolası varsa sohbetlerin geri gelir. | If you have your encrypted backup and its password, your chats come back. |
| Her şeyi sil, baştan başla | Erase everything, start over |
| Bu cihazdaki tüm sohbetler ve anahtarlar kalıcı olarak silinir. | All chats and keys on this device are permanently erased. |
| Tekrar dene | Try again |
| *(2nd confirmation, proposed)* Her şey silinsin mi? · Sohbetlerin, anahtarların ve ayarların bu cihazdan kalıcı olarak silinecek. Bu işlem geri alınamaz. · Vazgeç · Her şeyi sil | Erase everything? · Your chats, keys and settings will be permanently erased from this device. This can't be undone. · Cancel · Erase everything |

**States & transitions.** “Tekrar dene” or swipe-down → 3.2. “Yedekten geri yükle” → 7.3 (restore; replaces the current vault after success). “Her şeyi sil” → second confirmation (proposed: a destructive alert, or the same floating sheet re-rendered with the danger button as C1 in `danger`) → wipe → 1.1. A restore/erase makes the safety number change for contacts (they see a warning in the chat — see 6.3).

**Animation.** Sheet rises with `springSoft` (0.55 s), overlay + dimming of the lock content fade 0.36 s; dismiss reverses.

**Data.** Whether a backup restore is possible (always offered); wipe action.

**Elements.**

<details><summary>Element table 3.3 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×255 | box |  |  |  | opacity 0.3 |  |
| r.0.0 | 0,0 402×204 | stack |  |  |  |  | col ai:center gap 16 pad 106 0 0 0 |
| r.0.0.0 | 175,106 52×52 | shape |  |  |  | fill accent900 · r50% · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.0.0.0.0 | 189,120 24×24 | icon | ph:lock-simple (regular) | 24pt | accent300 |  |  |
| r.0.0.1 | 57,174 288×30 | text | “Gizli kodunu gir” | Doto 900 30/30 ROND100 center | text |  |  |
| r.0.1 | 0,204 402×51 | box |  |  |  |  | pad 36 0 0 0 |
| r.0.1.0 | 0,240 402×15 | stack |  |  |  |  | row jc:center gap 20 |
| r.0.1.0.0 | 106,240 15×15 | shape |  |  |  | r50% · shadow inset 0 0 0 1.5px text·40% |  |
| r.0.1.0.1 | 141,240 15×15 | shape |  |  |  | r50% · shadow inset 0 0 0 1.5px text·40% |  |
| r.0.1.0.2 | 176,240 15×15 | shape |  |  |  | r50% · shadow inset 0 0 0 1.5px text·40% |  |
| r.0.1.0.3 | 211,240 15×15 | shape |  |  |  | r50% · shadow inset 0 0 0 1.5px text·40% |  |
| r.0.1.0.4 | 246,240 15×15 | shape |  |  |  | r50% · shadow inset 0 0 0 1.5px text·40% |  |
| r.0.1.0.5 | 281,240 15×15 | shape |  |  |  | r50% · shadow inset 0 0 0 1.5px text·40% |  |
| r.1 | 0,0 402×874 | shape |  |  |  | fill rgba(6, 7, 14, 0.55) | abs |
| r.2 | 8,403.02 386×462.98 | shape |  |  |  | fill surface · r42 · shadow rgba(0, 0, 0, 0.35) 0px -12px 40px | col gap 18 pad 10 18 28 18 abs |
| r.2.0 | 182,413.02 38×5 | shape |  |  |  | fill text·25% · r3 | self:center |
| r.2.1 | 26,436.02 350×117.64 | stack |  |  |  |  | col gap 10 pad 6 4 0 4 |
| r.2.1.0 | 30,442.02 342×31.89 | text | “Kod kurtarılamaz.” | Doto 900 30/30.9 ROND100 | text |  |  |
| r.2.1.0.0 | 318,439.02 10.03×36 | text | “.” | Inter 700 30/30.9 | text |  |  |
| r.2.1.1 | 30,483.91 342×69.75 | text | “Gizli kodun yalnızca bu cihazda, şifreli olarak durur. Sıfırlama ba…” | Inter 400 15.5/23.25 | text·68% |  |  |
| r.2.2 | 26,571.66 350×194.34 | stack |  |  |  |  | col gap 10 |
| r.2.2.0 | 26,571.66 350×92.17 | shape |  |  |  | fill bg · r18 | row ai:center gap 14 pad 14 14 14 16 |
| r.2.2.0.0 | 42,597.73 40×40 | shape |  |  |  | fill accent900 · r12 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.2.0.0.0 | 51.5,607.23 21×21 | icon | ph:file-lock (regular) | 21pt | accent300 |  |  |
| r.2.2.0.1 | 96,585.66 237×64.17 | stack |  |  |  |  | col gap 3 flex 1 |
| r.2.2.0.1.0 | 96,585.66 237×24.8 | text | “Yedekten geri yükle” | Inter 500 16/24.8 | text |  |  |
| r.2.2.0.1.1 | 96,613.45 237×36.38 | text | “Şifreli yedeğin ve parolası varsa sohbetlerin geri gelir.” | Inter 400 13/18.2 | text·68% |  |  |
| r.2.2.0.2 | 347,610.23 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.2.2.1 | 26,673.83 350×92.17 | shape |  |  |  | fill bg · r18 | row ai:center gap 14 pad 14 14 14 16 |
| r.2.2.1.0 | 42,699.91 40×40 | shape |  |  |  | fill danger·14% · r12 | grid place:center |
| r.2.2.1.0.0 | 51.5,709.41 21×21 | icon | ph:trash (regular) | 21pt | danger |  |  |
| r.2.2.1.1 | 96,687.83 237×64.17 | stack |  |  |  |  | col gap 3 flex 1 |
| r.2.2.1.1.0 | 96,687.83 237×24.8 | text | “Her şeyi sil, baştan başla” | Inter 500 16/24.8 | danger |  |  |
| r.2.2.1.1.1 | 96,715.63 237×36.38 | text | “Bu cihazdaki tüm sohbetler ve anahtarlar kalıcı olarak silinir.” | Inter 400 13/18.2 | text·68% |  |  |
| r.2.2.1.2 | 347,712.41 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.2.3 | 26,784 350×54 | text | “Tekrar dene” | Inter 500 17/26.35 | text | r16 · shadow inset 0 0 0 1px divider | row ai:center jc:center gap 8 |

</details>

Colour legend 3.3:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `accent300` | #d2cefd | #5d5294 |
| `accent900` | #2b2741 | #f5f4ff |
| `accent800` | #423a6a | #e7e5fe |
| `text·40%` | #e9e9ed@40% | #292b31@40% |
| `surface` | #232532 | #e4e7f5 |
| `text·25%` | #e9e9ed@25% | #292b31@25% |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `danger` | #f2716a | #c53637 |
| `danger·14%` | #f2716a@14% | #c53637@14% |
| `divider` | #e9e9ed@16% | #292b31@16% |

## 3.4 · Uygulama değiştirici — App switcher

Reference `reference/3.4-uygulama-degistirici.png` · light `reference/light/3.4-uygulama-degistirici.png` · data `layout/3.4-uygulama-degistirici.json`

> **Note (TR):** Arka plana geçerken ekran bulanık bir kapakla örtülür; anlık görüntüde sohbet okunmaz. Dönüşte yeniden Face ID ya da kod istenir.
> **EN:** When going to the background, the screen is covered with a blurred cover; the chat can't be read in the snapshot. On return, Face ID or the code is asked again.

**Purpose.** Privacy cover for the system snapshot. The wallpaper, the neighbouring card and the “us2us” label/icon above the card are **system UI drawn as context** — the app draws only the card content.

**What the app draws (the cover).** The card in the mock is the app's 402×874 screen scaled to 282×613 (×0.7015). The cover, at full size, is:
- the current screen, blurred (`blur 9 pt` in the thumbnail → **≈13 pt** full size) at 75 % opacity,
- a tint layer `bg·45%`,
- centred stack (gap 14 → ≈20 full size): C20 line mark (60×16 → ≈86×23, ring Ø16 → ≈23, stroke 2 → ≈3), wordmark “us2us” Doto 900 24 → ≈34, label “Kilitli” `monoMeta` Mono 400 11 → ≈16 with `ph:lock-simple` 13 → ≈18, `text·68%`.
- Simplest faithful implementation: render the cover at thumbnail metrics × 1/0.7015 (values above); alternative acceptable: a solid `bg` + dot grid with the same centred stack (no content blur) if blurring live content is not possible at `scenePhase == .inactive` in time.

**Texts.**

| TR | EN |
|---|---|
| us2us | us2us |
| Kilitli | Locked |

**States & transitions.** `scenePhase` → `.inactive`/`.background`: show cover **instantly** (no animation — must be in place before the snapshot). Return to `.active`: cover stays, lock flow (3.1 or 3.2) appears; after unlock the cover dissolves (0.36 s). Screen recording / mirroring: also hide content (see 5.6).

**Data.** Scene phase; lock state.

**Elements (the whole mock, including system context).**

<details><summary>Element table 3.4 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill rgb(11, 12, 20) | col |
| r.0 | -40,-40 482×954 | shape |  |  |  | fill radial-gradient(50% 35% at 25% 20%, rgba(145, 132, 217, 0.45), transparent 70%), radial-gradient(55% 40% at 85% 85%, rgb… (full value in layout JSON) · filter blur(12px) | abs |
| r.1 | -196,176 252×548 | shape |  |  |  | fill rgb(232, 233, 239) · r32 · shadow rgba(0, 0, 0, 0.5) 0px 30px 60px | abs |
| r.1.0 | -196,176 252×186 | stack |  |  |  |  | col gap 12 pad 64 22 64 22 |
| r.1.0.0 | -174,240 124.8×16 | shape |  |  |  | fill rgb(201, 203, 214) · r4 |  |
| r.1.0.1 | -174,268 187.19×9 | shape |  |  |  | fill rgb(214, 216, 225) · r4 |  |
| r.1.0.2 | -174,289 166.39×9 | shape |  |  |  | fill rgb(214, 216, 225) · r4 |  |
| r.2 | 60,104 84.2×30 | stack |  |  |  |  | row ai:center gap 10 abs |
| r.2.0 | 60,104 30×30 | shape |  |  |  | fill radial-gradient(90% 90% at 30% 15%, rgb(66, 58, 106), rgb(22, 24, 38) 72%) · r7 · shadow rgba(233, 233, 237, 0.2) 0px 0px 0px 0.5px inset | grid place:center |
| r.2.0.0 | 65,116 20×6 | stack |  |  |  |  | row ai:center |
| r.2.0.0.0 | 65,116 6×6 | shape |  |  |  | r50% · border 1.5px solid rgb(181, 171, 252) |  |
| r.2.0.0.1 | 71,118.25 8×1.5 | shape |  |  |  | fill rgb(181, 171, 252) | flex 1 |
| r.2.0.0.2 | 79,116 6×6 | shape |  |  |  | r50% · border 1.5px solid rgb(181, 171, 252) |  |
| r.2.1 | 100,107.38 44.2×23.25 | text | “us2us” | Inter 600 15/23.25 | rgb(233, 233, 237) |  |  |
| r.3 | 60,150 282×613 | shape |  |  |  | fill bg · r36 · shadow 0 34px 70px rgba(0, 0, 0, 0.55), inset 0 0 0 1px text·10% | abs |
| r.3.0 | 60,150 282×613 | shape |  |  |  | opacity 0.75 · filter blur(9px) | col gap 22 pad 64 18 64 18 abs |
| r.3.0.0 | 78,214 127.91×22 | shape |  |  |  | fill text·45% · r6 |  |
| r.3.0.1 | 78,258 246×38 | stack |  |  |  |  | row ai:center gap 12 |
| r.3.0.1.0 | 78,258 38×38 | shape |  |  |  | fill accent800 · r50% |  |
| r.3.0.1.1 | 128,265 196×24 | stack |  |  |  |  | col gap 7 flex 1 |
| r.3.0.1.1.0 | 128,265 90.16×9 | shape |  |  |  | fill text·40% · r5 |  |
| r.3.0.1.1.1 | 128,281 152.88×8 | shape |  |  |  | fill text·22% · r4 |  |
| r.3.0.2 | 78,318 246×38 | stack |  |  |  |  | row ai:center gap 12 |
| r.3.0.2.0 | 78,318 38×38 | shape |  |  |  | fill accent800 · r50% |  |
| r.3.0.2.1 | 128,325 196×24 | stack |  |  |  |  | col gap 7 flex 1 |
| r.3.0.2.1.0 | 128,325 90.16×9 | shape |  |  |  | fill text·40% · r5 |  |
| r.3.0.2.1.1 | 128,341 152.88×8 | shape |  |  |  | fill text·22% · r4 |  |
| r.3.0.3 | 78,378 246×38 | stack |  |  |  |  | row ai:center gap 12 |
| r.3.0.3.0 | 78,378 38×38 | shape |  |  |  | fill accent800 · r50% |  |
| r.3.0.3.1 | 128,385 196×24 | stack |  |  |  |  | col gap 7 flex 1 |
| r.3.0.3.1.0 | 128,385 90.16×9 | shape |  |  |  | fill text·40% · r5 |  |
| r.3.0.3.1.1 | 128,401 152.88×8 | shape |  |  |  | fill text·22% · r4 |  |
| r.3.0.4 | 78,438 246×38 | stack |  |  |  |  | row ai:center gap 12 |
| r.3.0.4.0 | 78,438 38×38 | shape |  |  |  | fill accent800 · r50% |  |
| r.3.0.4.1 | 128,445 196×24 | stack |  |  |  |  | col gap 7 flex 1 |
| r.3.0.4.1.0 | 128,445 90.16×9 | shape |  |  |  | fill text·40% · r5 |  |
| r.3.0.4.1.1 | 128,461 152.88×8 | shape |  |  |  | fill text·22% · r4 |  |
| r.3.0.5 | 78,498 246×38 | stack |  |  |  |  | row ai:center gap 12 |
| r.3.0.5.0 | 78,498 38×38 | shape |  |  |  | fill accent800 · r50% |  |
| r.3.0.5.1 | 128,505 196×24 | stack |  |  |  |  | col gap 7 flex 1 |
| r.3.0.5.1.0 | 128,505 90.16×9 | shape |  |  |  | fill text·40% · r5 |  |
| r.3.0.5.1.1 | 128,521 152.88×8 | shape |  |  |  | fill text·22% · r4 |  |
| r.3.0.6 | 78,558 246×38 | stack |  |  |  |  | row ai:center gap 12 |
| r.3.0.6.0 | 78,558 38×38 | shape |  |  |  | fill accent800 · r50% |  |
| r.3.0.6.1 | 128,565 196×24 | stack |  |  |  |  | col gap 7 flex 1 |
| r.3.0.6.1.0 | 128,565 90.16×9 | shape |  |  |  | fill text·40% · r5 |  |
| r.3.0.6.1.1 | 128,581 152.88×8 | shape |  |  |  | fill text·22% · r4 |  |
| r.3.0.7 | 78,618 246×38 | stack |  |  |  |  | row ai:center gap 12 |
| r.3.0.7.0 | 78,618 38×38 | shape |  |  |  | fill accent800 · r50% |  |
| r.3.0.7.1 | 128,625 196×24 | stack |  |  |  |  | col gap 7 flex 1 |
| r.3.0.7.1.0 | 128,625 90.16×9 | shape |  |  |  | fill text·40% · r5 |  |
| r.3.0.7.1.1 | 128,641 152.88×8 | shape |  |  |  | fill text·22% · r4 |  |
| r.3.1 | 60,150 282×613 | shape |  |  |  | fill bg·45% | abs |
| r.3.2 | 60,150 282×613 | stack |  |  |  |  | col ai:center jc:center gap 14 abs |
| r.3.2.0 | 171,407.38 60×16 | stack |  |  |  |  | row ai:center |
| r.3.2.0.0 | 171,407.38 16×16 | shape |  |  |  | r50% · border 2px solid accent400 |  |
| r.3.2.0.1 | 187,414.38 28×2 | shape |  |  |  | fill accent400 | flex 1 |
| r.3.2.0.2 | 215,407.38 16×16 | shape |  |  |  | r50% · border 2px solid accent400 |  |
| r.3.2.1 | 165,437.38 72×37.19 | text | “us2us” | Doto 900 24/37.2 ROND100 | text |  |  |
| r.3.2.2 | 164.55,488.56 72.91×17.05 | text | “Kilitli” | Mono 400 11/17.05 | text·68% |  | row ai:center gap 6 |
| r.3.2.2.0 | 164.55,490.58 13×13 | icon | ph:lock-simple (regular) | 13pt | text·68% |  |  |

</details>

Colour legend 3.4:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·10%` | #e9e9ed@10% | #292b31@10% |
| `text·45%` | #e9e9ed@45% | #292b31@45% |
| `accent800` | #423a6a | #e7e5fe |
| `text·40%` | #e9e9ed@40% | #292b31@40% |
| `text·22%` | #e9e9ed@22% | #292b31@22% |
| `bg·45%` | #161826@45% | #f3f5fe@45% |
| `accent400` | #b5abfc | #796cbf |
| `text·68%` | #e9e9ed@68% | #292b31@68% |

# 04 · Sohbetler — Chats (“Hat açıkken gelir” / “It arrives while the line is open”)

> **Section note (TR):** Mesajlar yalnızca uygulama açıkken akar; listenin üstündeki hat bunu gösterir. Her satırda mesajın durumu ve Ghost sayacı var. Tanımadığın kişiler ayrı kutuda bekler.
> **EN:** Messages only flow while the app is open; the line at the top of the list shows this. Every row shows the message state and the Ghost counter. People you don't know wait in a separate box.

## 4.1 · Sohbet listesi — Chat list

Reference `reference/4.1-sohbet-listesi.png` · urgent `reference/urgent/4.1-sohbet-listesi-urgent.png` · light `reference/light/4.1-sohbet-listesi.png` (native light: `reference/8.1-sohbet-listesi-acik.png`, EN: `reference/9.1-chats-en.png`) · data `layout/4.1-sohbet-listesi.json`

> **Note (TR):** Üstteki hat, bağlantı açıkken akar; uygulama kapanınca mesaj gelmez. Yeşil nokta yalnızca kabul edilmiş kişilerde görünür. Ghost sayacı son saatte kırmızıya döner.
> **EN:** The line at the top flows while the connection is open; when the app is closed no messages arrive. The green dot only appears for accepted contacts. The Ghost counter turns red in the last hour.

**Purpose.** Home screen: connection state, requests entry, conversations.

**Layout.**
- Top bar (pad 56 16 0): self avatar 40 (palette D, “S” Mono 500 14) at 16,58 → opens Settings (7.1); right group (gap 10): C4 `ph:qr-code` 21 (`text`) → my code / scan; C4 `ph:note-pencil` 21 in `accent300` → new chat (4.2).
- Title block (pad 14 20 0, gap 12): “Sohbetler” `displayTitle` Doto 900 38/38 at 20,114; connection line: C20 mark 60×9 (rings Ø9 stroke 2 `accent400`, bar 2 high with gradient `accent400 → accent·30% → accent400`, two flowing packets) + gap 10 + “Hat açık · mesajlar geliyor” `monoMeta` `text·68%` (y 164).
- Requests card 16,199 370×73 (margin 18 16 4), `surface` r18, pad 13 14, gap 13: tray tile 42 (circle `accent900`, ring `accent800`, `ph:tray` 21 `accent300`) · title `rowTitleEmphasis` + subtitle `caption` `text·68%` (gap 2) · count badge 22 high, min-width 22, pad 0 7, r11, fill `accent`, number `badge` Inter 600 12.5 in `bg` · chevron 15 `text·40%`.
- Chat rows (min-height 76, pad 0 18 0 16, gap 13), first at y 276: avatar 52 (C12) · column (gap 4): line 1 = username `monoUsername` Mono 500 15 (single line, …) + optional verified seal `ph:seal-check` fill 14 `accent` (gap 6) + spacer + time `caption` Inter 400 13 tabular `text·68%` (unread: `accent300`); line 2 (min-height 22, gap 6) = status icon 15 (C16) + optional type icon 15 (`ph:timer`, `ph:sticker`, `ph:navigation-arrow`, `ph:prohibit`) + preview `preview` Inter 400 14.5 single line … (`text·68%`; unread: `text`; failed: `danger`) + trailing Ghost pill (C17) or unread badge.
- Separators between rows: dotted rule 321 wide, inset 81 (aligned with the text column).
- The list scrolls under the top bar (title block scrolls with content — large-title behaviour, proposed: no sticky header in the design).

**Rows in the design (demo data).**

| user | avatar | line 1 | line 2 |
|---|---|---|---|
| deniz | A | verified seal · 18:10 | sent check + timer icon · “Süreli mesaj” · Ghost pill “23 sa” (urgent: “43 dk” red) |
| mert_k | B + online dot | 18:44 in `accent300` (unread) | sticker icon · “Sticker” in `text` · badge “2” |
| ayla_ | C | 16:20 | delivered checks · “Fotoğraf · tek görüntülemelik” · urgent pill “38 dk” |
| kaan | D | Dün | read checks · “Pazar kahvaltıya geliyor musun?” · pill “6 gün” |
| ece_y | A | 24 Eyl | warning-circle · “İletilemedi · 14 gün açılmadı” (`danger`) |
| bora | B | 22 Eyl | navigation-arrow · “Canlı konum paylaşımı bitti” |
| oya_t | C | 19 Eyl | prohibit · “Bu mesaj herkesten silindi” |

**Texts.**

| TR | EN |
|---|---|
| Sohbetler | Chats (09) |
| Hat açık · mesajlar geliyor | Line open · messages arriving (09) |
| *(proposed)* Hat kuruluyor… · Hat kapalı · yeniden deneniyor | Connecting… · Line closed · retrying |
| Mesaj istekleri | Message requests (09) |
| 2 kişi seninle yazışmak istiyor | 2 people want to message you (09) — plural rule: “1 kişi…” / “1 person wants to message you” |
| Süreli mesaj | Disappearing message (09) |
| Sticker | Sticker (09) |
| Fotoğraf · tek görüntülemelik | Photo · view once (09) |
| Pazar kahvaltıya geliyor musun? | Coming to Sunday breakfast? (09) |
| İletilemedi · 14 gün açılmadı | Not delivered · not opened in 14 days (09) |
| Canlı konum paylaşımı bitti | Live location sharing ended (09) |
| Bu mesaj herkesten silindi | This message was deleted for everyone (09) |
| Dün · 24 Eyl · 22 Eyl · 19 Eyl | Yesterday · Sep 24 · Sep 22 · Sep 19 (09) |
| 23 sa · 38 dk · 6 gün · 43 dk | 23 h · 38 min · 6 days · 43 min (09) |
| *(proposed)* Fotoğraf · Video · Sesli mesaj · Dosya · Konum · Canlı konum | Photo · Video · Voice message · File · Location · Live location |
| *(empty, proposed)* Henüz sohbet yok. Birinin kullanıcı adını tam yazarak başla. | No chats yet. Start by typing someone's exact username. |
| *(blocked, proposed)* Engellendi | Blocked |

**States.** connection: open (line flows, copy as drawn) / connecting / closed (line static, packets hidden, copy changes — proposed, `text·68%`); requests card hidden when there are none; row variants per C16/C17; urgent Ghost (< 1 h) pill turns red; empty list (proposed copy above); someone blocked (proposed): line 2 is `ph:prohibit` 15 + “Engellendi” (`preview`, `text·68%`), like a deleted message's line — a request blocked in 4.3 / 4.4 stays here as such a row (see Kişi ekranı · Engelle ve Bildir). Time formatting: today `HH:mm`; yesterday “Dün/Yesterday”; this year `d MMM` (“24 Eyl”/“Sep 24”); older `d.MM.yy` (proposed).

**Transitions.** Row → 5.1 (push). Requests card → 4.3 (push). Compose → 4.2 (sheet). QR → 6.2 scanner or my-code sheet (proposed: a menu with “QR kodla ekle” / “Kodumu göster”, matching 4.2). Self avatar → 7.1 (push). Swipe actions on rows: not in the design (do not add).

**Animation.** Packets on the header line (`linePacket`, 6 s loop, two directions offset 3 s; hidden with Reduce Motion or when the line is closed). Ghost pills update every minute; calm→urgent colour cross-fade 0.8 s. New message: row moves to the top with a soft 0.5 s reorder (`curveSoft`), preview cross-fades.

**Data.** `connectionState`; `pendingRequestCount`; per chat: `peerUsername`, `avatarPalette`, `isVerified`, `isAcceptedContact`, `isBlocked`, `isOnline` (only if presence is shared), `lastMessage {kind, previewText, isOutgoing, status, sentAt, isDisappearing}`, `unreadCount`, `ghost {enabled, remaining}`.

**Urgent variant.** Only deniz's pill changes: fill `danger·14%`, filled ghost, “43 dk” in `danger` (`reference/urgent/4.1-sohbet-listesi-urgent.png`).

**Elements.**

<details><summary>Element table 4.1 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.0.0 | 16,58 40×40 | text | “S” | Mono 500 14/14 | accent300 | fill accent900 · r50% · shadow inset 0 0 0 1px accent700 | grid place:center |
| r.0.1 | 288,56 98×44 | stack |  |  |  |  | row gap 10 |
| r.0.1.0 | 288,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.1.0.0 | 299.5,67.5 21×21 | icon | ph:qr-code (regular) | 21pt | text |  |  |
| r.0.1.1 | 342,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.1.1.0 | 353.5,67.5 21×21 | icon | ph:note-pencil (regular) | 21pt | accent300 |  |  |
| r.1 | 0,100 402×81.05 | stack |  |  |  |  | col gap 12 pad 14 20 0 20 |
| r.1.0 | 20,114 362×38 | text | “Sohbetler” | Doto 900 38/38 ROND100 | text |  |  |
| r.1.1 | 20,164 362×17.05 | text | “Hat açık · mesajlar geliyor” | Mono 400 11/17.05 | text·68% |  | row ai:center gap 10 |
| r.1.1.0 | 20,168.02 60×9 | stack |  |  |  |  | row ai:center |
| r.1.1.0.0 | 20,168.02 9×9 | shape |  |  |  | r50% · border 2px solid accent400 |  |
| r.1.1.0.1 | 29,171.52 42×2 | shape |  |  |  | fill linear-gradient(90deg, accent400, accent·30% 50%, accent400) | flex 1 |
| r.1.1.0.1.0 | 32.19,170.52 44×4 | shape |  |  |  | fill linear-gradient(90deg, transparent, accent200, transparent) · r4 · shadow 0 0 12px 2px accent·50% · anim u2-packet-r 6s cubic-bezier(0.45, 0, 0.55, 1) delay 0s ×infinite | mar -2 0 0 -22 abs |
| r.1.1.0.1.1 | 7,170.52 44×4 | shape |  |  |  | fill linear-gradient(90deg, transparent, accent200, transparent) · r4 · shadow 0 0 12px 2px accent·50% · opacity 0 · anim u2-packet-l 6s cubic-bezier(0.45, 0, 0.55, 1) delay 3s ×infinite | mar -2 0 0 -22 abs |
| r.1.1.0.2 | 71,168.02 9×9 | shape |  |  |  | r50% · border 2px solid accent400 |  |
| r.2 | 16,199.05 370×72.94 | shape |  |  |  | fill surface · r18 | row ai:center gap 13 pad 13 14 13 14 mar 18 16 4 16 |
| r.2.0 | 30,214.52 42×42 | shape |  |  |  | fill accent900 · r50% · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.0.0 | 40.5,225.02 21×21 | icon | ph:tray (regular) | 21pt | accent300 |  |  |
| r.2.1 | 85,212.05 224×46.94 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.1.0 | 85,212.05 224×24.8 | text | “Mesaj istekleri” | Inter 500 16/24.8 | text |  |  |
| r.2.1.1 | 85,238.84 224×20.14 | text | “2 kişi seninle yazışmak istiyor” | Inter 400 13/20.15 | text·68% |  |  |
| r.2.2 | 322,224.52 22×22 | text | “2” | Inter 600 12.5/19.38 | bg | fill accent · r11 | grid place:center pad 0 7 0 7 |
| r.2.3 | 357,228.02 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.3 | 0,275.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.3.0 | 16,287.98 52×52 | text | “D” | Mono 500 19/19 | accent100 | fill accent800 · r50% | grid place:center |
| r.3.1 | 81,289.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.3.1.0 | 81,289.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.3.1.0.0 | 81,289.13 52.5×23.25 | text | “deniz” | Mono 500 15/23.25 … | text |  |  |
| r.3.1.0.1 | 139.5,293.75 14×14 | icon | ph:seal-check (fill) | 14pt | accent |  |  |
| r.3.1.0.2 | 159.5,300.75 181.28×0 | spacer |  |  |  |  | flex 1 |
| r.3.1.0.3 | 346.78,290.67 37.22×20.14 | text | “18:10” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.3.1.1 | 81,316.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.3.1.1.0 | 81,320.11 15×15 | icon | ph:check (regular) | 15pt | text·68% |  |  |
| r.3.1.1.1 | 102,320.11 15×15 | icon | ph:timer (regular) | 15pt | text·68% |  |  |
| r.3.1.1.2 | 123,316.38 186.25×22.47 | text | “Süreli mesaj” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.3.1.1.3 | 315.25,316.61 68.75×22 | shape |  |  |  | fill accent900 · r11 | row ai:center gap 4 pad 0 8 0 8 |
| r.3.1.1.3.0 | 323.25,321.61 12×12 | icon | ph:ghost (regular) | 12pt | accent300 |  |  |
| r.3.1.1.3.1 | 339.25,319.47 36.75×16.27 | text | “23 sa” | Mono 400 10.5/16.27 | accent300 |  |  |
| r.4 | 81,351.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.5 | 0,353.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.5.0 | 16,365.98 52×52 | text | “M” | Mono 500 19/19 | accent100 | fill accent700 · r50% | grid place:center |
| r.5.0.0 | 53,402.98 15×15 | shape |  |  |  | fill online · r50% · shadow 0 0 0 3px bg | abs |
| r.5.1 | 81,367.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.5.1.0 | 81,367.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.5.1.0.0 | 81,367.13 63×23.25 | text | “mert_k” | Mono 500 15/23.25 … | text |  |  |
| r.5.1.0.1 | 150,378.75 190.78×0 | spacer |  |  |  |  | flex 1 |
| r.5.1.0.2 | 346.78,368.67 37.22×20.14 | text | “18:44” | Inter 400 13/20.15 tnum | accent300 |  |  |
| r.5.1.1 | 81,394.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.5.1.1.0 | 81,398.11 15×15 | icon | ph:sticker (regular) | 15pt | text·68% |  |  |
| r.5.1.1.1 | 102,394.38 254×22.47 | text | “Sticker” | Inter 400 14.5/22.48 … | text |  | flex 1 |
| r.5.1.1.2 | 362,394.61 22×22 | text | “2” | Inter 600 12.5/19.38 | bg | fill accent · r11 | grid place:center pad 0 7 0 7 |
| r.6 | 81,429.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.7 | 0,431.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.7.0 | 16,443.98 52×52 | text | “A” | Mono 500 19/19 | neutral100 | fill neutral800 · r50% | grid place:center |
| r.7.1 | 81,445.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.7.1.0 | 81,445.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.7.1.0.0 | 81,445.13 52.5×23.25 | text | “ayla_” | Mono 500 15/23.25 … | text |  |  |
| r.7.1.0.1 | 139.5,456.75 201.28×0 | spacer |  |  |  |  | flex 1 |
| r.7.1.0.2 | 346.78,446.67 37.22×20.14 | text | “16:20” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.7.1.1 | 81,472.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.7.1.1.0 | 81,476.11 15×15 | icon | ph:checks (regular) | 15pt | text·68% |  |  |
| r.7.1.1.1 | 102,472.38 207.25×22.47 | text | “Fotoğraf · tek görüntülemelik” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.7.1.1.2 | 315.25,472.61 68.75×22 | text | “38 dk” | Mono 400 10.5/16.27 | danger | fill danger·14% · r11 | row ai:center gap 4 pad 0 8 0 8 |
| r.7.1.1.2.0 | 323.25,477.61 12×12 | icon | ph:ghost (fill) | 12pt | danger |  |  |
| r.8 | 81,507.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.9 | 0,509.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.9.0 | 16,521.98 52×52 | text | “K” | Mono 500 19/19 | accent300 | fill accent900 · r50% · shadow inset 0 0 0 1px accent700 | grid place:center |
| r.9.1 | 81,523.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.9.1.0 | 81,523.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.9.1.0.0 | 81,523.13 42×23.25 | text | “kaan” | Mono 500 15/23.25 … | text |  |  |
| r.9.1.0.1 | 129,534.75 224.25×0 | spacer |  |  |  |  | flex 1 |
| r.9.1.0.2 | 359.25,524.67 24.75×20.14 | text | “Dün” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.9.1.1 | 81,550.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.9.1.1.0 | 81,554.11 15×15 | icon | ph:checks (fill) | 15pt | accent300 |  |  |
| r.9.1.1.1 | 102,550.38 207.25×22.47 | text | “Pazar kahvaltıya geliyor musun?” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.9.1.1.2 | 315.25,550.61 68.75×22 | text | “6 gün” | Mono 400 10.5/16.27 | accent300 | fill accent900 · r11 | row ai:center gap 4 pad 0 8 0 8 |
| r.9.1.1.2.0 | 323.25,555.61 12×12 | icon | ph:ghost (regular) | 12pt | accent300 |  |  |
| r.10 | 81,585.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.11 | 0,587.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.11.0 | 16,599.98 52×52 | text | “E” | Mono 500 19/19 | accent100 | fill accent800 · r50% | grid place:center |
| r.11.1 | 81,601.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.11.1.0 | 81,601.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.11.1.0.0 | 81,601.13 52.5×23.25 | text | “ece_y” | Mono 500 15/23.25 … | text |  |  |
| r.11.1.0.1 | 139.5,612.75 199.81×0 | spacer |  |  |  |  | flex 1 |
| r.11.1.0.2 | 345.31,602.67 38.69×20.14 | text | “24 Eyl” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.11.1.1 | 81,628.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.11.1.1.0 | 81,632.11 15×15 | icon | ph:warning-circle (regular) | 15pt | danger |  |  |
| r.11.1.1.1 | 102,628.38 282×22.47 | text | “İletilemedi · 14 gün açılmadı” | Inter 400 14.5/22.48 … | danger |  | flex 1 |
| r.12 | 81,663.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.13 | 0,665.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.13.0 | 16,677.98 52×52 | text | “B” | Mono 500 19/19 | accent100 | fill accent700 · r50% | grid place:center |
| r.13.1 | 81,679.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.13.1.0 | 81,679.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.13.1.0.0 | 81,679.13 42×23.25 | text | “bora” | Mono 500 15/23.25 … | text |  |  |
| r.13.1.0.1 | 129,690.75 210.31×0 | spacer |  |  |  |  | flex 1 |
| r.13.1.0.2 | 345.31,680.67 38.69×20.14 | text | “22 Eyl” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.13.1.1 | 81,706.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.13.1.1.0 | 81,710.11 15×15 | icon | ph:navigation-arrow (regular) | 15pt | text·68% |  |  |
| r.13.1.1.1 | 102,706.38 282×22.47 | text | “Canlı konum paylaşımı bitti” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.14 | 81,741.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.15 | 0,743.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.15.0 | 16,755.98 52×52 | text | “O” | Mono 500 19/19 | neutral100 | fill neutral800 · r50% | grid place:center |
| r.15.1 | 81,757.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.15.1.0 | 81,757.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.15.1.0.0 | 81,757.13 52.5×23.25 | text | “oya_t” | Mono 500 15/23.25 … | text |  |  |
| r.15.1.0.1 | 139.5,768.75 199.81×0 | spacer |  |  |  |  | flex 1 |
| r.15.1.0.2 | 345.31,758.67 38.69×20.14 | text | “19 Eyl” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.15.1.1 | 81,784.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.15.1.1.0 | 81,788.11 15×15 | icon | ph:prohibit (regular) | 15pt | text·68% |  |  |
| r.15.1.1.1 | 102,784.38 282×22.47 | text | “Bu mesaj herkesten silindi” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |

</details>

Colour legend 4.1:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `accent300` | #d2cefd | #5d5294 |
| `accent900` | #2b2741 | #f5f4ff |
| `accent700` | #5d5294 | #d2cefd |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent400` | #b5abfc | #796cbf |
| `accent·30%` | #9184d9@30% | #796cbf@30% |
| `accent200` | #e7e5fe | #423a6a |
| `accent·50%` | #9184d9@50% | #796cbf@50% |
| `surface` | #232532 | #e4e7f5 |
| `accent800` | #423a6a | #e7e5fe |
| `accent` | #9184d9 | #796cbf |
| `text·40%` | #e9e9ed@40% | #292b31@40% |
| `accent100` | #f5f4ff | #2b2741 |
| `text·22%` | #e9e9ed@22% | #292b31@22% |
| `online` | #52cd86 | #009351 |
| `neutral100` | #f3f5fe | #292b31 |
| `neutral800` | #3f424d | #dfe2f0 |
| `danger` | #f2716a | #c53637 |
| `danger·14%` | #f2716a@14% | #c53637@14% |

## 4.2 · Yeni sohbet — New chat

Reference `reference/4.2-yeni-sohbet.png` · light `reference/light/4.2-yeni-sohbet.png` · data `layout/4.2-yeni-sohbet.json`

> **Note (TR):** Arama yalnızca tam kullanıcı adında sonuç döner; benzer adlar ya da öneriler yok. Klavyede otomatik düzeltme ve büyük harf kapalı.
> **EN:** Search only returns a result for the exact username; no similar names or suggestions. Autocorrect and capitalisation are off on the keyboard.

**Purpose.** Start a conversation by exact username, or via QR.

**Layout.** Full-height sheet (C18) at y 58 over the scrim; grabber at y 66.
- Sheet bar 48 high (pad 0 20): “Vazgeç” `rowTitle` `accent300` left; title “Yeni sohbet” `navTitle` centred over the full width.
- Search block (pad 10 16 0, gap 8): C23 field 16,129 370×56 on `bg` (ring + glow): “@” + Mono 400 18 value + caret + clear button `ph:x-circle` **fill** 20 `text·40%`; hint “Tam eşleşme · öneri ve otomatik tamamlama yok” `monoMeta` `text·68%` (pad 0 4).
- Result card 16,228 370×146 (margin-top 18), `bg` r20, pad 16, gap 14: avatar 52 + name `monoUsernameL` Mono 500 16 + “Kullanıcı bulundu” `caption` `text·68%` + `ph:check-circle` fill 22 `online`; C1 (48 high) “Mesaj yaz” with `ph:paper-plane-tilt` 20.
- Info row (pad 16 24 0, gap 12): `ph:tray` 18 `accent300` (top-aligned, +1) + `callout` Inter 400 13.5/19.58 `text·68%` with “Mesaj istekleri” emphasised in `text`.
- Options group (pad 22 16 0): C13-style card on `bg` r18, rows 62 high: tile `ph:scan` · “QR kodla ekle” / “Yan yanaysanız ekler ve aynı anda doğrular”; tile `ph:qr-code` · “Kodumu göster” / “@selin_n”; dotted separator inset 57; chevrons.

**Texts.**

| TR | EN |
|---|---|
| Vazgeç | Cancel |
| Yeni sohbet | New chat |
| Tam eşleşme · öneri ve otomatik tamamlama yok | Exact match · no suggestions or autocomplete |
| Kullanıcı bulundu | User found |
| Mesaj yaz | Write a message |
| deniz seni kabul edene kadar ilk mesajın onun **Mesaj istekleri** kutusunda bekler. O zamana kadar aktif durumunuzu birbirinizden göremezsiniz. | Until deniz accepts you, your first message waits in their **Message requests**. Until then, neither of you can see the other's activity. |
| QR kodla ekle | Add with QR code |
| Yan yanaysanız ekler ve aynı anda doğrular | If you're side by side, adds and verifies at once |
| Kodumu göster | Show my code |
| *(proposed)* Kullanıcı bulunamadı · Kullanıcı adını eksiksiz yaz. | User not found · Type the full username. |
| *(a name that breaks a rule: the first one, app copy since 2026-10-11)* Geçersiz kullanıcı adı · Türkçe karakter kullanılamaz. · … Yalnızca küçük harf, rakam ve alt çizgi. · … Harfle başlamalı. · … En fazla 24 karakter. | Not a valid username · Turkish characters can't be used. · … Only lower-case letters, digits and underscore. · … Must start with a letter. · … At most 24 characters. |
| *(proposed)* Aranıyor… | Searching… |

**States.** empty field (hint only, no card) → typing (debounced exact lookup; while searching `ph:circle-notch` `text·68%`) → found (card as drawn) → not found (card with C avatar placeholder `?`, title in `text·68%`, `ph:x-circle` `danger`, CTA hidden — proposed) → invalid (a name that breaks a rule: the same card, its subtitle names the first broken rule; nothing is looked up) → already a contact (CTA “Sohbete git” / “Go to chat”, proposed). The info row appears only when the user is found and not yet a contact.

**Transitions.** “Vazgeç”/swipe down → dismiss to 4.1. “Mesaj yaz” → dismiss + push 5.1 with an empty, pending-request conversation. “QR kodla ekle” → 6.2. “Kodumu göster” → my QR (6.1-style card; proposed).

**Animation.** Sheet presentation (`springSoft`), result card fade + 8 pt rise 0.36 s, info row fades in 0.36 s after the card.

**Data.** `lookupExact(username)` → found(profile) / notFound; own username; whether a chat already exists. The query is normalized as on 2.1 (`White_Space` trimmed, one leading “@” dropped, §3.1 normalization) and never turned into another name; a query that breaks PROTOCOL.md §3.1 is not hashed or looked up, the typed text stays and the broken rule is reported.

**Elements.**

<details><summary>Element table 4.2 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×874 | shape |  |  |  | fill mix(bg 55%, #05060c) | abs |
| r.1 | 0,58 402×816 | shape |  |  |  | fill surface · r38/38/0/0 | col pad 8 0 0 0 abs |
| r.1.0 | 182,66 38×5 | shape |  |  |  | fill text·25% · r3 | self:center |
| r.1.1 | 0,71 402×48 | stack |  |  |  |  | row ai:center jc:space-between pad 0 20 0 20 |
| r.1.1.0 | 20,82.59 56.09×24.8 | text | “Vazgeç” | Inter 400 16/24.8 | accent300 |  |  |
| r.1.1.1 | 0,81.83 402×26.34 | text | “Yeni sohbet” | Inter 600 17/26.35 center | text |  | abs |
| r.1.1.2 | 382,95 0×0 | box |  |  |  |  |  |
| r.1.2 | 0,119 402×91.05 | stack |  |  |  |  | col gap 8 pad 10 16 0 16 |
| r.1.2.0 | 16,129 370×56 | shape |  |  |  | fill bg · r16 · shadow inset 0 0 0 1.5px accent, 0 0 26px -10px accent | row ai:center gap 2 pad 0 14 0 16 |
| r.1.2.0.0 | 32,143.05 12.61×27.89 | text | “@” | Mono 400 18/27.9 | text·68% |  |  |
| r.1.2.0.1 | 46.61,143.05 63×27.89 | text | “deniz” | Mono 400 18/27.9 | text |  |  |
| r.1.2.0.2 | 112.61,145 2×24 | shape |  |  |  | fill accent400 · r1 | mar 0 0 0 1 |
| r.1.2.0.3 | 116.61,157 233.39×0 | spacer |  |  |  |  | flex 1 |
| r.1.2.0.4 | 352,147 20×20 | icon | ph:x-circle (fill) | 20pt | text·40% |  |  |
| r.1.2.1 | 16,193 370×17.05 | text | “Tam eşleşme · öneri ve otomatik tamamlama yok” | Mono 400 11/17.05 | text·68% |  | pad 0 4 0 4 |
| r.1.3 | 16,228.05 370×146 | shape |  |  |  | fill bg · r20 | col gap 14 pad 16 16 16 16 mar 18 16 0 16 |
| r.1.3.0 | 32,244.05 338×52 | stack |  |  |  |  | row ai:center gap 13 |
| r.1.3.0.0 | 32,244.05 52×52 | text | “D” | Mono 500 19/19 | accent100 | fill accent800 · r50% | grid place:center |
| r.1.3.0.1 | 97,246.08 238×47.94 | stack |  |  |  |  | col gap 3 flex 1 |
| r.1.3.0.1.0 | 97,246.08 238×24.8 | text | “deniz” | Mono 500 16/24.8 | text |  |  |
| r.1.3.0.1.1 | 97,273.88 238×20.14 | text | “Kullanıcı bulundu” | Inter 400 13/20.15 | text·68% |  |  |
| r.1.3.0.2 | 348,259.05 22×22 | icon | ph:check-circle (fill) | 22pt | online |  |  |
| r.1.3.1 | 32,310.05 338×48 | text | “Mesaj yaz” | Inter 500 17/26.35 | accent300 | r16 · border 1px solid accent · shadow 0 0 32px -8px accent·70% | row ai:center jc:center gap 8 |
| r.1.3.1.0 | 146.02,324.05 20×20 | icon | ph:paper-plane-tilt (regular) | 20pt | accent300 |  |  |
| r.1.4 | 0,374.05 402×74.69 | stack |  |  |  |  | row gap 12 pad 16 24 0 24 |
| r.1.4.0 | 24,391.05 18×57.69 | icon | ph:tray (regular) | 18pt | accent300 |  | mar 1 0 0 0 |
| r.1.4.1 | 54,390.05 324×58.69 | text | “deniz seni kabul edene kadar ilk mesajın onun Mesaj istekleri kutus…” | Inter 400 13.5/19.57 | text·68% |  |  |
| r.1.4.1.0 | 54,410.61 91.42×16 | text | “Mesaj istekleri” | Inter 400 13.5/19.57 | text |  |  |
| r.1.5 | 0,448.73 402×148.69 | box |  |  |  |  | pad 22 16 0 16 |
| r.1.5.0 | 16,470.73 370×126.69 | stack |  |  |  |  | col gap 7 |
| r.1.5.0.0 | 16,470.73 370×126.69 | shape |  |  |  | fill bg · r18 |  |
| r.1.5.0.0.0 | 16,470.73 370×62.34 | stack |  |  |  |  | row ai:center gap 13 pad 9 14 9 14 minH 48 |
| r.1.5.0.0.0.0 | 30,486.91 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.1.5.0.0.0.0.0 | 36,492.91 18×18 | icon | ph:scan (regular) | 18pt | accent300 |  |  |
| r.1.5.0.0.0.1 | 73,479.73 271×44.34 | stack |  |  |  |  | col gap 2 flex 1 |
| r.1.5.0.0.0.1.0 | 73,479.73 271×24.8 | text | “QR kodla ekle” | Inter 400 16/24.8 | text |  |  |
| r.1.5.0.0.0.1.1 | 73,506.53 271×17.55 | text | “Yan yanaysanız ekler ve aynı anda doğrular” | Inter 400 13/17.55 | text·68% |  |  |
| r.1.5.0.0.0.2 | 357,494.41 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.1.5.0.0.1 | 73,533.08 313×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 57 |
| r.1.5.0.0.2 | 16,535.08 370×62.34 | stack |  |  |  |  | row ai:center gap 13 pad 9 14 9 14 minH 48 |
| r.1.5.0.0.2.0 | 30,551.25 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.1.5.0.0.2.0.0 | 36,557.25 18×18 | icon | ph:qr-code (regular) | 18pt | accent300 |  |  |
| r.1.5.0.0.2.1 | 73,544.08 271×44.34 | stack |  |  |  |  | col gap 2 flex 1 |
| r.1.5.0.0.2.1.0 | 73,544.08 271×24.8 | text | “Kodumu göster” | Inter 400 16/24.8 | text |  |  |
| r.1.5.0.0.2.1.1 | 73,570.88 271×17.55 | text | “@selin_n” | Inter 400 13/17.55 | text·68% |  |  |
| r.1.5.0.0.2.2 | 357,558.75 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |

</details>

Colour legend 4.2:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `mix(bg 55%, #05060c)` | #0e101a | #888991 |
| `surface` | #232532 | #e4e7f5 |
| `text·25%` | #e9e9ed@25% | #292b31@25% |
| `accent300` | #d2cefd | #5d5294 |
| `accent` | #9184d9 | #796cbf |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent400` | #b5abfc | #796cbf |
| `text·40%` | #e9e9ed@40% | #292b31@40% |
| `accent100` | #f5f4ff | #2b2741 |
| `accent800` | #423a6a | #e7e5fe |
| `online` | #52cd86 | #009351 |
| `accent·70%` | #9184d9@70% | #796cbf@70% |
| `accent900` | #2b2741 | #f5f4ff |
| `text·22%` | #e9e9ed@22% | #292b31@22% |

## 4.3 · Mesaj istekleri — Message requests

Reference `reference/4.3-mesaj-istekleri.png` · light `reference/light/4.3-mesaj-istekleri.png` · data `layout/4.3-mesaj-istekleri.json`

> **Note (TR):** Tanımadığın birinin ilk mesajı burada bekler. Medya ve bağlantılar kabul edilene kadar açılmaz; şüpheli içerik işaretlenir. Gönderen, isteğinin görülüp görülmediğini bilemez.
> **EN:** A stranger's first message waits here. Media and links don't open until accepted; suspicious content is flagged. The sender can't tell whether the request was seen.

**Purpose.** Triage first messages from unknown people.

**Layout.**
- C7 header: back, title “Mesaj istekleri”, trailing C4 `ph:dots-three` 21.
- Privacy banner 16,116 370×67 (margin 16 16 0), `accent900` r18 + ring 1 pt `accent800`, pad 14 16, gap 12: `ph:shield-check` 22 `accent300` + `callout` `accent200` (2 lines).
- Request cards (pad 14 16 0, gap 10), each `surface` r20, pad 14 14 14 16, gap 13: row (gap 12): avatar 44 (palette C, letter Mono 500 16) · column (gap 4): username `monoUsername` + spacer + age `meta` Inter 400 12.5 `text·68%`; snippet `requestSnippet` Inter 400 14.5/20.3 `text·84%`, max 2 lines; optional flag line `monoTag` Mono 400 10.5 with a 13 pt icon, gap 6 (`ph:eye-slash` `text·68%` “Medya kabul edene kadar gizli”; `ph:warning` `danger` “Bağlantı içeriyor · açılmaz”). Action row (gap 8, 40 high, r12): “Engelle” (flex 1, inner 1 pt `divider`, `preview` style `danger`) · “Sil” (flex 1, `text`) · “Kabul et” (flex 1.3, 1 pt `accent` border, Inter 500 14.5 `accent300`).

**Texts.**

| TR | EN |
|---|---|
| Mesaj istekleri | Message requests |
| Kabul edene kadar sohbet açılmaz. Okuduğunu ve aktif olduğunu göremezler. | The chat won't open until you accept. They can't see that you've read it or that you're active. |
| 2 sa · Dün · 3 gün | 2 h · Yesterday · 3 days |
| Merhaba! Dünkü meetup'taki sunumunu çok beğendim, slaytları paylaşabilir misin? | Hi! I really liked your talk at yesterday's meetup — could you share the slides? *(user content, not localised)* |
| Fotoğraf | Photo |
| Medya kabul edene kadar gizli | Media hidden until you accept |
| Bağlantı içeriyor · açılmaz | Contains a link · won't open |
| Engelle | Block |
| Sil | Delete |
| Kabul et | Accept |
| *(empty, proposed)* Bekleyen istek yok. | No pending requests. |
| *(block confirmation, proposed)* {user} engellensin mi? · Sana bir daha yazamaz. Engellendiğini bilmez. · Engelle · Bildir · Vazgeç | Block {user}? · They can't message you again. They won't know they were blocked. · Block · Report · Cancel |

**States.** list / empty; per request: plain text, media (placeholder text + eye-slash flag), link (danger flag), suspicious. Actions: Engelle → confirmation (proposed alert “{user} engellensin mi?” · Engelle · Bildir · Vazgeç) → removed with a soft collapse (0.36 s); the blocked request stays in 4.1 as a blocked, read-only chat, where “Engeli kaldır” makes it a request again. Bildir (in that alert, proposed) → the report sheet (Kişi ekranı · Engelle ve Bildir), “Bu kişiyi de engelle” on: a report that left the sheet removes the card like Engelle. Sil → removed silently (sender is not told); Kabul et → chat moves to 4.1 and opens 5.1.

**Transitions.** Back → 4.1. Card tap → 4.4 preview. “…” → menu (proposed: “Tümünü sil” / “Delete all”).

**Animation.** Card removal: fade + height collapse 0.36 s `curveSoft`.

**Data.** Requests: `sender`, `receivedAt`, `firstMessagePreview`, `contentFlags {hasMedia, hasLink, suspicious}`.

**Elements.**

<details><summary>Element table 4.3 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.0.0 | 16,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 27.5,67.5 21×21 | icon | ph:caret-left (regular) | 21pt | text |  |  |
| r.0.1 | 76,56 250×44 | text | “Mesaj istekleri” | Inter 600 17/26.35 | text |  | grid place:center abs |
| r.0.2 | 342,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.2.0 | 353.5,67.5 21×21 | icon | ph:dots-three (regular) | 21pt | text |  |  |
| r.1 | 16,116 370×67.13 | shape |  |  |  | fill accent900 · r18 · shadow inset 0 0 0 1px accent800 | row gap 12 pad 14 16 14 16 mar 16 16 0 16 |
| r.1.0 | 32,130 22×39.13 | icon | ph:shield-check (regular) | 22pt | accent300 |  |  |
| r.1.1 | 66,130 304×39.13 | text | “Kabul edene kadar sohbet açılmaz. Okuduğunu ve aktif olduğunu görem…” | Inter 400 13.5/19.57 | accent200 |  |  |
| r.2 | 0,183.13 402×500.77 | stack |  |  |  |  | col gap 10 pad 14 16 0 16 |
| r.2.0 | 16,197.13 370×148.84 | shape |  |  |  | fill surface · r20 | col gap 13 pad 14 14 14 16 |
| r.2.0.0 | 32,211.13 340×67.84 | stack |  |  |  |  | row gap 12 |
| r.2.0.0.0 | 32,211.13 44×44 | text | “K” | Mono 500 16/16 | neutral100 | fill neutral800 · r50% | grid place:center |
| r.2.0.0.1 | 88,211.13 284×67.84 | stack |  |  |  |  | col gap 4 flex 1 |
| r.2.0.0.1.0 | 88,211.13 284×23.25 | stack |  |  |  |  | row ai:center gap 8 |
| r.2.0.0.1.0.0 | 88,211.13 94.5×23.25 | text | “kuzey_dev” | Mono 500 15/23.25 | text |  |  |
| r.2.0.0.1.0.1 | 190.5,222.75 148.73×0 | spacer |  |  |  |  | flex 1 |
| r.2.0.0.1.0.2 | 347.23,213.06 24.77×19.38 | text | “2 sa” | Inter 400 12.5/19.38 | text·68% |  |  |
| r.2.0.0.1.1 | 88,238.38 284×40.59 | text | “Merhaba! Dünkü meetup’taki sunumunu çok beğendim, slaytları paylaşa…” | Inter 400 14.5/20.3 clamp2 | text·84% |  |  |
| r.2.0.1 | 32,291.97 340×40 | stack |  |  |  |  | row gap 8 |
| r.2.0.1.0 | 32,291.97 97.58×40 | text | “Engelle” | Inter 400 14.5/22.48 | danger | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.2.0.1.1 | 137.58,291.97 97.58×40 | text | “Sil” | Inter 400 14.5/22.48 | text | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.2.0.1.2 | 243.16,291.97 128.84×40 | text | “Kabul et” | Inter 500 14.5/22.48 | accent300 | r12 · border 1px solid accent | grid place:center flex 1.3 |
| r.2.1 | 16,355.97 370×148.81 | shape |  |  |  | fill surface · r20 | col gap 13 pad 14 14 14 16 |
| r.2.1.0 | 32,369.97 340×67.81 | stack |  |  |  |  | row gap 12 |
| r.2.1.0.0 | 32,369.97 44×44 | text | “N” | Mono 500 16/16 | neutral100 | fill neutral800 · r50% | grid place:center |
| r.2.1.0.1 | 88,369.97 284×67.81 | stack |  |  |  |  | col gap 4 flex 1 |
| r.2.1.0.1.0 | 88,369.97 284×23.25 | stack |  |  |  |  | row ai:center gap 8 |
| r.2.1.0.1.0.0 | 88,369.97 73.5×23.25 | text | “n_aksoy” | Mono 500 15/23.25 | text |  |  |
| r.2.1.0.1.0.1 | 169.5,381.59 170.69×0 | spacer |  |  |  |  | flex 1 |
| r.2.1.0.1.0.2 | 348.19,371.91 23.81×19.38 | text | “Dün” | Inter 400 12.5/19.38 | text·68% |  |  |
| r.2.1.0.1.1 | 88,397.22 284×20.3 | text | “Fotoğraf” | Inter 400 14.5/20.3 clamp2 | text·84% |  |  |
| r.2.1.0.1.2 | 88,421.52 284×16.27 | text | “Medya kabul edene kadar gizli” | Mono 400 10.5/16.27 | text·68% |  | row ai:center gap 6 |
| r.2.1.0.1.2.0 | 88,423.14 13×13 | icon | ph:eye-slash (regular) | 13pt | text·68% |  |  |
| r.2.1.1 | 32,450.78 340×40 | stack |  |  |  |  | row gap 8 |
| r.2.1.1.0 | 32,450.78 97.58×40 | text | “Engelle” | Inter 400 14.5/22.48 | danger | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.2.1.1.1 | 137.58,450.78 97.58×40 | text | “Sil” | Inter 400 14.5/22.48 | text | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.2.1.1.2 | 243.16,450.78 128.84×40 | text | “Kabul et” | Inter 500 14.5/22.48 | accent300 | r12 · border 1px solid accent | grid place:center flex 1.3 |
| r.2.2 | 16,514.78 370×169.11 | shape |  |  |  | fill surface · r20 | col gap 13 pad 14 14 14 16 |
| r.2.2.0 | 32,528.78 340×88.11 | stack |  |  |  |  | row gap 12 |
| r.2.2.0.0 | 32,528.78 44×44 | text | “H” | Mono 500 16/16 | neutral100 | fill neutral800 · r50% | grid place:center |
| r.2.2.0.1 | 88,528.78 284×88.11 | stack |  |  |  |  | col gap 4 flex 1 |
| r.2.2.0.1.0 | 88,528.78 284×23.25 | stack |  |  |  |  | row ai:center gap 8 |
| r.2.2.0.1.0.0 | 88,528.78 126×23.25 | text | “hediye_kazan” | Mono 500 15/23.25 | text |  |  |
| r.2.2.0.1.0.1 | 222,540.41 108.31×0 | spacer |  |  |  |  | flex 1 |
| r.2.2.0.1.0.2 | 338.31,530.72 33.69×19.38 | text | “3 gün” | Inter 400 12.5/19.38 | text·68% |  |  |
| r.2.2.0.1.1 | 88,556.03 284×40.59 | text | “Tebrikler! Ödülünü almak için hemen bağlantıya tıkla: bit.ly/…” | Inter 400 14.5/20.3 clamp2 | text·84% |  |  |
| r.2.2.0.1.2 | 88,600.63 284×16.27 | text | “Bağlantı içeriyor · açılmaz” | Mono 400 10.5/16.27 | danger |  | row ai:center gap 6 |
| r.2.2.0.1.2.0 | 88,602.25 13×13 | icon | ph:warning (regular) | 13pt | danger |  |  |
| r.2.2.1 | 32,629.89 340×40 | stack |  |  |  |  | row gap 8 |
| r.2.2.1.0 | 32,629.89 97.58×40 | text | “Engelle” | Inter 400 14.5/22.48 | danger | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.2.2.1.1 | 137.58,629.89 97.58×40 | text | “Sil” | Inter 400 14.5/22.48 | text | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.2.2.1.2 | 243.16,629.89 128.84×40 | text | “Kabul et” | Inter 500 14.5/22.48 | accent300 | r12 · border 1px solid accent | grid place:center flex 1.3 |

</details>

Colour legend 4.3:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `accent900` | #2b2741 | #f5f4ff |
| `accent800` | #423a6a | #e7e5fe |
| `accent300` | #d2cefd | #5d5294 |
| `accent200` | #e7e5fe | #423a6a |
| `surface` | #232532 | #e4e7f5 |
| `neutral100` | #f3f5fe | #292b31 |
| `neutral800` | #3f424d | #dfe2f0 |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `text·84%` | #e9e9ed@84% | #292b31@84% |
| `danger` | #f2716a | #c53637 |
| `divider` | #e9e9ed@16% | #292b31@16% |
| `accent` | #9184d9 | #796cbf |

## 4.4 · İstek önizleme — Request preview

Reference `reference/4.4-istek-onizleme.png` · light `reference/light/4.4-istek-onizleme.png` · data `layout/4.4-istek-onizleme.json`

> **Note (TR):** İstek sohbet gibi görünür ama yazma alanı yoktur; karar çubuğu onun yerini alır. Kabul edilene kadar okundu bilgisi gitmez, bağlantılar tıklanmaz.
> **EN:** The request looks like a chat but has no input field; the decision bar replaces it. Until accepted, no read receipt is sent and links can't be tapped.

**Purpose.** Read a request in context before deciding.

**Layout.**
- C8 chat header: avatar 38 (palette C), “kuzey_dev”, status “Mesaj isteği”, trailing C4-40 `ph:dots-three` 20; dotted rule at y 104.
- Messages column (pad 122 14 0, gap 6): profile block (pad 8 0 16, centred, gap 10): avatar 64 (Mono 500 23), name `monoUsernameXL` Mono 500 17, explanation `callout` `text·68%` centred, max width 290. Date separator (pad 6 2, gap 12): dotted rules (`text·28%`, r .8) either side of “BUGÜN 16:12” `monoSectionLabel` `text·68%`. Incoming bubbles (C15); the link bubble shows `ph:link-break` 16 `text·68%` + URL `monoLink` Mono 400 13 `text·84%`, caption `metaInBubble` Inter 400 12.5 `text·68%`, time.
- Decision bar: bottom sheet-like panel 0,713 402×161, `surface`, top radius 30, pad 18 16 40, gap 14: explanation `callout` `text·68%` centred (pad 0 10); buttons row (gap 8, 50 high, r14): “Engelle” (flex 1, `danger`, inner `divider`), “Sil” (flex 1), “Kabul et” (flex 1.4, 1 pt `accent`, `accent300` Inter 500 16, glow `0 0 26px -8px accent·70%`). Button labels Inter 400/500 16.

**Texts.**

| TR | EN |
|---|---|
| Mesaj isteği | Message request |
| Daha önce yazışmadınız. Seni kullanıcı adını tam yazarak buldu. | You haven't talked before. They found you by typing your exact username. |
| Bugün 16:12 | Today 16:12 |
| Bağlantı, kabul edene kadar açılmaz | The link won't open until you accept |
| Kabul edersen sohbet açılır ve yanıt yazabilirsin. kuzey_dev isteği gördüğünü bilmez. | If you accept, the chat opens and you can reply. kuzey_dev won't know you've seen the request. |
| Engelle · Sil · Kabul et | Block · Delete · Accept |
| *(“…” menu, proposed)* Bildir · Engelle · Sil | Report · Block · Delete |

**States.** As 4.3 actions (the block confirmation with Bildir included). Links are inert (no tap, no preview fetch). Media show as placeholders. “…” (proposed) → the floating menu: Bildir `ph:warning` · Engelle `ph:prohibit` `danger` · Sil `ph:trash` `danger`.

**Transitions.** Back → 4.3. Kabul et → this screen becomes 5.1 (decision bar cross-fades into the composer C21, header status becomes the presence line). Engelle/Sil → pop to 4.3 with the card removed. Bildir → the report sheet; a report that blocked the sender closes back to 4.3 with the card gone, otherwise the preview stays.

**Animation.** Accept: decision bar slides down 0.4 s while the composer fades in (`curveSoft`).

**Data.** Request thread messages (decrypted locally), sender profile, flags.

**Elements.**

<details><summary>Element table 4.4 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×108.63 | shape |  |  |  | fill bg·80% · backdropFilter blur(20px) saturate(1.4) | row ai:center gap 10 pad 54 14 10 12 abs z3 |
| r.0.0 | 12,56.31 40×40 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 22,66.31 20×20 | icon | ph:caret-left (regular) | 20pt | text |  |  |
| r.0.1 | 62,57.31 38×38 | text | “K” | Mono 500 14/14 | neutral100 | fill neutral800 · r50% | grid place:center |
| r.0.2 | 110,54 228×44.63 | stack |  |  |  |  | col gap 2 flex 1 |
| r.0.2.0 | 110,54 228×23.25 | text | “kuzey_dev” | Mono 500 15/23.25 | text |  | row ai:center gap 5 |
| r.0.2.1 | 110,79.25 228×19.38 | text | “Mesaj isteği” | Inter 400 12.5/19.38 | text·68% |  | row ai:center gap 6 |
| r.0.3 | 348,56.31 40×40 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.3.0 | 358,66.31 20×20 | icon | ph:dots-three (regular) | 20pt | text |  |  |
| r.1 | 0,104 402×2 | shape |  |  |  | fill radial-gradient(circle, text·20% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | abs z3 |
| r.2 | 0,0 402×543.73 | stack |  |  |  |  | col gap 6 pad 122 14 0 14 |
| r.2.0 | 14,122 374×173.47 | stack |  |  |  |  | col ai:center gap 10 pad 8 0 16 0 |
| r.2.0.0 | 169,130 64×64 | text | “K” | Mono 500 23/23 center | neutral100 | fill neutral800 · r50% | grid place:center |
| r.2.0.1 | 147.44,204 107.11×26.34 | text | “kuzey_dev” | Mono 500 17/26.35 center | text |  |  |
| r.2.0.2 | 56,240.34 290×39.13 | text | “Daha önce yazışmadınız. Seni kullanıcı adını tam yazarak buldu.” | Inter 400 13.5/19.57 center | text·68% |  | maxW 290 |
| r.2.1 | 14,301.47 374×28.27 | text | “Bugün 16:12” | Mono 400 10.5/16.27 ls1.05 UPPER | text·68% |  | row ai:center gap 12 pad 6 2 6 2 |
| r.2.1.0 | 16,314.59 126.8×2 | shape |  |  |  | fill radial-gradient(circle, text·28% 0.8px, transparent 1.2px) 0 50% / 5px 2px repeat-x | flex 1 |
| r.2.1.1 | 259.2,314.59 126.8×2 | shape |  |  |  | fill radial-gradient(circle, text·28% 0.8px, transparent 1.2px) 0 50% / 5px 2px repeat-x | flex 1 |
| r.2.2 | 14,335.73 290×61.16 | shape |  |  |  | fill surface · r20 | col self:flex-start gap 5 pad 9 13 8 13 maxW 290 |
| r.2.2.0 | 27,344.73 264×44.16 | text | “Merhaba! Dünkü meetup’taki sunumunu çok beğendim.” | Inter 400 16/22.08 | text |  |  |
| r.2.3 | 14,402.89 290×61.16 | shape |  |  |  | fill surface · r20 | col self:flex-start gap 5 pad 9 13 8 13 maxW 290 |
| r.2.3.0 | 27,411.89 264×44.16 | text | “Slaytları paylaşabilir misin? Bağlantıyı da bırakıyorum:” | Inter 400 16/22.08 | text |  |  |
| r.2.4 | 14,470.05 259.31×73.69 | shape |  |  |  | fill surface · r20/20/20/6 | col self:flex-start gap 5 pad 9 13 8 13 maxW 290 |
| r.2.4.0 | 27,479.05 233.31×17.94 | text | “slides.kuzey.dev/meetup” | Mono 400 13/17.94 | text·84% |  | row ai:center gap 8 |
| r.2.4.0.0 | 27,480.02 16×16 | icon | ph:link-break (regular) | 16pt | text·68% |  |  |
| r.2.4.1 | 27,501.98 233.31×17.25 | text | “Bağlantı, kabul edene kadar açılmaz” | Inter 400 12.5/17.25 | text·68% |  |  |
| r.2.4.2 | 227.39,524.23 32.92×11.5 | text | “16:13” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.3 | 0,712.88 402×161.13 | shape |  |  |  | fill surface · r30/30/0/0 | col gap 14 pad 18 16 40 16 abs |
| r.3.0 | 16,730.88 370×39.13 | text | “Kabul edersen sohbet açılır ve yanıt yazabilirsin. kuzey_dev isteği…” | Inter 400 13.5/19.57 center | text·68% |  | pad 0 10 0 10 |
| r.3.1 | 16,784 370×50 | stack |  |  |  |  | row gap 8 |
| r.3.1.0 | 16,784 103.53×50 | text | “Engelle” | Inter 400 16/24.8 | danger | r14 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.3.1.1 | 127.53,784 103.53×50 | text | “Sil” | Inter 400 16/24.8 | text | r14 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.3.1.2 | 239.06,784 146.94×50 | text | “Kabul et” | Inter 500 16/24.8 | accent300 | r14 · border 1px solid accent · shadow 0 0 26px -8px accent·70% | grid place:center flex 1.4 |

</details>

Colour legend 4.4:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `bg·80%` | #161826@80% | #f3f5fe@80% |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `neutral100` | #f3f5fe | #292b31 |
| `neutral800` | #3f424d | #dfe2f0 |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `text·20%` | #e9e9ed@20% | #292b31@20% |
| `text·28%` | #e9e9ed@28% | #292b31@28% |
| `surface` | #232532 | #e4e7f5 |
| `text·84%` | #e9e9ed@84% | #292b31@84% |
| `danger` | #f2716a | #c53637 |
| `divider` | #e9e9ed@16% | #292b31@16% |
| `accent300` | #d2cefd | #5d5294 |
| `accent` | #9184d9 | #796cbf |
| `accent·70%` | #9184d9@70% | #796cbf@70% |


# 05 · Sohbet — Chat (“Her mesajın yeri belli” / “Every message's whereabouts are known”)

> **Section note (TR):** Başlıkta Ghost sayacı ve aktif durumu, her giden mesajın altında durumu. Push bildirimi olmadığı için durum metni açık yazılır: mesajın sende mi, yolda mı, onda mı olduğu her an görünür.
> **EN:** The header shows the Ghost counter and activity; every outgoing message shows its state. Because there are no push notifications, the state is spelled out: whether the message is with you, on its way, or with them is always visible.

## 5.1 · Sohbet — Chat

Reference `reference/5.1-sohbet.png` · urgent `reference/urgent/5.1-sohbet-urgent.png` · light `reference/light/5.1-sohbet.png` (native light `reference/8.2-sohbet-acik.png`, EN `reference/9.2-chat-en.png`) · data `layout/5.1-sohbet.json`

> **Note (TR):** Başlıktaki sayaç canlı akar; dokununca Ghost ayarı açılır (5.5). Son giden mesajın durumu açık cümleyle, öncekiler yalnızca simgeyle. Süreli mesajın sayacı karşı taraf açınca başlar.
> **EN:** The counter in the header runs live; tapping it opens the Ghost settings (5.5). The last outgoing message's state is shown as a sentence, earlier ones only as an icon. A disappearing message's timer starts when the other side opens it.

**Purpose.** The conversation.

**Layout.**
- C8 header: back 40 · avatar 38 (palette A “D”) · “deniz” + verified seal 15 `accent` · status “Az önce aktifti” · Ghost chip (C17) 276.8,59.3 111×34 “23:41:07” — urgent: “00:42:10” danger chip. Dotted rule at y 104.
- Messages (column, bottom-aligned, gap 6, pad 0 14 100): scrolls under the header (the topmost items sit behind the translucent header).
  1. System pill (centred, max 330): `fillSubtle` r14, pad 6 12, gap 7, `ph:ghost` 14 + `monoSystemPill` Mono 400 11/15.4 `text·68%` “Ghost mod açık · 24 saat”.
  2. Incoming text bubble “Bu akşam boş musun?” 14,114.5 195.5×55.6 (round 20 — first of the run).
  3. Incoming photo bubble 14,176 268×234.6 (tail 20/20/20/6, pad 5 5 8 5, margin-bottom 14 for the reaction): photo 258×176 r15 (placeholder gradient in the design → real image, aspect-fill), caption “Şu yeni yer, Moda'da” (pad 2 8 0), time; reaction chip at bubble-left +12, bottom −15: 34×27, r14, `accent800`, ring `0 0 0 2.5px bg`, emoji 14.
  4. Outgoing reply bubble 139.6,430.7 248.5×113 (max 270, pad 6 6 8 6, tail 20/20/6/20): quote block 236×52 r14 `bg·32%` pad 6 6 6 10, gap 10: author line `quoteAuthor` Inter 600 12.5 `accent300` with `ph:arrow-bend-up-left` 13, kind line `quoteBody` Inter 400 13.5 `text·68%` with `ph:image` 14 + thumbnail 40×40 r8; text “Çok güzelmiş! 8 gibi olur mu?” `accent100` (pad 0 7); time + read checks (fill `accent300`).
  5. Incoming voice bubble 14,549.8 239.8×69.5 (pad 9 12 8 9): play button 36 circle, 1 pt `accent` border, `ph:play` fill 15 `accent300`; waveform 132×28 — 31 bars 2.4 pt wide, round caps, pitch 4.4; played part `accent300`, rest `text·38%`; duration `monoVoiceDuration` Mono 400 11 `text·68%`; time.
  6. Outgoing “Tamam, iskelede buluşalım” (round), time + delivered checks (regular `text·68%`).
  7. Outgoing “Kapı kodu 4471” (tail), meta row gap 8: timer badge `ph:timer` 13 + “5 dk” `monoTimerInBubble` Mono 400 10.5 `accent300` (gap 3) · time + sent check.
  8. Status sentence under the last outgoing bubble (right-aligned, pad-right 4, gap 6): `ph:check` 14 `text·68%` + `statusLineEmphasis` Inter 500 12 “Gönderildi” in `text` + `statusLine` Inter 400 12 “ · Alıcı uygulamayı açınca iletilecek” `text·68%`.
- Composer C21 (0,780 402×94).
- Urgent banner (only < 1 h): floating 12,714 378×60 (left/right 12, bottom 100, z 2), `surface·92%` + backdrop blur 16, r16, inner ring `danger·45%` + shadow `0 10px 30px #000@30%`, pad 11 14, gap 10: `ph:ghost` fill 18 `danger` + `bullet` Inter 400 13.5/18.9 text with the duration in Mono `danger`.

**Texts.**

| TR | EN |
|---|---|
| Az önce aktifti | Active just now (09) |
| *(proposed)* Aktif · {n} dk önce aktifti · Bugün {HH:mm} aktifti | Active · Active {n} min ago · Active today at {HH:mm} |
| Ghost mod açık · 24 saat | Ghost mode on · 24 hours (09) |
| Fotoğraf | Photo (09) |
| Gönderildi · Alıcı uygulamayı açınca iletilecek | Sent · Will be delivered when they open the app (09) |
| *(proposed)* İletildi · Cihazına ulaştı · Okundu · İletilemedi · Tekrar gönder | Delivered · Reached their device · Read · Not delivered · Send again |
| Mesaj | Message (09) |
| 5 dk | 5 min (09) |
| Sohbet {43 dk} içinde iki taraftan silinecek. Bir mesaj sayacı sıfırlar. | Deleted from both ends in {43 min}. Any message resets the timer. (09) |
| *(user content in the demo)* Bu akşam boş musun? · Şu yeni yer, Moda'da · Çok güzelmiş! 8 gibi olur mu? · Tamam, iskelede buluşalım · Kapı kodu 4471 | Are you free tonight? · That new place in Moda · Looks great! Around 8? · OK, meet you at the pier · Door code 4471 (09) |
| *(blocked, proposed)* Engellendi · {user} engellendi. Yazdıkları sana ulaşmaz, sen de ona yazamazsın; engellendiğini bilmez. · Engeli kaldır | Blocked · {user} is blocked. Their messages don't reach you and you can't write to them; they don't know they're blocked. · Unblock |
| *(system lines, proposed — 5.5 note)* Ghost mod açıldı · 24 saat · Ghost mod kapatıldı · Süreli mesajlar: 5 dk · {user} ekran görüntüsü aldı · Güvenlik numarası değişti | Ghost mode turned on · 24 hours · Ghost mode turned off · Disappearing messages: 5 min · {user} took a screenshot · Safety number changed |

**States.** Ghost chip calm / urgent / off (5.2 “kapalı”); presence line variants (only for accepted contacts with presence shared — otherwise no status line or the last-seen line hidden); verified seal on/off; per-message C16 states; failed message shows “İletilemedi” sentence + “Tekrar gönder” (5.4 note); urgent banner; disappearing message meta badge; reactions; reply quotes; typing input grows (max 5 lines, proposed); blocked (proposed): the status line says “Engellendi”, the Ghost chip stays as it is at 45 % and does nothing, the composer's place goes to 4.4's decision bar look (`surface`, top radius 30, pad 18 16 40, gap 14: the sentence in `callout` `text·68%`, centred, over “Engeli kaldır” 50 high r14, inner 1 pt `divider`, `rowTitle` `text`), and 5.3 has no reaction bar, no Yanıtla and no Herkesten sil (nothing reaches a blocked person). “Engeli kaldır” cross-fades the composer back.

**Transitions.** Back → 4.1. Ghost chip → 5.5 sheet. Header avatar/name → contact info / verification 6.1 (proposed). Long-press a bubble → 5.3. Tap photo → media viewer; tap view-once → 5.6. “+” → attachment menu (photo, file, location, live location — proposed). Mic: hold to record (proposed).

**Animation.** Header countdown ticks every second (tabular digits, no layout shift). New bubble: fade + rise 8 pt 0.36 s `curveOut`. Status sentence/icon cross-fade 0.36 s on state change. Urgent switch: chip and banner cross-fade 0.8 s; banner enters with fade + 8 pt rise. Keyboard: composer follows the keyboard with the system curve.

**Data.** Chat: peer profile (username, palette, verified, presence + lastSeen if permitted), ghost {enabled, duration, remaining}, disappearing timer setting; messages: id, direction, kind (text/photo/video/voice/file/location/liveLocation/sticker/system/deleted/viewOnce), text, media refs, reply-to, reactions, timestamps, status, disappearing TTL/remaining.

**Elements.**

<details><summary>Element table 5.1 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×108.63 | shape |  |  |  | fill bg·80% · backdropFilter blur(20px) saturate(1.4) | row ai:center gap 10 pad 54 14 10 12 abs z3 |
| r.0.0 | 12,56.31 40×40 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 22,66.31 20×20 | icon | ph:caret-left (regular) | 20pt | text |  |  |
| r.0.1 | 62,57.31 38×38 | text | “D” | Mono 500 14/14 | accent100 | fill accent800 · r50% | grid place:center |
| r.0.2 | 110,54 156.8×44.63 | stack |  |  |  |  | col gap 2 flex 1 |
| r.0.2.0 | 110,54 156.8×23.25 | text | “deniz” | Mono 500 15/23.25 | text |  | row ai:center gap 5 |
| r.0.2.0.0 | 167.5,58.13 15×15 | icon | ph:seal-check (fill) | 15pt | accent |  |  |
| r.0.2.1 | 110,79.25 156.8×19.38 | text | “Az önce aktifti” | Inter 400 12.5/19.38 | text·68% |  | row ai:center gap 6 |
| r.0.3 | 276.8,59.31 111.2×34 | shape |  |  |  | fill accent900 · r17 · shadow inset 0 0 0 1px accent800 | row ai:center gap 6 pad 0 12 0 10 |
| r.0.3.0 | 286.8,68.31 16×16 | icon | ph:ghost (regular) | 16pt | accent200 |  |  |
| r.0.3.1 | 308.8,67.02 67.2×18.59 | text | “23:41:07” | Mono 400 12/18.6 tnum | accent200 |  |  |
| r.1 | 0,104 402×2 | shape |  |  |  | fill radial-gradient(circle, text·20% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | abs z3 |
| r.2 | 0,0 402×874 | stack |  |  |  |  | col jc:flex-end gap 6 pad 0 14 100 14 flex 1 |
| r.2.0 | 86.09,81.13 229.81×27.39 | shape |  |  |  | fill text·6% · r14 | row ai:center self:center gap 7 pad 6 12 6 12 maxW 330 |
| r.2.0.0 | 98.09,87.81 14×14 | icon | ph:ghost (regular) | 14pt | text·68% |  |  |
| r.2.0.1 | 119.09,87.13 184.81×15.39 | text | “Ghost mod açık · 24 saat” | Mono 400 11/15.4 | text·68% |  |  |
| r.2.1 | 14,114.52 195.5×55.58 | shape |  |  |  | fill surface · r20 | col self:flex-start gap 5 pad 9 13 8 13 maxW 290 |
| r.2.1.0 | 27,123.52 169.5×22.08 | text | “Bu akşam boş musun?” | Inter 400 16/22.08 | text |  |  |
| r.2.1.1 | 163.58,150.59 32.92×11.5 | text | “18:02” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.2 | 14,176.09 268×234.58 | shape |  |  |  | fill surface · r20/20/20/6 | col self:flex-start gap 5 pad 5 5 8 5 mar 0 0 14 0 maxW 290 |
| r.2.2.0 | 19,181.09 258×176 | shape |  |  |  | fill radial-gradient(circle at 16% 57%, rgba(255, 210, 150, 0.95) 0px, rgba(255, 210, 150, 0.95) 1.4px, transparent 2.4px), r… (full value in layout JSON) · r15 |  |
| r.2.2.1 | 19,362.09 258×24.08 | text | “Şu yeni yer, Moda’da” | Inter 400 16/22.08 | text |  | pad 2 8 0 8 |
| r.2.2.2 | 236.08,391.17 32.92×11.5 | text | “18:03” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 mar 0 8 0 0 |
| r.2.2.3 | 26,398.67 34×27 | text | “❤️” | Inter 400 14/14 | text | fill accent800 · r14 · shadow 0 0 0 2.5px bg | row ai:center pad 0 8 0 8 abs |
| r.2.3 | 139.55,430.67 248.45×113.08 | shape |  |  |  | fill accent800 · r20/20/6/20 | col self:flex-end gap 5 pad 6 6 8 6 maxW 270 |
| r.2.3.0 | 145.55,436.67 236.45×52 | shape |  |  |  | fill bg·32% · r14 | row ai:center gap 10 pad 6 6 6 10 |
| r.2.3.0.0 | 155.55,443.73 170.45×37.88 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.3.0.0.0 | 155.55,443.73 170.45×17.25 | text | “deniz” | Inter 600 12.5/17.25 | accent300 |  | row ai:center gap 5 |
| r.2.3.0.0.0.0 | 155.55,445.86 13×13 | icon | ph:arrow-bend-up-left (regular) | 13pt | accent300 |  |  |
| r.2.3.0.0.1 | 155.55,462.98 170.45×18.63 | text | “Fotoğraf” | Inter 400 13.5/18.63 | text·68% |  | row ai:center gap 5 |
| r.2.3.0.0.1.0 | 155.55,465.3 14×14 | icon | ph:image (regular) | 14pt | text·68% |  |  |
| r.2.3.0.1 | 336,442.67 40×40 | shape |  |  |  | fill radial-gradient(circle at 16% 57%, rgba(255, 210, 150, 0.95) 0px, rgba(255, 210, 150, 0.95) 1.4px, transparent 2.4px), r… (full value in layout JSON) · r8 |  |
| r.2.3.1 | 145.55,493.67 236.45×22.08 | text | “Çok güzelmiş! 8 gibi olur mu?” | Inter 400 16/22.08 | accent100 |  | pad 0 7 0 7 |
| r.2.3.2 | 323.08,520.75 51.92×15 | text | “18:05” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 mar 0 7 0 0 |
| r.2.3.2.0 | 360,520.75 15×15 | icon | ph:checks (fill) | 15pt | accent300 |  |  |
| r.2.4 | 14,549.75 239.81×69.5 | shape |  |  |  | fill surface · r20/20/20/6 | col self:flex-start gap 5 pad 9 12 8 9 maxW 290 |
| r.2.4.0 | 23,558.75 218.81×36 | stack |  |  |  |  | row ai:center gap 10 |
| r.2.4.0.0 | 23,558.75 36×36 | shape |  |  |  | r50% · border 1px solid accent | grid place:center |
| r.2.4.0.0.0 | 33.5,569.25 15×15 | icon | ph:play (fill) | 15pt | accent300 |  |  |
| r.2.4.0.1 | 69,562.75 132×28 | svg | custom svg (waveform) |  | text |  |  |
| r.2.4.0.2 | 211,569.16 30.81×15.17 | text | “0:42” | Mono 400 11/15.18 | text·68% |  |  |
| r.2.4.1 | 208.89,599.75 32.92×11.5 | text | “18:07” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.5 | 159.05,625.25 228.95×59.08 | shape |  |  |  | fill accent800 · r20 | col self:flex-end gap 5 pad 9 13 8 13 maxW 290 |
| r.2.5.0 | 172.05,634.25 202.95×22.08 | text | “Tamam, iskelede buluşalım” | Inter 400 16/22.08 | accent100 |  |  |
| r.2.5.1 | 323.08,661.33 51.92×15 | text | “18:09” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.5.1.0 | 360,661.33 15×15 | icon | ph:checks (regular) | 15pt | text·68% |  |  |
| r.2.6 | 246.08,690.33 141.92×59.08 | shape |  |  |  | fill accent800 · r20/20/6/20 | col self:flex-end gap 5 pad 9 13 8 13 maxW 290 |
| r.2.6.0 | 259.08,699.33 115.92×22.08 | text | “Kapı kodu 4471” | Inter 400 16/22.08 | accent100 |  |  |
| r.2.6.1 | 269.67,726.41 105.33×15 | stack |  |  |  |  | row ai:center self:flex-end gap 8 |
| r.2.6.1.0 | 269.67,727.41 45.41×13 | text | “5 dk” | Mono 400 10.5/10.5 | accent300 |  | row ai:center gap 3 |
| r.2.6.1.0.0 | 269.67,727.41 13×13 | icon | ph:timer (regular) | 13pt | accent300 |  |  |
| r.2.6.1.1 | 323.08,726.41 51.92×15 | text | “18:10” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center gap 4 |
| r.2.6.1.1.0 | 360,726.41 15×15 | icon | ph:check (regular) | 15pt | text·68% |  |  |
| r.2.7 | 108.95,755.41 279.05×18.59 | stack |  |  |  |  | row ai:center self:flex-end gap 6 pad 0 4 0 0 |
| r.2.7.0 | 108.95,757.7 14×14 | icon | ph:check (regular) | 14pt | text·68% |  |  |
| r.2.7.1 | 128.95,755.41 255.05×18.59 | text | “Gönderildi · Alıcı uygulamayı açınca iletilecek” | Inter 400 12/18.6 | text·68% |  |  |
| r.2.7.1.0 | 128.95,756.41 59.14×15 | text | “Gönderildi” | Inter 500 12/18.6 | text |  |  |
| r.3 | 0,780 402×94 | shape |  |  |  | fill linear-gradient(to bottom, transparent, bg 30%) | row ai:flex-end gap 8 pad 14 12 36 12 abs z2 |
| r.3.0 | 12,794 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.3.0.0 | 23,805 22×22 | icon | ph:plus (regular) | 22pt | text |  |  |
| r.3.1 | 64,794 274×44 | shape |  |  |  | fill surface · r22 · shadow inset 0 0 0 0.5px text·14% | row ai:center gap 12 pad 0 12 0 16 flex 1 minH 44 |
| r.3.1.0 | 80,803.59 180×24.8 | text | “Mesaj” | Inter 400 16/24.8 | text·50% |  | flex 1 |
| r.3.1.1 | 272,805.5 21×21 | icon | ph:timer (regular) | 21pt | text·55% |  |  |
| r.3.1.2 | 305,805.5 21×21 | icon | ph:sticker (regular) | 21pt | text·55% |  |  |
| r.3.2 | 346,794 44×44 | shape |  |  |  | r50% · border 1px solid accent · shadow 0 0 20px -6px accent·70% | grid place:center |
| r.3.2.0 | 357.5,805.5 21×21 | icon | ph:microphone (regular) | 21pt | accent300 |  |  |

</details>

Colour legend 5.1:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `bg·80%` | #161826@80% | #f3f5fe@80% |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `accent100` | #f5f4ff | #2b2741 |
| `accent800` | #423a6a | #e7e5fe |
| `accent` | #9184d9 | #796cbf |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent200` | #e7e5fe | #423a6a |
| `accent900` | #2b2741 | #f5f4ff |
| `text·20%` | #e9e9ed@20% | #292b31@20% |
| `text·6%` | #e9e9ed@6% | #292b31@6% |
| `surface` | #232532 | #e4e7f5 |
| `bg·32%` | #161826@32% | #f3f5fe@32% |
| `accent300` | #d2cefd | #5d5294 |
| `text·14%` | #e9e9ed@14% | #292b31@14% |
| `text·50%` | #e9e9ed@50% | #292b31@50% |
| `text·55%` | #e9e9ed@55% | #292b31@55% |
| `accent·70%` | #9184d9@70% | #796cbf@70% |

## 5.2 · Medya ve konum — Media and location

Reference `reference/5.2-medya-ve-konum.png` · light `reference/light/5.2-medya-ve-konum.png` · data `layout/5.2-medya-ve-konum.json`

> **Note (TR):** Aynı balon dili tüm içerik türlerine uzanır. Canlı konumun süresi balonun içinde geri sayar ve tek dokunuşla durur. Tek görüntülemelik medya önizleme göstermez; silinen mesaj yerinde iz bırakır.
> **EN:** The same bubble language extends to every content type. Live location's time counts down inside the bubble and stops with one tap. View-once media shows no preview; a deleted message leaves a trace in its place.

**Purpose.** Catalogue of content bubbles (chat with mert_k, Ghost off).

**Layout.**
- C8 header: avatar 38 palette B with 11 pt online dot (`online`, ring 2 pt `bg`); status “Aktif” with a 7 pt `online` dot (gap 6); Ghost chip **off**: inner 1 pt `divider`, ghost 16 + “kapalı” Mono 400 12 `text·68%`.
- Bubbles (bottom-aligned, gap 6):
  - File (outgoing, max 290, pad 10 12 8 10; partly under the header in the frame): file tile 42×50 r10 `bg·35%` with `ph:file-pdf` 25 `accent300`; name `bubbleTitleS` Inter 500 15 `accent100` (single line); meta `monoFileMeta` Mono 400 10.5 `text·68%` “2,1 MB · PDF · 4 sayfa”; time + read.
  - Video (incoming): frame 236×124 r15 (poster image); centre play button 46 circle `#0c0d16@45%` + backdrop blur 8 + inner ring 1 pt `#f5f4ff@35%`, `ph:play` fill 19 `#f5f4ff`; bottom-left label pad 3 7 r8 `#0c0d16@55%` “0:18 · 12,4 MB” Mono 400 10.5 `#f5f4ff`.
  - Location (incoming, pad 8 12 8 8): map tile 64×64 r12 (stylised dotted map: `bg` with a 8 pt dot grid `text·18%`, dotted “roads” `text·46%`/`text·38%`, park area `accent·70%` dots; pin `ph:map-pin` fill 24 `accent300` with drop shadow) + title `bubbleTitleS` “Moda İskelesi” + subtitle `metaInBubble` “Kadıköy, İstanbul”.
  - Live location (outgoing, max 266, pad 5 5 8 5): map 256×92 r14 with the live dot (18 `accent400`, ring 3 `bg`, glow `0 0 18px accent·80%`) and two pulse rings (as 3.1); footer (pad 2 4 0 6, gap 10): `ph:navigation-arrow` 15 `accent300` + “Canlı konum” Inter 500 15 `accent100`, remaining “47 dk kaldı” Mono 10.5 `text·68%`; “Durdur” pill 32 high pad 0 13 r16 `bg·55%` Inter 500 13.5 `danger`.
  - Deleted (incoming tombstone): transparent, inner 1 pt `divider`, r20; `ph:prohibit` 16 + “Bu mesaj herkesten silindi” `bubbleTombstone` Inter 400 **italic** 14.5 `text·68%`.
  - View-once (incoming, pad 9 13 8 9): 36 circle with **1.5 pt dashed** `accent300` ring + “1” Mono 400 13 `accent300`; title `bubbleTitle` Inter 500 15.5 “Fotoğraf”; subtitle `metaInBubble` “Tek görüntülemelik · açmak için dokun”.
  - Text sticker (incoming, no bubble, margin 8 0 0 8, gap 10, bottom-aligned): card rotated −6°, fill `accent200`, r22, pad 12 18 11 18, ring `0 0 0 4px #f5f4ff` + shadow `0 12px 26px #000@35%`; “tamam!” `displaySticker` Doto 900 32/28.8 `accent900` + mini line mark (rings Ø10 stroke 2, `accent700`); time beside it `monoTag` Mono 10.5 `text·68%`.
- Composer C21.

**Texts.**

| TR | EN |
|---|---|
| Aktif | Active |
| kapalı | off |
| 2,1 MB · PDF · 4 sayfa | 2.1 MB · PDF · 4 pages |
| 0:18 · 12,4 MB | 0:18 · 12.4 MB |
| Moda İskelesi · Kadıköy, İstanbul | *(place names, not localised)* |
| Canlı konum | Live location |
| 47 dk kaldı | 47 min left |
| Durdur | Stop |
| Bu mesaj herkesten silindi | This message was deleted for everyone (09) |
| Fotoğraf · Tek görüntülemelik · açmak için dokun | Photo · View once · tap to open |
| tamam! | *(sticker artwork — keep as drawn)* |
| *(after open, proposed)* Açıldı | Opened |

Number formatting follows the locale (TR “2,1 MB”, EN “2.1 MB”; TR thousands “3.920”, EN “3,920”).

**States.** File: downloading (progress ring around the tile, proposed) / ready; video: poster / playing (full-screen player); live location: active (countdown + Durdur) / ended (bubble greys to `text·68%`, footer “Canlı konum paylaşımı bitti”); view-once: unopened / opened (subtitle “Açıldı”, ring solid `text·40%` — proposed) / expired.

**Transitions.** File → Quick Look; video → player; location → map sheet (proposed); live location “Durdur” → stop sharing (confirmation not drawn); view-once → 5.6.

**Animation.** Live dot pulse rings (2.8 s `ease-out`, staggered 1.4 s; Reduce Motion: off); remaining minutes tick each minute.

**Data.** Attachment metadata (name, size, type, page count, duration, poster), location (name, locality, coordinates), live-location session (expiresAt, isOwn), view-once state.

**Elements.**

<details><summary>Element table 5.2 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×108.63 | shape |  |  |  | fill bg·80% · backdropFilter blur(20px) saturate(1.4) | row ai:center gap 10 pad 54 14 10 12 abs z3 |
| r.0.0 | 12,56.31 40×40 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 22,66.31 20×20 | icon | ph:caret-left (regular) | 20pt | text |  |  |
| r.0.1 | 62,57.31 38×38 | text | “M” | Mono 500 14/14 | accent100 | fill accent700 · r50% | grid place:center |
| r.0.1.0 | 89,84.31 11×11 | shape |  |  |  | fill online · r50% · shadow 0 0 0 2px bg | abs |
| r.0.2 | 110,54 173.59×44.63 | stack |  |  |  |  | col gap 2 flex 1 |
| r.0.2.0 | 110,54 173.59×23.25 | text | “mert_k” | Mono 500 15/23.25 | text |  | row ai:center gap 5 |
| r.0.2.1 | 110,79.25 173.59×19.38 | text | “Aktif” | Inter 400 12.5/19.38 | text·68% |  | row ai:center gap 6 |
| r.0.2.1.0 | 110,85.44 7×7 | shape |  |  |  | fill online · r50% |  |
| r.0.3 | 293.59,59.31 94.41×34 | text | “kapalı” | Mono 400 12/18.6 | text·68% | r17 · shadow inset 0 0 0 1px divider | row ai:center gap 6 pad 0 12 0 10 |
| r.0.3.0 | 303.59,68.31 16×16 | icon | ph:ghost (regular) | 16pt | text·68% |  |  |
| r.1 | 0,104 402×2 | shape |  |  |  | fill radial-gradient(circle, text·20% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | abs z3 |
| r.2 | 0,0 402×874 | stack |  |  |  |  | col jc:flex-end gap 6 pad 0 14 100 14 flex 1 |
| r.2.0 | 150.3,20.66 237.7×88 | shape |  |  |  | fill accent800 · r20/20/6/20 | col self:flex-end gap 5 pad 10 12 8 10 maxW 290 |
| r.2.0.0 | 160.3,30.66 215.7×50 | stack |  |  |  |  | row ai:center gap 12 |
| r.2.0.0.0 | 160.3,30.66 42×50 | shape |  |  |  | fill bg·35% · r10 | grid place:center |
| r.2.0.0.0.0 | 168.8,43.16 25×25 | icon | ph:file-pdf (regular) | 25pt | accent300 |  |  |
| r.2.0.0.1 | 214.3,36.06 161.7×39.17 | stack |  |  |  |  | col gap 4 |
| r.2.0.0.1.0 | 214.3,36.06 161.7×20.69 | text | “kira-sozlesmesi.pdf” | Inter 500 15/20.7 | accent100 |  |  |
| r.2.0.0.1.1 | 214.3,60.75 161.7×14.48 | text | “2,1 MB · PDF · 4 sayfa” | Mono 400 10.5/14.49 | text·68% |  |  |
| r.2.0.1 | 324.08,85.66 51.92×15 | text | “12:40” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.0.1.0 | 361,85.66 15×15 | icon | ph:checks (fill) | 15pt | accent300 |  |  |
| r.2.1 | 14,114.66 246×153.5 | shape |  |  |  | fill surface · r20/20/20/6 | col self:flex-start gap 5 pad 5 5 8 5 maxW 290 |
| r.2.1.0 | 19,119.66 236×124 | shape |  |  |  | fill radial-gradient(circle at 62% 58%, rgba(255, 214, 170, 0.95) 0px, rgba(255, 214, 170, 0.95) 8%, rgba(255, 170, 120, 0.5)… (full value in layout JSON) · r15 |  |
| r.2.1.0.0 | 114,158.66 46×46 | shape |  |  |  | fill rgba(12, 13, 22, 0.45) · r50% · shadow rgba(245, 244, 255, 0.35) 0px 0px 0px 1px inset · backdropFilter blur(8px) | grid place:center mar -23 0 0 -23 abs |
| r.2.1.0.0.0 | 127.5,172.16 19×19 | icon | ph:play (fill) | 19pt | rgb(245, 244, 255) |  |  |
| r.2.1.0.1 | 29,214.17 116.91×20.48 | text | “0:18 · 12,4 MB” | Mono 400 10.5/14.49 | rgb(245, 244, 255) | fill rgba(12, 13, 22, 0.55) · r8 | pad 3 7 3 7 abs |
| r.2.1.1 | 214.08,248.66 32.92×11.5 | text | “12:52” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 mar 0 8 0 0 |
| r.2.2 | 14,274.16 196.11×96.5 | shape |  |  |  | fill surface · r20/20/20/6 | col self:flex-start gap 5 pad 8 12 8 8 maxW 290 |
| r.2.2.0 | 22,282.16 176.11×64 | stack |  |  |  |  | row ai:center gap 12 |
| r.2.2.0.0 | 22,282.16 64×64 | shape |  |  |  | fill radial-gradient(circle, text·18% 1px, transparent 1.4px) 0 0 / 8px 8px, bg · r12 |  |
| r.2.2.0.0.0 | 9.07,308.25 89.86×26.91 | shape |  |  |  | fill radial-gradient(circle, text·46% 1.6px, transparent 2px) 0 50% / 8px 10px repeat-x | abs matrix(0.981627, -0.190809, 0.190809, 0.981627, 0, 0) |
| r.2.2.0.0.1 | 29.03,269.72 34.31×88.88 | shape |  |  |  | fill radial-gradient(circle, text·38% 1.6px, transparent 2px) 50% 0 / 10px 8px repeat-y | abs matrix(0.961262, 0.275637, -0.275637, 0.961262, 0, 0) |
| r.2.2.0.0.2 | 55.28,318 30.72×28.16 | shape |  |  |  | fill radial-gradient(circle, accent·70% 1.3px, transparent 1.7px) 0 0 / 8px 8px · r46/0/0/0 | abs |
| r.2.2.0.0.3 | 36.88,287.59 24×24 | shape |  |  |  | filter drop-shadow(rgba(0, 0, 0, 0.4) 0px 4px 6px) | grid place:center abs matrix(1, 0, 0, 1, -12, -24) |
| r.2.2.0.0.3.0 | 36.88,287.59 24×24 | icon | ph:map-pin (fill) | 24pt | accent300 |  |  |
| r.2.2.0.1 | 98,293.69 100.11×40.94 | stack |  |  |  |  | col gap 3 |
| r.2.2.0.1.0 | 98,293.69 100.11×20.69 | text | “Moda İskelesi” | Inter 500 15/20.7 | text |  |  |
| r.2.2.0.1.1 | 98,317.38 100.11×17.25 | text | “Kadıköy, İstanbul” | Inter 400 12.5/17.25 | text·68% |  |  |
| r.2.2.1 | 165.19,351.16 32.92×11.5 | text | “17:58” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.3 | 122,376.66 266×170.17 | shape |  |  |  | fill accent800 · r20/20/6/20 | col self:flex-end gap 5 pad 5 5 8 5 maxW 266 |
| r.2.3.0 | 127,381.66 256×92 | shape |  |  |  | fill radial-gradient(circle, text·18% 1px, transparent 1.4px) 0 0 / 8px 8px, bg · r14 |  |
| r.2.3.0.0 | 78.15,397.23 353.7×78.2 | shape |  |  |  | fill radial-gradient(circle, text·46% 1.6px, transparent 2px) 0 50% / 8px 10px repeat-x | abs matrix(0.981627, -0.190809, 0.190809, 0.981627, 0, 0) |
| r.2.3.0.1 | 186.24,364.38 45.11×126.55 | shape |  |  |  | fill radial-gradient(circle, text·38% 1.6px, transparent 2px) 50% 0 / 10px 8px repeat-y | abs matrix(0.961262, 0.275637, -0.275637, 0.961262, 0, 0) |
| r.2.3.0.2 | 260.13,433.19 122.88×40.47 | shape |  |  |  | fill radial-gradient(circle, accent·70% 1.3px, transparent 1.7px) 0 0 / 8px 8px · r46/0/0/0 | abs |
| r.2.3.0.3 | 234.52,423.97 0×0 | box |  |  |  |  | abs |
| r.2.3.0.3.0 | 182.37,371.82 104.29×104.29 | shape |  |  |  | r50% · border 1.5px solid accent400 · opacity 0.106906 · anim u2-pulse 2.8s ease-out delay 0s ×infinite | mar -58 0 0 -58 abs matrix(0.899034, 0, 0, 0.899034, 0, 0) |
| r.2.3.0.3.1 | 209.62,399.07 49.8×49.8 | shape |  |  |  | r50% · border 1.5px solid accent400 · opacity 0.60428 · anim u2-pulse 2.8s ease-out delay 1.4s ×infinite | mar -58 0 0 -58 abs matrix(0.429291, 0, 0, 0.429291, 0, 0) |
| r.2.3.0.3.2 | 225.52,414.97 18×18 | shape |  |  |  | fill accent400 · r50% · shadow 0 0 0 3px bg, 0 0 18px accent·80% | abs |
| r.2.3.1 | 127,478.66 256×40.17 | stack |  |  |  |  | row ai:center gap 10 pad 2 4 0 6 |
| r.2.3.1.0 | 133,480.66 165.47×38.17 | stack |  |  |  |  | col gap 3 flex 1 |
| r.2.3.1.0.0 | 133,480.66 165.47×20.69 | text | “Canlı konum” | Inter 500 15/20.7 | accent100 |  | row ai:center gap 6 |
| r.2.3.1.0.0.0 | 133,483.5 15×15 | icon | ph:navigation-arrow (regular) | 15pt | accent300 |  |  |
| r.2.3.1.0.1 | 133,504.34 165.47×14.48 | text | “47 dk kaldı” | Mono 400 10.5/14.49 | text·68% |  |  |
| r.2.3.1.1 | 308.47,483.73 70.53×32 | text | “Durdur” | Inter 500 13.5/18.63 | danger | fill bg·55% · r16 | row ai:center pad 0 13 0 13 |
| r.2.3.2 | 325.08,523.83 51.92×15 | text | “18:01” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 mar 0 6 0 0 |
| r.2.3.2.0 | 362,523.83 15×15 | icon | ph:checks (fill) | 15pt | accent300 |  |  |
| r.2.4 | 14,552.83 227.98×37 | shape |  |  |  | fill transparent · r20 · shadow inset 0 0 0 1px divider | col self:flex-start gap 5 pad 9 13 8 13 maxW 290 |
| r.2.4.0 | 27,561.83 201.98×20 | text | “Bu mesaj herkesten silindi” | Inter 400 14.5/20.01 italic | text·68% |  | row ai:center gap 8 |
| r.2.4.0.0 | 27,563.83 16×16 | icon | ph:prohibit (regular) | 16pt | text·68% |  |  |
| r.2.5 | 14,595.83 290×95.38 | shape |  |  |  | fill surface · r20 | col self:flex-start gap 5 pad 9 13 8 9 maxW 290 |
| r.2.5.0 | 23,604.83 268×61.88 | stack |  |  |  |  | row ai:center gap 12 pad 2 0 2 0 |
| r.2.5.0.0 | 23,617.77 36×36 | text | “1” | Mono 400 13/17.94 | accent300 | r50% · border 1.5px dashed accent300 | grid place:center |
| r.2.5.0.1 | 71,606.83 220×57.88 | stack |  |  |  |  | col gap 2 |
| r.2.5.0.1.0 | 71,606.83 220×21.38 | text | “Fotoğraf” | Inter 500 15.5/21.39 | text |  |  |
| r.2.5.0.1.1 | 71,630.2 220×34.5 | text | “Tek görüntülemelik · açmak için dokun” | Inter 400 12.5/17.25 | text·68% |  |  |
| r.2.5.1 | 258.08,671.7 32.92×11.5 | text | “18:40” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.6 | 22,705.2 197.95×68.8 | stack |  |  |  |  | row ai:flex-end self:flex-start gap 10 mar 8 0 0 8 |
| r.2.6.0 | 18.82,697.49 157.57×84.22 | shape |  |  |  | fill accent200 · r22 · shadow rgb(245, 244, 255) 0px 0px 0px 4px, rgba(0, 0, 0, 0.35) 0px 12px 26px | col ai:center gap 7 pad 12 18 11 18 matrix(0.994522, -0.104528, 0.104528, 0.994522, 0, 0) |
| r.2.6.0.0 | 37.97,711.3 117.58×40.68 | text | “tamam!” | Doto 900 32/28.8 ROND100 | accent900 |  |  |
| r.2.6.0.1 | 81.09,751.05 36.85×13.71 | stack |  |  |  |  | row ai:center |
| r.2.6.0.1.0 | 81.09,753.76 10.99×10.99 | shape |  |  |  | r50% · border 2px solid accent700 |  |
| r.2.6.0.1.1 | 91.46,756.07 16.12×3.66 | shape |  |  |  | fill accent700 | flex 1 |
| r.2.6.0.1.2 | 106.95,751.05 10.99×10.99 | shape |  |  |  | r50% · border 2px solid accent700 |  |
| r.2.6.1 | 183.2,757.73 36.75×16.27 | text | “18:44” | Mono 400 10.5/16.27 | text·68% |  |  |
| r.3 | 0,780 402×94 | shape |  |  |  | fill linear-gradient(to bottom, transparent, bg 30%) | row ai:flex-end gap 8 pad 14 12 36 12 abs z2 |
| r.3.0 | 12,794 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.3.0.0 | 23,805 22×22 | icon | ph:plus (regular) | 22pt | text |  |  |
| r.3.1 | 64,794 274×44 | shape |  |  |  | fill surface · r22 · shadow inset 0 0 0 0.5px text·14% | row ai:center gap 12 pad 0 12 0 16 flex 1 minH 44 |
| r.3.1.0 | 80,803.59 180×24.8 | text | “Mesaj” | Inter 400 16/24.8 | text·50% |  | flex 1 |
| r.3.1.1 | 272,805.5 21×21 | icon | ph:timer (regular) | 21pt | text·55% |  |  |
| r.3.1.2 | 305,805.5 21×21 | icon | ph:sticker (regular) | 21pt | text·55% |  |  |
| r.3.2 | 346,794 44×44 | shape |  |  |  | r50% · border 1px solid accent · shadow 0 0 20px -6px accent·70% | grid place:center |
| r.3.2.0 | 357.5,805.5 21×21 | icon | ph:microphone (regular) | 21pt | accent300 |  |  |

</details>

Colour legend 5.2:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `bg·80%` | #161826@80% | #f3f5fe@80% |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `accent100` | #f5f4ff | #2b2741 |
| `accent700` | #5d5294 | #d2cefd |
| `online` | #52cd86 | #009351 |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `divider` | #e9e9ed@16% | #292b31@16% |
| `text·20%` | #e9e9ed@20% | #292b31@20% |
| `accent800` | #423a6a | #e7e5fe |
| `accent300` | #d2cefd | #5d5294 |
| `bg·35%` | #161826@35% | #f3f5fe@35% |
| `surface` | #232532 | #e4e7f5 |
| `text·46%` | #e9e9ed@46% | #292b31@46% |
| `text·38%` | #e9e9ed@38% | #292b31@38% |
| `accent·70%` | #9184d9@70% | #796cbf@70% |
| `accent400` | #b5abfc | #796cbf |
| `accent·80%` | #9184d9@80% | #796cbf@80% |
| `danger` | #f2716a | #c53637 |
| `bg·55%` | #161826@55% | #f3f5fe@55% |
| `accent900` | #2b2741 | #f5f4ff |
| `accent200` | #e7e5fe | #423a6a |
| `text·14%` | #e9e9ed@14% | #292b31@14% |
| `text·50%` | #e9e9ed@50% | #292b31@50% |
| `text·55%` | #e9e9ed@55% | #292b31@55% |
| `accent` | #9184d9 | #796cbf |

## 5.3 · Mesaj eylemleri — Message actions

Reference `reference/5.3-mesaj-eylemleri.png` · light `reference/light/5.3-mesaj-eylemleri.png` · data `layout/5.3-mesaj-eylemleri.json`

> **Note (TR):** Uzun basınca tepki şeridi ve eylemler açılır. "Herkesten sil" mesajı iki uçtan da kaldırır ve yerine iz bırakır (5.2). Süreli mesajlarda kopyalama kapalıdır.
> **EN:** Long-press opens the reaction strip and the actions. “Delete for everyone” removes the message from both ends and leaves a trace in its place (5.2). Copying is disabled for disappearing messages.

**Purpose.** Context menu for a message (here: own disappearing message “Kapı kodu 4471”).

**Layout.**
- Background: the chat, blurred 14 pt at 55 % opacity, plus overlay `#06070e@35%`.
- Right-aligned column centred vertically (pad 0 14 30, gap 10):
  - Reaction bar 80,235 308×52, `surface`, r28, `shadowMd`, pad 6 8, gap 2: six emoji cells 40×40 (emoji 24: ❤️ 😂 👍 😮 😢 🙏) + “more” 40 circle `text·9%` with `ph:plus` 18.
  - The lifted bubble (as in 5.1, shadow `0 16px 40px #000@40%`).
  - Action menu 138,366 250×243, `surface·94%` + backdrop blur 20, r18, `shadowMd`: rows 46 high, pad 0 16, label `rowTitle` Inter 400 16 left, icon 19 right: Yanıtla `ph:arrow-bend-up-left` · Bilgi `ph:info` · Kopyala `ph:copy` (disabled: label/icon `text·35%`, hint “Süreli mesajda kapalı” `menuHint` Inter 400 11.5 `text·68%` under the label) · 7 pt group gap band `text·6%` · Herkesten sil `ph:trash` `danger` · Benden sil `ph:trash-simple` `danger`. Dotted separators between rows (no inset).

**Texts.**

| TR | EN |
|---|---|
| Yanıtla | Reply |
| Bilgi | Info |
| Kopyala | Copy |
| Süreli mesajda kapalı | Off for disappearing messages |
| Herkesten sil | Delete for everyone |
| Benden sil | Delete for me |
| *(confirm, proposed)* Mesaj iki taraftan da silinsin mi? · Yerinde “Bu mesaj herkesten silindi” izi kalır. | Delete this message for everyone? · A “This message was deleted for everyone” trace stays in its place. |

**States.** Own vs incoming message (incoming: no “Herkesten sil”, no “Bilgi” — proposed: Info only for own messages); disappearing (copy disabled); media (add “Kaydet” only when allowed — not in the design, don't add); selected reaction highlighted (proposed: `accent900` circle behind the emoji).

**Transitions.** Tap outside → dismiss. Yanıtla → composer with quote (5.1 item 4 style). Bilgi → 5.4 sheet. Herkesten sil → confirm → tombstone (5.2). Benden sil → removed locally. Reaction → chip appears on the bubble (5.1 item 3).

**Animation.** Long-press: bubble lifts (scale 1.0→1.02 + shadow fade-in) 0.3 s `springSoft`; background blur/dim fades in 0.3 s; reaction bar and menu fade + scale from 0.96 anchored at the bubble, 0.3 s; dismiss reverses 0.24 s. Haptic: medium impact on open (proposed).

**Data.** Message (own?, disappearing?, kind), reaction set, own reaction.

**Elements.**

<details><summary>Element table 5.3 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×874 | shape |  |  |  | opacity 0.55 · filter blur(14px) | abs |
| r.0.0 | 0,0 402×108.63 | shape |  |  |  | fill bg·80% · backdropFilter blur(20px) saturate(1.4) | row ai:center gap 10 pad 54 14 10 12 abs z3 |
| r.0.0.0 | 12,56.31 40×40 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0.0 | 22,66.31 20×20 | icon | ph:caret-left (regular) | 20pt | text |  |  |
| r.0.0.1 | 62,57.31 38×38 | text | “D” | Mono 500 14/14 | accent100 | fill accent800 · r50% | grid place:center |
| r.0.0.2 | 110,54 278×44.63 | stack |  |  |  |  | col gap 2 flex 1 |
| r.0.0.2.0 | 110,54 278×23.25 | text | “deniz” | Mono 500 15/23.25 | text |  | row ai:center gap 5 |
| r.0.0.2.1 | 110,79.25 278×19.38 | text | “Az önce aktifti” | Inter 400 12.5/19.38 | text·68% |  | row ai:center gap 6 |
| r.0.1 | 0,104 402×2 | shape |  |  |  | fill radial-gradient(circle, text·20% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | abs z3 |
| r.0.2 | 0,0 402×554 | stack |  |  |  |  | col gap 10 pad 140 14 0 14 |
| r.0.2.0 | 14,140 210×44 | shape |  |  |  | fill surface · r20 | self:flex-start |
| r.0.2.1 | 14,194 268×190 | shape |  |  |  | fill surface · r20 | self:flex-start |
| r.0.2.2 | 148,394 240×96 | shape |  |  |  | fill accent800 · r20 | self:flex-end |
| r.0.2.3 | 14,500 250×54 | shape |  |  |  | fill surface · r20 | self:flex-start |
| r.1 | 0,0 402×874 | shape |  |  |  | fill rgba(6, 7, 14, 0.35) | abs |
| r.2 | 0,0 402×874 | stack |  |  |  |  | col ai:flex-end jc:center gap 10 pad 0 14 30 14 flex 1 |
| r.2.0 | 80,234.95 308×52 | shape |  |  |  | fill surface · r28 · shadow shadow-md | row ai:center gap 2 pad 6 8 6 8 |
| r.2.0.0 | 88,240.95 40×40 | text | “❤️” | Inter 400 24/24 | text |  | grid place:center |
| r.2.0.1 | 130,240.95 40×40 | text | “😂” | Inter 400 24/24 | text |  | grid place:center |
| r.2.0.2 | 172,240.95 40×40 | text | “👍” | Inter 400 24/24 | text |  | grid place:center |
| r.2.0.3 | 214,240.95 40×40 | text | “😮” | Inter 400 24/24 | text |  | grid place:center |
| r.2.0.4 | 256,240.95 40×40 | text | “😢” | Inter 400 24/24 | text |  | grid place:center |
| r.2.0.5 | 298,240.95 40×40 | text | “🙏” | Inter 400 24/24 | text |  | grid place:center |
| r.2.0.6 | 340,240.95 40×40 | shape |  |  |  | fill text·9% · r50% | grid place:center |
| r.2.0.6.0 | 351,251.95 18×18 | icon | ph:plus (regular) | 18pt | text |  |  |
| r.2.1 | 246.08,296.95 141.92×59.08 | shape |  |  |  | fill accent800 · r20/20/6/20 · shadow rgba(0, 0, 0, 0.4) 0px 16px 40px | col self:flex-end gap 5 pad 9 13 8 13 maxW 290 |
| r.2.1.0 | 259.08,305.95 115.92×22.08 | text | “Kapı kodu 4471” | Inter 400 16/22.08 | accent100 |  |  |
| r.2.1.1 | 269.67,333.03 105.33×15 | stack |  |  |  |  | row ai:center self:flex-end gap 8 |
| r.2.1.1.0 | 269.67,334.03 45.41×13 | text | “5 dk” | Mono 400 10.5/10.5 | accent300 |  | row ai:center gap 3 |
| r.2.1.1.0.0 | 269.67,334.03 13×13 | icon | ph:timer (regular) | 13pt | accent300 |  |  |
| r.2.1.1.1 | 323.08,333.03 51.92×15 | text | “18:10” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center gap 4 |
| r.2.1.1.1.0 | 360,333.03 15×15 | icon | ph:check (regular) | 15pt | text·68% |  |  |
| r.2.2 | 138,366.03 250×243 | shape |  |  |  | fill surface·94% · r18 · shadow shadow-md · backdropFilter blur(20px) |  |
| r.2.2.0 | 138,366.03 250×46 | stack |  |  |  |  | row ai:center jc:space-between pad 0 16 0 16 |
| r.2.2.0.0 | 154,376.63 50.14×24.8 | stack |  |  |  |  | col gap 1 |
| r.2.2.0.0.0 | 154,376.63 50.14×24.8 | text | “Yanıtla” | Inter 400 16/24.8 | text |  |  |
| r.2.2.0.1 | 353,379.53 19×19 | icon | ph:arrow-bend-up-left (regular) | 19pt | text |  |  |
| r.2.2.1 | 138,412.03 250×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x |  |
| r.2.2.2 | 138,414.03 250×46 | stack |  |  |  |  | row ai:center jc:space-between pad 0 16 0 16 |
| r.2.2.2.0 | 154,424.63 31.91×24.8 | stack |  |  |  |  | col gap 1 |
| r.2.2.2.0.0 | 154,424.63 31.91×24.8 | text | “Bilgi” | Inter 400 16/24.8 | text |  |  |
| r.2.2.2.1 | 353,427.53 19×19 | icon | ph:info (regular) | 19pt | text |  |  |
| r.2.2.3 | 138,460.03 250×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x |  |
| r.2.2.4 | 138,462.03 250×46 | stack |  |  |  |  | row ai:center jc:space-between pad 0 16 0 16 |
| r.2.2.4.0 | 154,463.22 114.61×43.61 | stack |  |  |  |  | col gap 1 |
| r.2.2.4.0.0 | 154,463.22 114.61×24.8 | text | “Kopyala” | Inter 400 16/24.8 | text·35% |  |  |
| r.2.2.4.0.1 | 154,489.02 114.61×17.81 | text | “Süreli mesajda kapalı” | Inter 400 11.5/17.82 | text·68% |  |  |
| r.2.2.4.1 | 353,475.53 19×19 | icon | ph:copy (regular) | 19pt | text·35% |  |  |
| r.2.2.5 | 138,508.03 250×7 | shape |  |  |  | fill text·6% |  |
| r.2.2.6 | 138,515.03 250×46 | stack |  |  |  |  | row ai:center jc:space-between pad 0 16 0 16 |
| r.2.2.6.0 | 154,525.63 98.25×24.8 | stack |  |  |  |  | col gap 1 |
| r.2.2.6.0.0 | 154,525.63 98.25×24.8 | text | “Herkesten sil” | Inter 400 16/24.8 | danger |  |  |
| r.2.2.6.1 | 353,528.53 19×19 | icon | ph:trash (regular) | 19pt | danger |  |  |
| r.2.2.7 | 138,561.03 250×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x |  |
| r.2.2.8 | 138,563.03 250×46 | stack |  |  |  |  | row ai:center jc:space-between pad 0 16 0 16 |
| r.2.2.8.0 | 154,573.63 78.53×24.8 | stack |  |  |  |  | col gap 1 |
| r.2.2.8.0.0 | 154,573.63 78.53×24.8 | text | “Benden sil” | Inter 400 16/24.8 | danger |  |  |
| r.2.2.8.1 | 353,576.53 19×19 | icon | ph:trash-simple (regular) | 19pt | danger |  |  |

</details>

Colour legend 5.3:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `bg·80%` | #161826@80% | #f3f5fe@80% |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `accent100` | #f5f4ff | #2b2741 |
| `accent800` | #423a6a | #e7e5fe |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `text·20%` | #e9e9ed@20% | #292b31@20% |
| `surface` | #232532 | #e4e7f5 |
| `accent300` | #d2cefd | #5d5294 |
| `surface·94%` | #232532@94% | #e4e7f5@94% |
| `text·22%` | #e9e9ed@22% | #292b31@22% |
| `text·35%` | #e9e9ed@35% | #292b31@35% |
| `text·6%` | #e9e9ed@6% | #292b31@6% |
| `danger` | #f2716a | #c53637 |

## 5.4 · Mesaj bilgisi — Message info

Reference `reference/5.4-mesaj-bilgisi.png` · light `reference/light/5.4-mesaj-bilgisi.png` · data `layout/5.4-mesaj-bilgisi.json`

> **Note (TR):** Bildirim olmadığı için mesajın yolculuğu adım adım gösterilir. 14 gün içinde iletilemeyen mesaj karşı uçta hiç görünmez; tekrar göndermek onu yeni bir mesaj olarak sıraya alır.
> **EN:** Because there are no notifications, the message's journey is shown step by step. A message not delivered within 14 days never appears at the other end; resending queues it as a new message.

**Purpose.** Delivery timeline of one message.

**Layout.** Full-height sheet (C18) from y 58, pad 8 16 0, gap 16.
- Header 40 high: “Mesaj bilgisi” `navTitle` centred; close C4-36 (`ph:x` 17) at the right (348,89).
- The message bubble (outgoing, round 20) right-aligned.
- Timeline card 16,224 370×257, `bg` r20, pad 18 16: 3 rows, grid [34 | 1fr | auto], column gap 14: status node 34 circle `surface` + inner 1 pt `divider`, icon 17 (C16 colours); vertical dotted connector under each node except the last (2 wide, r .9 dots every 5, `text·32%`, min 22); text column pad-top 6 (bottom 16 except last), gap 2: title `rowTitleEmphasis` Inter 500 16, description `captionTighter` Inter 400 13/18.2 `text·68%`; time `monoMeta` Mono 11 `text·68%` pad-top 8.
- Note card 16,497 370×117, transparent, r20, inner 1 pt `danger·40%`, pad 16, gap 14: `ph:warning-circle` 22 `danger` + title `noteTitle` Inter 500 15.5 + body `captionTight` Inter 400 13/18.85 `text·68%`.

**Texts.**

| TR | EN |
|---|---|
| Mesaj bilgisi | Message info |
| Gönderildi · Cihazından çıktı; sunucu yalnızca kapalı zarfı taşıyor | Sent · Left your device; the server only carries the sealed envelope |
| İletildi · deniz uygulamayı açtı, mesaj artık onun cihazında | Delivered · deniz opened the app; the message is now on their device |
| Okundu · deniz mesajı açtı | Read · deniz opened the message |
| İletilemedi ne demek? | What does “not delivered” mean? |
| Alıcı 14 gün içinde uygulamayı açmazsa mesaj sunucudan silinir ve ona hiç ulaşmaz. Mesajın altında “Tekrar gönder” belirir. | If the recipient doesn't open the app within 14 days, the message is deleted from the server and never reaches them. “Send again” appears under the message. |
| *(failed step, proposed)* İletilemedi · 14 gün içinde açılmadı | Not delivered · not opened within 14 days |

**States.** Steps not yet reached: node + title in `text·45%`, no time (proposed, matches 7.3 pending). Read receipts disabled by the peer → “Okundu” row hidden (proposed). Failed: third row replaced by the failed step (danger icon), the note card stays.

**Transitions.** Close / swipe down → 5.1.

**Animation.** Sheet `springSoft`; a newly reached step: node icon cross-fade + connector dots fill top→bottom 0.5 s (proposed).

**Data.** Message status history with timestamps (sentAt, deliveredAt, readAt, failedAt), peer username.

**Elements.**

<details><summary>Element table 5.4 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×874 | shape |  |  |  | fill mix(bg 55%, #05060c) | abs |
| r.1 | 0,58 402×816 | shape |  |  |  | fill surface · r38/38/0/0 | col gap 16 pad 8 16 0 16 abs |
| r.1.0 | 182,66 38×5 | shape |  |  |  | fill text·25% · r3 | self:center |
| r.1.1 | 16,87 370×40 | stack |  |  |  |  | row ai:center jc:center |
| r.1.1.0 | 150.03,93.83 101.94×26.34 | text | “Mesaj bilgisi” | Inter 600 17/26.35 | text |  |  |
| r.1.1.1 | 348,89 36×36 | box |  |  |  |  | abs |
| r.1.1.1.0 | 348,89 36×36 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.1.1.1.0.0 | 357.5,98.5 17×17 | icon | ph:x (regular) | 17pt | text |  |  |
| r.1.2 | 16,143 370×65.08 | stack |  |  |  |  | col pad 4 0 2 0 |
| r.1.2.0 | 137.55,147 248.45×59.08 | shape |  |  |  | fill accent800 · r20 | col self:flex-end gap 5 pad 9 13 8 13 maxW 290 |
| r.1.2.0.0 | 150.55,156 222.45×22.08 | text | “Çok güzelmiş! 8 gibi olur mu?” | Inter 400 16/22.08 | accent100 |  |  |
| r.1.2.0.1 | 321.08,183.08 51.92×15 | text | “18:05” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.1.2.0.1.0 | 358,183.08 15×15 | icon | ph:checks (fill) | 15pt | accent300 |  |  |
| r.1.3 | 16,224.08 370×257.33 | shape |  |  |  | fill bg · r20 | col pad 18 16 18 16 |
| r.1.3.0 | 32,242.08 338×85.17 | stack |  |  |  |  | grid cols 34px 1fr auto gap 0/14 |
| r.1.3.0.0 | 32,242.08 34×85.17 | stack |  |  |  |  | col ai:center |
| r.1.3.0.0.0 | 32,242.08 34×34 | shape |  |  |  | fill surface · r50% · shadow inset 0 0 0 1px divider | grid place:center |
| r.1.3.0.0.0.0 | 40.5,250.58 17×17 | icon | ph:check (regular) | 17pt | text·68% |  |  |
| r.1.3.0.0.1 | 48,276.08 2×51.17 | shape |  |  |  | fill radial-gradient(circle, text·32% 0.9px, transparent 1.3px) 50% 0 / 2px 5px repeat-y | flex 1 minH 22 |
| r.1.3.0.1 | 80,242.08 237.5×85.17 | stack |  |  |  |  | col gap 2 pad 6 0 16 0 |
| r.1.3.0.1.0 | 80,248.08 237.5×24.8 | text | “Gönderildi” | Inter 500 16/24.8 | text |  |  |
| r.1.3.0.1.1 | 80,274.88 237.5×36.38 | text | “Cihazından çıktı; sunucu yalnızca kapalı zarfı taşıyor” | Inter 400 13/18.2 | text·68% |  |  |
| r.1.3.0.2 | 331.5,242.08 38.5×85.17 | text | “18:05” | Mono 400 11/17.05 | text·68% |  | pad 8 0 0 0 |
| r.1.3.1 | 32,327.25 338×85.17 | stack |  |  |  |  | grid cols 34px 1fr auto gap 0/14 |
| r.1.3.1.0 | 32,327.25 34×85.17 | stack |  |  |  |  | col ai:center |
| r.1.3.1.0.0 | 32,327.25 34×34 | shape |  |  |  | fill surface · r50% · shadow inset 0 0 0 1px divider | grid place:center |
| r.1.3.1.0.0.0 | 40.5,335.75 17×17 | icon | ph:checks (regular) | 17pt | text·68% |  |  |
| r.1.3.1.0.1 | 48,361.25 2×51.17 | shape |  |  |  | fill radial-gradient(circle, text·32% 0.9px, transparent 1.3px) 50% 0 / 2px 5px repeat-y | flex 1 minH 22 |
| r.1.3.1.1 | 80,327.25 237.5×85.17 | stack |  |  |  |  | col gap 2 pad 6 0 16 0 |
| r.1.3.1.1.0 | 80,333.25 237.5×24.8 | text | “İletildi” | Inter 500 16/24.8 | text |  |  |
| r.1.3.1.1.1 | 80,360.05 237.5×36.38 | text | “deniz uygulamayı açtı, mesaj artık onun cihazında” | Inter 400 13/18.2 | text·68% |  |  |
| r.1.3.1.2 | 331.5,327.25 38.5×85.17 | text | “18:06” | Mono 400 11/17.05 | text·68% |  | pad 8 0 0 0 |
| r.1.3.2 | 32,412.42 338×50.98 | stack |  |  |  |  | grid cols 34px 1fr auto gap 0/14 |
| r.1.3.2.0 | 32,412.42 34×50.98 | stack |  |  |  |  | col ai:center |
| r.1.3.2.0.0 | 32,412.42 34×34 | shape |  |  |  | fill surface · r50% · shadow inset 0 0 0 1px divider | grid place:center |
| r.1.3.2.0.0.0 | 40.5,420.92 17×17 | icon | ph:checks (fill) | 17pt | accent300 |  |  |
| r.1.3.2.1 | 80,412.42 237.5×50.98 | stack |  |  |  |  | col gap 2 pad 6 0 0 0 |
| r.1.3.2.1.0 | 80,418.42 237.5×24.8 | text | “Okundu” | Inter 500 16/24.8 | text |  |  |
| r.1.3.2.1.1 | 80,445.22 237.5×18.19 | text | “deniz mesajı açtı” | Inter 400 13/18.2 | text·68% |  |  |
| r.1.3.2.2 | 331.5,412.42 38.5×50.98 | text | “18:07” | Mono 400 11/17.05 | text·68% |  | pad 8 0 0 0 |
| r.1.4 | 16,497.41 370×116.55 | shape |  |  |  | r20 · shadow inset 0 0 0 1px danger·40% | row ai:flex-start gap 14 pad 16 16 16 16 |
| r.1.4.0 | 32,513.41 22×22 | icon | ph:warning-circle (regular) | 22pt | danger |  |  |
| r.1.4.1 | 68,513.41 302×84.55 | stack |  |  |  |  | col gap 4 |
| r.1.4.1.0 | 68,513.41 302×24.02 | text | “İletilemedi ne demek?” | Inter 500 15.5/24.02 | text |  |  |
| r.1.4.1.1 | 68,541.42 302×56.53 | text | “Alıcı 14 gün içinde uygulamayı açmazsa mesaj sunucudan silinir ve o…” | Inter 400 13/18.85 | text·68% |  |  |

</details>

Colour legend 5.4:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `mix(bg 55%, #05060c)` | #0e101a | #888991 |
| `surface` | #232532 | #e4e7f5 |
| `text·25%` | #e9e9ed@25% | #292b31@25% |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `accent100` | #f5f4ff | #2b2741 |
| `accent800` | #423a6a | #e7e5fe |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent300` | #d2cefd | #5d5294 |
| `divider` | #e9e9ed@16% | #292b31@16% |
| `text·32%` | #e9e9ed@32% | #292b31@32% |
| `danger·40%` | #f2716a@40% | #c53637@40% |
| `danger` | #f2716a | #c53637 |

## 5.5 · Ghost ve süreli mesaj — Ghost and disappearing messages

Reference `reference/5.5-ghost-ve-sureli-mesaj.png` · light `reference/light/5.5-ghost-ve-sureli-mesaj.png` · data `layout/5.5-ghost-ve-sureli-mesaj.json`

> **Note (TR):** Başlıktaki sayaçtan açılır. Ayarlar sohbete özeldir ve iki taraf için geçerlidir; her değişiklik sohbete sistem satırı olarak düşer.
> **EN:** Opens from the counter in the header. Settings are per chat and apply to both sides; every change drops into the chat as a system line.

**Purpose.** Per-chat Ghost mode and disappearing-message timer.

**Layout.** Content-height sheet 0,287.8 402×586 (top radius 38, pad 8 18 40, gap 20) over the scrim.
- Hero (pad-top 10, centred, gap 12): kicker `monoKicker` `accent300` with ghost 15 “GHOST MOD · DENİZ” (Turkish upper-case: “deniz” → “DENİZ”); countdown `displayCountdownM` Doto 900 56 `accent200` (min-height 56); explanation `sheetCaption` Inter 400 14/20.3 `text·68%` centred, max 320.
- Ghost block (gap 12): row “Ghost mod” `rowTitleEmphasis` + C14 toggle (ON); label “SESSİZLİK SÜRESİ” `monoSectionLabel`; C22 chips: 1 sa · 6 sa · **24 sa** · 3 gün · 7 gün.
- Dotted rule (full width).
- Disappearing block (gap 12): title “Süreli mesajlar” `rowTitleEmphasis` + description `captionTighter` (gap 3); C22 chips: **Kapalı** · 30 sn · 5 dk · 1 sa · 1 gün.
- C1 “Kaydet” 18,780 366×54.

**Texts.**

| TR | EN |
|---|---|
| Ghost mod · deniz | Ghost mode · deniz |
| Sohbet bu kadar sessiz kalırsa iki uçtan da tamamen silinir. Her yeni mesaj sayacı baştan başlatır. | If the chat stays this quiet, it's erased completely from both ends. Every new message restarts the timer. |
| Ghost mod | Ghost mode |
| Sessizlik süresi | Silence period |
| 1 sa · 6 sa · 24 sa · 3 gün · 7 gün | 1 h · 6 h · 24 h · 3 days · 7 days |
| Süreli mesajlar | Disappearing messages |
| Yeni mesajlar, okunduktan sonra seçtiğin sürede silinir. | New messages are deleted the chosen time after they're read. |
| Kapalı · 30 sn · 5 dk · 1 sa · 1 gün | Off · 30 s · 5 min · 1 h · 1 day |
| Kaydet | Save |

**States.** Ghost ON (as drawn) / OFF (countdown replaced by “—” or the selected duration, chips disabled at 45 % — proposed); Save enabled only when something changed (proposed). Saving sends a control message; both sides get a system line (5.1 proposed copy). Default values come from Settings (7.1: “Varsayılan Ghost süresi”, “Varsayılan süreli mesaj”).

**Transitions.** Kaydet → dismiss to 5.1 (+ system line). Swipe down → dismiss without saving.

**Animation.** Chip selection: fill/ring cross-fade 0.24 s; toggle `springSoft`; countdown ticks.

**Data.** Chat ghost {enabled, duration, remaining}, disappearing TTL, peer username.

**Elements.**

<details><summary>Element table 5.5 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×874 | shape |  |  |  | fill mix(bg 55%, #05060c) | abs |
| r.1 | 0,287.81 402×586.19 | shape |  |  |  | fill surface · r38/38/0/0 | col gap 20 pad 8 18 40 18 abs |
| r.1.0 | 182,295.81 38×5 | shape |  |  |  | fill text·25% · r3 | self:center |
| r.1.1 | 18,320.81 366×167.94 | stack |  |  |  |  | col ai:center gap 12 pad 10 0 0 0 |
| r.1.1.0 | 116.56,330.81 168.86×17.05 | text | “Ghost mod · deniz” | Mono 400 11/17.05 ls0.88 UPPER center | accent300 |  | row ai:center gap 8 |
| r.1.1.0.0 | 116.56,331.83 15×15 | icon | ph:ghost (regular) | 15pt | accent300 |  |  |
| r.1.1.1 | 66.59,359.86 268.8×56 | box |  |  |  |  | minH 56 |
| r.1.1.1.0 | 66.59,353.86 268.8×67 | text | “23:41:07” | Doto 900 56/56 ROND100 center | accent200 |  |  |
| r.1.1.2 | 41,427.86 320×60.89 | text | “Sohbet bu kadar sessiz kalırsa iki uçtan da tamamen silinir. Her ye…” | Inter 400 14/20.3 center | text·68% |  | maxW 320 |
| r.1.2 | 18,508.75 366×111.27 | stack |  |  |  |  | col gap 12 |
| r.1.2.0 | 18,508.75 366×31 | stack |  |  |  |  | row ai:center jc:space-between |
| r.1.2.0.0 | 18,511.84 83.36×24.8 | text | “Ghost mod” | Inter 500 16/24.8 | text |  |  |
| r.1.2.0.1 | 333,508.75 51×31 | shape |  |  |  | fill accent · r16 | row jc:flex-end pad 2 2 2 2 |
| r.1.2.0.1.0 | 355,510.75 27×27 | shape |  |  |  | fill rgb(245, 244, 255) · r50% · shadow rgba(0, 0, 0, 0.25) 0px 2px 6px |  |
| r.1.2.1 | 18,551.75 366×16.27 | text | “Sessizlik süresi” | Mono 400 10.5/16.27 ls1.05 UPPER | text·68% |  |  |
| r.1.2.2 | 18,580.02 366×40 | stack |  |  |  |  | row gap 6 |
| r.1.2.2.0 | 18,580.02 68.39×40 | text | “1 sa” | Inter 400 14/21.7 | text | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.1.2.2.1 | 92.39,580.02 68.41×40 | text | “6 sa” | Inter 400 14/21.7 | text | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.1.2.2.2 | 166.8,580.02 68.39×40 | text | “24 sa” | Inter 400 14/21.7 | accent300 | fill accent900 · r12 · shadow inset 0 0 0 1.5px accent | grid place:center flex 1 |
| r.1.2.2.3 | 241.19,580.02 68.41×40 | text | “3 gün” | Inter 400 14/21.7 | text | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.1.2.2.4 | 315.59,580.02 68.41×40 | text | “7 gün” | Inter 400 14/21.7 | text | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.1.3 | 18,640.02 366×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x |  |
| r.1.4 | 18,662.02 366×97.98 | stack |  |  |  |  | col gap 12 |
| r.1.4.0 | 18,662.02 366×45.98 | stack |  |  |  |  | col gap 3 |
| r.1.4.0.0 | 18,662.02 366×24.8 | text | “Süreli mesajlar” | Inter 500 16/24.8 | text |  |  |
| r.1.4.0.1 | 18,689.81 366×18.19 | text | “Yeni mesajlar, okunduktan sonra seçtiğin sürede silinir.” | Inter 400 13/18.2 | text·68% |  |  |
| r.1.4.1 | 18,720 366×40 | stack |  |  |  |  | row gap 6 |
| r.1.4.1.0 | 18,720 68.39×40 | text | “Kapalı” | Inter 400 14/21.7 | accent300 | fill accent900 · r12 · shadow inset 0 0 0 1.5px accent | grid place:center flex 1 |
| r.1.4.1.1 | 92.39,720 68.41×40 | text | “30 sn” | Inter 400 14/21.7 | text | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.1.4.1.2 | 166.8,720 68.39×40 | text | “5 dk” | Inter 400 14/21.7 | text | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.1.4.1.3 | 241.19,720 68.41×40 | text | “1 sa” | Inter 400 14/21.7 | text | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.1.4.1.4 | 315.59,720 68.41×40 | text | “1 gün” | Inter 400 14/21.7 | text | r12 · shadow inset 0 0 0 1px divider | grid place:center flex 1 |
| r.1.5 | 18,780 366×54 | text | “Kaydet” | Inter 500 17/26.35 | accent300 | r16 · border 1px solid accent · shadow 0 0 32px -8px accent·70% | row ai:center jc:center gap 8 |

</details>

Colour legend 5.5:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `mix(bg 55%, #05060c)` | #0e101a | #888991 |
| `surface` | #232532 | #e4e7f5 |
| `text·25%` | #e9e9ed@25% | #292b31@25% |
| `accent300` | #d2cefd | #5d5294 |
| `accent200` | #e7e5fe | #423a6a |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent` | #9184d9 | #796cbf |
| `divider` | #e9e9ed@16% | #292b31@16% |
| `accent900` | #2b2741 | #f5f4ff |
| `text·22%` | #e9e9ed@22% | #292b31@22% |
| `accent·70%` | #9184d9@70% | #796cbf@70% |

## 5.6 · Tek görüntülemelik — View once

Reference `reference/5.6-tek-goruntulemelik.png` · light `reference/light/5.6-tek-goruntulemelik.png` · data `layout/5.6-tek-goruntulemelik.json`

> **Note (TR):** Kapatınca iki taraftan silinir. iOS ekran görüntüsünü engelleyemez: alındığı anda algılanır, ekranda uyarı çıkar ve karşı tarafın sohbetine sistem satırı düşer. Ekran kaydı sırasında içerik gizlenir.
> **EN:** Closing deletes it from both sides. iOS can't block screenshots: it's detected the moment it's taken, a warning appears on screen and a system line drops into the other side's chat. Content is hidden during screen recording.

**Purpose.** Full-screen viewer for view-once media (shown after a screenshot was detected).

**Layout.** Always dark (status bar light) regardless of theme.
- Background `#0d0e19` + the photo full-bleed (aspect-fill).
- Top shade 0,0 402×190 `#05060c@70% → transparent`; bottom shade 0,654 402×220 `transparent → #05060c@80%`.
- Top bar (pad 56 16 0, gap 12): close 44 circle `#0c0d16@40%` + hairline `text·18%`, `ph:x` 21 `#f5f4ff`; sender “deniz” `monoUsername` `#f5f4ff` + “Bugün 18:40” `meta` `#f5f4ff@75%`; badge “1 kez” 32 high pad 0 12 r16 `#0c0d16@45%`, **1 pt dashed** `#f5f4ff@50%` border, Mono 400 11 `#f5f4ff`.
- Screenshot warning (margin 16 12 0): 12,116.6 378×91.4, `surface·92%` + backdrop blur 20, r20, inner ring `danger·45%` + shadow `0 14px 40px #000@45%`, pad 14 16, gap 12: `ph:camera` 22 `danger` + title `noteTitle` Inter 500 15.5 `text` + body `captionTighter` `text·68%`.
- Footer (pad 0 24 50, centred, gap 8): `ph:eye-slash` 18 + “Kapatınca silinir” Inter 500 15 `#f5f4ff`; “Bu fotoğraf yeniden açılamaz ve yedeğe girmez.” `caption` `#f5f4ff@72%`.

**Texts.**

| TR | EN |
|---|---|
| Bugün 18:40 | Today 18:40 |
| 1 kez | Once |
| Ekran görüntüsü algılandı | Screenshot detected |
| deniz'e bildirildi. iOS ekran görüntüsünü engellemiyor; yalnızca haber verebiliyoruz. | deniz has been told. iOS doesn't block screenshots; we can only let them know. |
| Kapatınca silinir | Deleted when you close it |
| Bu fotoğraf yeniden açılamaz ve yedeğe girmez. | This photo can't be opened again and never goes into a backup. |
| *(recording, proposed)* Ekran kaydı sırasında gizlendi | Hidden while the screen is being recorded |

**States.** viewing (no warning) → screenshot detected (`UIApplication.userDidTakeScreenshotNotification`): warning card fades in, a system line is sent to the peer; screen recording/mirroring (`UIScreen.isCaptured`): photo replaced by `#0d0e19` + centred “hidden” message (proposed); video variant (play controls, proposed). Closing deletes the media locally and sends the opened receipt.

**Transitions.** Opened from a view-once bubble in 5.1/5.2 (full-screen cover, fade 0.36 s). Close → back to the chat; the bubble becomes “Açıldı”.

**Animation.** Warning: fade + 8 pt drop 0.36 s; viewer open/close cross-fade 0.36 s.

**Data.** Media (decrypted in memory only), sender, sentAt, screenshot event sink.

**Elements.**

<details><summary>Element table 5.6 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill rgb(13, 14, 25) | col |
| r.0 | 0,0 402×874 | shape |  |  |  | fill radial-gradient(circle at 16% 57%, rgba(255, 210, 150, 0.95) 0px, rgba(255, 210, 150, 0.95) 1.4px, transparent 2.4px), r… (full value in layout JSON) | abs |
| r.1 | 0,0 402×190 | shape |  |  |  | fill linear-gradient(rgba(5, 6, 12, 0.7), transparent) | abs |
| r.2 | 0,0 402×100.63 | stack |  |  |  |  | row ai:center gap 12 pad 56 16 0 16 |
| r.2.0 | 16,56.31 44×44 | shape |  |  |  | fill rgba(12, 13, 22, 0.4) · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.2.0.0 | 27.5,67.81 21×21 | icon | ph:x (regular) | 21pt | rgb(245, 244, 255) |  |  |
| r.2.1 | 72,56 237.5×44.63 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.1.0 | 72,56 237.5×23.25 | text | “deniz” | Mono 500 15/23.25 | rgb(245, 244, 255) |  |  |
| r.2.1.1 | 72,81.25 237.5×19.38 | text | “Bugün 18:40” | Inter 400 12.5/19.38 | rgba(245, 244, 255, 0.75) |  |  |
| r.2.2 | 321.5,62.31 64.5×32 | text | “1 kez” | Mono 400 11/17.05 | rgb(245, 244, 255) | fill rgba(12, 13, 22, 0.45) · r16 · border 1px dashed rgba(245, 244, 255, 0.5) | row ai:center pad 0 12 0 12 |
| r.3 | 12,116.63 378×91.39 | shape |  |  |  | fill surface·92% · r20 · shadow inset 0 0 0 1px danger·45%, 0 14px 40px rgba(0, 0, 0, 0.45) · backdropFilter blur(20px) | row ai:flex-start gap 12 pad 14 16 14 16 mar 16 12 0 12 |
| r.3.0 | 28,130.63 22×22 | icon | ph:camera (regular) | 22pt | danger |  |  |
| r.3.1 | 62,130.63 312×63.39 | stack |  |  |  |  | col gap 3 |
| r.3.1.0 | 62,130.63 312×24.02 | text | “Ekran görüntüsü algılandı” | Inter 500 15.5/24.02 | text |  |  |
| r.3.1.1 | 62,157.64 312×36.38 | text | “deniz’e bildirildi. iOS ekran görüntüsünü engellemiyor; yalnızca ha…” | Inter 400 13/18.2 | text·68% |  |  |
| r.4 | 0,208.02 402×564.59 | spacer |  |  |  |  | flex 1 |
| r.5 | 0,654 402×220 | shape |  |  |  | fill linear-gradient(transparent, rgba(5, 6, 12, 0.8)) | abs |
| r.6 | 0,772.61 402×101.39 | stack |  |  |  |  | col ai:center gap 8 pad 0 24 50 24 |
| r.6.0 | 130.98,772.61 140.02×23.25 | text | “Kapatınca silinir” | Inter 500 15/23.25 center | rgb(245, 244, 255) |  | row ai:center gap 8 |
| r.6.0.0 | 130.98,775.23 18×18 | icon | ph:eye-slash (regular) | 18pt | rgb(245, 244, 255) |  |  |
| r.6.1 | 52.8,803.86 296.41×20.14 | text | “Bu fotoğraf yeniden açılamaz ve yedeğe girmez.” | Inter 400 13/20.15 center | rgba(245, 244, 255, 0.72) |  |  |

</details>

Colour legend 5.6:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `surface·92%` | #232532@92% | #e4e7f5@92% |
| `danger·45%` | #f2716a@45% | #c53637@45% |
| `danger` | #f2716a | #c53637 |
| `text·68%` | #e9e9ed@68% | #292b31@68% |

# 06 · Güvenlik numarası — Safety number (“Hattın ucunda o mu?” / “Is it them at the end of the line?”)

> **Section note (TR):** Her sohbetin bir güvenlik numarası vardır. QR okutulup eşleşince sohbet doğrulanır; numara değişirse rozet kalkar ve kullanıcı uyarılır.
> **EN:** Every chat has a safety number. When the QR is scanned and matches, the chat is verified; if the number changes, the badge is removed and the user is warned.

## 6.1 · Güvenlik numarası — Safety number

Reference `reference/6.1-guvenlik-numarasi.png` · light `reference/light/6.1-guvenlik-numarasi.png` · data `layout/6.1-guvenlik-numarasi.json`

> **Note (TR):** Her sohbetin 60 haneli tek bir numarası vardır; iki tarafta aynı görünmelidir. Yan yanayken QR okutmak en hızlısı; uzaktaysanız haneleri başka bir kanaldan karşılaştırın.
> **EN:** Every chat has a single 60-digit number; it must look the same on both sides. Side by side, scanning the QR is fastest; if you're apart, compare the digits over another channel.

**Purpose.** Show the safety number (QR + 12 groups of 5 digits) and start verification.

**Layout.**
- C7 header: back, “Güvenlik numarası”, trailing C4 `ph:share-network` 21.
- Line (pad-top 26, pad 0 30): self avatar 48 (D) + “sen” `monoMeta` `text·68%` (gap 8) — line 2 high (gradient `accent400 → accent·30% → accent400`, margin 0 −2 22 −2 so it sits at the avatars' centre line, flowing packets) — peer avatar 48 + “deniz”.
- QR card 91,221 220×220 (margin-top 22, centred), fill `#f3f5fe` (both themes), r26, pad 16, shadow `0 0 0 1px text·10%, 0 20px 50px -20px accent·60%`; QR 188×188 modules `#292b31`, crisp edges. **Use the real scannable fingerprint** (libsignal); the design's QR is a placeholder.
- Digits grid (pad 24 24 0): 4 columns (auto), column gap 18, row gap 10, centred; `monoSafetyDigits` Mono 400 15 `text·88%`; 3 rows × 4 groups = 60 digits.
- Spacer; bottom (pad 0 20 40, gap 6): C1 “QR kodu okut” with `ph:scan` 20; C3 “Doğrulandı olarak işaretle” `text·80%` (46 high).

**Texts.**

| TR | EN |
|---|---|
| Güvenlik numarası | Safety number |
| sen | you |
| QR kodu okut | Scan QR code |
| Doğrulandı olarak işaretle | Mark as verified |
| *(already verified, proposed)* Doğrulandı · {date} · Doğrulamayı kaldır | Verified · {date} · Remove verification |

**States.** unverified (as drawn) / verified (seal shown on the line as in 6.3, secondary button becomes “Doğrulamayı kaldır”) / number changed (banner “Güvenlik numarası değişti” `danger`-tinted, proposed). Share → share sheet with the digits as text (proposed). In the contact screen (5.1 › the person, proposed) 6.1 is the Doğrulama tab and the share button moved into the header's “…” menu (“Numarayı paylaş”) beside Bildir and Engelle — next section.

**Transitions.** Back → chat. “QR kodu okut” → 6.2 (full-screen). “Doğrulandı olarak işaretle” → confirmation (proposed) → 6.3.

**Animation.** Line packets (`linePacket`).

**Data.** Safety number (60 digits) + scannable fingerprint payload, verification state + date + method (face-to-face QR / manual).

**Elements.**

<details><summary>Element table 6.1 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.0.0 | 16,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 27.5,67.5 21×21 | icon | ph:caret-left (regular) | 21pt | text |  |  |
| r.0.1 | 76,56 250×44 | text | “Güvenlik numarası” | Inter 600 17/26.35 | text |  | grid place:center abs |
| r.0.2 | 342,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.2.0 | 353.5,67.5 21×21 | icon | ph:share-network (regular) | 21pt | text |  |  |
| r.1 | 0,100 402×99.05 | box |  |  |  |  | pad 26 0 0 0 |
| r.1.0 | 0,126 402×73.05 | stack |  |  |  |  | row ai:center pad 0 30 0 30 |
| r.1.0.0 | 30,126 48×73.05 | stack |  |  |  |  | col ai:center gap 8 |
| r.1.0.0.0 | 30,126 48×48 | text | “S” | Mono 500 17/17 | accent300 | fill accent900 · r50% · shadow inset 0 0 0 1px accent700 | grid place:center |
| r.1.0.0.1 | 42.44,182 23.11×17.05 | text | “sen” | Mono 400 11/17.05 | text·68% |  |  |
| r.1.0.1 | 76,150.52 250×2 | shape |  |  |  | fill linear-gradient(90deg, accent400, accent·30% 50%, accent400) | mar 0 -2 22 -2 flex 1 |
| r.1.0.1.0 | 204,149.52 44×4 | shape |  |  |  | fill linear-gradient(90deg, transparent, accent200, transparent) · r4 · shadow 0 0 12px 2px accent·50% · anim u2-packet-r 6s cubic-bezier(0.45, 0, 0.55, 1) delay 0s ×infinite | mar -2 0 0 -22 abs |
| r.1.0.1.1 | 54,149.52 44×4 | shape |  |  |  | fill linear-gradient(90deg, transparent, accent200, transparent) · r4 · shadow 0 0 12px 2px accent·50% · opacity 0 · anim u2-packet-l 6s cubic-bezier(0.45, 0, 0.55, 1) delay 3s ×infinite | mar -2 0 0 -22 abs |
| r.1.0.2 | 324,126 48×73.05 | stack |  |  |  |  | col ai:center gap 8 |
| r.1.0.2.0 | 324,126 48×48 | text | “D” | Mono 500 17/17 | accent100 | fill accent800 · r50% | grid place:center |
| r.1.0.2.1 | 328.75,182 38.5×17.05 | text | “deniz” | Mono 400 11/17.05 | text·68% |  |  |
| r.2 | 91,221.05 220×220 | shape |  |  |  | fill rgb(243, 245, 254) · r26 · shadow 0 0 0 1px text·10%, 0 20px 50px -20px accent·60% | self:center pad 16 16 16 16 mar 22 0 0 0 |
| r.2.0 | 107,237.05 188×188 | svg | custom svg |  | text |  |  |
| r.3 | 0,441.05 402×113.75 | stack |  |  |  |  | grid cols repeat(4, auto) jc:center gap 10/18 pad 24 24 0 24 |
| r.3.0 | 69,465.05 52.5×23.25 | box |  |  |  |  |  |
| r.3.0.0 | 69,467.05 52.5×18 | text | “08223” | Mono 400 15/23.25 | text·88% |  |  |
| r.3.1 | 139.5,465.05 52.5×23.25 | box |  |  |  |  |  |
| r.3.1.0 | 139.5,467.05 52.5×18 | text | “78626” | Mono 400 15/23.25 | text·88% |  |  |
| r.3.2 | 210,465.05 52.5×23.25 | box |  |  |  |  |  |
| r.3.2.0 | 210,467.05 52.5×18 | text | “74024” | Mono 400 15/23.25 | text·88% |  |  |
| r.3.3 | 280.5,465.05 52.5×23.25 | box |  |  |  |  |  |
| r.3.3.0 | 280.5,467.05 52.5×18 | text | “23263” | Mono 400 15/23.25 | text·88% |  |  |
| r.3.4 | 69,498.3 52.5×23.25 | box |  |  |  |  |  |
| r.3.4.0 | 69,500.3 52.5×18 | text | “12493” | Mono 400 15/23.25 | text·88% |  |  |
| r.3.5 | 139.5,498.3 52.5×23.25 | box |  |  |  |  |  |
| r.3.5.0 | 139.5,500.3 52.5×18 | text | “59214” | Mono 400 15/23.25 | text·88% |  |  |
| r.3.6 | 210,498.3 52.5×23.25 | box |  |  |  |  |  |
| r.3.6.0 | 210,500.3 52.5×18 | text | “00841” | Mono 400 15/23.25 | text·88% |  |  |
| r.3.7 | 280.5,498.3 52.5×23.25 | box |  |  |  |  |  |
| r.3.7.0 | 280.5,500.3 52.5×18 | text | “88304” | Mono 400 15/23.25 | text·88% |  |  |
| r.3.8 | 69,531.55 52.5×23.25 | box |  |  |  |  |  |
| r.3.8.0 | 69,533.55 52.5×18 | text | “26120” | Mono 400 15/23.25 | text·88% |  |  |
| r.3.9 | 139.5,531.55 52.5×23.25 | box |  |  |  |  |  |
| r.3.9.0 | 139.5,533.55 52.5×18 | text | “14864” | Mono 400 15/23.25 | text·88% |  |  |
| r.3.10 | 210,531.55 52.5×23.25 | box |  |  |  |  |  |
| r.3.10.0 | 210,533.55 52.5×18 | text | “87953” | Mono 400 15/23.25 | text·88% |  |  |
| r.3.11 | 280.5,531.55 52.5×23.25 | box |  |  |  |  |  |
| r.3.11.0 | 280.5,533.55 52.5×18 | text | “64169” | Mono 400 15/23.25 | text·88% |  |  |
| r.4 | 0,554.8 402×173.2 | spacer |  |  |  |  | flex 1 |
| r.5 | 0,728 402×146 | stack |  |  |  |  | col gap 6 pad 0 20 40 20 |
| r.5.0 | 20,728 362×54 | text | “QR kodu okut” | Inter 500 17/26.35 | accent300 | r16 · border 1px solid accent · shadow 0 0 32px -8px accent·70% | row ai:center jc:center gap 8 |
| r.5.0.0 | 132.45,745 20×20 | icon | ph:scan (regular) | 20pt | accent300 |  |  |
| r.5.1 | 20,788 362×46 | text | “Doğrulandı olarak işaretle” | Inter 400 16/24.8 | text·80% |  | row ai:center jc:center gap 8 |

</details>

Colour legend 6.1:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `accent300` | #d2cefd | #5d5294 |
| `accent900` | #2b2741 | #f5f4ff |
| `accent700` | #5d5294 | #d2cefd |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent400` | #b5abfc | #796cbf |
| `accent·30%` | #9184d9@30% | #796cbf@30% |
| `accent200` | #e7e5fe | #423a6a |
| `accent·50%` | #9184d9@50% | #796cbf@50% |
| `accent100` | #f5f4ff | #2b2741 |
| `accent800` | #423a6a | #e7e5fe |
| `text·10%` | #e9e9ed@10% | #292b31@10% |
| `accent·60%` | #9184d9@60% | #796cbf@60% |
| `text·88%` | #e9e9ed@88% | #292b31@88% |
| `accent` | #9184d9 | #796cbf |
| `accent·70%` | #9184d9@70% | #796cbf@70% |
| `text·80%` | #e9e9ed@80% | #292b31@80% |

## 6.1 › Kişi ekranı · Engelle ve Bildir — Contact screen · Block and report (proposed)

No design state: built in 6.1's and 4.4's language for App Review Guideline 1.2 (block abusive users, report offensive content, published contact information). Harness: `-U2Route safety:deniz/menu`, `…/block`, `…/blocked`, `…/report`, `…/report-address`; `conversation:deniz/5.1-blocked`; `chats:4.1-blocked`, `chats:4.4-report`.

**Layout.**
- The contact screen (5.1 › the person): C7 header (back · the name in `monoUsernameL` · C4 `ph:dots-three` 21 in 6.1's share button's place), C22 tabs Doğrulama · Fotoğraflar · Belgeler. “…” → the 4.3 / 4.4 floating menu (250 wide, rows 46) at design y 106, 16 from the right: “Numarayı paylaş” `ph:share-network` (Doğrulama only, once the number is known) ‖ “Bildir” `ph:warning` · “Engelle” `ph:prohibit` `danger` — “Engeli kaldır” `ph:prohibit` `text` while blocked.
- Engelle → the request block's own alert (4.3): “{user} engellensin mi?” / “Sana bir daha yazamaz. Engellendiğini bilmez.” · Engelle (destructive) · Bildir · Vazgeç.
- Blocked: `ph:prohibit` 16 `danger` after the name (after the seal when verified). Doğrulama starts, 16 below the tabs (gutter 16), with the 5.4 note card's look — inner 1 pt `danger·40%`, r20, pad 16 16 6 16, gap 14: `ph:prohibit` 22 `danger` · “{user} engellendi” `noteTitle` + the sentence `captionTight` `text·68%` (gap 4) · “Engeli kaldır” (`rowTitle` `accent300`, 36 high) under them.
- Bildir → a full-height C18 sheet (the invite sheet's language): sheet bar “Vazgeç” · “Bildir”; the first line `callout` `text·68%` (pad 0 8); 20 below: “NEDEN” + a C13 card on `bg` with the five reasons (rows 48, `rowTitle`, the chosen one with `ph:check` 18 `accent300`, separators inset 14); 22 below: “NOT · İSTEĞE BAĞLI” + a field on `bg` r16, 96–160 high, `bubble` 16 `text`, placeholder `text·50%`, 1.5 pt `accent` ring while focused, at most 500 characters; 22 below: a C13 card on `bg` with a C14 row — danger icon tile (`danger·14%`, `ph:prohibit` 18 `danger`) · “Bu kişiyi de engelle” + subtitle `captionCompact` · toggle, ON by default (not shown for someone blocked already); 16 below: the privacy line (`ph:shield-check` 18 `accent300` + `callout` `text·68%`, pad 0 8). The form scrolls (content pad 12 16 12) above a bar pinned to the sheet's bottom (`surface`, pad 12 16 40 — 12 above the keyboard while it is up, a 16 pt fade from `surface` above it): after a failure “E-posta gönderilemedi. Tekrar dene.” `caption` `danger` (pad 0 8, 10 above the button), then C1 “E-postayla gönder” with `ph:paper-plane-tilt` 20 — at 45 % until a reason is chosen, the soft spinner while Mail is up.
- No e-mail on this phone: the content cross-fades (0.36 s) to a centred block (pad-top 28, gap 14): C13 icon tile 52 r16 with `ph:paper-plane-tilt` 24; “Bu telefonda e-posta ayarlı değil” `rowTitleEmphasis` + the sentence `callout` `text·68%` (gap 6); 24 below a C13 row on `bg` (56 high, pad 0 12 0 16): “support@us2us.app” `monoUsername` `text` (selectable) + “Kopyala” (`rowTitle` `accent300` with `ph:copy` 18; “Kopyalandı” with `ph:check` once copied); 14 below, when the block followed: `ph:prohibit` 16 `danger` + “{user} engellendi.” `caption` `text·68%`; 28 below C1 “Tamam”. The bar's “Vazgeç” becomes “Kapat”.
- 5.1 and 4.1 for someone blocked: 5.1 › States, 4.1 › States.

**Texts.**

| TR | EN |
|---|---|
| Numarayı paylaş · Bildir · Engelle · Engeli kaldır | Share number · Report · Block · Unblock |
| {user} engellensin mi? · Sana bir daha yazamaz. Engellendiğini bilmez. · Engelle · Bildir · Vazgeç | Block {user}? · They can't message you again. They won't know they were blocked. · Block · Report · Cancel |
| {user} engellendi · Yazdıkları sana ulaşmaz, sen de ona yazamazsın. Engellendiğini bilmez. · Engeli kaldır | {user} is blocked · Their messages don't reach you and you can't write to them. They don't know they're blocked. · Unblock |
| Bildir | Report |
| {user} hakkındaki bildirimin e-postayla us2us ekibine gider. Ne olduğunu seç; istersen kısa bir not ekle. | Your report about {user} goes to the us2us team by e-mail. Choose what happened and add a short note if you like. |
| Neden · Spam · Taciz veya zorbalık · Uygunsuz içerik · Kimliğe bürünme · Diğer | Reason · Spam · Harassment or bullying · Inappropriate content · Impersonation · Other |
| Not · isteğe bağlı · Ne oldu? Birkaç kelime yeter. | Note · optional · What happened? A few words are enough. |
| Bu kişiyi de engelle · Bildirimi gönderince engellenir; vazgeçersen engellenmez. | Also block this person · Blocked when you send the report; not if you cancel. |
| Mesajlar uçtan uca şifreli: bildirime hiçbir mesaj içeriği eklenmez, yalnızca kullanıcı adı, seçtiğin neden, notun, uygulama sürümü ve saat gider. İstersen e-postaya ekran görüntüsü ekleyebilirsin. | Messages are end-to-end encrypted: no message content is added to the report, only the username, the reason you chose, your note, the app version and the time. You can attach screenshots to the e-mail if you like. |
| E-postayla gönder · E-posta gönderilemedi. Tekrar dene. | Send by e-mail · The e-mail couldn't be sent. Try again. |
| Bu telefonda e-posta ayarlı değil · Bildirimini bu adrese e-postayla gönder: kullanıcı adını ve ne olduğunu yaz, istersen ekran görüntüsü ekle. · Kopyala · Kopyalandı · {user} engellendi. · Tamam | No e-mail is set up on this phone · Send your report to this address by e-mail: write the username and what happened, and attach screenshots if you like. · Copy · Copied · {user} is blocked. · Done |
| *(e-mail)* us2us bildirimi: @{user} | us2us report: @{user} |
| *(e-mail body)* Bildirilen kullanıcı: @{user} [(kullanıcı adı doğrulanmadı)] · Neden: {reason} · Not: {note or —} · Uygulama: us2us iOS {version} ({build}) · Zaman: {yyyy-MM-dd HH:mm} UTC · Mesajlar uçtan uca şifreli olduğu için bu bildirime hiçbir mesaj içeriği eklenmedi. İstersen ekran görüntüsü ekleyebilirsin. | Reported user: @{user} [(username not confirmed)] · Reason: {reason} · Note: {note or —} · App: us2us iOS {version} ({build}) · Time: {yyyy-MM-dd HH:mm} UTC · Messages are end-to-end encrypted, so no message content was added to this report. You may attach screenshots. |

**States.**
- Block (PROTOCOL §6.4): what the person sends is dropped on this phone, nothing more is sent to them (messages still waiting to go fail — “İletilemedi”, to be sent again after an unblock — and a live location shared with them ends) and the access key they had is replaced; they are not told. The chat stays, read only; if Ghost erases it, 4.2 › “Mesaj yaz” brings it back, still blocked. 7.1 › Engellenenler lists everyone blocked, chat or not, each with “Engeli kaldır” (the same unblock). “Engeli kaldır” needs no confirmation: the person is back in the state before the block (a contact gets the current access key and the profile again; a blocked request is a request again and leaves 4.1 — from the contact screen, back to 4.1 with it). What they sent meanwhile was never kept.
- Report: an e-mail to support@us2us.app in the app's language with the subject and body above — the username alone names the person (marked when the directory did not confirm it; “kullanıcı adı bilinmiyor” when the phone has none); no message, key or account id is ever added. Mail when it can send (inside the app: no privacy cover, no lock), else another mail app through `mailto:` (outside the app: the lock follows on return), else the address to copy.
- “Bu kişiyi de engelle” blocks only once the report left the sheet: sent by Mail, handed to another mail app, or the address shown to the user. Cancelling Mail, keeping a draft or a failure blocks nobody and keeps the form as it was.

**Transitions.** “…” → menu (fade + scale from 0.96, 0.36 s). Engelle → alert → blocked in place (the note drops in, the mark fades in, 0.36 s). Bildir → sheet (`springSoft` + backdrop); from an alert it rises once the alert has gone. Mail: the system's own presentation over the sheet; sent → the sheet goes down. “Tamam” / “Vazgeç” / swipe down → dismiss.

**Data.** `Peer.isBlocked`; `MessengerService.block(chat:)` / `unblock(chat:)` / `block(_ request:)`; `reportSubject(for:)` → username + whether the directory confirmed it.

## 6.2 · QR tarama — QR scan

Reference `reference/6.2-qr-tarama.png` · light `reference/light/6.2-qr-tarama.png` · data `layout/6.2-qr-tarama.json`

> **Note (TR):** Kamera yalnızca bu ekranda açılır, görüntü cihazdan çıkmaz. Kodlar eşleşmezse "Numaralar uyuşmuyor" uyarısı çıkar ve sohbet doğrulanmamış kalır.
> **EN:** The camera opens only on this screen; the image never leaves the device. If the codes don't match, a “Numbers don't match” warning appears and the chat stays unverified.

**Purpose.** Scan the peer's safety-number QR.

**Layout.** Always dark.
- Camera preview full-screen (design placeholder: `#0c0d14` with a radial `#2a2d3e → #0c0d14` vignette and a 6 pt dot grid `#e9e9ed@8%`; the phone with the QR is mock content).
- Top bar (pad 56 16 0): close C4 `#f5f4ff@12%` `ph:x` 21; title “Kodu okut” `navTitle` `#f5f4ff`; torch C4 `ph:lightning` 21.
- Viewfinder 256×256 centred at 73,284: four corner brackets 46×46, 3 pt `#d2cefd`, outer corner radius 18; scan line 220×2 r2 `#d2cefd` at 47 % height with glow `0 0 18px 3px #9184d9@70%`.
- Bottom (pad 0 24 46, centred, gap 16): hint `cameraHint` Inter 400 15/21.75 `#f5f4ff@80%` (max 300); pill button 44 high pad 0 18 r22 `#f5f4ff@12%`, `ph:qr-code` 18 + “Kendi kodumu göster” Inter 400 15 `#f5f4ff`.

**Texts.**

| TR | EN |
|---|---|
| Kodu okut | Scan code |
| deniz'in telefonundaki güvenlik numarası kodunu çerçeveye al. | Frame the safety-number code on deniz's phone. |
| Kendi kodumu göster | Show my code |
| Numaralar uyuşmuyor *(from the note)* | Numbers don't match |
| *(proposed)* Sohbet doğrulanmamış kaldı. Kodu yeniden okutmayı dene. | The chat stays unverified. Try scanning again. |
| *(camera denied, proposed)* Kamera izni gerekli · Ayarlar'ı aç | Camera access needed · Open Settings |

**States.** scanning → match → 6.3 · mismatch → danger banner (proposed: 5.6-style floating card with `ph:warning-circle`) and stays · camera permission denied → message + Settings link · torch on/off (icon `ph:lightning` fill when on, proposed).

**Transitions.** Close → 6.1 (or 4.2 when started from “QR kodla ekle”). “Kendi kodumu göster” → my QR card (6.1 layout without the peer).

**Animation.** Scan line sweeps up and down inside the frame (proposed: 2.4 s `curveSoft` ping-pong; Reduce Motion: static at 47 %).

**Data.** Camera session (AVFoundation, no network), expected fingerprint, compare result.

**Elements.**

<details><summary>Element table 6.2 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill rgb(12, 13, 20) | col |
| r.0 | 0,0 402×874 | shape |  |  |  | fill radial-gradient(60% 45% at 50% 45%, rgb(42, 45, 62), rgb(12, 13, 20) 80%) | abs |
| r.1 | 0,0 402×874 | shape |  |  |  | fill radial-gradient(circle, rgba(233, 233, 237, 0.08) 1px, transparent 1.5px) | abs |
| r.2 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.2.0 | 16,56 44×44 | shape |  |  |  | fill rgba(245, 244, 255, 0.12) · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.2.0.0 | 27.5,67.5 21×21 | icon | ph:x (regular) | 21pt | rgb(245, 244, 255) |  |  |
| r.2.1 | 159.31,64.83 83.39×26.34 | text | “Kodu okut” | Inter 600 17/26.35 | rgb(245, 244, 255) |  |  |
| r.2.2 | 342,56 44×44 | shape |  |  |  | fill rgba(245, 244, 255, 0.12) · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.2.2.0 | 353.5,67.5 21×21 | icon | ph:lightning (regular) | 21pt | rgb(245, 244, 255) |  |  |
| r.3 | 0,100 402×624.5 | stack |  |  |  |  | grid place:center flex 1 |
| r.3.0 | 73,284.25 256×256 | box |  |  |  |  |  |
| r.3.0.0 | 107.83,319.08 186.34×186.34 | shape |  |  |  | fill rgba(243, 245, 254, 0.88) · r16 · shadow rgba(0, 0, 0, 0.45) 0px 20px 40px | pad 12 12 12 12 abs matrix(0.996195, -0.0871557, 0.0871557, 0.996195, 0, 0) |
| r.3.0.0.0 | 120.83,332.08 160.34×160.34 | svg | custom svg |  | text |  |  |
| r.3.0.1 | 73,284.25 46×46 | shape |  |  |  | r18/0/0/0 · border 3px solid rgb(210, 206, 253) | abs |
| r.3.0.2 | 283,284.25 46×46 | shape |  |  |  | r0/18/0/0 · border 3px solid rgb(210, 206, 253) | abs |
| r.3.0.3 | 73,494.25 46×46 | shape |  |  |  | r0/0/0/18 · border 3px solid rgb(210, 206, 253) | abs |
| r.3.0.4 | 283,494.25 46×46 | shape |  |  |  | r0/0/18/0 · border 3px solid rgb(210, 206, 253) | abs |
| r.3.0.5 | 91,404.56 220×2 | shape |  |  |  | fill rgb(210, 206, 253) · r2 · shadow rgba(145, 132, 217, 0.7) 0px 0px 18px 3px | abs |
| r.4 | 0,724.5 402×149.5 | stack |  |  |  |  | col ai:center gap 16 pad 0 24 46 24 |
| r.4.0 | 51,724.5 300×43.5 | text | “deniz’in telefonundaki güvenlik numarası kodunu çerçeveye al.” | Inter 400 15/21.75 center | rgba(245, 244, 255, 0.8) |  | maxW 300 |
| r.4.1 | 94.7,784 212.58×44 | text | “Kendi kodumu göster” | Inter 400 15/23.25 center | rgb(245, 244, 255) | fill rgba(245, 244, 255, 0.12) · r22 | row ai:center gap 8 pad 0 18 0 18 |
| r.4.1.0 | 112.7,797 18×18 | icon | ph:qr-code (regular) | 18pt | rgb(245, 244, 255) |  |  |

</details>

Colour legend 6.2:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `text·18%` | #e9e9ed@18% | #292b31@18% |

## 6.3 · Doğrulandı — Verified

Reference `reference/6.3-dogrulandi.png` · light `reference/light/6.3-dogrulandi.png` · data `layout/6.3-dogrulandi.json`

> **Note (TR):** Eşleşince sohbet başlığında rozet görünür. Karşı tarafın numarası sonradan değişirse (yeni cihaz, yeniden kurulum) rozet kalkar ve sohbete uyarı düşer.
> **EN:** When it matches, a badge appears in the chat header. If the other side's number changes later (new device, reinstall), the badge is removed and a warning drops into the chat.

**Purpose.** Confirmation of successful verification.

**Layout.**
- Dot grid C24 (`text·14%`, mask 70 %×36 % at 50 %/40 %).
- Top bar: close C4 `ph:x` 21 (left), empty 44 spacer (right).
- Centre stack (justify centre, gap 36): the sen ↔ deniz line (as 6.1) with a verified seal on its midpoint: 32 circle `bg`, ring `0 0 0 1px accent800` + glow `0 0 24px accent·55%`, `ph:seal-check` fill 22 `accent`; title “Hat doğrulandı.” `displayTitleLBalanced` Doto 900 40/41.2 centred (2 lines, balanced); paragraph `body` `text·68%` centred; pill “8 Ekim 2026 · 18:52 · yüz yüze” (`fillSubtle` r14, pad 8 14, gap 8, `ph:clock` 14 + `monoMeta` `text·68%`).
- C1 “Sohbete dön”.

**Texts.**

| TR | EN |
|---|---|
| Hat doğrulandı. | Line verified. |
| Sen ve deniz aynı güvenlik numarasını görüyorsunuz: hatta yalnızca ikinizin anahtarı var. *(app copy since 2026-10-11; the reference drew “Aradaki hatta başka kimse yok.”: a matching number proves the keys, not more)* | You and deniz see the same safety number: only your two keys are on the line. |
| 8 Ekim 2026 · 18:52 · yüz yüze | 8 October 2026 · 18:52 · face to face |
| *(manual, proposed)* elle işaretlendi | marked manually |
| Sohbete dön | Back to chat |

**States.** method “yüz yüze” (QR) or “elle işaretlendi” (manual).

**Transitions.** “Sohbete dön” / close → 5.1 (header now shows the seal; a system line “Güvenlik numarası doğrulandı” is added — proposed).

**Animation.** On appear: seal scales 0.6→1 with glow fade-in 0.5 s `curveOut`, title and paragraph fade in 0.36 s staggered 0.08 s; packets on the line continue.

**Data.** Peer username, verifiedAt, method.

**Elements.**

<details><summary>Element table 6.3 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×874 | shape |  |  |  | fill radial-gradient(circle, text·14% 1px, transparent 1.6px) · mask radial-gradient(70% 36% at 50% 40%, #000000, #000000@0% 75%) | abs |
| r.1 | 0,0 402×100 | box |  |  |  |  |  |
| r.1.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.1.0.0 | 16,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.1.0.0.0 | 27.5,67.5 21×21 | icon | ph:x (regular) | 21pt | text |  |  |
| r.1.0.1 | 342,56 44×44 | box |  |  |  |  |  |
| r.2 | 0,100 402×676 | stack |  |  |  |  | col jc:center gap 36 flex 1 |
| r.2.0 | 0,264.27 402×73.05 | stack |  |  |  |  | row ai:center pad 0 30 0 30 |
| r.2.0.0 | 30,264.27 48×73.05 | stack |  |  |  |  | col ai:center gap 8 |
| r.2.0.0.0 | 30,264.27 48×48 | text | “S” | Mono 500 17/17 | accent300 | fill accent900 · r50% · shadow inset 0 0 0 1px accent700 | grid place:center |
| r.2.0.0.1 | 42.44,320.27 23.11×17.05 | text | “sen” | Mono 400 11/17.05 | text·68% |  |  |
| r.2.0.1 | 76,288.78 250×2 | shape |  |  |  | fill linear-gradient(90deg, accent400, accent·30% 50%, accent400) | mar 0 -2 22 -2 flex 1 |
| r.2.0.1.0 | 204,287.78 44×4 | shape |  |  |  | fill linear-gradient(90deg, transparent, accent200, transparent) · r4 · shadow 0 0 12px 2px accent·50% · anim u2-packet-r 6s cubic-bezier(0.45, 0, 0.55, 1) delay 0s ×infinite | mar -2 0 0 -22 abs |
| r.2.0.1.1 | 54,287.78 44×4 | shape |  |  |  | fill linear-gradient(90deg, transparent, accent200, transparent) · r4 · shadow 0 0 12px 2px accent·50% · opacity 0 · anim u2-packet-l 6s cubic-bezier(0.45, 0, 0.55, 1) delay 3s ×infinite | mar -2 0 0 -22 abs |
| r.2.0.1.2 | 185,273.78 32×32 | shape |  |  |  | fill bg · r50% · shadow 0 0 0 1px accent800, 0 0 24px accent·55% | grid place:center mar -16 0 0 -16 abs |
| r.2.0.1.2.0 | 190,278.78 22×22 | icon | ph:seal-check (fill) | 22pt | accent |  |  |
| r.2.0.2 | 324,264.27 48×73.05 | stack |  |  |  |  | col ai:center gap 8 |
| r.2.0.2.0 | 324,264.27 48×48 | text | “D” | Mono 500 17/17 | accent100 | fill accent800 · r50% | grid place:center |
| r.2.0.2.1 | 328.75,320.27 38.5×17.05 | text | “deniz” | Mono 400 11/17.05 | text·68% |  |  |
| r.2.1 | 0,373.31 402×169.38 | stack |  |  |  |  | col ai:center gap 14 pad 0 30 0 30 |
| r.2.1.0 | 30,373.31 342×83.38 | text | “Hat doğrulandı.” | Doto 900 40/41.2 ROND100 center | text |  |  |
| r.2.1.0.0 | 314.31,410.5 13.36×49 | text | “.” | Inter 700 40/41.2 center | text |  |  |
| r.2.1.1 | 30,470.69 342×72 | text | “Sen ve deniz aynı güvenlik numarasını görüyorsunuz. Aradaki hatta b…” | Inter 400 16/24 center | text·68% |  |  |
| r.2.2 | 60.5,578.69 281×33.05 | text | “8 Ekim 2026 · 18:52 · yüz yüze” | Mono 400 11/17.05 | text·68% | fill text·6% · r14 | row ai:center self:center gap 8 pad 8 14 8 14 |
| r.2.2.0 | 74.5,588.2 14×14 | icon | ph:clock (regular) | 14pt | text·68% |  |  |
| r.3 | 0,776 402×98 | box |  |  |  |  | pad 0 20 44 20 |
| r.3.0 | 20,776 362×54 | text | “Sohbete dön” | Inter 500 17/26.35 | accent300 | r16 · border 1px solid accent · shadow 0 0 32px -8px accent·70% | row ai:center jc:center gap 8 |

</details>

Colour legend 6.3:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·14%` | #e9e9ed@14% | #292b31@14% |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `accent300` | #d2cefd | #5d5294 |
| `accent900` | #2b2741 | #f5f4ff |
| `accent700` | #5d5294 | #d2cefd |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent400` | #b5abfc | #796cbf |
| `accent·30%` | #9184d9@30% | #796cbf@30% |
| `accent200` | #e7e5fe | #423a6a |
| `accent·50%` | #9184d9@50% | #796cbf@50% |
| `accent` | #9184d9 | #796cbf |
| `accent800` | #423a6a | #e7e5fe |
| `accent·55%` | #9184d9@55% | #796cbf@55% |
| `accent100` | #f5f4ff | #2b2741 |
| `text·6%` | #e9e9ed@6% | #292b31@6% |
| `accent·70%` | #9184d9@70% | #796cbf@70% |

# 07 · Ayarlar ve yedek — Settings and backup (“Anahtar sende kalır” / “The key stays with you”)

> **Section note (TR):** Kod sıfırlanamadığı için şifreli yedek kullanıcının tek güvencesi. Dosya cihazda şifrelenir; parolası yalnızca kullanıcıda.
> **EN:** Because the code can't be reset, the encrypted backup is the user's only safeguard. The file is encrypted on the device; only the user has its password.

## 7.1 · Ayarlar — Settings

Reference `reference/7.1-ayarlar.png` · light `reference/light/7.1-ayarlar.png` · data `layout/7.1-ayarlar.json`

> **Note (TR):** Aktif durumu varsayılan olarak yalnızca kabul ettiğin kişilere görünür; "Kimse" seçilirse sen de başkalarınınkini görmezsin. Ekran görüntüsü uyarısı ayar değil, her zaman açık.
> **EN:** Activity status is visible only to people you've accepted by default; if “Nobody” is chosen, you don't see anyone else's either. The screenshot warning isn't a setting; it's always on.

**Purpose.** All settings. The content is taller than the screen (ends at y ≈ 899) — it scrolls.

**Layout.**
- Top bar (pad 56 16 0): back C4 `ph:caret-left` 21; nothing on the right.
- Title “Ayarlar” `displayTitle` Doto 900 38 at 20,108 (pad 12 20 0).
- Content (pad 18 16 0, gap 18):
  - Profile card 16,168 370×74, `surface` r18, pad 12 14, gap 14: avatar 50 (D, Mono 500 18) · “@selin_n” `monoUsernameL` + “Kullanıcı adın · değiştirilemez” `caption` `text·68%` · C4-40 `ph:qr-code` 20 `accent300`.
  - Sections (C13): GİZLİLİK: Aktif durumu (`ph:circle-half`) “Kabul ettiklerim” › · Okundu bilgisi (`ph:checks`) toggle ON · *(proposed)* Engellenenler (`ph:prohibit`) with how many are blocked (nothing when nobody is) › (see 7.1 › Engellenenler). GÜVENLİK: Face ID ile aç (custom Face ID glyph 18, stroke 1.8) toggle ON · Otomatik kilit (`ph:lock-simple`) “Hemen” › · Gizli kodu değiştir (`ph:password`) ›. SOHBETLER: Varsayılan Ghost süresi (`ph:ghost`) “24 saat” › · Varsayılan süreli mesaj (`ph:timer`) “Kapalı” ›. VERİ VE GÖRÜNÜM: Yedek (`ph:file-lock`) “Son: 8 Eki” › · Tema (`ph:moon`) “Sistem” › · Dil (`ph:translate`) “Türkçe” ›.

**Texts.**

| TR | EN |
|---|---|
| Ayarlar | Settings |
| @selin_n · Kullanıcı adın · değiştirilemez | Your username · can't be changed |
| Gizlilik | Privacy |
| Aktif durumu · Kabul ettiklerim | Activity status · People I've accepted |
| *(options, proposed from the note)* Kabul ettiklerim · Kimse | People I've accepted · Nobody |
| Okundu bilgisi | Read receipts |
| *(proposed)* Engellenenler · 2 | Blocked · 2 |
| Güvenlik | Security |
| Face ID ile aç | Unlock with Face ID |
| Otomatik kilit · Hemen | Auto-lock · Immediately |
| Gizli kodu değiştir | Change secret code |
| Sohbetler | Chats |
| Varsayılan Ghost süresi · 24 saat | Default Ghost period · 24 hours |
| Varsayılan süreli mesaj · Kapalı | Default disappearing messages · Off |
| Veri ve görünüm | Data and appearance |
| Yedek · Son: 8 Eki | Backup · Last: Oct 8 |
| *(never backed up, proposed)* Hiç alınmadı | Never |
| Tema · Sistem | Theme · System |
| *(options, proposed)* Sistem · Koyu · Açık | System · Dark · Light |
| Dil · Türkçe | Language · Türkçe |
| *(options)* Türkçe · English | Türkçe · English |

Section labels are uppercased with the Turkish locale (“GİZLİLİK”, “GÜVENLİK”, “VERİ VE GÖRÜNÜM”).

**States & decisions.** “Otomatik kilit” — owner decision: the app always locks immediately on every return from background; show “Hemen” and either make the row informational (no chevron) or open a screen with that single, explained option. Open point for the app. “Okundu bilgisi” off → read receipts neither sent nor shown (proposed, symmetric like presence). Face ID toggle mirrors 2.3. “Gizli kodu değiştir” → current code (3.2 layout) → new code ×2 (2.2 layout). “Dil” → in-app language switch (tr/en).

**Transitions.** Back → 4.1. Rows with › push their detail screens (pickers with the same C13 cards + a check mark `ph:check` `accent300` on the selected row — proposed). “Yedek” → 7.2.

**Animation.** Standard push (`screenPush`); toggles `springSoft`.

**Data.** Own username; settings: presence visibility, read receipts, faceIDEnabled, autoLock (fixed), defaultGhostDuration, defaultDisappearingTTL, lastBackupAt, theme, language.

**Elements.**

<details><summary>Element table 7.1 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.0.0 | 16,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 27.5,67.5 21×21 | icon | ph:caret-left (regular) | 21pt | text |  |  |
| r.0.1 | 386,78 0×0 | box |  |  |  |  |  |
| r.1 | 0,100 402×50.25 | box |  |  |  |  | pad 12 20 0 20 |
| r.1.0 | 20,108 159.61×46 | text | “Ayarlar” | Doto 900 38/38 ROND100 | text |  |  |
| r.2 | 0,150.25 402×749.06 | stack |  |  |  |  | col gap 18 pad 18 16 0 16 |
| r.2.0 | 16,168.25 370×74 | shape |  |  |  | fill surface · r18 | row ai:center gap 14 pad 12 14 12 14 |
| r.2.0.0 | 30,180.25 50×50 | text | “S” | Mono 500 18/18 | accent300 | fill accent900 · r50% · shadow inset 0 0 0 1px accent700 | grid place:center |
| r.2.0.1 | 94,181.28 224×47.94 | stack |  |  |  |  | col gap 3 flex 1 |
| r.2.0.1.0 | 94,181.28 224×24.8 | text | “@selin_n” | Mono 500 16/24.8 | text |  |  |
| r.2.0.1.1 | 94,209.08 224×20.14 | text | “Kullanıcı adın · değiştirilemez” | Inter 400 13/20.15 | text·68% |  |  |
| r.2.0.2 | 332,185.25 40×40 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.2.0.2.0 | 342,195.25 20×20 | icon | ph:qr-code (regular) | 20pt | accent300 |  |  |
| r.2.1 | 16,260.25 370×121.27 | stack |  |  |  |  | col gap 7 |
| r.2.1.0 | 16,260.25 370×16.27 | text | “Gizlilik” | Mono 400 10.5/16.27 ls1.05 UPPER | text·68% |  | pad 0 16 0 16 |
| r.2.1.1 | 16,283.52 370×98 | shape |  |  |  | fill surface · r18 |  |
| r.2.1.1.0 | 16,283.52 370×48 | stack |  |  |  |  | row ai:center gap 13 pad 0 14 0 14 minH 48 |
| r.2.1.1.0.0 | 30,292.52 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.1.1.0.0.0 | 36,298.52 18×18 | icon | ph:circle-half (regular) | 18pt | accent300 |  |  |
| r.2.1.1.0.1 | 73,295.11 148.22×24.8 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.1.1.0.1.0 | 73,295.11 148.22×24.8 | text | “Aktif durumu” | Inter 400 16/24.8 | text |  |  |
| r.2.1.1.0.2 | 234.22,295.89 109.78×23.25 | text | “Kabul ettiklerim” | Inter 400 15/23.25 | text·68% |  |  |
| r.2.1.1.0.3 | 357,300.02 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.2.1.1.1 | 73,331.52 313×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 57 |
| r.2.1.1.2 | 16,333.52 370×48 | stack |  |  |  |  | row ai:center gap 13 pad 0 14 0 14 minH 48 |
| r.2.1.1.2.0 | 30,342.52 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.1.1.2.0.0 | 36,348.52 18×18 | icon | ph:checks (regular) | 18pt | accent300 |  |  |
| r.2.1.1.2.1 | 73,345.11 235×24.8 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.1.1.2.1.0 | 73,345.11 235×24.8 | text | “Okundu bilgisi” | Inter 400 16/24.8 | text |  |  |
| r.2.1.1.2.2 | 321,342.02 51×31 | shape |  |  |  | fill accent · r16 | row jc:flex-end pad 2 2 2 2 |
| r.2.1.1.2.2.0 | 343,344.02 27×27 | shape |  |  |  | fill rgb(245, 244, 255) · r50% · shadow rgba(0, 0, 0, 0.25) 0px 2px 6px |  |
| r.2.2 | 16,399.52 370×171.27 | stack |  |  |  |  | col gap 7 |
| r.2.2.0 | 16,399.52 370×16.27 | text | “Güvenlik” | Mono 400 10.5/16.27 ls1.05 UPPER | text·68% |  | pad 0 16 0 16 |
| r.2.2.1 | 16,422.78 370×148 | shape |  |  |  | fill surface · r18 |  |
| r.2.2.1.0 | 16,422.78 370×48 | stack |  |  |  |  | row ai:center gap 13 pad 0 14 0 14 minH 48 |
| r.2.2.1.0.0 | 30,431.78 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.2.1.0.0.0 | 36,437.78 18×18 | svg | custom svg (face-id glyph) |  | currentcolor |  |  |
| r.2.2.1.0.1 | 73,434.38 235×24.8 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.2.1.0.1.0 | 73,434.38 235×24.8 | text | “Face ID ile aç” | Inter 400 16/24.8 | text |  |  |
| r.2.2.1.0.2 | 321,431.28 51×31 | shape |  |  |  | fill accent · r16 | row jc:flex-end pad 2 2 2 2 |
| r.2.2.1.0.2.0 | 343,433.28 27×27 | shape |  |  |  | fill rgb(245, 244, 255) · r50% · shadow rgba(0, 0, 0, 0.25) 0px 2px 6px |  |
| r.2.2.1.1 | 73,470.78 313×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 57 |
| r.2.2.1.2 | 16,472.78 370×48 | stack |  |  |  |  | row ai:center gap 13 pad 0 14 0 14 minH 48 |
| r.2.2.1.2.0 | 30,481.78 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.2.1.2.0.0 | 36,487.78 18×18 | icon | ph:lock-simple (regular) | 18pt | accent300 |  |  |
| r.2.2.1.2.1 | 73,484.38 207.36×24.8 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.2.1.2.1.0 | 73,484.38 207.36×24.8 | text | “Otomatik kilit” | Inter 400 16/24.8 | text |  |  |
| r.2.2.1.2.2 | 293.36,485.16 50.64×23.25 | text | “Hemen” | Inter 400 15/23.25 | text·68% |  |  |
| r.2.2.1.2.3 | 357,489.28 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.2.2.1.3 | 73,520.78 313×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 57 |
| r.2.2.1.4 | 16,522.78 370×48 | stack |  |  |  |  | row ai:center gap 13 pad 0 14 0 14 minH 48 |
| r.2.2.1.4.0 | 30,531.78 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.2.1.4.0.0 | 36,537.78 18×18 | icon | ph:password (regular) | 18pt | accent300 |  |  |
| r.2.2.1.4.1 | 73,534.38 271×24.8 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.2.1.4.1.0 | 73,534.38 271×24.8 | text | “Gizli kodu değiştir” | Inter 400 16/24.8 | text |  |  |
| r.2.2.1.4.2 | 357,539.28 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.2.3 | 16,588.78 370×121.27 | stack |  |  |  |  | col gap 7 |
| r.2.3.0 | 16,588.78 370×16.27 | text | “Sohbetler” | Mono 400 10.5/16.27 ls1.05 UPPER | text·68% |  | pad 0 16 0 16 |
| r.2.3.1 | 16,612.05 370×98 | shape |  |  |  | fill surface · r18 |  |
| r.2.3.1.0 | 16,612.05 370×48 | stack |  |  |  |  | row ai:center gap 13 pad 0 14 0 14 minH 48 |
| r.2.3.1.0.0 | 30,621.05 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.3.1.0.0.0 | 36,627.05 18×18 | icon | ph:ghost (regular) | 18pt | accent300 |  |  |
| r.2.3.1.0.1 | 73,623.64 205.5×24.8 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.3.1.0.1.0 | 73,623.64 205.5×24.8 | text | “Varsayılan Ghost süresi” | Inter 400 16/24.8 | text |  |  |
| r.2.3.1.0.2 | 291.5,624.42 52.5×23.25 | text | “24 saat” | Inter 400 15/23.25 | text·68% |  |  |
| r.2.3.1.0.3 | 357,628.55 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.2.3.1.1 | 73,660.05 313×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 57 |
| r.2.3.1.2 | 16,662.05 370×48 | stack |  |  |  |  | row ai:center gap 13 pad 0 14 0 14 minH 48 |
| r.2.3.1.2.0 | 30,671.05 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.3.1.2.0.0 | 36,677.05 18×18 | icon | ph:timer (regular) | 18pt | accent300 |  |  |
| r.2.3.1.2.1 | 73,673.64 214.47×24.8 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.3.1.2.1.0 | 73,673.64 214.47×24.8 | text | “Varsayılan süreli mesaj” | Inter 400 16/24.8 | text |  |  |
| r.2.3.1.2.2 | 300.47,674.42 43.53×23.25 | text | “Kapalı” | Inter 400 15/23.25 | text·68% |  |  |
| r.2.3.1.2.3 | 357,678.55 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.2.4 | 16,728.05 370×171.27 | stack |  |  |  |  | col gap 7 |
| r.2.4.0 | 16,728.05 370×16.27 | text | “Veri ve görünüm” | Mono 400 10.5/16.27 ls1.05 UPPER | text·68% |  | pad 0 16 0 16 |
| r.2.4.1 | 16,751.31 370×148 | shape |  |  |  | fill surface · r18 |  |
| r.2.4.1.0 | 16,751.31 370×48 | stack |  |  |  |  | row ai:center gap 13 pad 0 14 0 14 minH 48 |
| r.2.4.1.0.0 | 30,760.31 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.4.1.0.0.0 | 36,766.31 18×18 | icon | ph:file-lock (regular) | 18pt | accent300 |  |  |
| r.2.4.1.0.1 | 73,762.91 187.59×24.8 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.4.1.0.1.0 | 73,762.91 187.59×24.8 | text | “Yedek” | Inter 400 16/24.8 | text |  |  |
| r.2.4.1.0.2 | 273.59,763.69 70.41×23.25 | text | “Son: 8 Eki” | Inter 400 15/23.25 | text·68% |  |  |
| r.2.4.1.0.3 | 357,767.81 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.2.4.1.1 | 73,799.31 313×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 57 |
| r.2.4.1.2 | 16,801.31 370×48 | stack |  |  |  |  | row ai:center gap 13 pad 0 14 0 14 minH 48 |
| r.2.4.1.2.0 | 30,810.31 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.4.1.2.0.0 | 36,816.31 18×18 | icon | ph:moon (regular) | 18pt | accent300 |  |  |
| r.2.4.1.2.1 | 73,812.91 210.17×24.8 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.4.1.2.1.0 | 73,812.91 210.17×24.8 | text | “Tema” | Inter 400 16/24.8 | text |  |  |
| r.2.4.1.2.2 | 296.17,813.69 47.83×23.25 | text | “Sistem” | Inter 400 15/23.25 | text·68% |  |  |
| r.2.4.1.2.3 | 357,817.81 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.2.4.1.3 | 73,849.31 313×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 57 |
| r.2.4.1.4 | 16,851.31 370×48 | stack |  |  |  |  | row ai:center gap 13 pad 0 14 0 14 minH 48 |
| r.2.4.1.4.0 | 30,860.31 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.4.1.4.0.0 | 36,866.31 18×18 | icon | ph:translate (regular) | 18pt | accent300 |  |  |
| r.2.4.1.4.1 | 73,862.91 209.11×24.8 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.4.1.4.1.0 | 73,862.91 209.11×24.8 | text | “Dil” | Inter 400 16/24.8 | text |  |  |
| r.2.4.1.4.2 | 295.11,863.69 48.89×23.25 | text | “Türkçe” | Inter 400 15/23.25 | text·68% |  |  |
| r.2.4.1.4.3 | 357,867.81 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |

</details>

Colour legend 7.1:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `surface` | #232532 | #e4e7f5 |
| `accent300` | #d2cefd | #5d5294 |
| `accent900` | #2b2741 | #f5f4ff |
| `accent700` | #5d5294 | #d2cefd |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent800` | #423a6a | #e7e5fe |
| `text·40%` | #e9e9ed@40% | #292b31@40% |
| `text·22%` | #e9e9ed@22% | #292b31@22% |
| `accent` | #9184d9 | #796cbf |

## 7.1 › Engellenenler — Blocked (proposed)

No design state: built in 7.1's language. Harness: `-U2Route settings:blocked` (bora blocked, the chat still in 4.1; lale blocked, the chat erased), `settings:blocked-empty`, `settings:7.1-blocked` (7.1 with “Engellenenler · 2”).

**Purpose.** Everyone the user blocked — also those whose chat is gone (Ghost erased it, or it was deleted), who could otherwise only be found again through 4.2 › “Mesaj yaz” — and the way to unblock them.

**Layout.**
- C7 header: back C4 `ph:caret-left` 21 · “Engellenenler” `navTitle` · empty slot. The page scrolls under 7.1's status-bar fade.
- Content (pad 18 16 0): one C13 card `surface` r18, a row per person, by username (people whose username is unknown last). Row: pad 9 14, ≥ 62 high, gap 13 — avatar 44 (C12 initial in the person's palette; never a picture for someone blocked) · the username `monoUsername` `text` (one line, …), with “Kullanıcı adı bilinmiyor” `captionCompact` `text·68%` under it (gap 2) when the phone keeps none (a Ghost wipe with `wipe_contact` kept only the block; the name is then what 4.1 would show) · “Engeli kaldır” `rowTitle` `accent300`, 44 high. At accessibility text sizes “Engeli kaldır” goes under the name, and the name and “Kullanıcı adı bilinmiyor” may take two lines. Dotted separators inset 71 (under the name).
- 7 below the card: the footnote, `footnote` `text·68%`, pad 0 16.
- Nobody blocked: “Engellediğin kimse yok.” `callout` `text·68%`, centred, 120 below the content's top (4.3's empty state); no card, no footnote.

**Texts.**

| TR | EN |
|---|---|
| Engellenenler | Blocked |
| Engeli kaldır | Unblock |
| Kullanıcı adı bilinmiyor | Username unknown |
| Yazdıkları sana ulaşmaz, sen de onlara yazamazsın; engellendiklerini bilmezler. Sohbet silinse de engel sürer. Engeli kaldırınca her şey eskisi gibi olur, ama bu arada yazdıkları gelmez. | Their messages don't reach you and you can't write to them; they don't know they're blocked. The block stays even when the chat is erased. Unblocking puts things back as they were, but what they sent meanwhile won't arrive. |
| Engellediğin kimse yok. | You haven't blocked anyone. |
| *(VoiceOver, after an unblock)* {user} artık engelli değil. | {user} is no longer blocked. |

**States.** The list (people with and without a chat) · a row leaving · empty. On 7.1 the row's detail is the number (`label15` `text·68%`), nothing when it is 0.

**Transitions.** 7.1 row → push (`screenPush`). “Engeli kaldır” needs no confirmation (as on the contact screen): selection haptic, the row fades and collapses (0.36 s, `u2Collapse`; Reduce Motion: a fade) and the rows below glide up; the last one cross-fades into the empty state. If the unblock cannot be stored the row comes back. Back → 7.1, whose count follows.

**Data.** `MessengerService.blockedPeople` (`BlockedPerson`: the chat id = the account, the name, whether the username is known, the palette, the initial) — live from `AppRuntime.blockedContacts` (every blocked contact, with or without a conversation; empty while locked). `unblock(_:)` is `unblock(chat:)` for the person's account, chat or not: a contact is accepted again (it gets the current access key and the profile), a blocked request is a request again (back in 4.3 while its chat is there); someone without a chat stays without one until they write, and someone with nothing to go back to (no chat, and no contact before the block: a Ghost wipe with `wipe_contact` kept only the block) is forgotten — a stranger again, whom 4.2 › “Mesaj yaz” starts a new chat with. When someone was blocked is not recorded, so it is not shown.

## 7.2 · Yedeği dışa aktar — Export backup

Reference `reference/7.2-yedegi-disa-aktar.png` · light `reference/light/7.2-yedegi-disa-aktar.png` · data `layout/7.2-yedegi-disa-aktar.json`

> **Note (TR):** Yedek, ayrı bir yedek parolasıyla şifrelenmiş tek bir dosyadır; nereye kaydedileceğine kullanıcı karar verir (Dosyalar, AirDrop). Tek görüntülemelik medya ve süreli mesajlar yedeğe girmez.
> **EN:** The backup is a single file encrypted with a separate backup password; the user decides where to save it (Files, AirDrop). View-once media and disappearing messages never go into the backup.

**Purpose.** Create an encrypted backup file with a separate backup password (owner decision).

**Layout.**
- C7 header: back, “Yedek”, empty right.
- Content (pad 18 16 0, gap 18):
  - File card 16,118 370×84, `surface` r20, pad 14 16, gap 14: file tile 46×56 r10 `accent900` + ring `accent800`, `ph:file-lock` 26 `accent300`; name `monoFileName` Mono 500 12.5 (single line, …) “us2us-yedek-2026-10-08.u2u”; estimate `caption` `text·68%`.
  - “YEDEĞE GİRECEKLER” section (C13): Mesajlar (`ph:chat-circle-dots`) “12 sohbet · 3.920 mesaj” toggle · Fotoğraf ve videolar (`ph:image`) “162 MB” toggle · Ayarlar ve kişiler (`ph:gear-six`) toggle; footnote `footnote` Inter 400 12.5/17.5 `text·68%` (pad 0 16).
  - “YEDEK PAROLASI” (gap 8): password field 56 high r16 `surface`, focus ring `inset 0 0 0 1.5px accent` (no glow), pad 0 16: masked dots 9×9 `text` gap 7, `ph:eye` 20 `text·68%` (reveal); strength row (pad 0 6, gap 10): dotted meter (r 2, pitch 8, 6 high; track `text·25%`, lit `online` 82 %) + label “Güçlü” `meta` `text`.
  - Warning row (pad 0 6, gap 12): `ph:warning` 18 `accent300` + `captionTight` `text·68%`.
- Spacer; C1 “Dışa aktar” with `ph:export` 20.

**Texts.**

| TR | EN |
|---|---|
| Yedek | Backup |
| us2us-yedek-2026-10-08.u2u | us2us-backup-2026-10-08.u2u |
| Tahmini 184 MB · 3.920 mesaj | About 184 MB · 3,920 messages |
| Yedeğe girecekler | What goes into the backup |
| Mesajlar · 12 sohbet · 3.920 mesaj | Messages · 12 chats · 3,920 messages |
| Fotoğraf ve videolar · 162 MB | Photos and videos · 162 MB |
| Ayarlar ve kişiler | Settings and contacts |
| Tek görüntülemelik medya ve süreli mesajlar hiçbir zaman yedeğe girmez. | View-once media and disappearing messages never go into a backup. |
| Yedek parolası | Backup password |
| Güçlü | Strong |
| *(proposed)* Zayıf · Orta · Güçlü · Çok güçlü | Weak · Fair · Strong · Very strong |
| Bu parola olmadan yedek açılamaz; biz dahil kimse kurtaramaz. Gizli kodundan farklı olmalı. | Without this password the backup can't be opened; no one, including us, can recover it. It must be different from your secret code. |
| Dışa aktar | Export |
| *(proposed)* Parolayı tekrar gir · Parolalar eşleşmedi | Re-enter password · Passwords don't match |

**States.** password empty → button disabled; strength meter lights proportionally (weak: lit dots `danger`, fair: `accent400`, strong: `online` — proposed mapping); confirm-password step (proposed: second field appears under the first after the first is valid); exporting (button shows progress, proposed) → share sheet / document picker (Files, AirDrop) → done (Settings “Son: …” updates). Reject a password equal to the secret code (owner decision).

**Transitions.** Back → 7.1. Export → system share/export sheet.

**Animation.** Meter dots light up left→right 0.36 s as strength changes; toggle `springSoft`.

**Data.** Backup estimate (chat count, message count, media bytes), toggles, password strength (local estimate), export pipeline. *(Implemented: the account backup holds the account, identity key and contacts only, so the toggles are replaced by a fixed list of what it holds and a footnote that messages and media are never in it; the strength estimate counts names, words, years, dates, runs, keyboard walks and repeats as the few guesses they are; “Güçlü parola oluştur” fills in a random 20-symbol password, shown with “Kopyala”.)*

**Elements.**

<details><summary>Element table 7.2 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.0.0 | 16,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 27.5,67.5 21×21 | icon | ph:caret-left (regular) | 21pt | text |  |  |
| r.0.1 | 76,56 250×44 | text | “Yedek” | Inter 600 17/26.35 | text |  | grid place:center abs |
| r.0.2 | 342,56 44×44 | box |  |  |  |  |  |
| r.1 | 0,100 402×543.28 | stack |  |  |  |  | col gap 18 pad 18 16 0 16 |
| r.1.0 | 16,118 370×84 | shape |  |  |  | fill surface · r20 | row ai:center gap 14 pad 14 16 14 16 |
| r.1.0.0 | 32,132 46×56 | shape |  |  |  | fill accent900 · r10 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.1.0.0.0 | 42,147 26×26 | icon | ph:file-lock (regular) | 26pt | accent300 |  |  |
| r.1.0.1 | 92,138.23 278×43.52 | stack |  |  |  |  | col gap 4 flex 1 |
| r.1.0.1.0 | 92,138.23 278×19.38 | text | “us2us-yedek-2026-10-08.u2u” | Mono 500 12.5/19.38 … | text |  |  |
| r.1.0.1.1 | 92,161.61 278×20.14 | text | “Tahmini 184 MB · 3.920 mesaj” | Inter 400 13/20.15 | text·68% |  |  |
| r.1.1 | 16,220 370×241.95 | stack |  |  |  |  | col gap 7 |
| r.1.1.0 | 16,220 370×16.27 | text | “Yedeğe girecekler” | Mono 400 10.5/16.27 ls1.05 UPPER | text·68% |  | pad 0 16 0 16 |
| r.1.1.1 | 16,243.27 370×176.69 | shape |  |  |  | fill surface · r18 |  |
| r.1.1.1.0 | 16,243.27 370×62.34 | stack |  |  |  |  | row ai:center gap 13 pad 9 14 9 14 minH 48 |
| r.1.1.1.0.0 | 30,259.44 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.1.1.1.0.0.0 | 36,265.44 18×18 | icon | ph:chat-circle-dots (regular) | 18pt | accent300 |  |  |
| r.1.1.1.0.1 | 73,252.27 235×44.34 | stack |  |  |  |  | col gap 2 flex 1 |
| r.1.1.1.0.1.0 | 73,252.27 235×24.8 | text | “Mesajlar” | Inter 400 16/24.8 | text |  |  |
| r.1.1.1.0.1.1 | 73,279.06 235×17.55 | text | “12 sohbet · 3.920 mesaj” | Inter 400 13/17.55 | text·68% |  |  |
| r.1.1.1.0.2 | 321,258.94 51×31 | shape |  |  |  | fill accent · r16 | row jc:flex-end pad 2 2 2 2 |
| r.1.1.1.0.2.0 | 343,260.94 27×27 | shape |  |  |  | fill rgb(245, 244, 255) · r50% · shadow rgba(0, 0, 0, 0.25) 0px 2px 6px |  |
| r.1.1.1.1 | 73,305.61 313×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 57 |
| r.1.1.1.2 | 16,307.61 370×62.34 | stack |  |  |  |  | row ai:center gap 13 pad 9 14 9 14 minH 48 |
| r.1.1.1.2.0 | 30,323.78 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.1.1.1.2.0.0 | 36,329.78 18×18 | icon | ph:image (regular) | 18pt | accent300 |  |  |
| r.1.1.1.2.1 | 73,316.61 235×44.34 | stack |  |  |  |  | col gap 2 flex 1 |
| r.1.1.1.2.1.0 | 73,316.61 235×24.8 | text | “Fotoğraf ve videolar” | Inter 400 16/24.8 | text |  |  |
| r.1.1.1.2.1.1 | 73,343.41 235×17.55 | text | “162 MB” | Inter 400 13/17.55 | text·68% |  |  |
| r.1.1.1.2.2 | 321,323.28 51×31 | shape |  |  |  | fill accent · r16 | row jc:flex-end pad 2 2 2 2 |
| r.1.1.1.2.2.0 | 343,325.28 27×27 | shape |  |  |  | fill rgb(245, 244, 255) · r50% · shadow rgba(0, 0, 0, 0.25) 0px 2px 6px |  |
| r.1.1.1.3 | 73,369.95 313×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 57 |
| r.1.1.1.4 | 16,371.95 370×48 | stack |  |  |  |  | row ai:center gap 13 pad 0 14 0 14 minH 48 |
| r.1.1.1.4.0 | 30,380.95 30×30 | shape |  |  |  | fill accent900 · r8 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.1.1.1.4.0.0 | 36,386.95 18×18 | icon | ph:gear-six (regular) | 18pt | accent300 |  |  |
| r.1.1.1.4.1 | 73,383.55 235×24.8 | stack |  |  |  |  | col gap 2 flex 1 |
| r.1.1.1.4.1.0 | 73,383.55 235×24.8 | text | “Ayarlar ve kişiler” | Inter 400 16/24.8 | text |  |  |
| r.1.1.1.4.2 | 321,380.45 51×31 | shape |  |  |  | fill accent · r16 | row jc:flex-end pad 2 2 2 2 |
| r.1.1.1.4.2.0 | 343,382.45 27×27 | shape |  |  |  | fill rgb(245, 244, 255) · r50% · shadow rgba(0, 0, 0, 0.25) 0px 2px 6px |  |
| r.1.1.2 | 16,426.95 370×35 | text | “Tek görüntülemelik medya ve süreli mesajlar hiçbir zaman yedeğe gir…” | Inter 400 12.5/17.5 | text·68% |  | pad 0 16 0 16 |
| r.1.2 | 16,479.95 370×107.64 | stack |  |  |  |  | col gap 8 |
| r.1.2.0 | 16,479.95 370×16.27 | text | “Yedek parolası” | Mono 400 10.5/16.27 ls1.05 UPPER | text·68% |  | pad 0 16 0 16 |
| r.1.2.1 | 16,504.22 370×56 | shape |  |  |  | fill surface · r16 · shadow inset 0 0 0 1.5px accent | row ai:center gap 10 pad 0 16 0 16 |
| r.1.2.1.0 | 32,527.72 308×9 | stack |  |  |  |  | row gap 7 flex 1 |
| r.1.2.1.0.0 | 32,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.1 | 48,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.2 | 64,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.3 | 80,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.4 | 96,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.5 | 112,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.6 | 128,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.7 | 144,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.8 | 160,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.9 | 176,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.10 | 192,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.11 | 208,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.12 | 224,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.0.13 | 240,527.72 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.2.1.1 | 350,522.22 20×20 | icon | ph:eye (regular) | 20pt | text·68% |  |  |
| r.1.2.2 | 16,568.22 370×19.38 | stack |  |  |  |  | row ai:center gap 10 pad 0 6 0 6 |
| r.1.2.2.0 | 22,574.91 313.72×6 | shape |  |  |  | fill radial-gradient(circle, text·25% 2px, transparent 2.5px) 0 50% / 8px 6px repeat-x | flex 1 |
| r.1.2.2.0.0 | 22,574.91 257.23×6 | shape |  |  |  | fill radial-gradient(circle, online 2px, transparent 2.5px) 0 50% / 8px 6px repeat-x | abs |
| r.1.2.2.1 | 345.72,568.22 34.28×19.38 | text | “Güçlü” | Inter 400 12.5/19.38 | text |  |  |
| r.1.3 | 16,605.59 370×37.69 | stack |  |  |  |  | row gap 12 pad 0 6 0 6 |
| r.1.3.0 | 22,605.59 18×37.69 | icon | ph:warning (regular) | 18pt | accent300 |  |  |
| r.1.3.1 | 52,605.59 328×37.69 | text | “Bu parola olmadan yedek açılamaz; biz dahil kimse kurtaramaz. Gizli…” | Inter 400 13/18.85 | text·68% |  |  |
| r.2 | 0,643.28 402×132.72 | spacer |  |  |  |  | flex 1 |
| r.3 | 0,776 402×98 | box |  |  |  |  | pad 0 20 44 20 |
| r.3.0 | 20,776 362×54 | text | “Dışa aktar” | Inter 500 17/26.35 | accent300 | r16 · border 1px solid accent · shadow 0 0 32px -8px accent·70% | row ai:center jc:center gap 8 |
| r.3.0.0 | 146.42,793 20×20 | icon | ph:export (regular) | 20pt | accent300 |  |  |

</details>

Colour legend 7.2:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `surface` | #232532 | #e4e7f5 |
| `accent300` | #d2cefd | #5d5294 |
| `accent900` | #2b2741 | #f5f4ff |
| `accent800` | #423a6a | #e7e5fe |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent` | #9184d9 | #796cbf |
| `text·22%` | #e9e9ed@22% | #292b31@22% |
| `text·25%` | #e9e9ed@25% | #292b31@25% |
| `online` | #52cd86 | #009351 |
| `accent·70%` | #9184d9@70% | #796cbf@70% |

## 7.3 · Yedekten geri yükle — Restore from backup

Reference `reference/7.3-yedekten-geri-yukle.png` · light `reference/light/7.3-yedekten-geri-yukle.png` · data `layout/7.3-yedekten-geri-yukle.json`

> **Note (TR):** Yeni cihazda karşılamadan (1.1) ya da "Kodu unuttum" ekranından (3.3) açılır. Çözme tamamen cihazda yapılır; uygulama kapanırsa işlem kaldığı yerden sürer.
> **EN:** Opens from the welcome screen (1.1) on a new device, or from the “I forgot my code” screen (3.3). Decryption happens entirely on the device; if the app closes, the process resumes where it left off.

**Purpose.** Import an encrypted backup (shown mid-restore, 62 %).

**Layout.**
- C7 header with close `ph:x` (left), “Geri yükle”, empty right.
- Top (pad 18 16 0, gap 12): file card (as 7.2, meta “184 MB · 8 Ekim 2026, 21:14”, trailing `ph:check-circle` fill 22 `online`); password field 56 r16 `surface` (no ring once accepted) with masked dots and `ph:check-circle` fill 20 `online`; caption “Parola doğru · yedek açıldı” `caption` `text·68%`.
- Progress block (flex, centred vertically, pad 0 24, gap 18): row (baseline, space-between): “62” `displayPercent` Doto 900 76 + “%” Doto 900 42; “~1 dk kaldı” `monoMeta` `text·68%`. Dotted progress meter 354×8 (track `text·22%`, lit `accent400` 62 %). Checklist (pad-top 4, gap 12), `monoCountdownChip` Mono 400 12 with 15 pt icons, gap 10: done `ph:check` `online` + text `text·68%`; current `ph:circle-notch` `accent300` (spinning) + text `text`; pending `ph:circle` + text in `text·45%`.
- Bottom (pad 0 20 44, gap 12): note `captionTight` centred `text·68%`; C2 “İptal”.

**Texts.**

| TR | EN |
|---|---|
| Geri yükle | Restore |
| 184 MB · 8 Ekim 2026, 21:14 | 184 MB · 8 October 2026, 21:14 |
| Parola doğru · yedek açıldı | Password correct · backup opened |
| ~1 dk kaldı | ~1 min left |
| Anahtarlar doğrulandı | Keys verified |
| Mesajlar çözülüyor · 2.431 / 3.920 | Decrypting messages · 2,431 / 3,920 |
| Medya · 162 MB | Media · 162 MB |
| Uygulamayı açık tut. Ghost süresi dolmuş sohbetler geri gelmez. | Keep the app open. Chats whose Ghost time has run out won't come back. |
| İptal | Cancel |
| *(proposed)* Yedek dosyası seç · Yedek parolası · Parola yanlış · Geri yükle · Tamamlandı · Devam | Choose backup file · Backup password · Wrong password · Restore · Done · Continue |

**States.** pick file (system document picker) → password entry (field focused with the 7.2 ring, CTA “Geri yükle”) → wrong password (ring `danger`, caption “Parola yanlış” `danger`) → restoring (as drawn: steps keys → messages → media, percent + ETA) → done (100 %, all steps checked, CTA “Devam”) → set a new secret code (2.2 ×2, 2.3) on this device → 4.1. Cancel → confirmation (proposed) → back. Resumable after app relaunch.

**Animation.** Percent number updates with tabular digits (no layout shift); meter dots light left→right as progress advances (each dot fades in 0.24 s); spinner `ph:circle-notch` rotates 1 turn / 1.2 s linear (Reduce Motion: static). Step completion: icon cross-fade 0.24 s.

**Data.** Backup file metadata (name, size, createdAt), password check, restore progress (stage, done/total messages, media bytes, ETA), resumable job state.

**Elements.**

<details><summary>Element table 7.3 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.0.0 | 16,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 27.5,67.5 21×21 | icon | ph:x (regular) | 21pt | text |  |  |
| r.0.1 | 76,56 250×44 | text | “Geri yükle” | Inter 600 17/26.35 | text |  | grid place:center abs |
| r.0.2 | 342,56 44×44 | box |  |  |  |  |  |
| r.1 | 0,100 402×202.14 | stack |  |  |  |  | col gap 12 pad 18 16 0 16 |
| r.1.0 | 16,118 370×84 | shape |  |  |  | fill surface · r20 | row ai:center gap 14 pad 14 16 14 16 |
| r.1.0.0 | 32,132 46×56 | shape |  |  |  | fill accent900 · r10 · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.1.0.0.0 | 42,147 26×26 | icon | ph:file-lock (regular) | 26pt | accent300 |  |  |
| r.1.0.1 | 92,138.23 242×43.52 | stack |  |  |  |  | col gap 4 flex 1 |
| r.1.0.1.0 | 92,138.23 242×19.38 | text | “us2us-yedek-2026-10-08.u2u” | Mono 500 12.5/19.38 … | text |  |  |
| r.1.0.1.1 | 92,161.61 242×20.14 | text | “184 MB · 8 Ekim 2026, 21:14” | Inter 400 13/20.15 | text·68% |  |  |
| r.1.0.2 | 348,149 22×22 | icon | ph:check-circle (fill) | 22pt | online |  |  |
| r.1.1 | 16,214 370×56 | shape |  |  |  | fill surface · r16 | row ai:center gap 10 pad 0 16 0 16 |
| r.1.1.0 | 32,237.5 308×9 | stack |  |  |  |  | row gap 7 flex 1 |
| r.1.1.0.0 | 32,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.1 | 48,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.2 | 64,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.3 | 80,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.4 | 96,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.5 | 112,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.6 | 128,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.7 | 144,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.8 | 160,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.9 | 176,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.10 | 192,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.11 | 208,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.12 | 224,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.0.13 | 240,237.5 9×9 | shape |  |  |  | fill text · r50% |  |
| r.1.1.1 | 350,232 20×20 | icon | ph:check-circle (fill) | 20pt | online |  |  |
| r.1.2 | 16,282 370×20.14 | text | “Parola doğru · yedek açıldı” | Inter 400 13/20.15 | text·68% |  | pad 0 6 0 6 |
| r.2 | 0,302.14 402×424.17 | stack |  |  |  |  | col jc:center gap 18 pad 0 24 0 24 flex 1 |
| r.2.0 | 24,412.33 354×76 | stack |  |  |  |  | row ai:baseline jc:space-between |
| r.2.0.0 | 24,412.33 116.41×76 | text | “62%” | Doto 900 76/76 ROND100 | text |  |  |
| r.2.0.0.0 | 115.2,436.33 25.2×51 | text | “%” | Doto 900 42/42 ROND100 | text |  |  |
| r.2.0.1 | 293.3,463.33 84.7×17.05 | text | “~1 dk kaldı” | Mono 400 11/17.05 | text·68% |  |  |
| r.2.1 | 24,506.33 354×8 | shape |  |  |  | fill radial-gradient(circle, text·22% 2.5px, transparent 3px) 0 50% / 12px 8px repeat-x |  |
| r.2.1.0 | 24,506.33 219.47×8 | shape |  |  |  | fill radial-gradient(circle, accent400 2.5px, transparent 3px) 0 50% / 12px 8px repeat-x | abs |
| r.2.2 | 24,532.33 354×83.78 | stack |  |  |  |  | col gap 12 pad 4 0 0 0 |
| r.2.2.0 | 24,536.33 354×18.59 | text | “Anahtarlar doğrulandı” | Mono 400 12/18.6 | text·68% |  | row ai:center gap 10 |
| r.2.2.0.0 | 24,538.13 15×15 | icon | ph:check (regular) | 15pt | online |  |  |
| r.2.2.1 | 24,566.92 354×18.59 | text | “Mesajlar çözülüyor · 2.431 / 3.920” | Mono 400 12/18.6 | text |  | row ai:center gap 10 |
| r.2.2.1.0 | 24,568.72 15×15 | icon | ph:circle-notch (regular) | 15pt | accent300 |  |  |
| r.2.2.2 | 24,597.52 354×18.59 | text | “Medya · 162 MB” | Mono 400 12/18.6 | text·45% |  | row ai:center gap 10 |
| r.2.2.2.0 | 24,599.31 15×15 | icon | ph:circle (regular) | 15pt | text·45% |  |  |
| r.3 | 0,726.31 402×147.69 | stack |  |  |  |  | col gap 12 pad 0 20 44 20 |
| r.3.0 | 20,726.31 362×37.69 | text | “Uygulamayı açık tut. Ghost süresi dolmuş sohbetler geri gelmez.” | Inter 400 13/18.85 center | text·68% |  |  |
| r.3.1 | 20,776 362×54 | text | “İptal” | Inter 500 17/26.35 | text | r16 · shadow inset 0 0 0 1px divider | row ai:center jc:center gap 8 |

</details>

Colour legend 7.3:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `surface` | #232532 | #e4e7f5 |
| `accent300` | #d2cefd | #5d5294 |
| `accent900` | #2b2741 | #f5f4ff |
| `accent800` | #423a6a | #e7e5fe |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `online` | #52cd86 | #009351 |
| `text·22%` | #e9e9ed@22% | #292b31@22% |
| `accent400` | #b5abfc | #796cbf |
| `text·45%` | #e9e9ed@45% | #292b31@45% |
| `divider` | #e9e9ed@16% | #292b31@16% |

# 08 · Açık tema — Light theme (“Aynı hat, gündüz” / “The same line, by day”)

> **Section note (TR):** Bütün ekranlar aynı renk rollerini kullanır; açık temada ton basamakları ters çevrilir. Tweaks › Tema ile sayfanın tamamı açık temaya geçer.
> **EN:** Every screen uses the same colour roles; in the light theme the tonal steps are inverted. Tweaks › Theme switches the whole page to the light theme.

The light theme is **the same layouts with the light token values** (`tokens.json › color.base.*.light`). Notable remaps: `bg #f3f5fe`, `surface #e4e7f5`, `text #292b31`, `accent #796cbf`; the accent ramp is reversed (`accent800 #e7e5fe` becomes the outgoing bubble, `accent100 #2b2741` its text); `neutral800 #dfe2f0`; `online oklch(.58 .15 155) = #009351`; `danger oklch(.55 .18 25) = #c53637`; shadows become ink-tinted (`shadowMd` = `0 0 0 1px #b2b6ca, 0 6px 18px #292b31@10%`). Fixed colours (`#f5f4ff` knob, QR paper, camera/media screens) do not change. Every other screen in light is in `reference/light/`.

## 8.1 · Sohbet listesi (açık) — Chat list, light

Reference `reference/8.1-sohbet-listesi-acik.png` · urgent `reference/urgent/8.1-sohbet-listesi-acik-urgent.png` · data `layout/8.1-sohbet-listesi-acik.json`

> **Note (TR):** Yüzeyler ve vurgular aynı renk rollerinden gelir; açık temada ramp adımları yer değiştirir.
> **EN:** Surfaces and accents come from the same colour roles; in the light theme the ramp steps swap places.

Identical structure to 4.1 (same element ids and frames); colours from the light column of the legend.

<details><summary>Element table 8.1 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.0.0 | 16,58 40×40 | text | “S” | Mono 500 14/14 | accent300 | fill accent900 · r50% · shadow inset 0 0 0 1px accent700 | grid place:center |
| r.0.1 | 288,56 98×44 | stack |  |  |  |  | row gap 10 |
| r.0.1.0 | 288,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.1.0.0 | 299.5,67.5 21×21 | icon | ph:qr-code (regular) | 21pt | text |  |  |
| r.0.1.1 | 342,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.1.1.0 | 353.5,67.5 21×21 | icon | ph:note-pencil (regular) | 21pt | accent300 |  |  |
| r.1 | 0,100 402×81.05 | stack |  |  |  |  | col gap 12 pad 14 20 0 20 |
| r.1.0 | 20,114 362×38 | text | “Sohbetler” | Doto 900 38/38 ROND100 | text |  |  |
| r.1.1 | 20,164 362×17.05 | text | “Hat açık · mesajlar geliyor” | Mono 400 11/17.05 | text·68% |  | row ai:center gap 10 |
| r.1.1.0 | 20,168.02 60×9 | stack |  |  |  |  | row ai:center |
| r.1.1.0.0 | 20,168.02 9×9 | shape |  |  |  | r50% · border 2px solid accent400 |  |
| r.1.1.0.1 | 29,171.52 42×2 | shape |  |  |  | fill linear-gradient(90deg, accent400, accent·30% 50%, accent400) | flex 1 |
| r.1.1.0.1.0 | 32.19,170.52 44×4 | shape |  |  |  | fill linear-gradient(90deg, transparent, accent200, transparent) · r4 · shadow 0 0 12px 2px accent·50% · anim u2-packet-r 6s cubic-bezier(0.45, 0, 0.55, 1) delay 0s ×infinite | mar -2 0 0 -22 abs |
| r.1.1.0.1.1 | 7,170.52 44×4 | shape |  |  |  | fill linear-gradient(90deg, transparent, accent200, transparent) · r4 · shadow 0 0 12px 2px accent·50% · opacity 0 · anim u2-packet-l 6s cubic-bezier(0.45, 0, 0.55, 1) delay 3s ×infinite | mar -2 0 0 -22 abs |
| r.1.1.0.2 | 71,168.02 9×9 | shape |  |  |  | r50% · border 2px solid accent400 |  |
| r.2 | 16,199.05 370×72.94 | shape |  |  |  | fill surface · r18 | row ai:center gap 13 pad 13 14 13 14 mar 18 16 4 16 |
| r.2.0 | 30,214.52 42×42 | shape |  |  |  | fill accent900 · r50% · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.0.0 | 40.5,225.02 21×21 | icon | ph:tray (regular) | 21pt | accent300 |  |  |
| r.2.1 | 85,212.05 224×46.94 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.1.0 | 85,212.05 224×24.8 | text | “Mesaj istekleri” | Inter 500 16/24.8 | text |  |  |
| r.2.1.1 | 85,238.84 224×20.14 | text | “2 kişi seninle yazışmak istiyor” | Inter 400 13/20.15 | text·68% |  |  |
| r.2.2 | 322,224.52 22×22 | text | “2” | Inter 600 12.5/19.38 | bg | fill accent · r11 | grid place:center pad 0 7 0 7 |
| r.2.3 | 357,228.02 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.3 | 0,275.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.3.0 | 16,287.98 52×52 | text | “D” | Mono 500 19/19 | accent100 | fill accent800 · r50% | grid place:center |
| r.3.1 | 81,289.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.3.1.0 | 81,289.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.3.1.0.0 | 81,289.13 52.5×23.25 | text | “deniz” | Mono 500 15/23.25 … | text |  |  |
| r.3.1.0.1 | 139.5,293.75 14×14 | icon | ph:seal-check (fill) | 14pt | accent |  |  |
| r.3.1.0.2 | 159.5,300.75 181.28×0 | spacer |  |  |  |  | flex 1 |
| r.3.1.0.3 | 346.78,290.67 37.22×20.14 | text | “18:10” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.3.1.1 | 81,316.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.3.1.1.0 | 81,320.11 15×15 | icon | ph:check (regular) | 15pt | text·68% |  |  |
| r.3.1.1.1 | 102,320.11 15×15 | icon | ph:timer (regular) | 15pt | text·68% |  |  |
| r.3.1.1.2 | 123,316.38 186.25×22.47 | text | “Süreli mesaj” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.3.1.1.3 | 315.25,316.61 68.75×22 | shape |  |  |  | fill accent900 · r11 | row ai:center gap 4 pad 0 8 0 8 |
| r.3.1.1.3.0 | 323.25,321.61 12×12 | icon | ph:ghost (regular) | 12pt | accent300 |  |  |
| r.3.1.1.3.1 | 339.25,319.47 36.75×16.27 | text | “23 sa” | Mono 400 10.5/16.27 | accent300 |  |  |
| r.4 | 81,351.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.5 | 0,353.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.5.0 | 16,365.98 52×52 | text | “M” | Mono 500 19/19 | accent100 | fill accent700 · r50% | grid place:center |
| r.5.0.0 | 53,402.98 15×15 | shape |  |  |  | fill online · r50% · shadow 0 0 0 3px bg | abs |
| r.5.1 | 81,367.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.5.1.0 | 81,367.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.5.1.0.0 | 81,367.13 63×23.25 | text | “mert_k” | Mono 500 15/23.25 … | text |  |  |
| r.5.1.0.1 | 150,378.75 190.78×0 | spacer |  |  |  |  | flex 1 |
| r.5.1.0.2 | 346.78,368.67 37.22×20.14 | text | “18:44” | Inter 400 13/20.15 tnum | accent300 |  |  |
| r.5.1.1 | 81,394.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.5.1.1.0 | 81,398.11 15×15 | icon | ph:sticker (regular) | 15pt | text·68% |  |  |
| r.5.1.1.1 | 102,394.38 254×22.47 | text | “Sticker” | Inter 400 14.5/22.48 … | text |  | flex 1 |
| r.5.1.1.2 | 362,394.61 22×22 | text | “2” | Inter 600 12.5/19.38 | bg | fill accent · r11 | grid place:center pad 0 7 0 7 |
| r.6 | 81,429.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.7 | 0,431.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.7.0 | 16,443.98 52×52 | text | “A” | Mono 500 19/19 | neutral100 | fill neutral800 · r50% | grid place:center |
| r.7.1 | 81,445.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.7.1.0 | 81,445.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.7.1.0.0 | 81,445.13 52.5×23.25 | text | “ayla_” | Mono 500 15/23.25 … | text |  |  |
| r.7.1.0.1 | 139.5,456.75 201.28×0 | spacer |  |  |  |  | flex 1 |
| r.7.1.0.2 | 346.78,446.67 37.22×20.14 | text | “16:20” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.7.1.1 | 81,472.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.7.1.1.0 | 81,476.11 15×15 | icon | ph:checks (regular) | 15pt | text·68% |  |  |
| r.7.1.1.1 | 102,472.38 207.25×22.47 | text | “Fotoğraf · tek görüntülemelik” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.7.1.1.2 | 315.25,472.61 68.75×22 | text | “38 dk” | Mono 400 10.5/16.27 | danger | fill danger·14% · r11 | row ai:center gap 4 pad 0 8 0 8 |
| r.7.1.1.2.0 | 323.25,477.61 12×12 | icon | ph:ghost (fill) | 12pt | danger |  |  |
| r.8 | 81,507.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.9 | 0,509.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.9.0 | 16,521.98 52×52 | text | “K” | Mono 500 19/19 | accent300 | fill accent900 · r50% · shadow inset 0 0 0 1px accent700 | grid place:center |
| r.9.1 | 81,523.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.9.1.0 | 81,523.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.9.1.0.0 | 81,523.13 42×23.25 | text | “kaan” | Mono 500 15/23.25 … | text |  |  |
| r.9.1.0.1 | 129,534.75 224.25×0 | spacer |  |  |  |  | flex 1 |
| r.9.1.0.2 | 359.25,524.67 24.75×20.14 | text | “Dün” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.9.1.1 | 81,550.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.9.1.1.0 | 81,554.11 15×15 | icon | ph:checks (fill) | 15pt | accent300 |  |  |
| r.9.1.1.1 | 102,550.38 207.25×22.47 | text | “Pazar kahvaltıya geliyor musun?” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.9.1.1.2 | 315.25,550.61 68.75×22 | text | “6 gün” | Mono 400 10.5/16.27 | accent300 | fill accent900 · r11 | row ai:center gap 4 pad 0 8 0 8 |
| r.9.1.1.2.0 | 323.25,555.61 12×12 | icon | ph:ghost (regular) | 12pt | accent300 |  |  |
| r.10 | 81,585.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.11 | 0,587.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.11.0 | 16,599.98 52×52 | text | “E” | Mono 500 19/19 | accent100 | fill accent800 · r50% | grid place:center |
| r.11.1 | 81,601.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.11.1.0 | 81,601.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.11.1.0.0 | 81,601.13 52.5×23.25 | text | “ece_y” | Mono 500 15/23.25 … | text |  |  |
| r.11.1.0.1 | 139.5,612.75 199.81×0 | spacer |  |  |  |  | flex 1 |
| r.11.1.0.2 | 345.31,602.67 38.69×20.14 | text | “24 Eyl” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.11.1.1 | 81,628.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.11.1.1.0 | 81,632.11 15×15 | icon | ph:warning-circle (regular) | 15pt | danger |  |  |
| r.11.1.1.1 | 102,628.38 282×22.47 | text | “İletilemedi · 14 gün açılmadı” | Inter 400 14.5/22.48 … | danger |  | flex 1 |
| r.12 | 81,663.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.13 | 0,665.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.13.0 | 16,677.98 52×52 | text | “B” | Mono 500 19/19 | accent100 | fill accent700 · r50% | grid place:center |
| r.13.1 | 81,679.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.13.1.0 | 81,679.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.13.1.0.0 | 81,679.13 42×23.25 | text | “bora” | Mono 500 15/23.25 … | text |  |  |
| r.13.1.0.1 | 129,690.75 210.31×0 | spacer |  |  |  |  | flex 1 |
| r.13.1.0.2 | 345.31,680.67 38.69×20.14 | text | “22 Eyl” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.13.1.1 | 81,706.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.13.1.1.0 | 81,710.11 15×15 | icon | ph:navigation-arrow (regular) | 15pt | text·68% |  |  |
| r.13.1.1.1 | 102,706.38 282×22.47 | text | “Canlı konum paylaşımı bitti” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.14 | 81,741.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.15 | 0,743.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.15.0 | 16,755.98 52×52 | text | “O” | Mono 500 19/19 | neutral100 | fill neutral800 · r50% | grid place:center |
| r.15.1 | 81,757.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.15.1.0 | 81,757.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.15.1.0.0 | 81,757.13 52.5×23.25 | text | “oya_t” | Mono 500 15/23.25 … | text |  |  |
| r.15.1.0.1 | 139.5,768.75 199.81×0 | spacer |  |  |  |  | flex 1 |
| r.15.1.0.2 | 345.31,758.67 38.69×20.14 | text | “19 Eyl” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.15.1.1 | 81,784.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.15.1.1.0 | 81,788.11 15×15 | icon | ph:prohibit (regular) | 15pt | text·68% |  |  |
| r.15.1.1.1 | 102,784.38 282×22.47 | text | “Bu mesaj herkesten silindi” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |

</details>

Colour legend 8.1:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `accent300` | #d2cefd | #5d5294 |
| `accent900` | #2b2741 | #f5f4ff |
| `accent700` | #5d5294 | #d2cefd |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent400` | #b5abfc | #796cbf |
| `accent·30%` | #9184d9@30% | #796cbf@30% |
| `accent200` | #e7e5fe | #423a6a |
| `accent·50%` | #9184d9@50% | #796cbf@50% |
| `surface` | #232532 | #e4e7f5 |
| `accent800` | #423a6a | #e7e5fe |
| `accent` | #9184d9 | #796cbf |
| `text·40%` | #e9e9ed@40% | #292b31@40% |
| `accent100` | #f5f4ff | #2b2741 |
| `text·22%` | #e9e9ed@22% | #292b31@22% |
| `online` | #52cd86 | #009351 |
| `neutral100` | #f3f5fe | #292b31 |
| `neutral800` | #3f424d | #dfe2f0 |
| `danger` | #f2716a | #c53637 |
| `danger·14%` | #f2716a@14% | #c53637@14% |

## 8.2 · Sohbet (açık) — Chat, light

Reference `reference/8.2-sohbet-acik.png` · urgent `reference/urgent/8.2-sohbet-acik-urgent.png` · data `layout/8.2-sohbet-acik.json`

> **Note (TR):** Giden balon vurgunun en açık tonuna, gelen balon yüzey rengine iner. Durum simgeleri ve sayaç okunur kalır.
> **EN:** The outgoing bubble drops to the lightest accent tone, the incoming bubble to the surface colour. Status icons and the counter stay legible.

Identical structure to 5.1.

<details><summary>Element table 8.2 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×108.63 | shape |  |  |  | fill bg·80% · backdropFilter blur(20px) saturate(1.4) | row ai:center gap 10 pad 54 14 10 12 abs z3 |
| r.0.0 | 12,56.31 40×40 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 22,66.31 20×20 | icon | ph:caret-left (regular) | 20pt | text |  |  |
| r.0.1 | 62,57.31 38×38 | text | “D” | Mono 500 14/14 | accent100 | fill accent800 · r50% | grid place:center |
| r.0.2 | 110,54 156.8×44.63 | stack |  |  |  |  | col gap 2 flex 1 |
| r.0.2.0 | 110,54 156.8×23.25 | text | “deniz” | Mono 500 15/23.25 | text |  | row ai:center gap 5 |
| r.0.2.0.0 | 167.5,58.13 15×15 | icon | ph:seal-check (fill) | 15pt | accent |  |  |
| r.0.2.1 | 110,79.25 156.8×19.38 | text | “Az önce aktifti” | Inter 400 12.5/19.38 | text·68% |  | row ai:center gap 6 |
| r.0.3 | 276.8,59.31 111.2×34 | shape |  |  |  | fill accent900 · r17 · shadow inset 0 0 0 1px accent800 | row ai:center gap 6 pad 0 12 0 10 |
| r.0.3.0 | 286.8,68.31 16×16 | icon | ph:ghost (regular) | 16pt | accent200 |  |  |
| r.0.3.1 | 308.8,67.02 67.2×18.59 | text | “23:41:07” | Mono 400 12/18.6 tnum | accent200 |  |  |
| r.1 | 0,104 402×2 | shape |  |  |  | fill radial-gradient(circle, text·20% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | abs z3 |
| r.2 | 0,0 402×874 | stack |  |  |  |  | col jc:flex-end gap 6 pad 0 14 100 14 flex 1 |
| r.2.0 | 86.09,81.13 229.81×27.39 | shape |  |  |  | fill text·6% · r14 | row ai:center self:center gap 7 pad 6 12 6 12 maxW 330 |
| r.2.0.0 | 98.09,87.81 14×14 | icon | ph:ghost (regular) | 14pt | text·68% |  |  |
| r.2.0.1 | 119.09,87.13 184.81×15.39 | text | “Ghost mod açık · 24 saat” | Mono 400 11/15.4 | text·68% |  |  |
| r.2.1 | 14,114.52 195.5×55.58 | shape |  |  |  | fill surface · r20 | col self:flex-start gap 5 pad 9 13 8 13 maxW 290 |
| r.2.1.0 | 27,123.52 169.5×22.08 | text | “Bu akşam boş musun?” | Inter 400 16/22.08 | text |  |  |
| r.2.1.1 | 163.58,150.59 32.92×11.5 | text | “18:02” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.2 | 14,176.09 268×234.58 | shape |  |  |  | fill surface · r20/20/20/6 | col self:flex-start gap 5 pad 5 5 8 5 mar 0 0 14 0 maxW 290 |
| r.2.2.0 | 19,181.09 258×176 | shape |  |  |  | fill radial-gradient(circle at 16% 57%, rgba(255, 210, 150, 0.95) 0px, rgba(255, 210, 150, 0.95) 1.4px, transparent 2.4px), r… (full value in layout JSON) · r15 |  |
| r.2.2.1 | 19,362.09 258×24.08 | text | “Şu yeni yer, Moda’da” | Inter 400 16/22.08 | text |  | pad 2 8 0 8 |
| r.2.2.2 | 236.08,391.17 32.92×11.5 | text | “18:03” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 mar 0 8 0 0 |
| r.2.2.3 | 26,398.67 34×27 | text | “❤️” | Inter 400 14/14 | text | fill accent800 · r14 · shadow 0 0 0 2.5px bg | row ai:center pad 0 8 0 8 abs |
| r.2.3 | 139.55,430.67 248.45×113.08 | shape |  |  |  | fill accent800 · r20/20/6/20 | col self:flex-end gap 5 pad 6 6 8 6 maxW 270 |
| r.2.3.0 | 145.55,436.67 236.45×52 | shape |  |  |  | fill bg·32% · r14 | row ai:center gap 10 pad 6 6 6 10 |
| r.2.3.0.0 | 155.55,443.73 170.45×37.88 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.3.0.0.0 | 155.55,443.73 170.45×17.25 | text | “deniz” | Inter 600 12.5/17.25 | accent300 |  | row ai:center gap 5 |
| r.2.3.0.0.0.0 | 155.55,445.86 13×13 | icon | ph:arrow-bend-up-left (regular) | 13pt | accent300 |  |  |
| r.2.3.0.0.1 | 155.55,462.98 170.45×18.63 | text | “Fotoğraf” | Inter 400 13.5/18.63 | text·68% |  | row ai:center gap 5 |
| r.2.3.0.0.1.0 | 155.55,465.3 14×14 | icon | ph:image (regular) | 14pt | text·68% |  |  |
| r.2.3.0.1 | 336,442.67 40×40 | shape |  |  |  | fill radial-gradient(circle at 16% 57%, rgba(255, 210, 150, 0.95) 0px, rgba(255, 210, 150, 0.95) 1.4px, transparent 2.4px), r… (full value in layout JSON) · r8 |  |
| r.2.3.1 | 145.55,493.67 236.45×22.08 | text | “Çok güzelmiş! 8 gibi olur mu?” | Inter 400 16/22.08 | accent100 |  | pad 0 7 0 7 |
| r.2.3.2 | 323.08,520.75 51.92×15 | text | “18:05” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 mar 0 7 0 0 |
| r.2.3.2.0 | 360,520.75 15×15 | icon | ph:checks (fill) | 15pt | accent300 |  |  |
| r.2.4 | 14,549.75 239.81×69.5 | shape |  |  |  | fill surface · r20/20/20/6 | col self:flex-start gap 5 pad 9 12 8 9 maxW 290 |
| r.2.4.0 | 23,558.75 218.81×36 | stack |  |  |  |  | row ai:center gap 10 |
| r.2.4.0.0 | 23,558.75 36×36 | shape |  |  |  | r50% · border 1px solid accent | grid place:center |
| r.2.4.0.0.0 | 33.5,569.25 15×15 | icon | ph:play (fill) | 15pt | accent300 |  |  |
| r.2.4.0.1 | 69,562.75 132×28 | svg | custom svg (waveform) |  | text |  |  |
| r.2.4.0.2 | 211,569.16 30.81×15.17 | text | “0:42” | Mono 400 11/15.18 | text·68% |  |  |
| r.2.4.1 | 208.89,599.75 32.92×11.5 | text | “18:07” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.5 | 159.05,625.25 228.95×59.08 | shape |  |  |  | fill accent800 · r20 | col self:flex-end gap 5 pad 9 13 8 13 maxW 290 |
| r.2.5.0 | 172.05,634.25 202.95×22.08 | text | “Tamam, iskelede buluşalım” | Inter 400 16/22.08 | accent100 |  |  |
| r.2.5.1 | 323.08,661.33 51.92×15 | text | “18:09” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.5.1.0 | 360,661.33 15×15 | icon | ph:checks (regular) | 15pt | text·68% |  |  |
| r.2.6 | 246.08,690.33 141.92×59.08 | shape |  |  |  | fill accent800 · r20/20/6/20 | col self:flex-end gap 5 pad 9 13 8 13 maxW 290 |
| r.2.6.0 | 259.08,699.33 115.92×22.08 | text | “Kapı kodu 4471” | Inter 400 16/22.08 | accent100 |  |  |
| r.2.6.1 | 269.67,726.41 105.33×15 | stack |  |  |  |  | row ai:center self:flex-end gap 8 |
| r.2.6.1.0 | 269.67,727.41 45.41×13 | text | “5 dk” | Mono 400 10.5/10.5 | accent300 |  | row ai:center gap 3 |
| r.2.6.1.0.0 | 269.67,727.41 13×13 | icon | ph:timer (regular) | 13pt | accent300 |  |  |
| r.2.6.1.1 | 323.08,726.41 51.92×15 | text | “18:10” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center gap 4 |
| r.2.6.1.1.0 | 360,726.41 15×15 | icon | ph:check (regular) | 15pt | text·68% |  |  |
| r.2.7 | 108.95,755.41 279.05×18.59 | stack |  |  |  |  | row ai:center self:flex-end gap 6 pad 0 4 0 0 |
| r.2.7.0 | 108.95,757.7 14×14 | icon | ph:check (regular) | 14pt | text·68% |  |  |
| r.2.7.1 | 128.95,755.41 255.05×18.59 | text | “Gönderildi · Alıcı uygulamayı açınca iletilecek” | Inter 400 12/18.6 | text·68% |  |  |
| r.2.7.1.0 | 128.95,756.41 59.14×15 | text | “Gönderildi” | Inter 500 12/18.6 | text |  |  |
| r.3 | 0,780 402×94 | shape |  |  |  | fill linear-gradient(to bottom, transparent, bg 30%) | row ai:flex-end gap 8 pad 14 12 36 12 abs z2 |
| r.3.0 | 12,794 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.3.0.0 | 23,805 22×22 | icon | ph:plus (regular) | 22pt | text |  |  |
| r.3.1 | 64,794 274×44 | shape |  |  |  | fill surface · r22 · shadow inset 0 0 0 0.5px text·14% | row ai:center gap 12 pad 0 12 0 16 flex 1 minH 44 |
| r.3.1.0 | 80,803.59 180×24.8 | text | “Mesaj” | Inter 400 16/24.8 | text·50% |  | flex 1 |
| r.3.1.1 | 272,805.5 21×21 | icon | ph:timer (regular) | 21pt | text·55% |  |  |
| r.3.1.2 | 305,805.5 21×21 | icon | ph:sticker (regular) | 21pt | text·55% |  |  |
| r.3.2 | 346,794 44×44 | shape |  |  |  | r50% · border 1px solid accent · shadow 0 0 20px -6px accent·70% | grid place:center |
| r.3.2.0 | 357.5,805.5 21×21 | icon | ph:microphone (regular) | 21pt | accent300 |  |  |

</details>

Colour legend 8.2:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `bg·80%` | #161826@80% | #f3f5fe@80% |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `accent100` | #f5f4ff | #2b2741 |
| `accent800` | #423a6a | #e7e5fe |
| `accent` | #9184d9 | #796cbf |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent200` | #e7e5fe | #423a6a |
| `accent900` | #2b2741 | #f5f4ff |
| `text·20%` | #e9e9ed@20% | #292b31@20% |
| `text·6%` | #e9e9ed@6% | #292b31@6% |
| `surface` | #232532 | #e4e7f5 |
| `bg·32%` | #161826@32% | #f3f5fe@32% |
| `accent300` | #d2cefd | #5d5294 |
| `text·14%` | #e9e9ed@14% | #292b31@14% |
| `text·50%` | #e9e9ed@50% | #292b31@50% |
| `text·55%` | #e9e9ed@55% | #292b31@55% |
| `accent·70%` | #9184d9@70% | #796cbf@70% |

## 8.3 · Kod yanlış (açık) — Wrong code, light

Reference `reference/8.3-kod-yanlis-acik.png` · data `layout/8.3-kod-yanlis-acik.json`

> **Note (TR):** Hata rengi açık zeminde koyulaşır; uyarı metni 4.5:1 kontrastın üstünde kalır.
> **EN:** The error colour darkens on the light ground; the warning text stays above 4.5:1 contrast.

Identical structure to 3.2 (danger = `#c53637` on `#f3f5fe`).

<details><summary>Element table 8.3 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×204 | stack |  |  |  |  | col ai:center gap 16 pad 106 0 0 0 |
| r.0.0 | 175,106 52×52 | shape |  |  |  | fill accent900 · r50% · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.0.0.0 | 189,120 24×24 | icon | ph:lock-simple (regular) | 24pt | accent300 |  |  |
| r.0.1 | 57,174 288×30 | text | “Gizli kodunu gir” | Doto 900 30/30 ROND100 center | text |  |  |
| r.1 | 0,204 402×109.03 | stack |  |  |  |  | col ai:center gap 16 pad 36 0 0 0 |
| r.1.0 | 106,240 190×15 | stack |  |  |  |  | row jc:center gap 20 |
| r.1.0.0 | 106,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.0.1 | 141,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.0.2 | 176,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.0.3 | 211,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.0.4 | 246,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.0.5 | 281,240 15×15 | shape |  |  |  | fill danger · r50% |  |
| r.1.1 | 51,271 300×42.03 | text | “Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir.” | Inter 400 14.5/21.02 center | danger |  | maxW 300 |
| r.2 | 0,313.03 402×100.97 | spacer |  |  |  |  | flex 1 |
| r.3 | 0,414 402×374 | box |  |  |  |  | pad 0 0 14 0 |
| r.3.0 | 0,414 402×360 | stack |  |  |  |  | grid cols repeat(3, 78px) jc:center gap 16/26 |
| r.3.0.0 | 58,414 78×78 | text | “1” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.1 | 162,414 78×78 | text | “2” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.2 | 266,414 78×78 | text | “3” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.3 | 58,508 78×78 | text | “4” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.4 | 162,508 78×78 | text | “5” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.5 | 266,508 78×78 | text | “6” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.6 | 58,602 78×78 | text | “7” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.7 | 162,602 78×78 | text | “8” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.8 | 266,602 78×78 | text | “9” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.9 | 58,696 78×78 | stack |  |  |  |  | grid place:center |
| r.3.0.9.0 | 82,720 30×30 | svg | custom svg (face-id glyph) |  | text |  |  |
| r.3.0.10 | 162,696 78×78 | text | “0” | Inter 400 32/32 | text | fill text·9% · r50% | grid place:center |
| r.3.0.11 | 266,696 78×78 | stack |  |  |  |  | grid place:center |
| r.3.0.11.0 | 291,721 28×28 | icon | ph:backspace (regular) | 28pt | text |  |  |
| r.4 | 0,788 402×86 | box |  |  |  |  | pad 0 24 40 24 |
| r.4.0 | 24,788 354×46 | text | “Kodu unuttum” | Inter 400 16/24.8 | accent300 |  | row ai:center jc:center gap 8 |

</details>

Colour legend 8.3:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `accent300` | #d2cefd | #5d5294 |
| `accent900` | #2b2741 | #f5f4ff |
| `accent800` | #423a6a | #e7e5fe |
| `danger` | #f2716a | #c53637 |
| `text·9%` | #e9e9ed@9% | #292b31@9% |

# 09 · TR / EN (“İki dil, tek yerleşim” / “Two languages, one layout”)

> **Section note (TR):** Türkçe metinler İngilizceden %20–30 uzun; yerleşim Türkçeyle kuruldu, İngilizce aynı kalıplara sığar. Büyük-küçük harf dönüşümünde dil her zaman açıkça verilir.
> **EN:** Turkish copy is 20–30 % longer than English; the layout was built with Turkish and English fits the same moulds. The language is always given explicitly when changing case.

## 9.1 · Chats (EN)

Reference `reference/9.1-chats-en.png` · urgent `reference/urgent/9.1-chats-en-urgent.png` · light `reference/light/9.1-chats-en.png` · data `layout/9.1-chats-en.json`

> **Note (TR):** İngilizce metinler aynı kalıplara rahat sığar; Türkçede taşmayan hiçbir satır burada da taşmaz.
> **EN:** The English copy fits the same moulds comfortably; no line that fits in Turkish overflows here.

Same layout as 4.1 with the English strings (see 4.1 texts, “(09)”).

<details><summary>Element table 9.1 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×100 | stack |  |  |  |  | row ai:center jc:space-between pad 56 16 0 16 |
| r.0.0 | 16,58 40×40 | text | “S” | Mono 500 14/14 | accent300 | fill accent900 · r50% · shadow inset 0 0 0 1px accent700 | grid place:center |
| r.0.1 | 288,56 98×44 | stack |  |  |  |  | row gap 10 |
| r.0.1.0 | 288,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.1.0.0 | 299.5,67.5 21×21 | icon | ph:qr-code (regular) | 21pt | text |  |  |
| r.0.1.1 | 342,56 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.1.1.0 | 353.5,67.5 21×21 | icon | ph:note-pencil (regular) | 21pt | accent300 |  |  |
| r.1 | 0,100 402×81.05 | stack |  |  |  |  | col gap 12 pad 14 20 0 20 |
| r.1.0 | 20,114 362×38 | text | “Chats” | Doto 900 38/38 ROND100 | text |  |  |
| r.1.1 | 20,164 362×17.05 | text | “Line open · messages arriving” | Mono 400 11/17.05 | text·68% |  | row ai:center gap 10 |
| r.1.1.0 | 20,168.02 60×9 | stack |  |  |  |  | row ai:center |
| r.1.1.0.0 | 20,168.02 9×9 | shape |  |  |  | r50% · border 2px solid accent400 |  |
| r.1.1.0.1 | 29,171.52 42×2 | shape |  |  |  | fill linear-gradient(90deg, accent400, accent·30% 50%, accent400) | flex 1 |
| r.1.1.0.1.0 | 32.19,170.52 44×4 | shape |  |  |  | fill linear-gradient(90deg, transparent, accent200, transparent) · r4 · shadow 0 0 12px 2px accent·50% · anim u2-packet-r 6s cubic-bezier(0.45, 0, 0.55, 1) delay 0s ×infinite | mar -2 0 0 -22 abs |
| r.1.1.0.1.1 | 7,170.52 44×4 | shape |  |  |  | fill linear-gradient(90deg, transparent, accent200, transparent) · r4 · shadow 0 0 12px 2px accent·50% · opacity 0 · anim u2-packet-l 6s cubic-bezier(0.45, 0, 0.55, 1) delay 3s ×infinite | mar -2 0 0 -22 abs |
| r.1.1.0.2 | 71,168.02 9×9 | shape |  |  |  | r50% · border 2px solid accent400 |  |
| r.2 | 16,199.05 370×72.94 | shape |  |  |  | fill surface · r18 | row ai:center gap 13 pad 13 14 13 14 mar 18 16 4 16 |
| r.2.0 | 30,214.52 42×42 | shape |  |  |  | fill accent900 · r50% · shadow inset 0 0 0 1px accent800 | grid place:center |
| r.2.0.0 | 40.5,225.02 21×21 | icon | ph:tray (regular) | 21pt | accent300 |  |  |
| r.2.1 | 85,212.05 224×46.94 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.1.0 | 85,212.05 224×24.8 | text | “Message requests” | Inter 500 16/24.8 | text |  |  |
| r.2.1.1 | 85,238.84 224×20.14 | text | “2 people want to message you” | Inter 400 13/20.15 | text·68% |  |  |
| r.2.2 | 322,224.52 22×22 | text | “2” | Inter 600 12.5/19.38 | bg | fill accent · r11 | grid place:center pad 0 7 0 7 |
| r.2.3 | 357,228.02 15×15 | icon | ph:caret-right (regular) | 15pt | text·40% |  |  |
| r.3 | 0,275.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.3.0 | 16,287.98 52×52 | text | “D” | Mono 500 19/19 | accent100 | fill accent800 · r50% | grid place:center |
| r.3.1 | 81,289.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.3.1.0 | 81,289.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.3.1.0.0 | 81,289.13 52.5×23.25 | text | “deniz” | Mono 500 15/23.25 … | text |  |  |
| r.3.1.0.1 | 139.5,293.75 14×14 | icon | ph:seal-check (fill) | 14pt | accent |  |  |
| r.3.1.0.2 | 159.5,300.75 181.28×0 | spacer |  |  |  |  | flex 1 |
| r.3.1.0.3 | 346.78,290.67 37.22×20.14 | text | “18:10” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.3.1.1 | 81,316.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.3.1.1.0 | 81,320.11 15×15 | icon | ph:check (regular) | 15pt | text·68% |  |  |
| r.3.1.1.1 | 102,320.11 15×15 | icon | ph:timer (regular) | 15pt | text·68% |  |  |
| r.3.1.1.2 | 123,316.38 193.59×22.47 | text | “Disappearing message” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.3.1.1.3 | 322.59,316.61 61.41×22 | shape |  |  |  | fill accent900 · r11 | row ai:center gap 4 pad 0 8 0 8 |
| r.3.1.1.3.0 | 330.59,321.61 12×12 | icon | ph:ghost (regular) | 12pt | accent300 |  |  |
| r.3.1.1.3.1 | 346.59,319.47 29.41×16.27 | text | “23 h” | Mono 400 10.5/16.27 | accent300 |  |  |
| r.4 | 81,351.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.5 | 0,353.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.5.0 | 16,365.98 52×52 | text | “M” | Mono 500 19/19 | accent100 | fill accent700 · r50% | grid place:center |
| r.5.0.0 | 53,402.98 15×15 | shape |  |  |  | fill online · r50% · shadow 0 0 0 3px bg | abs |
| r.5.1 | 81,367.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.5.1.0 | 81,367.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.5.1.0.0 | 81,367.13 63×23.25 | text | “mert_k” | Mono 500 15/23.25 … | text |  |  |
| r.5.1.0.1 | 150,378.75 190.78×0 | spacer |  |  |  |  | flex 1 |
| r.5.1.0.2 | 346.78,368.67 37.22×20.14 | text | “18:44” | Inter 400 13/20.15 tnum | accent300 |  |  |
| r.5.1.1 | 81,394.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.5.1.1.0 | 81,398.11 15×15 | icon | ph:sticker (regular) | 15pt | text·68% |  |  |
| r.5.1.1.1 | 102,394.38 254×22.47 | text | “Sticker” | Inter 400 14.5/22.48 … | text |  | flex 1 |
| r.5.1.1.2 | 362,394.61 22×22 | text | “2” | Inter 600 12.5/19.38 | bg | fill accent · r11 | grid place:center pad 0 7 0 7 |
| r.6 | 81,429.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.7 | 0,431.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.7.0 | 16,443.98 52×52 | text | “A” | Mono 500 19/19 | neutral100 | fill neutral800 · r50% | grid place:center |
| r.7.1 | 81,445.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.7.1.0 | 81,445.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.7.1.0.0 | 81,445.13 52.5×23.25 | text | “ayla_” | Mono 500 15/23.25 … | text |  |  |
| r.7.1.0.1 | 139.5,456.75 201.28×0 | spacer |  |  |  |  | flex 1 |
| r.7.1.0.2 | 346.78,446.67 37.22×20.14 | text | “16:20” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.7.1.1 | 81,472.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.7.1.1.0 | 81,476.11 15×15 | icon | ph:checks (regular) | 15pt | text·68% |  |  |
| r.7.1.1.1 | 102,472.38 199.89×22.47 | text | “Photo · view once” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.7.1.1.2 | 307.89,472.61 76.11×22 | text | “38 min” | Mono 400 10.5/16.27 | danger | fill danger·14% · r11 | row ai:center gap 4 pad 0 8 0 8 |
| r.7.1.1.2.0 | 315.89,477.61 12×12 | icon | ph:ghost (fill) | 12pt | danger |  |  |
| r.8 | 81,507.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.9 | 0,509.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.9.0 | 16,521.98 52×52 | text | “K” | Mono 500 19/19 | accent300 | fill accent900 · r50% · shadow inset 0 0 0 1px accent700 | grid place:center |
| r.9.1 | 81,523.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.9.1.0 | 81,523.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.9.1.0.0 | 81,523.13 42×23.25 | text | “kaan” | Mono 500 15/23.25 … | text |  |  |
| r.9.1.0.1 | 129,534.75 187.97×0 | spacer |  |  |  |  | flex 1 |
| r.9.1.0.2 | 322.97,524.67 61.03×20.14 | text | “Yesterday” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.9.1.1 | 81,550.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.9.1.1.0 | 81,554.11 15×15 | icon | ph:checks (fill) | 15pt | accent300 |  |  |
| r.9.1.1.1 | 102,550.38 199.89×22.47 | text | “Coming to Sunday breakfast?” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.9.1.1.2 | 307.89,550.61 76.11×22 | text | “6 days” | Mono 400 10.5/16.27 | accent300 | fill accent900 · r11 | row ai:center gap 4 pad 0 8 0 8 |
| r.9.1.1.2.0 | 315.89,555.61 12×12 | icon | ph:ghost (regular) | 12pt | accent300 |  |  |
| r.10 | 81,585.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.11 | 0,587.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.11.0 | 16,599.98 52×52 | text | “E” | Mono 500 19/19 | accent100 | fill accent800 · r50% | grid place:center |
| r.11.1 | 81,601.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.11.1.0 | 81,601.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.11.1.0.0 | 81,601.13 52.5×23.25 | text | “ece_y” | Mono 500 15/23.25 … | text |  |  |
| r.11.1.0.1 | 139.5,612.75 194.27×0 | spacer |  |  |  |  | flex 1 |
| r.11.1.0.2 | 339.77,602.67 44.23×20.14 | text | “Sep 24” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.11.1.1 | 81,628.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.11.1.1.0 | 81,632.11 15×15 | icon | ph:warning-circle (regular) | 15pt | danger |  |  |
| r.11.1.1.1 | 102,628.38 282×22.47 | text | “Not delivered · not opened in 14 days” | Inter 400 14.5/22.48 … | danger |  | flex 1 |
| r.12 | 81,663.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.13 | 0,665.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.13.0 | 16,677.98 52×52 | text | “B” | Mono 500 19/19 | accent100 | fill accent700 · r50% | grid place:center |
| r.13.1 | 81,679.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.13.1.0 | 81,679.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.13.1.0.0 | 81,679.13 42×23.25 | text | “bora” | Mono 500 15/23.25 … | text |  |  |
| r.13.1.0.1 | 129,690.75 204.77×0 | spacer |  |  |  |  | flex 1 |
| r.13.1.0.2 | 339.77,680.67 44.23×20.14 | text | “Sep 22” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.13.1.1 | 81,706.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.13.1.1.0 | 81,710.11 15×15 | icon | ph:navigation-arrow (regular) | 15pt | text·68% |  |  |
| r.13.1.1.1 | 102,706.38 282×22.47 | text | “Live location sharing ended” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |
| r.14 | 81,741.98 321×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | mar 0 0 0 81 |
| r.15 | 0,743.98 402×76 | stack |  |  |  |  | row ai:center gap 13 pad 0 18 0 16 minH 76 |
| r.15.0 | 16,755.98 52×52 | text | “O” | Mono 500 19/19 | neutral100 | fill neutral800 · r50% | grid place:center |
| r.15.1 | 81,757.13 303×49.72 | stack |  |  |  |  | col gap 4 flex 1 |
| r.15.1.0 | 81,757.13 303×23.25 | stack |  |  |  |  | row ai:center gap 6 |
| r.15.1.0.0 | 81,757.13 52.5×23.25 | text | “oya_t” | Mono 500 15/23.25 … | text |  |  |
| r.15.1.0.1 | 139.5,768.75 194.27×0 | spacer |  |  |  |  | flex 1 |
| r.15.1.0.2 | 339.77,758.67 44.23×20.14 | text | “Sep 19” | Inter 400 13/20.15 tnum | text·68% |  |  |
| r.15.1.1 | 81,784.38 303×22.47 | stack |  |  |  |  | row ai:center gap 6 minH 22 |
| r.15.1.1.0 | 81,788.11 15×15 | icon | ph:prohibit (regular) | 15pt | text·68% |  |  |
| r.15.1.1.1 | 102,784.38 282×22.47 | text | “This message was deleted for everyone” | Inter 400 14.5/22.48 … | text·68% |  | flex 1 |

</details>

Colour legend 9.1:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `accent300` | #d2cefd | #5d5294 |
| `accent900` | #2b2741 | #f5f4ff |
| `accent700` | #5d5294 | #d2cefd |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent400` | #b5abfc | #796cbf |
| `accent·30%` | #9184d9@30% | #796cbf@30% |
| `accent200` | #e7e5fe | #423a6a |
| `accent·50%` | #9184d9@50% | #796cbf@50% |
| `surface` | #232532 | #e4e7f5 |
| `accent800` | #423a6a | #e7e5fe |
| `accent` | #9184d9 | #796cbf |
| `text·40%` | #e9e9ed@40% | #292b31@40% |
| `accent100` | #f5f4ff | #2b2741 |
| `text·22%` | #e9e9ed@22% | #292b31@22% |
| `online` | #52cd86 | #009351 |
| `neutral100` | #f3f5fe | #292b31 |
| `neutral800` | #3f424d | #dfe2f0 |
| `danger` | #f2716a | #c53637 |
| `danger·14%` | #f2716a@14% | #c53637@14% |

## 9.2 · Chat (EN)

Reference `reference/9.2-chat-en.png` · urgent `reference/urgent/9.2-chat-en-urgent.png` · light `reference/light/9.2-chat-en.png` · data `layout/9.2-chat-en.json`

> **Note (TR):** Durum cümlesi iki dilde de tek satır. Sayaç birimi dile göre değişir: “23 sa” / “23 h”.
> **EN:** The status sentence is a single line in both languages. The counter unit changes with the language: “23 sa” / “23 h”.

Same layout as 5.1 with the English strings.

<details><summary>Element table 9.2 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 402×874 | shape |  |  |  | fill bg | col |
| r.0 | 0,0 402×108.63 | shape |  |  |  | fill bg·80% · backdropFilter blur(20px) saturate(1.4) | row ai:center gap 10 pad 54 14 10 12 abs z3 |
| r.0.0 | 12,56.31 40×40 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.0.0.0 | 22,66.31 20×20 | icon | ph:caret-left (regular) | 20pt | text |  |  |
| r.0.1 | 62,57.31 38×38 | text | “D” | Mono 500 14/14 | accent100 | fill accent800 · r50% | grid place:center |
| r.0.2 | 110,54 156.8×44.63 | stack |  |  |  |  | col gap 2 flex 1 |
| r.0.2.0 | 110,54 156.8×23.25 | text | “deniz” | Mono 500 15/23.25 | text |  | row ai:center gap 5 |
| r.0.2.0.0 | 167.5,58.13 15×15 | icon | ph:seal-check (fill) | 15pt | accent |  |  |
| r.0.2.1 | 110,79.25 156.8×19.38 | text | “Active just now” | Inter 400 12.5/19.38 | text·68% |  | row ai:center gap 6 |
| r.0.3 | 276.8,59.31 111.2×34 | shape |  |  |  | fill accent900 · r17 · shadow inset 0 0 0 1px accent800 | row ai:center gap 6 pad 0 12 0 10 |
| r.0.3.0 | 286.8,68.31 16×16 | icon | ph:ghost (regular) | 16pt | accent200 |  |  |
| r.0.3.1 | 308.8,67.02 67.2×18.59 | text | “23:41:07” | Mono 400 12/18.6 tnum | accent200 |  |  |
| r.1 | 0,104 402×2 | shape |  |  |  | fill radial-gradient(circle, text·20% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x | abs z3 |
| r.2 | 0,0 402×874 | stack |  |  |  |  | col jc:flex-end gap 6 pad 0 14 100 14 flex 1 |
| r.2.0 | 86.09,81.13 229.81×27.39 | shape |  |  |  | fill text·6% · r14 | row ai:center self:center gap 7 pad 6 12 6 12 maxW 330 |
| r.2.0.0 | 98.09,87.81 14×14 | icon | ph:ghost (regular) | 14pt | text·68% |  |  |
| r.2.0.1 | 119.09,87.13 184.81×15.39 | text | “Ghost mode on · 24 hours” | Mono 400 11/15.4 | text·68% |  |  |
| r.2.1 | 14,114.52 184.39×55.58 | shape |  |  |  | fill surface · r20 | col self:flex-start gap 5 pad 9 13 8 13 maxW 290 |
| r.2.1.0 | 27,123.52 158.39×22.08 | text | “Are you free tonight?” | Inter 400 16/22.08 | text |  |  |
| r.2.1.1 | 152.47,150.59 32.92×11.5 | text | “18:02” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.2 | 14,176.09 268×234.58 | shape |  |  |  | fill surface · r20/20/20/6 | col self:flex-start gap 5 pad 5 5 8 5 mar 0 0 14 0 maxW 290 |
| r.2.2.0 | 19,181.09 258×176 | shape |  |  |  | fill radial-gradient(circle at 16% 57%, rgba(255, 210, 150, 0.95) 0px, rgba(255, 210, 150, 0.95) 1.4px, transparent 2.4px), r… (full value in layout JSON) · r15 |  |
| r.2.2.1 | 19,362.09 258×24.08 | text | “That new place in Moda” | Inter 400 16/22.08 | text |  | pad 2 8 0 8 |
| r.2.2.2 | 236.08,391.17 32.92×11.5 | text | “18:03” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 mar 0 8 0 0 |
| r.2.2.3 | 26,398.67 34×27 | text | “❤️” | Inter 400 14/14 | text | fill accent800 · r14 · shadow 0 0 0 2.5px bg | row ai:center pad 0 8 0 8 abs |
| r.2.3 | 185.98,430.67 202.02×113.08 | shape |  |  |  | fill accent800 · r20/20/6/20 | col self:flex-end gap 5 pad 6 6 8 6 maxW 270 |
| r.2.3.0 | 191.98,436.67 190.02×52 | shape |  |  |  | fill bg·32% · r14 | row ai:center gap 10 pad 6 6 6 10 |
| r.2.3.0.0 | 201.98,443.73 124.02×37.88 | stack |  |  |  |  | col gap 2 flex 1 |
| r.2.3.0.0.0 | 201.98,443.73 124.02×17.25 | text | “deniz” | Inter 600 12.5/17.25 | accent300 |  | row ai:center gap 5 |
| r.2.3.0.0.0.0 | 201.98,445.86 13×13 | icon | ph:arrow-bend-up-left (regular) | 13pt | accent300 |  |  |
| r.2.3.0.0.1 | 201.98,462.98 124.02×18.63 | text | “Photo” | Inter 400 13.5/18.63 | text·68% |  | row ai:center gap 5 |
| r.2.3.0.0.1.0 | 201.98,465.3 14×14 | icon | ph:image (regular) | 14pt | text·68% |  |  |
| r.2.3.0.1 | 336,442.67 40×40 | shape |  |  |  | fill radial-gradient(circle at 16% 57%, rgba(255, 210, 150, 0.95) 0px, rgba(255, 210, 150, 0.95) 1.4px, transparent 2.4px), r… (full value in layout JSON) · r8 |  |
| r.2.3.1 | 191.98,493.67 190.02×22.08 | text | “Looks great! Around 8?” | Inter 400 16/22.08 | accent100 |  | pad 0 7 0 7 |
| r.2.3.2 | 323.08,520.75 51.92×15 | text | “18:05” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 mar 0 7 0 0 |
| r.2.3.2.0 | 360,520.75 15×15 | icon | ph:checks (fill) | 15pt | accent300 |  |  |
| r.2.4 | 14,549.75 239.81×69.5 | shape |  |  |  | fill surface · r20/20/20/6 | col self:flex-start gap 5 pad 9 12 8 9 maxW 290 |
| r.2.4.0 | 23,558.75 218.81×36 | stack |  |  |  |  | row ai:center gap 10 |
| r.2.4.0.0 | 23,558.75 36×36 | shape |  |  |  | r50% · border 1px solid accent | grid place:center |
| r.2.4.0.0.0 | 33.5,569.25 15×15 | icon | ph:play (fill) | 15pt | accent300 |  |  |
| r.2.4.0.1 | 69,562.75 132×28 | svg | custom svg (waveform) |  | text |  |  |
| r.2.4.0.2 | 211,569.16 30.81×15.17 | text | “0:42” | Mono 400 11/15.18 | text·68% |  |  |
| r.2.4.1 | 208.89,599.75 32.92×11.5 | text | “18:07” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.5 | 179,625.25 209×59.08 | shape |  |  |  | fill accent800 · r20 | col self:flex-end gap 5 pad 9 13 8 13 maxW 290 |
| r.2.5.0 | 192,634.25 183×22.08 | text | “OK, meet you at the pier” | Inter 400 16/22.08 | accent100 |  |  |
| r.2.5.1 | 323.08,661.33 51.92×15 | text | “18:09” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center self:flex-end gap 4 |
| r.2.5.1.0 | 360,661.33 15×15 | icon | ph:checks (regular) | 15pt | text·68% |  |  |
| r.2.6 | 242.14,690.33 145.86×59.08 | shape |  |  |  | fill accent800 · r20/20/6/20 | col self:flex-end gap 5 pad 9 13 8 13 maxW 290 |
| r.2.6.0 | 255.14,699.33 119.86×22.08 | text | “Door code 4471” | Inter 400 16/22.08 | accent100 |  |  |
| r.2.6.1 | 262.33,726.41 112.67×15 | stack |  |  |  |  | row ai:center self:flex-end gap 8 |
| r.2.6.1.0 | 262.33,727.41 52.75×13 | text | “5 min” | Mono 400 10.5/10.5 | accent300 |  | row ai:center gap 3 |
| r.2.6.1.0.0 | 262.33,727.41 13×13 | icon | ph:timer (regular) | 13pt | accent300 |  |  |
| r.2.6.1.1 | 323.08,726.41 51.92×15 | text | “18:10” | Inter 400 11.5/11.5 tnum | text·68% |  | row ai:center gap 4 |
| r.2.6.1.1.0 | 360,726.41 15×15 | icon | ph:check (regular) | 15pt | text·68% |  |  |
| r.2.7 | 93.23,755.41 294.77×18.59 | stack |  |  |  |  | row ai:center self:flex-end gap 6 pad 0 4 0 0 |
| r.2.7.0 | 93.23,757.7 14×14 | icon | ph:check (regular) | 14pt | text·68% |  |  |
| r.2.7.1 | 113.23,755.41 270.77×18.59 | text | “Sent · Will be delivered when they open the app” | Inter 400 12/18.6 | text·68% |  |  |
| r.2.7.1.0 | 113.23,756.41 26.11×15 | text | “Sent” | Inter 500 12/18.6 | text |  |  |
| r.3 | 0,780 402×94 | shape |  |  |  | fill linear-gradient(to bottom, transparent, bg 30%) | row ai:flex-end gap 8 pad 14 12 36 12 abs z2 |
| r.3.0 | 12,794 44×44 | shape |  |  |  | fill text·9% · r50% · shadow inset 0 0 0 0.5px text·18% | grid place:center |
| r.3.0.0 | 23,805 22×22 | icon | ph:plus (regular) | 22pt | text |  |  |
| r.3.1 | 64,794 274×44 | shape |  |  |  | fill surface · r22 · shadow inset 0 0 0 0.5px text·14% | row ai:center gap 12 pad 0 12 0 16 flex 1 minH 44 |
| r.3.1.0 | 80,803.59 180×24.8 | text | “Message” | Inter 400 16/24.8 | text·50% |  | flex 1 |
| r.3.1.1 | 272,805.5 21×21 | icon | ph:timer (regular) | 21pt | text·55% |  |  |
| r.3.1.2 | 305,805.5 21×21 | icon | ph:sticker (regular) | 21pt | text·55% |  |  |
| r.3.2 | 346,794 44×44 | shape |  |  |  | r50% · border 1px solid accent · shadow 0 0 20px -6px accent·70% | grid place:center |
| r.3.2.0 | 357.5,805.5 21×21 | icon | ph:microphone (regular) | 21pt | accent300 |  |  |

</details>

Colour legend 9.2:

| token expression | dark | light |
|---|---|---|
| `text` | #e9e9ed | #292b31 |
| `bg` | #161826 | #f3f5fe |
| `bg·80%` | #161826@80% | #f3f5fe@80% |
| `text·9%` | #e9e9ed@9% | #292b31@9% |
| `text·18%` | #e9e9ed@18% | #292b31@18% |
| `accent100` | #f5f4ff | #2b2741 |
| `accent800` | #423a6a | #e7e5fe |
| `accent` | #9184d9 | #796cbf |
| `text·68%` | #e9e9ed@68% | #292b31@68% |
| `accent200` | #e7e5fe | #423a6a |
| `accent900` | #2b2741 | #f5f4ff |
| `text·20%` | #e9e9ed@20% | #292b31@20% |
| `text·6%` | #e9e9ed@6% | #292b31@6% |
| `surface` | #232532 | #e4e7f5 |
| `bg·32%` | #161826@32% | #f3f5fe@32% |
| `accent300` | #d2cefd | #5d5294 |
| `text·14%` | #e9e9ed@14% | #292b31@14% |
| `text·50%` | #e9e9ed@50% | #292b31@50% |
| `text·55%` | #e9e9ed@55% | #292b31@55% |
| `accent·70%` | #9184d9@70% | #796cbf@70% |

## 9.3 · Büyük harf testi — Upper-case test (design guidance card, not a screen)

Reference `reference/9.3-buyuk-harf.png` · data `layout/9.3-buyuk-harf.json`

Rule (from the card): i ↔ İ and ı ↔ I mapping depends on the language. Upper-casing “istekler” without a language gives “ISTEKLER” (wrong in Turkish). In SwiftUI, `Text(key).textCase(.uppercase)` uses the environment locale; for raw `String` conversions pass the locale explicitly, e.g. `s.uppercased(with: Locale(identifier: "tr_TR"))`. Usernames follow PROTOCOL.md §3.1 — only `a-z`, `0-9` and `_`, normalized with the locale-independent lower case; Turkish letters are reported, never converted (see 2.1) — so exact matching is unaffected. Test table on the card:

| source | `lang="tr"` | no language (wrong) |
|---|---|---|
| mesaj istekleri | MESAJ İSTEKLERİ | MESAJ ISTEKLERI |
| iletildi | İLETİLDİ | ILETILDI |
| gizli kodu gir | GİZLİ KODU GİR | GIZLI KODU GIR |
| İLETİLEMEDİ (lower-cased) | iletilemedi | i̇leti̇lemedi̇ |

Builders: every UPPERCASE label in this design (`monoKicker`, `monoSectionLabel`) must be produced with `.textCase(.uppercase)` under the app's current locale (and the app's locale must follow the in-app language setting).

<details><summary>Element table 9.3 — every element, measured</summary>

| id | x,y w×h | kind | content | type | fg | paint | layout |
|---|---|---|---|---|---|---|---|
| r | 0,0 460×541.19 | shape |  |  |  | fill surface · r28 · shadow shadow-sm | col gap 22 pad 30 30 30 30 |
| r.0 | 30,30 400×17.05 | text | “Büyük harf testi” | Mono 400 11/17.05 ls0.88 UPPER | accent300 |  | row ai:center gap 8 |
| r.0.0 | 30,31.02 15×15 | icon | ph:text-aa (regular) | 15pt | accent300 |  |  |
| r.1 | 30,69.05 400×211.77 | stack |  |  |  |  | grid cols auto 1fr 1fr ai:center gap 14/18 |
| r.1.0 | 30,69.05 136.5×16.27 | text | “KAYNAK” | Mono 400 10.5/16.27 ls0.84 | text·68% |  |  |
| r.1.1 | 184.5,69.05 113.75×16.27 | text | “lang="tr"” | Mono 400 10.5/16.27 | online |  | row ai:center gap 6 |
| r.1.1.0 | 184.5,70.17 14×14 | icon | ph:check-circle (regular) | 14pt | online |  |  |
| r.1.2 | 316.25,69.05 113.75×16.27 | text | “dil verilmezse” | Mono 400 10.5/16.27 | text·68% |  |  |
| r.1.3 | 30,112.48 136.5×20.14 | text | “mesaj istekleri” | Mono 400 13/20.15 | text·68% |  |  |
| r.1.4 | 184.5,99.31 113.75×46.5 | text | “mesaj istekleri” | Inter 600 15/23.25 UPPER | #e9e9ed |  |  |
| r.1.5 | 316.25,99.31 113.75×46.5 | text | “mesaj istekleri” | Inter 600 15/23.25 UPPER | danger |  | row ai:center gap 6 |
| r.1.5.0 | 416,115.56 14×14 | icon | ph:x-circle (regular) | 14pt | danger |  |  |
| r.1.6 | 30,161.36 136.5×20.14 | text | “iletildi” | Mono 400 13/20.15 | text·68% |  |  |
| r.1.7 | 184.5,159.81 113.75×23.25 | text | “iletildi” | Inter 600 15/23.25 UPPER | #e9e9ed |  |  |
| r.1.8 | 316.25,159.81 113.75×23.25 | text | “iletildi” | Inter 600 15/23.25 UPPER | danger |  | row ai:center gap 6 |
| r.1.8.0 | 381.48,164.44 14×14 | icon | ph:x-circle (regular) | 14pt | danger |  |  |
| r.1.9 | 30,210.23 136.5×20.14 | text | “gizli kodu gir” | Mono 400 13/20.15 | text·68% |  |  |
| r.1.10 | 184.5,197.06 113.75×46.5 | text | “gizli kodu gir” | Inter 600 15/23.25 UPPER | #e9e9ed |  |  |
| r.1.11 | 316.25,197.06 113.75×46.5 | text | “gizli kodu gir” | Inter 600 15/23.25 UPPER | danger |  | row ai:center gap 6 |
| r.1.11.0 | 416,213.31 14×14 | icon | ph:x-circle (regular) | 14pt | danger |  |  |
| r.1.12 | 30,259.11 136.5×20.14 | text | “İLETİLEMEDİ” | Mono 400 13/20.15 | text·68% |  |  |
| r.1.13 | 184.5,257.56 113.75×23.25 | text | “İLETİLEMEDİ” | Inter 600 15/23.25 | #e9e9ed |  |  |
| r.1.14 | 316.25,257.56 113.75×23.25 | text | “İLETİLEMEDİ” | Inter 600 15/23.25 | danger |  | row ai:center gap 6 |
| r.1.14.0 | 396.5,262.19 14×14 | icon | ph:x-circle (regular) | 14pt | danger |  |  |
| r.2 | 30,302.81 400×2 | shape |  |  |  | fill radial-gradient(circle, text·22% 0.75px, transparent 1.1px) 0 50% / 5px 2px repeat-x |  |
| r.3 | 30,326.81 400×184.38 | stack |  |  |  |  | col gap 10 |
| r.3.0 | 30,326.81 400×42 | text | “i ↔ İ ve ı ↔ I eşleşmesi dile bağlıdır. Dil verilmeden büyütülen “i…” | Inter 400 14/21 | text·68% |  |  |
| r.3.1 | 30,378.81 400×38.38 | text | “Text(key).textCase(.uppercase)s.uppercased(with: Locale(identifier:…” | Mono 400 12/19.2 | text |  |  |
| r.3.1.0 | 282,380.81 0×14 | box |  |  |  |  |  |
| r.3.2 | 30,427.19 400×84 | text | “SwiftUI’da textCase ortam dilini kullanır; ham String dönüşümlerind…” | Inter 400 14/21 | text·68% |  |  |

</details>
