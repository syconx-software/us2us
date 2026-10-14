package com.syconx.us2us.domain.nav

/** Screens pushed on the main stack (rooted at 4.1 Sohbetler) — flows.md › Map. */
sealed interface AppRoute {
    /** 5.1 Sohbet. */
    data class Conversation(val chat: ChatId) : AppRoute

    /** 4.3 Mesaj istekleri. */
    data object Requests : AppRoute

    /** 4.4 İstek önizleme. */
    data class RequestPreview(val request: RequestId) : AppRoute

    /** 6.1 Güvenlik numarası: the contact screen, opened on [tab]. */
    data class SafetyNumber(val chat: ChatId, val tab: ContactTab = ContactTab.Verification) : AppRoute

    /** 7.1 Ayarlar. */
    data object Settings : AppRoute

    /** 7.1 › a picker row. */
    data class Picker(val kind: SettingsPicker) : AppRoute

    /** 7.2 Yedeği dışa aktar. */
    data object BackupExport : AppRoute

    /** 7.1 › Gizli kodu değiştir, Hakkında, (debug) 7.3. */
    data class Page(val kind: SettingsPage) : AppRoute

    /** Edge-swipe back is allowed everywhere on the main stack (flows.md › Back behaviour). */
    val allowsSwipeBack: Boolean get() = true
}

/** Screens of onboarding (01–02), pushed after the paged welcome. */
enum class OnboardingRoute {
    /** 2.1 Kullanıcı adı. */
    Username,

    /** 2.2 Gizli kod. */
    SecretCode,

    /** 2.3 Geri dönüş yok. */
    NoWayBack,

    /** 7.3 Yedekten geri yükle (new device). */
    Restore,
}

/** The pages of section 07 beyond 7.1 itself and its pickers. */
enum class SettingsPage {
    /** 7.2 Yedeği dışa aktar (pushed as [AppRoute.BackupExport]). */
    BackupExport,

    /** 7.1 › Gizli kodu değiştir: current code (3.2 layout) → new code ×2 (2.2 layout). */
    ChangeCode,

    /** 7.1 › “Face ID ile aç” turned on while it is off: the code once, so the biometric key can wrap the vault key. */
    EnableFaceId,

    /** 7.1 › Hakkında: version, privacy, licences. */
    About,

    /** 7.1 › Gizlilik › Engellenenler: everyone blocked, also without a chat, each with “Engeli kaldır”. */
    Blocked,

    /** 7.3 Yedekten geri yükle. Production entry points are 1.1 and 3.3; inside settings only the debug harness opens it. */
    Restore,
}

/** The 7.1 rows that open a picker. [raw] is the iOS raw value. */
enum class SettingsPicker(val raw: String) {
    Presence("presence"),
    AutoLock("autoLock"),
    DefaultGhost("defaultGhost"),
    DefaultDisappearing("defaultDisappearing"),
    Theme("theme"),
    Language("language"),
    ;

    companion object {
        fun fromRaw(raw: String): SettingsPicker? = entries.firstOrNull { it.raw == raw }
    }
}

/** The contact screen's tabs: Doğrulama (6.1), Fotoğraflar, Belgeler. */
enum class ContactTab(val raw: String) {
    Verification("verification"),
    Photos("photos"),
    Documents("documents"),
    ;

    companion object {
        fun fromRaw(raw: String): ContactTab? = entries.firstOrNull { it.raw == raw }
    }
}

/** What 6.2 scans for. */
sealed interface QrScanMode {
    /** 6.2: the contact's safety-number code. */
    data class SafetyNumber(val chat: ChatId) : QrScanMode

    /** Add by QR: someone's contact code (from 4.1 or 4.2). */
    data object AddContact : QrScanMode
}

/** Where 7.3 Yedekten geri yükle was opened. */
enum class RestoreMode {
    /** 1.1 › “Yedekten geri yükle” on a new device (or after “Her şeyi sil”). */
    Onboarding,

    /** 3.3 › “Yedekten geri yükle” inside the lock: replaces this phone's account. */
    Lock,

