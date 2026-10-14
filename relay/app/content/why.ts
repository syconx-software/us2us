/*
 * "Neden yaptık / Why we built it" (/tr/neden-yaptik, /en/why-we-built-it):
 * the SyconX team's statement on why us2us exists, laid out as a press
 * release by WhyPage.vue. All of its text is in this file, in both
 * languages; the `WhyText` type makes a missing translation a type error.
 * The English text is written for English readers, not translated line by
 * line, but it makes the same points in the same order.
 *
 * Rules for the text (test/unit/why-copy.test.ts checks the mechanical ones):
 * - What it says about us2us must agree with the privacy policy in ./site.ts
 *   and PROTOCOL.md §9, honest limits included: no absolute anonymity or
 *   post-quantum claims (test/unit/site-copy.test.ts reads this file too).
 *   The source code is public (github.com/syconx-software/us2us); the text
 *   names that address wherever it invites readers to check the code.
 * - Every claim about the world cites a source. Sources (WHY_SOURCES) are
 *   shared by both languages and numbered in list order, which is the order
 *   in which the text first cites them, in both languages. `[n]` in the
 *   standfirst, a paragraph, a quote, the about box or the call to action
 *   cites source n: it becomes a link to the entry, and the entry links back
 *   to its first citation. Put the mark right after the word or punctuation
 *   it belongs to, without a space ("…izleniyor.[2]"). Every source is cited
 *   in both languages, and every source is a page we have opened and checked.
 * - Sources are told in our own words. A direct quote is short (under 15
 *   words), in quotation marks and attributed; at most one per source.
 *   Pull quotes are drawn with their own quotation marks, so their text has
 *   none.
 * - Our own pages can be linked inline as `[label](/tr/gizlilik#guvenlik)`;
 *   other sites only through the sources.
 * - Section ids (WHY_SECTION) are the same in both languages, so the
 *   language switch keeps the reader's place.
 */

import { BETA_ANCHOR, LICENCES_ANCHOR, paths, type Locale, type PageId } from './site'

/** Section ids, shared by both languages (as SECTION is on the landing page). */
export const WHY_SECTION = {
  waiting: 'bekleyis',
  notifications: 'bildirimler',
  watched: 'izlenmek',
  read: 'okunmak',
  debate: 'tartisma',
  wrong: 'yanlis',
  built: 'ne-yaptik',
  volunteers: 'gonullu',
  invitation: 'davet',
  sources: 'kaynaklar',
  about: 'syconx',
} as const

export type WhySectionId = (typeof WHY_SECTION)[keyof typeof WHY_SECTION]

/** The date of the statement: the dateline, its <time> and article:published_time. */
export const WHY_PUBLISHED = '2026-10-11'

/** A news report or document the text cites as `[n]` (n = position in WHY_SOURCES, from 1). */
export interface WhySource {
  /** Who published it, e.g. a newspaper or a broadcaster. */
  publisher: string
  /** Its headline as published, in its own language. */
  title: string
  /** Publication date, YYYY-MM-DD. */
  date: string
  /** The language it is written in. */
  lang: Locale
  /** https only; opened and checked by us. */
  url: string
}

/** A pull quote, shown after paragraph `after` (0-based) of its section. */
export interface WhyQuote {
  text: string
  by: string
  after: number
}

export interface WhySection {
  id: Exclude<WhySectionId, typeof WHY_SECTION.sources | typeof WHY_SECTION.about>
  title: string
  paragraphs: string[]
  quote?: WhyQuote
}

export interface WhyText {
  /** Browser and link-preview title (" · us2us" is added). */
  title: string
  /** The meta description (search results). */
  description: string
  /** The link-preview text (og:description) when the page is shared. */
  social: string
  /** The small line above the headline. */
  eyebrow: string
  headline: string
  /** The paragraph under the headline that says it all in brief. */
  standfirst: string
  /** "İstanbul, 11 Ekim 2026 — SyconX": the date comes from WHY_PUBLISHED. */
  dateline: { place: string, by: string }
  sections: WhySection[]
  sources: {
    title: string
    intro: string
    /** Read before the number of a citation mark ("Kaynak 3"); hidden on screen. */
    mark: string
    /** The language tag of an entry. */
    language: Record<Locale, string>
    /** The link from an entry back to where it is first cited. */
    back: string
  }
  about: { title: string, paragraphs: string[], pressLabel: string }
  /** The calm close: our own pages only (the store buttons are not links yet). */
  cta: { title: string, body: string, links: Array<{ label: string, page: PageId, hash?: string }> }
}

