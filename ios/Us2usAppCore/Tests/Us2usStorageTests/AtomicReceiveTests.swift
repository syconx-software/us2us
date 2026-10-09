// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

/// The ratchet advance of a decryption and the stored message commit in one
/// transaction (ios/README "Persistent store"), so a crash in between can
/// never lose a message.
@Suite("Atomic receive: staged decryption state (§5.4)")
struct AtomicReceiveTests {
    struct Fixture {
        let environment: TestEnvironment
        let storage: LocalStorage
        let bob: Party
        let alice: Party
        let conversation: Conversation
    }

    private func fixture() throws -> Fixture {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let account = try TestAccount(storage: storage)
        let bob = Party(account: account.accountId, store: account.store)
        let alice = Party.inMemory()
        try alice.cipher.processBundle(try bob.bundle())
        let (_, conversation) = try makeContactWithConversation(storage, contact: alice.account)
        return Fixture(environment: environment, storage: storage, bob: bob, alice: alice, conversation: conversation)
    }

    private func store(_ decrypted: DecryptedContent, in fixture: Fixture, relayId: String) throws {
        try fixture.storage.write { tx throws in
            guard case .data(let data) = decrypted.content.body else { return }
            try tx.messages.storeIncoming(data, in: fixture.conversation.id)
            try tx.envelopes.record(relayId)
        }
    }

    @Test("a crash after decrypting but before storing loses nothing: the redelivery decrypts again")
    func crashBeforeStoring() async throws {
        let f = try fixture()
        let store = try #require(f.storage.protocolStore)
        let envelope = try f.alice.seal(.text("önemli"), to: f.bob)
        let preKeysBefore = try store.oneTimePreKeyCount()

        // Decrypt inside the scope, then "crash" (the process dies: storage is
        // closed without committing anything).
        _ = try? await store.withStagedInboundWrites {
            let decrypted = try f.bob.receive(envelope)
            #expect(decrypted.content.textBody == "önemli")
            #expect(store.hasStagedWrites)
            f.storage.close()
        }

        let reopened = try f.environment.open()
        let restoredStore = try #require(reopened.protocolStore)
        #expect(try restoredStore.loadSession(for: f.alice.account.protocolAddress, context: NullContext()) == nil)
        #expect(try restoredStore.oneTimePreKeyCount() == preKeysBefore)
        // The relay redelivers the unacknowledged envelope: it still decrypts.
        let bobAgain = Party(account: f.bob.account, store: restoredStore)
        #expect(try bobAgain.receive(envelope).content.textBody == "önemli")
    }