    /** 7.1 (debug harness only). */
    Settings,
}

/** The app's root state (flows.md › App states). */
enum class Phase {
    /** No account on this device: 1.1 → 1.3 → 2.x. */
    Onboarding,

    /** An account exists but nothing has been unlocked since launch. */
    Locked,

    /** The main stack (4.1 …) is alive; it stays alive underneath the lock. */
    Unlocked,
}

/** What the security layer shows above everything. */
enum class Overlay {
    None,

    /** 3.4 — instantly on pause; dissolves on return if no lock is due. */
    PrivacyCover,

    /** 03 — Face ID (3.1) or the code (3.2), over the cover. */
    Lock,
}

/** Where onboarding starts when the root swaps to it. */
enum class OnboardingEntry {
    /** 1.1 (fresh install, after “Her şeyi sil”). */
    Welcome,

    /** 2.2 “1 / 2” — a backup was restored inside the lock (3.3 › 7.3). */
    NewCodeAfterRestore,

    /** 7.3 with the document picker — “Yedekten geri yükle” after the optional wipe erased everything. */
    RestoreFromBackup,
}

/** The first screen (`U2Start`). */
enum class StartScreen(val raw: String) {
    Welcome("welcome"),
    Locked("locked"),
    Chats("chats"),
    ;

