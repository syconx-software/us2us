import Foundation
import LibSignalClient
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

// MARK: Fake community relay

/// An in-process relay for the community routes (and the few 1:1 routes the
/// tests touch), routed by host so tests run in parallel.
final class FakeCommunityRelay: URLProtocol, @unchecked Sendable {
    struct Envelope: Codable, Sendable {
        let id: String; let communityId: AccountId; let revision: UInt64; let serverTimestamp: Int64; let envelope: Data
    }
    struct Sent: Sendable { let community: AccountId; let recipient: AccountId; let message: AccountId; let revision: UInt64; let envelope: Data }
    struct DirectEnvelope: Codable, Sendable { let id: String; let serverTimestamp: Int64; let envelope: Data }

    final class Server: @unchecked Sendable {
        let own: AccountId
        let baseURL: URL
        let client: RelayClient
        private let lock = NSLock()
        private var _states: [AccountId: SignedCommunityState] = [:]
        private var _inbox: [Envelope] = []
        private var _acked: [String] = []
        private var _sent: [Sent] = []
        private var _sendStatus: [AccountId: Int] = [:]
        private var _identities: [AccountId: Data] = [:]
        private var _identityStatus = 200
        private var _bundles: [AccountId: PreKeyBundleResponse] = [:]
        private var _requests: [String] = []
        private var _leaveStatus = 204
        private var _applications: [AccountId: [CommunityJoinRequest]] = [:]
        private var _applicationStatus: [AccountId: Int] = [:]
        private var _summaries: [AccountId: Data] = [:]
        private var _direct: [DirectEnvelope] = []
        private var _media: [Data: (blob: Data, token: Data)] = [:]
        private var _uploads = 0
        private var _mediaDeletes: [Data] = []

        init(own: AccountId) {
            self.own = own
            baseURL = URL(string: "https://community-\(UUID().uuidString.lowercased()).test")!
            let configuration = RelayClient.defaultConfiguration()
            configuration.protocolClasses = [FakeCommunityRelay.self]
            client = RelayClient(baseURL: baseURL, configuration: configuration)
            FakeCommunityRelay.servers.withLock { $0[baseURL.host!] = self }
        }

        func with<T>(_ body: (Server) -> T) -> T { lock.lock(); defer { lock.unlock() }; return body(self) }
        func setState(_ signed: SignedCommunityState?, for id: AccountId) { with { $0._states[id] = signed } }
        func deliver(_ envelope: Envelope) { with { $0._inbox.append(envelope) } }
        func deliverDirect(_ envelope: DirectEnvelope) { with { $0._direct.append(envelope) } }
        func setSendStatus(_ status: Int?, for recipient: AccountId) { with { $0._sendStatus[recipient] = status } }
        func register(_ account: AccountId, identityKey: Data, bundle: PreKeyBundleResponse? = nil) {
            with { $0._identities[account] = identityKey; $0._bundles[account] = bundle }
        }
        func setIdentityStatus(_ status: Int) { with { $0._identityStatus = status } }
        func setLeaveStatus(_ status: Int) { with { $0._leaveStatus = status } }
        func setRedeemSummary(_ json: String, for id: AccountId) { with { $0._summaries[id] = Data(json.utf8) } }
        func setApplications(_ applications: [CommunityJoinRequest], in id: AccountId) { with { $0._applications[id] = applications } }
        func setApplicationStatus(_ status: Int, in id: AccountId) { with { $0._applicationStatus[id] = status } }
        var acked: [String] { with { $0._acked } }
        var inbox: [Envelope] { with { $0._inbox } }
        var sent: [Sent] { with { $0._sent } }
        var requests: [String] { with { $0._requests } }
        /// `POST /v1/media` calls.
        var uploads: Int { with { $0._uploads } }
        /// Blobs deleted with their token (`DELETE /v1/media/{id}` → 204).
        var deletedMedia: [Data] { with { $0._mediaDeletes } }
        func blob(_ id: Data) -> Data? { with { $0._media[id]?.blob } }
        /// A blob another member uploaded.
        func putMedia(_ blob: Data, token: Data = Data(repeating: 0x44, count: 32)) -> Data {
            let id = RandomId.make()
            with { $0._media[id] = (blob, token) }
            return id
        }
        func count(_ prefix: String) -> Int { requests.filter { $0.hasPrefix(prefix) }.count }

