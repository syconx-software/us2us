# "No push" — user-facing copy (TR + EN)

Single source for every place where the apps, the website and the store
listings explain why us2us has no push notifications. Keep the tone warm,
honest and confident, never preachy. When the text changes here, update the
string catalogs (iOS `Localizable.xcstrings`, Android `strings.xml`) and the
website.

The 14-day value comes from PROTOCOL.md §4.10. If it changes, change it here
too.

---

## 1. Onboarding screen (shown once, before account creation)

### TR

**Bildirim yok. Bilerek.**

us2us sana bildirim göndermez. Bunu unuttuğumuz için değil, gizliliğin için
yapıyoruz.

Telefonlardaki bildirimler Apple ve Google'ın sunucularından geçer. Mesajın
içeriği gizli olsa bile, telefonunun hangi uygulamadan, ne zaman mesaj
aldığını onlar görür. Biz bu izi de bırakmak istemedik.

- **Mesajların uygulamayı açtığında gelir.** Açık olduğu sürece her şey
  anında akar.
- **Hiçbir mesaj kaybolmaz.** Sen uygulamayı açana kadar mesajlar şifreli
  olarak 14 gün bekler.
- **Kimse izleyemez.** Apple, Google ya da başka bir şirket, kiminle ne zaman
  konuştuğunu öğrenemez.
- **Arka planda hiçbir şey çalışmaz.** Pilin de bundan memnun kalacak.

[ Anladım ]

<small>Önemli bir şey bekliyorsan karşı tarafa "us2us'a bak" demen yeterli.</small>

### EN

**No notifications. On purpose.**

us2us doesn't send you notifications. We didn't forget them; we left them
out to protect your privacy.

Phone notifications travel through Apple's and Google's servers. Even when
the content is hidden, they can see which app sent your phone a message, and
when. We didn't want to leave even that trace.

- **Your messages arrive when you open the app.** While it's open,
  everything flows instantly.
- **Nothing gets lost.** Messages wait for you, encrypted, for up to 14 days
  until you open the app.
- **Nobody is watching.** Not Apple, not Google, not any other company can
  learn who you talk to or when.
- **Nothing runs in the background.** Your battery will thank you.

[ Got it ]

<small>Waiting for something important? Just tell them "check us2us".</small>

---

## 2. Settings → Notifications

### TR

**Neden bildirim yok?**

Çoğu uygulama sana bildirim gönderebilmek için Apple'a (iPhone) ya da
Google'a (Android) haber verir: "Bu telefona yeni bir mesaj var." Bildirimin
içi boş olsa bile bu haber, senin bu uygulamayı kullandığını ve ne zaman
mesaj aldığını bu şirketlerin kayıtlarına geçirir. Ayrıca bizim sunucumuzun da
hangi hesabın hangi telefona ait olduğunu bilmesini gerektirir.

us2us'ta bu zincir hiç yok:

1. Sen uygulamayı açtığında telefonun bizim sunucuya bağlanır.
2. Seni bekleyen şifreli mesajları alır ve sunucudan hemen sildirir.
3. Uygulamayı kapattığında bağlantı da kapanır. Arada kimseye haber gitmez.

Bedeli şu: uygulama kapalıyken yeni mesaj geldiğini göremezsin. Uygulamayı ne
sıklıkla açacağına sen karar verirsin.

### EN

**Why are there no notifications?**

To send you a notification, most apps tell Apple (iPhone) or Google
(Android): "there's a new message for this phone." Even if the notification
itself is empty, that message puts on record that you use the app and when
you receive messages. It also forces the app's server to know which account
belongs to which phone.

us2us doesn't have that chain at all:

1. When you open the app, your phone connects to our server.
2. It picks up the encrypted messages waiting for you, and they are deleted
   from the server right away.
3. When you close the app, the connection closes too. Nobody else is told
   anything.

The trade-off: while the app is closed, you won't see that a message
arrived. You decide how often to check.

---

## 3. Message status (sender side)

| State | TR | EN |
|---|---|---|
| Sent, waiting | Gönderildi · Alıcı uygulamayı açınca iletilecek | Sent · Will be delivered when they open the app |
| Delivered | İletildi | Delivered |
| Read (optional) | Okundu | Read |
| Expired | İletilemedi · 14 gün içinde açılmadı | Not delivered · Not opened within 14 days |

Info sheet when tapping "Gönderildi" / "Sent":

- **TR:** us2us bildirim kullanmadığı için mesajın, alıcı uygulamayı açtığı an
  iletilir. O zamana kadar şifreli olarak en fazla 14 gün bekler.
- **EN:** Because us2us doesn't use notifications, your message is delivered
  the moment they open the app. Until then it waits, encrypted, for up to 14
  days.

---

## 4. Small hints

| Where | TR | EN |
|---|---|---|
| Chat list footer | Uygulama açıkken mesajların anında gelir. | Messages arrive instantly while the app is open. |
| After reconnecting (banner) | 3 yeni mesaj alındı | 3 new messages received |
| Message-request intro | İlk mesajın, karşı taraf uygulamayı açtığında ona ulaşır. | Your first message reaches them when they open the app. |

---

## 5. Website FAQ (us2us.app)

### TR — "us2us neden bildirim göndermiyor?"

Çünkü bildirimler Apple ve Google'ın sunucularından geçer ve hangi
uygulamanın, hangi telefona, ne zaman mesaj gönderdiğini onlara bildirir.
us2us'ta mesajlar sadece uygulama açıkken, doğrudan bizim kör sunucumuz
üzerinden alınır. Böylece ne Apple, ne Google, ne de biz kimin ne zaman mesaj
aldığını bir şirkete raporlamış oluruz. Mesajlar, sen uygulamayı açana kadar
şifreli olarak en fazla 14 gün bekler.

### EN — "Why doesn't us2us send notifications?"

Because notifications travel through Apple's and Google's servers and tell
them which app sent a message to which phone, and when. In us2us, messages
are fetched only while the app is open, directly from our blind relay. No
company, not Apple, not Google, not us, gets a report of who received a
message and when. Messages wait for you, encrypted, for up to 14 days.

---

## 6. Store listing paragraph

- **TR:** Bildirim yok, bilerek. us2us, Apple'ın ve Google'ın bildirim
  sunucularını hiç kullanmaz; kimin ne zaman mesaj aldığı kimseye bildirilmez.
  Mesajların, uygulamayı açtığında gelir.
- **EN:** No notifications, on purpose. us2us never uses Apple's or Google's
  push servers, so no one is told who receives messages or when. Your
  messages arrive when you open the app.
