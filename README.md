# us2us

**İki uç. Bir hat.** · **Two ends. One line.**

[Türkçe](#türkçe) · [English](#english)

---

## Türkçe

### Neden us2us?

İletişim, insanın en temel özgürlüklerinden biridir. Birine bir şey söylediğinde
bu söz, yalnızca söylediğin kişiye ait olmalı. Ne bir şirkete, ne bir reklam
ağına, ne de aradaki bir sunucuya.

us2us'ı **SyconX** bu inançla geliştirdi: iki insan arasında, araya kimsenin
giremediği özel bir hat. Çocukken iki plastik bardağı bir iple bağlayıp
konuştuğumuz o ilk telefon gibi: bir uçta sen, öbür uçta o.

us2us **özgür ve açık kaynaklı** bir yazılımdır. Kodun tamamı burada. "Bu
uygulama gerçekten mesajlarımı okuyamıyor mu?" sorusunun cevabını bize
güvenerek değil, kendin bakarak bulabilmen için.

### Seni nasıl korur?

- **Telefon numarası yok.** Kayıt olmak ve birini bulmak için yalnızca kullanıcı
  adı yeter. Sunucu kullanıcı adını bile düz metin olarak görmez; yalnızca
  yavaş hesaplanan bir özetini (hash) saklar. Arama tam eşleşme ister, öneri
  ya da otomatik tamamlama yoktur.
- **Uçtan uca şifreleme.** Mesajlar Signal Protokolü'nün kuantum sonrası
  sürümüyle (libsignal: PQXDH ve üçlü ratchet) yalnızca senin telefonunda
  kilitlenir, yalnızca karşı tarafın telefonunda açılır.
- **Kimin kime yazdığı gizli.** Gönderen bilgisi de şifreli zarfın içindedir
  (sealed sender). Sunucu bir paketin kime gittiğini bilir, kimden geldiğini
  bilmez.
- **Unutan bir sunucu.** Aradaki sunucu (kör aktarıcı) yalnızca kapalı zarfı
  taşır. İçini göremez, anahtarlara dokunamaz. Zarf teslim edilince silinir;
  teslim edilemezse en fazla 14 gün bekler ve yine silinir.
- **Bildirim yok. Bilerek.** Bildirimler Apple ve Google sunucularından geçer
  ve kimin ne zaman mesaj aldığını onlara söyler. us2us bildirim kullanmaz;
  mesajların uygulamayı açtığında gelir.
- **Konuşmalar yalnızca iki telefonda.** Bulut yedeği yok, sunucuda geçmiş yok.
- **Gizli kodla kilitli.** Telefondaki her şey, belirlediğin 6 haneli gizli kod
  ve telefondan hiç çıkmayan bir donanım sırrıyla şifrelenir. Kod unutulursa
  veriler geri gelmez. Bu bir eksiklik değil, sözümüzün parçası.
- **Hayalet mod.** Sessiz kalan bir sohbet, seçtiğin sürenin sonunda iki
  telefondan da iz bırakmadan silinir. Her yeni mesaj sayacı baştan başlatır.
- **Süreli mesajlar, tek görüntülemelik medya, herkesten silme.**
- **Güvenlik numarası.** Yan yana geldiğinizde QR kodu okutarak hatta
  ikinizden başkası olmadığını doğrulayabilirsiniz.
- **Şifreli hesap yedeği.** Hesabını ve kişilerini ayrı bir yedek parolasıyla
  şifrelenmiş tek bir dosyaya aktarabilirsin. Dosyanın nerede duracağına sen
  karar verirsin; mesajlar yedeğe hiç girmez.
- **Davet ederken rehberine dokunmaz.** Arkadaşını SMS, WhatsApp ya da e-postayla
  davet edebilirsin. Alıcıyı sistemin kendi ekranında sen seçersin; us2us
  kişi listeni okumaz, hiçbir yere yüklemez.

### Dürüst olmak gerekirse

Hiçbir sistem her şeyi gizleyemez. Sunucu, bağlantı sırasında IP adresini,
bir zarfın hangi hesaba gittiğini, zarfın boyutunu ve zamanını görebilir.
Karşı taraf ekranın fotoğrafını çekebilir. Neyin görülüp neyin görülmediğinin
eksiksiz listesi [docs/PROTOCOL.md §9](docs/PROTOCOL.md) içinde.

### Depo haritası

| Klasör | İçerik |
|---|---|
| `ios/App` | iOS uygulaması (SwiftUI) |
| `ios/Us2usKit` | iOS protokol ve kripto kütüphanesi |
| `ios/Us2usAppCore` | iOS gizlilik çekirdeği: şifreli veritabanı, kasa, bağlantı yönetimi |
| `android/` | Android protokol ve kripto kütüphanesi (Kotlin); Android uygulaması geliştiriliyor |
| `relay/` | Kör aktarıcı sunucu ve `us2us.app` web sitesi (Nuxt, MySQL) |
| `docs/` | Protokol (`PROTOCOL.md`), mimari (`ARCHITECTURE.md`), davet bağlantıları |
| `spec/` | Şifreli içerik şeması ve iki platformun birbirini doğruladığı test vektörleri |
| `scripts/` | Uçtan uca birlikte çalışma testi ve sunucu dağıtım betiği |

Her bölümün nasıl derleneceği kendi `README.md` dosyasında anlatılıyor.

### Katkı ve güvenlik

Katkılarını bekliyoruz: hata bildirimi, çeviri, kod ya da protokol incelemesi.
Bir güvenlik açığı bulursan lütfen herkese açık bir kayıt açmadan önce
**security@us2us.app** adresine yaz.

### Lisans

us2us, [GNU Affero Genel Kamu Lisansı v3.0](LICENSE) ile lisanslanmıştır.
© 2026 SyconX

---

## English

### Why us2us?

Being able to talk freely is one of our most basic freedoms. When you say
something to someone, it should belong to that person alone: not to a company,
not to an ad network, not to a server in between.

**SyconX** built us2us on that belief: a private line between two people, with
no one in between. Like the very first telephone we made as kids, two plastic
cups joined by a string: you at one end, them at the other.

us2us is **free and open-source software**. All of the code is here, so you can
answer "can this app really not read my messages?" by looking for yourself
instead of taking our word for it.

### How it protects you

- **No phone number.** A username is all you need to sign up and to find
  someone. The server never sees usernames in plain text, only a slow hash of
  them. Search needs an exact match: no suggestions, no autocomplete.
- **End-to-end encryption.** Messages are sealed on your phone and opened only
  on theirs, with the post-quantum version of the Signal Protocol (libsignal:
  PQXDH and the triple ratchet).
- **Who talks to whom stays private.** The sender is inside the encrypted
  envelope too (sealed sender). The server knows where a package goes, not
  where it comes from.
- **A server that forgets.** The relay in between only carries sealed
  envelopes. It cannot look inside or touch the keys. An envelope is deleted
  once it is delivered, and after 14 days at the latest if it never is.
- **No notifications. On purpose.** Notifications pass through Apple's and
  Google's servers and tell them who receives messages and when. us2us uses no
  push notifications; your messages arrive when you open the app.
- **Conversations live only on the two phones.** No cloud backup, no history on
  the server.
- **Locked with a secret code.** Everything on the phone is encrypted with your
  6-digit secret code combined with a hardware secret that never leaves the
  device. Forget the code and the data is gone. That is not a flaw; it is part
  of the promise.
- **Ghost mode.** A conversation that stays quiet is wiped from both phones
  without a trace when the time you chose runs out. Every new message restarts
  the timer.
- **Disappearing messages, view-once media, delete for everyone.**
- **Safety numbers.** When you are together, scan each other's QR code to make
  sure it is only the two of you on the line.
- **Encrypted account backup.** Export your account and contacts to a single
  file locked with a separate backup password. You decide where it is kept;
  messages are never included.
- **Invites that never touch your contacts.** Invite a friend by SMS, WhatsApp
  or e-mail. You pick the recipient in the system's own screen; us2us never
  reads or uploads your address book.

### To be honest

No system can hide everything. While you are connected, the server can see your
IP address, which account an envelope is addressed to, and its size and timing.
The person you talk to can photograph the screen. The complete list of what is
and is not visible is in [docs/PROTOCOL.md §9](docs/PROTOCOL.md).

### Repository map

| Folder | Contents |
|---|---|
| `ios/App` | The iOS app (SwiftUI) |
| `ios/Us2usKit` | iOS protocol and crypto library |
| `ios/Us2usAppCore` | iOS privacy core: encrypted database, vault, connection management |
| `android/` | Android protocol and crypto library (Kotlin); the Android app is in progress |
| `relay/` | The blind relay and the `us2us.app` website (Nuxt, MySQL) |
| `docs/` | The protocol (`PROTOCOL.md`), architecture (`ARCHITECTURE.md`), invite links |
| `spec/` | The encrypted content schema and cross-platform test vectors |
| `scripts/` | End-to-end interoperability test and the relay deployment script |

Each part explains how to build it in its own `README.md`.

### Contributing and security

Contributions are welcome: bug reports, translations, code and protocol review.
If you find a security issue, please write to **security@us2us.app** before
opening a public issue.

### License

us2us is licensed under the [GNU Affero General Public License v3.0](LICENSE).
© 2026 SyconX