        static let encoder: JSONEncoder = {
            let encoder = JSONEncoder()
            encoder.dataEncodingStrategy = .custom { data, encoder in
                var container = encoder.singleValueContainer()
                try container.encode(data.base64URLEncodedString())
            }
            return encoder
        }()
        static let decoder: JSONDecoder = {
            let decoder = JSONDecoder()
            decoder.dataDecodingStrategy = .custom { decoder in
                let container = try decoder.singleValueContainer()
                return Data(base64URLEncoded: try container.decode(String.self)) ?? Data()
            }
            return decoder
        }()

        func handle(method: String, url: URL, headers: [String: String], body: Data) -> (Int, Data) {
            lock.lock(); defer { lock.unlock() }
            let path = url.path
            _requests.append("\(method) \(path)")
            let parts = path.split(separator: "/").map(String.init)
            func json<T: Encodable>(_ status: Int, _ value: T) -> (Int, Data) { (status, (try? Self.encoder.encode(value)) ?? Data()) }
            func error(_ status: Int, _ code: String) -> (Int, Data) { (status, Data(#"{"error":{"code":"\#(code)","message":"test"}}"#.utf8)) }
            struct States: Encodable { let communities: [SignedCommunityState] }
            struct Inbox: Encodable { let envelopes: [Envelope]; let more: Bool }
            struct Ack: Decodable { let ids: [String] }
            struct Accepted: Encodable { let id: String; let serverTimestamp: Int64 }
            struct Identity: Encodable { let accountId: AccountId; let identityKey: Data }
            struct Page: Encodable { let messages: [DirectEnvelope]; let more: Bool }
            struct Directory: Encodable { let communities: [String]; let nextCursor: String? }
            switch (method, parts) {
            case ("GET", ["v1", "communities", "mine"]):
                let mine = _states.values.filter { (try? CommunityState(bytes: $0.state))?.member(own) != nil }
                return json(200, States(communities: mine))
            case ("GET", ["v1", "communities", "inbox"]):
                return json(200, Inbox(envelopes: _inbox, more: false))
            case ("POST", ["v1", "communities", "inbox", "ack"]):
                let ids = (try? Self.decoder.decode(Ack.self, from: body).ids) ?? []
                _acked += ids
                _inbox.removeAll { ids.contains($0.id) }
                return (204, Data())
            case ("GET", ["v1", "communities"]):
                return json(200, Directory(communities: [], nextCursor: nil))
            case ("POST", ["v1", "communities"]):
                return (201, body)
            case ("POST", ["v1", "communities", "invites", "redeem"]):
                struct Redemption: Decodable { let communityId: AccountId }
                guard let id = try? Self.decoder.decode(Redemption.self, from: body).communityId, let summary = _summaries[id] else { return error(404, "not_found") }
                return (202, summary)
            case ("GET", let p) where p.count == 3 && p[1] == "communities":
                guard let id = AccountId(string: p[2]), let state = _states[id] else { return error(404, "not_found") }
                return json(200, state)
            case ("GET", let p) where p.count == 4 && p[1] == "communities" && p[3] == "requests":
                guard let id = AccountId(string: p[2]) else { return error(400, "bad_request") }
                if let status = _applicationStatus[id], status != 200 { return error(status, "unavailable") }
                struct Applications: Encodable { let requests: [CommunityJoinRequest] }
                return json(200, Applications(requests: _applications[id] ?? []))
            case ("DELETE", let p) where p.count == 5 && p[3] == "requests" && p[4] != "me":
                guard let id = AccountId(string: p[2]), let account = AccountId(string: p[4]) else { return error(400, "bad_request") }
                _applications[id]?.removeAll { $0.accountId == account }
                return (204, Data())
            case ("PUT", let p) where p.count == 4 && p[3] == "state":
                guard let id = AccountId(string: p[2]), let signed = try? Self.decoder.decode(SignedCommunityState.self, from: body),
                      let state = try? CommunityState(bytes: signed.state) else { return error(400, "bad_request") }
                _states[id] = signed
                _applications[id]?.removeAll { state.member($0.accountId) != nil }
                return json(200, signed)
            case ("DELETE", let p) where p.count == 5 && p[3] == "members" && p[4] == "me":
                return _leaveStatus == 204 ? (204, Data()) : error(_leaveStatus, _leaveStatus == 409 ? "stale_version" : "not_found")
            case ("DELETE", let p) where p.count == 5 && p[3] == "requests" && p[4] == "me":
                return (204, Data())
            case ("DELETE", let p) where p.count == 3 && p[1] == "communities":
                return (204, Data())
            case ("PUT", let p) where p.count == 6 && p[3] == "messages":
                guard let community = AccountId(string: p[2]), let recipient = AccountId(string: p[4]), let message = AccountId(string: p[5]),
                      let revision = headers.first(where: { $0.key.lowercased() == "x-community-revision" }).flatMap({ UInt64($0.value) })
                else { return error(400, "bad_request") }
                let status = _sendStatus[recipient] ?? 202
                switch status {
                case 202:
                    _sent.append(Sent(community: community, recipient: recipient, message: message, revision: revision, envelope: body))
                    return json(202, Accepted(id: UUID().uuidString, serverTimestamp: 1))
                case 429: return error(429, "community_inbox_full")
                case 409: return error(409, "stale_version")
                case 404: return error(404, "not_found")
                default: return error(status, "unavailable")
                }
            case ("GET", let p) where p.count == 4 && p[1] == "accounts" && p[3] == "identity":
                guard _identityStatus == 200 else { return error(_identityStatus, "unavailable") }
                guard let id = AccountId(string: p[2]), let key = _identities[id] else { return error(404, "not_found") }
                return json(200, Identity(accountId: id, identityKey: key))
            case ("GET", let p) where p.count == 3 && p[1] == "keys":
                guard let id = AccountId(string: p[2]), let bundle = _bundles[id] else { return error(404, "not_found") }
                return json(200, bundle)
            case ("POST", ["v1", "media"]):
                struct Uploaded: Encodable { let mediaId: Data; let deleteToken: Data; let expiresAt: Int64 }
                let id = RandomId.make(), token = RandomId.make() + RandomId.make()
                _media[id] = (body, token)
                _uploads += 1
                return json(201, Uploaded(mediaId: id, deleteToken: token, expiresAt: 1))
            case ("GET", let p) where p.count == 3 && p[1] == "media":
                guard let id = Data(base64URLEncoded: p[2]), let stored = _media[id] else { return error(404, "not_found") }
                return (200, stored.blob)
            case ("DELETE", let p) where p.count == 3 && p[1] == "media":
                guard let id = Data(base64URLEncoded: p[2]), let stored = _media[id] else { return error(404, "not_found") }
                let token = headers.first(where: { $0.key.lowercased() == "x-delete-token" }).flatMap { Data(base64URLEncoded: $0.value) }
                guard token == stored.token else { return error(403, "access_denied") }
                _media[id] = nil
                _mediaDeletes.append(id)
                return (204, Data())
            case ("GET", ["v1", "messages"]):
                return json(200, Page(messages: _direct, more: false))
            case ("POST", ["v1", "messages", "ack"]):
                let ids = (try? Self.decoder.decode(Ack.self, from: body).ids) ?? []
                _direct.removeAll { ids.contains($0.id) }
                return (204, Data())
            default:
                return error(404, "not_found")
            }
        }
    }

    static let servers = LockedState<[String: Server]>([:])

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let server = Self.servers.withLock({ $0[url.host ?? ""] }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotFindHost)); return
        }
        var body = request.httpBody ?? Data()
        if body.isEmpty, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 65_536)
            while stream.hasBytesAvailable {
                let read = stream.read(&buffer, maxLength: buffer.count)
                if read <= 0 { break }
                body.append(buffer, count: read)
            }
        }
        let (status, data) = server.handle(method: request.httpMethod ?? "GET", url: url, headers: request.allHTTPHeaderFields ?? [:], body: body)
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: status == 429 ? ["Content-Type": "application/json", "Retry-After": "0"] : ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

