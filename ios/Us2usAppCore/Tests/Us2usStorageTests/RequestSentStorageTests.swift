// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

/// Message requests (audit 2026-10-28 M-9): an outgoing request counts as
/// sent only once the relay accepted a visible message of ours (schema v11,
/// the same step as Android's v11).
@Suite("Message requests: an opened handle is not a sent request")
struct RequestSentStorageTests {
    @Test("v11 treats every existing outgoing request as sent, and nothing else")
    func migrationBackfillsOutgoingRequests() throws {
        let environment = try TestEnvironment()
        try environment.layout.prepare()
        let dbKey = LocalKeyDerivation.databaseKey(dek: environment.dek)
        let outgoing = AccountId(uuid: UUID()), incoming = AccountId(uuid: UUID())
        let accepted = AccountId(uuid: UUID()), blocked = AccountId(uuid: UUID())
        // A v10 database as 1.1.5 left it.
        do {
            let v10 = try EncryptedDatabase(
                url: environment.layout.databaseURL, key: dbKey, migrations: Array(Schema.migrations.prefix(10)))
            #expect(try v10.locked { try $0.scalar("PRAGMA user_version") } == .integer(10))
            _ = try PersistentProtocolStore.create(database: v10, identity: .generate(), registrationId: 5)
            try v10.locked { connection in
                for (account, state, created) in [
                    (outgoing, "outgoingRequest", 1_700_000_000_000), (incoming, "incomingRequest", 1_700_000_100_000),
                    (accepted, "accepted", 1_700_000_200_000), (blocked, "blocked", 1_700_000_300_000),
                ] as [(AccountId, String, Int64)] {
                    try connection.run(
                        "INSERT INTO contacts (account, request_state, created_at) VALUES (?, ?, ?)",
                        [account.bytes, state, created])
                }
            }
            v10.close()
        }
        let storage = try environment.open()
        #expect(try storage.database.locked { try $0.scalar("PRAGMA user_version") } == .integer(11))
        let sentAt = try storage.database.locked { connection in
            try connection.query("SELECT account, request_sent_at FROM contacts").reduce(into: [AccountId: Int64?]()) {
                $0[AccountId(bytes: $1.blob("account")!)!] = $1.int64("request_sent_at")
            }
        }
        #expect(sentAt[outgoing] == .some(1_700_000_000_000))
        #expect(sentAt[incoming] == .some(nil))
        #expect(sentAt[accepted] == .some(nil))
        #expect(sentAt[blocked] == .some(nil))
        try storage.read { tx throws in
            #expect(try tx.contacts.requestSent(to: outgoing))
            #expect(try !tx.contacts.requestSent(to: incoming))
        }
        // The waiting request still ends with the contact's ACCEPT.
        let state = try storage.write { try $0.contacts.noteVerifiedMessage(.messageRequestResponse(.accept), from: outgoing) }
        #expect(state == .accepted)
    }

    @Test("an ACCEPT ends only a request the relay took")
    func acceptNeedsASentRequest() throws {
        let storage = try TestEnvironment().open()
        _ = try TestAccount(storage: storage)
        let peer = AccountId(uuid: UUID())
        try storage.write { try $0.contacts.create(peer, state: .outgoingRequest, now: t0) }
        // Opened only: a bare ACCEPT changes nothing.
        #expect(try storage.write { try $0.contacts.noteVerifiedMessage(.messageRequestResponse(.accept), from: peer) }
            == .outgoingRequest)
        #expect(try storage.read { try $0.contacts.requestSent(to: peer) } == false)
        // The relay took a visible message: now it is a request the peer can accept.
        try storage.write { try $0.contacts.markRequestSent(to: peer, now: t0) }
        try storage.write { try $0.contacts.markRequestSent(to: peer, now: t0.addingTimeInterval(60)) }  // first one stays
        #expect(try storage.database.locked {
            try $0.queryOne("SELECT request_sent_at FROM contacts WHERE account = ?", [peer.bytes])?.date("request_sent_at")
        } == t0)
        #expect(try storage.write { try $0.contacts.noteVerifiedMessage(.messageRequestResponse(.accept), from: peer) }
            == .accepted)
    }

    @Test("a visible message to an opened handle becomes its own incoming request")
    func unsentRequestTurnsIncoming() throws {
        let storage = try TestEnvironment().open()
        _ = try TestAccount(storage: storage)
        let opened = AccountId(uuid: UUID()), sent = AccountId(uuid: UUID())
        try storage.write { tx in
            try tx.contacts.create(opened, state: .outgoingRequest, now: t0)
            try tx.contacts.create(sent, state: .outgoingRequest, now: t0)
            try tx.contacts.markRequestSent(to: sent, now: t0)
        }
        #expect(try storage.write { try $0.contacts.turnUnsentRequestIntoIncoming(opened) })
        #expect(try storage.read { try $0.contacts.requestState(of: opened) } == .incomingRequest)
        #expect(try !storage.write { try $0.contacts.turnUnsentRequestIntoIncoming(sent) })
        #expect(try storage.read { try $0.contacts.requestState(of: sent) } == .outgoingRequest)
        // Only an outgoing request is marked sent.
        try storage.write { try $0.contacts.markRequestSent(to: opened, now: t0) }
        #expect(try storage.read { try $0.contacts.requestSent(to: opened) } == false)
    }

    @Test("a contact deleted and written to again starts unsent")
    func forgottenContactStartsUnsent() throws {
        let storage = try TestEnvironment().open()
        _ = try TestAccount(storage: storage)
        let peer = AccountId(uuid: UUID())
        try storage.write { tx in
            try tx.contacts.create(peer, state: .outgoingRequest, now: t0)
            try tx.contacts.markRequestSent(to: peer, now: t0)
            try tx.contacts.removeKeepingSession(peer)
            try tx.contacts.create(peer, state: .outgoingRequest, now: t0)
        }
        #expect(try storage.read { try $0.contacts.requestSent(to: peer) } == false)
    }
}
