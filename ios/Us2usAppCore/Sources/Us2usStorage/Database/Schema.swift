// us2us — AGPL-3.0-only

/// The versioned database schema. Append migrations; never edit a shipped one.
///
/// What is in clear inside the database (which is itself SQLCipher-encrypted
/// under the DEK): ids, timestamps, states and flags the app must query on.
/// Everything a conversation *says* — message bodies (text, captions, quotes,
/// locations, attachment keys), reactions, drafts, pending outgoing content —
/// is additionally an item under the conversation key CK (§5.3), so
/// crypto-shredding a conversation leaves only unreadable ciphertext.
enum Schema {
    static let migrations: [Migration] = [v1, v2, v3, v4, v5, v6]

    static var latestVersion: Int { migrations.map(\.version).max() ?? 0 }

    /// Local identity, account and the libsignal protocol store (§5).
    static let v1 = Migration(
        version: 1,
        statements: [
            """
            CREATE TABLE local_identity (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                identity_key_pair BLOB NOT NULL,
                registration_id INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE local_account (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                account BLOB NOT NULL
            )
            """,
            """
            CREATE TABLE identities (
                account BLOB PRIMARY KEY,
                identity_key BLOB NOT NULL,
                previous_key BLOB,
                approved INTEGER NOT NULL,
                verified INTEGER NOT NULL
            ) WITHOUT ROWID
            """,
            "CREATE TABLE pre_keys (id INTEGER PRIMARY KEY, record BLOB NOT NULL)",
            "CREATE TABLE signed_pre_keys (id INTEGER PRIMARY KEY, record BLOB NOT NULL)",
            """
            CREATE TABLE kyber_pre_keys (
                id INTEGER PRIMARY KEY,
                record BLOB NOT NULL,
                last_resort INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE kyber_last_resort_uses (
                kyber_id INTEGER NOT NULL,
                signed_id INTEGER NOT NULL,
                base_key BLOB NOT NULL,
                PRIMARY KEY (kyber_id, signed_id, base_key)
            ) WITHOUT ROWID
            """,
            "CREATE TABLE sessions (account BLOB PRIMARY KEY, record BLOB NOT NULL) WITHOUT ROWID",
            "CREATE TABLE key_id_counters (space TEXT PRIMARY KEY, next_id INTEGER NOT NULL) WITHOUT ROWID",
        ])

    /// Contacts, conversations, messages, attachments, dedupe, outbox.
    static let v2 = Migration(
        version: 2,
        statements: [
            """
            CREATE TABLE contacts (
                account BLOB PRIMARY KEY,
                request_state TEXT NOT NULL,
                state_before_block TEXT,
                username TEXT,
                username_status TEXT NOT NULL DEFAULT 'unchecked',
                display_name TEXT,
                presence_sharing INTEGER NOT NULL DEFAULT 0,
                avatar BLOB,
                profile_updated_at INTEGER NOT NULL DEFAULT 0,
                their_access_key BLOB,
                our_access_key_sent BLOB,
                profile_sent_at INTEGER,
                pending_forget INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE conversations (
                id BLOB PRIMARY KEY,
                contact BLOB NOT NULL UNIQUE REFERENCES contacts(account) ON DELETE CASCADE,
                slot_id BLOB NOT NULL UNIQUE,
                settings BLOB NOT NULL,
                settings_author BLOB,
                ghost_anchor_at INTEGER,
                last_activity_at INTEGER,
                ghost_deadline_at INTEGER,
                draft BLOB,
                draft_item_id BLOB NOT NULL,
                created_at INTEGER NOT NULL
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY,
                conversation_id BLOB NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                message_id BLOB NOT NULL,
                outgoing INTEGER NOT NULL,
                sender BLOB NOT NULL,
                kind INTEGER NOT NULL,
                body BLOB,
                sent_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                server_timestamp INTEGER,
                status INTEGER NOT NULL,
                accepted_at INTEGER,
                delivered_at INTEGER,
                read_at INTEGER,
                viewed_at INTEGER,
                not_delivered_at INTEGER,
                failed_at INTEGER,
                displayed_at INTEGER,
                expire_timer INTEGER NOT NULL DEFAULT 0,
                expires_at INTEGER,
                view_once INTEGER NOT NULL DEFAULT 0,
                quote_target BLOB,
                live_session BLOB,
                deleted_at INTEGER,
                UNIQUE (conversation_id, message_id)
            )
            """,
            // Pages walk a conversation by row id (insertion order) from the newest.
            "CREATE INDEX messages_by_conversation ON messages(conversation_id, id)",
            "CREATE INDEX messages_by_expiry ON messages(expires_at) WHERE expires_at IS NOT NULL",
            "CREATE INDEX messages_awaiting_delivery ON messages(status, accepted_at) WHERE outgoing = 1",
            "CREATE INDEX messages_unread ON messages(conversation_id, displayed_at) WHERE outgoing = 0",
            """
            CREATE INDEX messages_by_live_session ON messages(conversation_id, live_session)
                WHERE live_session IS NOT NULL
            """,
            """
            CREATE TABLE reactions (
                conversation_id BLOB NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                target_message_id BLOB NOT NULL,
                reactor BLOB NOT NULL,
                reaction_id BLOB NOT NULL,
                emoji BLOB NOT NULL,
                sent_at INTEGER NOT NULL,
                PRIMARY KEY (conversation_id, target_message_id, reactor)
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE pending_deletes (
                conversation_id BLOB NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                message_id BLOB NOT NULL,
                sender BLOB NOT NULL,
                requested_at INTEGER NOT NULL,
                PRIMARY KEY (conversation_id, message_id)
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE attachments (
                id BLOB PRIMARY KEY,
                conversation_id BLOB NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                message_id BLOB NOT NULL,
                descriptor BLOB,
                state INTEGER NOT NULL,
                has_file INTEGER NOT NULL DEFAULT 0,
                thumbnail_id BLOB,
                byte_count INTEGER,
                transferred INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            ) WITHOUT ROWID
            """,
            "CREATE INDEX attachments_by_message ON attachments(conversation_id, message_id)",
            "CREATE INDEX attachments_by_state ON attachments(state)",
            """
            CREATE TABLE processed_envelopes (
                relay_id TEXT PRIMARY KEY,
                processed_at INTEGER NOT NULL
            ) WITHOUT ROWID
            """,
            "CREATE INDEX processed_envelopes_by_time ON processed_envelopes(processed_at)",
            """
            CREATE TABLE outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recipient BLOB NOT NULL,
                conversation_id BLOB REFERENCES conversations(id) ON DELETE CASCADE,
                message_id BLOB,
                payload BLOB NOT NULL,
                payload_id BLOB NOT NULL,
                ephemeral INTEGER NOT NULL DEFAULT 0,
                state INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                last_error TEXT
            )
            """,
            "CREATE INDEX outbox_due ON outbox(state, next_attempt_at)",
            "CREATE INDEX outbox_by_recipient ON outbox(recipient)",
        ])

