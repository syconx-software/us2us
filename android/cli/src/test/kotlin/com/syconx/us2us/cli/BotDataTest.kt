package com.syconx.us2us.cli

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.PendingSetup
import com.syconx.us2us.core.proto.AccountBackup
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.BackupFile
import com.syconx.us2us.core.vault.BackupPasswordPolicy
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `--data`: the bot's account kept as its own backup across restarts, against [FakeRelay]. */
class BotDataTest {

    @Test
    fun `the first start registers and writes an owner-only backup`(@TempDir tmp: File) = runBlocking {
        FakeRelay().start().use { relay ->
            val dir = File(tmp, "echo")
            val logs = CopyOnWriteArrayList<String>()
            BotData.open(dir, { logs += it }, iterations = ITERATIONS).use { data ->
                val bot = startWithData(data, Username("echo_bot"), RelayClient(relay.url), TestClient.testConfig)

                assertEquals(setOf(BotData.BACKUP_FILE, BotData.PASSWORD_FILE, BotData.LOCK_FILE), dir.list()!!.toSet())
                assertEquals("rwx------", permissions(dir))
                dir.listFiles()!!.forEach { assertEquals("rw-------", permissions(it), it.name) }
                val password = File(dir, BotData.PASSWORD_FILE).readText()
                assertNull(BackupPasswordPolicy.check(password))
                val backup = savedBackup(dir)
                assertEquals(bot.accountId, AccountId.fromBytes(backup.accountId.toByteArray()))
                assertEquals("echo_bot", backup.username)
                assertArrayEquals(bot.account.authToken.toBytes(), backup.authToken.toByteArray())
                assertEquals(1, data.writes)
                assertTrue(logs.none { password in it }, "the password is never logged")
            }
        }
    }

    @Test
    fun `a restarted bot keeps its account and contacts and still answers`(@TempDir tmp: File) = runBlocking {
        FakeRelay().start().use { relay ->
            coroutineScope {
                val dir = File(tmp, "echo")
                val first = BotData.open(dir, iterations = ITERATIONS, backupDelay = 50.milliseconds)
                val bot = startWithData(first, Username("echo_bot"), RelayClient(relay.url), TestClient.testConfig)
                val botJobs = listOf(launch { EchoBot(bot).run() }, launch { first.runBackups() })
                val user = TestClient.register(relay.url, "tester", this)
                user.awaitUpToDate()
                user.messenger.findByUsername("echo_bot")
                user.messenger.sendText(bot.accountId, "merhaba")
                assertEquals("echo: merhaba", user.nextData().content.data.text.body)
                // The accepted request, then the tester's access key, reach the backup a moment later.
                eventually { savedBackup(dir).contactsList.any { AccountId.fromBytes(it.accountId.toByteArray()) == user.accountId && !it.theirAccessKey.isEmpty } }
                botJobs.forEach { it.cancelAndJoin() }
                first.close()

                val restartedAt = System.currentTimeMillis()
                val second = BotData.open(dir, iterations = ITERATIONS, backupDelay = 50.milliseconds)
                val restored = startWithData(second, null, RelayClient(relay.url), TestClient.testConfig)
                assertEquals(bot.accountId, restored.accountId)
                assertEquals("echo_bot", restored.account.username.value)
                assertEquals(ContactState.ACCEPTED, restored.contact(user.accountId)?.state)
                assertEquals(1, second.writes, "only the rotated token; the setup steps after it are not in the backup")

                val restoredJobs = listOf(launch { EchoBot(restored).run() }, launch { second.runBackups() })
                // A restore brings no sessions: the announced profile starts new ones (§5.5).
                user.next { it.sender == bot.accountId && it.content.control.hasProfile() && it.content.control.profile.updatedAt >= restartedAt }
                user.messenger.sendText(bot.accountId, "yine ben")
                assertEquals("echo: yine ben", user.nextData().content.data.text.body)
                assertEquals("access-key", relay.lastDeliveryTo(bot.accountId), "the tester still holds the bot's key")
                (restoredJobs + user.job).forEach { it.cancelAndJoin() }
                second.close()
            }
        }
    }