// MARK: Parties

/// A member on another phone: an in-memory libsignal store.
struct CommunityPeer {
    let account = AccountId(uuid: UUID())
    let store: InMemoryProtocolStore
    let cipher: SessionCipher

    init(identity: IdentityKeyPair = .generate()) {
        store = InMemoryProtocolStore(identity: identity, registrationId: LocalIdentity.generateRegistrationId())
        cipher = SessionCipher(localAccount: account, store: store)
    }

    var identity: IdentityKeyPair { try! store.identityKeyPair(context: NullContext()) }
    var member: CommunityMember { CommunityMember(accountId: account, identityKey: identity.identityKey.serialize()) }

    func bundle() throws -> PreKeyBundleResponse { try CommunityPeer.bundle(account: account, store: store) }

    static func bundle(account: AccountId, store: any ProtocolStore) throws -> PreKeyBundleResponse {
        let generator = PreKeyGenerator(store: store)
        let identity = try store.identityKeyPair(context: NullContext())
        return PreKeyBundleResponse(
            accountId: account, deviceId: 1, registrationId: try store.localRegistrationId(context: NullContext()),
            identityKey: identity.identityKey.serialize(), signedPreKey: try generator.generateSignedPreKey(),
            kyberPreKey: try generator.generateOneTimeKyberPreKeys(count: 1)[0],
            oneTimePreKey: try generator.generateOneTimePreKeys(count: 1)[0])
    }
}

