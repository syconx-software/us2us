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
        /// `(method, path)` pairs answered once with a 500, to simulate outages.
        var injectedFailures: [(method: String, path: String)] = []
        /// Accounts treated as having an open WebSocket.
        var online: Set<AccountId> = []
        /// Ephemeral envelopes "forwarded over the WebSocket" to online accounts.
        var live: [AccountId: [RelayEnvelope]] = [:]
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
        state.withLock { $0.injectedFailures.append((method, path)) }
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

    private func handle(_ request: RecordedRequest) -> Reply {
        let injected = state.withLock { state -> Bool in
            state.requests.append(request)
            guard let index = state.injectedFailures.firstIndex(where: { $0 == (request.method, request.path) }) else {
                return false
            }
            state.injectedFailures.remove(at: index)
            return true
        }
        if injected { return error(500, "internal") }
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
            return (204, [:], Data())
        }
        if route.match("POST", "accounts/me/token") != nil {
            let fresh = SecureRandom.bytes(32)
            update(me) { $0.tokenHash = fresh.sha256Digest }
            return json(200, ["authToken": fresh.base64URLEncodedString()])
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
            state.withLock { $0.accounts[me] = nil }
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
