/*
 * All website text, Turkish and English. Every entry exists in both
 * languages; the `SiteText` type makes a missing translation a type error.
 * The landing page follows the "us2us Web" design; the privacy
 * policy mirrors PROTOCOL.md §4.10 (retention) and §9 (known limits), and
 * the "no notifications" texts follow docs/copy/no-push.md; change them
 * together. The "why we built it" page has its own file, ./why.ts.
 *
 * Headings are plain strings: a full stop that ends a sentence is drawn in
 * the body font by <SiteDots>, as in the design.
 */

import type { IconName } from './icons'

export type Locale = 'tr' | 'en'
export type PageId = 'home' | 'privacy' | 'support' | 'why'

export const paths: Record<Locale, Record<PageId, string>> = {
  tr: { home: '/tr', privacy: '/tr/gizlilik', support: '/tr/destek', why: '/tr/neden-yaptik' },
  en: { home: '/en', privacy: '/en/privacy', support: '/en/support', why: '/en/why-we-built-it' },
}

export const SITE_ORIGIN = 'https://us2us.app'
export const SUPPORT_EMAIL = 'support@us2us.app'
/** Where security problems go (the support page's beta notes, the FAQ and the privacy policy). */
export const SECURITY_EMAIL = 'security@us2us.app'

/** The support page's beta-tester notes: one id in both languages (`/tr/destek#beta`, `/en/support#beta`). */
export const BETA_ANCHOR = 'beta'

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

