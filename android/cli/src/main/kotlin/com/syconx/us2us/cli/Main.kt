package com.syconx.us2us.cli

import com.google.protobuf.InvalidProtocolBufferException
import com.syconx.us2us.core.messenger.InvalidBackupException
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.MessengerConfig
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.username.UsernameRules
import com.syconx.us2us.core.vault.InvalidVaultFileException
import com.syconx.us2us.core.vault.WrongBackupPasswordException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

/**
 * `us2us-bot`: runs an [EchoBot] until killed, and writes `{accountId, username}` to the state file
 * if one is given. Without `--data` it registers a fresh account whose keys live in memory only, so
 * every run is a new account; with `--data <dir>` the account is kept there ([BotData]) and later
 * runs restore it.
 */
fun main(args: Array<String>) {
    val options = try {
        Options.parse(args, System.getenv("RELAY_URL"))
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        System.err.println(Options.USAGE)
        exitProcess(2)
    }
    val data = options.dataDir?.let { dir ->
        try {
            BotData.open(dir, ::log)
        } catch (e: IOException) {
            log("cannot use $dir: ${e.message}")
            exitProcess(1)
        }
    }
    // pm2 stops with SIGINT: contact changes still waiting for their backup are written first, and
    // a registration or restore under way saves its account before the process ends.
    data?.let { Runtime.getRuntime().addShutdownHook(Thread(it::flush)) }
    runBlocking {
        val messenger = try {
            if (data != null) startWithData(data, options.username, RelayClient(options.relayUrl)) else register(options)
        } catch (e: StartFailure) {
            log(e.message)
            exitProcess(e.exitCode)
        }
        options.stateFile?.let { writeState(it, BotState(messenger.accountId.toString(), messenger.account.username.value)) }
        data?.let { launch { it.runBackups() } }
        EchoBot(messenger, ::log).run()
    }
}

private suspend fun register(options: Options): Messenger {
    val username = checkNotNull(options.username) { "checked by Options.parse" }
    val messenger = try {
        Messenger.register(username, RelayClient(options.relayUrl))
    } catch (e: IOException) {
        // RelayException included: e.g. username_taken, rate_limited, or an unreachable relay.
        throw registrationFailed(username, e)
    }
    log("registered ${messenger.accountId} as $username")
    return messenger
}

/**
 * Why the bot does not start, in one line for the log; it then exits with [exitCode]: [FOR_GOOD]
 * when starting again cannot help (pm2 is told to leave it stopped), 1 when it may.
 */
internal class StartFailure(override val message: String, val exitCode: Int = 1) : Exception(message) {
    companion object {
        const val FOR_GOOD = 2
    }
}

/** A taken username stays taken; anything else (rate limits, the network) may pass. */
private fun registrationFailed(username: Username, error: IOException) = StartFailure(
    registrationFailure(username, error),
    if (error is RelayException && error.code == RelayErrorCode.USERNAME_TAKEN) StartFailure.FOR_GOOD else 1,
)

/**
 * Restores the account kept in [data], or registers [username] there on the first start. A backup
 * of another account than [username] is refused before the relay is asked, so its token stays valid.
 *
 * @throws StartFailure if the bot cannot start.
 */
