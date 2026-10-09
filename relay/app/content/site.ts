/*
 * All website text, Turkish and English. Every entry exists in both
 * languages; the `SiteText` type makes a missing translation a type error.
 * The landing page follows the owner's design ("us2us Web"); the privacy
 * policy mirrors PROTOCOL.md §4.10 (retention) and §9 (known limits), and
 * the "no notifications" texts follow docs/copy/no-push.md; change them
 * together.
 *
 * Headings are plain strings: a full stop that ends a sentence is drawn in
 * the body font by <SiteDots>, as in the design.
 */

import type { IconName } from './icons'

export type Locale = 'tr' | 'en'
export type PageId = 'home' | 'privacy' | 'support'

export const paths: Record<Locale, Record<PageId, string>> = {
  tr: { home: '/tr', privacy: '/tr/gizlilik', support: '/tr/destek' },
  en: { home: '/en', privacy: '/en/privacy', support: '/en/support' },
}

export const SITE_ORIGIN = 'https://us2us.app'
export const SUPPORT_EMAIL = 'support@us2us.app'

/**
 * Landing-page section ids. They are the same in both languages, so the
 * language switch can keep the reader's place (`/tr#durumlar` → `/en#durumlar`).
 */
export const SECTION = {
  top: 'top',
  how: 'hat',
  statuses: 'durumlar',
  ghost: 'hayalet',
  features: 'ozellikler',
  verify: 'dogrula',
  download: 'indir',
} as const

/**
 * The privacy page's security section answers to both ids (the section has
 * one, its heading the other), so `#guvenlik` and `#security` work on both
 * language versions.
 */
export const SECURITY_ANCHOR: Record<Locale, string> = { tr: 'guvenlik', en: 'security' }

/** The support page's licences section, linked from the footer; it works like SECURITY_ANCHOR. */
export const LICENCES_ANCHOR: Record<Locale, string> = { tr: 'lisanslar', en: 'licences' }

/** Third-party licence texts served by the site itself (public/fonts, public/licenses). */
const LICENCES: SiteText['support']['licences'] = [
  { name: 'Inter', licence: 'SIL Open Font License 1.1', href: '/fonts/OFL-Inter.txt' },
  { name: 'Doto', licence: 'SIL Open Font License 1.1', href: '/fonts/OFL-Doto.txt' },
  { name: 'Martian Mono', licence: 'SIL Open Font License 1.1', href: '/fonts/OFL-MartianMono.txt' },
  { name: 'Phosphor Icons 2.1.1', licence: 'MIT', href: '/licenses/phosphor-icons.txt' },
  { name: 'three.js r160', licence: 'MIT', href: '/licenses/three.txt' },
]

interface RetentionRow {
  data: string
  lifetime: string
}

interface Faq {
  question: string
  answer: string
}

interface Card {
  icon: IconName
  title: string
  body: string
}

export type StatusTone = 'muted' | 'accent' | 'danger'

export interface SiteText {
  languageName: string
  skipLink: string
  nav: {
    label: string
    languageLabel: string
    themeLabel: string
    home: string
    sections: Array<{ id: string, label: string }>
  }
  footer: { label: string, privacy: string, security: string, press: string, contact: string, licence: string }
  home: {
    title: string
    description: string
    hero: {
      badge: string
      kicker: string
      headline: string
      intro: string
      facts: Array<{ icon: IconName, text: string }>
    }
    stores: { label: string, comingSoon: string }
    band: { label: string, stats: Array<{ value: string, label: string, text: string }> }
    how: { eyebrow: string, title: string, intro: string, points: Array<Card & { n: string }> }
    statuses: {
      eyebrow: string
      title: string
      intro: string
      label: string
      items: Array<{ icon: IconName, tone: StatusTone, label: string, text: string }>
    }
    ghost: {
      eyebrow: string
      title: string
      intro: string
      contact: string
      presence: string
      timerLabel: string
      windowLabel: string
      windows: string[]
      selectedWindow: number
    }
    features: { eyebrow: string, title: string, items: Card[], kindsTitle: string, kinds: Array<{ icon: IconName, label: string }> }
    verify: {
      eyebrow: string
      title: string
      intro: string
      cardTitle: string
      cardSubtitle: string
      verified: string
      scan: string
      example: string
    }
    cta: { title: string, intro: string }
  }
  privacy: {
    title: string
    description: string
    updated: string
    summary: string[]
    storedTitle: string
    storedIntro: string
    columns: { data: string, lifetime: string }
    stored: RetentionRow[]
    neverTitle: string
    never: string[]
    seesTitle: string
    sees: string[]
    appleGoogleTitle: string
    appleGoogle: string[]
    phoneTitle: string
    phone: string[]
    securityTitle: string
    security: string[]
    limitsTitle: string
    limits: string[]
    noTrackingTitle: string
    noTracking: string[]
    deletionTitle: string
    deletion: string[]
    contactTitle: string
    contact: string
  }
  support: {
    title: string
    description: string
    intro: string
    emailLabel: string
    faqTitle: string
    faq: Faq[]
    licencesTitle: string
    licencesIntro: string
    licencesListIntro: string
    licences: Array<{ name: string, licence: string, href: string }>
  }
}

