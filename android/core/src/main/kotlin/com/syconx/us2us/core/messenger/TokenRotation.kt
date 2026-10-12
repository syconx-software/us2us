package com.syconx.us2us.core.messenger

import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.crypto.Random
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayException
import java.io.IOException

/** How one rotation ended (PROTOCOL.md §5.5 step 3). */
internal sealed interface TokenRotationOutcome {
    /** The relay holds [token]: the new one (`204`), or the one an older relay chose itself. */
    class Committed(val token: AuthToken) : TokenRotationOutcome

    /** `400` for the old token: nothing changed, the old token still works. */
    data object Unchanged : TokenRotationOutcome

    /** `401` for both tokens. */
    data object BackupNoLongerValid : TokenRotationOutcome
}

/**
 * The client side of the token rotation of §4.3 and §5.5 step 3. The new token `N` is chosen here
 * and stored with the backup's token `T` before the relay hears of it; the answers then decide
 * which of the two the account has. Whatever answer gets lost, one of them works.
 */
internal object TokenRotation {
    /** §4.3: the relay refuses a token with fewer different byte values. */
    const val MINIMUM_DISTINCT_BYTES = 16

    /**
     * A fresh token: 32 bytes from the system's CSPRNG. A draw with fewer than 16 different byte
     * values (the relay's "plainly not random") is drawn again; for 32 random bytes that
     * practically never happens.
     */
    fun newToken(): AuthToken {
        var bytes = Random.bytes(AuthToken.LENGTH)
        while (bytes.toSet().size < MINIMUM_DISTINCT_BYTES) bytes = Random.bytes(AuthToken.LENGTH)
        return AuthToken(bytes)
    }

    /**
     * Step 3.2: rotate [old] to [new]; without an answer, step 3.4 at once.
     *
     * @throws IOException the relay error that left the outcome unknown (no answer, `429`, `5xx`, an
     *   unexpected answer). Both tokens must then be kept and the outcome found out later with
     *   [resolve].
     */
    suspend fun rotate(old: AuthToken, new: AuthToken, relay: RelayClient): TokenRotationOutcome =
        when (val answer = ask(relay, old, new)) {
            is Answer.Accepted -> TokenRotationOutcome.Committed(answer.token)
            Answer.Unauthorized -> askWithNewTokenOnceMore(relay, new)
            is Answer.BadRequest -> TokenRotationOutcome.Unchanged
            is Answer.Unknown -> resolve(old, new, relay)
        }

    /**
     * Step 3.4: an earlier rotation from [old] to [new] got no answer (or the app starts with a
     * pending token). Asks with [new], then with [old], then with [new] once more; at most three
     * `401`s.
     *
     * @throws IOException as [rotate], when the outcome is still unknown.
     */
    suspend fun resolve(old: AuthToken, new: AuthToken, relay: RelayClient): TokenRotationOutcome {
        when (val answer = ask(relay, new, new)) {
            is Answer.Accepted -> return TokenRotationOutcome.Committed(answer.token)
            Answer.Unauthorized -> Unit
            is Answer.BadRequest -> throw answer.error
            is Answer.Unknown -> throw answer.error
        }
        return when (val answer = ask(relay, old, new)) {
            is Answer.Accepted -> TokenRotationOutcome.Committed(answer.token)
            Answer.Unauthorized -> askWithNewTokenOnceMore(relay, new)
            is Answer.BadRequest -> TokenRotationOutcome.Unchanged
            is Answer.Unknown -> throw answer.error
        }
    }

    /**
     * `401` for the old token: an earlier attempt may have completed in between, so [new] is asked
     * once more before the backup counts as lost.
     */
    private suspend fun askWithNewTokenOnceMore(relay: RelayClient, new: AuthToken): TokenRotationOutcome =
        when (val answer = ask(relay, new, new)) {
            is Answer.Accepted -> TokenRotationOutcome.Committed(answer.token)
            Answer.Unauthorized -> TokenRotationOutcome.BackupNoLongerValid
            is Answer.BadRequest -> throw answer.error
            is Answer.Unknown -> throw answer.error
        }

    private sealed interface Answer {
        class Accepted(val token: AuthToken) : Answer

        data object Unauthorized : Answer

        /** `400`: nothing changed. */
        class BadRequest(val error: IOException) : Answer

        /** No answer, `429`, `5xx` or anything unexpected. */
        class Unknown(val error: IOException) : Answer
    }

    private suspend fun ask(relay: RelayClient, token: AuthToken, new: AuthToken): Answer = try {
        Answer.Accepted(relay.rotateAuthToken(new, authenticatingWith = token))
    } catch (e: RelayException) {
        when (e.httpStatus) {
            HTTP_UNAUTHORIZED -> Answer.Unauthorized
            HTTP_BAD_REQUEST -> Answer.BadRequest(e)
            else -> Answer.Unknown(e)
        }
    } catch (e: IOException) {
        Answer.Unknown(e)
    }

    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_UNAUTHORIZED = 401
}