    companion object {
        fun fromRaw(raw: String): StartScreen? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * `U2Route <feature>[:<target>][/<state>]` — one argument opens every screen of the app,
 * in every drawn state (the iOS `LaunchRoute`, same grammar and the same state ids).
 *
 * | route | opens |
 * |---|---|
 * | `onboarding:<state>` | 1.1–2.3, 7.3 from 1.1 |
 * | `lock:<state>` | 3.1–3.4 |
 * | `chats` · `chats:<state>` | 4.1–4.4 |
 * | `newChat` · `newChat:<username>` | the 4.2 sheet (with that query) |
 * | `requests` · `request:<id>` | 4.3 · 4.4 over 4.3 |
 * | `conversation:<chat>[/<state>]` | 5.1–5.6 |
 * | `safety:<chat>[/<state>]` | 6.1–6.3, add by QR |
 * | `settings` · `settings:<state>` | 7.1–7.3, pickers, code change, Hakkında |
 * | `backup` | 7.2 over 7.1 |
 * | `invite` · `invite:<state>` | the invite sheet over 4.1 |
 *
 * Without `U2Start` the route picks the start: `onboarding` → welcome, `lock` → locked,
 * everything else → chats.
 */
data class LaunchRoute(
    val feature: Feature,
    /** The chat, request or username a route is about. */
    val target: String? = null,
    /** The drawn state (a harness id such as `5.3` or `7.2-empty`). */
    val state: String? = null,
) {
    enum class Feature(val raw: String) {
        Onboarding("onboarding"),
        Lock("lock"),
        Chats("chats"),
        NewChat("newChat"),
        Requests("requests"),
        Request("request"),
        Conversation("conversation"),
        Safety("safety"),
        Settings("settings"),
        Backup("backup"),
        Invite("invite"),
        ;

        companion object {
            fun fromRaw(raw: String): Feature? = entries.firstOrNull { it.raw == raw }
        }
    }

    /** The harness state when the route is [feature]'s. */
    fun stateFor(feature: Feature): String? = if (this.feature == feature) state else null

    val impliedStart: StartScreen
        get() = when (feature) {
            Feature.Onboarding -> StartScreen.Welcome
            Feature.Lock -> StartScreen.Locked
            else -> StartScreen.Chats
        }

    /** The main-stack screens under 4.1. */
    val path: List<AppRoute>
        get() = when (feature) {
            Feature.Conversation -> target?.let { listOf(AppRoute.Conversation(ChatId(it))) }.orEmpty()
            Feature.Safety -> target?.let { listOf(AppRoute.SafetyNumber(ChatId(it))) }.orEmpty()
            Feature.Requests -> listOf(AppRoute.Requests)
            Feature.Request -> target?.let { listOf(AppRoute.Requests, AppRoute.RequestPreview(RequestId(it))) }.orEmpty()
            Feature.Settings -> listOf(AppRoute.Settings)
            Feature.Backup -> listOf(AppRoute.Settings, AppRoute.BackupExport)
            Feature.Onboarding, Feature.Lock, Feature.Chats, Feature.NewChat, Feature.Invite -> emptyList()
        }

    /** The argument again (`conversation:deniz/5.3`). */
    override fun toString(): String {
        val rest = listOfNotNull(target?.takeIf { it.isNotEmpty() }, state).joinToString("/")
        return if (rest.isEmpty()) feature.raw else "${feature.raw}:$rest"
    }

    companion object {
        /** Parses `feature[:target][/state]`; `null` for an unknown feature or a missing required target. */
        fun parse(argument: String): LaunchRoute? {
            val parts = argument.split(':', limit = 2)
            val feature = Feature.fromRaw(parts[0]) ?: return null
            val rest = if (parts.size == 2 && parts[1].isNotEmpty()) parts[1] else null
            return when (feature) {
                Feature.Conversation, Feature.Safety, Feature.Request -> {
                    if (rest == null) return null
                    val pieces = rest.split('/', limit = 2)
                    LaunchRoute(feature, target = pieces[0], state = pieces.getOrNull(1))
                }
                Feature.NewChat -> LaunchRoute(feature, target = rest ?: "")
                Feature.Onboarding, Feature.Lock, Feature.Chats, Feature.Requests,
                Feature.Settings, Feature.Backup, Feature.Invite,
                -> LaunchRoute(feature, state = rest)
            }
        }
    }
}

/**
 * The launch configuration (the iOS `AppConfiguration`). Debug builds read it from the intent
 * extras that mirror the iOS launch arguments, with the same names and values; release builds
 * always use [Release]: the live service, the production relay, the wall clock and the saved
 * preferences only.
 */
data class LaunchConfig(
    /** `U2Start`, else the route's implied start; the lock route always starts locked. */
    val start: StartScreen = StartScreen.Welcome,
    /** `U2Theme`: `system`, `dark` or `light`; `null` keeps the saved preference. */
    val theme: String? = null,
    /** `U2Language`: `system`, `tr` or `en`; `null` keeps the saved preference. */
    val language: String? = null,
    /** `U2FrozenClock`: time frozen at the design's moment (8 Oct 2026 18:52). */
    val frozenClock: Boolean = false,
    /** `U2GhostUrgent`: the “Ghost — last hour” variant. */
    val ghostUrgent: Boolean = false,
    /** `U2Route`, parsed. */
    val launchRoute: LaunchRoute? = null,
    /** `U2Gallery YES`: the design-system gallery. */
    val showsGallery: Boolean = false,
    /** `U2Gallery stickers`: every built-in sticker, both languages and themes. */
    val showsStickerGallery: Boolean = false,
    /** `U2Gallery avatars`: every preset avatar, a photo and the picker sheet. */
    val showsAvatarGallery: Boolean = false,
    /** `U2ResetPreferences`: forgets the saved theme, language and save notice. */
    val resetPreferences: Boolean = false,
    /** `U2Fixtures`: the design's data instead of the live service. */
    val usesFixtures: Boolean = false,
    /** `U2RelayURL`: a local relay instead of the production one; validated by the live service. */
    val relayUrl: String? = null,
    /** `U2OpenURL`: any URL handed to the invite link inbox. */
    val openUrl: String? = null,
    /** `U2PickBackup`: 7.3 “picks” a stand-in backup instead of showing the system picker. */
    val pickBackup: Boolean = false,
    /** `U2ChatsDemo <action>`. */
    val chatsDemo: String? = null,
    /** `U2ConversationDemo <action>`. */
    val conversationDemo: String? = null,
    /** `U2ConversationDemoDelay <seconds>`. */
    val conversationDemoDelay: Double? = null,
    /** `U2LockDemo forgot` · `unlock`. */
    val lockDemo: String? = null,
    /** `U2StressChats <n>`. */
    val stressChats: Int? = null,
    /** `U2StressTicks`. */
    val stressTicks: Boolean = false,
    /** `U2StressAutoScroll <cycles>`. */
    val stressAutoScroll: Int? = null,
    /** `U2FrameProbe`. */
    val frameProbe: Boolean = false,
    /** `U2FrameProbeLog`. */
    val frameProbeLog: Boolean = false,
    /** `U2ReportMail address`: “Bildir” › “E-postayla gönder” shows the support address to copy, whatever the phone has. */
    val reportMail: String? = null,
    /** Android only, `U2DesignInsets`: safe-area insets fixed at the design's 62 / 34. */
    val designInsets: Boolean = false,
    /** Android only, `U2AllowCapture`: lifts FLAG_SECURE so the screen can be captured. */
    val allowCapture: Boolean = false,
) {
    /** The main-stack screens the route opens (pushed without animation at start, or after the first unlock). */
    val routePath: List<AppRoute> get() = launchRoute?.path.orEmpty()

    companion object {
        val Release = LaunchConfig()

        /**
         * Builds the configuration from launch arguments. [value] returns an argument as text
         * (`YES`, `true`, `stickers`, a route …) or `null` when it is absent.
         */
        fun from(value: (String) -> String?): LaunchConfig {
            fun flag(key: String): Boolean = when (value(key)?.trim()?.lowercase()) {
                "yes", "true", "1" -> true
                else -> false
            }
            fun text(key: String): String? = value(key)?.takeIf { it.isNotEmpty() }

            val route = text("U2Route")?.let(LaunchRoute::parse)
            var start = text("U2Start")?.let(StartScreen::fromRaw) ?: route?.impliedStart ?: StartScreen.Welcome
            // The lock harness always starts locked (its states live in the lock).
            if (route?.feature == LaunchRoute.Feature.Lock) start = StartScreen.Locked
            val gallery = text("U2Gallery")
            return LaunchConfig(
                start = start,
                theme = text("U2Theme")?.takeIf { it in setOf("system", "dark", "light") },
                language = text("U2Language")?.takeIf { it in setOf("system", "tr", "en") },
                frozenClock = flag("U2FrozenClock"),
                ghostUrgent = flag("U2GhostUrgent"),
                launchRoute = route,
                showsGallery = flag("U2Gallery"),
                showsStickerGallery = gallery == "stickers",
                showsAvatarGallery = gallery == "avatars",
                resetPreferences = flag("U2ResetPreferences"),
                usesFixtures = flag("U2Fixtures"),
                relayUrl = text("U2RelayURL"),
                openUrl = text("U2OpenURL"),
                pickBackup = flag("U2PickBackup"),
                chatsDemo = text("U2ChatsDemo"),
                conversationDemo = text("U2ConversationDemo"),
                conversationDemoDelay = text("U2ConversationDemoDelay")?.toDoubleOrNull(),
                lockDemo = text("U2LockDemo"),
                stressChats = text("U2StressChats")?.toIntOrNull(),
                stressTicks = flag("U2StressTicks"),
                stressAutoScroll = text("U2StressAutoScroll")?.toIntOrNull(),
                frameProbe = flag("U2FrameProbe"),
                frameProbeLog = flag("U2FrameProbeLog"),
                reportMail = text("U2ReportMail"),
                designInsets = flag("U2DesignInsets"),
                allowCapture = flag("U2AllowCapture"),
            )
        }

        /** Every argument [from] reads, so a launcher can copy them all. */
        val argumentNames: List<String> = listOf(
            "U2Route", "U2Start", "U2Theme", "U2Language", "U2FrozenClock", "U2GhostUrgent",
            "U2Gallery", "U2ResetPreferences", "U2Fixtures", "U2RelayURL", "U2OpenURL", "U2PickBackup",
            "U2ChatsDemo", "U2ConversationDemo", "U2ConversationDemoDelay", "U2LockDemo",
            "U2StressChats", "U2StressTicks", "U2StressAutoScroll", "U2FrameProbe", "U2FrameProbeLog",
            "U2ReportMail", "U2DesignInsets", "U2AllowCapture",
        )
    }
}