/* The sources, in the order the text first cites them. All opened and checked on 11 October 2026. */
export const WHY_SOURCES: WhySource[] = [
  /* 1 */ {
    publisher: 'Sözcü',
    title: 'Tarihteki ilk SMS 33 yıl önce bugün atıldı: İşte mesajın içeriği',
    date: '2025-12-03',
    lang: 'tr',
    url: 'https://www.sozcu.com.tr/tarihteki-ilk-sms-33-yil-once-bugun-atildi-iste-mesajin-icerigi-p265998',
  },
  /* 2 */ {
    publisher: 'Vodafone',
    title: '25 years since the world’s first text message',
    date: '2017-12-04',
    lang: 'en',
    url: 'https://www.vodafone.com/news/technology/25-anniversary-text-message',
  },
  /* 3 */ {
    publisher: 'Hürriyet Daily News (Anadolu Ajansı)',
    title: 'Revenues for Turkey’s telecom firms up 6 pct',
    date: '2012-05-25',
    lang: 'en',
    url: 'https://hurriyetdailynews.com/revenues-for-turkeys-telecom-firms-up-6-pct-21623',
  },
  /* 4 */ {
    publisher: 'Sabah',
    title: 'Telefon kulağımıza yapıştı!',
    date: '2026-08-16',
    lang: 'tr',
    url: 'https://sabah.com.tr/ekonomi/telefon-kulagimiza-yapisti-7642448',
  },
  /* 5 */ {
    publisher: 'TRT Haber',
    title: 'Günlük ortalama 4 saat mobil internete ayrılıyor',
    date: '2025-04-07',
    lang: 'tr',
    url: 'https://www.trthaber.com/haber/bilim-teknoloji/gunluk-ortalama-4-saat-mobil-internete-ayriliyor-903172.html',
  },
  /* 6 */ {
    publisher: 'Common Sense Media',
    title: 'Teens Are Bombarded with Hundreds of Notifications a Day on Their Smartphones, New Report Reveals',
    date: '2023-09-26',
    lang: 'en',
    url: 'https://www.commonsensemedia.org/press-releases/teens-are-bombarded-with-hundreds-of-notifications-a-day',
  },
  /* 7 */ {
    publisher: 'Martin Pielot, Karen Church, Rodrigo de Oliveira (ACM MobileHCI 2014)',
    title: 'An In-Situ Study of Mobile Phone Notifications',
    date: '2014-09-23',
    lang: 'en',
    url: 'https://ic.unicamp.br/~oliveira/doc/MHCI2014_An-in-situ-study-of-mobile-phone-notifications.pdf',
  },
  /* 8 */ {
    publisher: 'BBC News Türkçe',
    title: 'Okullarda telefon kısıtlamaları: ‘Veliler önemini anlamalı’',
    date: '2025-09-29',
    lang: 'tr',
    url: 'https://www.bbc.com/turkce/articles/c4gz82eevvvo',
  },
  /* 9 */ {
    publisher: 'Association for Psychological Science',
    title: 'You’re A Distracted Driver Even When You Ignore That Text',
    date: '2015-08-27',
    lang: 'en',
    url: 'https://www.psychologicalscience.org/news/motr/youre-distracted-even-when-you-ignore-that-text.html',
  },
  /* 10 */ {
    publisher: 'Office of U.S. Senator Ron Wyden',
    title: 'Letter to Attorney General Merrick B. Garland on smartphone push notification surveillance',
    date: '2023-12-06',
    lang: 'en',
    url: 'https://www.wyden.senate.gov/imo/media/doc/wyden_smartphone_push_notification_surveillance_letter.pdf',
  },
  /* 11 */ {
    publisher: 'Cumhuriyet (Reuters)',
    title: 'ABD’li senatör: Hükümetler Google ve Apple kullanıcılarını anlık izliyor',
    date: '2023-12-07',
    lang: 'tr',
    url: 'https://www.cumhuriyet.com.tr/bilim-teknoloji/abdli-senator-hukumetler-google-ve-apple-kullanicilarini-anlik-2149081',
  },
  /* 12 */ {
    publisher: 'Reuters',
    title: 'Apple now requires a judge’s consent to hand over push notification data',
    date: '2023-12-12',
    lang: 'en',
    url: 'https://www.reuters.com/technology/apple-now-requires-judges-consent-hand-over-push-notification-data-2023-12-12/',
  },
  /* 13 */ {
    publisher: 'The Washington Post / Portland Press Herald',
    title: 'The FBI’s new tactic: Catching suspects with push alerts',
    date: '2024-02-29',
    lang: 'en',
    url: 'https://www.pressherald.com/2024/02/29/the-fbis-new-tactic-catching-suspects-with-push-alerts/',
  },
  /* 14 */ {
    publisher: 'The Guardian',
    title: 'NSA collecting phone records of millions of Verizon customers daily',
    date: '2013-06-06',
    lang: 'en',
    url: 'https://www.theguardian.com/world/2013/jun/06/nsa-phone-records-verizon-court-order',
  },
  /* 15 */ {
    publisher: 'Lawfare',
    title: 'NSA Bulk Phone Data Collection Unlawful, Appeals Court Rules',
    date: '2020-09-03',
    lang: 'en',
    url: 'https://www.lawfaremedia.org/article/nsa-bulk-phone-data-collection-unlawful-appeals-court-rules',
  },
  /* 16 */ {
    publisher: 'BBC News Türkçe',
    title: '10 yıl önce ABD’nin sırlarını ifşa eden Snowden: Dönemin gözetleme teknikleri bugünküne kıyasla çocuk oyuncağı',
    date: '2023-06-08',
    lang: 'tr',
    url: 'https://www.bbc.com/turkce/articles/cgr608rdd42o',
  },
  /* 17 */ {
    publisher: 'DW Türkçe',
    title: 'Meta’dan gizli takip: Almanya’da binlerce kişi dava açtı',
    date: '2026-06-20',
    lang: 'tr',
    url: 'https://www.dw.com/tr/metadan-gizli-takip-almanyada-binlerce-ki%C5%9Fi-dava-a%C3%A7t%C4%B1/a-77565570',
  },
  /* 18 */ {
    publisher: 'BBC News Türkçe',
    title: 'Akıllı telefonlar konuştuklarımızı dinliyor mu?',
    date: '2026-10-03',
    lang: 'tr',
    url: 'https://www.bbc.com/turkce/articles/c6ew9vke0157o',
  },
  /* 19 */ {
    publisher: 'NBC News',
    title: 'U.S. officials urge Americans to use encrypted apps amid unprecedented cyberattack',
    date: '2024-12-03',
    lang: 'en',
    url: 'https://www.nbcnews.com/tech/security/us-officials-urge-americans-use-encrypted-apps-cyberattack-rcna182694',
  },
  /* 20 */ {
    publisher: 'Cybersecurity and Infrastructure Security Agency (CISA)',
    title: 'Mobile Communications Best Practice Guidance',
    date: '2024-12-18',
    lang: 'en',
    url: 'https://www.cisa.gov/sites/default/files/2024-12/guidance-mobile-communications-best-practices.pdf',
  },
  /* 21 */ {
    publisher: 'Bloomberg',
    title: 'Facebook Scans the Photos and Links You Send on Messenger',
    date: '2018-04-04',
    lang: 'en',
    url: 'https://www.bloomberg.com/news/articles/2018-04-04/facebook-scans-what-you-send-to-other-people-on-messenger-app',
  },
  /* 22 */ {
    publisher: 'NPR',
    title: 'Nebraska cops used Facebook messages to investigate an alleged illegal abortion',
    date: '2022-08-12',
    lang: 'en',
    url: 'https://www.npr.org/2022/08/12/1117092169/nebraska-cops-used-facebook-messages-to-investigate-an-alleged-illegal-abortion',
  },
  /* 23 */ {
    publisher: 'Amnesty International',
    title: 'Massive data leak reveals Israeli NSO Group’s spyware used to target activists, journalists, and political leaders globally',
    date: '2021-07-19',
    lang: 'en',
    url: 'https://www.amnesty.org/en/latest/press-release/2021/07/the-pegasus-project/',
  },
  /* 24 */ {
    publisher: 'BBC News Türkçe',
    title: 'Pegasus: İsrailli NSO’nun geliştirdiği casus yazılım hakkında neler biliniyor?',
    date: '2021-07-19',
    lang: 'tr',
    url: 'https://www.bbc.com/turkce/haberler-dunya-57885746',
  },
  /* 25 */ {
    publisher: 'BBC News Türkçe',
    title: 'WhatsApp İsrail merkezli casus yazılım saldırısı konusunda uyarıda bulundu',
    date: '2025-02-01',
    lang: 'tr',
    url: 'https://www.bbc.com/turkce/articles/cjdeje17m8mo',
  },
  /* 26 */ {
    publisher: 'European Data Protection Board and European Data Protection Supervisor',
    title: 'EDPB-EDPS Joint Opinion 04/2022 on the Proposal for a Regulation laying down rules to prevent and combat child sexual abuse',
    date: '2022-07-28',
    lang: 'en',
    url: 'https://edps.europa.eu/system/files/2022-07/22-07-28_edpb-edps-joint-opinion-csam_en.pdf',
  },
  /* 27 */ {
    publisher: 'Euronews',
    title: 'EU countries reach long-awaited deal on online child abuse detection',
    date: '2025-11-27',
    lang: 'en',
    url: 'https://www.euronews.com/2025/11/27/eu-countries-reach-long-awaited-deal-on-online-child-abuse-detection',
  },
  /* 28 */ {
    publisher: 'eucrim',
    title: 'Interim Rules on Voluntary CSAM Detection Reinstated Until 2028',
    date: '2026-08-10',
    lang: 'en',
    url: 'https://eucrim.eu/news/interim-rules-on-voluntary-csam-detection-reinstated-until-2028/',
  },
  /* 29 */ {
    publisher: 'BBC News',
    title: 'Apple pulls data protection tool after UK government security row',
    date: '2025-02-22',
    lang: 'en',
    url: 'https://www.bbc.com/news/articles/cgj54eq4vejo',
  },
  /* 30 */ {
    publisher: 'The Guardian',
    title: 'Apple launches legal challenge against UK government demand to access data',
    date: '2026-08-03',
    lang: 'en',
    url: 'https://www.theguardian.com/technology/2026/aug/03/apple-legal-challenge-uk-government-data-access',
  },
  /* 31 */ {
    publisher: 'BBC News Türkçe',
    title: 'WhatsApp gizlilik sözleşmesini neden değiştirdi, rakipleri daha mı güvenli, kullanıcılar için riskler neler?',
    date: '2021-01-11',
    lang: 'tr',
    url: 'https://www.bbc.com/turkce/haberler-turkiye-55617439',
  },
  /* 32 */ {
    publisher: 'Kişisel Verileri Koruma Kurumu',
    title: 'WhatsApp Uygulaması Hakkında Yürütülen Resen İncelemeye İlişkin Kamuoyu Duyurusu',
    date: '2021-09-03',
    lang: 'tr',
    url: 'https://www.kvkk.gov.tr/Icerik/7045/WHATSAPP-UYGULAMASI-HAKKINDA-YURUTULEN-RESEN-INCELEMEYE-ILISKIN-KAMUOYU-DUYURUSU',
  },
  /* 33 */ {
    publisher: 'Rekabet Kurumu',
    title: 'Facebook-WhatsApp Soruşturması Hakkında Basın Duyurusu',
    date: '2021-05-21',
    lang: 'tr',
    url: 'https://www.rekabet.gov.tr/tr/Guncel/facebook-whatsapp-sorusturmasi-hakkinda--7f1270260cbaeb11812e00505694b4c6',
  },
  /* 34 */ {
    publisher: 'Webrazzi',
    title: '11 günde WhatsApp, Telegram ve BiP’in kullanıcı sayılarının değişimi',
    date: '2021-01-14',
    lang: 'tr',
    url: 'https://webrazzi.com/2021/01/14/11-gunde-whatsapp-telegram-ve-bip-in-kullanici-sayilarinin-degisimi',
  },
  /* 35 */ {
    publisher: 'Martin Pielot, Luz Rello (arXiv)',
    title: 'Productive, Anxious, Lonely - 24 Hours Without Push Notifications',
    date: '2017-07-05',
    lang: 'en',
    url: 'https://arxiv.org/abs/1612.02314',
  },
]

