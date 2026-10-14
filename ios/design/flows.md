# us2us iOS — flows and navigation

Screen ids refer to `inventory.md` / `reference/`. “(proposed)” marks a step the design implies but does not draw; everything else is drawn or stated in a designer note. Motion names (`curveSoft`, `springSoft`, `screenPush` …) are defined in `tokens.json › motion` — every transition is meant to be very soft (no bounce, no overshoot).

## 1 · Map

```mermaid
flowchart TD
    subgraph Onboarding["01 · Onboarding (paged)"]
        W1["1.1 Karşılama"] -->|Başla / swipe| W2["1.2 Bildirim yok"]
        W2 -->|Anladım / swipe| W3["1.3 Sessizlik siler"]
    end
    W1 -->|Yedekten geri yükle| PICK["Document picker (system)"]
    PICK --> R["7.3 Geri yükle"]

    subgraph Setup["02 · Setup (push)"]
        S1["2.1 Kullanıcı adı"] -->|Devam| S2["2.2 Gizli kod — set"]
        S2 -->|6 digits, auto| S2b["2.2 Gizli kod — confirm (proposed copy)"]
        S2b -->|match| S3["2.3 Geri dönüş yok"]
        S2b -->|mismatch| S2
    end
    W3 -->|Hesabımı oluştur| S1
    S3 -->|checkbox + Kurulumu tamamla| CHATS
    S3 -->|name taken: Başka bir ad seç| S1
    R -->|done (new device)| S2

    subgraph Lock["03 · Lock (full-screen cover over everything)"]
        L1["3.1 Face ID"] -->|fail / cancel / Kodla aç| L2["3.2 Kod"]
        L2 -->|Face ID key| L1
        L2 -->|Kodu unuttum| L3["3.3 Kodu unuttum (sheet)"]
        L3 -->|Tekrar dene| L2
        L3 -->|Yedekten geri yükle| R
        L3 -->|Her şeyi sil → 2nd confirm| WIPE["wipe → 1.1"]
    end
    L1 -->|success| APP["app content (last screen)"]
    L2 -->|correct code| APP

    subgraph Main["04–07 · App (NavigationStack rooted at 4.1)"]
        CHATS["4.1 Sohbetler"] -->|row| CHAT["5.1 Sohbet"]
        CHATS -->|Mesaj istekleri| REQ["4.3 Mesaj istekleri"]
        REQ -->|card| PREV["4.4 İstek önizleme"]
        PREV -->|Kabul et| CHAT
        CHATS -->|note-pencil| NEW["4.2 Yeni sohbet (sheet)"]
        NEW -->|Mesaj yaz| CHAT
        NEW -->|QR kodla ekle| SCAN["6.2 QR tarama"]
        CHATS -->|qr-code| QRMENU["my code / scan (proposed)"]
        CHATS -->|self avatar| SET["7.1 Ayarlar"]
        CHAT -->|Ghost chip| GHOST["5.5 Ghost (sheet)"]
        CHAT -->|long-press bubble| ACT["5.3 Eylemler (overlay)"]
        ACT -->|Bilgi| INFO["5.4 Mesaj bilgisi (sheet)"]
        CHAT -->|view-once bubble| VO["5.6 Tek görüntülemelik (full screen)"]
        CHAT -->|header name (proposed)| SAFE["6.1 Güvenlik numarası"]
        SAFE -->|QR kodu okut| SCAN
        SCAN -->|match| OK["6.3 Doğrulandı"]
        SAFE -->|Doğrulandı olarak işaretle| OK
        OK -->|Sohbete dön| CHAT
        SET -->|Yedek| BK["7.2 Yedek"]
        BK -->|Dışa aktar| SHARE["Share / Files (system)"]
    end
```

## 2 · App states (root)

| state | shows | enters when | leaves when |
|---|---|---|---|
| `onboarding` | 1.1 → 1.3 → 2.x | no account/vault on the device | setup finished (→ `unlocked`) or restore finished (→ set code → `unlocked`) |
| `locked` | 3.1 (Face ID enabled) or 3.2 | **every launch** and **every return from background** (by design; no grace period) | correct code / Face ID success |
| `unlocked` | NavigationStack at 4.1 (restores the last screen after unlock) | unlock | app goes inactive |
| `privacyCover` | 3.4 cover | `scenePhase` becomes `.inactive` or `.background` (instantly, before the snapshot) | unlock completes (cover dissolves 0.36 s) |
| `wiping` | — | 3.3 “Her şeyi sil” + 2nd confirmation | → `onboarding` (1.1) |

