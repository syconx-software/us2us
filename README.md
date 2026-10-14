# us2us

**İki uç. Bir hat.** · **Two ends. One line.**

[Türkçe](#türkçe) · [English](#english)

---

## Türkçe

### Bazı sözler yalnızca bir kişi içindir.

Bir zamanlar bir SMS'i sabırla beklerdik. Telefon masada titrediğinde içimizde
bir şey kıpırdardı; aradaki o sessizlik, söylenen sözü daha değerli kılardı.

Bugün telefonlarımız hiç susmuyor. Kiminle, ne zaman, nereden konuştuğumuz bir
yerlerde kayda geçiyor; bazı mesajlarımızı da onlara hiç yazmadığımız kişiler
okuyor.

Çoğumuzun ilk telefonu iki bardak ile bir parça ipti. Bardağın birine
fısıldardık, ip gerilir, sesimiz öbür uçtaki arkadaşımıza giderdi; başka hiç
kimseye. Logomuzdaki iki halka ve onları birleştiren çizgi o bardaklar ve o ip.
us2us'ı yaparken hep onu düşündük: **iki uç, bir hat, arada kimse yok.**

### Dinlemek yanlıştır

“Saklayacak neyim var ki?” Biz soruyu tersinden soruyoruz: Gece yarısı
sevdiğimize yazdığımız o cümle, bir dosta açtığımız o dert neden başka birinin
dosyasında dursun? Saklayacak bir şeyimiz olduğu için değil; bazı sözler
yalnızca bir kişi için söylendiği için.

İnancımız şu: insanların birbiriyle konuşmasını dinlemek yanlıştır. Bunu ister
bir devlet yapsın, ister bir şirket, ister bir reklam ağı, ister yalnızca
meraklı biri. Dinlendiğini bilen insan susmayı öğrenir. Biz kimsenin susmasını
istemiyoruz.

### Ne yaptık

Her kararda kendimize aynı soruyu sorduk: *Bu, konuşmayı o iki kişinin arasında
tutuyor mu?*

- **Telefon numarası yok.** Kayıt olmak ve birini bulmak için kullanıcı adı
  yeter. Sunucu kullanıcı adını bile düz metin olarak görmez, yalnızca yavaş
  hesaplanan bir özetini saklar. Arama tam eşleşme ister; öneri ya da otomatik
  tamamlama yoktur.
- **Uçtan uca şifreleme.** Mesajlar Signal protokolünün resmî kütüphanesi
  libsignal ile (PQXDH ve üçlü ratchet) senin telefonunda kilitlenir, yalnızca
  karşındakinin telefonunda açılır. İçerik, bugün kaydedilip yarın bir kuantum
  bilgisayarla çözülme girişimlerine karşı da korunur.
- **Gönderen zarfın içinde.** Mühürlü gönderici sayesinde sunucu bir zarfın
  kime gittiğini görür, kimden geldiğini zarfın üzerinden okuyamaz.
- **Unutkan bir postacı.** Aradaki sunucu kapalı zarfı taşır, içine bakamaz,
  anahtarlara dokunamaz. Zarfı teslim eder etmez siler; teslim edemezse en geç
  14 gün sonra.
- **Bildirim yok. Bilerek.** Bildirimler Apple'ın ve Google'ın sunucularından
  geçer ve kimin ne zaman mesaj aldığını onlara söyler. us2us bildirim
  göndermez; mesajların uygulamayı açtığında gelir. Cevap vermek için herkes
  kendi zamanını seçer.
- **Konuşmalar yalnızca iki telefonda.** Bulut yedeği yok, sunucuda geçmiş yok.
- **Gizli kodla kilitli.** Telefondaki her şey, 6 haneli gizli kodundan ve
  telefondan hiç çıkmayan bir cihaz sırrından türetilen bir anahtarla
  şifrelenir. iPhone'da her deneme ayrıca Secure Enclave'den hiç çıkmayan bir
  anahtar ister. Kod unutulursa veriler geri gelmez; arka kapı yoktur.
- **Ghost mod, süreli mesajlar, tek görüntülemelik medya, herkesten silme.**
  Sessiz kalan bir sohbet seçtiğin sürenin sonunda iki telefondan da iz
  bırakmadan silinir.
- **Güvenlik numarası.** Yan yana geldiğinizde QR kodu okutarak hatta
  ikinizden başkası olmadığını doğrularsınız.
- **Mesaj istekleri.** Tanımadığın birinin ilk mesajı ayrı bir kutuda bekler;
  sen kabul etmeden sohbet açılmaz, bağlantılar ve medya gizli kalır.
- **Şifreli hesap yedeği.** Hesabını ve kişilerini, ayrı bir yedek parolasıyla
  şifrelenmiş tek bir dosyaya aktarırsın. Mesajlar yedeğe hiç girmez.
- **Rehberine dokunmaz.** Davet ederken alıcıyı sistemin kendi ekranında sen
  seçersin; us2us kişi listeni okumaz, hiçbir yere yüklemez.

### Söz veremeyeceklerimiz

Hiçbir sistem her şeyi gizleyemez. Sunucu, bağlantı sırasında IP adresini, bir
zarfın hangi hesaba gittiğini, zarfın boyutunu ve zamanını görebilir; bunları
kaydetmiyoruz, ama bu konuda bize güvenmen gerekir. Biriyle ilk kez
yazıştığında onun anahtarı sunucudan gelir; güvenlik numaralarınızı
karşılaştırana kadar araya kimsenin girmediğinden tam emin olamazsın.
Kuantum sonrası koruma mesaj içeriği içindir, zarftaki gönderen bilgisi için
değil. Telefonunda casus yazılım varsa ya da karşındaki ekranın fotoğrafını
çekerse hiçbir uygulama seni koruyamaz; us2us da. Neyin görülüp neyin
görülmediğinin eksiksiz listesi [docs/PROTOCOL.md §9](docs/PROTOCOL.md) içinde.

### Ücretsiz, açık kaynaklı, gönüllü

us2us ücretsiz. Reklam yok, izleyici yok, analiz aracı yok. Mesajlarına,
alışkanlıklarına ya da dikkatine ihtiyaç duyan bir iş modelimiz yok.
**SyconX** ekibi olarak us2us'ı gönüllü geliştiriyoruz; mahremiyet, parası
olanın satın alabildiği bir ayrıcalık olmamalı.

Kodun tamamı burada. Bir uygulamanın mesajlarını okuyamadığını bize inanarak
değil, koduna bakarak bilebilmelisin. Mağaza derlemeleri henüz tekrar
üretilebilir (reproducible) değil; libsignal sürümü ve SHA-256 değerleri depoda
sabitlenmiştir ve her sürümün derleme girdileri kayıt altına alınır.

Uygulama bugün iOS'ta TestFlight üzerinden kapalı betada; App Store ve Google
Play sürümleri yolda. Bildirinin tamamı ve kaynakları:
[us2us.app/tr/neden-yaptik](https://us2us.app/tr/neden-yaptik).

### Depo haritası

| Klasör | İçerik |
|---|---|
| `ios/App` | iOS uygulaması (SwiftUI) |
| `ios/Us2usKit` | iOS protokol ve kripto kütüphanesi (Swift, libsignal) |
| `ios/Us2usAppCore` | iOS gizlilik çekirdeği: şifreli veritabanı, kasa, bağlantı yönetimi |
| `android/` | Android uygulaması (Jetpack Compose) ve Kotlin protokol çekirdeği |
| `relay/` | Kör aktarıcı sunucu ve `us2us.app` web sitesi (Nuxt, MySQL) |
| `spec/` | Ortak içerik şeması, iki platformun birbirini doğruladığı test vektörleri |
| `docs/` | Protokol (`PROTOCOL.md`), mimari (`ARCHITECTURE.md`), davet bağlantıları |
| `scripts/` | Uçtan uca birlikte çalışma testi ve sunucu dağıtım betiği |

Her bölümün nasıl derleneceği kendi `README.md` dosyasında anlatılıyor.

### Katkı, güvenlik ve iletişim

Hata bildirimi, çeviri, kod ya da protokol incelemesi; hepsi değerli. Bu
uygulama eleştiriyle büyüyecek.

- **Güvenlik açığı** bulursan lütfen herkese açık bir kayıt açmadan önce
  **security@us2us.app** adresine yaz.
- **Bize ulaşmak için** us2us'ta **`kagany`** kullanıcı adını ekleyebilirsin.
  Elbette uçtan uca şifreli.

### Lisans

us2us, [GNU Affero Genel Kamu Lisansı v3.0](LICENSE) ile lisanslanmıştır:
us2us'ı değiştirip bir ağ üzerinden sunan herkes, değişikliklerini de
paylaşmak zorundadır. © 2026 SyconX

---

## English

### Some words are meant for one person only.

We used to wait patiently for a text message. When the phone buzzed on the
table something stirred inside us; the silence in between made the words worth
more.

Today our phones never fall silent. Who we talk to, when and from where is
recorded somewhere, and some of our messages are read by people we never wrote
to.

For many of us the very first telephone was two cups and a piece of string.
We whispered into one cup, the string pulled tight, and our voice reached the
friend at the other end, and no one else. The two rings in our logo and the
line between them are those cups and that string. We kept them in mind the
whole time we built us2us: **two ends, one line, no one in between.**

### Listening in is wrong

“I have nothing to hide.” We ask the question the other way round: why should
the sentence you wrote to someone you love at midnight, or the trouble you
shared with a friend, sit in someone else's file? Not because we have
something to hide, but because some words are said for one person only.

This is what we believe: listening in on people's conversations is wrong,
whether a government does it, a company, an ad network or just someone curious.
People who know they are being listened to learn to stay silent. We want no one
to stay silent.

### What we built

We asked ourselves the same question at every decision: *does this keep the
conversation between those two people?*

- **No phone number.** A username is all you need to sign up and to find
  someone. The server never sees usernames in plain text, only a slow hash.
  Search needs an exact match: no suggestions, no autocomplete.
- **End-to-end encryption.** Messages are sealed on your phone with libsignal,
  the official Signal Protocol library (PQXDH and the triple ratchet), and
  opened only on theirs. The content is also protected against “record now,
  decrypt later with a quantum computer”.
- **The sender is inside the envelope.** With sealed sender the server sees
  where an envelope goes but cannot read from it where it came from.
- **A forgetful postman.** The relay carries sealed envelopes, cannot look
  inside and never touches the keys. It deletes an envelope as soon as it is
  delivered, and after 14 days at the latest if it never is.
- **No notifications. On purpose.** Notifications pass through Apple's and
  Google's servers and tell them who gets messages and when. us2us sends none;
  your messages arrive when you open the app. Everyone chooses their own time
  to answer.
- **Conversations live only on the two phones.** No cloud backup, no history on
  the server.
- **Locked with a secret code.** Everything on the phone is encrypted with a key
  derived from your 6-digit secret code and a device secret that never leaves
  the phone. On an iPhone every attempt also needs a key that never leaves its
  Secure Enclave. Forget the code and the data is gone: there is no back door.
- **Ghost mode, disappearing messages, view-once media, delete for everyone.**
  A chat that stays quiet is wiped from both phones without a trace when the
  time you chose runs out.
- **Safety numbers.** When you are together, scan each other's QR code and
  make sure it is only the two of you on the line.
- **Message requests.** A stranger's first message waits in a separate box; no
  chat opens until you accept, and links and media stay hidden until then.
- **Encrypted account backup.** Export your account and contacts to one file
  locked with a separate backup password. Messages are never included.
- **Your contacts stay yours.** When you invite someone you pick the recipient
  in the system's own screen; us2us never reads or uploads your address book.

### What we cannot promise

No system can hide everything. While you are connected the server can see your
IP address, which account an envelope is addressed to, and its size and
timing; we do not log them, but you have to trust us on that. The first time
you write to someone their key comes from the server; until you compare safety
numbers you cannot be fully sure no one is in between. The post-quantum
protection covers message content, not the sender on the envelope. If your
phone carries spyware, or the other person photographs the screen, no app can
protect you; us2us included. The complete list of what is and is not visible is
in [docs/PROTOCOL.md §9](docs/PROTOCOL.md).

### Free, open source, volunteer-built

us2us is free. No ads, no trackers, no analytics. We have no business model
that needs your messages, your habits or your attention. We, the **SyconX**
team, build us2us as volunteers; privacy should not be a privilege for those
who can pay for it.

All of the code is here. You should be able to know that an app cannot read
your messages by looking at its code, not by taking our word for it. The store
builds are not yet reproducible; the libsignal version and SHA-256 hashes are
pinned in this repository and every release records its build inputs.

Today us2us is in a closed beta on iOS through TestFlight; the App Store and
Google Play releases are on their way. The full essay and its sources:
[us2us.app/en/why-we-built-it](https://us2us.app/en/why-we-built-it).

### Repository map

| Folder | Contents |
|---|---|
| `ios/App` | The iOS app (SwiftUI) |
| `ios/Us2usKit` | iOS protocol and crypto library (Swift, libsignal) |
| `ios/Us2usAppCore` | iOS privacy core: encrypted database, vault, connection management |
| `android/` | The Android app (Jetpack Compose) and the Kotlin protocol core |
| `relay/` | The blind relay and the `us2us.app` website (Nuxt, MySQL) |
| `spec/` | Shared content schema and the test vectors both platforms check each other with |
| `docs/` | The protocol (`PROTOCOL.md`), architecture (`ARCHITECTURE.md`), invite links |
| `scripts/` | End-to-end interoperability test and the relay deployment script |

Each part explains how to build it in its own `README.md`.

### Contributing, security and contact

Bug reports, translations, code and protocol review are all welcome. This app
will grow through criticism.

- **Found a security issue?** Please write to **security@us2us.app** before
  opening a public issue.
- **To reach us,** add the username **`kagany`** in us2us. End-to-end
  encrypted, of course.

### License

us2us is licensed under the [GNU Affero General Public License v3.0](LICENSE):
anyone who modifies us2us and offers it over a network must share their
changes too. © 2026 SyconX
