package com.syconx.us2us.runtime.vault

import java.io.IOException

/**
 * "Erase everything" (forgot code, the optional wipe after 10 wrong codes, deleting the account),
 * in the order of PROTOCOL.md §5.2/§5.3: first every key, so that what is still on flash while the
 * files are deleted is already unreadable, then the files.
 *
 * 1. The device secret and its Keystore key, the vault file and attempt counter, the biometric
 *    Keystore key and its wrap ([VaultController.destroyKeys]).
 * 2. Every conversation key slot and the ghost schedule (each slot is a conversation's key).
 * 3. Every file of the app, after which the directories are created again, empty.
 *
 * The storage layer closes its database before this runs.
 */
class WipeAll(
    private val vault: VaultController,
    private val directories: AppDirectories,
) {
    /** @throws IOException when files could not be removed; the keys are gone by then. */
    suspend fun run() {
        vault.destroyKeys()
        try {
            KeySlotStore(directories.slots).destroyAll()
        } catch (_: KeySlotException) {
            // The slots go with the files below; without the DEK they open to nothing anyway.
        }
        directories.eraseAll()
        directories.prepare()
        VaultLog.info("local data erased")
    }
}
