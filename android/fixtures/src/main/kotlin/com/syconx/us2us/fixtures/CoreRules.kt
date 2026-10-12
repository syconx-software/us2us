package com.syconx.us2us.fixtures

import com.syconx.us2us.core.vault.BackupPasswordPolicy
import com.syconx.us2us.core.vault.BackupPasswordProblem
import com.syconx.us2us.core.vault.SecretCodePolicy
import com.syconx.us2us.core.vault.SecretCodeProblem
import com.syconx.us2us.core.vault.UnlockAttemptPolicy
import com.syconx.us2us.domain.BackupPasswordError
import com.syconx.us2us.domain.SecretCodeError
import com.syconx.us2us.domain.UpcomingDelay
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.core.username.UsernameRules as CoreUsernameRules

/** The core's rules (PROTOCOL.md), answered in the domain's UI-shaped types. */
internal object CoreRules {
    fun secretCodeProblem(code: String): SecretCodeError? = when (SecretCodePolicy.check(code)) {
        null -> null
        SecretCodeProblem.FORMAT -> SecretCodeError.NotSixDigits
        SecretCodeProblem.REPEATED_DIGIT -> SecretCodeError.RepeatedDigit
        SecretCodeProblem.SEQUENCE -> SecretCodeError.Sequence
        SecretCodeProblem.COMMON -> SecretCodeError.TooCommon
    }

    fun isWellFormedCode(code: String): Boolean = SecretCodePolicy.isWellFormed(code)

    fun backupPasswordProblem(password: String): BackupPasswordError? = when (BackupPasswordPolicy.check(password)) {
        null -> null
        BackupPasswordProblem.TOO_SHORT -> BackupPasswordError.TooShort
        BackupPasswordProblem.COMMON -> BackupPasswordError.TooCommon
        BackupPasswordProblem.REPEATED_CHARACTER -> BackupPasswordError.RepeatedCharacter
        BackupPasswordProblem.SEQUENCE -> BackupPasswordError.SimpleSequence
    }

    fun upcomingDelay(failures: Int): UpcomingDelay? =
        UnlockAttemptPolicy.upcomingDelay(failures)?.let { UpcomingDelay(it.attemptsLeft, it.delay) }

    /** The first rule a typed [name] breaks, as the core reports it. */
    fun usernameViolation(name: String): UsernameRules.Violation? = when (CoreUsernameRules.violation(name)) {
        null -> null
        CoreUsernameRules.Violation.TURKISH_CHARACTERS -> UsernameRules.Violation.TurkishCharacters
        CoreUsernameRules.Violation.CHARACTERS -> UsernameRules.Violation.Characters
        CoreUsernameRules.Violation.FIRST_LETTER -> UsernameRules.Violation.FirstLetter
        CoreUsernameRules.Violation.LENGTH -> UsernameRules.Violation.Length
    }

    fun normalizeUsername(text: String): String = CoreUsernameRules.normalize(text)

    fun isValidUsername(name: String): Boolean = CoreUsernameRules.isValid(name)
}