    @Test("storing the message commits the session advance in the same transaction")
    func commitTogether() async throws {
        let f = try fixture()
        let store = try #require(f.storage.protocolStore)
        let envelope = try f.alice.seal(.text("birlikte"), to: f.bob)
        try await store.withStagedInboundWrites {
            let decrypted = try f.bob.receive(envelope)
            // Not durable yet: a second connection would not see it.
            #expect(store.hasStagedWrites)
            try self.store(decrypted, in: f, relayId: "r1")
            #expect(!store.hasStagedWrites)
        }
        f.storage.close()

        let reopened = try f.environment.open()
        let restoredStore = try #require(reopened.protocolStore)
        #expect(try restoredStore.loadSession(for: f.alice.account.protocolAddress, context: NullContext()) != nil)
        let messages = try reopened.read { try $0.messages.page(in: f.conversation.id) }
        #expect(messages.map { $0.content.map { BRPContent(data: $0).textBody } } == [Optional("birlikte")])
        #expect(try reopened.read { try $0.envelopes.contains("r1") })
        // The same envelope is now a duplicate for libsignal, and known to the envelope log.
        #expect(throws: SessionError.self) {
            try Party(account: f.bob.account, store: restoredStore).receive(envelope)
        }
    }

    @Test("a failed store rolls back both the message and the session advance")
    func failedStoreRollsBack() async throws {
        let f = try fixture()
        let store = try #require(f.storage.protocolStore)
        let envelope = try f.alice.seal(.text("x"), to: f.bob)
        struct Boom: Error {}
        try await store.withStagedInboundWrites {
            _ = try f.bob.receive(envelope)
            #expect(throws: Boom.self) {
                try f.storage.write { tx throws in
                    try tx.envelopes.record("r1")
                    throw Boom()
                }
            }
            // Still staged (not lost, not committed): discard it for the redelivery.
            #expect(store.hasStagedWrites)
            #expect(try store.discardStagedInboundWrites())
        }
        #expect(try f.bob.receive(envelope).content.textBody == "x")
        #expect(try !f.storage.read { try $0.envelopes.contains("r1") })
    }

    @Test("a write from outside the scope commits staged state first (no chain-key reuse after a crash)")
    func outsideWriteFlushes() async throws {
        let f = try fixture()
        let store = try #require(f.storage.protocolStore)
        // An established session in both directions.
        _ = try f.alice.send(.text("a"), to: f.bob)
        _ = try f.bob.send(.text("b"), to: f.alice)
        let envelope = try f.alice.seal(.text("c"), to: f.bob)
        try await store.withStagedInboundWrites {
            _ = try f.bob.receive(envelope)
            #expect(store.hasStagedWrites)
            // A concurrent send on another task advances the same session.
            let reply = try await Task.detached { [bob = f.bob, alice = f.alice] in
                try bob.seal(.text("reply"), to: alice)
            }.value
            #expect(!store.hasStagedWrites)
            #expect(try f.alice.receive(reply).content.textBody == "reply")
            // Already committed by the send: nothing is left to discard.
            #expect(try store.discardStagedInboundWrites())
        }
        f.storage.close()
        let reopened = try f.environment.open()
        let bobAgain = Party(account: f.bob.account, store: try #require(reopened.protocolStore))
        // The persisted session contains both the receive and the send.
        #expect(try f.alice.send(.text("after"), to: bobAgain).content.textBody == "after")
        #expect(try bobAgain.send(.text("after2"), to: f.alice).content.textBody == "after2")
    }

    @Test("a protocol write nested in another transaction commits staged state first, never overwritten later")
    func nestedProtocolWrite() async throws {
        let f = try fixture()
        let store = try #require(f.storage.protocolStore)
        _ = try f.alice.send(.text("a"), to: f.bob)
        _ = try f.bob.send(.text("b"), to: f.alice)
        let envelope = try f.alice.seal(.text("c"), to: f.bob)
        var replies: [Data] = []
        try await store.withStagedInboundWrites {
            _ = try f.bob.receive(envelope)
            // Another task seals inside its own storage transaction (savepoint).
            replies = try await Task.detached { [storage = f.storage, bob = f.bob, alice = f.alice] in
                try storage.write { tx throws in
                    try tx.envelopes.record("other-work")
                    return [try bob.seal(.text("r1"), to: alice), try bob.seal(.text("r2"), to: alice)]
                }
            }.value
            #expect(!store.hasStagedWrites)
        }
        f.storage.close()
        let reopened = try f.environment.open()
        let bobAgain = Party(account: f.bob.account, store: try #require(reopened.protocolStore))
        for reply in replies { _ = try f.alice.receive(reply) }
        // Had the staged (older) session overwritten the sealed one, this would
        // reuse r1's chain key and Alice would reject it as a duplicate.
        #expect(try bobAgain.send(.text("r3"), to: f.alice).content.textBody == "r3")
    }

    @Test("staged state read outside the scope can no longer be discarded")
    func observedStateIsKept() async throws {
        let f = try fixture()
        let store = try #require(f.storage.protocolStore)
        let envelope = try f.alice.seal(.text("seen"), to: f.bob)
        try await store.withStagedInboundWrites {
            _ = try f.bob.receive(envelope)
            _ = try await Task.detached { [store, alice = f.alice] in
                try store.loadSession(for: alice.account.protocolAddress, context: NullContext()) != nil
            }.value
            #expect(try !store.discardStagedInboundWrites())
            #expect(!store.hasStagedWrites)  // committed instead
        }
    }

    @Test("staging is per store: another party's writes in the same task are not staged")
    func perStore() async throws {
        let f = try fixture()
        let store = try #require(f.storage.protocolStore)
        let other = try StoreFixture(.persistent)
        let carol = other.party()
        try await store.withStagedInboundWrites {
            try carol.cipher.processBundle(try f.alice.bundle())
            #expect(!store.hasStagedWrites)
            #expect(try carol.store.loadSession(for: f.alice.account.protocolAddress, context: NullContext()) != nil)
        }
    }

    @Test("whatever is still staged when the scope ends is committed")
    func scopeEndCommits() async throws {
        let f = try fixture()
        let store = try #require(f.storage.protocolStore)
        let envelope = try f.alice.seal(.text("late"), to: f.bob)
        try await store.withStagedInboundWrites {
            _ = try f.bob.receive(envelope)
        }
        #expect(!store.hasStagedWrites)
        f.storage.close()
        let reopened = try f.environment.open()
        #expect(
            try reopened.protocolStore?.loadSession(for: f.alice.account.protocolAddress, context: NullContext())
                != nil)
    }
}