/// This phone ("bob"): encrypted storage, a real `Messenger` on the fake relay.
final class CommunityFixture: @unchecked Sendable {
    let directory: TemporaryDirectory
    let storage: LocalStorage
    let store: PersistentProtocolStore
    let account: LocalAccount
    let relay: FakeCommunityRelay.Server
    let messenger: Messenger
    let backend: MessengerBackend
    let engine: CommunityEngine

    init() throws {
        directory = try TemporaryDirectory()
        let directories = directory.directories
        try directories.prepare()
        storage = try LocalStorage.open(layout: directories.storage, dek: randomKey()).storage
        store = try storage.createIdentity(.generate(), registrationId: LocalIdentity.generateRegistrationId())
        account = LocalAccount(
            accountId: AccountId(uuid: UUID()), authToken: Data(repeating: 7, count: 32),
            username: try NormalizedUsername("community_tester"), accessKey: Data(repeating: 9, count: 16))
        try storage.accountSaver(account)
        relay = FakeCommunityRelay.Server(own: account.accountId)
        messenger = Messenger(account: account, store: store, relay: relay.client)
        backend = MessengerBackend(messenger: messenger, accountId: account.accountId, protocolStore: store)
        engine = CommunityEngine(backend: backend, storage: storage)
        relay.register(account.accountId, identityKey: me.identityKey)
    }

    deinit { storage.close() }

    var identity: IdentityKeyPair { try! store.identityKeyPair(context: NullContext()) }
    var me: CommunityMember { CommunityMember(accountId: account.accountId, identityKey: identity.identityKey.serialize()) }
    func bundle() throws -> PreKeyBundleResponse { try CommunityPeer.bundle(account: account.accountId, store: store) }

    /// A community `owner` signed, served by the relay; pinned here as after a request or invitation.
    @discardableResult
    func community(owner: CommunityPeer, members: [CommunityMember], revision: UInt64 = 1, id: AccountId = AccountId(uuid: UUID()), pin: Bool = true) throws -> CommunityState {
        let state = try CommunityState(id: id, revision: revision, isPublic: true, owner: owner.member, name: "Mahalle", members: [owner.member] + members)
        relay.setState(SignedCommunityState(state: state.bytes, signature: owner.identity.privateKey.generateSignature(message: state.bytes)), for: id)
        if pin { try storage.write { try $0.communities.pin(id, ownerKey: owner.member.identityKey, name: "Mahalle") } }
        return state
    }

    /// A community this phone owns.
    @discardableResult
    func ownCommunity(members: [CommunityMember], revision: UInt64 = 1) throws -> CommunityState {
        let state = try CommunityState(id: AccountId(uuid: UUID()), revision: revision, isPublic: true, owner: me, name: "Bizim", members: [me] + members)
        let signed = SignedCommunityState(state: state.bytes, signature: identity.privateKey.generateSignature(message: state.bytes))
        relay.setState(signed, for: state.id)
        try storage.write { try $0.communities.save(signed) }
        return state
    }

    /// `peer` seals a community message to this phone and the relay queues it.
    @discardableResult
    func receive(_ text: String, from peer: CommunityPeer, in state: CommunityState, revision: UInt64? = nil, envelopeId: String = UUID().uuidString) throws -> String {
        if try !peer.cipher.hasUsableSession(with: account.accountId) { try peer.cipher.processBundle(try bundle()) }
        var message = BRPCommunityMessage()
        message.communityID = state.id.bytes; message.revision = revision ?? state.revision
        message.messageID = RandomId.make(); message.sentAt = 1_000; message.text = text
        var content = BRPContent(); content.body = .community(message)
        let sealed = try peer.cipher.seal(content, for: account.accountId)
        relay.deliver(.init(id: envelopeId, communityId: state.id, revision: revision ?? state.revision, serverTimestamp: 1, envelope: sealed))
        return envelopeId
    }

