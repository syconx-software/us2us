import Foundation
import Testing

@testable import Us2usKit

@Suite("Profile pictures end to end against a fake relay (§4.12, §8.9, §8.10)")
struct AvatarMessengerTests {
    let relay = FakeRelay()

    private func register(_ name: String) async throws -> Messenger {
        let store = InMemoryProtocolStore(identity: .generate(), registrationId: LocalIdentity.generateRegistrationId())
        return try await Messenger.register(
            username: try NormalizedUsername(name), store: store, relay: relay.makeClient())
    }

    private func open(_ record: Data?, as username: String, account: AccountId) throws -> ProfilePicture {
        let record = try #require(record)
        let key = AvatarRecordKey.derive(username: try NormalizedUsername(username), accountId: account)
        return try AvatarRecord.openPicture(record, key: key).picture
    }

    /// A JPEG header whose `ProfileAvatar` serializes to exactly 49110 bytes,
    /// without metadata (so it may be sent).
    static let largestPhoto: AvatarPhoto = {
        let data = AvatarFixtures.jpeg(width: 256, height: 256, comment: false, fill: 49_033)
        return try! AvatarPhoto(format: .jpeg, data: data)
    }()

    @Test("a preset is published for search and found by the exact username")
    func publishAndSearch() async throws {
        let alice = try await register("alice_01")
        let aliceId = await alice.account.accountId
        try await alice.setProfilePicture(.preset(5), showInSearch: true)
        #expect(await alice.account.avatarRecordPending)
        #expect(await alice.syncAvatarRecord() == .inSync)
        let stored = try #require(relay.avatar(aliceId))
        #expect(stored.highest == 1)
        #expect(stored.record?.count == 8192)
        #expect(try open(stored.record, as: "alice_01", account: aliceId) == .preset(5))
        #expect(throws: AvatarRecordError.authenticationFailed) { try open(stored.record, as: "alice_02", account: aliceId) }
        let account = await alice.account
        #expect(account.avatarVersion == 1 && !account.avatarRecordPending)
        #expect(await alice.ownProfilePicture == OwnProfilePicture(picture: .preset(5), showInSearch: true, searchRecord: .inSync))
        let put = try #require(relay.avatarRequests.last)
        #expect(put.method == "PUT" && put.header("Content-Type") == "application/octet-stream")
        #expect(put.header("X-Auth-Token") != nil)

        let bob = try await register("bob_02")
        let found = try #require(try await bob.findAccount(username: try NormalizedUsername("Alice_01")))
        #expect(found.accountId == aliceId)
        let now = Date()
        #expect(await bob.searchAvatar(username: try NormalizedUsername("alice_01"), account: aliceId, now: now) == .preset(5))
        let fetch = try #require(relay.avatarRequests.last)
        #expect(fetch.method == "GET" && fetch.path == "/v1/accounts/\(aliceId.string)/avatar")
        #expect(fetch.header("X-Auth-Token") != nil && fetch.header("If-None-Match") == nil)

        // Within a minute: the copy in memory, no request.
        let before = relay.avatarRequests.count
        #expect(await bob.searchAvatar(username: try NormalizedUsername("alice_01"), account: aliceId, now: now) == .preset(5))
        #expect(relay.avatarRequests.count == before)
        // Later: revalidated with If-None-Match; 304 keeps the copy.
        let later = now.addingTimeInterval(120)
        #expect(await bob.searchAvatar(username: try NormalizedUsername("alice_01"), account: aliceId, now: later) == .preset(5))
        let revalidation = try #require(relay.avatarRequests.last)
        #expect(revalidation.header("If-None-Match") == "\"1\"")
        #expect(relay.avatarRequests.count == before + 1)
        // Our own account shows our own choice, without a request.
        #expect(await alice.searchAvatar(username: try NormalizedUsername("alice_01"), account: aliceId) == .preset(5))
        #expect(relay.avatarRequests.count == before + 1)
    }

