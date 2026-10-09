# us2us iOS — icons

Icon set: **Phosphor 2.1.1** (the design file loads `@phosphor-icons/web@2.1.1` regular + fill web fonts). The exact SVGs for every glyph the iOS screens use are copied into `icons/phosphor/regular/<name>.svg` and `icons/phosphor/fill/<name>.svg` (from `@phosphor-icons/core@2.1.1`, MIT — `icons/phosphor/LICENSE`). Bundle **only these 57** (50 regular + 7 fill) as template images / SF-Symbol-style assets (single colour, `currentColor` → `.renderingMode(.template)`), keyed by `<name>` + weight.

Sizing rule: the CSS `font-size` of a Phosphor glyph equals its square box. Draw the SVG (viewBox 256) in an N×N pt frame for “N pt”. Colour comes from the token given in the inventory (mostly `text`, `text·68%`, `accent300`, `danger`, `online`).

Not Phosphor:

* **Face ID glyph** — custom 24×24 stroke path, `icons/custom/face-id.svg` (`M2 7V5a3 3 0 0 1 3-3h2M17 2h2a3 3 0 0 1 3 3v2M22 17v2a3 3 0 0 1-3 3h-2M7 22H5a3 3 0 0 1-3-3v-2M8.5 9v1.5M15.5 9v1.5M12 9v4.5h-1M9 16.5c1.7 1.3 4.3 1.3 6 0`, round caps/joins). Stroke width varies by size: 56 pt @ 1.3 (3.1 disc), 30 pt @ 1.6 (keypad key), 22 pt @ 1.7 (2.3 card), 18 pt @ 1.8 (settings tile). Scale the stroke with the glyph: stroke = value × size/24 in pt. Prefer this over SF Symbol `faceid` (the design draws its own).
* **Line mark** (two rings + bar), **waveform** (31 rounded bars, 2.4 wide, pitch 4.4, 28 high), **status-bar glyphs** (system) and the **QR code** (generated) are drawn in code — see inventory C20, 5.1.
* `text-aa` appears only on the 9.3 design note card — do not bundle.