The lock UI is presented **above** the navigation stack (full-screen cover, not a push) so that the stack and scroll positions survive. While locked, no content is rendered behind the cover (render the cover first, then tear down sensitive views if needed).

## 3 · Transitions (all screens)

| from | trigger | to | presentation | motion |
|---|---|---|---|---|
| 1.1 | “Başla” or swipe ← | 1.2 | paged (TabView `.page`, no indicator — C9 dots are custom) | cross-fade + 16 pt drift, 0.5 s `curveSoft`; dots: active pill width 6→22 morph 0.36 s |
| 1.2 | “Anladım” or swipe ← | 1.3 | paged | same |
| 1.3 | “Hesabımı oluştur” | 2.1 | push (custom, no system back swipe needed but allowed) | `screenPush` |
| 1.x | swipe → | previous page | paged | same |
| 1.1 | “Yedekten geri yükle” | system document picker → 7.3 | sheet (system) → push | system / `screenPush` |
| 2.1 | “Devam” (enabled when available) | 2.2 | push | `screenPush` |
| 2.2 | 6th digit (set) | 2.2 confirm | same screen, content swap | title/paragraph cross-fade 0.36 s, dots reset 0.24 s, after a 0.25 s hold |
| 2.2 | 6th digit (confirm, match) | 2.3 | push | `screenPush` |
| 2.2 | weak code / mismatch | 2.2 set | in place | dots → `danger` + soft shake 0.45 s, message fade 0.36 s, reset after 0.8 s |
| 2.x | back button / edge swipe | previous step | pop | `screenPush` reversed |
| 2.3 | “Kurulumu tamamla” | 4.1 | replace root | cross-fade 0.5 s `curveSoft` |
| 2.3 | name taken → “Başka bir ad seç” (proposed) | 2.1 (the code kept; “Devam” → 2.3) | pop to 2.1 | `screenPush` reversed |
| launch / foreground | — | 3.1 or 3.2 | full-screen cover | cover present instantly; content inside fades in 0.36 s |
| 3.1 | Face ID success | last app screen | cover dismiss | dissolve 0.36 s |
| 3.1 | failure / cancel / “Kodla aç” | 3.2 | in-cover swap | cross-fade 0.36 s |
| 3.2 | correct code | last app screen | cover dismiss | dissolve 0.36 s |
| 3.2 | wrong code | 3.2 error | in place | dots `danger` + shake (no shake with Reduce Motion) |
| 3.2 | 3rd consecutive wrong code (copy) | 3.2 locked-out | in place | keypad fades to 45 % 0.36 s, live `mm:ss` |
| 3.2 | Face ID key | 3.1 scan | in-cover swap | cross-fade |
| 3.2 | “Kodu unuttum” | 3.3 | floating sheet | backdrop + lock content dim 0.36 s; sheet `springSoft` |
| 3.3 | “Tekrar dene” / swipe down | 3.2 | dismiss | reverse |
| 3.3 | “Yedekten geri yükle” | document picker → 7.3 | sheet → push inside the cover | — |
| 3.3 | “Her şeyi sil, baştan başla” | destructive confirmation (proposed alert) → wipe → 1.1 | alert → replace root | cross-fade 0.5 s |
| any | app inactive | 3.4 cover | overlay | **no animation** (must be in the snapshot) |
| 4.1 | chat row | 5.1 | push | `screenPush` |
| 4.1 | requests card | 4.3 | push | `screenPush` |
| 4.1 | `note-pencil` | 4.2 | sheet (full height, top at y 58) | system sheet or custom `springSoft` + backdrop fade |
| 4.1 | `qr-code` | menu → 6.2 scan / my code (proposed) | menu → full-screen cover | fade 0.36 s |
| 4.1 | self avatar | 7.1 | push | `screenPush` |
| 4.2 | “Vazgeç” / swipe down | 4.1 | dismiss | reverse |
| 4.2 | “Mesaj yaz” | 5.1 (new, pending request) | dismiss sheet then push | sheet down 0.4 s, then `screenPush` |
| 4.2 | “QR kodla ekle” | 6.2 | full-screen cover | fade 0.36 s |
| 4.2 | “Kodumu göster” | my QR card (proposed: 6.1 layout without the peer) | push inside the sheet | `screenPush` |
| 4.3 | card | 4.4 | push | `screenPush` |
| 4.3 | Engelle / Sil | — (card removed) | in place | fade + collapse 0.36 s |
| 4.3 / 4.4 | Kabul et | 5.1 | replace (4.4 morphs into 5.1) | decision bar slides down 0.4 s, composer fades in |
| 5.1 | back | 4.1 | pop | reverse |
| 5.1 | Ghost chip | 5.5 | sheet (content height) | `springSoft` + backdrop |
| 5.5 | “Kaydet” | 5.1 + system line | dismiss | reverse; system line fades in 0.36 s |
| 5.1 | long-press bubble | 5.3 | overlay (context menu) | bubble lift 0.3 s, blur/dim 0.3 s, menu fade+scale from 0.96 |
| 5.3 | tap outside | 5.1 | dismiss | 0.24 s reverse |
| 5.3 | “Yanıtla” | 5.1 composer with quote | dismiss | quote block slides up into the composer 0.3 s |
| 5.3 | “Bilgi” | 5.4 | dismiss menu, present sheet (full height) | `springSoft` |
| 5.3 | “Herkesten sil” | confirm (proposed) → tombstone | alert → in place | bubble cross-fades into the tombstone 0.36 s |
| 5.3 | “Benden sil” | — | in place | fade + collapse 0.36 s |
| 5.4 | close / swipe down | 5.1 | dismiss | reverse |
| 5.1/5.2 | view-once bubble | 5.6 | full-screen cover (always dark) | fade 0.36 s |
| 5.6 | close | 5.1 (bubble → “Açıldı”) | dismiss | fade 0.36 s |
| 5.1 | header name/avatar (proposed) | 6.1 | push | `screenPush` |
| 6.1 (contact screen) | “…” › Engelle (proposed) | alert → blocked in place | alert | note drops in, mark fades in 0.36 s |
| 6.1 (contact screen) / 4.4 | “…” › Bildir (proposed) | report sheet | sheet (full height) | `springSoft` + backdrop |
| report sheet | “E-postayla gönder” | Mail / another mail app / the address to copy | system sheet / outside the app / in place | — / — / cross-fade 0.36 s |
| 5.1 (blocked) | “Engeli kaldır” (proposed) | 5.1 | in place | bar cross-fades into the composer 0.36 s |
| 6.1 | “QR kodu okut” | 6.2 | full-screen cover | fade 0.36 s |
| 6.1 | “Doğrulandı olarak işaretle” | confirmation (proposed) → 6.3 | alert → push | `screenPush` |
| 6.2 | match | 6.3 | replace cover content | cross-fade 0.5 s |
| 6.2 | mismatch | 6.2 + “Numaralar uyuşmuyor” | in place | warning card fade + 8 pt drop |
| 6.2 | close | 6.1 / 4.2 | dismiss | fade |
| 6.3 | “Sohbete dön” / close | 5.1 (seal in header) | dismiss to the chat | cross-fade 0.5 s |
| 7.1 | back | 4.1 | pop | reverse |
| 7.1 | “Yedek” | 7.2 | push | `screenPush` |
| 7.1 | other › rows | pickers (proposed, C13 cards with a check mark) | push | `screenPush` |
| 7.1 | “Gizli kodu değiştir” | 3.2-style current code → 2.2-style new ×2 | push | `screenPush` |
| 7.2 | “Dışa aktar” | system share / export sheet | sheet (system) | system |
| 7.3 | “İptal” | confirmation (proposed) → previous | alert → pop/dismiss | reverse |
| 7.3 | done (new device or after 3.3) | 2.2 set new code → 2.3 → 4.1 | push | `screenPush` |