/** us2us's own source, then the third-party licence texts served by the site itself (public/fonts, public/licenses). */
const LICENCES: SiteText['support']['licences'] = [
  { name: 'us2us', licence: 'AGPL-3.0 · github.com/syconx-software/us2us', href: 'https://github.com/syconx-software/us2us' },
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

/** A note card on the support page; `email` is shown as a mail link under the text. */
interface Note {
  icon: IconName
  tone?: 'danger'
  title: string
  body: string
  email?: string
}

export interface SiteText {
  languageName: string
  skipLink: string
  nav: {
    label: string
    languageLabel: string
    themeLabel: string
    home: string
    sections: Array<{ id: string, label: string }>
    /** The "why we built it" page (WhyPage.vue, text in ./why.ts): the header's last link, right after the sections, and the footer's first. */
    why: string
    /** `why` as the header shows it on phones, where the full label does not fit beside the controls (screen readers still read `why`). */
    whyShort: string
  }
  footer: { label: string, privacy: string, security: string, press: string, contact: string, licence: string, noTracking: string }
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
    /** `beta`: the line under the store buttons (closed beta on iOS; the stores are not open yet). */
    stores: { label: string, comingSoon: string, beta: string }
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
    beta: { title: string, intro: string, notes: Note[] }
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
      why: 'Neden yaptık',
      whyShort: 'Neden?',
    },
    footer: {
      label: 'Site bağlantıları',
      privacy: 'Gizlilik politikası',
      security: 'Güvenlik',
      press: 'Basın',
      contact: 'İletişim',
      licence: 'AGPL-3.0 · Açık kaynak',
      noTracking: 'Bu site çerez ve izleyici kullanmaz, başka bir siteden hiçbir şey yüklemez.',
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
      stores: { label: 'Uygulama mağazaları', comingSoon: 'Yakında', beta: 'Şu an iOS’ta kapalı betada (TestFlight, davetle). App Store ve Android sürümü yakında.' },
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
        intro: 'Şifreleme cihazında olur. Aradaki sunucu yalnızca kapalı zarfı taşır; içini göremez, onu açan anahtarlar yalnızca ikinizde. Güvenlik numarasını karşılaştırdığınızda bundan emin olursunuz.',
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
          { icon: 'file-lock', title: 'Şifreli hesap yedeği', body: 'Hesabını ve kişilerini yedek parolanla şifrelenmiş tek bir dosyaya aktar; yeni telefonunda aynı kullanıcı adıyla devam et. Mesajlar yedeğe girmez.' },
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
      updated: 'Son güncelleme: 11 Ekim 2026',
      summary: [
        'us2us, konuşmalarınızı sunucularımızda değil, yalnızca sizin ve konuştuğunuz kişinin telefonunda tutacak şekilde tasarlandı.',
        'Sunucumuz (“aktarıcı”) yalnızca şifreli zarfları teslim edene kadar taşır. Mesajlarınızın içeriğini, konumunuzu ve mühürlü bir zarfın içindeki gönderen bilgisini okuyamaz; kullanıcı adınızı yalnızca özet (hash) olarak tutar, kişi listenizi hiç saklamaz. Neleri görebildiği ve bunlardan neler çıkarılabileceği aşağıda. Telefon numaranızı ya da e-posta adresinizi hiç istemeyiz.',
      ],
      storedTitle: 'Sunucunun sakladıkları ve saklama süreleri',
      storedIntro: 'Aktarıcı yalnızca aşağıdakileri saklar. Rastgele hesap kimlikleri, son bağlantı günü ve profil resmi kaydının sürüm numarası dışında hepsi ya herkese açık anahtar, ya şifreli veri ya da geri çevrilemeyen özettir (hash).',
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
        { data: 'Profil resminiz, yalnızca “Aramada göster” açıksa (varsayılan): kullanıcı adınızdan türetilen bir anahtarla şifrelenmiş tek bir kayıt, boyutu (8, 16, 32 ya da 48 KiB) ve sürüm numarası. Kullanıcı adınızı bilen ya da tahmin eden herkes, aktarıcı da dahil, onu açabilir.', lifetime: 'Resminizi değiştirene, “Aramada göster”i kapatana, baş harfe dönene, kullanıcı adınızı değiştirene ya da hesabınızı silene kadar. Son sürüm numarası, eski bir kaydın geri gelmemesi için hesapla birlikte kalır.' },
      ],
      neverTitle: 'Sunucunun asla göremedikleri',
      never: [
        'Mesajlarınızın, fotoğraflarınızın, dosyalarınızın ve sesli mesajlarınızın içeriği',
        'Mühürlü bir zarfın içindeki gönderen bilgisi (zarfı yalnızca alıcının telefonu açabilir; sınırları aşağıda)',
        'Kullanıcı adınızın açık hâli; bir davet bağlantısı açılmadıkça ya da önizlenmedikçe (aşağıda)',
        'Telefonunuzdaki kişi listeniz ve görünen adınız (yeni kişi sorgularının bıraktığı iz aşağıda)',
        'Profil resminiz, “Aramada göster” kapalıysa: o zaman resim yalnızca kişilerinize uçtan uca şifreli gider. Ayar açıksa (varsayılan) resminiz kullanıcı adınızla şifrelenmiş olarak saklanır; kullanıcı adınızı bilen ya da tahmin eden herkes, aktarıcı da dahil, onu açabilir.',
        'Konumunuz ve canlı konumunuz',
        'Gizli kodunuz ya da ondan türetilen herhangi bir şey',
      ],
      seesTitle: 'Sunucunun çalışırken görebildikleri',
      sees: [
        'Bağlandığınız andaki IP adresiniz. Aktarıcı IP adreslerini kayıt altına almaz; gizlemek isterseniz Tor ya da VPN kullanabilirsiniz.',
        'Her zarfın hangi hesaba gittiği, boyutu (dolgulu) ve zamanı.',
        'Hangi hesabın dosya yüklediği ve dosyanın (dolgulu) boyutu; bir sesli mesajın boyutu aşağı yukarı süresini ele verir.',
        'Yeni bir kişiyi bulduğunuzda ya da ondan ilk mesajı aldığınızda telefonunuz onun hesabını ve anahtarlarını sunucuya sorar. Bu sorgular hesabınızla yapıldığı için sunucu, her yeni kişi için bir kez, hangi hesabın hangi hesabı sorguladığını öğrenir.',
        'Mühürlü bir zarfın, alıcının kabul ettiği kişilerden birinden mi yoksa henüz kabul etmediği birinden (mesaj isteği) mi geldiği; hangi kişiden geldiği değil.',
        'Aramada bir profil resmi istendiğinde hangi hesabın hangi hesabın resmine baktığı. Ayrıca hangi hesapların aramada resim gösterdiği, resmi ne zaman değiştirdikleri ve kaydın boyutu: en küçüğü (8 KiB) her hazır avatarı ve küçük fotoğrafları tutar; daha büyüğü kesinlikle bir fotoğraftır ve aşağı yukarı ne kadar ayrıntılı olduğunu ele verir. Hesap kimliğinizi bilen başka bir hesap da (eski kişiler ve engellediğiniz biri dahil) aynı şeyleri görebilir.',
        'Bir davet bağlantısı (us2us.app/u/…) tarayıcıda açıldığında ya da bir mesajlaşma uygulaması onun önizlemesini oluşturduğunda, bağlantıdaki kullanıcı adı sunucumuza açık hâliyle ulaşır. Sayfa adı hiçbir yere yazmaz ve hiçbir şey sorgulamaz; ama adı bilen bir sunucu, “Aramada göster” açıksa o hesabın profil resmini açabilir.',
        '“Aktif” durumu uçtan uca şifrelidir ve sunucu bununla ilgili hiçbir şey saklamaz. Ancak uygulama her açılışta, durumunu paylaştığı kişilere tek seferde şifreli zarflar gönderir; IP adreslerini ve zamanları kaydeden kötü niyetli bir sunucu bu toplu gönderimlerden kişi listelerini tahmin edebilirdi. Bizim sunucumuz böyle bir kayıt tutmaz. Yine de korunmak isterseniz “Aktif olduğumu göster” ayarını kapatabilir ya da Tor veya VPN kullanabilirsiniz.',
      ],
      appleGoogleTitle: 'Apple, Google, bildirimler ve haritalar',
      appleGoogle: [
        'us2us bildirim (push) kullanmaz. Mesajlarınızın tesliminde Apple ve Google hiç devrede değildir: ne bildirim gönderilir ne de cihazınızı tanıtan bir kimlik onlara ya da bize verilir. Bu yüzden kimin ne zaman mesaj aldığını öğrenemezler.',
        'Bunun bedeli şu: uygulama kapalıyken yeni mesajları göremezsiniz. Mesajlarınız uygulamayı açtığınızda doğrudan sunucumuzdan gelir.',
        'Haritalar: canlı konum haritasını açtığınızda harita görüntüleri Apple Haritalar’dan yüklenir; Apple, IP adresinizle birlikte görüntülenen bölgeyi görebilir, ama haritanın kimin konumunu gösterdiğini ve mesajlarınızı bilmez. Harita yalnızca siz açtığınızda yüklenir; sohbetteki konum balonları harita yüklemez. Harita görüntüleri sistem tarafından telefonunuzda önbelleğe alınabilir. Bir konumu “Apple Haritalar’da aç” ya da “Google Maps’te aç” ile açarsanız konumun koordinatları (Apple Haritalar’a, varsa yerin adı da) seçtiğiniz uygulamaya verilir; ondan sonrası o uygulamanın gizlilik politikasına tabidir. Süreli bir mesajdaki konumu başka bir uygulamada açarsanız, o uygulama konumu kendi geçmişinde tutabilir.',
        'Uygulamayı App Store ya da Google Play’den indirmeniz, Apple’ın ve Google’ın kendi gizlilik politikalarına tabidir.',
        'Beta: us2us’ı TestFlight ile deniyorsanız Apple, beta testi için topladığı bazı bilgileri bize gösterir: sizi e-postayla davet ettiysek adınız ve e-posta adresiniz, yükleme ve oturum sayıları, çökme raporları ve TestFlight’tan gönderdiğiniz geri bildirimler (eklediğiniz ekran görüntüleri dahil). Bunlar mesajlarınızı içermez; yalnızca geri bildirime eklediğiniz bir ekran görüntüsünde ne görünüyorsa o bize ulaşır. TestFlight’ı kullanmanız Apple’ın kendi koşullarına ve gizlilik politikasına tabidir.',
        `Bildirimler: uygulamadaki “Bildir” ile birini bildirdiğinizde telefonunuz, sizin göndereceğiniz bir e-postayı ${SUPPORT_EMAIL} adresine hazırlar. İçinde yalnızca bildirdiğiniz kişinin kullanıcı adı, seçtiğiniz neden, yazdığınız not, uygulama sürümü ve saat (UTC) bulunur. Mesajlar uçtan uca şifreli olduğu için bildirime hiçbir mesaj içeriği eklenmez; ekran görüntüsü yalnızca siz eklerseniz bize ulaşır. E-posta adresinizi ve e-postada yazanları yalnızca bildirimi incelemek ve size yanıt vermek için kullanırız.`,
      ],
      phoneTitle: 'Telefonunuzdaki veriler',
      phone: [
        'Mesajlarınız, kişileriniz ve ayarlarınız yalnızca telefonunuzda, gizli kodunuzla şifrelenmiş olarak durur. Kod telefonunuzdan hiç çıkmaz.',
        'Hesap yedeği dosyasını yalnızca siz oluşturursunuz; gizli kodunuzdan farklı bir yedek parolasıyla şifrelenir ve bize hiç gönderilmez. Mesajlar yedeğe dahil değildir. Yedek dosyasını ele geçiren biri parolayı çevrimdışı ve sınırsızca deneyebilir; bu yüzden uygulamanın sizin için oluşturduğu rastgele parolayı kullanmanızı öneririz.',
        'Mikrofon yalnızca siz sesli mesaj kaydederken açılır; kayıt yalnızca siz gönderirseniz telefonunuzdan çıkar, uçtan uca şifreli gider ve sunucumuz yalnızca şifreli dosyayı taşır.',
      ],
      securityTitle: 'Güvenlik',
      security: [
        'Mesajlar, Signal protokolünün resmî kütüphanesi libsignal ile telefonunuzda şifrelenir (PQXDH ve üçlü ratchet: Double Ratchet + SPQR); böylece mesaj içerikleri, bugün kaydedilip ileride bir kuantum bilgisayarla çözülme girişimlerine karşı da korunur. Bu kuantum sonrası koruma mesaj içeriği içindir; mühürlü zarftaki gönderen bilgisi ve kimlik doğrulama bugün klasik (eliptik eğri) şifrelemeye dayanır. Gizli anahtarlar yalnızca telefonlarınızda (ve dışa aktardıysanız yedek parolanızla şifrelenmiş hesap yedeğinizde) bulunur.',
        'Mühürlü gönderici sayesinde zarfın üzerinde gönderen yazmaz: sunucu bir mesajın kime gittiğini görür, kimden geldiğini zarftan okuyamaz. Bu tam bir anonimlik değildir; IP adresi, zamanlama ve yeni kişi sorguları gibi izler ipucu verebilir (aşağıda).',
        'Telefonunuzdaki her şey (anahtarlar, mesajlar, fotoğraflar, kişiler, ayarlar) gizli kodunuzla şifrelenir. Kod telefonunuzdan hiç çıkmaz ve sıfırlanamaz; arka kapı yoktur. İki istisna var: iPhone’da bir dosyayı önizlerken, kaydederken, gönderirken ya da alırken uygulama onun geçici bir kopyasını iOS dosya korumasıyla (telefon kilitliyken okunamaz) tutar; kopya önizleme ya da aktarım bitince, en geç uygulamanın bir sonraki açılışında silinir. Sesli mesaj da siz kaydederken böyle korunan geçici bir dosyada durur; kayıt durunca belleğe alınır ve o dosya hemen silinir (uygulama kayıt sırasında kapanırsa en geç bir sonraki açılışta). Gönderilirken ve alınırken her dosya gibi kısa süre korumalı bir geçici kopyası olur.',
        'Her sohbetin bir güvenlik numarası vardır. Yan yana geldiğinizde QR kodunu okutarak konuştuğunuz kişinin gerçekten o olduğunu doğrulayabilirsiniz. Bir kişinin anahtarı değişirse uygulama sizi uyarır ve siz numaraları karşılaştırana ya da “Doğrulamadan devam et”i seçene kadar ona mesaj göndermez; ikinci durumda sohbet doğrulanmamış kalır.',
        `Bir güvenlik açığı bulduysanız ${SECURITY_EMAIL} adresine yazın. Açığı düzeltene kadar herkese açık paylaşmamanızı rica ederiz.`,
      ],
      limitsTitle: 'Dürüst olmak gerekirse',
      limits: [
        'Karşı taraf ekranın fotoğrafını çekebilir ya da değiştirilmiş bir uygulama kullanabilir; “kaybolan” mesajları hiçbir uygulama tamamen koruyamaz. Android ekran görüntüsünü engeller, iOS ise yalnızca algılayabilir.',
        'Hayalet mod ve süreli mesaj silmeleri, süre dolduktan sonra uygulamanın ya da telefondaki zamanlayıcının çalıştığı ilk anda yapılır. iOS arka plan görevlerini garanti etmediği için iPhone’da silme bir sonraki açılışa kalabilir; o zamana kadar veriler gizli kodunuzla şifreli durur ve süresi geçtikten sonra hiç gösterilmez.',
        'Sunucu beklenmedik biçimde kapanırsa (örneğin elektrik kesintisinde), o an bellekte bekleyen mesajlar kaybolabilir; gönderen “Gönderildi”yi görür ama “İletildi”yi görmez ve mesajı yeniden gönderebilir.',
        'Güvenlik numarasını henüz karşılaştırmadığınız biriyle ilk yazıştığınızda onun açık anahtarını sunucumuzdan alırsınız. Ele geçirilmiş bir sunucu o anda araya kendi anahtarını koyabilir; bu ancak güvenlik numaralarını karşılaştırdığınızda ortaya çıkar. Yan yana gelip QR kodunu bir kez okutmanız bu riski ortadan kaldırır.',
        'Mühürlü gönderici tam bir anonimlik sağlamaz. Telefonunuz mesaj gönderirken sunucuya, oturum açmış bağlantısıyla aynı IP adresinden ulaşır. IP adreslerini ve zamanları kaydeden kötü niyetli bir sunucu, gönderilen bir zarfı gönderen hesapla, yüklenen bir dosyayı da onu indiren hesapla eşleştirebilirdi. VPN gerçek IP adresinizi gizler ama bu eşleştirmeyi tek başına engellemez. Bizim sunucumuz böyle bir kayıt tutmaz.',
        'Kuantum sonrası koruma mesaj içerikleri içindir. Mühürlü zarf, alıcının uzun ömürlü kimlik anahtarına klasik (X25519) şifrelemeyle kapatılır. Zarfları bugün kaydeden biri, ileride alıcının kimlik anahtarını ele geçirirse (örneğin telefondan ya da parolasıyla birlikte hesap yedeğinden) veya yeterince güçlü bir kuantum bilgisayar kurarsa, o zarfların kimden geldiğini öğrenebilir; mesajların içeriğini yine okuyamaz.',
        'Aramada gösterilen profil resmi yalnızca kullanıcı adınızla korunur. Kullanıcı adınızı bilen herkes, kişiniz olmasa da ve siz fark etmeden resminizi görebilir; engellediğiniz biri de. Aktarıcı kullanıcı adınızı bilmez ama tahmin edebilir: sık kullanılan ya da kısa adları deneyerek resminizi açabilir, arayanlara başka bir resim ya da sizin eski bir resminizi (silmiş olduğunuz biri dahil) gösterebilir. Uzun ve sıradan olmayan bir kullanıcı adı bunu yavaşlatır, imkânsız kılmaz. Bunu istemiyorsanız “Aramada göster”i kapatın; o zaman resminiz yalnızca kişilerinize uçtan uca şifreli gider. Birinden uzaklaşmak için kullanıcı adınızı değiştiriyorsanız ayarı da kapatın. Kişileriniz her zaman uçtan uca şifreli kopyayı görür.',
        'Gizli kodunuz 6 hanelidir. Her deneme bu telefondaki cihaz sırrını gerektirdiği için kod yalnızca telefonda, uygulamanın bekleme süreleriyle denenebilir. iPhone’da her deneme ayrıca Secure Enclave’den hiç çıkmayan bir anahtar ister (1.0.5 sürümünden itibaren; mevcut veriler bu korumaya kodunuzu ilk girdiğinizde geçer); bu yüzden telefondan çıkarılan dosyalarla kod başka bir yerde denenemez. Ancak telefonunuzun kilidini açabilen ve işletim sistemini kıran biri (örneğin kilit kodunuzu bilen biri, adli bir araçla) uygulamayı atlayıp kodları telefonun kendisinde, bekleme süreleri olmadan deneyebilir. Buna karşı en iyi koruma güçlü bir telefon kilit kodu ve güncel bir işletim sistemidir; gizli kodunuzu telefonunuzun kilit kodu olarak kullanmayın.',
      ],
      noTrackingTitle: 'Analiz, reklam ve kayıtlar',
      noTracking: [
        'Uygulamada ve bu sitede analiz aracı, reklam, izleyici ya da çerez yoktur. Bu site dış kaynaktan yazı tipi ya da betik yüklemez; tema seçiminiz yalnızca tarayıcınızda (localStorage) saklanır, bize gönderilmez.',
        'Aktarıcı istek içeriklerini, belirteçleri, anahtarları ve IP adreslerini kayıt altına almaz. Web sunucusu erişim kaydı tutmaz; hata kaydına yalnızca sunucunun kendisindeki ciddi arızalar (örneğin bağlantı kapasitesinin dolması) yazılır. Böyle bir kayıtta ilgili IP adresi ve istek yer alabilir. Hata kayıtları 14 gün sonra silinir.',
        'Mesajları ve dosyaları asla yedeklemeyiz. Felaket kurtarma için yalnızca hesap dizini (hesap kimlikleri, kullanıcı adı özetleri, açık anahtarlar, belirteç ve erişim anahtarı özetleri, son bağlantı günü) şifreli olarak yedeklenebilir; bu yedekler en fazla 7 gün tutulur.',
      ],
      deletionTitle: 'Verilerinizi silme',
      deletion: [
        'Uygulamada Ayarlar › Hesap › “Her şeyi sil” ile hesabınızı sildiğinizde hesabınız, kullanıcı adınızın özeti, anahtarlarınız, profil resmi kaydınız, bekleyen mesajlarınız ve yüklediğiniz dosyalar sunucudan hemen silinir; ardından telefonunuzdaki tüm veriler silinir. Kullanıcı adınız hemen serbest kalır ve silinen hesap bir yedekten geri getirilemez. Hesap kaydınız (hesap kimliği, kullanıcı adı özeti, açık anahtarlar) şifreli felaket kurtarma yedeklerinde en fazla 7 gün daha kalabilir.',
        '“Aramada göster”i kapattığınızda, baş harfe döndüğünüzde ya da kullanıcı adınızı değiştirdiğinizde profil resmi kaydınız sunucudan silinir (sunucuya o an ulaşılamazsa ilk bağlantıda). Resminizi daha önce görmüş olanların telefonlarındaki kopyalar geri alınamaz.',
        'Sunucu silmeyi onaylamazsa (örneğin sunucuya ulaşılamazsa) uygulama telefonunuzda hiçbir şeyi silmez; tekrar deneyebilir ya da yalnızca telefonunuzdaki verileri silebilirsiniz. Gizli kodunuzu unuttuysanız hesap yedeğinizi yedek parolasıyla geri yükleyip hesabı Ayarlar’dan silebilirsiniz; yedeğiniz yoksa yalnızca telefon silinebilir, çünkü hesabınıza o kod olmadan erişilemez.',
        'Hesabınız sunucuda kaldıysa (uygulamayı sildiyseniz ya da yalnızca telefonu sildiyseniz) ve bir daha bağlanmazsanız, hesabınız 180 gün sonra kendiliğinden silinir; o zamana kadar size yazılanlar size ulaşmaz.',
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
      beta: {
        title: 'Beta testçileri için',
        intro: 'us2us şu an iOS’ta, TestFlight üzerinden kapalı betada; App Store ve Android sürümü yakında. Beta derlemeleri sık güncellenir ve hata içerebilir. Verilerinizi korumak için şunları bilin:',
        notes: [
          {
            icon: 'warning',
            tone: 'danger',
            title: '1.0.5’ten sonra eski bir derlemeye dönmeyin',
            body: '1.0.5 ile birlikte, gizli kodunuzu ilk girdiğinizde telefonunuzdaki verileri açan anahtar, iPhone’un Secure Enclave’iyle korunan yeni bir biçimde yeniden kilitlenir. Daha eski derlemeler bu biçimi açamaz: TestFlight’tan eski bir derleme yüklerseniz verilerinize ulaşamazsınız. Yanlışlıkla yüklediyseniz uygulamada hiçbir şeyi silmeyin ve en yeni derlemeyi yeniden yükleyin.',
          },
          {
            icon: 'file-lock',
            title: 'Yedeğinizi ve parolasını saklayın',
            body: 'Bir şey ters giderse hesabınızı ve kişilerinizi geri getiren tek şey hesap yedeğidir. Ayarlar › Yedek’ten bir yedek alın; dosyayı telefonun dışında bir yerde, parolasını ayrı bir yerde (örneğin bir parola yöneticisinde) saklayın. Parola olmadan yedek açılamaz, biz de açamayız. Mesajlar yedeğe girmez.',
          },
          {
            icon: 'envelope-simple',
            title: 'Hata bildirmek',
            body: 'Bir hata gördüyseniz bize yazın: ne yaptığınızı ve ne olduğunu, telefon modelinizi, iOS sürümünüzü ve Ayarlar › Hakkında’daki sürüm numarasını ekleyin. Ekran görüntüsü gönderecekseniz içinde sohbetlerinizin, güvenlik numaranızın ya da yedek parolanızın görünmediğinden emin olun. TestFlight’ın geri bildirim düğmesini de kullanabilirsiniz; o zaman yazdıklarınız ve ekran görüntünüz Apple üzerinden bize gelir.',
            email: SUPPORT_EMAIL,
          },
          {
            icon: 'shield-check',
            title: 'Güvenlik açıkları',
            body: 'Bir güvenlik açığı bulduysanız lütfen önce herkese açık bir yerde (hata kaydı, forum, sosyal medya) paylaşmayın; doğrudan bu adrese yazın. Açığı düzeltene kadar gizli tutmanızı rica ederiz. Yeniden üretmek için gereken adımları ekleyin ve denemelerinizi yalnızca kendi hesaplarınızla yapın.',
            email: SECURITY_EMAIL,
          },
        ],
      },
      faqTitle: 'Sık sorulan sorular',
      faq: [
        {
          question: 'Gizli kodumu unuttum. Ne yapabilirim?',
          answer: 'Kod kurtarılamaz: telefonunuzdaki veriler bu kodla şifrelenir ve kod hiçbir yere gönderilmez; biz de dahil kimse sıfırlayamaz. Hesap yedeğiniz ve parolası varsa kilit ekranında “Kodu unuttum” › “Yedekten geri yükle” ile hesabınıza ve kişilerinize yeni bir kodla dönebilirsiniz. Mesaj geçmişi yedekte yoktur ve bu telefondaki eski mesajlar silinir (kod olmadan zaten açılamazlar); uygulama yedeği açıp sunucuda hâlâ geçerli olduğunu doğrulamadan hiçbir şeyi silmez. Yedeğiniz yoksa telefonu silip yeni bir hesap açabilirsiniz; eski hesabınız ve kullanıcı adınız 180 gün hiç bağlanılmadığında kendiliğinden silinir.',
        },
        {
          question: 'Yedekten geri yükleyince neler geri gelir?',
          answer: 'Hesabınız (kullanıcı adınız, görünen adınız ve kimlik anahtarınız; bu yüzden güvenlik numaralarınız değişmez) ve kişileriniz, doğruladıklarınız ve engellediklerinizle birlikte geri gelir. Mesajlar, fotoğraflar ve videolar yedekte yoktur, geri gelmez; kilit ekranındaki “Kodu unuttum” yolundan geri yüklerseniz bu telefondaki mesajlar da silinir. Geri yükleme hesabın girişini yeniler: hesabı kullanan başka bir telefon erişimini kaybeder ve daha önce aldığınız yedek dosyaları artık çalışmaz. Bu yüzden geri yükledikten sonra Ayarlar › Yedek’ten yeni bir yedek alın.',
        },
        {
          question: 'Yedek parolamı nasıl seçmeliyim?',
          answer: 'En kolayı, yedeği dışa aktarırken “Güçlü parola oluştur”a dokunmak: uygulama, tirelerle dörder karaktere bölünmüş 20 karakterlik rastgele bir parola üretir. Onu tireleriyle birlikte bir parola yöneticisine ya da kâğıda kaydedin; yedek dosyasıyla aynı yerde saklamayın, ekran görüntüsünü almayın. Kopyaladığınız parola 1 dakika sonra panodan silinir. Kendi parolanızı yazacaksanız 4–5 rastgele kelimeden oluşan uzun bir parola seçin; en az 10 karakter olmalı ve gizli kodunuzdan farklı olmalı. İsim, doğum yılı, takım adı ya da klavye dizisi gibi kolay tahmin edilen parolaları uygulama kabul etmez. Yedek dosyasını ele geçiren biri parolayı sınırsızca deneyebilir; yedeğinizi koruyan tek şey bu paroladır.',
        },
        {
          question: 'Kullanıcı adımı değiştirebilir miyim?',
          answer: 'Şimdilik hayır: uygulamada kullanıcı adı değiştirilemiyor. Kullanıcı adınız yalnızca sizi bulmaya yarayan bir etikettir; hesabınız ve şifreleme, telefonunuzda üretilen anahtarlara dayanır. Başka bir ad istiyorsanız tek yol hesabınızı silip yeni bir hesap açmaktır; o zaman kişileriniz ve sohbetleriniz yeni hesaba gelmez.',
        },
        {
          question: 'Yeni bir telefona nasıl geçerim?',
          answer: 'Eski telefonda bir hesap yedeği oluşturun, dosyayı yeni telefona taşıyın ve yedek parolanızla açın, sonra yeni telefon için bir gizli kod belirleyin. Eski telefonun erişimi kapanır. Mesaj geçmişi yedeğe dahil değildir. Kimlik anahtarınız yedekle taşındığı için güvenlik numaralarınız değişmez.',
        },
        {
          question: 'Sesli mesaj nasıl gönderilir?',
          answer: 'Mesaj alanındaki mikrofona basılı tutup konuşun; bıraktığınızda sesli mesaj gider. Vazgeçmek için parmağınızı sola kaydırın. Yukarı kaydırırsanız kayıt kilitlenir; parmağınızı kaldırıp konuşmaya devam edebilir, sonra kaydı silebilir, durdurup dinleyebilir ya da gönderebilirsiniz. Mikrofon izni ilk basışta istenir; mikrofon yalnızca kayıt sırasında açıktır. Dinlenmemiş bir sesli mesajın yanında nokta görünür. Sesli mesajınızın dinlendiğini yalnızca karşı taraf okundu bilgisini paylaşıyorsa görürsünüz. Sesli mesajlar kopyalanamaz, kaydedilemez ve paylaşılamaz.',
        },
        {
          question: 'Konum ve canlı konum nasıl çalışır?',
          answer: 'Sohbetteki bir konum balonuna dokunduğunuzda yeri “Apple Haritalar’da aç” ya da “Google Maps’te aç” ile açabilirsiniz. Konum seçtiğiniz uygulamaya aktarılır (Google Maps yüklü değilse tarayıcıda açılır); ondan sonrası o uygulamanın gizlilik kurallarına bağlıdır. Süreli bir mesajdaki konumu başka bir uygulamada açarsanız o uygulama konumu kendi geçmişinde tutabilir; us2us onu oradan silemez. Canlı konum balonuna dokunduğunuzda uygulamanın içinde bir harita açılır: karşı tarafın son konumu, kalan süre ve izin verdiyseniz kendi konumunuz. Haritada kendinizi görmeniz konumunuzu paylaşmaz; paylaşım yalnızca siz başlattığınızda başlar ve “Paylaşımı durdur” ile istediğiniz an biter. Harita görüntüleri Apple Haritalar’dan, yalnızca harita açıkken yüklenir.',
        },
        {
          question: 'Fotoğraf, video ve dosyalar nasıl açılır?',
          answer: 'Fotoğraflar ekrana sığacak şekilde açılır ve yakınlaştırılabilir; videolar sohbetin içinde oynar. Dosyalar uygulamanın içinde önizlenir ve isterseniz Dosyalar’a kaydedilebilir. Önizleme için açılan, şifresi çözülmüş geçici kopya önizlemeyi kapattığınızda silinir (en geç uygulama arka plana geçince ya da bir sonraki açılışta). Süreli mesajlardaki dosyalar paylaş düğmesi olmadan açılır ve kaydedilemez; tek görüntülemelik fotoğraf ve videolar bir kez açılır, sonra kaybolur. Mesaj isteğiyle gelen dosyalar, isteği kabul ettiğinizde indirilir. Aldığınız bir fotoğrafı, videoyu ya da dosyayı kaydettiğinizde ya da paylaştığınızda gönderen bunu görür (okundu bilgisi kapalıyken de); uygulama ilk kayıttan önce bunu söyler. Fotoğraf ve videolar Fotoğraflar’a da kaydedilebilir.',
        },
        {
          question: 'Güvenlik numarası nedir, “Doğrulanmadı” ne demek?',
          answer: 'Her sohbetin, ikinizin kimlik anahtarlarından hesaplanan bir güvenlik numarası vardır. Sohbetteki “Doğrulanmadı · QR ile doğrula” yazısı, bu numarayı henüz karşılaştırmadığınızı söyler. Mesajlarınız yine uçtan uca şifrelidir; ama aradaki sunucunun araya kendi anahtarını koymadığından henüz emin değilsiniz. Yan yana geldiğinizde birinizin QR kodunu diğeri okutsun; numaralar eşleşirse sohbet doğrulanır. Yan yana gelemiyorsanız rakamları başka bir kanaldan (örneğin telefonda konuşarak) karşılaştırın ve yalnızca hepsi aynıysa “Doğrulandı olarak işaretle”yi seçin. Numaraları us2us mesajıyla karşılaştırmak bir şey kanıtlamaz.',
        },
        {
          question: '“Güvenlik numarası değişti” uyarısı ne demek?',
          answer: 'Karşı tarafın kimlik anahtarı değişti demektir. Kimlik anahtarı yedekle birlikte taşındığı için bu sık olmaz; biri araya girmeye çalışıyorsa da bu uyarıyı görürsünüz. Uygulama siz karar verene kadar ona mesaj göndermez; ondan gelenler gelmeye devam eder. En güvenlisi, uyarıdaki “Karşılaştır”a dokunup numaraları yeniden karşılaştırmaktır (yan yana QR koduyla ya da başka bir kanaldan). “Doğrulamadan devam et”i seçerseniz mesajlarınız yeni numarayla gönderilir ama sohbet doğrulanmamış kalır; numaraları sonra istediğiniz zaman karşılaştırabilirsiniz.',
        },
        {
          question: 'us2us neden bildirim göndermiyor?',
          answer: 'Çünkü bildirimler Apple ve Google’ın sunucularından geçer ve hangi uygulamanın, hangi telefona, ne zaman mesaj gönderdiğini onlara bildirir. us2us’ta mesajlar sadece uygulama açıkken, doğrudan kör sunucumuz üzerinden alınır. Böylece Apple’a ve Google’a hiçbir şey bildirilmez. Sunucumuz bir zarfın hangi hesaba gittiğini yalnızca onu tutarken bilir, ne zaman teslim edildiğini de teslim ederken görür; ikisini de kayda geçirmez ve telefonunuz teslimi onaylar onaylamaz zarfı siler. Mesajlar, siz uygulamayı açana kadar şifreli olarak en fazla 14 gün bekler. Önemli bir şey bekliyorsanız karşı tarafa “us2us’a bak” demeniz yeterli.',
        },
        {
          question: 'Hesabımı nasıl silerim?',
          answer: 'Uygulamada Ayarlar › Hesap › “Her şeyi sil”i seçip onaylayın. Hesabınız ve kullanıcı adınız sunucudan, sohbetleriniz ve anahtarlarınız telefonunuzdan hemen silinir; artık kimse size ulaşamaz ve kullanıcı adınız hemen serbest kalır. Yalnızca şifreli felaket kurtarma yedeklerinde hesap kaydınız en fazla 7 gün daha kalabilir. Silinen hesap yedekten geri gelmez; daha önce aldığınız yedek dosyaları olduğu yerde kalır ama artık kullanılamaz, onları kendiniz silebilirsiniz. Sunucu silmeyi onaylamazsa telefonunuzda hiçbir şey silinmez: tekrar deneyebilir ya da yalnızca telefonu silebilirsiniz. Kilit ekranındaki “Kodu unuttum” › “Her şeyi sil, baştan başla” da yalnızca telefonu siler. Bu iki durumda hesabınız sunucuda kalır ve 180 gün hiç bağlanılmadığında kendiliğinden silinir.',
        },
        {
          question: 'Bir güvenlik açığı buldum.',
          answer: `Lütfen ${SECURITY_EMAIL} adresine yazın. Açığı düzeltene kadar herkese açık paylaşmamanızı (herkese açık bir hata kaydı da açmamanızı) rica ederiz. Denemelerinizi yalnızca kendi hesaplarınızla yapın.`,
        },
      ],
      licencesTitle: 'Lisanslar',
      licencesIntro: 'us2us’ın kodu (uygulamalar ve bu siteyi de sunan aktarıcı) AGPL-3.0 lisanslıdır. Kaynak kodunun tamamı GitHub’da herkese açık; katkılarınızı bekliyoruz.',
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
      why: 'Why we built it',
      whyShort: 'Why?',
    },
    footer: {
      label: 'Site links',
      privacy: 'Privacy policy',
      security: 'Security',
      press: 'Press',
      contact: 'Contact',
      licence: 'AGPL-3.0 · Open source',
      noTracking: 'This site uses no cookies or trackers and loads nothing from other sites.',
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
      stores: { label: 'App stores', comingSoon: 'Coming soon', beta: 'Now in closed beta on iOS (TestFlight, by invitation). App Store and Android versions coming soon.' },
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
        intro: 'Encryption happens on your device. The server in between only carries a sealed envelope — it can’t look inside, and only the two of you hold the keys that open it. Comparing your safety number proves it.',
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
          { icon: 'file-lock', title: 'Encrypted account backup', body: 'Export your account and contacts to one file locked with your backup password and carry on with the same username on a new phone. Messages are never included.' },
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
      updated: 'Last updated: 11 October 2026',
      summary: [
        'us2us is designed so that your conversations live only on your phone and the phone of the person you talk to, not on our servers.',
        'Our server (the “relay”) only carries encrypted envelopes until they are delivered. It cannot read what your messages say, your location, or the sender details inside a sealed envelope; it keeps your username only as a hash and never stores your contact list. What it can see, and what could be inferred from it, is listed below. We never ask for your phone number or e-mail address.',
      ],
      storedTitle: 'What the relay stores, and for how long',
      storedIntro: 'The relay stores only the following. Apart from random account ids, the last connection day and the version number of the profile picture record, everything is either a public key, encrypted data, or a one-way hash.',
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
        { data: 'Your profile picture, only while “Show in search” is on (the default): one record encrypted under a key derived from your username, its size (8, 16, 32 or 48 KiB) and its version number. Anyone who knows or guesses your username, the relay included, can open it.', lifetime: 'Until you change the picture, turn “Show in search” off, go back to the initial letter, change your username or delete your account. The last version number stays as long as the account, so that an old record cannot come back.' },
      ],
      neverTitle: 'What the relay can never see',
      never: [
        'The content of your messages, photos, files and voice notes',
        'The sender details inside a sealed envelope (only the recipient’s phone can open it; limits below)',
        'Your username in clear text, unless an invite link is opened or previewed (see below)',
        'The contact list on your phone and your display name (for the trace that new-contact lookups leave, see below)',
        'Your profile picture, while “Show in search” is off: then it goes only to your contacts, end-to-end encrypted. While it is on (the default), your picture is stored encrypted under your username, and anyone who knows or guesses the username, the relay included, can open it.',
        'Your location or live location',
        'Your secret code, or anything derived from it',
      ],
      seesTitle: 'What the relay sees while it works',
      sees: [
        'Your IP address while you are connected. The relay does not log IP addresses; use Tor or a VPN if you want to hide yours.',
        'Which account each envelope is addressed to, its (padded) size and its time.',
        'Which account uploads a file, and its (padded) size; for a voice message the size roughly reveals its length.',
        'When you look up someone new, or get a first message from them, your phone asks the relay for their account and keys. These lookups are made with your account, so the relay learns, once per new contact, which account looked up which.',
        'Whether a sealed envelope comes from one of the recipient’s accepted contacts or from someone not yet accepted (a message request); not which contact.',
        'When a profile picture is fetched in search, which account looked at whose picture. Also which accounts show a picture in search, when they change it, and the record’s size: the smallest (8 KiB) holds every preset and small photos; a larger one is certainly a photo, and its size roughly says how detailed it is. Any other account that knows your account id (former contacts and someone you blocked included) can see the same.',
        'When an invite link (us2us.app/u/…) is opened in a browser, or a messaging app builds a preview of it, the username in the link reaches our server in clear text. The page writes the name nowhere and looks nothing up; but a server that knows the name could open that account’s profile picture if “Show in search” is on.',
        'Your “active” status is end-to-end encrypted and the relay stores nothing about it. But each time the app opens, it sends one batch of encrypted envelopes to the contacts you share your status with; a hostile relay that logged IP addresses and timing could use those batches to guess contact lists. Our relay keeps no such logs. If you want protection anyway, turn off “Show when I’m active” or use Tor or a VPN.',
      ],
      appleGoogleTitle: 'Apple, Google, notifications and maps',
      appleGoogle: [
        'us2us uses no push notifications. Apple and Google play no part in delivering your messages: no notification is ever sent, and no identifier of your device is given to them or to us, so they cannot learn who receives messages, or when.',
        'The trade-off: while the app is closed you cannot see new messages. They arrive when you open the app, directly from our relay.',
        'Maps: when you open the live-location map, its map images load from Apple Maps, so Apple can see the area shown, together with your IP address, but not whose location the map shows, nor anything of your messages. The map loads only when you open it; location bubbles in a chat load no map. The system may cache map images on your phone. If you open a place with “Open in Apple Maps” or “Open in Google Maps”, its coordinates (and, for Apple Maps, the place’s name if it has one) are handed to the app you chose; from then on, that app’s privacy policy applies. If you open a place from a disappearing message in another app, that app may keep it in its own history.',
        'Downloading the app from the App Store or Google Play is subject to Apple’s and Google’s own privacy policies.',
        'Beta: if you test us2us with TestFlight, Apple shows us some of what it collects for beta testing: your name and e-mail address if we invited you by e-mail, install and session counts, crash reports, and the feedback you send from TestFlight (including any screenshots you attach). None of this contains your messages; only what a screenshot you attach to feedback shows reaches us. Your use of TestFlight is subject to Apple’s own terms and privacy policy.',
        `Reports: when you report someone with “Report” in the app, your phone prepares an e-mail to ${SUPPORT_EMAIL} that you send yourself. It contains only the reported person’s username, the reason you chose, your note, the app version and the time (UTC). Because messages are end-to-end encrypted, no message content is added to a report; screenshots reach us only if you attach them. We use your e-mail address and what the e-mail says only to review the report and reply to you.`,
      ],
      phoneTitle: 'Data on your phone',
      phone: [
        'Your messages, contacts and settings exist only on your phone, encrypted with your secret code. The code never leaves your phone.',
        'Only you create an account backup file; it is encrypted with a backup password, separate from your secret code, and never sent to us. Messages are not part of it. Anyone who obtains the backup file can try passwords offline, without limit, so we recommend the random password the app generates for you.',
        'The microphone is on only while you record a voice message; a recording leaves your phone only if you send it, end-to-end encrypted, and our relay only carries the encrypted file.',
      ],
      securityTitle: 'Security',
      security: [
        'Messages are encrypted on your phone with libsignal, the official Signal protocol library (PQXDH and the triple ratchet: Double Ratchet + SPQR), so their content is also protected against traffic recorded today and attacked later with a quantum computer. This post-quantum protection covers message content; the sender details in the sealed envelope and identity verification still rely on classical (elliptic-curve) cryptography. The private keys exist only on your phones (and, if you export one, in your account backup, encrypted with your backup password).',
        'With sealed sender, the envelope does not say who sent it: the server sees where a message goes, but cannot read from the envelope who it came from. This is not full anonymity; traces such as IP addresses, timing and new-contact lookups can still give hints (see below).',
        'Everything on your phone (keys, messages, photos, contacts, settings) is encrypted with your secret code. The code never leaves your phone and cannot be reset; there is no back door. Two exceptions: on an iPhone, while you preview, save, send or receive a file, the app keeps a temporary copy protected by iOS file protection (unreadable while the phone is locked); it is deleted when the preview or the transfer ends, and at the latest when the app next starts. A voice message, too, is kept in such a protected temporary file while you record it; when you stop, it is taken into memory and that file is deleted at once (if the app is closed mid-recording, at the latest at the next start). Like any file, it briefly has a protected temporary copy while it is sent or received.',
        'Every conversation has a safety number. When you are together, scan the QR code to check that you are really talking to them. If a contact’s key changes, the app warns you and sends nothing to them until you compare the numbers or choose “Continue without verifying”; in the second case the chat stays unverified.',
        `Found a security problem? Please write to ${SECURITY_EMAIL}, and please keep it private until we have fixed it.`,
      ],
      limitsTitle: 'To be honest',
      limits: [
        'The other person can photograph the screen or use a modified app; no app can fully protect messages that “disappear”. Android blocks screenshots; iOS can only detect them.',
        'Ghost-mode and disappearing-message deletions happen the first moment after the deadline that the app or its local timer runs. iOS does not guarantee background tasks, so on an iPhone the deletion may wait until the app is next opened; until then the data stays encrypted with your secret code and is never shown after its deadline.',
        'If the relay goes down abruptly (for example in a power cut), messages held in memory at that moment can be lost; the sender sees “Sent” without “Delivered” and can send again.',
        'The first time you write to someone whose safety number you have not compared, their public key comes from our server. A compromised server could put its own key in between at that moment, and only comparing safety numbers would reveal it. Scanning the QR code together once rules this out.',
        'Sealed sender is not full anonymity. When your phone sends a message, it reaches the relay from the same IP address as its signed-in connection. A hostile relay that logged IP addresses and timing could match a sent envelope to the account that sent it, and an uploaded file to the account that downloads it. A VPN hides your real IP address but does not by itself prevent that matching. Our relay keeps no such logs.',
        'The post-quantum protection covers message content. A sealed envelope is locked to the recipient’s long-term identity key with classical (X25519) encryption. Someone who records envelopes today and later obtains the recipient’s identity key (for example from the phone, or from an account backup together with its password), or builds a large enough quantum computer, could learn who those envelopes came from; they still could not read the messages.',
        'A profile picture shown in search is protected only by your username. Anyone who knows your username can see it, without being your contact and without you noticing, someone you blocked included. The relay does not know your username but can guess it: by trying common or short names it could open your picture, and show searchers another picture or an older one of yours (one you deleted included). A long, unusual username makes this slower, not impossible. If you do not want this, turn “Show in search” off; your picture then goes only to your contacts, end-to-end encrypted. If you change your username to get away from someone, turn the setting off too. Your contacts always see the end-to-end encrypted copy.',
        'Your secret code has 6 digits. Every guess needs this phone’s device secret, so the code can only be tried on the phone, through the app and its waiting times. On an iPhone every guess also needs a key that never leaves the Secure Enclave (from version 1.0.5 on; existing data moves to this protection the first time you enter your code), so files taken off the phone cannot be used to try the code anywhere else. But someone who can unlock your phone and break its operating system (for example someone who knows your passcode, with a forensic tool) could skip the app and try codes on the phone itself, without the waiting times. A strong phone passcode and an up-to-date OS are the best defence; don’t use your phone’s passcode as your secret code.',
      ],
      noTrackingTitle: 'Analytics, ads and logs',
      noTracking: [
        'There are no analytics, ads, trackers or cookies in the app or on this site. This site loads no fonts or scripts from anywhere else; your theme choice is kept only in your browser (localStorage) and is never sent to us.',
        'The relay does not log request contents, tokens, keys or IP addresses. The web server keeps no access logs, and its error log records only serious faults of the server itself, such as running out of connections; such an entry can name the IP address and the request involved. The error log is deleted after 14 days.',
        'We never back up messages or files. For disaster recovery only the account directory (account ids, username hashes, public keys, token and access-key hashes, last connection day) may be backed up, encrypted, and such backups are kept at most 7 days.',
      ],
      deletionTitle: 'Deleting your data',
      deletion: [
        'When you delete your account in the app with Settings › Account › “Erase everything”, your account, the hash of your username, your keys, your profile picture record, waiting messages and the files you uploaded are deleted from the server immediately; then everything on your phone is erased. Your username becomes free right away, and a deleted account cannot be restored from a backup. Your account record (account id, username hash, public keys) can remain in encrypted disaster-recovery backups for up to 7 more days.',
        'When you turn “Show in search” off, go back to the initial letter or change your username, your profile picture record is deleted from the server (if the server cannot be reached at that moment, at the next connection). Copies on the phones of people who saw your picture before cannot be recalled.',
        'If the server does not confirm the deletion (for example because it cannot be reached), the app erases nothing on your phone; you can try again or erase only the data on your phone. If you forget your secret code, you can restore your account backup with its backup password and then delete the account in Settings; without a backup, only the phone can be erased, because your account cannot be reached without that code.',
        'If your account is left on the server (you uninstalled the app or erased only the phone) and you never connect again, it is deleted automatically after 180 days; until then, messages sent to you do not reach you.',
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
      beta: {
        title: 'For beta testers',
        intro: 'us2us is in closed beta on iOS, through TestFlight; the App Store and Android versions are coming soon. Beta builds change often and can have bugs. To keep your data safe, know this:',
        notes: [
          {
            icon: 'warning',
            tone: 'danger',
            title: 'Don’t go back to an older build after 1.0.5',
            body: 'From 1.0.5 on, the first time you enter your secret code, the key that opens the data on your phone is locked again in a new format protected by the iPhone’s Secure Enclave. Older builds cannot open that format: if you install an older build from TestFlight, you will not be able to reach your data. If that happened by mistake, erase nothing in the app and install the newest build again.',
          },
          {
            icon: 'file-lock',
            title: 'Keep your backup and its password safe',
            body: 'If something goes wrong, your account backup is the only thing that brings back your account and contacts. Make one in Settings › Backup, and keep the file somewhere off the phone and its password somewhere else (in a password manager, for example). Without the password the backup cannot be opened, not even by us. Messages are not in the backup.',
          },
          {
            icon: 'envelope-simple',
            title: 'Reporting bugs',
            body: 'Seen a bug? Write to us with what you did and what happened, your phone model, your iOS version and the version number in Settings › About. If you send a screenshot, make sure it shows none of your chats, safety numbers or your backup password. You can also use TestFlight’s feedback button; your note and screenshot then reach us through Apple.',
            email: SUPPORT_EMAIL,
          },
          {
            icon: 'shield-check',
            title: 'Security problems',
            body: 'Found a security problem? Please don’t post it anywhere public first (an issue tracker, a forum, social media); write to this address instead, and please keep it private until we have fixed it. Include the steps to reproduce it, and test only with your own accounts.',
            email: SECURITY_EMAIL,
          },
        ],
      },
      faqTitle: 'Frequently asked questions',
      faq: [
        {
          question: 'I forgot my secret code. What can I do?',
          answer: 'The code cannot be recovered: all data on your phone is encrypted with it and it is never sent anywhere, so nobody, including us, can reset it. If you have your account backup and its password, use “I forgot my code” › “Restore from backup” on the lock screen to get back into your account and contacts with a new code. Message history is not in the backup, and the old messages on this phone are erased (without the code they cannot be opened anyway); the app erases nothing before it has opened the backup and checked with the relay that it is still valid. Without a backup, erase the phone and create a new account; the old account and username are deleted automatically after 180 days without a connection.',
        },
        {
          question: 'What comes back when I restore from a backup?',
          answer: 'Your account (your username, display name and identity key, so your safety numbers stay the same) and your contacts, including who you verified and who you blocked. Messages, photos and videos are not in the backup and do not come back; if you restore through “I forgot my code” on the lock screen, the messages on this phone are erased too. Restoring renews the account’s sign-in: any other phone using the account loses access, and backup files you made earlier no longer work. So make a new backup in Settings › Backup after restoring.',
        },
        {
          question: 'How should I choose my backup password?',
          answer: 'The easiest way is to tap “Generate strong password” when you export the backup: the app creates a random 20-character password in groups of four, joined by dashes. Save it, dashes included, in a password manager or on paper, not next to the backup file, and don’t take a screenshot of it. A copied password is cleared from the clipboard after 1 minute. If you type your own, choose a long passphrase of 4–5 random words; it must be at least 10 characters long and different from your secret code. The app refuses passwords that are easy to guess, such as names, birth years, team names or keyboard runs. Anyone who gets the backup file can try passwords without limit, so this password is all that protects it.',
        },
        {
          question: 'Can I change my username?',
          answer: 'Not for now: the app cannot change a username yet. Your username is only a label that lets people find you; your account and encryption rest on keys created on your phone. If you want a different name, the only way is to delete your account and create a new one; your contacts and chats do not move to the new account.',
        },
        {
          question: 'How do I move to a new phone?',
          answer: 'Create an account backup on the old phone, move the file to the new phone, open it with your backup password, then choose a secret code for the new phone. The old phone loses access. Message history is not part of the backup. Your identity key moves with the backup, so your safety numbers stay the same.',
        },
        {
          question: 'How do I send a voice message?',
          answer: 'Press and hold the microphone in the message field and talk; let go and the voice message is sent. Slide your finger left to cancel. Slide it up to lock the recording, so you can lift your finger and keep talking; then delete the recording, stop and listen to it, or send it. The app asks for microphone access the first time you press; the microphone is on only while you record. An unplayed voice message shows a dot. You see that your voice message was played only if the other person shares read receipts. Voice messages cannot be copied, saved or shared.',
        },
        {
          question: 'How do location and live location work?',
          answer: 'Tap a location bubble in a chat to open the place with “Open in Apple Maps” or “Open in Google Maps”. The place is handed to the app you choose (without the Google Maps app, it opens in your browser), and from then on that app’s privacy rules apply. If you open a place from a disappearing message in another app, that app may keep it in its own history; us2us cannot remove it from there. Tap a live-location bubble to open a map inside the app: the other person’s latest position, the time left and, if you allow it, your own position. Seeing yourself on the map does not share your location; sharing starts only when you start it, and “Stop sharing” ends it at any time. The map images load from Apple Maps, only while the map is open.',
        },
        {
          question: 'How do photos, videos and files open?',
          answer: 'Photos open fitted to the screen and can be zoomed; videos play right in the chat. Files are previewed inside the app, and you can save them to Files. The decrypted temporary copy made for a preview is deleted when you close the preview (at the latest when the app goes to the background or next starts). Files in disappearing messages open without a share button and cannot be saved; view-once photos and videos open once, then disappear. Files that come with a message request download only once you accept it. When you save or share a photo, video or file you received, the sender sees it (even with read receipts off); the app tells you before your first save. Photos and videos can also be saved to Photos.',
        },
        {
          question: 'What is a safety number, and what does “Not verified” mean?',
          answer: 'Every conversation has a safety number, calculated from both your identity keys. “Not verified · Verify with QR” in a chat means you have not compared it yet. Your messages are still end-to-end encrypted, but you cannot yet be sure that the server in between did not slip in its own key. When you are together, one of you scans the other’s QR code; if the numbers match, the chat is verified. If you can’t meet, compare the digits over another channel (a phone call, for example) and choose “Mark as verified” only if every group matches. Comparing them in a us2us message proves nothing.',
        },
        {
          question: 'What does the “Safety number changed” warning mean?',
          answer: 'It means their identity key changed. The identity key moves with the backup, so this is not common, and it is also what you would see if someone tried to get in between. The app sends them nothing until you decide; their messages keep arriving. The safest choice is to tap “Compare” in the warning and compare the numbers again (scanning the QR code together, or over another channel). If you choose “Continue without verifying”, your messages are sent under the new number but the chat stays unverified; you can compare the numbers at any time later.',
        },
        {
          question: 'Why doesn’t us2us send notifications?',
          answer: 'Because notifications travel through Apple’s and Google’s servers and tell them which app sent a message to which phone, and when. In us2us, messages are fetched only while the app is open, directly from our blind relay. Apple and Google are told nothing. Our relay knows which account an envelope is for only while it holds it, and sees when it is delivered only while delivering it; it logs neither, and deletes the envelope as soon as your phone confirms delivery. Messages wait for you, encrypted, for up to 14 days. Waiting for something important? Just tell them “check us2us”.',
        },
        {
          question: 'How do I delete my account?',
          answer: 'In the app, choose Settings › Account › “Erase everything” and confirm. Your account and username are deleted from the server, and your chats and keys from your phone, immediately; nobody can reach you any more and your username becomes free right away. Only the encrypted disaster-recovery backups may keep your account record for up to 7 more days. A deleted account cannot be brought back from a backup; backup files you made earlier stay where they are but no longer work, and you can delete them yourself. If the server does not confirm the deletion, nothing on your phone is erased: you can try again or erase only the phone. “I forgot my code” › “Erase everything, start over” on the lock screen also erases only the phone. In both cases your account stays on the server and is deleted automatically after 180 days without a connection.',
        },
        {
          question: 'I found a security problem.',
          answer: `Please write to ${SECURITY_EMAIL}, and please keep it private until we have fixed it (that includes public issue trackers). Test only with your own accounts.`,
        },
      ],
      licencesTitle: 'Licences',
      licencesIntro: 'us2us’s code (the apps and the relay that also serves this site) is licensed under AGPL-3.0. The full source code is public on GitHub; contributions are welcome.',
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