    /// Privacy review fixes (2026-10-08):
    /// * `removed_messages`: ids of messages removed entirely (expired
    ///   disappearing messages, "delete for me"), so a retransmission of the
    ///   same `message_id` is recognised as a duplicate and does not bring
    ///   the message back. Only the random id and a day; wiped with the
    ///   conversation (cascade) and pruned like the envelope log.
    /// * an index for scrubbing the quote previews of removed messages.
    /// * the envelope log keeps days, not milliseconds: its only use is
    ///   pruning, and exact receive times would outlive a ghost wipe.
    static let v3 = Migration(
        version: 3,
        statements: [
            """
            CREATE TABLE removed_messages (
                conversation_id BLOB NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                message_id BLOB NOT NULL,
                removed_day INTEGER NOT NULL,
                PRIMARY KEY (conversation_id, message_id)
            ) WITHOUT ROWID
            """,
            "CREATE INDEX removed_messages_by_day ON removed_messages(removed_day)",
            """
            CREATE INDEX messages_by_quote ON messages(conversation_id, quote_target)
                WHERE quote_target IS NOT NULL
            """,
            "UPDATE processed_envelopes SET processed_at = processed_at - (processed_at % 86400000)",
        ])

    /// Identity verification review (2026-10-11): when and how the current
    /// key of a contact was verified (`IdentityVerification`, PROTOCOL.md §5),
    /// stored with the key, so a change of the key drops it together with the
    /// verification. Rows verified before keep `NULL` (no date is shown).
    static let v4 = Migration(
        version: 4,
        statements: [
            "ALTER TABLE identities ADD COLUMN verified_at INTEGER",
            "ALTER TABLE identities ADD COLUMN verified_method INTEGER",
            // While a change awaits approval: whether the previous (approved)
            // key was verified, and when and how — a return to it keeps that
            // verification (PROTOCOL §5). NULL reads as not verified.
            "ALTER TABLE identities ADD COLUMN previous_verified INTEGER",
            "ALTER TABLE identities ADD COLUMN previous_verified_at INTEGER",
            "ALTER TABLE identities ADD COLUMN previous_verified_method INTEGER",
        ])

    /// Profile pictures (2026-10-11, PROTOCOL.md §8.9): `contacts.avatar`
    /// holds the contact's last `ProfileUpdate.profile_avatar` (a serialized
    /// `ProfileAvatar` that passed the §8.9 checks) instead of the deprecated
    /// `ProfileUpdate.avatar` attachment, which no client ever sent. Whatever
    /// an earlier build may have kept there is dropped (`secure_delete`
    /// overwrites it); the contact's next `ProfileUpdate` brings the picture.
    static let v5 = Migration(
        version: 5,
        statements: [
            "UPDATE contacts SET avatar = NULL WHERE avatar IS NOT NULL"
        ])

    /// Saved media (2026-10-11, PROTOCOL.md §8.4 `SAVED`): when the contact
    /// said it saved one of our photos, videos or files outside the app
    /// (`saved_by_peer_at`), and when we saved one (`saved_by_me_at`).
    /// Existing rows read `NULL`: nothing was saved before this version
    /// could tell. A partial index lets the media list of a conversation
    /// walk its attachment rows only, newest first, like a page.
    static let v6 = Migration(
        version: 6,
        statements: [
            "ALTER TABLE messages ADD COLUMN saved_by_peer_at INTEGER",
            "ALTER TABLE messages ADD COLUMN saved_by_me_at INTEGER",
            "CREATE INDEX messages_attachments ON messages(conversation_id, id) WHERE kind = 2",
        ])
}