## 4 · Sub-flows

### 4.1 Account creation (first launch)
1. 1.1 → 1.2 → 1.3 (paged). No notification permission is ever requested.
2. 2.1 username: local validation by PROTOCOL.md §3.1 — the normalized name (`White_Space` trimmed, one leading “@” dropped, NFKC, locale-independent lower case) must match `^[a-z][a-z0-9_]{2,23}$` (3–24, a letter first, `a-z 0-9 _`, no dot); nothing typed is filtered, cut or converted, every broken rule is shown — + availability check (debounced ~400 ms). “Devam” enabled only when available. The live service cannot ask the relay before the account exists (§4.4): a name that meets the rules shows “Kurallara uygun” and continues; a taken name shows at step 5.
3. 2.2 secret code: 6 digits → reject weak codes (all-equal, straight runs, simple repeats) → confirm → mismatch returns to set.
4. 2.3: Face ID toggle (default ON if available; enabling may show the system prompt), checkbox required.
5. “Kurulumu tamamla”: create identity keys, vault bound to the device secret + code, register username with the relay, then root = 4.1. On failure: inline error, stay on 2.3 (code already confirmed). Name taken (`username_taken`, 409): “Bu kullanıcı adı alınmış.” and the CTA becomes “Başka bir ad seç” → 2.1, the taken name still in the field and marked “Alınmış”; the confirmed code is kept, so “Devam” returns to 2.3 (proposed). Exception: when the failure leaves a vault on this phone — the account was saved before a later step failed, or a restore inside the lock was refused before anything was erased — retrying would fail, so the sentence says so, the CTA becomes “Kilit ekranına dön” (back and the checkbox disabled) and the lock appears at once (proposed; the design has no state for it).

