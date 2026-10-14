import CryptoKit
import Foundation

@testable import Us2usKit

/// An in-memory relay implementing the HTTP API of PROTOCOL.md §4 closely
/// enough to drive ``Messenger`` end to end, and strict about the request
/// shapes the spec mandates (so client mistakes fail tests).
final class FakeRelay: Sendable {
    struct Account {
        let id: AccountId
        var tokenHash: Data
        var usernameHash: Data
        var identityKey: Data
        var registrationId: UInt32
        var signedPreKey: SignedPreKeyEntity
        var kyberLastResort: SignedPreKeyEntity
        var oneTime: [PreKeyEntity] = []
        var kyberOneTime: [SignedPreKeyEntity] = []
        var accessKeyHash: Data?
        var mailbox: [RelayEnvelope] = []
    }

    private struct State {
        var accounts: [AccountId: Account] = [:]
        var usedPowTokens: Set<String> = []
        var media: [Data: (blob: Data, deleteToken: Data)] = [:]
        var requests: [RecordedRequest] = []
        var clock: Int64 = 1_760_000_000_000
        /// Outages for the next `(method, path)` requests, used up in order:
        /// a 500, no answer at all, or an answer lost after the request was carried out.
        var injections: [(method: String, path: String, kind: Injection)] = []
        /// Accounts treated as having an open WebSocket.
        var online: Set<AccountId> = []
        /// Ephemeral envelopes "forwarded over the WebSocket" to online accounts.
        var live: [AccountId: [RelayEnvelope]] = [:]
        /// Avatar records (§4.12): the highest version and the stored record.
        var avatars: [AccountId: AvatarState] = [:]
        /// `false`: a relay that predates avatar records (their paths are 404).
        var avatarsSupported = true
    }

    struct AvatarState: Equatable {
        var highest: UInt64 = 0
        var record: Data?
    }

    let powDifficulty: Int
    let baseURL: URL
    private let state = Locked(State())
    private let configuration: Locked<URLSessionConfiguration>

    init(powDifficulty: Int = 4) {
        self.powDifficulty = powDifficulty
        let endpoint = StubURLProtocol.makeEndpoint()
        self.baseURL = endpoint.baseURL
        self.configuration = Locked(endpoint.configuration)
        StubURLProtocol.setHandler(
            { [weak self] request in self?.handle(request) ?? (503, [:], Data()) }, for: endpoint.baseURL)
    }

    func makeClient() -> RelayClient {
        RelayClient(baseURL: baseURL, configuration: configuration.withLock { $0 })
    }

    var requests: [RecordedRequest] { state.withLock { $0.requests } }
    func account(_ id: AccountId) -> Account? { state.withLock { $0.accounts[id] } }
    func mediaCount() -> Int { state.withLock { $0.media.count } }

    /// Treats `id` as connected, so ephemeral envelopes reach it (see ``liveDeliveries(for:)``).
    func setOnline(_ id: AccountId) {
        state.withLock { _ = $0.online.insert(id) }
    }

    /// Ephemeral envelopes the relay would have forwarded live to `id`.
    func liveDeliveries(for id: AccountId) -> [RelayEnvelope] {
        state.withLock { $0.live[id] ?? [] }
    }

    /// Makes the next `method path` request fail with a 500.
    func failNext(_ method: String, _ path: String) {
        state.withLock { $0.injections.append((method, path, .serverError)) }
    }

    /// The next `method path` request gets no answer and never reaches the relay.
    func dropNext(_ method: String, _ path: String) {
        state.withLock { $0.injections.append((method, path, .noAnswer)) }
    }

    /// The next `method path` request is carried out, but its answer is lost
    /// (the connection drops before it arrives).
    func loseNextAnswer(_ method: String, _ path: String) {
        state.withLock { $0.injections.append((method, path, .lostAnswer)) }
    }

    /// The next `method path` request gets `429 rate_limited` with
    /// `Retry-After: seconds` and is not carried out.
    func rateLimitNext(_ method: String, _ path: String, retryAfter seconds: Int) {
        state.withLock { $0.injections.append((method, path, .rateLimited(seconds))) }
    }

    /// The avatar state of an account (§4.12), `nil` before its first write.
    func avatar(_ id: AccountId) -> AvatarState? { state.withLock { $0.avatars[id] } }

