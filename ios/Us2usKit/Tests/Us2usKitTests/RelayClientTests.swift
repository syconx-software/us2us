import Foundation
import Testing

@testable import Us2usKit

@Suite("Relay client (§4)")
struct RelayClientTests {
    /// Records every request and answers with a canned reply.
    final class Server: Sendable {
        typealias Reply = (status: Int, headers: [String: String], body: Data)
        private let recorded: Locked<[RecordedRequest]>
        let client: RelayClient

        init(credentials: RelayCredentials? = Server.credentials, reply: @escaping @Sendable (RecordedRequest) -> Reply)
        {
            let recorded = Locked<[RecordedRequest]>([])
            let endpoint = StubURLProtocol.register { request in
                recorded.withLock { $0.append(request) }
                return reply(request)
            }
            self.recorded = recorded
            client = RelayClient(
                baseURL: endpoint.baseURL, credentials: credentials, configuration: endpoint.configuration)
        }

        static let credentials = RelayCredentials(
            accountId: AccountId(string: "8f14e45f-ceea-467a-9f0b-6d3e2a1b4c5d")!,
            authToken: Data(repeating: 0xFB, count: 32))

        var requests: [RecordedRequest] { recorded.withLock { $0 } }
        var last: RecordedRequest { requests.last! }

        static func json(_ status: Int, _ text: String, headers: [String: String] = [:]) -> Reply {
            (status, headers.merging(["Content-Type": "application/json"]) { a, _ in a }, Data(text.utf8))
        }
    }

