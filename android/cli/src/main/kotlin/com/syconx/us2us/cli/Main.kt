package com.syconx.us2us.cli

import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

/**
 * `us2us-bot`: registers a fresh account, writes `{accountId, username}` to the state file and runs
 * an [EchoBot] until killed. Keys live in memory only, so every run is a new account.
 */
fun main(args: Array<String>) {
    val options = try {
        Options.parse(args, System.getenv("RELAY_URL"))
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        System.err.println(Options.USAGE)
        exitProcess(2)
    }
    runBlocking {
        val messenger = try {
            Messenger.register(options.username, RelayClient(options.relayUrl))
        } catch (e: IOException) {
            // RelayException included: e.g. username_taken, rate_limited, or an unreachable relay.
            log("registration failed: ${e.message}")
            exitProcess(1)
        }
        writeState(options.stateFile, BotState(messenger.accountId.toString(), options.username.value))
        log("registered ${messenger.accountId} as ${options.username}")
        EchoBot(messenger, ::log).run()
    }
}

internal class Options(val relayUrl: String, val username: Username, val stateFile: File) {
    companion object {
        const val USAGE = "usage: us2us-bot --username <name> --state <file> [--relay <url>]   (the relay defaults to \$RELAY_URL)"
        private val KNOWN = setOf("--relay", "--username", "--state")

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
            val rawUsername = values["--username"] ?: throw IllegalArgumentException("--username is required")
            val username = Username.parse(rawUsername)
                ?: throw IllegalArgumentException("invalid username: 3–24 characters a-z, 0-9 or _, starting with a letter")
            val state = values["--state"] ?: throw IllegalArgumentException("--state is required")
            return Options(relay, username, File(state))
        }
    }
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