    /// Sets an account's avatar state directly (e.g. what an old phone left).
    func setAvatar(_ avatar: AvatarState?, for id: AccountId) {
        state.withLock { $0.avatars[id] = avatar }
    }

    /// Turns the relay into one without avatar records (`404` on their paths).
    func disableAvatars() {
        state.withLock { $0.avatarsSupported = false }
    }

    /// Requests to the avatar paths so far.
    var avatarRequests: [RecordedRequest] { requests.filter { $0.path.hasSuffix("/avatar") } }

    /// Rotations (`POST /v1/accounts/me/token`) so far, with the token each was authenticated with.
    var tokenRotations: [Data] {
        requests.filter { $0.method == "POST" && $0.path == "/v1/accounts/me/token" }.compactMap {
            $0.header("X-Auth-Token").flatMap(Data.init(base64URLEncoded:))
        }
    }

    /// Changes an account's server-side state directly.
    func update(_ id: AccountId, _ change: (inout Account) -> Void) {
        state.withLock {
            if var account = $0.accounts[id] {
                change(&account)
                $0.accounts[id] = account
            }
        }
    }

    // MARK: Routing

    private typealias Reply = (status: Int, headers: [String: String], body: Data)

    /// `method` + path below `/v1/`, matched against patterns like `accounts/*/identity`.
    private struct Route {
        let method: String
        let segments: [String]

        /// The `*` segments if the request matches, else `nil`.
        func match(_ method: String, _ pattern: String) -> [String]? {
            let parts = pattern.split(separator: "/").map(String.init)
            guard method == self.method, parts.count == segments.count else { return nil }
            var captures: [String] = []
            for (part, segment) in zip(parts, segments) {
                if part == "*" {
                    captures.append(segment)
                } else if part != segment {
                    return nil
                }
            }
            return captures
        }
    }

    fileprivate enum Injection {
        case serverError, noAnswer, lostAnswer
        case rateLimited(Int)
    }

    private func handle(_ request: RecordedRequest) -> Reply {
        let injection = state.withLock { state -> Injection? in
            state.requests.append(request)
            guard
                let index = state.injections.firstIndex(where: {
                    $0.method == request.method && $0.path == request.path
                })
            else { return nil }
            return state.injections.remove(at: index).kind
        }
        switch injection {
        case .serverError?:
            return error(500, "internal")
        case .noAnswer?:
            return StubURLProtocol.noAnswer
        case .lostAnswer?:
            _ = respond(request)
            return StubURLProtocol.noAnswer
        case .rateLimited(let seconds)?:
            var reply = error(429, "rate_limited")
            reply.headers["Retry-After"] = String(seconds)
            return reply
        case nil:
            return respond(request)
        }
    }

