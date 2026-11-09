/*
 * "Kullanım koşulları / Terms of use" (/tr/kullanim-kosullari, /en/terms):
 * the terms every user accepts in the app before creating an account,
 * restoring a backup or moving from another phone; the apps link to these
 * two addresses. Laid out by TermsPage.vue in the privacy page's frame. The
 * Turkish text is the source; the English one says the same things in the
 * same order. The `TermsText` type makes a missing translation a type error.
 *
 * What it says about the relay, reports and secret codes must agree with
 * the privacy policy and the support page in ./site.ts (the relay cannot
 * read message content; a report is an e-mail the reporting user sends
 * to SUPPORT_EMAIL; a secret code cannot be recovered).
 *
 * Section ids (TERMS_SECTION) are the same in both languages, so the
 * language switch keeps the reader's place.
 */

import { SUPPORT_EMAIL, type Locale } from './site'

/** Section ids, shared by both languages. */
export const TERMS_SECTION = {
  acceptance: 'kabul',
  conduct: 'tolerans',
  tools: 'engelle-bildir',
  enforcement: 'yaptirim',
  communities: 'topluluklar',
  age: 'yas',
  account: 'hesap',
  changes: 'degisiklikler',
  contact: 'iletisim',
} as const

export type TermsSectionId = (typeof TERMS_SECTION)[keyof typeof TERMS_SECTION]

/** The minimum age to use us2us, unless the user's country sets a higher one. */
export const TERMS_MINIMUM_AGE = 13

export interface TermsSection {
  id: TermsSectionId
  title: string
  paragraphs: string[]
  items?: string[]
}

export interface TermsText {
  title: string
  description: string
  /** The effective date line under the title. */
  effective: string
  summary: string[]
  /** The zero-tolerance statement, shown on its own under the summary. */
  zeroTolerance: string
  sections: TermsSection[]
  contactTitle: string
  contact: string
}