### 4.2 Unlock (every launch and every foreground)
1. `scenePhase` → inactive: show 3.4 cover immediately.
2. → active: present the lock (3.1 if Face ID enabled and available, else 3.2).
3. 3.1 auto-starts `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)`; success → unlock; failure/cancel → 3.2.
4. 3.2: 6 digits auto-submit. Wrong → error state; attempts 1–2 free (copy counts the remaining), then 1 min wait, then increasing waits (proposed 1 → 5 → 15 → 60 min), persisted across relaunches; keypad disabled while waiting. No reset path.
5. 3.3 (forgot): restore (7.3) or erase-everything (2nd confirmation) → 1.1. After a restore/erase on a new install, contacts see “Güvenlik numarası değişti” in their chats.
6. Optional wipe after 10 wrong codes (off by default; PROTOCOL §5.2): the 10th wrong code in a row erases everything, and the lock shows it at once (proposed, no design state): 3.2's top block with a danger badge, “Veriler silindi.” and the sentence, and 3.3's two option cards on a `surface` panel — “Yedekten geri yükle” → 7.3 (the picker opens) and “Baştan başla” → 1.1. Never “Kod yanlış”. If the 10th wrong code was the current code of 7.1 › Gizli kodu değiştir, the lock rises over the erased app with the same screen. Harness: `lock:wiped`; the not-a-wrong-code sentences: `lock:unavailable`, `lock:unusable`.

### 4.3 Starting a conversation
* By username: 4.1 → 4.2 → exact lookup → “Mesaj yaz” → 5.1 (the first message becomes a request on the other side; neither side sees presence until accepted).
* By QR (side by side): 4.2 “QR kodla ekle” → 6.2 → adds the contact **and** verifies it (→ 6.3 → 5.1). The code carries the contact's identity-key fingerprint; that key is pinned, so a different key later is a safety-number change. A code without the fingerprint (older versions) adds the contact unverified (→ 5.1, no 6.3); a code whose key does not match shows “Numaralar uyuşmuyor”.
* Incoming: request appears in 4.1's requests card → 4.3 → 4.4 → Kabul et (→ 5.1) / Engelle / Sil. The sender never learns whether the request was seen, and no read receipt is sent until accepted.

### 4.4 Message lifecycle (as shown in 1.2, 5.1, 5.4)
`sending (local)` → **Gönderildi** (left the device; relay holds the sealed envelope) → **İletildi** (recipient opened the app and fetched it) → **Okundu** (recipient opened the chat; only when read receipts are on for both) — or → **İletilemedi** after 14 days without the recipient opening the app (message deleted from the relay; bubble offers “Tekrar gönder”, which queues it as a new message). Only the newest outgoing message shows the state as a sentence under the bubble.

### 4.5 Ghost mode & disappearing messages
* Per chat, both sides (5.5). Defaults from 7.1. Every change posts a system line in both chats.
* Ghost: if the chat is silent for the chosen period (1 h / 6 h / 24 h / 3 days / 7 days) it is erased on both ends; every new message restarts the timer. Shown: header chip (live `HH:MM:SS`), list pill (short form), urgent red styling + banner in the last hour.
* Disappearing messages (Off / 30 s / 5 min / 1 h / 1 day): timer starts when the recipient reads the message; copy is disabled for such messages (5.3); never backed up.
* View-once media: no preview; opening shows 5.6; closing deletes it on both sides; screenshots are detected and reported (system line to the sender); content hidden while the screen is captured.