| icon | weight | sizes (pt) | screens | where / meaning | glyph |
|---|---|---|---|---|---|
| `arrow-bend-up-left` | regular | 13, 19 | 5.1, 5.3, 8.2, 9.2 | reply-quote author line in bubbles (13); “Yanıtla” in the action menu (19) | U+E024 |
| `backspace` | regular | 28 | 2.2, 3.2, 8.3 | keypad delete key (28) | U+E0AE |
| `camera` | regular | 22 | 5.6 | screenshot-detected warning (5.6) | U+E10E |
| `caret-left` | regular | 20, 21, 22 | 2.1, 2.2, 2.3, 4.3, 4.4, 5.1, 5.2, 5.3, 6.1, 7.1, 7.2, 8.2, 9.2 | back buttons (22 setup, 21 nav, 20 chat header) | U+E138 |
| `caret-right` | regular | 15 | 3.3, 4.1, 4.2, 7.1, 8.1, 9.1 | row chevrons, `text·40%` (15) | U+E13A |
| `chat-circle-dots` | regular | 18 | 7.2 | backup contents “Mesajlar” tile (18) | U+E16C |
| `check` | regular | 14, 15, 16, 17 | 1.2, 2.3, 4.1, 5.1, 5.3, 5.4, 7.3, 8.1, 8.2, 9.1, 9.2 | status “Gönderildi/Sent” (14–17); checkbox tick (16, `bg` on `accent`); restore step done (15, `online`) | U+E182 |
| `check-circle` | regular | 16 | 2.1 | “bulunur / found” in the exact-match example (16, `online`) | U+E184 |
| `checks` | regular | 15, 17, 18 | 1.2, 4.1, 5.1, 5.4, 7.1, 8.1, 8.2, 9.1, 9.2 | status “İletildi/Delivered” (15–17); “Okundu bilgisi” settings tile (18) | U+E53A |
| `circle` | regular | 15 | 7.3 | restore step pending (15) | U+E18A |
| `circle-half` | regular | 18 | 7.1 | “Aktif durumu” settings tile (18) | U+E18C |
| `circle-notch` | regular | 15 | 7.3 | restore step in progress — rotates (15); proposed generic spinner | U+EB44 |
| `clock` | regular | 14 | 6.3 | verified-at pill (14) | U+E19A |
| `cloud-slash` | regular | 21 | 2.3 | “Kod sunucuya hiç gönderilmez” (21) | U+E1B6 |
| `copy` | regular | 19 | 5.3 | “Kopyala” in the action menu (19) | U+E1CA |
| `dots-three` | regular | 20, 21 | 4.3, 4.4 | overflow buttons on requests / request preview (21 / 20) | U+E1FE |
| `export` | regular | 20 | 7.2 | “Dışa aktar” button (20) | U+EAF0 |
| `eye` | regular | 20 | 7.2 | reveal password (20) | U+E220 |
| `eye-slash` | regular | 13, 18 | 4.3, 5.6 | “Medya kabul edene kadar gizli” flag (13); “Kapatınca silinir” (18) | U+E224 |
| `file-lock` | regular | 18, 21, 26 | 2.3, 3.3, 7.1, 7.2, 7.3 | encrypted backup everywhere: 2.3 row (21), 3.3 option (21), settings tile (18), backup file tile (26) | U+E95C |
| `file-pdf` | regular | 25 | 5.2 | file bubble type tile (25) | U+E702 |
| `gear-six` | regular | 18 | 7.2 | backup contents “Ayarlar ve kişiler” (18) | U+E272 |
| `ghost` | regular | 12, 14, 15, 16, 18 | 1.3, 4.1, 5.1, 5.2, 5.5, 7.1, 8.1, 8.2, 9.1, 9.2 | Ghost (calm): list pill (12), system pill (14), kicker (15/16), header chip (16), off chip (16), settings tile (18) | U+E62A |
| `image` | regular | 14, 18 | 5.1, 7.2, 8.2, 9.2 | reply-quote kind “Fotoğraf” (14); backup contents “Fotoğraf ve videolar” (18) | U+E2CA |
| `info` | regular | 19 | 5.3 | “Bilgi” in the action menu (19) | U+E2CE |
| `lightning` | regular | 21 | 6.2 | torch toggle on the scanner (21) | U+E2DE |
| `link-break` | regular | 16 | 4.4 | inert link in a request bubble (16) | U+E2E4 |
| `lock-simple` | regular | 13, 18, 24 | 3.2, 3.3, 3.4, 7.1, 8.3 | lock badge (24); “Kilitli” on the app-switcher cover (13); “Otomatik kilit” tile (18) | U+E308 |
| `microphone` | regular | 21 | 5.1, 5.2, 8.2, 9.2 | composer mic button (21, `accent300`) | U+E326 |
| `moon` | regular | 18 | 7.1 | “Tema” settings tile (18) | U+E330 |
| `navigation-arrow` | regular | 15 | 4.1, 5.2, 8.1, 9.1 | live location (15) in list preview and bubble | U+EADE |
| `note-pencil` | regular | 21 | 4.1, 8.1, 9.1 | new-chat button on the chat list (21, `accent300`) | U+E34C |
| `paper-plane-tilt` | regular | 20 | 4.2 | “Mesaj yaz” button (20); proposed send button in the composer | U+E398 |
| `password` | regular | 18, 20 | 3.1, 7.1 | “Kodla aç” button (20); “Gizli kodu değiştir” tile (18) | U+E752 |
| `plus` | regular | 18, 22 | 5.1, 5.2, 5.3, 8.2, 9.2 | composer attach (22); “more reactions” (18) | U+E3D4 |
| `prohibit` | regular | 15, 16, 21 | 2.3, 4.1, 5.2, 8.1, 9.1 | “Kod sıfırlama yok” (21, `danger`); deleted-message tombstone (15/16) | U+E3DE |
| `qr-code` | regular | 18, 20, 21 | 4.1, 4.2, 6.2, 7.1, 8.1, 9.1 | QR entry points: chat list (21), “Kodumu göster” tile (18), scanner pill (18), profile card (20) | U+E3E6 |
| `scan` | regular | 18, 20 | 4.2, 6.1 | “QR kodla ekle” tile (18); “QR kodu okut” button (20) | U+EBB6 |
| `share-network` | regular | 21 | 6.1 | share safety number (21) | U+E408 |
| `shield-check` | regular | 22 | 4.3 | requests privacy banner (22) | U+E40C |
| `sticker` | regular | 15, 21 | 4.1, 5.1, 5.2, 8.1, 8.2, 9.1, 9.2 | sticker preview in list (15); composer sticker button (21) | U+E5AC |
| `timer` | regular | 13, 15, 18, 21 | 1.3, 4.1, 5.1, 5.2, 5.3, 7.1, 8.1, 8.2, 9.1, 9.2 | disappearing messages: list (15), bubble badge (13), composer (21), feature list (18), settings tile (18) | U+E492 |
| `translate` | regular | 18 | 7.1 | “Dil” settings tile (18) | U+E4A2 |
| `trash` | regular | 18, 19, 21 | 1.3, 3.3, 5.3 | delete for everyone (19); erase everything (21); feature list (18) | U+E4A6 |
| `trash-simple` | regular | 19 | 5.3 | “Benden sil” (19) | U+E4A8 |
| `tray` | regular | 18, 21 | 4.1, 4.2, 8.1, 9.1 | message requests (21 tile on the list, 18 in 4.2 info row) | U+E4AA |
| `warning` | regular | 13, 18 | 4.3, 7.2 | “Bağlantı içeriyor” flag (13, `danger`); backup-password warning (18, `accent300`) | U+E4E0 |
| `warning-circle` | regular | 15, 17, 22 | 1.2, 4.1, 5.4, 8.1, 9.1 | status “İletilemedi/Not delivered” (15–17, `danger`); 5.4 note (22) | U+E4E2 |
| `x` | regular | 17, 21 | 5.4, 5.6, 6.2, 6.3, 7.3 | close buttons (21; 17 in sheets) | U+E4F6 |
| `x-circle` | regular | 16 | 2.1 | “bulunamaz / not found” in the exact-match example (16, `danger`) | U+E4F8 |
| `check-circle` | fill | 20, 22 | 2.1, 4.2, 7.3 | positive field state: username available, user found, backup/password OK (20–22, `online`) | U+E184 |
| `checks` | fill | 15, 17 | 1.2, 4.1, 5.1, 5.2, 5.4, 8.1, 8.2, 9.1, 9.2 | status “Okundu/Read” (15–17, `accent300`) | U+E53A |
| `ghost` | fill | 12, 16, 18 | 4.1, 5.1, 8.1, 9.1 | Ghost **urgent** (< 1 h): list pill (12), header chip (16), banner (18) — `danger` | U+E62A |
| `map-pin` | fill | 24 | 5.2 | location bubble pin (24, `accent300`, drop shadow) | U+E316 |
| `play` | fill | 15, 19 | 5.1, 5.2, 8.2, 9.2 | voice message play (15), video play (19) | U+E3D0 |
| `seal-check` | fill | 14, 15, 22 | 4.1, 5.1, 6.3, 8.1, 8.2, 9.1, 9.2 | verified badge: list (14), chat header (15), verified seal on the line (22) — `accent` | U+E606 |
| `x-circle` | fill | 20 | 4.2 | clear the search field (20, `text·40%`) | U+E4F8 |

Total: 57 glyph/weight combinations.
