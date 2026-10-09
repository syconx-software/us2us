package com.syconx.us2us.core.vault

import com.syconx.us2us.core.text.UnicodeText
import java.text.Normalizer

/**
 * Why a proposed backup password is refused, in the order of PROTOCOL.md §5.5 (the first that
 * applies is reported). The app shows a localized explanation for each.
 */
enum class BackupPasswordProblem(
    /** The rule's name in PROTOCOL.md §5.5 and `backup-password.json`. */
    val specName: String,
) {
    /** Fewer than [BackupPasswordPolicy.MIN_LENGTH] code points after trimming and NFKC. */
    TOO_SHORT("too_short"),

    /** On the bundled list of common passwords (`spec/common-backup-passwords.txt`). */
    COMMON("common"),

    /** One code point repeated, ignoring case, e.g. `AAAAAaaaaa`. */
    REPEATED_CHARACTER("repeated"),

    /** Code points that step by +1 or by −1, or a keyboard-row run, e.g. `abcdefghij`, `poiuytrewq`. */
    SEQUENCE("sequence"),
}

/** A new backup password breaks a rule of PROTOCOL.md §5.5. */
class InvalidBackupPasswordException(val problem: BackupPasswordProblem) :
    Exception("backup password refused: ${problem.specName}")

/**
 * The backup-password rules of PROTOCOL.md §5.5. The account backup file is encrypted with this
 * separate password, never with the six-digit secret code: a backup file can be attacked offline,
 * without any device secret, so the password must be long. With at least 10 characters it can
 * never be the secret code. The export UI asks for it twice; opening a backup never checks the rules.
 */
object BackupPasswordPolicy {
    const val MIN_LENGTH = 10

    /**
     * Keyboard rows checked for runs, as typed on the US and Turkish Q and F layouts (the same
     * list as ios/Us2usKit, so both apps refuse the same passwords).
     */
    private val KEYBOARD_ROWS = listOf(
        "1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm",
        "qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç",
        "fgğıodrnhpqw", "uieaütkmlyşx", "jövcçzsb",
    ).map { it.codePoints().toArray() }

    private val commonPasswords: Set<String> by lazy {
        BundledList.load("common-backup-passwords.txt").map(::comparisonForm).toSet()
    }

    /**
     * Returns the first problem with [password], or `null` if it is acceptable. The checks run on
     * `p = NFKC(trim(password))` (trimming Unicode `White_Space`) and, except for the length, on
     * `lower(p)`, so every comparison is case-insensitive. The key derivation never sees this
     * form: it uses the password as entered (§5.5).
     */
    fun check(password: String): BackupPasswordProblem? {
        val normalized = Normalizer.normalize(UnicodeText.trimWhitespace(password), Normalizer.Form.NFKC)
        if (normalized.codePointCount(0, normalized.length) < MIN_LENGTH) return BackupPasswordProblem.TOO_SHORT
        val lowered = UnicodeText.lowercase(normalized)
        if (lowered in commonPasswords) return BackupPasswordProblem.COMMON
        val codePoints = lowered.codePoints().toArray()
        if (codePoints.all { it == codePoints[0] }) return BackupPasswordProblem.REPEATED_CHARACTER
        if (isSequence(codePoints)) return BackupPasswordProblem.SEQUENCE
        return null
    }

    /** Throws [InvalidBackupPasswordException] if [password] breaks a rule. */
    fun requireValid(password: String) {
        check(password)?.let { throw InvalidBackupPasswordException(it) }
    }

    /** `UTF-8(NFKC(password))`. Not trimmed: the password is used exactly as entered (§5.5). */
    internal fun keyMaterial(password: String): ByteArray =
        Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)

    internal fun comparisonForm(entry: String) = UnicodeText.lowercase(Normalizer.normalize(entry, Normalizer.Form.NFKC))

    /** The bundled list, NFKC-lowercased, for tests. */
    internal fun commonPasswords(): Set<String> = commonPasswords

    /**
     * A simple sequence (§5.5): every step between consecutive code points is +1, or every step is
     * −1; or the password is a contiguous run, read in either direction, of one keyboard row.
     */
    private fun isSequence(cps: IntArray): Boolean {
        val steps = (1 until cps.size).map { cps[it] - cps[it - 1] }
        if (steps.all { it == 1 } || steps.all { it == -1 }) return true
        val reversed = cps.reversedArray()
        return KEYBOARD_ROWS.any { row -> row.containsRun(cps) || row.containsRun(reversed) }
    }

    private fun IntArray.containsRun(run: IntArray): Boolean =
        run.size <= size && (0..size - run.size).any { start -> run.indices.all { this[start + it] == run[it] } }
}
