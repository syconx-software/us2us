package com.syconx.us2us.feature.settings

// The words the 7.2 strength estimate looks for (PasswordStrength). All are lower-case ASCII: the
// estimate folds ç ğ ı ö ş ü (and â î û) to ASCII and reads leet spellings (`s1fr3`) before looking a
// piece up. Only pieces of three or more characters are looked up.
//
// The lists are deliberately small: they hold what a guesser tries first, not every word. Anything
// not listed is counted as random characters, so a missing word makes the estimate kinder, never
// harsher.
internal object PasswordStrengthWords {
    /** The very first guesses of any cracking run (≈ 6 bits each). */
    val topWords: Set<String> = wordSet(
        """
        password passwd passw0rd parola parolam sifre sifrem sifresi qwerty qwertz azerty admin administrator
        root guest login test deneme welcome hosgeldin hosgeldiniz letmein iloveyou loveyou seviyorum
        seniseviyorum sevgilim askim askimsin canim canimsin bebegim hayatim birtanem bitanem monkey dragon
        master shadow sunshine princess football baseball superman batman starwars whatever freedom trustno
        secret gizli yedek yedekparola backup merhaba selam hello allah bismillah elhamdulillah allahbirdir
        masallah insallah galatasaray fenerbahce besiktas trabzonspor turkiye ataturk mustafakemal kemalataturk
        cumhuriyet istanbul ankara izmir us2us changeme default
        """,
    )