    private func respond(_ request: RecordedRequest) -> Reply {
        if request.header("Authorization") != nil {
            return error(400, "bad_request", "Authorization header must not be used")
        }
        let parts = request.url.path.split(separator: "/").map(String.init)
        guard parts.first == "v1" else { return error(404, "not_found") }
        let route = Route(method: request.method, segments: Array(parts.dropFirst()))
        let query = URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func queryValue(_ name: String) -> String? { query.first { $0.name == name }?.value }

        // Unauthenticated endpoints.
        if route.match("GET", "health") != nil {
            return json(200, ["ok": true, "version": "0.0.0-test"])
        }
        if route.match("GET", "pow") != nil {
            return pow(purpose: queryValue("purpose"), to: queryValue("to"))
        }
        if route.match("POST", "accounts") != nil {
            return register(request)
        }
        if route.match("POST", "messages/ephemeral") != nil {
            return sendEphemeralBatch(request)
        }
        if let captured = route.match("PUT", "messages/*") {
            return sendMessage(request, to: captured[0], ephemeral: queryValue("ephemeral") == "1")
        }
        if let captured = route.match("GET", "media/*") {
            return downloadMedia(captured[0])
        }
        if let captured = route.match("DELETE", "media/*") {
            return deleteMedia(captured[0], token: request.header("X-Delete-Token"))
        }

        guard let token = request.header("X-Auth-Token").flatMap(Data.init(base64URLEncoded:)),
            let me = state.withLock({ state in state.accounts.values.first { $0.tokenHash == token.sha256Digest }?.id })
        else { return error(401, "unauthorized") }

        if route.match("GET", "accounts/me") != nil {
            let account = self.account(me)!
            return json(200, ["accountId": me.string, "usernameHash": account.usernameHash.base64URLEncodedString()])
        }
        if route.match("PUT", "accounts/me/username") != nil {
            guard let hash = (try? request.json())?["usernameHash"] as? String, let data = Data(base64URLEncoded: hash)
            else { return error(400, "bad_request") }
            if state.withLock({ $0.accounts.values.contains { $0.usernameHash == data && $0.id != me } }) {
                return error(409, "username_taken")
            }
            update(me) { $0.usernameHash = data }
            // §4.3: the avatar record goes with the old name; the version stays.
            state.withLock { $0.avatars[me]?.record = nil }
            return (204, [:], Data())
        }
        if route.match("PUT", "accounts/me/avatar") != nil {
            return putAvatar(request, for: me)
        }
        if route.match("DELETE", "accounts/me/avatar") != nil {
            return deleteAvatar(request, for: me)
        }
        if let captured = route.match("GET", "accounts/*/avatar") {
            return getAvatar(captured[0], request)
        }
        if route.match("POST", "accounts/me/token") != nil {
            return rotateToken(request, for: me)
        }
        if route.match("PUT", "accounts/me/access-key") != nil {
            guard let body = try? request.json(), body.keys.contains("accessKeyHash") else {
                return error(400, "bad_request")
            }
            let hash = (body["accessKeyHash"] as? String).flatMap(Data.init(base64URLEncoded:))
            if body["accessKeyHash"] is String, hash?.count != 32 { return error(400, "bad_request") }
            update(me) { $0.accessKeyHash = hash }
            return (204, [:], Data())
        }
        if route.match("DELETE", "accounts/me") != nil {
            state.withLock {
                $0.accounts[me] = nil
                $0.avatars[me] = nil  // record and version (§4.3)
            }
            return (204, [:], Data())
        }
        if let captured = route.match("GET", "accounts/*/identity") {
            guard let id = AccountId(string: captured[0]), let account = account(id) else {
                return error(404, "not_found")
            }
            return json(200, ["accountId": id.string, "identityKey": account.identityKey.base64URLEncodedString()])
        }
        if let captured = route.match("GET", "directory/*") {
            guard let hash = Data(base64URLEncoded: captured[0]),
                let account = state.withLock({ $0.accounts.values.first { $0.usernameHash == hash } })
            else { return error(404, "not_found") }
            return json(
                200, ["accountId": account.id.string, "identityKey": account.identityKey.base64URLEncodedString()])
        }
        if route.match("PUT", "keys") != nil {
            return uploadKeys(request, for: me)
        }
        if route.match("GET", "keys/status") != nil {
            let account = self.account(me)!
            return json(
                200,
                [
                    "oneTimePreKeys": account.oneTime.count, "kyberOneTimePreKeys": account.kyberOneTime.count,
                    "signedPreKeyId": account.signedPreKey.keyId, "kyberLastResortKeyId": account.kyberLastResort.keyId,
                ])
        }
        if let captured = route.match("GET", "keys/*") {
            return bundle(for: captured[0])
        }
        if route.match("GET", "messages") != nil {
            let limit = Int(queryValue("limit") ?? "100") ?? 100
            let mailbox = account(me)!.mailbox
            let page = Array(mailbox.prefix(limit))
            return encoded(200, MessagesPage(messages: page, more: mailbox.count > page.count))
        }
        if route.match("POST", "messages/ack") != nil {
            guard let ids = (try? request.json())?["ids"] as? [String], ids.count <= 500 else {
                return error(400, "bad_request")
            }
            update(me) { $0.mailbox.removeAll { ids.contains($0.id) } }
            return (204, [:], Data())
        }
        if route.match("POST", "media") != nil {
            let mediaId = SecureRandom.bytes(16)
            let deleteToken = SecureRandom.bytes(32)
            state.withLock { $0.media[mediaId] = (request.body, deleteToken) }
            return json(
                201,
                [
                    "mediaId": mediaId.base64URLEncodedString(), "deleteToken": deleteToken.base64URLEncodedString(),
                    "expiresAt": 1_761_209_600_000,
                ])
        }
        return error(404, "not_found")
    }

    // MARK: Endpoints

    private func pow(purpose: String?, to: String?) -> Reply {
        switch purpose {
        case "register": break
        case "message": guard to.flatMap(AccountId.init(string:)) != nil else { return error(400, "bad_request") }
        default: return error(400, "bad_request")
        }
        let token = "fake.\(purpose!).\(to ?? "-").\(UUID().uuidString)"
        return json(200, ["token": token, "difficulty": powDifficulty, "expiresAt": 1_760_000_600_000])
    }