    /// `peer` seals any community message to this phone and the relay queues it.
    @discardableResult
    func deliver(_ message: BRPCommunityMessage, from peer: CommunityPeer, in state: CommunityState, envelopeId: String = UUID().uuidString) throws -> String {
        if try !peer.cipher.hasUsableSession(with: account.accountId) { try peer.cipher.processBundle(try bundle()) }
        var content = BRPContent(); content.body = .community(message)
        let sealed = try peer.cipher.seal(content, for: account.accountId)
        relay.deliver(.init(id: envelopeId, communityId: state.id, revision: message.revision, serverTimestamp: 1, envelope: sealed))
        return envelopeId
    }

    private var openedCount: [AccountId: Int] = [:]

    /// What this phone sent to `peer` since the last call, decrypted by
    /// `peer` (each envelope once: the ratchet moves on).
    func opened(by peer: CommunityPeer) throws -> [BRPCommunityMessage] {
        let all = relay.sent.filter { $0.recipient == peer.account }
        let start = openedCount[peer.account] ?? 0
        openedCount[peer.account] = all.count
        return try all[start...].map {
            try peer.cipher.decrypt(try SealedEnvelope.open($0.envelope, with: peer.identity)).content.community
        }
    }

    func texts(_ id: AccountId) throws -> [String] { try storage.read { try $0.communities.messages(id) }.map(\.text) }
    func status(_ id: AccountId) throws -> [CommunityDeliveryStatus] { try storage.read { try $0.communities.messages(id) }.filter(\.outgoing).map(\.status) }
    func pending() throws -> [CommunityOutboxItem] { try storage.read { try $0.communities.pending() } }
}

// MARK: Tests