export const site: Record<Locale, SiteText> = {
  tr: {
    languageName: 'Türkçe',
    skipLink: 'İçeriğe geç',
    nav: {
      label: 'Bölümler',
      languageLabel: 'Dil',
      themeLabel: 'Açık tema',
      home: 'us2us ana sayfası',
      sections: [
        { id: SECTION.how, label: 'Nasıl çalışır' },
        { id: SECTION.statuses, label: 'Bildirimler' },
        { id: SECTION.features, label: 'Gizlilik' },
        { id: SECTION.verify, label: 'Doğrulama' },
      ],
    },
    footer: {
      label: 'Site bağlantıları',
      privacy: 'Gizlilik politikası',
      security: 'Güvenlik',
      press: 'Basın',
      contact: 'İletişim',
      licence: 'AGPL-3.0 · Kaynak kodu yakında',
    },
    home: {
      title: 'us2us · Araya kimse giremez',
      description: 'us2us iki kişi arasında kurulan özel bir hat. Telefon numarası istemez, bildirim göndermez; istersen sessiz kalan sohbetleri iz bırakmadan siler.',
      hero: {
        badge: 'Uçtan uca şifreli mesajlaşma',
        kicker: 'İki uç. Bir hat.',
        headline: 'Araya kimse giremez.',
        intro: 'us2us iki kişi arasında kurulan özel bir hat. Telefon numarası istemez, bildirim göndermez; istersen sessiz kalan sohbetleri iz bırakmadan siler.',
        facts: [
          { icon: 'phone-slash', text: 'Telefon numarası yok' },
          { icon: 'bell-slash', text: 'Push bildirimi yok' },
          { icon: 'password', text: 'Gizli kodla kilitli' },
        ],
      },
      stores: { label: 'Uygulama mağazaları', comingSoon: 'Yakında' },
      band: {
        label: 'Rakamlarla us2us',
        stats: [
          { value: '0', label: 'telefon numarası', text: 'Kayıt ve kişi bulma sadece kullanıcı adıyla.' },
          { value: '0', label: 'push bildirimi', text: 'Mesajlar yalnızca uygulama açıkken gelir.' },
          { value: '2', label: 'uç', text: 'Her sohbet iki kişilik bir hat.' },
        ],
      },
      how: {
        eyebrow: 'Nasıl çalışır',
        title: 'Mesaj senin ucunda kilitlenir, yalnızca onun ucunda açılır.',
        intro: 'Şifreleme cihazında olur. Aradaki sunucu yalnızca kapalı zarfı taşır; içini göremez, onu açan anahtarlar yalnızca ikinizde.',
        points: [
          { n: '01', icon: 'at', title: 'Kullanıcı adı, numara değil', body: 'Kayıt ve kişi bulma sadece kullanıcı adıyla. Arama tam eşleşme ister; öneri ya da otomatik tamamlama yok.' },
          { n: '02', icon: 'password', title: 'Gizli kodla açılır', body: 'Uygulamayı açarken kodun ya da istersen Face ID veya parmak izin istenir. Kodu unutursan veriler geri gelmez.' },
          { n: '03', icon: 'qr-code', title: 'Güvenlik numarası', body: 'Yan yana gelince QR kodu okutun. Numaralar eşleşiyorsa hatta ikinizden başkası yok.' },
        ],
      },
      statuses: {
        eyebrow: 'Push bildirimi yok',
        title: 'Bildirim yok. Bilerek.',
        intro: 'Bildirimler Apple ve Google sunucularından geçer, kilit ekranında iz bırakır. us2us’ta mesajlar yalnızca uygulama açıkken gelir; karşılığında her mesajın durumunu görürsün.',
        label: 'Mesaj durumları',
        items: [
          { icon: 'check', tone: 'muted', label: 'Gönderildi', text: 'Alıcı uygulamayı açınca iletilecek.' },
          { icon: 'checks', tone: 'muted', label: 'İletildi', text: 'Mesaj karşı uçta, onun cihazında.' },
          { icon: 'checks-fill', tone: 'accent', label: 'Okundu', text: 'Karşı taraf mesajı açtı (okundu bilgisini paylaşıyorsa).' },
          { icon: 'warning-circle', tone: 'danger', label: 'İletilemedi', text: 'Alıcı 14 gün içinde uygulamayı açmadı; mesaj ona hiç ulaşmadı.' },
        ],
      },
      ghost: {
        eyebrow: 'Hayalet mod',
        title: 'Sessiz kalan sohbet kendini siler.',
        intro: 'Hayalet modu açtığın sohbet, seçtiğin süre boyunca sessiz kalırsa iki uçtan da tamamen silinir. Her yeni mesaj sayacı sıfırlar; geri sayım sohbet başlığında hep görünür.',
        contact: 'deniz',
        presence: 'Aktif',
        timerLabel: 'Silinmeye kalan süre',
        windowLabel: 'Sessizlik süresi',
        windows: ['1 saat', '24 saat', '7 gün'],
        selectedWindow: 1,
      },
      features: {
        eyebrow: 'Gizlilik',
        title: 'Az iz bırakır. Kontrol sende kalır.',
        items: [
          { icon: 'timer', title: 'Süreli mesajlar', body: 'Süre dolunca mesaj iki taraftan da silinir.' },
          { icon: 'eye-closed', title: 'Tek görüntülemelik medya', body: 'Fotoğraf ve video bir kez açılır, sonra kaybolur.' },
          { icon: 'trash', title: 'Herkesten sil', body: 'Gönderdiğin mesajı karşı uçtan da kaldır.' },
          { icon: 'tray', title: 'Mesaj istekleri', body: 'Tanımadığın birinin ilk mesajı ayrı bir kutuya düşer. Kabul etmeden sohbet açılmaz.' },
          { icon: 'circle-fill', title: 'Aktif durumu', body: 'Yeşil noktayı ve “Az önce aktifti” bilgisini yalnızca kabul ettiğin kişiler görür. Karşılıklıdır: durumunu gizlersen sen de başkalarınınkini görmezsin.' },
          { icon: 'camera-slash', title: 'Ekran görüntüsü koruması', body: 'Android’de ekran görüntüsü engellenir. iOS’ta alındığı algılanır ve sohbette bildirilir.' },
          { icon: 'drop-half', title: 'Bulanık önizleme', body: 'Uygulama değiştiricide sohbetlerin görünmez; ekran bulanıklaşır.' },
          { icon: 'file-lock', title: 'Şifreli hesap yedeği', body: 'Hesabını ve kişilerini gizli kodunla şifrelenmiş tek bir dosyaya aktar; yeni telefonunda aynı kullanıcı adıyla devam et. Mesajlar yedeğe girmez.' },
        ],
        kindsTitle: 'Hat neyi taşır',
        kinds: [
          { icon: 'text-aa', label: 'Metin' },
          { icon: 'image', label: 'Fotoğraf' },
          { icon: 'video-camera', label: 'Video' },
          { icon: 'microphone', label: 'Sesli mesaj' },
          { icon: 'file', label: 'Dosya' },
          { icon: 'map-pin', label: 'Konum' },
          { icon: 'navigation-arrow', label: 'Canlı konum' },
          { icon: 'sticker', label: 'Sticker' },
          { icon: 'smiley', label: 'Tepki' },
          { icon: 'arrow-bend-up-left', label: 'Yanıtlama' },
        ],
      },
      verify: {
        eyebrow: 'Doğrulama',
        title: 'Hattın ucunda gerçekten o mu var?',
        intro: 'Her sohbetin bir güvenlik numarası vardır. Yan yana geldiğinizde birinizin QR kodunu diğeri okutsun; eşleşirse sohbet doğrulanmış olarak işaretlenir.',
        cardTitle: 'Güvenlik numarası',
        cardSubtitle: 'sen ve deniz',
        verified: 'Doğrulandı',
        scan: 'QR kodu okut',
        example: 'Örnek görünüm; QR kodu ve numara gerçek değil.',
      },
      cta: {
        title: 'Hattın iki ucu da sizde.',
        intro: 'Kullanıcı adını al, gizli kodunu belirle, hattı kur. Yakında iOS ve Android’de.',
      },
    },
    privacy: {
      title: 'Gizlilik politikası',
      description: 'us2us sunucusunun ne sakladığı, ne kadar süre sakladığı ve neyi asla göremediği.',
      updated: 'Son güncelleme: 8 Ekim 2026',
      summary: [
        'us2us, konuşmalarınızı sunucularımızda değil, yalnızca sizin ve konuştuğunuz kişinin telefonunda tutacak şekilde tasarlandı.',
        'Sunucumuz (“aktarıcı”) yalnızca şifreli zarfları teslim edene kadar taşır. Mesajlarınızın içeriğini, mühürlü bir mesajı kimin gönderdiğini, kullanıcı adınızı, kişi listenizi ve konumunuzu göremez; nelerin görülebildiği aşağıda. Telefon numaranızı ya da e-posta adresinizi hiç istemeyiz.',
      ],
      storedTitle: 'Sunucunun sakladıkları ve saklama süreleri',
      storedIntro: 'Aktarıcı yalnızca aşağıdakileri saklar. Rastgele hesap kimlikleri ve son bağlantı günü dışında hepsi ya herkese açık anahtar, ya şifreli veri ya da geri çevrilemeyen özettir (hash).',
      columns: { data: 'Veri', lifetime: 'Ne kadar süre' },
      stored: [
        { data: 'Rastgele hesap kimliği', lifetime: 'Hesabınızı silene ya da 180 gün boyunca hiç bağlanmayana kadar' },
        { data: 'Kullanıcı adınızın özeti (PBKDF2, 100.000 tur; kullanıcı adının kendisi değil)', lifetime: 'Hesapla birlikte. Kullanıcı adınızı değiştirirseniz eski adın özeti, başkası alamasın diye 14 gün daha yalnızca sizin için ayrılır.' },
        { data: 'Açık anahtarlarınız (kimlik anahtarı ve oturum kurmak için ön anahtarlar)', lifetime: 'Hesapla birlikte. Tek kullanımlık ön anahtarlar bir kez verildiği anda silinir.' },
        { data: 'Giriş belirtecinizin ve erişim anahtarınızın SHA-256 özetleri', lifetime: 'Hesapla birlikte; yenilendiğinde eskisi hemen silinir' },
        { data: 'Son bağlantı tarihi (yalnızca gün; saat yok)', lifetime: 'Hesapla birlikte' },
        { data: 'Şifreli mesaj zarfları ve alıcı hesap kimliği', lifetime: 'Telefonunuz teslim aldığını onaylayana kadar, en fazla 14 gün' },
        { data: 'Şifreli dosyalar (fotoğraf, video, ses, belge) ve yükleyen hesap', lifetime: 'Silinene kadar (genellikle alıcı indirir indirmez), en fazla 14 gün' },
        { data: 'Kullanılmış istenmeyen mesaj önleme belirteçlerinin özetleri', lifetime: 'Belirtecin süresi dolana kadar (en fazla 10 dakika)' },
      ],
      neverTitle: 'Sunucunun asla göremedikleri',
      never: [
        'Mesajlarınızın, fotoğraflarınızın, dosyalarınızın ve sesli mesajlarınızın içeriği',
        'Mühürlü bir mesajı kimin gönderdiği',
        'Kullanıcı adınızın açık hâli',
        'Kişi listeniz, görünen adınız ve profil fotoğrafınız',
        'Konumunuz ve canlı konumunuz',
        'Gizli kodunuz ya da ondan türetilen herhangi bir şey',
      ],
      seesTitle: 'Sunucunun çalışırken görebildikleri',
      sees: [
        'Bağlandığınız andaki IP adresiniz. Aktarıcı IP adreslerini kayıt altına almaz; gizlemek isterseniz Tor ya da VPN kullanabilirsiniz.',
        'Her zarfın hangi hesaba gittiği, boyutu (dolgulu) ve zamanı.',
        'Hangi hesabın dosya yüklediği.',
        'Yeni bir kişiyi bulduğunuzda ya da ondan ilk mesajı aldığınızda telefonunuz onun hesabını ve anahtarlarını sunucuya sorar. Bu sorgular hesabınızla yapıldığı için sunucu, her yeni kişi için bir kez, hangi hesabın hangi hesabı sorguladığını öğrenir.',
        '“Aktif” durumu uçtan uca şifrelidir ve sunucu bununla ilgili hiçbir şey saklamaz. Ancak uygulama her açılışta, durumunu paylaştığı kişilere tek seferde şifreli zarflar gönderir; IP adreslerini ve zamanları kaydeden kötü niyetli bir sunucu bu toplu gönderimlerden kişi listelerini tahmin edebilirdi. Bizim sunucumuz böyle bir kayıt tutmaz. Yine de korunmak isterseniz “Aktif olduğumu göster” ayarını kapatabilir ya da Tor veya VPN kullanabilirsiniz.',
      ],
      appleGoogleTitle: 'Apple, Google ve bildirimler',
      appleGoogle: [
        'us2us bildirim (push) kullanmaz. Apple ve Google hiç devrede değildir: ne bildirim gönderilir ne de cihazınızı tanıtan bir kimlik onlara ya da bize verilir. Bu yüzden kimin ne zaman mesaj aldığını öğrenemezler.',
        'Bunun bedeli şu: uygulama kapalıyken yeni mesajları göremezsiniz. Mesajlarınız uygulamayı açtığınızda doğrudan sunucumuzdan gelir.',
        'Uygulamayı App Store ya da Google Play’den indirmeniz, Apple’ın ve Google’ın kendi gizlilik politikalarına tabidir.',
      ],
      phoneTitle: 'Telefonunuzdaki veriler',
      phone: [
        'Mesajlarınız, kişileriniz ve ayarlarınız yalnızca telefonunuzda, gizli kodunuzla şifrelenmiş olarak durur. Kod telefonunuzdan hiç çıkmaz.',
        'Hesap yedeği dosyasını yalnızca siz oluşturursunuz; gizli kodunuzla şifrelenir ve bize hiç gönderilmez. Mesajlar yedeğe dahil değildir.',
      ],
      securityTitle: 'Güvenlik',
      security: [
        'Mesajlar, Signal protokolünün resmî kütüphanesi libsignal ile telefonunuzda şifrelenir (PQXDH ve Double Ratchet, kuantum sonrası korumalı). Gizli anahtarlar yalnızca telefonlarınızda (ve dışa aktardıysanız gizli kodunuzla şifrelenmiş hesap yedeğinizde) bulunur.',
        'Mühürlü gönderici sayesinde sunucu bir mesajın kime gittiğini bilir ama kimden geldiğini bilmez.',
        'Telefonunuzdaki her şey (anahtarlar, mesajlar, fotoğraflar, kişiler, ayarlar) gizli kodunuzla şifrelenir. Kod telefonunuzdan hiç çıkmaz ve sıfırlanamaz; arka kapı yoktur.',
        'Her sohbetin bir güvenlik numarası vardır. Yan yana geldiğinizde QR kodunu okutarak konuştuğunuz kişinin gerçekten o olduğunu doğrulayabilirsiniz. Bir kişinin anahtarı değişirse uygulama sizi uyarır ve siz onaylayana kadar ona mesaj göndermez.',
        'Bir güvenlik açığı bulduysanız konu satırına “security” yazarak bize e-posta gönderin. Açığı düzeltene kadar herkese açık paylaşmamanızı rica ederiz.',
      ],
      limitsTitle: 'Dürüst olmak gerekirse',
      limits: [
        'Karşı taraf ekranın fotoğrafını çekebilir ya da değiştirilmiş bir uygulama kullanabilir; “kaybolan” mesajları hiçbir uygulama tamamen koruyamaz. Android ekran görüntüsünü engeller, iOS ise yalnızca algılayabilir.',
        'Hayalet mod ve süreli mesaj silmeleri, süre dolduktan sonra uygulamanın ya da telefondaki zamanlayıcının çalıştığı ilk anda yapılır. iOS arka plan görevlerini garanti etmediği için iPhone’da silme bir sonraki açılışa kalabilir; o zamana kadar veriler gizli kodunuzla şifreli durur ve süresi geçtikten sonra hiç gösterilmez.',
        'Sunucu beklenmedik biçimde kapanırsa (örneğin elektrik kesintisinde), o an bellekte bekleyen mesajlar kaybolabilir; gönderen “Gönderildi”yi görür ama “İletildi”yi görmez ve mesajı yeniden gönderebilir.',
      ],
      noTrackingTitle: 'Analiz, reklam ve kayıtlar',
      noTracking: [
        'Uygulamada ve bu sitede analiz aracı, reklam, izleyici ya da çerez yoktur. Bu site dış kaynaktan yazı tipi ya da betik yüklemez; tema seçiminiz yalnızca tarayıcınızda (localStorage) saklanır, bize gönderilmez.',
        'Aktarıcı istek içeriklerini, belirteçleri, anahtarları ve IP adreslerini kayıt altına almaz. Web sunucusu erişim kaydı tutmaz; hata kaydına yalnızca sunucunun kendisindeki ciddi arızalar (örneğin bağlantı kapasitesinin dolması) yazılır. Böyle bir kayıtta ilgili IP adresi ve istek yer alabilir. Hata kayıtları 14 gün sonra silinir.',
        'Mesajları ve dosyaları asla yedeklemeyiz. Felaket kurtarma için yalnızca hesap dizini (hesap kimlikleri, kullanıcı adı özetleri, açık anahtarlar, belirteç ve erişim anahtarı özetleri, son bağlantı günü) şifreli olarak yedeklenebilir; bu yedekler en fazla 7 gün tutulur.',
      ],
      deletionTitle: 'Verilerinizi silme',
      deletion: [
        'Uygulamadan hesabınızı sildiğinizde hesabınız, anahtarlarınız, bekleyen mesajlarınız ve yüklediğiniz dosyalar sunucudan hemen silinir. Hesap kaydınız (hesap kimliği, kullanıcı adı özeti, açık anahtarlar) şifreli felaket kurtarma yedeklerinde en fazla 7 gün daha kalabilir.',
        'Uygulamayı silip bir daha bağlanmazsanız hesabınız 180 gün sonra kendiliğinden silinir.',
        'Telefon numaranızı ya da e-postanızı bilmediğimiz için sizi tanıyamayız; bu nedenle silme işlemi uygulamanın içinden yapılır.',
      ],
      contactTitle: 'İletişim',
      contact: 'Bu politikayla ilgili sorularınız için bize yazın:',
    },
    support: {
      title: 'Destek',
      description: 'us2us ile ilgili sık sorulan sorular ve iletişim.',
      intro: 'Bir sorun mu var ya da bir sorunuz mu? Bize e-postayla ulaşın. Gizli kodunuzu, yedek dosyanızı ya da mesajlarınızı asla göndermeyin; size yardım etmek için bunlara ihtiyacımız yok. Basın soruları için de aynı adrese yazabilirsiniz.',
      emailLabel: 'E-posta',
      faqTitle: 'Sık sorulan sorular',
      faq: [
        {
          question: 'Gizli kodumu unuttum. Ne yapabilirim?',
          answer: 'Ne yazık ki hiçbir şey. Telefonunuzdaki tüm veriler bu kodla şifrelenir ve kod hiçbir yere gönderilmez; biz de dahil kimse sıfırlayamaz. Yeni bir hesap açmanız gerekir.',
        },
        {
          question: 'Kullanıcı adımı değiştirebilir miyim?',
          answer: 'Evet, istediğiniz zaman (günde en fazla 5 kez). Kullanıcı adınız yalnızca sizi bulmaya yarayan bir etikettir; hesabınız ve şifreleme, telefonunuzda üretilen anahtarlara dayanır. Konuşmalarınız ve güvenlik numaralarınız değişmez, kişilerinize yeni adınız otomatik bildirilir. Eski adınız 14 gün boyunca yalnızca size ayrılır.',
        },
        {
          question: 'Yeni bir telefona nasıl geçerim?',
          answer: 'Eski telefonda bir hesap yedeği oluşturun, dosyayı yeni telefona taşıyın ve gizli kodunuzla açın. Eski telefonun erişimi kapanır. Mesaj geçmişi yedeğe dahil değildir.',
        },
        {
          question: 'us2us neden bildirim göndermiyor?',
          answer: 'Çünkü bildirimler Apple ve Google’ın sunucularından geçer ve hangi uygulamanın, hangi telefona, ne zaman mesaj gönderdiğini onlara bildirir. us2us’ta mesajlar sadece uygulama açıkken, doğrudan kör sunucumuz üzerinden alınır. Böylece kimin ne zaman mesaj aldığı ne Apple’a, ne Google’a, ne de bize bildirilir. Mesajlar, siz uygulamayı açana kadar şifreli olarak en fazla 14 gün bekler.',
        },
        {
          question: 'Hesabımı nasıl silerim?',
          answer: 'Uygulamanın ayarlarından “Hesabı sil”i seçin. Sunucudaki her şey hemen silinir; yalnızca şifreli felaket kurtarma yedeklerinde hesap kaydınız en fazla 7 gün daha kalabilir.',
        },
        {
          question: 'Bir güvenlik açığı buldum.',
          answer: 'Lütfen konu satırına “security” yazarak bize e-posta gönderin. Açığı düzeltene kadar herkese açık paylaşmamanızı rica ederiz.',
        },
      ],
      licencesTitle: 'Lisanslar',
      licencesIntro: 'us2us’ın kodu (uygulamalar ve bu siteyi de sunan aktarıcı) AGPL-3.0 lisanslıdır. Kaynak kodu yakında yayımlanacak; adresini bu sayfada duyuracağız.',
      licencesListIntro: 'Bu sitenin kullandığı yazı tipleri ve kütüphaneler:',
      licences: LICENCES,
    },
  },
  en: {
    languageName: 'English',
    skipLink: 'Skip to content',
    nav: {
      label: 'Sections',
      languageLabel: 'Language',
      themeLabel: 'Light theme',
      home: 'us2us home',
      sections: [
        { id: SECTION.how, label: 'How it works' },
        { id: SECTION.statuses, label: 'Notifications' },
        { id: SECTION.features, label: 'Privacy' },
        { id: SECTION.verify, label: 'Verification' },
      ],
    },
    footer: {
      label: 'Site links',
      privacy: 'Privacy policy',
      security: 'Security',
      press: 'Press',
      contact: 'Contact',
      licence: 'AGPL-3.0 · Source code coming soon',
    },
    home: {
      title: 'us2us · No one gets in between',
      description: 'us2us is a private line between two people. It never asks for your phone number, sends no notifications, and, if you want, erases quiet conversations without a trace.',
      hero: {
        badge: 'End-to-end encrypted messaging',
        kicker: 'Two ends. One line.',
        headline: 'No one gets in between.',
        intro: 'us2us is a private line between two people. It never asks for your phone number, sends no notifications, and, if you want, erases quiet conversations without a trace.',
        facts: [
          { icon: 'phone-slash', text: 'No phone number' },
          { icon: 'bell-slash', text: 'No push notifications' },
          { icon: 'password', text: 'Locked with a secret code' },
        ],
      },
      stores: { label: 'App stores', comingSoon: 'Coming soon' },
      band: {
        label: 'us2us in numbers',
        stats: [
          { value: '0', label: 'phone numbers', text: 'Sign-up and search use usernames only.' },
          { value: '0', label: 'push notifications', text: 'Messages arrive only while the app is open.' },
          { value: '2', label: 'ends', text: 'Every conversation is a line between two people.' },
        ],
      },
      how: {
        eyebrow: 'How it works',
        title: 'Sealed at your end. Opened only at theirs.',
        intro: 'Encryption happens on your device. The server in between only carries a sealed envelope — it can’t look inside, and only the two of you hold the keys that open it.',
        points: [
          { n: '01', icon: 'at', title: 'A username, not a number', body: 'Sign up and find people by username only. Search needs an exact match — no suggestions, no autocomplete.' },
          { n: '02', icon: 'password', title: 'Opens with a secret code', body: 'Opening the app asks for your code, or Face ID or fingerprint if you choose. Forget the code and your data is gone for good.' },
          { n: '03', icon: 'qr-code', title: 'Safety number', body: 'Scan their QR code in person. If the numbers match, it’s only the two of you on the line.' },
        ],
      },
      statuses: {
        eyebrow: 'No push notifications',
        title: 'No notifications. On purpose.',
        intro: 'Notifications pass through Apple’s and Google’s servers and leave traces on your lock screen. On us2us, messages arrive only while the app is open — in return, you can see each message’s status.',
        label: 'Message statuses',
        items: [
          { icon: 'check', tone: 'muted', label: 'Sent', text: 'Will be delivered when they open the app.' },
          { icon: 'checks', tone: 'muted', label: 'Delivered', text: 'It reached their device.' },
          { icon: 'checks-fill', tone: 'accent', label: 'Read', text: 'They opened it (if they share read receipts).' },
          { icon: 'warning-circle', tone: 'danger', label: 'Not delivered', text: 'They didn’t open the app within 14 days, so it never reached them.' },
        ],
      },
      ghost: {
        eyebrow: 'Ghost mode',
        title: 'A quiet conversation deletes itself.',
        intro: 'Turn on ghost mode and, if the conversation stays quiet for the time you choose, it’s wiped from both ends. Every new message resets the timer, and the countdown is always visible in the chat header.',
        contact: 'deniz',
        presence: 'Active',
        timerLabel: 'Time until deletion',
        windowLabel: 'Silence window',
        windows: ['1 hour', '24 hours', '7 days'],
        selectedWindow: 1,
      },
      features: {
        eyebrow: 'Privacy',
        title: 'Fewer traces. You stay in control.',
        items: [
          { icon: 'timer', title: 'Disappearing messages', body: 'When the timer runs out, the message is gone on both sides.' },
          { icon: 'eye-closed', title: 'View-once media', body: 'Photos and videos open once, then disappear.' },
          { icon: 'trash', title: 'Delete for everyone', body: 'Remove a message you sent from their end too.' },
          { icon: 'tray', title: 'Message requests', body: 'A stranger’s first message lands in a separate inbox. No chat opens until you accept.' },
          { icon: 'circle-fill', title: 'Active status', body: 'Only people you have accepted see your green dot and “Active just now”. It works both ways: hide yours and you won’t see theirs.' },
          { icon: 'camera-slash', title: 'Screenshot protection', body: 'Blocked on Android. On iOS, screenshots are detected and flagged in the chat.' },
          { icon: 'drop-half', title: 'Blurred preview', body: 'Your chats stay hidden behind a blur in the app switcher.' },
          { icon: 'file-lock', title: 'Encrypted account backup', body: 'Export your account and contacts to one file locked with your secret code and carry on with the same username on a new phone. Messages are never included.' },
        ],
        kindsTitle: 'What the line carries',
        kinds: [
          { icon: 'text-aa', label: 'Text' },
          { icon: 'image', label: 'Photo' },
          { icon: 'video-camera', label: 'Video' },
          { icon: 'microphone', label: 'Voice message' },
          { icon: 'file', label: 'File' },
          { icon: 'map-pin', label: 'Location' },
          { icon: 'navigation-arrow', label: 'Live location' },
          { icon: 'sticker', label: 'Sticker' },
          { icon: 'smiley', label: 'Reaction' },
          { icon: 'arrow-bend-up-left', label: 'Reply' },
        ],
      },
      verify: {
        eyebrow: 'Verification',
        title: 'Is it really them on the other end?',
        intro: 'Every conversation has a safety number. When you’re together, one of you scans the other’s QR code; if they match, the chat is marked as verified.',
        cardTitle: 'Safety number',
        cardSubtitle: 'you and deniz',
        verified: 'Verified',
        scan: 'Scan QR code',
        example: 'Example only; not a real QR code or safety number.',
      },
      cta: {
        title: 'Both ends of the line are yours.',
        intro: 'Pick a username, set your secret code, open the line. Coming soon to iOS and Android.',
      },
    },
    privacy: {
      title: 'Privacy policy',
      description: 'What the us2us server stores, for how long, and what it can never see.',
      updated: 'Last updated: 8 October 2026',
      summary: [
        'us2us is designed so that your conversations live only on your phone and the phone of the person you talk to, not on our servers.',
        'Our server (the “relay”) only carries encrypted envelopes until they are delivered. It cannot see what your messages say, who sent a sealed message, your username, your contact list or your location; what it can see is listed below. We never ask for your phone number or e-mail address.',
      ],
      storedTitle: 'What the relay stores, and for how long',
      storedIntro: 'The relay stores only the following. Apart from random account ids and the last connection day, everything is either a public key, encrypted data, or a one-way hash.',
      columns: { data: 'Data', lifetime: 'How long' },
      stored: [
        { data: 'Random account id', lifetime: 'Until you delete your account, or after 180 days without any connection' },
        { data: 'Hash of your username (PBKDF2, 100,000 rounds; never the username itself)', lifetime: 'As long as the account. After a username change the old hash stays reserved for you for 14 days, so nobody can take it.' },
        { data: 'Your public keys (identity key and pre-keys for starting sessions)', lifetime: 'As long as the account. One-time pre-keys are deleted the moment they are handed out.' },
        { data: 'SHA-256 hashes of your login token and access key', lifetime: 'As long as the account; replaced ones are deleted at once' },
        { data: 'Last connection date (day only, no time)', lifetime: 'As long as the account' },
        { data: 'Encrypted message envelopes and the recipient account id', lifetime: 'Until your phone confirms delivery, at most 14 days' },
        { data: 'Encrypted files (photos, videos, voice, documents) and the uploading account', lifetime: 'Until deleted (usually right after the recipient downloads them), at most 14 days' },
        { data: 'Hashes of used anti-spam tokens', lifetime: 'Until the token expires (at most 10 minutes)' },
      ],
      neverTitle: 'What the relay can never see',
      never: [
        'The content of your messages, photos, files and voice notes',
        'Who sent a sealed message',
        'Your username in clear text',
        'Your contacts, display name and profile photo',
        'Your location or live location',
        'Your secret code, or anything derived from it',
      ],
      seesTitle: 'What the relay sees while it works',
      sees: [
        'Your IP address while you are connected. The relay does not log IP addresses; use Tor or a VPN if you want to hide yours.',
        'Which account each envelope is addressed to, its (padded) size and its time.',
        'Which account uploads a file.',
        'When you look up someone new, or get a first message from them, your phone asks the relay for their account and keys. These lookups are made with your account, so the relay learns, once per new contact, which account looked up which.',
        'Your “active” status is end-to-end encrypted and the relay stores nothing about it. But each time the app opens, it sends one batch of encrypted envelopes to the contacts you share your status with; a hostile relay that logged IP addresses and timing could use those batches to guess contact lists. Our relay keeps no such logs. If you want protection anyway, turn off “Show when I’m active” or use Tor or a VPN.',
      ],
      appleGoogleTitle: 'Apple, Google and notifications',
      appleGoogle: [
        'us2us uses no push notifications. Apple and Google are not involved at all: no notification is ever sent, and no identifier of your device is given to them or to us, so they cannot learn who receives messages, or when.',
        'The trade-off: while the app is closed you cannot see new messages. They arrive when you open the app, directly from our relay.',
        'Downloading the app from the App Store or Google Play is subject to Apple’s and Google’s own privacy policies.',
      ],
      phoneTitle: 'Data on your phone',
      phone: [
        'Your messages, contacts and settings exist only on your phone, encrypted with your secret code. The code never leaves your phone.',
        'Only you create an account backup file; it is encrypted with your secret code and never sent to us. Messages are not part of it.',
      ],
      securityTitle: 'Security',
      security: [
        'Messages are encrypted on your phone with libsignal, the official Signal protocol library (PQXDH and Double Ratchet, post-quantum protected). The private keys exist only on your phones (and, if you export one, in your account backup, encrypted with your secret code).',
        'With sealed sender, the server knows where a message goes but not who sent it.',
        'Everything on your phone (keys, messages, photos, contacts, settings) is encrypted with your secret code. The code never leaves your phone and cannot be reset; there is no back door.',
        'Every conversation has a safety number. When you are together, scan the QR code to check that you are really talking to them. If a contact’s key changes, the app warns you and sends nothing to them until you accept it.',
        'Found a security problem? Please e-mail us with “security” in the subject line, and please keep it private until we have fixed it.',
      ],
      limitsTitle: 'To be honest',
      limits: [
        'The other person can photograph the screen or use a modified app; no app can fully protect messages that “disappear”. Android blocks screenshots; iOS can only detect them.',
        'Ghost-mode and disappearing-message deletions happen the first moment after the deadline that the app or its local timer runs. iOS does not guarantee background tasks, so on an iPhone the deletion may wait until the app is next opened; until then the data stays encrypted with your secret code and is never shown after its deadline.',
        'If the relay goes down abruptly (for example in a power cut), messages held in memory at that moment can be lost; the sender sees “Sent” without “Delivered” and can send again.',
      ],
      noTrackingTitle: 'Analytics, ads and logs',
      noTracking: [
        'There are no analytics, ads, trackers or cookies in the app or on this site. This site loads no fonts or scripts from anywhere else; your theme choice is kept only in your browser (localStorage) and are never sent to us.',
        'The relay does not log request contents, tokens, keys or IP addresses. The web server keeps no access logs, and its error log records only serious faults of the server itself, such as running out of connections; such an entry can name the IP address and the request involved. The error log is deleted after 14 days.',
        'We never back up messages or files. For disaster recovery only the account directory (account ids, username hashes, public keys, token and access-key hashes, last connection day) may be backed up, encrypted, and such backups are kept at most 7 days.',
      ],
      deletionTitle: 'Deleting your data',
      deletion: [
        'When you delete your account in the app, your account, keys, waiting messages and the files you uploaded are deleted from the server immediately. Your account record (account id, username hash, public keys) can remain in encrypted disaster-recovery backups for up to 7 more days.',
        'If you uninstall the app and never connect again, your account is deleted automatically after 180 days.',
        'Because we do not know your phone number or e-mail, we cannot identify you; that is why deletion happens inside the app.',
      ],
      contactTitle: 'Contact',
      contact: 'Questions about this policy? Write to us:',
    },
    support: {
      title: 'Support',
      description: 'Frequently asked questions about us2us, and how to reach us.',
      intro: 'Something not working, or a question? Reach us by e-mail. Never send us your secret code, your backup file or your messages; we do not need them to help you. Press enquiries are welcome at the same address.',
      emailLabel: 'E-mail',
      faqTitle: 'Frequently asked questions',
      faq: [
        {
          question: 'I forgot my secret code. What can I do?',
          answer: 'Unfortunately, nothing. All data on your phone is encrypted with that code and the code is never sent anywhere, so nobody, including us, can reset it. You will need to create a new account.',
        },
        {
          question: 'Can I change my username?',
          answer: 'Yes, at any time (up to 5 times a day). Your username is only a label that lets people find you; your account and encryption rest on keys created on your phone. Your conversations and safety numbers stay the same, and your contacts are told your new name automatically. Your old name stays reserved for you for 14 days.',
        },
        {
          question: 'How do I move to a new phone?',
          answer: 'Create an account backup on the old phone, move the file to the new phone and open it with your secret code. The old phone loses access. Message history is not part of the backup.',
        },
        {
          question: 'Why doesn’t us2us send notifications?',
          answer: 'Because notifications travel through Apple’s and Google’s servers and tell them which app sent a message to which phone, and when. In us2us, messages are fetched only while the app is open, directly from our blind relay. No company, not Apple, not Google, not us, gets a report of who received a message and when. Messages wait for you, encrypted, for up to 14 days.',
        },
        {
          question: 'How do I delete my account?',
          answer: 'Choose “Delete account” in the app’s settings. Everything on the server is deleted immediately; only the encrypted disaster-recovery backups may keep your account record for up to 7 more days.',
        },
        {
          question: 'I found a security problem.',
          answer: 'Please e-mail us with “security” in the subject line, and please keep it private until we have fixed it.',
        },
      ],
      licencesTitle: 'Licences',
      licencesIntro: 'us2us’s code (the apps and the relay that also serves this site) is licensed under AGPL-3.0. The source code will be published soon; we will announce its address on this page.',
      licencesListIntro: 'Fonts and libraries used by this site:',
      licences: LICENCES,
    },
  },
}

/**
 * The 404 page shows both languages at once, since it cannot know the
 * visitor's. Used by app/error.vue and by the relay's static 404
 * (server/middleware/not-found.ts).
 */
export const notFoundText: Record<Locale, { message: string, home: string }> = {
  tr: { message: 'Aradığınız sayfa bulunamadı.', home: 'Ana sayfa' },
  en: { message: 'The page you are looking for does not exist.', home: 'Home' },
}