export const why: Record<Locale, WhyText> = {
  tr: {
    title: 'Neden yaptık',
    description: 'SyconX ekibi us2us’ı neden yaptığını anlatıyor: susmayan bildirimler, kayda geçen her adım, okunan mesajlar ve buna karşı gönüllü emekle geliştirilen ücretsiz, açık kaynaklı bir uygulama.',
    social: 'Bir zamanlar bir SMS’i sabırla beklerdik. İnsanların konuşmalarını dinlemenin yanlış olduğuna inandığımız için us2us’ı ücretsiz, açık kaynaklı ve gönüllü olarak geliştiriyoruz.',
    eyebrow: 'Basın · Bildiri',
    headline: 'Bazı sözler yalnızca bir kişi içindir.',
    standfirst: 'Bir zamanlar bir SMS’i sabırla beklerdik. Bugün telefonlarımız hiç susmuyor. Kiminle, ne zaman, nereden konuştuğumuz bir yerlerde kayda geçiyor; bazı mesajlarımızı da onlara hiç yazmadığımız kişiler okuyor. SyconX ekibi olarak insanların birbiriyle konuşmasını dinlemenin yanlış olduğuna inanıyoruz. us2us’ı bu yüzden yaptık: telefon numarası istemeyen, bildirim göndermeyen, konuşmaları yalnızca iki telefonda tutan bir mesajlaşma uygulaması. Ücretsiz, reklamsız ve açık kaynaklı; gönüllü emekle geliştiriliyor.',
    dateline: { place: 'İstanbul', by: 'SyconX' },
    sections: [
      {
        id: WHY_SECTION.waiting,
        title: 'Bir zamanlar beklemeyi bilirdik',
        paragraphs: [
          'Çoğumuz hatırlarız. Telefon masanın üstünde, ekranı kararmış. Bir mesaj yazmışız; doğru harfe ulaşmak için aynı tuşa iki, üç kez basmış, kontör yetsin diye kelimeleri kısaltmış, sonunda “Gönder”i seçmişiz. Sonra beklemişiz. Cevap bazen beş dakika sonra gelirdi, bazen akşam, bazen ertesi sabah. Telefon masada titrediğinde içimizde bir şey kıpırdardı. Aradaki o sessizlik, söylenen sözü daha değerli kılardı.',
          'Kısa mesaj da mütevazı başlamıştı: ilk SMS, 3 Aralık 1992’de bir mühendisin bilgisayarından bir iş arkadaşının telefonuna gönderilen, iki kelimelik bir Noel kutlamasıydı.[1][2] 2000’lerin başında hayatımızın bir parçası oldu; bugün gündelik yazışmalarımızın büyük kısmını mesajlaşma uygulamaları taşıyor.[1] Türkiye’de 2012’nin ilk üç ayında 40 milyardan fazla SMS gönderilmişti.[3] 2026’nın aynı aylarında bu sayı beş milyarın altındaydı.[4] Birbirimize yazmayı bırakmadık, yalnızca taşındık. Taşınırken de bir şeyi geride bıraktık: beklemeyi.',
          'Daha da eskiye gidersek, çoğumuzun ilk telefonu iki bardak ile bir parça ipti. Bardağın birine fısıldardık, ip gerilir, sesimiz öbür uçtaki arkadaşımıza giderdi; başka hiç kimseye. Logomuzdaki iki halkaya ve onları birleştiren çizgiye baktığımızda hâlâ o bardakları ve o ipi görüyoruz. us2us’ı yaparken hep onu düşündük: iki uç, bir hat, arada kimse yok.',
        ],
        quote: { text: 'Beklemek, mesajın bir parçasıydı.', by: 'SyconX ekibi', after: 0 },
      },
      {
        id: WHY_SECTION.notifications,
        title: 'Sonra bekleyemez olduk',
        paragraphs: [
          'Bugün telefon elimizden düşmüyor. Dijital 2025 Türkiye raporuna göre Türkiye’de internet kullanıcıları günde dört saati aşkın süreyi mobil internette geçiriyor.[5] Ekran durmadan yanıyor: bir beğeni, bir indirim, bir hatırlatma, sessize almayı hep unuttuğumuz bir grubun yüzüncü mesajı. ABD’de 11–17 yaşlarındaki yaklaşık 200 gencin telefon kullanımı bir hafta boyunca kaydedildi: yarısından fazlası günde 237 ya da daha fazla bildirim alıyordu ve gençler telefonlarına günde ortalama 100’den fazla kez bakıyordu.[6]',
          'On beş kişilik küçük bir saha araştırmasında telefonlara günde ortalama 63,5 bildirim geliyordu, çoğu mesajlaşma uygulamalarından ve e-postadan. Katılımcılar telefon sessizdeyken bile bildirimlere çoğunlukla dakikalar içinde bakıyordu; gösterdikleri başlıca nedenlerden biri sosyal baskıydı, yani karşıdakinin hemen cevap beklediği duygusu.[7] O duyguyu hepimiz tanıyoruz. Mesajın altında “Görüldü” belirdiği anda bir saat işlemeye başlar. Artık biz mesaj beklemiyoruz; mesajlar bizi bekliyor, hem de sabırsızca.',
          'Bunun bir bedeli var. BBC Türkçe’nin aktardığı UNESCO araştırmalarına göre bildirim geldiğinde telefonun yakında olması bile öğrencinin dikkatini dağıtmaya yetiyor ve yeniden odaklanmak 20 dakikayı bulabiliyor.[8] Bir deneyde, telefona hiç dokunmadan yalnızca bir arama ya da mesaj bildirimi almak, dikkat isteyen bir görevde hata yapma olasılığını yaklaşık üç katına çıkardı.[9] Ekran yeniden karardığında, yarıda bıraktığımız düşüncenin ucunu her zaman bulamıyoruz.',
        ],
        quote: { text: 'Bekleyemez olduk, çünkü bekletmekten korkar olduk.', by: 'SyconX ekibi', after: 1 },
      },
      {
        id: WHY_SECTION.watched,
        title: 'Her anımız bir kayıt',
        paragraphs: [
          'Bildirimin bir de görmediğimiz yüzü var. Telefonumuza düşen bildirimlerin neredeyse hepsi, uygulamadan bize gelmeden önce Apple’ın ya da Google’ın sunucularından geçer. ABD’li senatör Ron Wyden Aralık 2023’te, ofisine gelen bir ihbarın ardından, yabancı ülkelerdeki devlet kurumlarının bu iki şirketten bildirim kayıtları istediğini ve şirketlerin bu bilgileri gizlice vermeye zorlanabileceğini yazdı.[10][11] İki şirket de bu tür talepler aldığını kabul etti. Birkaç gün sonra Apple, bu veriler için artık hâkim kararı arayacağını yönergelerine ekledi; Google zaten böyle yapıyordu.[12] İleriye doğru bir adımdı. Ama kayıtlar yerinde duruyordu.',
          'The Washington Post, ABD’de soruşturmacıların bildirim verisi istediği 130’dan fazla arama emri ve mahkeme kararı buldu. Birinde soruşturmacılar, şifreli bir mesajlaşma uygulamasının bildirim belirtecinden yola çıkıp Google’a acil bir talep gönderdi; gelen yanıt onları bir hesaba, oradan da bir isme götürdü. Şüpheli bir hafta içinde yakalandı.[13] Bu dosyalar arasında ağır suçlar da vardı. Bizi asıl düşündüren, şifreli bir uygulamanın bile, yalnızca bildirim ulaşsın diye tutulan bir belirteç yüzünden kullanıcısını ele verebilmesiydi.',
          'Bu kaygılar yeni değil. 2013’te The Guardian, bir ABD telefon şirketinin milyonlarca abonesinin arama kayıtlarını her gün ABD Ulusal Güvenlik Ajansı’na (NSA) vermesini isteyen gizli bir mahkeme kararını yayımladı: konuşmalar yoktu, yalnızca kimin kimi, ne zaman, ne kadar süreyle aradığı vardı.[14] 2020’de bir ABD temyiz mahkemesi, telefon kayıtlarının bu şekilde toplanmasının yasaya aykırı olduğuna hükmetti.[15] Bu kararı ve daha birçok gizli belgeyi sızdıran Edward Snowden, aradan on yıl geçtikten sonra, o günlerin gözetleme tekniklerinin bugünküler yanında çocuk oyuncağı kaldığını söyledi.[16] Bir insanı tanımak için tek kelimesini duymak gerekmez; gece yarısı kimi, hangi doktoru, hangi avukatı aradığını bilmek yeter.',
          'Bugün izlemek için devlet olmak da gerekmiyor. Almanya’da Berlin’deki bir mahkeme, Meta’nın görünmez izleme araçlarının tahminen dünyadaki web sitelerinin en az yüzde 30–40’ında kullanıldığını belirtti; Leipzig’deki bir mahkeme de Meta’nın, Facebook ya da Instagram’a giriş yapmamış kullanıcıları bile başka sitelerde tek tek tanıyabildiğini tespit etti. Mart 2026 başına kadar yaklaşık 91.500 kişi Meta’ya karşı açılan bir toplu davaya katıldı.[17]',
          'Abartmak istemiyoruz: telefonların reklam göstermek için konuşmalarımızı mikrofondan gizlice dinlediğine dair bir kanıt yok. Buna gerek de yok. Reklamcılar bizi konum verisinden, gezinme geçmişinden ve sayfalara gizlenmiş izleme piksellerinden tanıyor.[18] Bir insanın gününü anlatmak için bunlar fazlasıyla yetiyor. “Her anımız izleniyor” derken kastettiğimiz de bu: gizli bir kulak yok; hakkımızda tutulan binlerce küçük kayıt var.',
        ],
      },
      {
        id: WHY_SECTION.read,
        title: 'Mesajlarımız da okunuyor',
        paragraphs: [
          'Sabırla beklediğimiz o SMS’ler bile sandığımız kadar bize ait değildi. Aralık 2024’te ABD’li yetkililer, Çin’e atfedilen bir saldırı kampanyasının büyük telekom şirketlerine sızdığını, kimin kimi aradığını gösteren kayıtları aldığını ve bazı hedeflerin canlı görüşmelerini dinlediğini açıkladı; Amerikalılara şifreli uygulamalar kullanmalarını önerdiler. Çin suçlamayı reddetti.[19] İki hafta sonra ABD’nin siber güvenlik ajansı CISA nedenini açıkça yazdı: SMS şifreli değildir, telekom ağına erişebilen biri onu okuyabilir.[20] Meğer o heyecanla beklediğimiz mesajlar, yol boyunca birer kartpostal gibi taşınmış.',
          'Bazı uygulamalarda mesajlar şirketin sunucularında okunabilir hâlde durur. Facebook 2018’de, insanların Messenger’da gönderdiği bağlantıları ve fotoğrafları otomatik olarak taradığını, işaretlenen sohbetleri de moderatörlerin okuduğunu doğruladı.[21] 2022’de ABD’nin Nebraska eyaletinde polis, bir anne ile kızının Messenger yazışmalarını arama emriyle Meta’dan aldı ve bir ceza davasında temel delil olarak kullandı. Dijital haklar örgütü EFF’den bir avukat NPR’a, en iyisinin şirketlerin bu veriyi baştan hiç saklamaması olacağını söyledi.[22] Okunabilir biçimde saklanan bir mesaj, bir gün birine teslim edilebilir. Bir şirket ise hiç okuyamadığı bir mesajın içeriğini kimseye veremez.',
          'Bazen telefonun kendisi ele geçirilir. 2021’de 80’i aşkın gazetecinin yürüttüğü Pegasus Projesi, NSO Group’un casus yazılımına hedef olabilecek yaklaşık 50.000 telefon numarasından oluşan bir sızıntıyı inceledi; gizlice yüklenen Pegasus mesajlara, kameraya ve mikrofona tam erişim sağlıyor. Uluslararası Af Örgütü’nün incelemesine göre Cemal Kaşıkçı’nın nişanlısı Hatice Cengiz’in telefonuna, Kaşıkçı’nın öldürülmesinden dört gün sonra Pegasus yüklenmişti; NSO iddiaları reddetti.[23] Citizen Lab, 2018’de Türkiye dahil 45 ülkede bu yazılımın izlerine rastlamıştı.[24] 2025’in başında da WhatsApp, 20’yi aşkın ülkede yaklaşık 90 gazeteci ve sivil toplum temsilcisinin İsrail merkezli Paragon’un casus yazılımıyla hedef alındığını açıkladı; uzmanlara göre bu yazılım telefona, kurban hiçbir şeye dokunmadan yerleşebiliyor ve şifreli uygulamalardaki mesajları bile okuyabiliyordu.[25]',
          'Bunu açıkça söyleyelim: böyle bir yazılım telefona yerleştiğinde, us2us dahil hiçbir uygulamanın şifrelemesi sizi koruyamaz; çünkü mesajı telefonun içinde, şifresi çözüldükten sonra okur. Şifreleme mesajı yolda korur, ele geçirilmiş bir telefonu değil.',
        ],
        quote: { text: 'Şifreleme dostunuzdur.', by: 'Jeff Greene, CISA (NBC News)', after: 0 },
      },
      {
        id: WHY_SECTION.debate,
        title: 'Bu tartışma bitmedi',
        paragraphs: [
          'Mesajlarımıza kimin bakabileceği bugün de tartışılıyor. Avrupa Komisyonu Mayıs 2022’de, çocuk cinsel istismarıyla mücadele amacıyla, iletişim hizmetlerine özel yazışmaları tarama yükümlülüğü getirebilecek bir yasa önerdi. AB’nin veri koruma otoriteleri istismarı son derece ağır ve iğrenç bir suç olarak nitelendirdi, ama öneriyle ilgili ciddi orantılılık kaygılarını da dile getirdi. Şifrelemenin özel hayata ve gizli iletişime temelden katkı sağladığını vurguladılar ve yasaya, hiçbir hükmünün şifrelemeyi yasaklamak ya da zayıflatmak olarak yorumlanamayacağının açıkça yazılmasını istediler.[26] Kasım 2025’te üye ülkeler ortak tutumlarında zorunlu taramadan vazgeçti, ama platformların gönüllü olarak tarama yapabilmesini korudu.[27] Gönüllü taramaya izin veren geçici kurallar Nisan 2026’da sona erdi, Temmuz 2026’da 2028’e kadar geçerli olmak üzere geri geldi; uçtan uca şifreli iletişim açıkça kapsam dışında bırakıldı. Kalıcı yasa için müzakereler sürüyor.[28]',
          'Birleşik Krallık’ta, bildirildiğine göre İçişleri Bakanlığı’nın şifreli iCloud verilerine erişim talebi üzerine Apple, Şubat 2025’te iCloud verileri için isteğe bağlı uçtan uca şifreleme özelliğini (Gelişmiş Veri Koruması) ülkedeki kullanıcılara sunmayı bıraktı ve ürünlerine hiçbir zaman arka kapı ya da ana anahtar koymadığını, koymayacağını söyledi; bakanlık talebi ne doğruladı ne yalanladı.[29] Hükümet 2025’te bu ilk talepten geri adım attı, ama yalnızca İngiliz kullanıcıların verilerini kapsayan yeni bir talep çıkardı. Apple Temmuz 2026’da buna karşı da dava açtı.[30]',
          'Biz bu tartışmayı Türkiye’de de yaşadık. Ocak 2021’de WhatsApp’ın gizlilik koşullarındaki değişiklik büyük tepki çekti. Mesajlar zaten uçtan uca şifreliydi; mesele kiminle, ne zaman, ne sıklıkla, hangi cihazdan ve nereden konuştuğumuzu anlatan üst veriydi.[31] Kişisel Verileri Koruma Kurulu, uygulamayı kullanmanın verilerin yurt dışına aktarılmasına rıza göstermeye bağlandığını ve kullanıcıların özgürce hayır diyemediğini tespit edip WhatsApp’a 1.950.000 TL idari para cezası verdi.[32] Rekabet Kurulu’nun ara kararının ardından WhatsApp, veri paylaşımı güncellemesinin Türkiye’de hiçbir kullanıcı için yürürlüğe girmeyeceğini Kuruma bildirdi.[33] İnsanlar da harekete geçti: Ocak 2021’in ilk on bir gününde Telegram’ın Türkiye’deki günlük kullanıcı sayısı yaklaşık üç katına çıktı.[34] Mahremiyet, sanıldığı gibi kimsenin umursamadığı bir şey değil.',
        ],
      },
      {
        id: WHY_SECTION.wrong,
        title: 'Dinlemek yanlıştır',
        paragraphs: [
          'Bütün bunları okuyunca o tanıdık cümle akla geliyor: “Saklayacak neyim var ki?” Biz soruyu tersinden soruyoruz. Gece yarısı sevdiğimize yazdığımız o cümle, bir doktora yüz yüze sormaya utandığımız o soru, bir dosta açtığımız o dert neden başka birinin dosyasında dursun? Saklayacak bir şeyimiz olduğu için değil; bazı sözler yalnızca bir kişi için söylendiği için.',
          'İnancımız şu: insanların birbiriyle konuşmasını dinlemek yanlıştır. Bunu ister bir devlet yapsın, ister bir şirket, ister bir reklam ağı, ister yalnızca meraklı biri. Ağır suçlarla mücadelenin zor ve önemli olduğunu biliyoruz. Ama herkesin mesajına açılan bir kapı yalnızca iyi niyetlilere açılmaz; bir kez açıldı mı, kimin gireceğine de onu açanlar karar veremez.',
          'Dinlenmek insanı bir anda susturmaz. Önce kelimelerimizi seçmeye başlarız, sonra konularımızı, en sonunda da kiminle konuşacağımızı. Oysa düşündüğümüz, dertleştiğimiz, sevdiğimiz, fikir değiştirdiğimiz yer tam da o özel konuşmalardır; insanlar birbirine güvenmeyi orada öğrenir.',
        ],
        quote: { text: 'Dinlendiğini bilen insan susmayı öğrenir. Biz kimsenin susmasını istemiyoruz.', by: 'SyconX ekibi', after: 1 },
      },
      {
        id: WHY_SECTION.built,
        title: 'Ne yaptık, neden böyle yaptık',
        paragraphs: [
          'us2us’ı bu inançla yaptık: iki kişi arasında, kimse araya girmesin diye kurulmuş özel bir hat. Her kararda kendimize aynı soruyu sorduk: Bu, konuşmayı o iki kişinin arasında tutuyor mu?',
          'Telefon numarası istemiyoruz; kayıt olmak ve birini bulmak için kullanıcı adı yetiyor. Mesajlar, Signal protokolünün resmî kütüphanesi libsignal ile sizin telefonunuzda kilitlenir ve yalnızca karşınızdakinin telefonunda açılır; içerikleri, bugün kaydedilip yarın bir kuantum bilgisayarla çözülme girişimlerine karşı da korunur. Mühürlü gönderici sayesinde sunucumuz bir zarfın kime gittiğini görür, ama kimden geldiğini zarfın üzerinden okuyamaz.',
          'Bildirim göndermiyoruz; bunu bilerek seçtik. Apple’ın ve Google’ın bildirim sunucularını hiç kullanmıyoruz; mesajlarınız siz uygulamayı açtığınızda gelir. Hiçbir yerde bildirim belirteci kaydedilmediği için, birinin isteyebileceği bir bildirim kaydı da yok. Bunun kolay bir seçim olmadığını biliyoruz. Bir araştırmada 30 gönüllü bütün bildirimlerini 24 saat boyunca kapattı. Dikkatlerinin daha az dağıldığını ve daha verimli olduklarını söylediler; ama bazıları beklendiği kadar çabuk cevap verememekten kaygılandı, kendini çevresinden biraz kopuk hissetti. İki yıl sonra katılımcıların yarısı bildirimlerini hâlâ farklı yönetiyordu.[35] Cevap vermek için herkesin kendi zamanını seçebileceğini kabul ettiğimizde bu kaygının hafifleyeceğine inanıyoruz. Önemli bir şey mi bekliyorsunuz? Karşınızdakine “us2us’a bak” demeniz yeter.',
          'Konuşmalar yalnızca iki telefonda yaşar. Sunucumuz arşiv tutmaz, unutkan bir postacı gibidir: zarfı teslim eder etmez siler, teslim edemezse en geç 14 gün sonra. Uygulama gizli bir kodla açılır; iPhone’da her kod denemesi, telefonun Secure Enclave’inden hiç çıkmayan bir anahtar ister. Kodumuz açık: bütün bunları herkes github.com/syconx-software/us2us adresinde kendisi denetleyebilir.',
          'Söz veremeyeceklerimizi de söyleyelim. Mühürlü gönderici her şeyi gizlemez: sunucu IP adreslerini ve zamanlamayı görebilir. Bizim sunucumuz bunları kaydetmez, ama bu konuda bize güvenmeniz gerekir. Biriyle ilk kez yazıştığınızda onun anahtarı sunucumuzdan gelir; yan yana gelip güvenlik numaralarınızı karşılaştırana kadar araya kimsenin girmediğinden tam emin olamazsınız. Kuantum sonrası koruma mesajların içeriği içindir, zarftaki gönderen bilgisi için değil. Telefonunuzda casus yazılım varsa ya da karşınızdaki ekranın fotoğrafını çekerse, hiçbir uygulama sizi koruyamaz; us2us da. Bütün ayrıntılar [gizlilik politikamızda](/tr/gizlilik#guvenlik).',
        ],
      },
      {
        id: WHY_SECTION.volunteers,
        title: 'Ücretsiz, açık kaynaklı, gönüllü',
        paragraphs: [
          'us2us ücretsiz. Reklam yok, izleyici yok, analiz aracı yok. Verilerinizi satmıyoruz; hizmetin çalışması için gerekenden fazlasını zaten toplamıyoruz. “Bir şey ücretsizse ürün sizsinizdir” derler; bu sözün bu kez yanlış çıkmasını istiyoruz. Mesajlarınıza, alışkanlıklarınıza ya da dikkatinize ihtiyaç duyan bir iş modelimiz yok, olmasını da istemiyoruz.',
          'Biz, SyconX ekibi, us2us’ı gönüllü olarak geliştiriyoruz. Önce kendimiz için yaptık: annemizle, kardeşimizle, uzaktaki eski bir dostla konuşurken araya kimsenin girmediğini bilmek için. Sonra bunun herkesin hakkı olduğunu düşündük. Mahremiyet, parası olanın satın alabildiği bir ayrıcalık olmamalı.',
          'us2us’ın kodu AGPL-3.0 lisanslıdır; kaynak kodunun tamamı github.com/syconx-software/us2us adresinde herkese açık. Bu lisansla us2us’ı değiştirip bir ağ üzerinden başkalarına sunan herkes, değişikliklerini de onlarla paylaşmak zorundadır; yani kimse bu emeği alıp kendi kapalı ürününe dönüştüremez. Açık kaynak bizim için hesap vermenin bir yolu: Bir uygulamanın mesajlarınızı okuyamadığını bize inanarak değil, koduna bakarak bilebilmelisiniz.',
        ],
      },
      {
        id: WHY_SECTION.invitation,
        title: 'Yeniden beklemek',
        paragraphs: [
          'us2us bugün iOS’ta, TestFlight üzerinden davetle açılan kapalı bir betada; App Store ve Android sürümleri yakında. Betaya davet edildiyseniz deneyin; takıldığınız ya da içinize sinmeyen her yeri bize yazın. Kodu github.com/syconx-software/us2us adresinde okuyun; yanlış yaptığımız bir şey görürseniz söyleyin. Bu uygulama eleştiriyle büyüyecek.',
          'Bir de şunu deneyin: bir mesaj gönderin ve bekleyin. Telefonunuz sessizken, karşınızdakinin cevap vereceği anı kendisinin seçtiğini bilin. Uygulamayı açtığında mesajınız orada olacak; yalnızca onun için, yalnızca onun ekranında.',
          'Çocukken bardağa fısıldadığımız sözler gibi: ip gerilir, ses yol alır ve öbür uçta yalnızca bir kişi duyar. us2us’ı o ipi yeniden germek için yaptık.',
        ],
      },
    ],
    sources: {
      title: 'Kaynaklar',
      intro: 'Bu bildiride andığımız haberler, araştırmalar ve belgeler; metinde ilk anıldıkları sırayla. Bağlantılar başka sitelere gider.',
      mark: 'Kaynak',
      language: { tr: 'Türkçe', en: 'İngilizce' },
      back: 'Metne dön',
    },
    about: {
      title: 'SyconX hakkında',
      paragraphs: [
        'SyconX, us2us’ı geliştiren yazılım ekibidir. us2us, iki kişi arasındaki konuşmalar için uçtan uca şifreli bir mesajlaşma uygulamasıdır: telefon numarası istemez, push bildirimi göndermez, konuşmaları yalnızca iki telefonda tutar. Ücretsizdir; reklam, izleyici ya da analiz aracı içermez. Kodu AGPL-3.0 lisanslıdır ve github.com/syconx-software/us2us adresinde herkese açıktır.',
        'us2us şu an iOS’ta TestFlight üzerinden kapalı betada; App Store ve Android sürümleri yakında. Basın soruları ve görüşme talepleri için aşağıdaki adrese yazabilirsiniz.',
      ],
      pressLabel: 'Basın iletişimi',
    },
    cta: {
      title: 'İpin öbür ucu sizde.',
      body: 'us2us’ı denemek, nasıl çalıştığını okumak ya da nerede yanıldığımızı bize söylemek isterseniz buradan başlayabilirsiniz.',
      links: [
        { label: 'Beta testçileri için', page: 'support', hash: BETA_ANCHOR },
        { label: 'Gizlilik politikası', page: 'privacy' },
        { label: 'Lisans ve kaynak kodu', page: 'support', hash: LICENCES_ANCHOR.tr },
      ],
    },
  },
  en: {
    title: 'Why we built it',
    description: 'The SyconX team explains why it built us2us, a free, open-source messenger made by volunteers: endless notifications, constant tracking, and messages read by people they were never meant for.',
    social: 'We used to wait for a text message. We believe listening in on people’s conversations is wrong, so we build us2us as volunteers: free and open source.',
    eyebrow: 'Press · Statement',
    headline: 'Some words are meant for one person only.',
    standfirst: 'We used to wait patiently for a text message. Now our phones are never quiet; who we talk to, when and from where is recorded somewhere; and some of our messages are read by eyes they were never meant for. At SyconX we believe that listening in on people’s conversations is wrong. So we built us2us: a messenger with no phone number and no notifications, which keeps your conversations on two phones only. Free, ad-free, open source and made by volunteers.',
    dateline: { place: 'Istanbul', by: 'SyconX' },
    sections: [
      {
        id: WHY_SECTION.waiting,
        title: 'We used to know how to wait',
        paragraphs: [
          'Most of us remember it. The phone on the table, its screen dark. You had typed a message, pressing the same key two or three times for a single letter, shortening words so your credit would last, and finally hit send. Then you waited: five minutes, or until evening, or until the next morning. When the phone buzzed at last, something in you leapt. The silence in between made the words worth more.',
          'Texting began modestly: the first text, sent on 3 December 1992 from an engineer’s computer to a colleague’s phone, was a two-word Christmas greeting.[1][2] By the early 2000s it was part of everyday life; today messaging apps carry much of what we write.[1] In Türkiye, more than 40 billion texts were sent in the first three months of 2012.[3] In the same months of 2026, fewer than five billion.[4] We never stopped writing to each other. We moved, and on the way we left the waiting behind.',
          'Long before that, many of us had a first telephone made of two cups and a string. You whispered into one cup, the string pulled tight, and your voice reached your friend at the other end and no one else. When we look at the two rings joined by a line in our logo, we still see those cups and that string. We built us2us with them in mind: two ends, one line, no one in between.',
        ],
        quote: { text: 'Waiting used to be part of the message.', by: 'The SyconX team', after: 0 },
      },
      {
        id: WHY_SECTION.notifications,
        title: 'Then we stopped waiting',
        paragraphs: [
          'Now the phone hardly leaves our hand. According to the Digital 2025 Türkiye report, internet users in Türkiye spend more than four hours a day on mobile internet.[5] The screen keeps lighting up: a like, a sale, a reminder, the hundredth message in a group we keep meaning to mute. When researchers logged the phones of about 200 Americans aged 11 to 17 for a week, more than half got 237 or more notifications a day, and they checked their phones more than 100 times a day on average.[6]',
          'In a small field study, 15 people received 63.5 notifications a day on average, mostly from messengers and email, and usually looked within minutes, even on silent. One of the main reasons they gave was social pressure: the sense that someone is waiting for an answer.[7] We all know it. The moment “Seen” appears, a clock starts ticking. We no longer wait for messages; messages wait for us, impatiently.',
          'It has a price. UNESCO research reported by BBC Türkçe says that a phone nearby when a notification arrives is enough to break a student’s concentration, and that refocusing can take up to 20 minutes.[8] In one experiment, merely receiving a call or text alert, without touching the phone, made mistakes on an attention task about three times as likely.[9] When the screen goes dark again, we cannot always find the end of the thought we dropped.',
        ],
        quote: { text: 'We stopped waiting when we grew afraid of keeping others waiting.', by: 'The SyconX team', after: 1 },
      },
      {
        id: WHY_SECTION.watched,
        title: 'Every moment, a record',
        paragraphs: [
          'Notifications have a side we never see: almost all of them pass through Apple’s or Google’s servers. In December 2023 US Senator Ron Wyden, following up a tip to his office, wrote that government agencies in foreign countries had been demanding push-notification records from both companies, which could be “secretly compelled by governments to hand over this information”.[10][11] Both companies acknowledged such requests, and days later Apple began requiring a judge’s order for the data, as Google already did.[12] A step forward. But the records were still there.',
          'The Washington Post found more than 130 search warrants and court orders in which US investigators sought such data. In one, an encrypted messaging app’s push token led, through an emergency request to Google, to an account, a name and an arrest within a week.[13] Some of these cases involved grave crimes. What stays with us is that an encrypted app could give its user away through a token kept only so that notifications could arrive.',
          'None of this is new. In 2013 the Guardian published a secret court order making a US phone company hand the NSA the call records of millions of customers every day: no conversations, just who called whom, when and for how long.[14] In 2020 a US appeals court ruled that this bulk collection was unlawful.[15] Edward Snowden, who had leaked that order along with many other secret documents, said ten years on that the surveillance techniques of his day looked like child’s play next to today’s.[16] You don’t need to hear a word someone says to know them. It is enough to know whom they call at midnight, which doctor, which lawyer.',
          'Nor do you need to be a government to watch. A court in Berlin noted estimates that Meta’s invisible trackers sit on at least 30 to 40 percent of the world’s websites; a court in Leipzig found that Meta can recognise individual users on other sites even when they are not logged in to Facebook or Instagram. By early March 2026 about 91,500 people in Germany had joined a class action against Meta.[17]',
          'We don’t want to exaggerate: there is no evidence that phones secretly listen through the microphone to target ads. They don’t need to. Advertisers learn about us from location data, browsing history and tracking pixels hidden in web pages.[18] That is more than enough to tell the story of a person’s day. This is what we mean when we say every moment is watched: there is no hidden ear, only thousands of small records about us.',
        ],
      },
      {
        id: WHY_SECTION.read,
        title: 'And our messages are read',
        paragraphs: [
          'Even the texts we waited for were never as private as we thought. In December 2024 US officials said that hackers attributed to China had broken into major telecom companies, taken records of who called whom and listened to some targets’ live calls; they urged Americans to use encrypted apps, and China denied it.[19] Two weeks later the US cybersecurity agency CISA spelled out why: text messages are not encrypted, and anyone with access to a telecom network can read them.[20] The messages we waited for so eagerly had travelled like postcards.',
          'Some apps keep messages on their servers in a form they can read. In 2018 Facebook confirmed that it scanned the links and photos people sent on Messenger, and that moderators read flagged chats.[21] In 2022 Nebraska police obtained a mother’s and a daughter’s Messenger chats from Meta with a warrant and used them as key evidence in a criminal case. As a lawyer for the Electronic Frontier Foundation told NPR, “we might hope the companies aren’t holding the data in the first place”.[22] A message stored in readable form can one day be handed over. A company cannot hand over the words of a message it was never able to read.',
          'Sometimes the phone itself is taken over. In 2021 the Pegasus Project examined a leaked list of some 50,000 phone numbers of potential targets of NSO Group’s spyware, which, once secretly installed, gives full access to a phone’s messages, camera and microphone. Amnesty International found that Pegasus had been installed on the phone of Hatice Cengiz, Jamal Khashoggi’s fiancée, four days after his murder; NSO denied the claims.[23] Citizen Lab had found traces of Pegasus in 45 countries, Türkiye among them, in 2018.[24] In early 2025 WhatsApp said that about 90 journalists and members of civil society in more than 20 countries had been targeted with spyware from the Israel-based company Paragon, which experts say could get onto a phone without a single tap and then read even encrypted messages.[25]',
          'So let us be plain: once spyware like that is on a phone, no app’s encryption can protect you, us2us included, because the spyware reads each message on the phone itself, after it has been decrypted. Encryption protects a message on its way, not a phone that has been taken over.',
        ],
        quote: { text: 'Encryption is your friend.', by: 'Jeff Greene, CISA (NBC News)', after: 0 },
      },
      {
        id: WHY_SECTION.debate,
        title: 'The argument isn’t over',
        paragraphs: [
          'Who may read our messages is still being argued over. In May 2022 the European Commission proposed a law against child sexual abuse that could oblige services to scan private conversations. The EU’s data-protection authorities called such abuse a particularly serious and heinous crime, but raised serious concerns about the proposal’s proportionality, stressed that encryption contributes in a fundamental way to private life and confidential communication, and asked for the law to state clearly that nothing in it may be read as prohibiting or weakening encryption.[26] In November 2025 member states dropped mandatory scanning from their joint position, but kept the option for platforms to scan voluntarily.[27] The temporary rules allowing that lapsed in April 2026 and returned in July, to last until 2028, with end-to-end encrypted communication explicitly left out; talks on a permanent law go on.[28]',
          'In Britain, after the Home Office reportedly demanded access to encrypted iCloud data, Apple stopped offering Advanced Data Protection, the opt-in end-to-end encryption for iCloud data, to UK users in February 2025, saying it had never built a backdoor or master key into its products and never would; the Home Office would neither confirm nor deny the demand.[29] The government backed down on that demand later in 2025, then issued a new one covering British users only, and in July 2026 Apple took that one to court as well.[30]',
          'We lived through this argument in Türkiye, too. In January 2021 a change to WhatsApp’s privacy terms caused an uproar. Messages were already end-to-end encrypted; the issue was metadata: who we talk to, when, how often, from which device and from where.[31] Türkiye’s data protection board found that people had been left no free choice but to accept data transfers abroad, and fined WhatsApp 1,950,000 lira.[32] After an interim order from the Competition Board, WhatsApp said the data-sharing update would not take effect for anyone in Türkiye.[33] And people moved: in the first eleven days of January 2021, Telegram’s daily users in Türkiye nearly tripled.[34] So much for the idea that nobody cares about privacy.',
        ],
      },
      {
        id: WHY_SECTION.wrong,
        title: 'Listening in is wrong',
        paragraphs: [
          'Reading all this, it is tempting to shrug: “I have nothing to hide.” We would turn that around. Why should what you wrote to someone you love at midnight, the question you were too shy to ask your doctor, the worry you shared with a friend, sit in someone else’s file? Not because we have something to hide. Because some words are meant for one person only.',
          'This is what we believe: listening in on people’s conversations is wrong, whether a government does it, a company, an ad network or someone merely curious. We know that fighting serious crime is hard, and that it matters. But a door that opens onto everyone’s messages does not open only for the well-meaning, and once it is open, those who opened it no longer choose who walks through.',
          'Being listened to doesn’t silence people all at once. First we choose our words more carefully, then our topics, and in the end the people we talk to. Yet private conversation is where we think out loud, confide, love and change our minds, and where people learn to trust each other.',
        ],
        quote: { text: 'People who know they are being listened to learn silence. We want no one silenced.', by: 'The SyconX team', after: 1 },
      },
      {
        id: WHY_SECTION.built,
        title: 'What we built, and why',
        paragraphs: [
          'So we built us2us on that belief: a private line between two people, built to keep everyone else out. Every decision came down to one question: does this keep the conversation between the two of them?',
          'We ask for no phone number; a username is enough to sign up and to find someone. Messages are locked on your phone with libsignal, the official library of the Signal protocol, and open only on the other person’s; their content is also protected against being recorded today and attacked later with a quantum computer. With sealed sender, our server sees where an envelope is going, but cannot read on the envelope where it came from.',
          'We send no notifications, on purpose. We never use Apple’s or Google’s push servers; messages arrive when you open the app. With no push token registered anywhere, there is no push record for anyone to ask for. We know it is not an easy choice. In one study, 30 volunteers switched off all notifications for 24 hours. They felt less distracted and more productive, but some worried that they could not answer as quickly as others expected, and felt a little cut off. Two years later, half of the participants still handled notifications differently.[35] We believe that worry fades once we accept that everyone may answer in their own time. Expecting something important? Just tell them: “check us2us”.',
          'Your conversations live on the two phones and nowhere else. Our server keeps no archive. It works like a forgetful postman: it deletes each envelope as soon as it is delivered, or after 14 days at most. The app opens with a secret code, and on an iPhone every attempt at the code needs a key that never leaves the Secure Enclave. Our code is open: anyone can check all of this at github.com/syconx-software/us2us.',
          'And here is what we can’t promise. Sealed sender doesn’t hide everything: the server can see IP addresses and timing. Ours keeps no record of them, but on that you have to take our word. The first time you write to someone, their key comes from our server; until you meet and compare safety numbers, you can’t be fully sure that no one stepped in between. The post-quantum protection covers the content of messages, not the sender details on the envelope. And no app, us2us included, can protect you from spyware on your own phone, or from the other person photographing the screen. The details are in our [privacy policy](/en/privacy#security).',
        ],
      },
      {
        id: WHY_SECTION.volunteers,
        title: 'Free, open source, made by volunteers',
        paragraphs: [
          'us2us is free. No ads, no trackers, no analytics. We don’t sell your data, and we collect no more of it than the service needs to work. They say that if something is free, you are the product; we would like to prove that wrong. We have no business model that needs your messages, your habits or your attention, and we don’t want one.',
          'At SyconX we build us2us as volunteers. We made it for ourselves first, to know that when we talk to our mothers, our brothers and sisters, an old friend far away, no one is in between. Then we realised it should be everyone’s right. Privacy shouldn’t be a privilege for those who can afford it.',
          'The code is licensed under the AGPL-3.0, and the full source code is public at github.com/syconx-software/us2us. Anyone who changes us2us and offers it to others over a network must then share their changes too, so no one can take this work and lock it inside a closed product of their own. For us, open source is a way of being accountable: you should know that an app can’t read your messages from its code, not from our word.',
        ],
      },
      {
        id: WHY_SECTION.invitation,
        title: 'Learning to wait again',
        paragraphs: [
          'Today us2us is in closed beta on iOS, through TestFlight, by invitation; the App Store and Android versions are coming soon. If you have an invitation, try it, and tell us wherever you got stuck or something felt wrong. Read the code at github.com/syconx-software/us2us, and if you find a mistake, say so. Criticism is how this app will grow.',
          'And try this, too: send a message, then wait. While your phone stays quiet, the other person is choosing their own moment to answer. When they open the app, your message will be there, for them alone.',
          'Like the words we whispered into a cup as children: the string pulls tight, the voice travels, and at the other end only one person hears. We built us2us to pull that string tight again.',
        ],
      },
    ],
    sources: {
      title: 'Sources',
      intro: 'The reports, studies and documents this statement refers to, in the order the text first cites them. The links lead to other sites.',
      mark: 'Source',
      language: { tr: 'Turkish', en: 'English' },
      back: 'Back to the text',
    },
    about: {
      title: 'About SyconX',
      paragraphs: [
        'SyconX is the software team behind us2us. us2us is an end-to-end encrypted messenger for conversations between two people: it asks for no phone number, sends no push notifications and keeps conversations on the two phones only. It is free, with no ads, trackers or analytics. Its code is licensed under the AGPL-3.0 and is public at github.com/syconx-software/us2us.',
        'us2us is in closed beta on iOS through TestFlight; the App Store and Android versions are coming soon. For press questions and interview requests, please write to the address below.',
      ],
      pressLabel: 'Press contact',
    },
    cta: {
      title: 'The other end of the string is yours.',
      body: 'If you would like to try us2us, read how it works, or tell us where we went wrong, start here.',
      links: [
        { label: 'For beta testers', page: 'support', hash: BETA_ANCHOR },
        { label: 'Privacy policy', page: 'privacy' },
        { label: 'Licence and source code', page: 'support', hash: LICENCES_ANCHOR.en },
      ],
    },
  },
}

