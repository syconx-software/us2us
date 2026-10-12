import Foundation
import Testing

@testable import Us2usKit

@Suite("Relay client: avatar records (§4.12)")
struct AvatarRelayClientTests {
    typealias Server = RelayClientTests.Server

    static func record(version: UInt64, length: Int = 8192) -> Data {
        var record = Data("BRPA".utf8) + Data([0x01])
        record.appendBigEndian(version)
        record.append(Data.pattern(length - record.count))
        return record
    }

    @Test("PUT carries the record as an octet stream, authenticated; 409 stale_version is an answer")
    func store() async throws {
        let replies = Locked<[Server.Reply]>([
            (204, ["ETag": "\"3\""], Data()),
            Server.json(409, #"{"error":{"code":"stale_version","message":"stale"}}"#, headers: ["X-Avatar-Version": "41"]),
            Server.json(409, #"{"error":{"code":"stale_version","message":"stale"}}"#, headers: ["X-Avatar-Version": "x"]),
            Server.json(409, #"{"error":{"code":"username_taken","message":"other"}}"#),
            Server.json(404, #"{"error":{"code":"not_found","message":"no such path"}}"#),
        ])
        let server = Server { _ in replies.withLock { $0.removeFirst() } }
        let record = Self.record(version: 3)
        #expect(try await server.client.putAvatar(record) == .accepted)
        let request = server.last
        #expect(request.method == "PUT")
        #expect(request.path == "/v1/accounts/me/avatar")
        #expect(request.header("Content-Type") == "application/octet-stream")
        #expect(request.header("X-Auth-Token") == Server.credentials.authToken.base64URLEncodedString())
        #expect(request.body == record)

        #expect(try await server.client.putAvatar(record) == .stale(highestVersion: 41))
        #expect(try await server.client.putAvatar(record) == .stale(highestVersion: nil))
        await #expect(throws: RelayError.server(status: 409, code: .usernameTaken, message: "other")) {
            try await server.client.putAvatar(record)
        }
        await #expect(throws: RelayError.server(status: 404, code: .notFound, message: "no such path")) {
            try await server.client.putAvatar(record)
        }
        // Never more than the largest record.
        let before = server.requests.count
        await #expect(throws: RelayError.payloadTooLarge) { try await server.client.putAvatar(Data(count: 49153)) }
        #expect(server.requests.count == before)
    }

    @Test("DELETE names the version in X-Avatar-Version")
    func delete() async throws {
        let server = Server { _ in (204, [:], Data()) }
        #expect(try await server.client.deleteAvatar(version: 12) == .accepted)
        #expect(server.last.method == "DELETE")
        #expect(server.last.path == "/v1/accounts/me/avatar")
        #expect(server.last.header("X-Avatar-Version") == "12")
        #expect(server.last.header("X-Auth-Token") != nil)
        _ = try await server.client.deleteAvatar(version: UInt64(Int64.max))
        #expect(server.last.header("X-Avatar-Version") == "9223372036854775807")
    }

    @Test("GET: the record, 304 for the cached version, 404 for none; errors and oversized bodies throw")
    func fetch() async throws {
        let account = AccountId(string: "8f14e45f-ceea-467a-9f0b-6d3e2a1b4c5d")!
        let record = Self.record(version: 5, length: 16384)
        let replies = Locked<[Server.Reply]>([
            (200, ["Content-Type": "application/octet-stream", "ETag": "\"5\"", "Cache-Control": "no-store"], record),
            (304, ["ETag": "\"5\""], Data()),
            Server.json(404, #"{"error":{"code":"not_found","message":"not found"}}"#),
            Server.json(429, #"{"error":{"code":"rate_limited","message":"slow down"}}"#, headers: ["Retry-After": "30"]),
            (200, ["Content-Type": "application/octet-stream"], Data(count: 49153)),
        ])
        let server = Server { _ in replies.withLock { $0.removeFirst() } }

        #expect(try await server.client.fetchAvatar(of: account) == .record(record))
        #expect(server.last.method == "GET")
        #expect(server.last.path == "/v1/accounts/\(account.string)/avatar")
        #expect(server.last.header("X-Auth-Token") != nil)
        #expect(server.last.header("If-None-Match") == nil)
        #expect(try await server.client.fetchAvatar(of: account, ifNoneMatch: 5) == .notModified)
        #expect(server.last.header("If-None-Match") == "\"5\"")
        #expect(try await server.client.fetchAvatar(of: account) == .notFound)
        await #expect(throws: RelayError.rateLimited(retryAfter: 30)) {
            try await server.client.fetchAvatar(of: account)
        }
        await #expect(throws: RelayError.payloadTooLarge) { try await server.client.fetchAvatar(of: account) }

        let anonymous = Server(credentials: nil) { _ in (200, [:], record) }
        await #expect(throws: RelayError.notAuthenticated) { try await anonymous.client.fetchAvatar(of: account) }
        #expect(anonymous.requests.isEmpty)
    }

    @Test("X-Avatar-Version: a decimal in 1 … 2^63 − 1")
    func versionHeader() {
        #expect(RelayClient.avatarVersion("1") == 1)
        #expect(RelayClient.avatarVersion(" 41 ") == 41)
        #expect(RelayClient.avatarVersion("9223372036854775807") == UInt64(Int64.max))
        #expect(RelayClient.avatarVersion("9223372036854775808") == nil)
        #expect(RelayClient.avatarVersion("0") == nil)
        #expect(RelayClient.avatarVersion("") == nil)
        #expect(RelayClient.avatarVersion("+5") == nil)
        #expect(RelayClient.avatarVersion("5a") == nil)
        #expect(RelayClient.avatarVersion("١٢") == nil)
    }
}