    private func checkPow(token: String, nonce: String, purpose: String, target: String) -> Bool {
        let parts = token.split(separator: ".").map(String.init)
        guard parts.count == 4, parts[0] == "fake", parts[1] == purpose, parts[2] == target,
            let nonce = UInt64(nonce), ProofOfWork.isValid(token: token, nonce: nonce, difficulty: powDifficulty)
        else { return false }
        return state.withLock { $0.usedPowTokens.insert(token).inserted }
    }

    private func register(_ request: RecordedRequest) -> Reply {
        guard let registration = try? JSONDecoder.relay.decode(RegistrationRequest.self, from: request.body) else {
            return error(400, "bad_request")
        }
        guard checkPow(token: registration.pow.token, nonce: registration.pow.nonce, purpose: "register", target: "-")
        else { return error(403, "pow_invalid") }
        let keyIds: [UInt32] =
            [registration.signedPreKey.keyId, registration.kyberLastResortKey.keyId]
            + registration.oneTimePreKeys.map(\.keyId) + registration.kyberOneTimePreKeys.map(\.keyId)
        guard registration.usernameHash.count == 32, registration.identityKey.count == 33,
            registration.identityKey.first == 0x05, (1...16380).contains(registration.registrationId),
            keyIds.allSatisfy({ (1...16_777_215).contains($0) }),
            registration.oneTimePreKeys.count <= 100, registration.kyberOneTimePreKeys.count <= 100,
            registration.signedPreKey.signature.count == 64, registration.kyberLastResortKey.signature.count == 64,
            registration.kyberOneTimePreKeys.allSatisfy({ $0.signature.count == 64 })
        else { return error(400, "bad_request") }
        if state.withLock({ $0.accounts.values.contains { $0.usernameHash == registration.usernameHash } }) {
            return error(409, "username_taken")
        }
        let id = AccountId(uuid: UUID())
        let token = SecureRandom.bytes(32)
        let account = Account(
            id: id, tokenHash: token.sha256Digest, usernameHash: registration.usernameHash,
            identityKey: registration.identityKey, registrationId: registration.registrationId,
            signedPreKey: registration.signedPreKey, kyberLastResort: registration.kyberLastResortKey,
            oneTime: registration.oneTimePreKeys, kyberOneTime: registration.kyberOneTimePreKeys)
        state.withLock { $0.accounts[id] = account }
        return json(201, ["accountId": id.string, "authToken": token.base64URLEncodedString()])
    }

    private func uploadKeys(_ request: RecordedRequest, for me: AccountId) -> Reply {
        guard let upload = try? JSONDecoder.relay.decode(KeyUploadRequest.self, from: request.body) else {
            return error(400, "bad_request")
        }
        update(me) { account in
            if upload.replaceAll == true {
                account.oneTime = []
                account.kyberOneTime = []
            }
            if let signed = upload.signedPreKey { account.signedPreKey = signed }
            if let lastResort = upload.kyberLastResortKey { account.kyberLastResort = lastResort }
            for key in upload.oneTimePreKeys ?? [] where !account.oneTime.contains(where: { $0.keyId == key.keyId }) {
                account.oneTime.append(key)
            }
            for key in upload.kyberOneTimePreKeys ?? []
            where !account.kyberOneTime.contains(where: { $0.keyId == key.keyId }) {
                account.kyberOneTime.append(key)
            }
        }
        return (204, [:], Data())
    }

    private func bundle(for id: String) -> Reply {
        guard let id = AccountId(string: id) else { return error(404, "not_found") }
        let bundle: PreKeyBundleResponse? = state.withLock { state in
            guard var account = state.accounts[id] else { return nil }
            let oneTime = account.oneTime.isEmpty ? nil : account.oneTime.removeFirst()
            let kyber = account.kyberOneTime.isEmpty ? account.kyberLastResort : account.kyberOneTime.removeFirst()
            state.accounts[id] = account
            return PreKeyBundleResponse(
                accountId: id, deviceId: 1, registrationId: account.registrationId, identityKey: account.identityKey,
                signedPreKey: account.signedPreKey, kyberPreKey: kyber, oneTimePreKey: oneTime)
        }
        guard let bundle else { return error(404, "not_found") }
        return encoded(200, bundle)
    }

