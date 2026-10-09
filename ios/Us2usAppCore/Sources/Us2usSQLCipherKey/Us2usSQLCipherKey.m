// us2us — AGPL-3.0-only
//
// Objective-C only because SQLCipher's umbrella header imports Foundation;
// the code is plain C.

#import "Us2usSQLCipherKey.h"
#import <SQLCipher/sqlite3.h>

int us2us_sqlcipher_key(struct sqlite3 *db, const void *key, int length) {
    return sqlite3_key(db, key, length);
}