### 4.6 Verification
6.1 shows the 60-digit safety number (12 × 5) and its QR. Side by side → 6.2 scan → match → 6.3 → header seal + list seal. Remote → compare digits over another channel → “Doğrulandı olarak işaretle”. If the peer's number changes later, the seal is removed and a warning system line is inserted (found on receive or while sending).

Proposed additions (the design has none; they reuse its components):
* 5.1, unverified chat with a known key: the first row of the history is a tappable system pill “Doğrulanmadı · QR ile doğrula” → 6.1; it scrolls away with the history and goes once verified.
* 5.1 while a changed number waits: a banner above the composer in the urgent banner's style, “Güvenlik numarası değişti. Karşılaştırana kadar mesajların gönderilmez. · Karşılaştır” → 6.1. It stays until the number is verified or accepted.
* 6.1 after a change: besides scanning and marking, “Doğrulamadan devam et” (with a confirmation) accepts the new number without the seal; the system line says “Yeni güvenlik numarası kabul edildi · doğrulanmadı”.
* Every decision on 6.1 is bound to the number on screen: if the number changed meanwhile, nothing is marked and the danger note says so. 6.1 reloads when the key changes. 6.3 appears only after a verification was stored.

### 4.7 Backup and restore
* Export (7.1 → 7.2): choose contents, set a **separate backup password** (must differ from the secret code; strength meter), export one `.u2u` file via the system share/export sheet (Files, AirDrop). View-once media and disappearing messages are never included.
* Restore (1.1 or 3.3 → 7.3): pick the file → backup password → on-device decryption with progress (keys → messages → media), resumable after relaunch → set a new 6-digit secret code on this device (2.2 ×2 → 2.3) → 4.1. Chats whose Ghost time ran out are not restored. Backing out of 2.2 after a restore that began on the lock (3.3) returns to the lock, never to 1.1 (the vault on this phone is still there).
* Generated backup password (7.2, proposed): its card shows the password hidden (`••••-••••-…`) until the eye shows it, hides it again when the app leaves the foreground and while the screen is recorded; “Kopyala” copies it to this device only, and “Kopyalandı · yalnızca bu cihazda, 1 dk sonra silinir” stays under the button until the copy expires. Harness: `settings:7.2-generated`, `7.2-revealed`, `7.2-copied`.
* After a restore whose token rotation got no answer (PROTOCOL §5.5 step 3.4; proposed): 7.1 opens with a danger-ringed notice “Eski telefonun erişimi kapanmamış olabilir” and “Şimdi kontrol et”; 4.1 shows a card in the requests card's place that opens 7.1. Both go once the outcome is known (toast “Eski telefonun erişimi kapandı.”, or — the backup was restored elsewhere first — the line “Hat kapandı · oturum sona erdi”). Harness: `settings:7.1-access`, `7.1-access-checking`, `7.1-access-unanswered`.

### 4.8 Blocking and reports (proposed; App Review 1.2)
* Block: 5.1 › the person → contact screen › “…” › Engelle → “{user} engellensin mi?” (the 4.3 / 4.4 alert: Engelle · Bildir · Vazgeç). Blocked: the note on Doğrulama and the mark by the name, “Engellendi” in 4.1's row and 5.1's header, 5.1's composer replaced by a bar with “Engeli kaldır”. Nothing reaches the person and nothing of theirs arrives (PROTOCOL §6.4); they are not told. A request blocked in 4.3 / 4.4 stays in 4.1 as a blocked chat; unblocking makes it a request again.
* Unblock: the contact screen's “…” › Engeli kaldır, its note, or 5.1's bar — no confirmation.
* Report: the contact screen's “…” › Bildir, 4.4's “…” › Bildir, or Bildir in any block confirmation → the report sheet (reason, optional note, “Bu kişiyi de engelle” on) → “E-postayla gönder”: Mail inside the app, else another mail app (`mailto:`), else the address support@us2us.app to copy. Only the username, the reason, the note, the app version and the UTC time go into the e-mail — never message content. The block follows only a report that left the sheet (sent, handed off, or the address shown); from 4.4 the sheet then closes back to 4.3.

## 5 · Back behaviour & gestures
* Edge-swipe back is allowed on every pushed screen except during setup step 2.2 confirm (back returns to the set step instead) and inside the lock cover (no back).
* Sheets dismiss with swipe-down (4.2, 5.4, 5.5); 3.3 also with the grabber; 5.6 / 6.2 only with the close button (no swipe — avoids accidental closing of view-once media).
* Long-press = message actions (5.3); no swipe actions on chat rows (not in the design).
