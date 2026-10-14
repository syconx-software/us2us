package com.syconx.us2us.runtime.vault

import com.syconx.us2us.core.encoding.Hex
import com.syconx.us2us.core.vault.BootClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** A Keystore stand-in: wraps by remembering, unwraps by script. */
class FakeBiometrics : BiometricKeyProtector {
    @Volatile var availabilityValue = BiometricAvailability(BiometryKind.FINGERPRINT, enrolled = true)
    private val keys = mutableMapOf<String, ByteArray>()
    private val script = ArrayDeque<BiometricError?>()
    private var invalidated = false
    val prompts = mutableListOf<Pair<String, String>>()
    var deletions = 0
        private set

    /** Held open until completed: the prompt is up. */
    @Volatile var gate: CompletableDeferred<Unit>? = null

    /** The next unwrap outcomes: `null` = success. */
    @Synchronized
    fun script(vararg outcomes: BiometricError?) {
        script.addAll(outcomes)
    }

    val hasKey: Boolean @Synchronized get() = keys.isNotEmpty()

    /** Faces or fingers changed: the Keystore invalidates the key. */
    @Synchronized
    fun changeEnrollment() {
        invalidated = true
    }

    override fun availability() = availabilityValue

    @Synchronized
    override fun wrap(secret: ByteArray): ByteArray {
        val token = ByteArray(16).also(SecureRandom()::nextBytes)
        keys.clear()
        keys[Hex.encode(token)] = secret.copyOf()
        invalidated = false
        return token
    }

    @Synchronized
    override fun isKeyValid(wrapped: ByteArray): Boolean = !invalidated && keys.containsKey(Hex.encode(wrapped))

    override suspend fun unwrap(wrapped: ByteArray, reason: String, cancelTitle: String): ByteArray {
        synchronized(this) { prompts += reason to cancelTitle }
        gate?.await()
        synchronized(this) {
            if (script.isNotEmpty()) script.removeFirst()?.let { throw BiometricException(it) }
            if (invalidated) throw BiometricException(BiometricError.INVALIDATED)
            return keys[Hex.encode(wrapped)]?.copyOf() ?: throw BiometricException(BiometricError.INVALIDATED)
        }
    }

    @Synchronized
    override fun deleteKey() {
        keys.clear()
        deletions += 1
    }
}

/** A wall clock the test moves. */
class ManualClock(private var now: Instant = Instant.parse("2026-10-08T18:52:00Z")) : Clock() {
    @Synchronized
    fun advance(by: Duration) {
        now = now.plus(by.toJavaDuration())
    }

    @Synchronized
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

/** Time since boot the test moves (the vault measures delays with it). */
class ManualBootClock(private var reading: Duration = 1000.seconds) : BootClock {
    @Synchronized
    fun advance(by: Duration) {
        reading += by
    }

    /** The phone restarted [since] ago. */
    @Synchronized
    fun reboot(since: Duration = Duration.ZERO) {
        reading = since
    }

    @Synchronized
    override fun sinceBoot(): Duration = reading
}

class VaultFixture(root: File) {
    val directories = AppDirectories(File(root, "us2us")).also { it.prepare() }
    val items = InMemorySecureItemStore()
    val biometrics = FakeBiometrics()
    val clock = ManualClock()
    val bootClock = ManualBootClock()
    val vault = reopened()

    /** Another controller on the same files, items and biometrics: the app started again. */
    fun reopened() = VaultController(
        directories = directories,
        secureItems = items,
        biometrics = biometrics,
        clock = clock,
        configuration = VaultController.Configuration(iterations = 1000),
        bootClock = bootClock,
        dispatcher = Dispatchers.Unconfined,
    )

    val vaultFile: File get() = File(directories.vault, "vault.brpv")

    fun pass(duration: Duration) {
        clock.advance(duration)
        bootClock.advance(duration)
    }
}

const val CODE = "482917"
const val OTHER_CODE = "730415"