export const terms: Record<Locale, TermsText> = {
  tr: {
    title: 'Kullanım koşulları',
    description: 'us2us’ı kullanma koşulları: uygunsuz içeriğe ve kötü niyetli kullanıcılara sıfır tolerans, engelleme ve bildirme, kuralları çiğneyen hesapların kaldırılması.',
    effective: 'Yürürlük tarihi: 9 Kasım 2026',
    summary: [
      'Bu koşullar, SyconX LLC’nin geliştirdiği us2us mesajlaşma uygulamasının ve us2us.app sitesinin kullanımını düzenler. Lütfen dikkatle okuyun.',
    ],
    zeroTolerance: 'us2us’ta uygunsuz içeriğe ve kötü niyetli kullanıcılara hiçbir şekilde tolerans gösterilmez.',
    sections: [
      {
        id: TERMS_SECTION.acceptance,
        title: 'Koşulların kabulü',
        paragraphs: [
          'us2us’ı kullanmak, bu koşulları kabul etmek demektir. Kabul etmiyorsanız us2us’ı kullanmayın.',
          'Uygulama, hesap oluşturmadan, bir hesap yedeğini geri yüklemeden ya da başka bir telefondan taşımadan önce bu koşulları kabul etmenizi ister; kabul etmeden bu adımlara geçemezsiniz.',
          'Sunucumuzun neyi sakladığı ve neyi göremediği gizlilik politikamızda anlatılır; bu koşullar onunla birlikte geçerlidir.',
        ],
      },
      {
        id: TERMS_SECTION.conduct,
        title: 'Uygunsuz içeriğe sıfır tolerans',
        paragraphs: [
          'us2us’ı aşağıdakilerden herhangi biri için kullanmak yasaktır. Bu kural bire bir sohbetlerde, topluluklarda, kullanıcı adlarında, görünen adlarda ve profil resimlerinde aynı şekilde geçerlidir:',
        ],
        items: [
          'Taciz: birini istemediği hâlde rahatsız etmek, aşağılamak ya da peşini bırakmamak',
          'Tehdit',
          'Nefret söylemi',
          'Reşit olmayanları içeren her türlü cinsel içerik',
          'Kişinin rızası olmadan paylaşılan mahrem görüntüler',
          'Spam: istenmeyen toplu ya da tekrarlayan mesajlar',
          'Başka bir kişiyi ya da kurumu taklit etmek',
          'Yasa dışı içerik',
          'Şiddete kışkırtmak',
          'Dolandırıcılık',
        ],
      },
      {
        id: TERMS_SECTION.tools,
        title: 'Engelleme ve bildirme',
        paragraphs: [
          'Herhangi bir kişiyi istediğiniz zaman “Engelle” ile engelleyebilirsiniz; engellediğiniz kişinin mesajları artık size ulaşmaz.',
          `Bir kişiyi ya da bir mesajı uygulamadaki “Bildir” ile bize bildirebilirsiniz. Telefonunuz ${SUPPORT_EMAIL} adresine, sizin göndereceğiniz bir e-posta hazırlar. Bildirimlerinizi doğrudan ${SUPPORT_EMAIL} adresine yazarak da yapabilirsiniz.`,
        ],
      },
      {
        id: TERMS_SECTION.enforcement,
        title: 'Kuralları çiğneyenler',
        paragraphs: [
          'Bildirimleri 24 saat içinde inceleriz.',
          'Bu koşulları çiğneyen hesapları kaldırırız: kullanıcı adı sunucumuzda kapatılır ve o hesap artık mesaj gönderemez, alamaz.',
          'Yasa dışı içeriği yetkili makamlara bildirebiliriz.',
          'Mesajlar uçtan uca şifreli olduğu için biz de sunucumuz da mesaj içeriklerini okuyamayız. Bir bildirimde yalnızca bildiren kişinin göndermeyi seçtiği bilgiler bulunur: bildirilen kişinin kullanıcı adı, seçilen neden, yazılan not ve eklenirse ekran görüntüleri.',
        ],
      },
      {
        id: TERMS_SECTION.communities,
        title: 'Topluluklar',
        paragraphs: [
          'Bu koşullar topluluklarda da aynen geçerlidir.',
          'Topluluk yöneticileri bir mesajı herkes için silebilir ve üyeleri topluluktan çıkarabilir.',
          `Bir topluluktaki uygunsuz içeriği ya da kötü niyetli bir üyeyi de ${SUPPORT_EMAIL} adresine bildirebilirsiniz.`,
        ],
      },
      {
        id: TERMS_SECTION.age,
        title: 'Yaş sınırı',
        paragraphs: [
          `us2us’ı kullanmak için en az ${TERMS_MINIMUM_AGE} yaşında olmalısınız. Ülkenizde daha yüksek bir yaş sınırı varsa o geçerlidir.`,
        ],
      },
      {
        id: TERMS_SECTION.account,
        title: 'Hesabınız ve gizli kodunuz',
        paragraphs: [
          'Gizli kodunuzu korumak sizin sorumluluğunuzdadır.',
          'Gizli kod telefonunuzdan hiç çıkmaz ve sıfırlanamaz. Kaybolan bir kod kurtarılamaz; biz de dahil kimse onu geri getiremez. Hesap yedeğiniz ve parolası varsa hesabınıza ve kişilerinize yeni bir kodla dönebilirsiniz; mesajlar yedekte yoktur.',
        ],
      },
      {
        id: TERMS_SECTION.changes,
        title: 'Koşullardaki değişiklikler',
        paragraphs: [
          'Bu koşulları zaman zaman güncelleyebiliriz. Güncel metin her zaman bu sayfadadır ve yürürlük tarihi sayfanın başında yazar.',
          'Değişikliklerden sonra us2us’ı kullanmaya devam etmeniz, güncel koşulları kabul ettiğiniz anlamına gelir.',
        ],
      },
    ],
    contactTitle: 'İletişim',
    contact: 'us2us’ı SyconX LLC geliştirir. Bu koşullarla ilgili sorularınız ve bildirimleriniz için bize yazın:',
  },
  en: {
    title: 'Terms of use',
    description: 'The terms for using us2us: no tolerance for objectionable content or abusive users, blocking and reporting, and removal of accounts that break the rules.',
    effective: 'Effective: 9 November 2026',
    summary: [
      'These terms govern your use of us2us, the messaging app made by SyconX LLC, and of the us2us.app website. Please read them carefully.',
    ],
    zeroTolerance: 'There is no tolerance for objectionable content or abusive users on us2us.',
    sections: [
      {
        id: TERMS_SECTION.acceptance,
        title: 'Accepting these terms',
        paragraphs: [
          'Using us2us means accepting these terms. If you do not accept them, do not use us2us.',
          'The app asks you to accept these terms before you create an account, restore an account backup or move from another phone; you cannot take those steps without accepting them.',
          'Our privacy policy explains what our server stores and what it can never see; these terms apply together with it.',
        ],
      },
      {
        id: TERMS_SECTION.conduct,
        title: 'Zero tolerance for objectionable content',
        paragraphs: [
          'You may not use us2us for any of the following. The rule applies equally to one-to-one chats, communities, usernames, display names and profile pictures:',
        ],
        items: [
          'Harassment: bothering, demeaning or pursuing someone against their wishes',
          'Threats',
          'Hate speech',
          'Any sexual content involving minors',
          'Intimate images shared without the person’s consent',
          'Spam: unwanted bulk or repeated messages',
          'Impersonating another person or organisation',
          'Illegal content',
          'Inciting violence',
          'Fraud',
        ],
      },
      {
        id: TERMS_SECTION.tools,
        title: 'Blocking and reporting',
        paragraphs: [
          'You can block any person at any time with “Block”; messages from someone you blocked no longer reach you.',
          `You can report a person or a message to us with “Report” in the app. Your phone prepares an e-mail to ${SUPPORT_EMAIL} that you send yourself. You can also report by writing to ${SUPPORT_EMAIL} directly.`,
        ],
      },
      {
        id: TERMS_SECTION.enforcement,
        title: 'When someone breaks the rules',
        paragraphs: [
          'We review reports within 24 hours.',
          'We remove accounts that break these terms: the username is closed on our server, and that account can no longer send or receive messages.',
          'We may report illegal content to the authorities.',
          'Because messages are end-to-end encrypted, neither we nor our server can read them. A report contains only what the reporting user chooses to send: the reported person’s username, the reason chosen, their note and, if they attach them, screenshots.',
        ],
      },
      {
        id: TERMS_SECTION.communities,
        title: 'Communities',
        paragraphs: [
          'These terms apply in communities just the same.',
          'Community admins can delete a message for everyone and remove members from the community.',
          `You can report objectionable content or an abusive member of a community to ${SUPPORT_EMAIL} as well.`,
        ],
      },
      {
        id: TERMS_SECTION.age,
        title: 'Minimum age',
        paragraphs: [
          `You must be at least ${TERMS_MINIMUM_AGE} years old to use us2us. If your country sets a higher minimum age, that age applies.`,
        ],
      },
      {
        id: TERMS_SECTION.account,
        title: 'Your account and secret code',
        paragraphs: [
          'Keeping your secret code safe is your responsibility.',
          'Your secret code never leaves your phone and cannot be reset. A lost code cannot be recovered; nobody, including us, can bring it back. If you have your account backup and its password, you can get back into your account and contacts with a new code; messages are not in the backup.',
        ],
      },
      {
        id: TERMS_SECTION.changes,
        title: 'Changes to these terms',
        paragraphs: [
          'We may update these terms from time to time. The current text is always on this page, with its effective date at the top.',
          'If you keep using us2us after a change, you accept the updated terms.',
        ],
      },
    ],
    contactTitle: 'Contact',
    contact: 'us2us is made by SyconX LLC. Questions about these terms, or something to report? Write to us:',
  },
}