// ── rendering helpers (pure, so the unit tests can check them) ────────────

/** A run of text: plain, a citation of source `n`, or a link to one of our own pages. */
export type WhyPart =
  | { kind: 'text', text: string }
  | { kind: 'cite', n: number, first: boolean }
  | { kind: 'link', text: string, href: string }

/** `[n]` cites a source; `[label](/path#hash)` links a page of this site. */
const TOKEN = /\[(\d+)\]|\[([^\]]+)\]\((\/[^)\s]*)\)/g

/** Splits `text` into parts; `seen` collects the sources cited so far, so only a source's first citation is `first`. */
export function whyParts(text: string, seen: Set<number> = new Set()): WhyPart[] {
  const parts: WhyPart[] = []
  let last = 0
  for (const match of text.matchAll(TOKEN)) {
    if (match.index > last) parts.push({ kind: 'text', text: text.slice(last, match.index) })
    if (match[1] !== undefined) {
      const n = Number(match[1])
      parts.push({ kind: 'cite', n, first: !seen.has(n) })
      seen.add(n)
    } else {
      parts.push({ kind: 'link', text: match[2]!, href: match[3]! })
    }
    last = match.index + match[0].length
  }
  if (last < text.length) parts.push({ kind: 'text', text: text.slice(last) })
  return parts
}