    @Test
    fun `the rotated token is saved before restore returns, so the backup restores again`(@TempDir tmp: File) = runBlocking {
        FakeRelay().start().use { relay ->
            val dir = File(tmp, "echo")
            val registered = BotData.open(dir, iterations = ITERATIONS).use { startWithData(it, Username("echo_bot"), RelayClient(relay.url), TestClient.testConfig) }

            // The key upload after the rotation fails: the new token is on disk all the same.
            relay.failNext("PUT", "/v1/keys")
            val first = BotData.open(dir, iterations = ITERATIONS).use { startWithData(it, Username("echo_bot"), RelayClient(relay.url), TestClient.testConfig) }
            assertTrue(PendingSetup.REPLACE_PRE_KEYS in first.account.pendingSetup)
            assertNotEquals(registered.account.authToken, first.account.authToken)
            assertArrayEquals(first.account.authToken.toBytes(), savedBackup(dir).authToken.toByteArray())
            assertEquals(RelayErrorCode.UNAUTHORIZED, assertThrows<RelayException> { runBlocking { registered.maintainPreKeys() } }.code)

            val second = BotData.open(dir, iterations = ITERATIONS).use { startWithData(it, null, RelayClient(relay.url), TestClient.testConfig) }
            assertEquals(registered.accountId, second.accountId)
            assertArrayEquals(second.account.authToken.toBytes(), savedBackup(dir).authToken.toByteArray())
            assertFalse(second.maintainPreKeys(), "the saved token works and the keys are replaced")
            assertEquals(RelayErrorCode.UNAUTHORIZED, assertThrows<RelayException> { runBlocking { first.maintainPreKeys() } }.code)
        }
    }

    @Test
    fun `a shutdown while the relay creates the account or rotates its token waits until it is saved`(@TempDir tmp: File) = runBlocking {
        FakeRelay().start().use { relay ->
            val dir = File(tmp, "echo")
            // pm2's SIGINT while the request is at the relay: the shutdown hook, then what it leaves on disk.
            fun shutdownDuring(method: String, path: String, onDisk: AtomicReference<AccountBackup>, data: BotData): AtomicReference<Thread> {
                val hook = AtomicReference<Thread>()
                relay.failNext { request ->
                    if (request.method == method && request.url.encodedPath == path && hook.get() == null) {
                        hook.set(
                            thread {
                                data.flush()
                                onDisk.set(savedBackup(dir))
                            },
                        )
                        Thread.sleep(200) // the answer is still on its way
                    }
                    false
                }
                return hook
            }

            val registered = AtomicReference<AccountBackup>()
            val bot = BotData.open(dir, iterations = ITERATIONS).use { data ->
                val hook = shutdownDuring("POST", "/v1/accounts", registered, data)
                startWithData(data, Username("echo_bot"), RelayClient(relay.url), TestClient.testConfig).also { hook.get().join() }
            }
            assertEquals(bot.accountId, AccountId.fromBytes(registered.get().accountId.toByteArray()))

            val rotated = AtomicReference<AccountBackup>()
            val restored = BotData.open(dir, iterations = ITERATIONS).use { data ->
                val hook = shutdownDuring("POST", "/v1/accounts/me/token", rotated, data)
                startWithData(data, null, RelayClient(relay.url), TestClient.testConfig).also { hook.get().join() }
            }
            assertArrayEquals(restored.account.authToken.toBytes(), rotated.get().authToken.toByteArray())
        }
    }

