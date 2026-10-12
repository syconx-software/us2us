package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.messenger.PendingSetup
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.username.Username
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import java.util.Base64

/**
 * The persisted `LocalAccount` (credentials, username, access key, pending relay setup), inside the
 * encrypted database. The identity key pair and registration id live in `local_identity`, the rest
 * in `local_account`, as on iOS.
 */
class AccountRepository internal constructor(private val tx: StorageTransaction) {
    fun load(): LocalAccount? = load(tx.connection)

    fun save(account: LocalAccount) {
        tx.requireWritable()
        save(tx.connection, account)
        val storage = tx.storage
        tx.afterCommit { storage.rememberAccount(account) }
    }

    /** Removes the account (after `DELETE /v1/accounts/me`). */
    fun delete() {
        tx.requireWritable()
        tx.connection.run("DELETE FROM local_account")
        val storage = tx.storage
        tx.afterCommit { storage.rememberAccount(null) }
    }

    internal companion object {
        fun load(connection: SqlConnection): LocalAccount? {
            val data = connection.queryOne("SELECT account FROM local_account WHERE id = 1")?.blob("account") ?: return null
            val identity = connection.queryOne("SELECT identity_key_pair, registration_id FROM local_identity WHERE id = 1")
                ?: storageFailure(StorageError.Integrity)
            return try {
                LocalAccountCodec.decode(
                    data,
                    IdentityKeyPair(identity.blob("identity_key_pair") ?: storageFailure(StorageError.Integrity)),
                    identity.int("registration_id") ?: storageFailure(StorageError.Integrity),
                )
            } catch (e: JSONException) {
                storageFailure(StorageError.Integrity, e)
            } catch (e: IllegalArgumentException) {
                storageFailure(StorageError.Integrity, e)
            } catch (e: InvalidKeyException) {
                storageFailure(StorageError.Integrity, e)
            }
        }

        fun save(connection: SqlConnection, account: LocalAccount) {
            val existing = load(connection)
            if (existing != null && existing.accountId != account.accountId) {
                storageFailure(StorageError.InvalidState("a different account is stored"))
            }
            val identity = account.identityKeyPair.serialize()
            val stored = connection.queryOne("SELECT identity_key_pair, registration_id FROM local_identity WHERE id = 1")
            if (stored == null) {
                connection.run(
                    "INSERT INTO local_identity (id, identity_key_pair, registration_id) VALUES (1, ?, ?)",
                    identity,
                    account.registrationId,
                )
            } else if (!stored.blob("identity_key_pair").contentEquals(identity) ||
                stored.int("registration_id") != account.registrationId
            ) {
                storageFailure(StorageError.InvalidState("an identity already exists"))
            }
            connection.run("INSERT OR REPLACE INTO local_account (id, account) VALUES (1, ?)", LocalAccountCodec.encode(account))
        }
    }
}

/** `LocalAccount` without its identity, as JSON (secrets included: it lives only inside the encrypted database). */
internal object LocalAccountCodec {
    private const val VERSION = 1

    fun encode(account: LocalAccount): ByteArray {
        val json = JSONObject()
            .put("version", VERSION)
            .put("accountId", account.accountId.toString())
            .put("authToken", base64(account.authToken.toBytes()))
            .put("username", account.username.value)
            .put("accessKey", base64(account.accessKey.toBytes()))
            .put("displayName", account.displayName)
            .put("presenceSharing", account.presenceSharing)
            .put("profilePicture", account.profilePicture?.let { base64(it.toProto().toByteArray()) } ?: JSONObject.NULL)
            .put("showPictureInSearch", account.showPictureInSearch)
            .put("avatarVersion", account.avatarVersion)
            .put("pendingSetup", JSONArray(account.pendingSetup.map { it.name }.sorted()))
        // Only while a token rotation's outcome is unknown (§5.5 step 3.4); absent otherwise.
        account.pendingAuthToken?.let { json.put("pendingAuthToken", base64(it.toBytes())) }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(data: ByteArray, identity: IdentityKeyPair, registrationId: Int): LocalAccount {
        val json = JSONObject(String(data, Charsets.UTF_8))
        require(json.getInt("version") == VERSION) { "unknown account format" }
        val pending = json.getJSONArray("pendingSetup")
        return LocalAccount(
            accountId = AccountId.parse(json.getString("accountId")),
            authToken = AuthToken(unbase64(json.getString("authToken"))),
            username = Username(json.getString("username")),
            identityKeyPair = identity,
            registrationId = registrationId,
            accessKey = AccessKey(unbase64(json.getString("accessKey"))),
            displayName = json.getString("displayName"),
            presenceSharing = json.getBoolean("presenceSharing"),
            profilePicture = if (json.isNull("profilePicture")) null else ProfilePicture.parse(unbase64(json.getString("profilePicture"))),
            showPictureInSearch = json.getBoolean("showPictureInSearch"),
            avatarVersion = json.getLong("avatarVersion"),
            pendingSetup = (0 until pending.length()).mapTo(LinkedHashSet()) { PendingSetup.valueOf(pending.getString(it)) },
            pendingAuthToken = if (json.isNull("pendingAuthToken")) null else AuthToken(unbase64(json.getString("pendingAuthToken"))),
        )
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unbase64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