    private func sendMessage(_ request: RecordedRequest, to recipient: String, ephemeral: Bool) -> Reply {
        // Sealed sender: the relay must not learn who sends (§4.6).
        guard request.header("X-Auth-Token") == nil else {
            return error(400, "bad_request", "sealed send carried a token")
        }
        guard request.header("Content-Type") == "application/octet-stream" else { return error(400, "bad_request") }
        guard request.body.count <= 64 * 1024 else { return error(413, "payload_too_large") }
        guard let recipient = AccountId(string: recipient), let account = account(recipient) else {
            return error(404, "not_found")
        }
        let id = SecureRandom.bytes(16).base64URLEncodedString()
        let timestamp = state.withLock { state -> Int64 in
            state.clock += 1
            return state.clock
        }
        let accepted = json(202, ["id": id, "serverTimestamp": timestamp])
        let accessKey = request.header("X-Access-Key").flatMap(Data.init(base64URLEncoded:))
        let keyMatches = accessKey.map { key in
            account.accessKeyHash.map { ConstantTime.equals(key.sha256Digest, $0) } ?? false
        }

        if ephemeral {
            // Access key only; and the answer never reveals whether anything was delivered.
            guard let keyMatches else { return error(403, "access_denied") }
            if keyMatches {
                deliverLive(RelayEnvelope(id: id, serverTimestamp: timestamp, envelope: request.body), to: recipient)
            }
            return accepted
        }
        if let keyMatches {
            guard keyMatches else { return error(403, "access_denied") }
        } else if let pow = request.header("X-PoW") {
            guard let split = pow.lastIndex(of: ":"),
                checkPow(
                    token: String(pow[..<split]), nonce: String(pow[pow.index(after: split)...]), purpose: "message",
                    target: recipient.string)
            else { return error(403, "pow_invalid") }
        } else {
            return error(403, "access_denied")
        }
        update(recipient) {
            $0.mailbox.append(RelayEnvelope(id: id, serverTimestamp: timestamp, envelope: request.body))
        }
        return accepted
    }

    private func sendEphemeralBatch(_ request: RecordedRequest) -> Reply {
        guard request.header("X-Auth-Token") == nil else {
            return error(400, "bad_request", "ephemeral batch carried a token")
        }
        guard request.body.count <= 1024 * 1024 else { return error(413, "payload_too_large") }
        guard let batch = try? JSONDecoder.relay.decode(EphemeralBatchRequest.self, from: request.body),
            (1...200).contains(batch.messages.count), batch.messages.allSatisfy({ $0.envelope.count <= 4096 })
        else { return error(400, "bad_request") }
        for message in batch.messages {
            guard let hash = account(message.to)?.accessKeyHash,
                ConstantTime.equals(message.accessKey.sha256Digest, hash)
            else { continue }
            let timestamp = state.withLock { state -> Int64 in
                state.clock += 1
                return state.clock
            }
            deliverLive(
                RelayEnvelope(
                    id: SecureRandom.bytes(16).base64URLEncodedString(), serverTimestamp: timestamp,
                    envelope: message.envelope),
                to: message.to)
        }
        return json(202, [:])
    }

    private func deliverLive(_ envelope: RelayEnvelope, to recipient: AccountId) {
        state.withLock { state in
            guard state.online.contains(recipient) else { return }
            state.live[recipient, default: []].append(envelope)
        }
    }

    private func downloadMedia(_ id: String) -> Reply {
        guard let id = Data(base64URLEncoded: id), let blob = state.withLock({ $0.media[id]?.blob }) else {
            return error(404, "not_found")
        }
        return (200, ["Content-Type": "application/octet-stream"], blob)
    }

    private func deleteMedia(_ id: String, token: String?) -> Reply {
        guard let id = Data(base64URLEncoded: id), let token = token.flatMap(Data.init(base64URLEncoded:)) else {
            return error(400, "bad_request")
        }
        return state.withLock { state in
            guard let entry = state.media[id] else { return error(404, "not_found") }
            guard ConstantTime.equals(entry.deleteToken, token) else { return error(403, "access_denied") }
            state.media[id] = nil
            return (204, [:], Data())
        }
    }