    @Test
    fun `a restore whose answer is lost keeps the backup's token on disk until the rotation is settled`(@TempDir tmp: File) = runBlocking {
        FakeRelay().start().use { relay ->
            val dir = File(tmp, "echo")
            val registered = BotData.open(dir, iterations = ITERATIONS).use { startWithData(it, Username("echo_bot"), RelayClient(relay.url), TestClient.testConfig) }
            val backupToken = registered.account.authToken

            // The rotation is carried out but its answer is lost, and the question with the new token gets none.
            relay.loseNextAnswer("POST", "/v1/accounts/me/token")
            relay.failNext("POST", "/v1/accounts/me/token")
            BotData.open(dir, iterations = ITERATIONS).use { data ->
                val restored = startWithData(data, null, RelayClient(relay.url), TestClient.testConfig)
                val chosen = checkNotNull(restored.account.pendingAuthToken)
                assertEquals(0, data.writes, "nothing new for the file while the outcome is unknown")
                assertArrayEquals(backupToken.toBytes(), savedBackup(dir).authToken.toByteArray())

                // Settled (the receive loop asks before it connects): the relay's token is written at once.
                restored.finishPendingTokenRotation()
                assertEquals(chosen, restored.account.authToken)
                assertEquals(1, data.writes)
                assertArrayEquals(chosen.toBytes(), savedBackup(dir).authToken.toByteArray())
            }
        }
    }

    @Test
    fun `a backup restored elsewhere stops the bot for good`(@TempDir tmp: File) = runBlocking {
        FakeRelay().start().use { relay ->
            val dir = File(tmp, "echo")
            val bot = BotData.open(dir, iterations = ITERATIONS).use { startWithData(it, Username("echo_bot"), RelayClient(relay.url), TestClient.testConfig) }
            relay.rotateTokenElsewhere(bot.accountId)

            val refused = BotData.open(dir, iterations = ITERATIONS).use { data ->
                assertThrows<StartFailure> { runBlocking { startWithData(data, null, RelayClient(relay.url), TestClient.testConfig) } }
            }
            assertEquals(StartFailure.FOR_GOOD, refused.exitCode)
            assertEquals("the relay refused the token in $dir: the account was restored elsewhere or deleted", refused.message)
        }
    }

    @Test
    fun `an account change is saved before the messenger carries on`(@TempDir tmp: File) = runBlocking {
        FakeRelay().start().use { relay ->
            val dir = File(tmp, "echo")
            BotData.open(dir, iterations = ITERATIONS).use { data ->
                val bot = startWithData(data, Username("echo_bot"), RelayClient(relay.url), TestClient.testConfig)
                val stranger = Messenger.register(Username("stranger"), RelayClient(relay.url), config = TestClient.testConfig)
                val oldKey = bot.account.accessKey

                bot.block(stranger.accountId) // rotates the access key
                assertNotEquals(oldKey, bot.account.accessKey)
                val saved = savedBackup(dir)
                assertArrayEquals(bot.account.accessKey.toBytes(), saved.accessKey.toByteArray())
                assertTrue(saved.contactsList.single().blocked)
                assertEquals(2, data.writes)
            }
        }
    }

    @Test
    fun `a backup of another account is refused without touching it`(@TempDir tmp: File) = runBlocking {
        FakeRelay().start().use { relay ->
            val dir = File(tmp, "echo")
            val bot = BotData.open(dir, iterations = ITERATIONS).use { startWithData(it, Username("echo_bot"), RelayClient(relay.url), TestClient.testConfig) }
            val before = File(dir, BotData.BACKUP_FILE).readBytes()

            val refused = BotData.open(dir, iterations = ITERATIONS).use { data ->
                assertThrows<StartFailure> { runBlocking { startWithData(data, Username("other_bot"), RelayClient(relay.url), TestClient.testConfig) } }
            }
            assertEquals(2, refused.exitCode)
            assertEquals("$dir holds the account echo_bot, not other_bot: leave out --username or use another --data directory", refused.message)
            assertArrayEquals(before, File(dir, BotData.BACKUP_FILE).readBytes())
            assertFalse(bot.maintainPreKeys(), "the token was not rotated")
        }
    }