    @Test("authenticated calls send X-Auth-Token, never Authorization")
    func authHeader() async throws {
        let server = Server { _ in
            Server.json(200, #"{"accountId":"8f14e45f-ceea-467a-9f0b-6d3e2a1b4c5d","usernameHash":"AAAA"}"#)
        }
        let info = try await server.client.accountInfo()
        #expect(info.accountId == Server.credentials.accountId)
        #expect(server.last.path == "/v1/accounts/me")
        #expect(server.last.header("X-Auth-Token") == Server.credentials.authToken.base64URLEncodedString())
        #expect(server.last.header("Authorization") == nil)
    }

    @Test func notAuthenticatedFailsLocally() async {
        let server = Server(credentials: nil) { _ in Server.json(200, "{}") }
        await #expect(throws: RelayError.notAuthenticated) { try await server.client.keyStatus() }
        #expect(server.requests.isEmpty)
    }

    @Test("sealed sends are anonymous and carry the access key or PoW (§4.6)")
    func sealedSend() async throws {
        let server = Server { _ in Server.json(202, #"{"id":"AAAAAAAAAAAAAAAAAAAAAA","serverTimestamp":1760000000000}"#)
        }
        let recipient = AccountId(uuid: UUID())
        let envelope = Data([1, 2, 3])

        let result = try await server.client.sendMessage(envelope, to: recipient, authorization: .accessKey(.id(7)))
        #expect(result == SendMessageResponse(id: "AAAAAAAAAAAAAAAAAAAAAA", serverTimestamp: 1_760_000_000_000))
        var request = server.last
        #expect(request.method == "PUT")
        #expect(request.path == "/v1/messages/\(recipient.string)")
        #expect(request.header("X-Auth-Token") == nil)
        #expect(request.header("X-Access-Key") == Data.id(7).base64URLEncodedString())
        #expect(request.header("Content-Type") == "application/octet-stream")
        #expect(request.body == envelope)
        #expect(request.url.query == nil)

        _ = try await server.client.sendMessage(
            envelope, to: recipient, authorization: .proofOfWork(PowSolution(token: "tok.en", nonce: 42)))
        request = server.last
        #expect(request.header("X-PoW") == "tok.en:42")
        #expect(request.header("X-Access-Key") == nil)

        _ = try await server.client.sendMessage(
            envelope, to: recipient, authorization: .accessKey(.id(7)), ephemeral: true)
        #expect(server.last.url.query == "ephemeral=1")
    }

    @Test("ephemeral fan-out: anonymous, at most 200 items and 1 MiB per request (§4.6)")
    func ephemeralBatches() async throws {
        let server = Server { _ in Server.json(202, "{}") }
        let messages = (0..<450).map { index in
            EphemeralMessage(to: AccountId(uuid: UUID()), accessKey: .id(UInt8(index % 200)), envelope: .pattern(300))
        }
        try await server.client.sendEphemeral(messages)
        #expect(server.requests.count == 3)
        for request in server.requests {
            #expect(request.method == "POST")
            #expect(request.path == "/v1/messages/ephemeral")
            #expect(request.header("X-Auth-Token") == nil)
        }
        let first = try #require(try server.requests[0].json()["messages"] as? [[String: Any]])
        #expect(first.count == 200)
        #expect(first[0]["to"] as? String == messages[0].to.string)
        #expect(first[0]["accessKey"] as? String == messages[0].accessKey.base64URLEncodedString())
        #expect(first[0]["envelope"] as? String == messages[0].envelope.base64URLEncodedString())

        // 4 KiB envelopes: 200 of them would exceed 1 MiB, so batches are smaller.
        let large = (0..<200).map { _ in
            EphemeralMessage(to: AccountId(uuid: UUID()), accessKey: .id(1), envelope: .pattern(4096))
        }
        try await server.client.sendEphemeral(large)
        let bodies = server.requests.dropFirst(3).map(\.body.count)
        #expect(bodies.count == 2)
        #expect(bodies.allSatisfy { $0 <= 1024 * 1024 })

        await #expect(throws: RelayError.payloadTooLarge) {
            try await server.client.sendEphemeral([
                EphemeralMessage(to: AccountId(uuid: UUID()), accessKey: .id(1), envelope: .pattern(4097))
            ])
        }
        try await server.client.sendEphemeral([])
        #expect(server.requests.count == 5)
    }

    @Test("registration JSON uses the exact field names of §4.3")
    func registrationShape() async throws {
        let server = Server { _ in
            Server.json(
                201,
                #"{"accountId":"8f14e45f-ceea-467a-9f0b-6d3e2a1b4c5d","authToken":"\#(Data(count: 32).base64URLEncodedString())"}"#
            )
        }
        let entity = SignedPreKeyEntity(keyId: 1, publicKey: Data([5]), signature: Data([6]))
        let registration = RegistrationRequest(
            usernameHash: Data([1]), identityKey: Data([2]), registrationId: 1234, signedPreKey: entity,
            kyberLastResortKey: entity, oneTimePreKeys: [PreKeyEntity(keyId: 2, publicKey: Data([3]))],
            kyberOneTimePreKeys: [entity], pow: PowSolution(token: "t", nonce: 123))
        let credentials = try await server.client.register(registration)
        #expect(server.client.credentials == credentials)
        #expect(server.last.header("X-Auth-Token") == nil)

        let json = try server.last.json()
        #expect(
            Set(json.keys) == [
                "usernameHash", "identityKey", "registrationId", "signedPreKey", "kyberLastResortKey", "oneTimePreKeys",
                "kyberOneTimePreKeys", "pow",
            ])
        #expect(json["usernameHash"] as? String == "AQ")
        #expect((json["signedPreKey"] as? [String: Any]).map { Set($0.keys) } == ["keyId", "publicKey", "signature"])
        #expect((json["oneTimePreKeys"] as? [[String: Any]])?.first.map { Set($0.keys) } == ["keyId", "publicKey"])
        let pow = try #require(json["pow"] as? [String: Any])
        #expect(pow["nonce"] as? String == "123")
    }

    @Test("access key null is sent explicitly")
    func accessKeyNull() async throws {
        let server = Server { _ in (204, [:], Data()) }
        try await server.client.setAccessKeyHash(nil)
        #expect(String(decoding: server.last.body, as: UTF8.self) == #"{"accessKeyHash":null}"#)
        try await server.client.setAccessKeyHash(Data([0xFF]))
        #expect(String(decoding: server.last.body, as: UTF8.self) == #"{"accessKeyHash":"_w"}"#)
    }