@Suite("Communities: engine sync, flush and membership against a fake relay")
struct CommunityEngineTests {
    @Test("a verified message is stored and acknowledged without a personal contact")
    func syncStoresAndAcknowledges() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey)
        let state = try fixture.community(owner: alice, members: [fixture.me])
        let ids = try (0..<3).map { try fixture.receive("merhaba \($0)", from: alice, in: state) }
        let report = try await fixture.engine.sync()
        #expect(report.issue == nil)
        #expect(try fixture.texts(state.id) == ["merhaba 0", "merhaba 1", "merhaba 2"])
        #expect(fixture.relay.acked == ids)
        #expect(try fixture.storage.read { try $0.contacts.all() }.isEmpty)
        // The roster came with /mine: no extra fetch for three envelopes.
        #expect(fixture.relay.count("GET /v1/communities/\(state.id.string)") == 0)
    }

    @Test("each community's state is fetched and verified at most once per pass")
    func oneStateFetchPerPass() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey)
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        let state = try fixture.ownCommunity(members: [alice.member])
        for index in 0..<3 { _ = try await fixture.engine.send("bir \(index)", to: state.id) }
        let fetchesAfterSends = fixture.relay.count("GET /v1/communities/\(state.id.string)")
        #expect(await fixture.engine.flushNow() == nil)
        #expect(fixture.relay.count("GET /v1/communities/\(state.id.string)") == fetchesAfterSends + 1)
        #expect(fixture.relay.sent.count == 3)
        #expect(try fixture.status(state.id) == [.sent, .sent, .sent])

        // Removed from a community: three envelopes cost one 404, all acknowledged.
        let carol = CommunityPeer()
        fixture.relay.register(carol.account, identityKey: carol.member.identityKey)
        let ended = try fixture.community(owner: carol, members: [fixture.me])
        _ = try await fixture.engine.sync()
        let queued = try (0..<3).map { try fixture.receive("geç \($0)", from: carol, in: ended) }
        fixture.relay.setState(nil, for: ended.id)
        _ = try await fixture.engine.sync()
        #expect(fixture.relay.count("GET /v1/communities/\(ended.id.string)") <= 1)
        #expect(Set(fixture.relay.acked).isSuperset(of: queued))
        #expect(try fixture.texts(ended.id).isEmpty)
        #expect(try fixture.storage.read { try $0.communities.get(ended.id)?.isActive } == false)
    }

    @Test("a malformed envelope is acknowledged and dropped, not retried")
    func malformedEnvelopeIsAcknowledged() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey)
        let state = try fixture.community(owner: alice, members: [fixture.me])
        fixture.relay.deliver(.init(id: "garbage", communityId: state.id, revision: state.revision, serverTimestamp: 1, envelope: Data(repeating: 0xAB, count: 200)))
        _ = try await fixture.engine.sync()
        #expect(fixture.relay.acked == ["garbage"])
        #expect(fixture.relay.inbox.isEmpty)
        #expect(try fixture.texts(state.id).isEmpty)
    }

    @Test("old revisions, removed senders and unknown roster keys are dropped; a failed lookup is retried")
    func staleRemovedAndRetry() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), carol = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey)
        fixture.relay.register(carol.account, identityKey: carol.member.identityKey)
        let state = try fixture.community(owner: alice, members: [fixture.me], revision: 2)
        let stale = try fixture.receive("eski", from: alice, in: state, revision: 1)
        let outsider = try fixture.receive("yabancı", from: carol, in: state)
        _ = try await fixture.engine.sync()
        #expect(Set(fixture.relay.acked) == [stale, outsider])
        #expect(try fixture.texts(state.id).isEmpty)

        // The directory lookup fails: kept on the relay, stored once it works.
        fixture.relay.setIdentityStatus(503)
        let later = try fixture.receive("sonra", from: alice, in: state)
        _ = try? await fixture.engine.sync()
        #expect(!fixture.relay.acked.contains(later))
        fixture.relay.setIdentityStatus(200)
        _ = try await fixture.engine.sync()
        #expect(fixture.relay.acked.contains(later))
        #expect(try fixture.texts(state.id) == ["sonra"])
    }

    @Test("one failing recipient does not cancel the message for the others; 429 is retried")
    func perRecipientFlush() async throws {
        let fixture = try CommunityFixture()
        let peers = (0..<3).map { _ in CommunityPeer() }
        for peer in peers { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.ownCommunity(members: peers.map(\.member))
        let (ok, gone, full) = (peers[0], peers[1], peers[2])
        fixture.relay.setSendStatus(404, for: gone.account)
        fixture.relay.setSendStatus(429, for: full.account)
        #expect(try await fixture.engine.send("herkese", to: state.id).isEmpty)
        #expect(await fixture.engine.flushNow() == .rateLimited)
        #expect(fixture.relay.sent.map(\.recipient) == [ok.account])
        #expect(try fixture.pending().map(\.recipient) == [full.account])
        // What reached a member decrypts to the community message.
        let sealed = try SealedEnvelope.open(try #require(fixture.relay.sent.first).envelope, with: ok.identity)
        #expect(try ok.cipher.decrypt(sealed).content.community.text == "herkese")
        fixture.relay.setSendStatus(nil, for: full.account)
        #expect(await fixture.engine.flushNow() == nil)
        #expect(try fixture.pending().isEmpty)
        #expect(try fixture.status(state.id) == [.partial])
    }

    @Test("409 stops the whole message; the outbox never blocks the inbox")
    func conflictAndInboxIndependence() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), dave = CommunityPeer()
        for peer in [alice, dave] { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.ownCommunity(members: [alice.member, dave.member])
        fixture.relay.setSendStatus(409, for: alice.account)
        _ = try await fixture.engine.send("çakışma", to: state.id)
        #expect(await fixture.engine.flushNow() == .stale)
        #expect(try fixture.pending().isEmpty)
        #expect(try fixture.status(state.id) == [.failed])

        // The relay fails every send; the inbox is still fetched and stored.
        fixture.relay.setSendStatus(503, for: alice.account)
        fixture.relay.setSendStatus(503, for: dave.account)
        _ = try await fixture.engine.send("bekliyor", to: state.id)
        try fixture.receive("gelen", from: alice, in: state)
        let report = try await fixture.engine.sync()
        #expect(report.issue == .offline)
        #expect(try fixture.texts(state.id).contains("gelen"))
        // The text to both members, and alice's DELIVERY for what she sent (v2).
        #expect(try fixture.pending().count == 3)
    }

    @Test("a member's key that differs from the roster is skipped and named; the message fails for them")
    func keyMismatchNamesTheMember() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), mallory = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        // The relay hands out another key than the one the owner signed.
        let substitute = CommunityPeer()
        fixture.relay.register(mallory.account, identityKey: mallory.member.identityKey, bundle: try CommunityPeer.bundle(account: mallory.account, store: substitute.store))
        let state = try fixture.ownCommunity(members: [alice.member, mallory.member])
        #expect(try await fixture.engine.send("dikkat", to: state.id) == [mallory.account])
        #expect(try fixture.pending().map(\.recipient) == [alice.account])
        // “Tekrar gönder” is offered: the others still get it.
        #expect(try fixture.status(state.id) == [.failed])
    }

    @Test("leave, withdraw and forget")
    func leaveWithdrawForget() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey)
        let joined = try fixture.community(owner: alice, members: [fixture.me])
        _ = try await fixture.engine.sync()
        #expect(try fixture.storage.read { try $0.communities.get(joined.id)?.isActive } == true)
        await #expect(throws: (any Error).self) { try await fixture.engine.forget(joined.id) }
        try await fixture.engine.leave(joined.id)
        #expect(fixture.relay.requests.contains("DELETE /v1/communities/\(joined.id.string)/members/me"))
        #expect(try fixture.storage.read { try $0.communities.get(joined.id) } == nil)

        let pending = try fixture.community(owner: alice, members: [], pin: true)
        try await fixture.engine.withdraw(pending.id)
        #expect(fixture.relay.requests.contains("DELETE /v1/communities/\(pending.id.string)/requests/me"))
        #expect(try fixture.storage.read { try $0.communities.get(pending.id) } == nil)

        let owned = try fixture.ownCommunity(members: [alice.member])
        await #expect(throws: CommunityIssue.ownerCannotLeave) { try await fixture.engine.leave(owned.id) }
        #expect(try fixture.storage.read { try $0.communities.get(owned.id) } != nil)

        // Removed meanwhile: leaving still forgets it here.
        let removed = try fixture.community(owner: alice, members: [fixture.me])
        _ = try await fixture.engine.sync()
        fixture.relay.setLeaveStatus(404)
        try await fixture.engine.leave(removed.id)
        #expect(try fixture.storage.read { try $0.communities.get(removed.id) } == nil)
    }

    @Test("an invitation pins its owner key; another owner key is refused")
    func mismatchedInvitePin() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), impostor = CommunityPeer()
        let id = AccountId(uuid: UUID())
        let invite = CommunityInvitation(communityId: id, token: Data(repeating: 4, count: 32), ownerIdentityKey: alice.member.identityKey, name: "Aile 👨‍👩‍👧")
        fixture.relay.setRedeemSummary(
            #"{"id":"\#(id.string)","name":"","visibility":"private","ownerAccountId":"\#(impostor.account.string)","ownerIdentityKey":"\#(impostor.member.identityKey.base64URLEncodedString())","memberCount":1,"revision":1}"#,
            for: id)
        await #expect(throws: CommunityError.identityMismatch) { try await fixture.engine.redeem(try invite.code) }
        // The pin stays on the invitation's key: a roster signed by another owner is never accepted.
        let forged = try CommunityState(id: id, revision: 2, isPublic: false, owner: impostor.member, name: "", members: [impostor.member, fixture.me])
        fixture.relay.setState(SignedCommunityState(state: forged.bytes, signature: impostor.identity.privateKey.generateSignature(message: forged.bytes)), for: id)
        let report = try await fixture.engine.sync()
        #expect(report.issue == .verification)
        let stored = try #require(try fixture.storage.read { try $0.communities.get(id) })
        #expect(stored.signedState == nil && stored.ownerKey == alice.member.identityKey && stored.name == "Aile 👨‍👩‍👧")
    }

    @Test("a sender's new key learned from a community message is reported, not committed silently")
    func identityChangeIsReported() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey)
        // This phone still holds alice's old key (e.g. from a direct chat).
        _ = try fixture.store.saveIdentity(IdentityKeyPair.generate().identityKey, for: alice.account.protocolAddress, context: NullContext())
        let state = try fixture.community(owner: alice, members: [fixture.me])
        try fixture.receive("yeni anahtar", from: alice, in: state)
        let report = try await fixture.engine.sync()
        #expect(report.identityChanges == [alice.account])
        #expect(try fixture.texts(state.id) == ["yeni anahtar"])
    }

    @Test("a 1:1 message between decryption and storage keeps its staged ratchet; the community receive waits")
    func directAndCommunityReceivesAreIsolated() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey)
        let state = try fixture.community(owner: alice, members: [fixture.me])
        _ = try await fixture.engine.sync()
        try alice.cipher.processBundle(try fixture.bundle())
        var direct = BRPContent()
        direct.body = .data(BRPDataMessage(payload: .text({ var text = BRPText(); text.body = "bire bir"; return text }()), messageId: RandomId.make()))
        fixture.relay.deliverDirect(.init(id: "direct-1", serverTimestamp: 1, envelope: try alice.cipher.seal(direct, for: fixture.account.accountId)))
        try fixture.receive("topluluk", from: alice, in: state)

        let decrypted = AsyncStream<Void>.makeStream()
        let release = AsyncStream<Void>.makeStream()
        let storage = fixture.storage, store = fixture.store, messenger = fixture.messenger
        let directReceive = Task {
            try await store.withStagedInboundWrites {
                try await messenger.receivePending { _ in
                    decrypted.continuation.yield()
                    for await _ in release.stream { break }
                    // The 1:1 message is stored (here: its envelope id) with its ratchet.
                    try storage.write { try $0.envelopes.record("direct-1", at: Date()) }
                }
            }
        }
        for await _ in decrypted.stream { break }
        #expect(store.hasStagedWrites)
        let communityReceive = Task { try await fixture.engine.sync() }
        await settle(50)
        // The community path waits for the 1:1 turn: nothing of either is committed yet.
        #expect(store.hasStagedWrites)
        #expect(try fixture.texts(state.id).isEmpty)
        release.continuation.yield()
        _ = try await directReceive.value
        _ = try await communityReceive.value
        #expect(!store.hasStagedWrites)
        #expect(try fixture.texts(state.id) == ["topluluk"])
        #expect(try storage.read { try $0.envelopes.contains("direct-1") })
    }

    @Test("names and invitations follow the shared rule")
    func createAndInviteNames() async throws {
        let fixture = try CommunityFixture()
        await #expect(throws: CommunityError.invalidName) { try await fixture.engine.create(name: "  \u{202E}  ", isPublic: true) }
        try await fixture.engine.create(name: "  Aile 👨‍👩‍👧 ", isPublic: true)
        let created = try #require(try fixture.storage.read { try $0.communities.all() }.first)
        #expect(created.name == "Aile 👨‍👩‍👧" && created.isActive)
    }
}