    @Test("turning search off or choosing the initial letter deletes the record; contacts are not affected")
    func deletion() async throws {
        let alice = try await register("alice_01")
        let aliceId = await alice.account.accountId
        try await alice.setProfilePicture(.preset(2), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .inSync)

        try await alice.setProfilePicture(.preset(2), showInSearch: false)
        #expect(await alice.syncAvatarRecord() == .inSync)
        #expect(relay.avatar(aliceId) == FakeRelay.AvatarState(highest: 2, record: nil))
        let delete = try #require(relay.avatarRequests.last)
        #expect(delete.method == "DELETE" && delete.header("X-Avatar-Version") == "2")
        // The picture stays the user's (for contacts), the setting is kept.
        #expect(await alice.ownProfilePicture.picture == .preset(2))
        #expect(await alice.ownProfilePicture.isShownInSearch == false)
        guard case .control(let control) = await alice.ownProfileUpdate(displayName: "A", presenceSharing: true).body,
            case .profile(let profile) = control.action
        else { return }
        #expect(ProfilePicture(profile.profileAvatar) == .preset(2))

        // Nothing to write when only the picture changes while search is off.
        let writes = relay.avatarRequests.count
        try await alice.setProfilePicture(.preset(3), showInSearch: false)
        #expect(await alice.syncAvatarRecord() == .inSync)
        #expect(relay.avatarRequests.count == writes)

        // Back on: published again; then the initial letter deletes it.
        try await alice.setProfilePicture(.preset(3), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .inSync)
        #expect(try open(relay.avatar(aliceId)?.record, as: "alice_01", account: aliceId) == .preset(3))
        try await alice.setProfilePicture(.initials, showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .inSync)
        #expect(relay.avatar(aliceId)?.record == nil)
        #expect(relay.avatar(aliceId)?.highest == 4)

        // A search now finds no record: the initial letter.
        let bob = try await register("bob_02")
        #expect(await bob.searchAvatar(username: try NormalizedUsername("alice_01"), account: aliceId) == .initials)
        await #expect(throws: MessengerError.invalidContent(.malformedField("profile_avatar"))) {
            try await alice.setProfilePicture(.preset(0), showInSearch: true)
        }
    }

    @Test("409: once more with X-Avatar-Version + 1; a late older write never undoes a newer one")
    func versions() async throws {
        let alice = try await register("alice_01")
        let aliceId = await alice.account.accountId
        relay.setAvatar(FakeRelay.AvatarState(highest: 40, record: nil), for: aliceId)
        try await alice.setProfilePicture(.preset(1), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .inSync)
        #expect(relay.avatarRequests.map(\.method) == ["PUT", "PUT"])
        #expect(relay.avatar(aliceId)?.highest == 41)
        #expect(await alice.account.avatarVersion == 41)

        // A lost answer: the write happened, the client cannot know. The
        // version was saved before sending, so the next attempt uses a new one.
        relay.loseNextAnswer("DELETE", "/v1/accounts/me/avatar")
        try await alice.setProfilePicture(.preset(1), showInSearch: false)
        #expect(await alice.syncAvatarRecord() == .pending)
        #expect(await alice.account.avatarVersion == 42)
        #expect(relay.avatar(aliceId) == FakeRelay.AvatarState(highest: 42, record: nil))
        #expect(await alice.syncAvatarRecord() == .inSync)
        #expect(relay.avatar(aliceId)?.highest == 43)

        // A retry of the old publish arriving late is refused, record stays deleted.
        let key = AvatarRecordKey.derive(username: try NormalizedUsername("alice_01"), accountId: aliceId)
        let late = try AvatarRecord.seal(try #require(ProfilePicture.preset(1).serializedProfileAvatar), version: 41, key: key)
        #expect(try await alice.relay.putAvatar(late) == .stale(highestVersion: 43))
        #expect(try await alice.relay.deleteAvatar(version: 43) == .stale(highestVersion: 43))
        #expect(relay.avatar(aliceId)?.record == nil)

        // A relay far ahead of this phone (e.g. after another phone wrote):
        // the one repeat with X-Avatar-Version + 1 is enough.
        relay.setAvatar(FakeRelay.AvatarState(highest: 100, record: nil), for: aliceId)
        try await alice.setProfilePicture(.preset(9), showInSearch: true)
        relay.setAvatar(FakeRelay.AvatarState(highest: 200, record: nil), for: aliceId)
        let requests = relay.avatarRequests.count
        // First PUT (44) → 409 (200); second PUT (201) succeeds.
        #expect(await alice.syncAvatarRecord() == .inSync)
        #expect(relay.avatarRequests.count == requests + 2)
        #expect(relay.avatar(aliceId)?.highest == 201)
    }

    @Test("failures leave the write owed; a relay without avatar records is left alone until the next start")
    func failures() async throws {
        let alice = try await register("alice_01")
        let aliceId = await alice.account.accountId
        relay.failNext("PUT", "/v1/accounts/me/avatar")
        try await alice.setProfilePicture(.preset(1), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .pending)
        #expect(await alice.ownProfilePicture.searchRecord == .pending)
        // The next connection makes it (queue_empty), as does maintenance.
        try await alice.handle(.queueEmpty, deliver: { _ in }, onQueueEmpty: {}, acknowledge: { _ in })
        #expect(await alice.syncAvatarRecord() == .inSync)
        #expect(relay.avatar(aliceId)?.record != nil)

        let bob = try await register("bob_02")
        relay.disableAvatars()
        try await bob.setProfilePicture(.preset(2), showInSearch: true)
        #expect(await bob.syncAvatarRecord() == .unavailable)
        #expect(await bob.ownProfilePicture.searchRecord == .unavailable)
        let requests = relay.avatarRequests.count
        #expect(await bob.syncAvatarRecord() == .unavailable)
        try await bob.performMaintenance()
        #expect(relay.avatarRequests.count == requests)
        // A search against such a relay is the initial letter, nothing thrown.
        #expect(await bob.searchAvatar(username: try NormalizedUsername("alice_01"), account: aliceId) == .initials)

        // The next app start (a new Messenger from the saved account) tries again.
        let restarted = Messenger(account: await bob.account, store: bob.store, relay: relay.makeClient())
        #expect(await restarted.account.avatarRecordPending)
        #expect(await restarted.syncAvatarRecord() == .unavailable)
        #expect(relay.avatarRequests.count == requests + 2)
    }

    @Test("a username change republishes under the new name (the relay deleted the record)")
    func usernameChange() async throws {
        let alice = try await register("alice_01")
        let aliceId = await alice.account.accountId
        try await alice.setProfilePicture(.preset(6), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .inSync)

        try await alice.changeUsername(to: try NormalizedUsername("alice_new"))
        #expect(await alice.syncAvatarRecord() == .inSync)
        let record = relay.avatar(aliceId)?.record
        #expect(try open(record, as: "alice_new", account: aliceId) == .preset(6))
        #expect(throws: AvatarRecordError.authenticationFailed) { try open(record, as: "alice_01", account: aliceId) }
        #expect(relay.avatar(aliceId)?.highest == 2)

        let bob = try await register("bob_02")
        #expect(try await bob.findAccount(username: try NormalizedUsername("alice_new"))?.accountId == aliceId)
        #expect(await bob.searchAvatar(username: try NormalizedUsername("alice_new"), account: aliceId) == .preset(6))

        // Without a published picture a rename writes nothing.
        let carol = try await register("carol_03")
        let writes = relay.avatarRequests.count
        try await carol.changeUsername(to: try NormalizedUsername("carol_new"))
        #expect(await carol.syncAvatarRecord() == .inSync)
        #expect(relay.avatarRequests.count == writes)
    }

    @Test("search copies go when the directory maps the name elsewhere, and with the account")
    func searchCache() async throws {
        let alice = try await register("alice_01")
        let aliceId = await alice.account.accountId
        try await alice.setProfilePicture(.preset(8), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .inSync)
        let bob = try await register("bob_02")
        let name = try NormalizedUsername("alice_01")
        #expect(await bob.searchAvatar(username: name, account: aliceId) == .preset(8))
        let fetches = relay.avatarRequests.count

        try await alice.deleteAccount()
        #expect(relay.avatar(aliceId) == nil)
        #expect(try await bob.findAccount(username: name) == nil)
        // The cached copy for that name went with the 404.
        #expect(await bob.searchAvatar(username: name, account: aliceId) == .initials)
        #expect(relay.avatarRequests.count == fetches + 1)
        await bob.forgetAvatarCaches()
    }

    @Test("a record under another account's id, or tampered, opens as nothing")
    func foreignRecords() async throws {
        let alice = try await register("alice_01")
        let aliceId = await alice.account.accountId
        let mallory = try await register("mallory_9")
        let malloryId = await mallory.account.accountId
        // Mallory stores a record sealed for Alice's account under her own: it
        // does not open as hers (the account id is in the key and the AAD).
        let key = AvatarRecordKey.derive(username: try NormalizedUsername("alice_01"), accountId: aliceId)
        let sealed = try AvatarRecord.seal(try #require(ProfilePicture.preset(4).serializedProfileAvatar), version: 1, key: key)
        relay.setAvatar(FakeRelay.AvatarState(highest: 1, record: sealed), for: malloryId)
        let bob = try await register("bob_02")
        #expect(await bob.searchAvatar(username: try NormalizedUsername("alice_01"), account: malloryId) == .initials)
        // A flipped byte in a stored record: the initial letter too.
        relay.setAvatar(FakeRelay.AvatarState(highest: 1, record: sealed.flippingBit(at: 100)), for: aliceId)
        #expect(await bob.searchAvatar(username: try NormalizedUsername("alice_01"), account: aliceId) == .initials)
        relay.setAvatar(FakeRelay.AvatarState(highest: 1, record: sealed), for: aliceId)
        #expect(await bob.searchAvatar(username: try NormalizedUsername("alice_01"), account: aliceId, now: Date() + 120)
            == .preset(4))
    }

    @Test("backup and restore keep the picture and the setting; the restored phone brings the record in line")
    func backupAndRestore() async throws {
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        try await bob.setProfilePicture(.preset(7), showInSearch: true)
        #expect(await bob.syncAvatarRecord() == .inSync)
        var contact = BRPBackupContact()
        contact.accountID = AccountId(uuid: UUID()).bytes
        contact.avatar = BRPProfileAvatar.with { $0.presetID = 0 }  // malformed: left out of the file
        let backup = try await bob.makeBackup(displayName: "Bob", contacts: [contact])
        #expect(ProfilePicture(backup.avatar) == .preset(7))
        #expect(!backup.avatarSearchOff)
        #expect(!backup.contacts[0].hasAvatar)
        try Messenger.validate(backup)

        let restored = try await Messenger.restore(
            from: backup, relay: relay.makeClient(),
            makeStore: { InMemoryProtocolStore(identity: $0, registrationId: $1) })
        let account = await restored.account
        #expect(account.profilePicture == .preset(7) && !account.avatarSearchOff)
        // The backup holds no version: 409 first, then X-Avatar-Version + 1.
        #expect(!account.avatarRecordPending)
        #expect(account.avatarVersion == 2)
        #expect(relay.avatar(bobId)?.highest == 2)
        #expect(try open(relay.avatar(bobId)?.record, as: "bob_02", account: bobId) == .preset(7))

        // A backup with the initial letter: the restored phone deletes what is published.
        var plain = try await restored.makeBackup(displayName: "Bob", contacts: [])
        plain.clearAvatar()
        plain.avatarSearchOff = true
        let again = try await Messenger.restore(
            from: plain, relay: relay.makeClient(),
            makeStore: { InMemoryProtocolStore(identity: $0, registrationId: $1) })
        #expect(await again.account.profilePicture == .initials)
        #expect(await again.account.avatarSearchOff)
        #expect(relay.avatar(bobId) == FakeRelay.AvatarState(highest: 3, record: nil))
    }

    @Test("a photo with metadata is never sent, whoever built it (§8.9)")
    func photoWithMetadata() async throws {
        let alice = try await register("alice_01")
        let exif = try AvatarPhoto(
            format: .jpeg, data: AvatarFixtures.jpeg(width: 64, height: 64, exif: true, comment: false))
        await #expect(throws: MessengerError.invalidContent(.malformedField("profile_avatar"))) {
            try await alice.setProfilePicture(.photo(exif), showInSearch: true)
        }
        let wide = try AvatarPhoto(format: .jpeg, data: AvatarFixtures.jpeg(width: 64, height: 32, comment: false))
        await #expect(throws: MessengerError.invalidContent(.malformedField("profile_avatar"))) {
            try await alice.setProfilePicture(.photo(wide), showInSearch: true)
        }
        #expect(await alice.account.profilePicture == .initials)
        #expect(await alice.syncAvatarRecord() == .inSync)
        #expect(relay.avatarRequests.isEmpty)
    }

    @Test("a replayed older record is ignored, also after a 404 (§8.10 step 5)")
    func rollback() async throws {
        let alice = try await register("alice_01")
        let aliceId = await alice.account.accountId
        try await alice.setProfilePicture(.preset(1), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .inSync)
        let first = try #require(relay.avatar(aliceId)?.record)
        try await alice.setProfilePicture(.preset(2), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .inSync)
        let second = try #require(relay.avatar(aliceId)?.record)
        let bob = try await register("bob_02")
        let name = try NormalizedUsername("alice_01")
        var now = Date()
        #expect(await bob.searchAvatar(username: name, account: aliceId, now: now) == .preset(2))

        // The relay serves the older record: the cached copy stays.
        relay.setAvatar(FakeRelay.AvatarState(highest: 2, record: first), for: aliceId)
        now += 120
        #expect(await bob.searchAvatar(username: name, account: aliceId, now: now) == .preset(2))
        // A 404 drops the copy but not the version: the older record shows nothing.
        relay.setAvatar(FakeRelay.AvatarState(highest: 2, record: nil), for: aliceId)
        now += 120
        #expect(await bob.searchAvatar(username: name, account: aliceId, now: now) == .initials)
        relay.setAvatar(FakeRelay.AvatarState(highest: 2, record: first), for: aliceId)
        now += 120
        #expect(await bob.searchAvatar(username: name, account: aliceId, now: now) == .initials)
        // The same version again is shown.
        relay.setAvatar(FakeRelay.AvatarState(highest: 2, record: second), for: aliceId)
        now += 120
        #expect(await bob.searchAvatar(username: name, account: aliceId, now: now) == .preset(2))
        // Forgetting the caches (the vault locked) forgets the versions too.
        await bob.forgetAvatarCaches()
        relay.setAvatar(FakeRelay.AvatarState(highest: 2, record: first), for: aliceId)
        now += 120
        #expect(await bob.searchAvatar(username: name, account: aliceId, now: now) == .preset(1))
    }

    @Test("a rate-limited store never holds up taking the picture out of search (§4.11)")
    func rateLimitedStore() async throws {
        let alice = try await register("alice_01")
        let aliceId = await alice.account.accountId
        try await alice.setProfilePicture(.preset(1), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .inSync)
        relay.rateLimitNext("PUT", "/v1/accounts/me/avatar", retryAfter: 3600)
        try await alice.setProfilePicture(.preset(2), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .pending)
        #expect(try open(relay.avatar(aliceId)?.record, as: "alice_01", account: aliceId) == .preset(1))

        // Off while the store waits: the delete goes out at once.
        try await alice.setProfilePicture(.preset(2), showInSearch: false)
        #expect(await alice.syncAvatarRecord() == .inSync)
        #expect(relay.avatar(aliceId) == FakeRelay.AvatarState(highest: 3, record: nil))

        // On again: the store still waits for its Retry-After.
        let requests = relay.avatarRequests.count
        try await alice.setProfilePicture(.preset(2), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .pending)
        #expect(relay.avatarRequests.count == requests)
    }

    @Test("the largest photo fits a ProfileUpdate in one sealed envelope (§8.7)")
    func largestPhotoProfileUpdate() async throws {
        let photo = Self.largestPhoto
        #expect(ProfilePicture.photo(photo).serializedProfileAvatar?.count == ProfilePicture.maxSerializedBytes)
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        try await alice.setProfilePicture(.photo(photo), showInSearch: true)
        #expect(await alice.syncAvatarRecord() == .inSync)
        let aliceId = await alice.account.accountId
        #expect(relay.avatar(aliceId)?.record?.count == AvatarRecord.maxLength)

        let profile = await alice.ownProfileUpdate(displayName: "Alice", presenceSharing: true)
        // The first message to a new contact carries the pre-key overhead too.
        try await alice.send(profile, to: await bob.account.accountId, authorization: .proofOfWork)
        let received = try #require(try await bob.receiveAll().first)
        guard case .control(let control) = received.content.body, case .profile(let update) = control.action else {
            Issue.record("not a profile")
            return
        }
        #expect(ContactProfile().applying(update).avatar == .photo(photo))
        #expect(await bob.searchAvatar(username: try NormalizedUsername("alice_01"), account: aliceId) == .photo(photo))
    }
}
