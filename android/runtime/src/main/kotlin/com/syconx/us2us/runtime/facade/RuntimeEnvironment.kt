package com.syconx.us2us.runtime.facade

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.username.UsernameError
import com.syconx.us2us.core.vault.BootClock
import com.syconx.us2us.runtime.engine.MessagingEngine
import com.syconx.us2us.runtime.net.Backoff
import com.syconx.us2us.runtime.net.NetworkPathWatcher
import com.syconx.us2us.runtime.storage.LocalStorage
import com.syconx.us2us.runtime.storage.StorageLayout
import com.syconx.us2us.runtime.support.RuntimeClock
import com.syconx.us2us.runtime.support.SystemRuntimeClock
import com.syconx.us2us.runtime.vault.AndroidBootClock
import com.syconx.us2us.runtime.vault.AppDirectories
import com.syconx.us2us.runtime.vault.BiometricKeyProtector
import com.syconx.us2us.runtime.vault.KeystoreItemStore
import com.syconx.us2us.runtime.vault.SecretKeyMaterial
import com.syconx.us2us.runtime.vault.SecureItemStore
import com.syconx.us2us.runtime.vault.VaultController
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Reports whether a usable network exists, at once and on every change. */
fun interface NetworkWatching {
    fun start(onChange: (Boolean) -> Unit)
}

/** Opens (creating on first use) the encrypted storage with the vault's DEK. */
fun interface StorageOpener {
    fun open(layout: StorageLayout, key: SecretKeyMaterial, now: Instant): LocalStorage.Opened
}

/** Everything [AppRuntime] depends on that is not tied to the main thread. */
data class RuntimeEnvironment(
    val directories: AppDirectories,
    val secureItems: SecureItemStore,
    val biometrics: BiometricKeyProtector,
    val backends: BackendFactory,
    val clock: RuntimeClock = SystemRuntimeClock,
    val bootClock: BootClock = AndroidBootClock,
    val vault: VaultController.Configuration = VaultController.Configuration(),
    val engine: MessagingEngine.Configuration = MessagingEngine.Configuration(),
    val reconnectBackoff: Backoff = Backoff(),
    /** §5.4: the connection stays this long after the app leaves the foreground. */
    val gracePeriod: Duration = 30.seconds,
    /** Watches the network to reconnect at once when it returns; `null` to never watch. */
    val network: NetworkWatching? = null,
    /** Schedules the locked-path wipe of overdue ghost conversations (§5.3); `null` to schedule nothing. */
    val wipeScheduler: BackgroundWipeScheduler? = null,
    /** SQLCipher with the keys of §5.3 (tests swap the SQL binding). */
    val storage: StorageOpener = StorageOpener { layout, key, now -> LocalStorage.open(layout, key, now) },
) {
    val storageLayout: StorageLayout get() = StorageLayout.standard(directories)

    /** The wall clock of [clock] for the APIs that take a `java.time.Clock`. */
    internal val javaClock: Clock get() = RuntimeJavaClock(clock)

    companion object {
        /**
         * The app's environment: files under `noBackupFilesDir`, Keystore-sealed items, biometric
         * quick unlock through [biometrics], the relay at [relayUrl] (https, or http to the loopback
         * for a local relay), the network watcher and the exact wipe alarm. The app's manifest
         * declares `ACCESS_NETWORK_STATE`.
         */
        @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
        fun production(context: Context, relayUrl: String, biometrics: BiometricKeyProtector): RuntimeEnvironment {
            val application = context.applicationContext ?: context
            val watcher = NetworkPathWatcher(application)
            return RuntimeEnvironment(
                directories = AppDirectories.standard(application),
                secureItems = KeystoreItemStore.standard(application),
                biometrics = biometrics,
                backends = RelayBackendFactory(relayUrl),
                network = NetworkWatching { onChange -> watcher.start(onChange) },
                wipeScheduler = AlarmWipeScheduler(application),
            )
        }
    }
}

private class RuntimeJavaClock(private val clock: RuntimeClock) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = clock.now()
}

/** Where the vault and the UI stand. */
enum class LockState {
    /** No vault on this phone: onboarding (create an account or restore a backup). */
    NEEDS_SETUP,

    /** The lock screen: the code or biometrics are required. Shown at every launch and after every return from the background. */
    LOCKED,

    /** An unlock, registration or restore is running. */
    WORKING,
    UNLOCKED,
}

/** An in-app banner for a message in another conversation (no push exists). It names the contact only; the app shows the generic "New message". */
data class MessageBanner(
    val id: UUID,
    val peer: AccountId,
    /** The contact's name. */
    val title: String,
)

/** Why the runtime refused an action. */
sealed interface RuntimeError {
    /** The action needs an unlocked vault. */
    data object Locked : RuntimeError

    /** A vault already exists (onboarding is over). */
    data object AlreadySetUp : RuntimeError

    /** The username is invalid. */
    data class InvalidUsername(val error: UsernameError) : RuntimeError

    /** The backup file could not be read. */
    data object UnreadableBackup : RuntimeError

    /** The relay revoked this installation's token (restored on another phone). */
    data object Revoked : RuntimeError

    /** The relay refuses the backup's token: restored on another phone since, or its account deleted. */
    data object BackupRevoked : RuntimeError

    /** The backup password and its confirmation differ. */
    data object BackupPasswordMismatch : RuntimeError
}

class RuntimeException(val error: RuntimeError, cause: Throwable? = null) : Exception("runtime: $error", cause)

internal fun runtimeFailure(error: RuntimeError, cause: Throwable? = null): Nothing = throw RuntimeException(error, cause)