@Suite("Communities: polling and identity notices")
@MainActor
struct CommunityServiceTests {
    @Test("no polling without communities; a visible screen polls")
    func pollsOnlyWhenNeeded() async throws {
        let fixture = try CommunityFixture()
        let service = CommunityService(backend: fixture.backend, storage: fixture.storage, pollInterval: .milliseconds(20))
        service.start()
        defer { service.stop() }
        try await Task.sleep(for: .milliseconds(200))
        #expect(fixture.relay.count("GET /v1/communities/mine") == 0)
        #expect(fixture.relay.count("GET /v1/communities/inbox") == 0)
        service.screenAppeared()
        try await eventually("polled") { fixture.relay.count("GET /v1/communities/mine") > 0 }
        service.screenDisappeared()
    }

    @Test("an identity change from a community message reaches the 1:1 engine")
    func identityChangeCallback() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey)
        _ = try fixture.store.saveIdentity(IdentityKeyPair.generate().identityKey, for: alice.account.protocolAddress, context: NullContext())
        let state = try fixture.community(owner: alice, members: [fixture.me])
        try fixture.receive("merhaba", from: alice, in: state)
        let changed = LockedState<[AccountId]>([])
        let service = CommunityService(backend: fixture.backend, storage: fixture.storage) { peer in changed.withLock { $0.append(peer) } }
        await service.refresh()
        #expect(changed.withLock { $0 } == [alice.account])
        service.stop()
    }

    @Test("the engine shows the 1:1 safety-number notice for a change learned elsewhere")
    func engineNoticeForCommunityIdentityChange() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let peer = try fixture.acceptedContact()
        await fixture.engine.noteIdentityChange(of: peer)
        let items = try await fixture.messages(peer)
        #expect(items.contains { $0.body == .notice(.safetyNumberChanged) })
        await fixture.engine.stop()
    }
}