    @Test
    fun `a backup that cannot be used stops the bot for good`(@TempDir tmp: File) = runBlocking {
        FakeRelay().start().use { relay ->
            val dir = File(tmp, "echo")
            BotData.open(dir, iterations = ITERATIONS).use { startWithData(it, Username("echo_bot"), RelayClient(relay.url), TestClient.testConfig) }
            File(dir, "account.brpb12345.tmp").writeText("cut off") // a write that never finished
            File(dir, BotData.PASSWORD_FILE).writeText("başka bir parola 42")

            val wrongPassword = BotData.open(dir, iterations = ITERATIONS).use { data ->
                assertThrows<StartFailure> { runBlocking { startWithData(data, null, RelayClient(relay.url), TestClient.testConfig) } }
            }
            assertEquals(StartFailure.FOR_GOOD, wrongPassword.exitCode)
            assertEquals("the backup in $dir does not open with its ${BotData.PASSWORD_FILE} file", wrongPassword.message)
            assertFalse(File(dir, "account.brpb12345.tmp").exists())

            val taken = BotData.open(File(tmp, "other"), iterations = ITERATIONS).use { data ->
                assertThrows<StartFailure> { runBlocking { startWithData(data, Username("echo_bot"), RelayClient(relay.url), TestClient.testConfig) } }
            }
            assertEquals(StartFailure.FOR_GOOD, taken.exitCode)
            assertEquals("registration failed: the username echo_bot is taken, choose another one with --username", taken.message)
        }
    }

    @Test
    fun `the first start needs a username`(@TempDir tmp: File) = runBlocking {
        val dir = File(tmp, "echo")
        val refused = BotData.open(dir).use { data ->
            assertThrows<StartFailure> { runBlocking { startWithData(data, null, RelayClient("http://127.0.0.1:9"), TestClient.testConfig) } }
        }
        assertEquals(2, refused.exitCode)
        assertEquals("--username is required for the first start: there is no account in $dir yet", refused.message)
    }

    @Test
    fun `the directory is owner-only and used by one bot at a time`(@TempDir tmp: File) {
        val dir = File(tmp, "echo").apply { mkdirs() }
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val password = BotData.open(dir).use {
            assertEquals("rwx------", permissions(dir))
            val error = assertThrows<IOException> { BotData.open(dir, lockWait = Duration.ZERO) }
            assertEquals("another us2us-bot is using $dir", error.message)
            File(dir, BotData.PASSWORD_FILE).readText()
        }
        BotData.open(dir, lockWait = Duration.ZERO).close()
        assertEquals(password, File(dir, BotData.PASSWORD_FILE).readText(), "the password is made once")

        // Without its password a backup can never be opened again; a new account must not replace it.
        File(dir, BotData.BACKUP_FILE).writeBytes(ByteArray(64))
        File(dir, BotData.PASSWORD_FILE).delete()
        val error = assertThrows<IOException> { BotData.open(dir) }
        assertEquals("${File(dir, BotData.PASSWORD_FILE)} is missing, so the backup in $dir cannot be opened", error.message)
    }

    @Test
    fun `--data makes the username and the state file optional`() {
        val options = Options.parse(arrayOf("--data", "/srv/echo"), "http://relay")
        assertEquals(File("/srv/echo"), options.dataDir)
        assertNull(options.username)
        assertNull(options.stateFile)

        val full = Options.parse(arrayOf("--data", "d", "--username", "Echo_Bot", "--state", "s.json"), "http://relay")
        assertEquals("echo_bot", full.username?.value)
        assertEquals(File("s.json"), full.stateFile)

        assertThrows<IllegalArgumentException> { Options.parse(arrayOf("--data", " "), "http://relay") }
        assertThrows<IllegalArgumentException> { Options.parse(arrayOf("--data", "d", "--username", "Kağan"), "http://relay") }
        assertThrows<IllegalArgumentException> { Options.parse(arrayOf("--data", "d"), null) }
    }

    private fun savedBackup(dir: File): AccountBackup =
        AccountBackup.parseFrom(BackupFile.open(File(dir, BotData.BACKUP_FILE).readBytes(), File(dir, BotData.PASSWORD_FILE).readText()))

    private fun permissions(file: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(30.seconds) { while (!condition()) delay(20) }

    private companion object {
        /** The fewest the file format allows: these tests are about what is saved, not the key derivation. */
        const val ITERATIONS = 1_000
    }
}
