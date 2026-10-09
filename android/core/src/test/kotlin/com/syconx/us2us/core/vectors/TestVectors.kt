package com.syconx.us2us.core.vectors

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.attachment.AttachmentCipher
import com.syconx.us2us.core.encoding.Hex
import com.syconx.us2us.core.encoding.toHex
import com.syconx.us2us.core.pow.ProofOfWork
import com.syconx.us2us.core.proto.AccountBackup
import com.syconx.us2us.core.proto.SealedContent
import com.syconx.us2us.core.proto.accountBackup
import com.syconx.us2us.core.proto.backupContact
import com.syconx.us2us.core.proto.sealedContent
import com.syconx.us2us.core.safety.SafetyNumber
import com.syconx.us2us.core.session.ContentPadding
import com.syconx.us2us.core.session.InvalidEnvelopeException
import com.syconx.us2us.core.session.SealedEnvelope
import com.syconx.us2us.core.testing.deterministicIdentity
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.BackupFile
import com.syconx.us2us.core.vault.BackupPasswordPolicy
import com.syconx.us2us.core.vault.DataKey
import com.syconx.us2us.core.vault.ItemCipher
import com.syconx.us2us.core.vault.KeySlot
import com.syconx.us2us.core.vault.SecretCodePolicy
import com.syconx.us2us.core.vault.Vault
import com.syconx.us2us.core.vault.WrongBackupPasswordException
import com.syconx.us2us.core.vault.WrongSecretCodeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import java.io.File

/*
 * Cross-implementation test vectors (spec/test-vectors/README.md). The generator writes what this
 * implementation produces; the verifier checks a vector against this implementation, whichever
 * side generated it.
 */

/** A vector file: `{ "version": 1, "generator", "category", "vectors": [...] }`. */
class VectorFile(val generator: String, val category: String, val vectors: List<JsonObject>) {
    fun toJson(): String = PrettyJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("version", 1)
            put("generator", generator)
            put("category", category)
            put("vectors", JsonArray(vectors))
        },
    ) + "\n"

    companion object {
        private val PrettyJson = Json { prettyPrint = true }

        fun specDir(): File = File(checkNotNull(System.getProperty("us2us.specDir")) { "us2us.specDir is not set; run through Gradle" })

        fun directory(generator: String): File = File(specDir(), "test-vectors/$generator")

        fun read(file: File): VectorFile {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            require(root["version"]?.jsonPrimitive?.int == 1) { "${file.name}: unsupported version" }
            return VectorFile(
                generator = root.getValue("generator").jsonPrimitive.content,
                category = root.getValue("category").jsonPrimitive.content,
                vectors = root.getValue("vectors").jsonArray.map { it.jsonObject },
            )
        }
    }
}