    /**
     * Common first names, surnames, places, clubs and everyday words, Turkish and English (≈ 11 bits
     * each: about log2 of the list's 1,700 entries).
     */
    val commonWords: Set<String> = wordSet(
        """
        mehmet mustafa ahmet ali huseyin hasan ibrahim ismail osman yusuf murat omer ramazan halil suleyman
        abdullah mahmut salih kemal recep fatih emre burak yasin enes mert can kaan baris serkan hakan cem cenk
        deniz efe ege emir eren erdem erkan ertan erhan ercan furkan gokhan gurkan harun hamza hikmet ilker
        irfan kadir kerem koray levent metin muhammed muhammet nazim necati nihat oguz oguzhan okan onur orhan
        ozan ozgur polat riza sait selim sercan serdar sinan tarik taner tolga tuncay tunc ugur umut volkan
        yavuz yigit zafer batuhan berk berkay berkan bora caner cagri cagatay cihan dogan dogukan egemen eray
        ersin evren gokay goktug hakki hilmi kagan kenan mesut mithat mucahit nevzat nuri orkun ozkan ridvan
        sami savas sefa semih sertac seyit sukru tahsin tamer tayfun timur turgay turgut ufuk utku yakup yunus
        zeki alp alper altan arda aras atakan atilla aydin aykut aytac bahadir baran bayram bekir bilal burhan
        bulent cemal cengiz cetin davut dursun emin engin erol esat ferhat fikret gazi gunay hayri ilhan kamil
        kazim kudret mahir mazlum necmi nedim nejat niyazi ogun rauf resat sadik sahin selcuk sener seref sezer
        suat tekin tevfik turan vedat veli yasar yahya yalcin yener yucel yuksel zekeriya poyraz miran alparslan
        aslan kartal atlas ayaz cinar eymen mirac asaf arif adem adnan ahmed akif aziz bahri bedir celal cevdet
        erdal ersan faruk fevzi galip haydar ihsan ilyas iskender kerim lutfi mehdi mevlut muhsin musa mumin
        nail namik orhun rasim recai rifat sabri sedat selahattin semsettin talat tayyip tuncer ugurcan umit
        vahit yildirim yilmaz zubeyir berat emirhan eyup hamit kutay kuzey mete ozer serhat sarp tuna tunahan
        yagiz yaman

        ayse fatma emine hatice zeynep elif meryem sultan zehra hanife merve havva zeliha esra ozlem yasemin
        hulya leyla derya tugba busra kubra ebru seda selin sevgi gamze cansu buse irem ece ela eda ezgi gizem
        gul gulsah gulsen hande ipek kader melek melike nazli nur nurcan ozge pinar sema serap sibel songul tuba
        tulay yeliz yildiz zuhal asli ayla aylin aynur aysel aysegul aysun basak beren berna betul beyza burcu
        cagla canan ceren cigdem damla defne demet didem dilan dilara dilek duygu emel esin esma feride figen
        filiz funda gonul guler gulay gulcan gulten hacer hale hayriye hilal ilknur ilayda kevser lale mehtap
        melis meltem mine muge naz nazan nergis nesrin neslihan nilay nilgun nihal nisa nisanur oya ozden pelin
        rabia reyhan rukiye saadet sabriye safiye sanem selda selma semra serpil sevda sevil sevim seyma sila
        simge sinem sude sumeyye sule tansu tulin tugce umran yagmur yaren yeter zerrin zuleyha azra asya eylul
        ecrin elisa hira mira masal nehir duru asel ahsen belinay medine umay zumra lina alya aleyna asude
        bengisu birsen cemre deren dilber ekin elvan esengul fadime gulbahar gulizar gulsum hamiyet hazal inci
        kezban lamia makbule melda nalan nebahat necla nermin nuray nurhan nursel oznur pakize perihan rahime
        sakine saliha seher serife sevgul sevinc sukran sukriye ummuhan zekiye zubeyde aysenur berfin beril
        buket ceyda dicle dilay ebrar elcin esila feyza gulce ilke irmak isil iclal kardelen lara melisa
        mihriban mukaddes pembe ruya ruveyda selen serra sevde sezen sirin yonca

        kaya demir celik ozturk ozdemir arslan kilic kara koc kurt simsek ozcan korkmaz cakir erdogan acar sen
        aktas gunes bozkurt bulut keskin unal isik kaplan avci sari tas kocak kose ates karaca akin aksoy coskun
        akbulut karatas gurbuz yurt altun akgul sonmez ozyurt duman uysal toprak uzun cicek aydogan kirmizi
        demirci kucuk buyuk kahraman arikan dag ekinci yazici seker ince bilgin ucar gokce mutlu kocaman akyol
        baysal kalkan esen gunduz sarac altintas aksu akcay akdeniz aktepe alkan altay arslantas atalay ayhan
        baykal bilgic bingol cakmak caliskan cevik cimen dagli demirel dincer dogru durmaz dundar ergun eser
        genc gok gokmen guven guzel inan kalayci karabulut karakaya karakus kavak kaymaz kesici kilinc kocabas
        koroglu kulac oral ozbek ozdem ozgun ozkaya ozmen ozsoy parlak sahan saglam soylu sungur tasdemir temiz
        topcu tuncel turkmen tutar ugurlu uslu yagci yalin yazgan yesil yetkin yurdakul zengin

        adana adiyaman afyon agri amasya antalya artvin balikesir bilecik bitlis bolu burdur bursa canakkale
        cankiri corum denizli diyarbakir edirne elazig erzincan erzurum eskisehir gaziantep antep giresun
        gumushane hakkari hatay isparta mersin icel kars kastamonu kayseri kirklareli kirsehir kocaeli izmit
        konya kutahya malatya manisa maras kahramanmaras mardin mugla mus nevsehir nigde ordu rize sakarya
        adapazari samsun siirt sinop sivas tekirdag tokat trabzon tunceli urfa sanliurfa usak van yozgat
        zonguldak aksaray bayburt karaman kirikkale sirnak bartin ardahan igdir yalova karabuk kilis osmaniye
        duzce bodrum alanya fethiye marmaris kapadokya kadikoy uskudar beyoglu sisli bakirkoy pendik maltepe
        atasehir esenyurt umraniye cankaya kecioren karsiyaka bornova buca cesme kemer side nicosia lefkosa
        girne magusa kibris anadolu karadeniz marmara trakya

        cimbom cimbombom fener kanarya bjk karakartal bordomavi sarikirmizi sarilacivert bursaspor sivasspor
        konyaspor kayserispor antalyaspor alanyaspor goztepe karsiyakaspor kocaelispor samsunspor basaksehir
        rizespor ankaragucu genclerbirligi eskisehirspor denizlispor altinordu realmadrid madrid barcelona barca
        chelsea arsenal liverpool juventus milan inter bayern manchester united city psg ajax porto benfica
        napoli roma lazio

        james john robert michael william david richard joseph thomas charles chris christopher daniel matthew
        anthony mark donald steven paul andrew joshua kevin brian george edward ronald timothy jason jeffrey
        ryan jacob gary nicholas eric jonathan stephen larry justin scott brandon benjamin samuel frank gregory
        raymond alexander patrick jack dennis jerry tyler aaron jose adam henry nathan douglas peter kyle walter
        ethan jeremy harold keith christian roger noah gerald carl terry sean austin arthur lawrence jesse dylan
        bryan joe jordan billy bruce albert willie gabriel logan alan juan wayne roy ralph randy eugene vincent
        russell elijah louis bobby philip johnny mary patricia jennifer linda elizabeth barbara susan jessica
        sarah karen nancy lisa betty margaret sandra ashley kimberly emily donna michelle dorothy carol amanda
        melissa deborah stephanie rebecca sharon laura cynthia kathleen amy shirley angela helen anna brenda
        pamela nicole emma samantha katherine christine debra rachel catherine carolyn janet ruth maria heather
        diane virginia julie joyce victoria olivia kelly christina lauren joan evelyn judith megan cheryl andrea
        hannah martha jacqueline frances gloria ann teresa kathryn sara janice jean alice madison doris abigail
        julia judy grace denise amber marilyn beverly danielle theresa sophia marie diana brittany natalie
        isabella charlotte rose alexis kayla mia ava lily chloe zoe max alex sam ben tom tim jim bob anne jane
        kate tony nick mike dave steve smith johnson williams brown jones miller davis wilson anderson taylor
        moore jackson martin lee white harris clark lewis walker hall allen young king wright hill green adams
        baker nelson carter mitchell roberts turner phillips campbell parker evans edwards collins stewart
        morris murphy cook rogers morgan cooper peterson reed bailey bell howard ward cox richardson wood watson
        brooks bennett gray hughes price myers long foster sanders ross morales powell sullivan ortiz jenkins
        perry butler barnes fisher henderson coleman simmons patterson reynolds hamilton graham wallace west
        cole hayes gibson ellis stevens murray ford marshall owens harrison fernandez mcdonald woods washington
        kennedy wells

        love lover loveme baby angel summer winter spring autumn fall sunny happy lucky money tiger lion bear
        wolf eagle hawk falcon shark snake horse pony kitty kitten puppy doggy cat dog fish bird apple orange
        banana cherry lemon peach strawberry chocolate cookie candy sugar honey sweet sweetie cutie pretty
        beauty flower daisy star stars moon sun sky sea ocean river mountain forest tree blue red black yellow
        purple pink silver gold golden diamond crystal magic dream dreams heaven hell devil jesus christ god
        lord faith hope peace joy life live world earth fire water ice snow rain storm thunder light dark night
        day morning evening home house family friend friends forever always never thanks please sorry private
        access pass word key lock open close start begin end game games gamer player play soccer hockey tennis
        golf basket basketball music rock metal punk jazz guitar piano dance party cool super hero power strong
        queen prince lady boy girl man woman mom mother dad father son daughter brother sister bebe computer
        internet google samsung iphone android windows linux system server network phone mobile cyber hacker
        ninja killer death blood ghost zombie vampire spider spiderman ironman hulk thor marvel pokemon pikachu
        naruto goku mario zelda minecraft fortnite roblox matrix yoda jedi harry potter hogwarts frodo gandalf
        america usa london paris berlin rome tokyo newyork england france germany italy spain canada mexico
        brazil china russia japan qwer asdf zxcv abc abcd xyz one two three four five six seven eight nine ten
        first last best good bad new old big small little short high low hot cold fast slow free true false yes
        nope okay

        ask asik sevgili kalp hayat yasam dunya yildizlar orman agac papatya menekse bahar yaz kis sonbahar kar
        ruzgar firtina hava karanlik gece sabah aksam aile baba annem babam kardes kardesim abla ablam abi abim
        agabey dede babaanne anneanne oglum kizim bebek cocuk arkadas dost dostum kanka kanki cicim tatlim tatli
        bal sut ekmek cay kahve elma armut kiraz cilek muz portakal limon karpuz kavun uzum kedi kedim kedicik
        pisi pamuk tekir minnos kopek kopegim karabas kus balik ayi tavsan fare tilki yilan papagan tavuk inek
        kuzu keci mavi siyah beyaz mor turuncu gri lacivert altin gumus elmas yakut zumrut mutluluk huzur
        ozgurluk nese guzelim cirkin yeni eski iyi kotu yanlis evet hayir tamam selamlar gunaydin iyigeceler
        hosca gorusuruz tesekkur sagol lutfen ozur bilgisayar telefon oyun oyuncu futbol basketbol voleybol
        takim mac gol sampiyon kupa okul ogrenci ogretmen sinif ders kitap kalem defter universite para fakir
        kral kralice prens prenses padisah osmanli turk vatan millet bayrak sehit asker polis doktor hemsire
        avukat muhendis pilot sofor yolcu yol araba otobus tren ucak gemi bisiklet motor muzik sarki sarkici
        gitar piyano dans film dizi sinema tiyatro resim sanat sair siir roman hikaye hayal gercek sir sihir
        buyu cin peri seytan cennet cehennem tanri peygamber cuma kurban oruc namaz dua iman islam musluman
        kuran haci hoca imam ezan cami ozledim hasret ayrilik veda gozyasi gulumse kahkaha kalbim gozum gozlerin
        ruhum ruh cananim yarim yar sevdigim gulum omrum seninle benimle sensiz biz siz onlar kadin erkek kiz
        oglan yasli bey hanim efendi patron sef usta lider baskan bitane birtane hayatimsin kuzum kuzucuk tontis
        bicim cilgin deli delikanli yakisikli guzellik sevimli tatlis yildizim gunesim ayim melegim prensesim
        kralim kralicem aslanim kaplanim kartalim bebegimsin sonsuz sonsuza ebedi ebediyen kismet nasip umudum
        hayallerim ruyalarim sifresiz parolasiz kodsuz sakla sakli gizlim ozel kisisel benim senin bizim yedegim
        yedekleme anahtar kilit kasa kapi pencere masa sandalye bardak tabak kasik catal bicak
        """,
    )

    private fun wordSet(list: String): Set<String> = list.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
}