    /// §4.3: the client chooses the token. Only the token that authenticated
    /// the request is replaced; asking again with the new token is `204`
    /// without a change. The deprecated empty body is refused here, so a
    /// client that still sends it fails the tests.
    private func rotateToken(_ request: RecordedRequest, for me: AccountId) -> Reply {
        guard let encoded = (try? request.json())?["newAuthToken"] as? String,
            let token = Data(base64URLEncoded: encoded), token.count == 32, Set(token).count >= 16
        else { return error(400, "bad_request") }
        let hash = token.sha256Digest
        let taken = state.withLock { state in state.accounts.values.contains { $0.tokenHash == hash && $0.id != me } }
        if taken { return error(400, "bad_request") }
        update(me) { $0.tokenHash = hash }
        return (204, [:], Data())
    }

    // MARK: §4.12 Avatar records

    private static let avatarLengths: Set<Int> = [8192, 16384, 32768, 49152]

    /// A decimal version in 1 … 2^63 − 1.
    private static func avatarVersion(_ text: String?) -> UInt64? {
        guard let text, !text.isEmpty, text.utf8.allSatisfy({ (48...57).contains($0) }), let value = UInt64(text),
            value >= 1, value < 1 << 63
        else { return nil }
        return value
    }

    /// The version in a record's header, if the length and header are right.
    private static func recordVersion(_ body: Data) -> UInt64? {
        let body = Data(body)
        guard avatarLengths.contains(body.count), body.prefix(4) == Data("BRPA".utf8), body[4] == 0x01 else {
            return nil
        }
        let version = body[5..<13].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        return version >= 1 && version < 1 << 63 ? version : nil
    }

    private func staleVersion(_ highest: UInt64) -> Reply {
        var reply = error(409, "stale_version")
        reply.headers["X-Avatar-Version"] = String(highest)
        return reply
    }

    private func putAvatar(_ request: RecordedRequest, for me: AccountId) -> Reply {
        guard state.withLock({ $0.avatarsSupported }) else { return error(404, "not_found") }
        guard request.header("Content-Type") == "application/octet-stream" else { return error(400, "bad_request") }
        guard request.body.count <= 49152 else { return error(413, "payload_too_large") }
        guard let version = Self.recordVersion(request.body) else { return error(400, "bad_request") }
        return state.withLock { state -> Reply in
            var avatar = state.avatars[me] ?? AvatarState()
            guard version > avatar.highest else { return staleVersion(avatar.highest) }
            avatar.highest = version
            avatar.record = Data(request.body)
            state.avatars[me] = avatar
            return (204, ["ETag": "\"\(version)\""], Data())
        }
    }

    private func deleteAvatar(_ request: RecordedRequest, for me: AccountId) -> Reply {
        guard state.withLock({ $0.avatarsSupported }) else { return error(404, "not_found") }
        guard let version = Self.avatarVersion(request.header("X-Avatar-Version")) else {
            return error(400, "bad_request")
        }
        return state.withLock { state -> Reply in
            var avatar = state.avatars[me] ?? AvatarState()
            guard version > avatar.highest else { return staleVersion(avatar.highest) }
            avatar.highest = version
            avatar.record = nil
            state.avatars[me] = avatar
            return (204, [:], Data())
        }
    }

    private func getAvatar(_ id: String, _ request: RecordedRequest) -> Reply {
        guard state.withLock({ $0.avatarsSupported }) else { return error(404, "not_found") }
        guard let id = AccountId(string: id) else { return error(400, "bad_request") }
        // No record and no account: the same 404.
        guard let record = state.withLock({ $0.avatars[id]?.record }), let version = Self.recordVersion(record) else {
            return error(404, "not_found")
        }
        let etag = "\"\(version)\""
        if request.header("If-None-Match") == etag { return (304, ["ETag": etag], Data()) }
        return (200, ["Content-Type": "application/octet-stream", "ETag": etag, "Cache-Control": "no-store"], record)
    }

    // MARK: Responses

    private func json(_ status: Int, _ object: [String: Any]) -> Reply {
        (status, ["Content-Type": "application/json"], try! JSONSerialization.data(withJSONObject: object))
    }

    private func encoded(_ status: Int, _ value: some Encodable) -> Reply {
        (status, ["Content-Type": "application/json"], try! JSONEncoder.relay.encode(value))
    }

    private func error(_ status: Int, _ code: String, _ message: String = "error") -> Reply {
        json(status, ["error": ["code": code, "message": message]])
    }
}