/** One block of a section, in reading order. */
export type WhyBlock = { kind: 'paragraph', parts: WhyPart[] } | { kind: 'quote', parts: WhyPart[], by: string }

/** The page's text parsed in reading order (standfirst, sections, about box, call to action). */
export interface WhyDocument {
  standfirst: WhyPart[]
  sections: Array<{ id: WhySection['id'], title: string, blocks: WhyBlock[] }>
  about: WhyPart[][]
  cta: WhyPart[]
  /** Source numbers in the order they are first cited. */
  cited: number[]
}

export function whyDocument(locale: Locale): WhyDocument {
  const text = why[locale]
  const seen = new Set<number>()
  const parse = (value: string) => whyParts(value, seen)
  const standfirst = parse(text.standfirst)
  const sections = text.sections.map((section) => {
    const blocks: WhyBlock[] = []
    section.paragraphs.forEach((paragraph, index) => {
      blocks.push({ kind: 'paragraph', parts: parse(paragraph) })
      if (section.quote && section.quote.after === index) blocks.push({ kind: 'quote', parts: parse(section.quote.text), by: section.quote.by })
    })
    return { id: section.id, title: section.title, blocks }
  })
  const about = text.about.paragraphs.map(parse)
  const cta = parse(text.cta.body)
  return { standfirst, sections, about, cta, cited: [...seen] }
}

/** Element ids of a source entry and of its first citation in the text. */
export const sourceId = (n: number) => `kaynak-${n}`
export const citeId = (n: number) => `atif-${n}`

/** A YYYY-MM-DD date as the reader's language writes it ("11 Ekim 2026", "11 October 2026"). */
export function whyDate(iso: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale === 'tr' ? 'tr-TR' : 'en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(`${iso}T00:00:00Z`))
}

/** The href of a call-to-action link. */
export const whyHref = (locale: Locale, link: WhyText['cta']['links'][number]) => `${paths[locale][link.page]}${link.hash ? `#${link.hash}` : ''}`
