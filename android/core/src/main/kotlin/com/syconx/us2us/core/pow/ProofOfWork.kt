package com.syconx.us2us.core.pow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** A solved challenge, submitted as `{"token","nonce"}` or as the `X-PoW` header. */
class PowSolution(val token: String, val nonce: ULong) {
    /** `X-PoW: <token>:<nonce>` (PROTOCOL.md §4.2). */
    val headerValue: String get() = "$token:$nonce"
}

/**
 * Proof-of-work (PROTOCOL.md §4.2): find a 64-bit `nonce` such that
 * `SHA-256(UTF-8(token) ‖ uint64_be(nonce))` has at least `difficulty` leading zero bits.
 *
 * Solving happens on the phone; the relay verifies with a single hash.
 */
object ProofOfWork {
    const val MAX_DIFFICULTY = 256

    /** How many hashes a worker computes between cancellation checks. */
    private const val CHECK_INTERVAL = 4096

    fun leadingZeroBits(hash: ByteArray): Int {
        var bits = 0
        for (b in hash) {
            val v = b.toInt() and 0xff
            if (v == 0) {
                bits += 8
            } else {
                return bits + Integer.numberOfLeadingZeros(v) - 24
            }
        }
        return bits
    }

    fun isValid(token: String, nonce: ULong, difficulty: Int): Boolean {
        require(difficulty in 0..MAX_DIFFICULTY) { "difficulty out of range" }
        return Searcher(token).zeroBits(nonce) >= difficulty
    }

    /**
     * Solves the challenge using [parallelism] workers on [Dispatchers.Default]. Worker `i` tries
     * nonces `i, i + parallelism, i + 2·parallelism, …`, so the work splits without coordination.
     * Cancelling the calling coroutine stops all workers promptly.
     */
    suspend fun solve(
        token: String,
        difficulty: Int,
        parallelism: Int = Runtime.getRuntime().availableProcessors(),
    ): PowSolution {
        require(difficulty in 0..MAX_DIFFICULTY) { "difficulty out of range" }
        require(parallelism >= 1) { "parallelism must be positive" }
        return withContext(Dispatchers.Default) {
            val found = CompletableDeferred<ULong>()
            coroutineScope {
                val workers = List(parallelism) { worker ->
                    launch {
                        val searcher = Searcher(token)
                        val stride = parallelism.toULong()
                        var nonce = worker.toULong()
                        while (!found.isCompleted) {
                            repeat(CHECK_INTERVAL) {
                                if (searcher.zeroBits(nonce) >= difficulty) {
                                    found.complete(nonce)
                                    return@launch
                                }
                                nonce += stride
                            }
                            ensureActive()
                        }
                    }
                }
                found.await().also { workers.forEach { it.cancel() } }
            }.let { PowSolution(token, it) }
        }
    }

    /**
     * Returns the smallest nonce ≥ [start] that solves the challenge, on the calling thread.
     * Deterministic, which makes it the right tool for test vectors; prefer [solve] in apps.
     */
    fun solveSequentially(token: String, difficulty: Int, start: ULong = 0u): PowSolution {
        require(difficulty in 0..MAX_DIFFICULTY) { "difficulty out of range" }
        val searcher = Searcher(token)
        var nonce = start
        while (searcher.zeroBits(nonce) < difficulty) nonce++
        return PowSolution(token, nonce)
    }

    /** Hashes `token ‖ nonce` for many nonces, reusing one digest and one buffer. */
    private class Searcher(token: String) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val tokenBytes = token.toByteArray(Charsets.UTF_8)
        private val input = tokenBytes.copyOf(tokenBytes.size + Long.SIZE_BYTES)
        private val output = ByteArray(digest.digestLength)

        fun zeroBits(nonce: ULong): Int {
            var v = nonce.toLong()
            for (i in input.size - 1 downTo tokenBytes.size) {
                input[i] = v.toByte()
                v = v ushr 8
            }
            digest.update(input)
            digest.digest(output, 0, output.size)
            return leadingZeroBits(output)
        }
    }
}