internal suspend fun startWithData(data: BotData, username: Username?, relay: RelayClient, config: MessengerConfig = MessengerConfig()): Messenger {
    val saved = try {
        data.load()
    } catch (_: WrongBackupPasswordException) {
        throw StartFailure("the backup in ${data.dir} does not open with its ${BotData.PASSWORD_FILE} file", StartFailure.FOR_GOOD)
    } catch (e: InvalidVaultFileException) {
        throw StartFailure("the backup in ${data.dir} is unreadable: ${e.message}", StartFailure.FOR_GOOD)
    } catch (e: InvalidProtocolBufferException) {
        throw StartFailure("the backup in ${data.dir} is unreadable: ${e.message}", StartFailure.FOR_GOOD)
    } catch (e: IOException) {
        throw StartFailure("could not read the backup in ${data.dir}: ${e.message}")
    }
    if (saved == null) {
        username ?: throw StartFailure("--username is required for the first start: there is no account in ${data.dir} yet", StartFailure.FOR_GOOD)
        val messenger = try {
            data.register(username, relay, config)
        } catch (e: IOException) {
            throw registrationFailed(username, e)
        }
        log("registered ${messenger.accountId} as $username, kept in ${data.dir}")
        return messenger
    }
    if (username != null && username.value != saved.username) {
        throw StartFailure(
            "${data.dir} holds the account ${saved.username}, not $username: leave out --username or use another --data directory",
            StartFailure.FOR_GOOD,
        )
    }
    val messenger = try {
        data.restore(saved, relay, config)
    } catch (e: RelayException) {
        if (e.code == RelayErrorCode.UNAUTHORIZED) {
            throw StartFailure("the relay refused the token in ${data.dir}: the account was restored elsewhere or deleted", StartFailure.FOR_GOOD)
        }
        throw StartFailure("restore failed: ${e.message}")
    } catch (e: IOException) {
        throw StartFailure("restore failed: ${e.message}")
    } catch (e: InvalidBackupException) {
        throw StartFailure("the backup in ${data.dir} is invalid: ${e.message}", StartFailure.FOR_GOOD)
    }
    log("restored ${messenger.accountId} as ${messenger.account.username} from ${data.dir}")
    return messenger
}

/**
 * Without [dataDir], [username] and [stateFile] are required. With it, the username is needed only
 * for the first start, and the state file is optional.
 */
internal class Options(val relayUrl: String, val username: Username?, val stateFile: File?, val dataDir: File?) {
    companion object {
        const val USAGE = "usage: us2us-bot --username <name> --state <file> [--relay <url>]\n" +
            "       us2us-bot --data <dir> [--username <name>] [--state <file>] [--relay <url>]\n" +
            "The relay defaults to \$RELAY_URL; --data keeps the account across runs."
        private val KNOWN = setOf("--relay", "--username", "--state", "--data")

        fun parse(args: Array<String>, relayFromEnvironment: String?): Options {
            val values = HashMap<String, String>()
            var i = 0
            while (i < args.size) {
                val key = args[i]
                require(key in KNOWN) { "unknown option $key" }
                require(i + 1 < args.size) { "$key needs a value" }
                values[key] = args[i + 1]
                i += 2
            }
            val relay = values["--relay"] ?: relayFromEnvironment?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("--relay or RELAY_URL is required")
            val data = values["--data"]?.also { require(it.isNotBlank()) { "--data needs a directory" } }
            val rawUsername = values["--username"]
            require(rawUsername != null || data != null) { "--username is required" }
            val username = rawUsername?.let { UsernameRules.username(it) ?: throw IllegalArgumentException(invalidUsername(it)) }
            val state = values["--state"]
            require(state != null || data != null) { "--state is required" }
            return Options(relay, username, state?.let(::File), data?.let(::File))
        }

        /** Says what is wrong with the name as typed; it is never changed into another one. */
        private fun invalidUsername(raw: String): String {
            val violations = UsernameRules.violations(UsernameRules.normalize(raw))
            return "invalid username '$raw' (${violations.joinToString { it.hint }}): use 3–24 characters a-z, 0-9 or _, starting with a letter"
        }
    }
}

private val UsernameRules.Violation.hint: String
    get() = when (this) {
        UsernameRules.Violation.TURKISH_CHARACTERS -> "Turkish characters"
        UsernameRules.Violation.CHARACTERS -> "characters other than a-z, 0-9 and _"
        UsernameRules.Violation.FIRST_LETTER -> "does not start with a letter"
        UsernameRules.Violation.LENGTH -> "not 3–24 characters"
    }

/** One line for the log; a taken username is said plainly, everything else as the error reads. */
internal fun registrationFailure(username: Username, error: IOException): String =
    if (error is RelayException && error.code == RelayErrorCode.USERNAME_TAKEN) {
        "registration failed: the username $username is taken, choose another one with --username"
    } else {
        "registration failed: ${error.message}"
    }

@Serializable
internal class BotState(val accountId: String, val username: String)

/** Written atomically, so a test harness polling for the file never reads half of it. */
internal fun writeState(file: File, state: BotState) {
    val target = file.absoluteFile
    target.parentFile.mkdirs()
    val temp = File.createTempFile(target.name, ".tmp", target.parentFile)
    temp.writeText(Json.encodeToString(BotState.serializer(), state))
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

private fun log(line: String) = System.err.println("[us2us-bot] $line")