private fun JsonObject.hex(key: String): ByteArray = Hex.decode(getValue(key).jsonPrimitive.content)
private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.strOrNull(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
private fun JsonObject.flag(key: String, default: Boolean): Boolean = this[key]?.jsonPrimitive?.boolean ?: default

/** Deterministic filler: byte `i` is `(i * step + offset) mod 256`. */
private fun pattern(size: Int, step: Int, offset: Int) = ByteArray(size) { (it * step + offset).toByte() }

object VectorGenerator {
    /** Every category of the README, in its order. `sealed` is randomized and handled separately. */
    val deterministic: Map<String, () -> List<JsonObject>> = linkedMapOf(
        "username" to ::username,
        "pow" to ::pow,
        "padding" to ::padding,
        "attachment-padding" to ::attachmentPadding,
        "attachment" to ::attachment,
        "content" to ::content,
        "vault" to ::vault,
        "backup" to ::backup,
        "secret-code" to ::secretCode,
        "backup-password" to ::backupPassword,
        "keyslot" to ::keyslot,
        "item" to ::item,
        "fingerprint" to ::fingerprint,
    )

    private fun username() = listOf(
        "alice", "ALICE_01", "  bob_smith  ", "ｃａｒｏｌ１２", "ﬁle_x", "Kelvin", "Kagan_Y", "abc", "a23456789012345678901234",
        "ab", "a234567890123456789012345", "1alice", "_alice", "ali-ce", "ali.ce", "şule", "ıvan", "İstanbul", "",
        // Trimming is by Unicode White_Space: NEL and ideographic space go, U+001C stays (and is invalid).
        "\u0085white_space\u3000", "\u001cnot_space",
    ).map { input ->
        val username = Username.parse(input)
        buildJsonObject {
            put("input", input)
            put("normalized", username?.value)
            put("hash", username?.hash()?.toHex())
        }
    }

    private fun pow(): List<JsonObject> {
        fun vector(token: String, nonce: ULong, difficulty: Int, valid: Boolean) = buildJsonObject {
            put("token", token)
            put("nonce", nonce.toString())
            put("difficulty", difficulty)
            put("valid", valid)
        }
        val vectors = mutableListOf<JsonObject>()
        for (difficulty in listOf(0, 1, 8, 12, 16)) {
            val token = "v1.message.0123abcd-4567-89ef-0123-456789abcdef.1760000600.AAECAwQFBgcICQoLDA0ODw.d$difficulty"
            val nonce = ProofOfWork.solveSequentially(token, difficulty).nonce
            vectors += vector(token, nonce, difficulty, true)
            if (difficulty >= 8) {
                if (!ProofOfWork.isValid(token, nonce + 1u, difficulty)) vectors += vector(token, nonce + 1u, difficulty, false)
                var exact = difficulty
                while (ProofOfWork.isValid(token, nonce, exact + 1)) exact++
                vectors += vector(token, nonce, exact + 1, false)
            }
        }
        // A nonce above 2^63 checks unsigned 64-bit handling and decimal encoding.
        val high = ProofOfWork.solveSequentially("v1.register.-.1760000600.AAAAAAAAAAAAAAAAAAAAAA.high", 8, 18_446_744_073_709_000_000uL)
        vectors += vector(high.token, high.nonce, 8, true)
        return vectors
    }

    private fun padding() = listOf(0, 1, 158, 159, 160, 161, 319, 320, 1000).map { length ->
        val plaintext = pattern(length, 7, 3)
        buildJsonObject {
            put("plaintext", plaintext.toHex())
            put("padded", ContentPadding.pad(plaintext).toHex())
        }
    }

    private fun attachmentPadding() = listOf(0L, 1L, 541L, 542L, 568L, 569L, 1000L, 10_000L, 1_048_576L, 52_428_800L).map { size ->
        buildJsonObject {
            put("size", size)
            put("paddedSize", AttachmentCipher.paddedSize(size))
        }
    }

    private fun attachment() = listOf(0, 1, 541, 600, 5000).map { length ->
        val plaintext = pattern(length, 13, 7)
        val key = pattern(64, 3, 1)
        val iv = pattern(16, 1, 0xa0)
        val (blob, info) = AttachmentCipher.encrypt(plaintext, key, iv)
        buildJsonObject {
            put("plaintext", plaintext.toHex())
            put("key", key.toHex())
            put("iv", iv.toHex())
            put("blob", blob.toHex())
            put("digest", info.digest.toHex())
        }
    }

    private fun content() = ContentCases.all.map { (case, message) ->
        buildJsonObject {
            put("case", case)
            put("protobuf", message.toByteArray().toHex())
        }
    }

    // Codes are six ASCII digits (§5.2); the KDF input is their six bytes.
    private fun vault() = listOf(
        VaultInput("358024", 0x11, 1000),
        VaultInput("071946", 0x21, 1000), // a leading zero
        VaultInput("926471", 0x31, 1000),
        VaultInput("190519", 0x41, 1000),
        VaultInput("739150", 0x51, Vault.ITERATIONS),
    ).map { input ->
        val deviceSecret = pattern(32, 1, input.seed)
        val salt = pattern(16, 3, input.seed + 1)
        val nonce = pattern(12, 5, input.seed + 2)
        val dek = pattern(32, 7, input.seed + 3)
        buildJsonObject {
            put("code", input.code)
            put("deviceSecret", deviceSecret.toHex())
            put("salt", salt.toHex())
            put("iterations", input.iterations)
            put("nonce", nonce.toHex())
            put("dek", dek.toHex())
            put("vault", Vault.create(input.code, deviceSecret, salt, input.iterations, nonce, DataKey(dek)).toHex())
        }
    }

    private class VaultInput(val code: String, val seed: Int, val iterations: Int)

    private fun backup() = listOf(
        VaultInput("yedek parolam: Kağan 2026", 0x61, 1000),
        VaultInput("backup password, slow", 0x71, BackupFile.ITERATIONS),
        // Never trimmed, NFKC-normalized: fullwidth letters fold, U+00A0 and U+3000 become spaces.
        VaultInput("\u00a0ｙｅｄｅｋ parolası\u3000", 0x79, 1000),
    ).map { input ->
        val salt = pattern(16, 3, input.seed)
        val nonce = pattern(12, 5, input.seed + 1)
        val accountBackup = sampleBackup().toByteArray()
        buildJsonObject {
            put("password", input.code)
            put("salt", salt.toHex())
            put("iterations", input.iterations)
            put("nonce", nonce.toHex())
            put("accountBackup", accountBackup.toHex())
            put("file", BackupFile.create(input.code, salt, input.iterations, nonce, accountBackup).toHex())
        }
    }

    private fun sampleBackup(): AccountBackup = accountBackup {
        version = 1
        accountId = ByteArray(16) { 0x21 }.toByteString()
        authToken = ByteArray(32) { 0x22 }.toByteString()
        identityKeyPair = deterministicIdentity(5).serialize().toByteString()
        registrationId = 4242
        username = "kagan_y"
        displayName = "Kağan"
        accessKey = ByteArray(16) { 0x23 }.toByteString()
        contacts += backupContact {
            accountId = ByteArray(16) { 0x24 }.toByteString()
            username = "alice_01"
            displayName = "Alice"
            identityKey = deterministicIdentity(6).publicKey.serialize().toByteString()
            verified = true
            theirAccessKey = ByteArray(16) { 0x25 }.toByteString()
            presenceSharing = true
        }
        // A contact whose new safety number the user has not accepted yet (fields 8 and 9).
        contacts += backupContact {
            accountId = ByteArray(16) { 0x26 }.toByteString()
            username = "bob_02"
            identityKey = deterministicIdentity(7).publicKey.serialize().toByteString()
            identityUnaccepted = true
        }
        createdAt = 1760000000010
    }

    /**
     * PROTOCOL.md §5.2, with the cases the README requires. Each code carries the problem the spec
     * expects, so a regression fails generation instead of being written into the vectors.
     */
    private fun secretCode(): List<JsonObject> {
        val cases = mutableListOf<Pair<String, String?>>(
            // format: never trimmed or normalized; only ASCII digits count.
            "" to "format", "35802" to "format", "3580241" to "format", "35802x" to "format", "\t358024" to "format",
            "358024\u00a0" to "format", "358 24" to "format", "+35802" to "format",
            "３５８０２４" to "format", "٣٥٨٠٢٤" to "format", "۳۵۸۰۲۴" to "format", "३५८०२४" to "format",
        )
        // repeated: all ten.
        cases += (0..9).map { "$it".repeat(6) to "repeated" }
        // sequence: all twenty runs up and down the digit circle, wrapping 9 → 0.
        for (start in 0..9) {
            val up = (0 until 6).joinToString("") { "${(start + it) % 10}" }
            cases += up to "sequence"
            cases += up.reversed() to "sequence"
        }
        // common: every list entry.
        cases += SecretCodePolicy.commonCodes().sorted().map { it to "common" }
        // acceptable: dates, a leading zero, runs broken by one digit, steps of 2, ordinary codes.
        cases += listOf(
            "358024", "071946", "190519", "311299", "200000", "023456", "345670", "765431", "246813", "999990", "100000",
        ).map { it to null }
        return cases.map { (code, expected) ->
            val problem = SecretCodePolicy.check(code)?.specName
            check(problem == expected) { "secret code '$code': expected $expected, got $problem" }
            buildJsonObject {
                put("code", code)
                put("problem", problem)
            }
        }
    }

    /** PROTOCOL.md §5.5, with the cases the README requires. */
    private fun backupPassword(): List<JsonObject> {
        val cases = listOf(
            // too_short: 9 code points, with and without White_Space around them.
            "abc123456" to "too_short", "\u3000abc123456\u0085" to "too_short", " ab12cd345\u00a0" to "too_short",
            "ｓｈｏｒｔ１２３４" to "too_short", "358024" to "too_short",
            // common: list entries in other cases and widths, also inside White_Space.
            "PASSWORD123" to "common", "Galatasaray1905" to "common", "ｑｗｅｒｔｙ１２３４" to "common",
            "  1234567890\t" to "common", "QWERTYUIOP" to "common",
            // repeated: case-insensitive and per code point (a final capital sigma stays σ).
            "AAAAAaaaaa" to "repeated", "zzzzzzzzzzzz" to "repeated", "ΣΣΣΣΣΣΣΣΣΣ" to "repeated", "ççççççÇÇÇÇ" to "repeated",
            // sequence: ±1 steps between code points, also beyond the BMP and after NFKC.
            "abcdefghij" to "sequence", "JIHGFEDCBA" to "sequence", "ΑΒΓΔΕΖΗΘΙΚ" to "sequence",
            "😀😁😂😃😄😅😆😇😈😉" to "sequence", "ａｂｃｄｅｆｇｈｉｊ" to "sequence",
            // sequence: a run of every keyboard row with at least 10 code points, forward and reversed.
            "0987654321" to "sequence", "POIUYTREWQ" to "sequence", "ertyuıopğü" to "sequence", "ğpoıuytrew" to "sequence",
            "sdfghjklşi" to "sequence", "şlkjhgfdsa" to "sequence", "fgğıodrnhp" to "sequence", "qphnrdoığg" to "sequence",
            "uieaütkmly" to "sequence", "şylmktüaei" to "sequence",
            // acceptable: a row run broken by one character, steps of 2, a wrapped row, ordinary passwords.
            "qwertyuiox" to null, "bdfhjlnprt" to null, "pqwertyuio" to null, "Gizli kod 2026" to null,
            "correct horse" to null, "😀😃😄😁😆😅🤣😂😀😃" to null, "Kırmızı elma 7" to null,
            // U+001C is not White_Space, so it stays and counts.
            "\u001cabc123456" to null,
            // İ lowercases to i + U+0307 (default casing, never Turkish), which breaks the row run.
            "SDFGHJKLŞİ" to null,
        )
        return cases.map { (password, expected) ->
            val problem = BackupPasswordPolicy.check(password)?.specName
            check(problem == expected) { "backup password '$password': expected $expected, got $problem" }
            buildJsonObject {
                put("password", password)
                put("problem", problem)
            }
        }
    }

    private fun keyslot() = listOf(0x81, 0x91).map { seed ->
        val dek = pattern(32, 7, seed)
        val slotId = pattern(16, 1, seed + 1)
        val ck = pattern(32, 11, seed + 2)
        val nonce = pattern(12, 5, seed + 3)
        buildJsonObject {
            put("dek", dek.toHex())
            put("slotId", slotId.toHex())
            put("ck", ck.toHex())
            put("nonce", nonce.toHex())
            put("slotFile", KeySlot.create(DataKey(dek), slotId, nonce, ck).toHex())
        }
    }

    private fun item() = listOf(ByteArray(0), "mesaj".toByteArray(), pattern(1000, 17, 9)).mapIndexed { i, plaintext ->
        val ck = pattern(32, 11, 0xa1 + i)
        val itemId = pattern(16, 1, 0xb1 + i)
        val nonce = pattern(12, 5, 0xc1 + i)
        buildJsonObject {
            put("ck", ck.toHex())
            put("itemId", itemId.toHex())
            put("nonce", nonce.toHex())
            put("plaintext", plaintext.toHex())
            put("item", ItemCipher.seal(ck, itemId, nonce, plaintext).toHex())
        }
    }

    private fun fingerprint() = listOf(1 to 2, 3 to 4, 7 to 8).map { (local, remote) ->
        val localId = pattern(16, 1, local * 16)
        val remoteId = pattern(16, 1, remote * 16)
        val localKey = deterministicIdentity(local).publicKey
        val remoteKey = deterministicIdentity(remote).publicKey
        val number = SafetyNumber.compute(AccountId.fromBytes(localId), localKey, AccountId.fromBytes(remoteId), remoteKey)
        buildJsonObject {
            put("localId", localId.toHex())
            put("localKey", localKey.serialize().toHex())
            put("remoteId", remoteId.toHex())
            put("remoteKey", remoteKey.serialize().toHex())
            put("displayable", number.digits)
            put("scannable", number.scannable.toHex())
        }
    }

    /**
     * HPKE draws a fresh encapsulation every time, so sealed vectors cannot be reproduced. The key
     * pair and contents are fixed; only the envelopes vary between generations.
     */
    fun sealed(): List<JsonObject> {
        val recipient = deterministicIdentity(10)
        val cases = listOf(
            sealedContent {
                senderAccountId = pattern(16, 1, 0x30).toByteString()
                senderDeviceId = 1
                type = 3
                ciphertext = pattern(120, 9, 4).toByteString()
                senderUsername = "alice_01"
            },
            sealedContent {
                senderAccountId = pattern(16, 1, 0x40).toByteString()
                senderDeviceId = 1
                type = 2
                ciphertext = pattern(64, 5, 2).toByteString()
            },
        )
        val vectors = cases.map { content ->
            sealedVector(recipient, SealedEnvelope.seal(content, recipient.publicKey), content, valid = true)
        }
        val tampered = Hex.decode(vectors[0].str("envelope")).also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
        return vectors + sealedVector(recipient, tampered, cases[0], valid = false)
    }

    private fun sealedVector(recipient: IdentityKeyPair, envelope: ByteArray, content: SealedContent, valid: Boolean) =
        buildJsonObject {
            put("recipientIdentityKeyPair", recipient.serialize().toHex())
            put("envelope", envelope.toHex())
            put(
                "sealedContent",
                buildJsonObject {
                    put("senderAccountId", content.senderAccountId.toByteArray().toHex())
                    put("senderDeviceId", content.senderDeviceId)
                    put("type", content.type)
                    put("ciphertext", content.ciphertext.toByteArray().toHex())
                    put("senderUsername", content.senderUsername)
                },
            )
            put("valid", valid)
        }
}

/** Checks one vector of any category against this implementation; throws an assertion error on mismatch. */
object VectorVerifier {
    val categories: List<String> = VectorGenerator.deterministic.keys.toList() + "sealed"

    fun verify(category: String, v: JsonObject) {
        when (category) {
            "username" -> {
                val parsed = Username.parse(v.str("input"))
                assertEquals(v.strOrNull("normalized"), parsed?.value, "normalization of '${v.str("input")}'")
                assertEquals(v.strOrNull("hash"), parsed?.hash()?.toHex(), "hash of '${v.str("input")}'")
            }
            "pow" -> assertEquals(
                v.flag("valid", true),
                ProofOfWork.isValid(v.str("token"), v.str("nonce").toULong(), v.int("difficulty")),
                "pow ${v.str("nonce")} @ ${v.int("difficulty")}",
            )
            "padding" -> {
                assertArrayEquals(v.hex("padded"), ContentPadding.pad(v.hex("plaintext")))
                assertArrayEquals(v.hex("plaintext"), ContentPadding.unpad(v.hex("padded")))
            }
            "attachment-padding" -> assertEquals(v.long("paddedSize"), AttachmentCipher.paddedSize(v.long("size")), "P(${v.long("size")})")
            "attachment" -> {
                val plaintext = v.hex("plaintext")
                val (blob, info) = AttachmentCipher.encrypt(plaintext, v.hex("key"), v.hex("iv"))
                assertArrayEquals(v.hex("blob"), blob)
                assertArrayEquals(v.hex("digest"), info.digest)
                assertArrayEquals(plaintext, AttachmentCipher.decrypt(v.hex("blob"), v.hex("key"), v.hex("digest"), plaintext.size.toLong()))
            }
            "content" -> {
                val case = v.str("case")
                val built = checkNotNull(ContentCases.all[case]) { "unknown content case '$case'" }
                assertEquals(v.str("protobuf"), built.toByteArray().toHex(), "bytes of '$case'")
                assertEquals(built, ContentCases.parse(case, v.hex("protobuf")), "parsed '$case'")
            }
            "vault" -> {
                val code = v.str("code")
                assertTrue(SecretCodePolicy.isWellFormed(code), "vault code '$code' is not six ASCII digits")
                val deviceSecret = v.hex("deviceSecret")
                val created = Vault.create(code, deviceSecret, v.hex("salt"), v.int("iterations"), v.hex("nonce"), DataKey(v.hex("dek")))
                assertArrayEquals(v.hex("vault"), created, "vault for '$code'")
                assertArrayEquals(v.hex("dek"), Vault.open(v.hex("vault"), code, deviceSecret).toBytes())
                // Another six-digit code: the last digit changed.
                val other = code.dropLast(1) + if (code.endsWith("0")) "1" else "0"
                assertThrows<WrongSecretCodeException> { Vault.open(v.hex("vault"), other, deviceSecret) }
            }
            "backup" -> {
                val password = v.str("password")
                val created = BackupFile.create(password, v.hex("salt"), v.int("iterations"), v.hex("nonce"), v.hex("accountBackup"))
                assertArrayEquals(v.hex("file"), created, "backup for '$password'")
                val opened = BackupFile.open(v.hex("file"), password)
                assertArrayEquals(v.hex("accountBackup"), opened)
                AccountBackup.parseFrom(opened)
                assertThrows<WrongBackupPasswordException> { BackupFile.open(v.hex("file"), password + "!") }
            }
            "secret-code" -> assertEquals(v.strOrNull("problem"), SecretCodePolicy.check(v.str("code"))?.specName, "problem of '${v.str("code")}'")
            "backup-password" -> assertEquals(
                v.strOrNull("problem"),
                BackupPasswordPolicy.check(v.str("password"))?.specName,
                "problem of '${v.str("password")}'",
            )
            "keyslot" -> {
                val dek = DataKey(v.hex("dek"))
                assertArrayEquals(v.hex("slotFile"), KeySlot.create(dek, v.hex("slotId"), v.hex("nonce"), v.hex("ck")))
                assertArrayEquals(v.hex("ck"), KeySlot.open(dek, v.hex("slotId"), v.hex("slotFile")))
            }
            "item" -> {
                assertArrayEquals(v.hex("item"), ItemCipher.seal(v.hex("ck"), v.hex("itemId"), v.hex("nonce"), v.hex("plaintext")))
                assertArrayEquals(v.hex("plaintext"), ItemCipher.open(v.hex("ck"), v.hex("itemId"), v.hex("item")))
            }
            "fingerprint" -> {
                val number = SafetyNumber.compute(
                    AccountId.fromBytes(v.hex("localId")),
                    IdentityKey(v.hex("localKey")),
                    AccountId.fromBytes(v.hex("remoteId")),
                    IdentityKey(v.hex("remoteKey")),
                )
                assertEquals(v.str("displayable"), number.digits)
                assertArrayEquals(v.hex("scannable"), number.scannable)
            }
            "sealed" -> {
                val recipient = IdentityKeyPair(v.hex("recipientIdentityKeyPair"))
                if (!v.flag("valid", true)) {
                    assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(v.hex("envelope"), recipient) }
                    return
                }
                val expected = v.getValue("sealedContent").jsonObject
                val opened = SealedEnvelope.open(v.hex("envelope"), recipient)
                assertEquals(expected.str("senderAccountId"), opened.senderAccountId.toByteArray().toHex())
                assertEquals(expected.int("senderDeviceId"), opened.senderDeviceId)
                assertEquals(expected.int("type"), opened.type)
                assertEquals(expected.str("ciphertext"), opened.ciphertext.toByteArray().toHex())
                assertEquals(expected.strOrNull("senderUsername").orEmpty(), opened.senderUsername)
            }
            else -> throw AssertionError("unknown vector category '$category'")
        }
    }
}