    @Test("errors map to the §2.1 codes")
    func errors() async throws {
        let replies = Locked<[Server.Reply]>([
            Server.json(409, #"{"error":{"code":"username_taken","message":"taken"}}"#),
            Server.json(429, #"{"error":{"code":"rate_limited","message":"slow"}}"#, headers: ["Retry-After": "17"]),
            (500, [:], Data("oops".utf8)),
            Server.json(200, #"{"unexpected":true}"#),
            Server.json(403, #"{"error":{"code":"some_future_code","message":"?"}}"#),
        ])
        let server = Server { _ in replies.withLock { $0.removeFirst() } }
        await #expect(throws: RelayError.server(status: 409, code: .usernameTaken, message: "taken")) {
            try await server.client.changeUsername(hash: Data(count: 32))
        }
        await #expect(throws: RelayError.rateLimited(retryAfter: 17)) { try await server.client.keyStatus() }
        await #expect(throws: RelayError.server(status: 500, code: nil, message: nil)) {
            try await server.client.keyStatus()
        }
        await #expect(throws: RelayError.invalidResponse) { try await server.client.keyStatus() }
        await #expect(
            throws: RelayError.server(status: 403, code: RelayErrorCode(rawValue: "some_future_code"), message: "?")
        ) {
            try await server.client.keyStatus()
        }
    }

    @Test("404 on lookups means absent, not an error")
    func notFoundLookups() async throws {
        let server = Server { _ in Server.json(404, #"{"error":{"code":"not_found","message":"no"}}"#) }
        #expect(try await server.client.identity(of: AccountId(uuid: UUID())) == nil)
        #expect(try await server.client.lookup(usernameHash: Data([0xFB, 0xFF])) == nil)
        #expect(server.last.path == "/v1/directory/-_8")
    }

    @Test("acknowledgements are batched by 500")
    func ackBatching() async throws {
        let server = Server { _ in (204, [:], Data()) }
        try await server.client.acknowledge((0..<1200).map { "id\($0)" })
        let batches = try server.requests.map { try $0.json()["ids"] as? [String] ?? [] }
        #expect(batches.map(\.count) == [500, 500, 200])
        try await server.client.acknowledge([])
        #expect(server.requests.count == 3)
    }

    @Test func powChallengeQuery() async throws {
        let server = Server { _ in Server.json(200, #"{"token":"t","difficulty":18,"expiresAt":1760000000000}"#) }
        let recipient = AccountId(uuid: UUID())
        let challenge = try await server.client.powChallenge(for: .message(to: recipient))
        #expect(challenge == PowChallenge(token: "t", difficulty: 18, expiresAt: 1_760_000_000_000))
        #expect(server.last.url.query == "purpose=message&to=\(recipient.string)")
        #expect(server.last.header("X-Auth-Token") == nil)
        _ = try await server.client.powChallenge(for: .register)
        #expect(server.last.url.query == "purpose=register")
    }

    @Test("media: streamed upload, anonymous download and delete (§4.9)")
    func media() async throws {
        let blob = Data.pattern(200_000)
        let server = Server { request in
            switch request.method {
            case "POST":
                return Server.json(
                    201,
                    #"{"mediaId":"AAAAAAAAAAAAAAAAAAAAAA","deleteToken":"\#(Data(count: 32).base64URLEncodedString())","expiresAt":1}"#
                )
            case "GET": return (200, [:], blob)
            default: return (204, [:], Data())
            }
        }
        let directory = try TemporaryDirectory()
        try blob.write(to: directory.file("blob"))
        let upload = try await server.client.uploadMedia(fileAt: directory.file("blob"))
        #expect(upload.mediaId == Data(count: 16))
        #expect(server.last.body == blob)
        #expect(server.last.header("Content-Type") == "application/octet-stream")
        #expect(server.last.header("X-Auth-Token") != nil)

        let received = Locked<[UInt64]>([])
        try await server.client.downloadMedia(id: upload.mediaId, to: directory.file("downloaded")) { bytes in
            received.withLock { $0.append(bytes) }
        }
        #expect(try Data(contentsOf: directory.file("downloaded")) == blob)
        #expect(server.last.header("X-Auth-Token") == nil)
        // Progress after every 64 KiB chunk and at the end, never backwards.
        let progress = received.withLock { $0 }
        #expect(progress.last == UInt64(blob.count))
        #expect(progress.count == blob.count / (64 * 1024) + 1)
        #expect(progress == progress.sorted())
        // A blob longer than the caller expects is cut off and nothing is left behind (§7).
        try await server.client.downloadMedia(
            id: upload.mediaId, to: directory.file("exact"), maxBytes: UInt64(blob.count))
        await #expect(throws: RelayError.payloadTooLarge) {
            try await server.client.downloadMedia(
                id: upload.mediaId, to: directory.file("short"), maxBytes: UInt64(blob.count - 1))
        }
        #expect(!FileManager.default.fileExists(atPath: directory.file("short").path))
        #expect(try FileManager.default.contentsOfDirectory(atPath: directory.url.path).sorted() == ["blob", "downloaded", "exact"])

        try await server.client.deleteMedia(id: upload.mediaId, deleteToken: upload.deleteToken)
        #expect(server.last.method == "DELETE")
        #expect(server.last.header("X-Delete-Token") == upload.deleteToken.base64URLEncodedString())
        #expect(server.last.header("X-Auth-Token") == nil)
    }

    @Test("token rotation sends the client's new token and leaves the credentials alone (§4.3)")
    func rotation() async throws {
        let old = Data(repeating: 0x22, count: 32)
        let fresh = Data((0..<32).map { UInt8($0 * 7) })
        let server = Server { _ in (204, [:], Data()) }
        #expect(try await server.client.rotateAuthToken(to: fresh, authenticatingWith: old) == fresh)
        #expect(server.last.method == "POST")
        #expect(server.last.path == "/v1/accounts/me/token")
        #expect(server.last.header("X-Auth-Token") == old.base64URLEncodedString())
        #expect(try server.last.json()["newAuthToken"] as? String == fresh.base64URLEncodedString())
        #expect(server.client.credentials == Server.credentials)

        // A relay that predates client-chosen tokens answers with its own: that one is the account's now.
        let chosen = Data(repeating: 0x11, count: 32)
        let older = Server { _ in Server.json(200, #"{"authToken":"\#(chosen.base64URLEncodedString())"}"#) }
        #expect(try await older.client.rotateAuthToken(to: fresh, authenticatingWith: old) == chosen)
        let refused = Server { _ in Server.json(401, #"{"error":{"code":"unauthorized","message":"no"}}"#) }
        await #expect(throws: RelayError.server(status: 401, code: .unauthorized, message: "no")) {
            _ = try await refused.client.rotateAuthToken(to: fresh, authenticatingWith: old)
        }
    }

    @Test("WebSocket frames (§4.7)")
    func webSocketFrames() {
        let message = Data(#"{"type":"message","id":"abc","serverTimestamp":5,"envelope":"AQID"}"#.utf8)
        #expect(
            RelayConnection.decode(message)
                == .message(RelayEnvelope(id: "abc", serverTimestamp: 5, envelope: Data([1, 2, 3]))))
        #expect(RelayConnection.decode(Data(#"{"type":"queue_empty"}"#.utf8)) == .queueEmpty)
        #expect(RelayConnection.decode(Data(#"{"type":"pong"}"#.utf8)) == .pong)
        #expect(RelayConnection.decode(Data(#"{"type":"future"}"#.utf8)) == nil)
        #expect(RelayConnection.decode(Data(#"{"type":"message","id":"x"}"#.utf8)) == nil)
        #expect(RelayConnection.decode(Data("not json".utf8)) == nil)
    }
}
