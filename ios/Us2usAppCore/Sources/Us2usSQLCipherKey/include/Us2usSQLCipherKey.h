// us2us — AGPL-3.0-only
//
// Exposes SQLCipher's keying function to Swift. SQLCipher declares
// sqlite3_key() only when SQLITE_HAS_CODEC is defined, and a Swift target
// cannot define a C macro for a header it imports, so this target compiles
// with SQLITE_HAS_CODEC and forwards the call. The header itself does not
// include SQLCipher, so importing it never changes how SQLCipher is seen.

#ifndef US2US_SQLCIPHER_KEY_H
#define US2US_SQLCIPHER_KEY_H

struct sqlite3;

/// Keys a freshly opened SQLCipher connection. `key` is passed to
/// sqlite3_key() unchanged (for a raw key: the ASCII text x'<64 hex digits>').
/// Returns an SQLite result code.
int us2us_sqlcipher_key(struct sqlite3 *db, const void *key, int length);

#endif
