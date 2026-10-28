import Foundation

/// The relay credentials of a registered account (PROTOCOL.md §4).
public struct RelayCredentials: Sendable, Equatable {
    public let accountId: AccountId
    /// 32 random bytes; the relay stores only its SHA-256.
    public let authToken: Data

    public init(accountId: AccountId, authToken: Data) {
        self.accountId = accountId
        self.authToken = authToken
    }
}

/// What the relay asks for before it accepts a sealed send (§4.6).
public enum SendAuthorization: Sendable, Equatable {
    case accessKey(Data)
    case proofOfWork(PowSolution)
}

public enum PowPurpose: Sendable, Equatable {
    case register
    case message(to: AccountId)
}

/// Client for every relay endpoint (PROTOCOL.md §4), on URLSession.
///
/// Authenticated calls send `X-Auth-Token` (never `Authorization`, which
/// URLSession may drop). Calls that hide the sender — sealed and ephemeral
/// sends, message proof-of-work challenges, media download and delete —
/// carry no token and go over a second `URLSession` built from its own
/// configuration object, so they never share a connection or a resumable TLS
/// session with authenticated traffic (§4.6 "Unlinkability"):
///
/// * Connections: every `URLSession` has its own connection pool. HTTP
///   keep-alive and HTTP/2 or HTTP/3 multiplexing reuse a connection only
///   within the session that opened it.
/// * TLS: URLSession keeps its TLS session cache (session IDs, tickets) per
///   `URLSession` as well, so a connection of one session never resumes a TLS
///   session another one established. `TLSIsolationTests` measures both
///   against a server that reports connection reuse and resumption
///   (`ios/scripts/tls-probe.py`): on iOS 27, TLS 1.2 sessions are resumed
///   within a URLSession and never across URLSessions, and TLS 1.3 sessions
///   were not resumed at all.
/// * Nothing else that identifies the account is shared either: the
///   anonymous configuration has no cookie storage, URL cache or credential
///   storage, whatever the configuration passed in holds.
public final class RelayClient: Sendable {
    public static let productionURL = URL(string: "https://relay.us2us.app")!
    /// Maximum ids per acknowledgement request (§4.6).
    static let maxAckBatch = 500
    /// Limits of `POST /v1/messages/ephemeral` (§4.6).
    static let maxEphemeralBatch = 200
    public static let maxEphemeralEnvelopeBytes = 4 * 1024
    static let maxEphemeralBodyBytes = 1024 * 1024
    /// Clients ping at least this often while connected (§4.7).
    static let maxKeepAliveInterval: TimeInterval = 30
    /// Error bodies are short JSON objects (§2.1); more is not worth reading.
    static let maxErrorBodyBytes = 16 * 1024
    static let downloadChunkBytes = 64 * 1024

    public let baseURL: URL
    private let authenticatedSession: URLSession
    private let anonymousSession: URLSession
    private let credentialsBox: Locked<RelayCredentials?>

    /// - Parameter configuration: base configuration for both sessions; the
    ///   default is ephemeral (no cookies, no cache, no credential storage).
    public init(
        baseURL: URL = productionURL,
        credentials: RelayCredentials? = nil,
        configuration: URLSessionConfiguration = RelayClient.defaultConfiguration()
    ) {
        self.baseURL = baseURL
        self.credentialsBox = Locked(credentials)
        (authenticatedSession, anonymousSession) = Self.makeSessions(configuration: configuration)
    }

    /// The two sessions of a client (see the type documentation). Each gets
    /// its own copy of `configuration`; `delegate` exists for tests that talk
    /// to a TLS server with a self-signed certificate. Both sessions refuse
    /// HTTP redirects (``RedirectRefusingDelegate``).
    static func makeSessions(
        configuration: URLSessionConfiguration, delegate: (any URLSessionDelegate)? = nil
    ) -> (authenticated: URLSession, anonymous: URLSession) {
        let authenticated = configuration.copy() as! URLSessionConfiguration
        let anonymous = configuration.copy() as! URLSessionConfiguration
        // Shallow copies share these stores with the caller's configuration.
        anonymous.httpCookieStorage = nil
        anonymous.httpShouldSetCookies = false
        anonymous.urlCache = nil
        anonymous.urlCredentialStorage = nil
        return (
            URLSession(configuration: authenticated, delegate: RedirectRefusingDelegate(inner: delegate), delegateQueue: nil),
            URLSession(configuration: anonymous, delegate: RedirectRefusingDelegate(inner: delegate), delegateQueue: nil)
        )
    }

    deinit {
        authenticatedSession.finishTasksAndInvalidate()
        anonymousSession.finishTasksAndInvalidate()
    }

    public static func defaultConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.urlCredentialStorage = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 30
        return configuration
    }

    public var credentials: RelayCredentials? {
        credentialsBox.withLock { $0 }
    }

    public func setCredentials(_ credentials: RelayCredentials?) {
        credentialsBox.withLock { $0 = credentials }
    }

    // MARK: Additive communities transport (docs/COMMUNITIES.md)

    public func createCommunity(_ state: SignedCommunityState) async throws -> SignedCommunityState {
        try await json(request("POST", "communities", auth: true, body: encode(state)))
    }
    public func communityDirectory(query: String = "", after: String? = nil) async throws -> CommunityDirectory {
        var items = [URLQueryItem(name: "query", value: query)]
        if let after { items.append(URLQueryItem(name: "after", value: after)) }
        return try await json(request("GET", "communities", query: items, auth: true))
    }
    public func myCommunities() async throws -> CommunityMemberships { try await json(request("GET", "communities/mine", auth: true)) }
    public func community(_ id: AccountId) async throws -> SignedCommunityState { try await json(request("GET", "communities/\(id.string)", auth: true)) }
    public func updateCommunity(_ id: AccountId, state: SignedCommunityState) async throws -> SignedCommunityState {
        try await json(request("PUT", "communities/\(id.string)/state", auth: true, body: encode(state)))
    }
    public func requestCommunityJoin(_ id: AccountId) async throws {
        try await perform(request("POST", "communities/\(id.string)/requests", auth: true, body: Data("{}".utf8)))
    }
    public func communityRequests(_ id: AccountId) async throws -> CommunityRequests { try await json(request("GET", "communities/\(id.string)/requests", auth: true)) }
    public func rejectCommunityRequest(_ id: AccountId, account: AccountId) async throws {
        try await perform(request("DELETE", "communities/\(id.string)/requests/\(account.string)", auth: true))
    }
    /// Owner: rejects every pending request (and the invitations they
    /// redeemed); `204`. Relays before 2026-10-28 answer `404` or `405`.
    public func rejectAllCommunityRequests(_ id: AccountId) async throws {
        try await perform(request("DELETE", "communities/\(id.string)/requests", auth: true))
    }
    public func createCommunityInvite(_ id: AccountId) async throws -> CommunityInviteToken {
        try await json(request("POST", "communities/\(id.string)/invites", auth: true, body: Data("{\"expiresInSeconds\":86400}".utf8)))
    }
    public func revokeCommunityInvites(_ id: AccountId) async throws { try await perform(request("DELETE", "communities/\(id.string)/invites", auth: true)) }
    public func redeemCommunityInvite(_ invite: CommunityInvitation) async throws -> CommunitySummary {
        struct Redemption: Encodable { let communityId: AccountId; let token: Data }
        return try await json(request("POST", "communities/invites/redeem", auth: true, body: encode(Redemption(communityId: invite.communityId, token: invite.token))))
    }
    public func deleteCommunity(_ id: AccountId) async throws { try await perform(request("DELETE", "communities/\(id.string)", auth: true)) }
    /// A member other than the owner leaves (`409` for the owner, `404` when not a member).
    public func leaveCommunity(_ id: AccountId) async throws {
        try await perform(request("DELETE", "communities/\(id.string)/members/me", auth: true))
    }
    /// Withdraws the own pending join request; idempotent.
    public func withdrawCommunityRequest(_ id: AccountId) async throws {
        try await perform(request("DELETE", "communities/\(id.string)/requests/me", auth: true))
    }
    public func sendCommunityEnvelope(_ envelope: Data, community: AccountId, recipient: AccountId, messageId: AccountId, revision: UInt64) async throws -> SendMessageResponse {
        var request = try request("PUT", "communities/\(community.string)/messages/\(recipient.string)/\(messageId.string)", auth: true, body: envelope, contentType: "application/octet-stream")
        request.setValue(String(revision), forHTTPHeaderField: "X-Community-Revision")
        return try await json(request)
    }
    public func communityInbox() async throws -> CommunityInbox { try await json(request("GET", "communities/inbox", auth: true)) }
    public func acknowledgeCommunity(_ ids: [String]) async throws {
        for offset in stride(from: 0, to: ids.count, by: 100) {
            try await perform(request("POST", "communities/inbox/ack", auth: true, body: encode(AckRequest(ids: Array(ids[offset..<min(offset + 100, ids.count)])))))
        }
    }

    // MARK: §4.1 Health

    public func health() async throws -> HealthResponse {
        try await json(request("GET", "health", auth: false))
    }

    // MARK: §4.2 Proof-of-work

    public func powChallenge(for purpose: PowPurpose) async throws -> PowChallenge {
        let query: [URLQueryItem]
        switch purpose {
        case .register:
            query = [URLQueryItem(name: "purpose", value: "register")]
        case .message(let recipient):
            query = [
                URLQueryItem(name: "purpose", value: "message"), URLQueryItem(name: "to", value: recipient.string),
            ]
        }
        // Anonymous: a message challenge names the recipient.
        return try await json(request("GET", "pow", query: query, auth: false), session: anonymousSession)
    }

    // MARK: §4.3 Accounts

    /// Registers an account and stores the returned credentials in the client.
    public func register(_ registration: RegistrationRequest) async throws -> RelayCredentials {
        let response: RegistrationResponse = try await json(
            request("POST", "accounts", auth: false, body: try encode(registration)))
        guard response.authToken.count == 32 else { throw RelayError.invalidResponse }
        let credentials = RelayCredentials(accountId: response.accountId, authToken: response.authToken)
        setCredentials(credentials)
        return credentials
    }

    public func accountInfo() async throws -> AccountInfo {
        try await json(request("GET", "accounts/me", auth: true))
    }

    public func changeUsername(hash: Data) async throws {
        try await perform(
            request("PUT", "accounts/me/username", auth: true, body: try encode(UsernameHashBody(usernameHash: hash))))
    }

    /// One `POST /v1/accounts/me/token` with `{"newAuthToken"}` (§4.3),
    /// authenticated with `token` instead of the client's credentials, which
    /// stay as they are: the caller has stored `newToken` before and decides
    /// from the answer which token the account has (§5.5 step 3). Never the
    /// deprecated empty body, whose lost answer loses the account.
    ///
    /// - Returns: the account's token now: `newToken` on `204`. A relay that
    ///   predates client-chosen tokens ignores the body and answers `200`
    ///   with a token it chose; that one is returned then, since it is the
    ///   only one that works.
    /// - Throws: ``RelayError``; `401` means `token` is not (or no longer)
    ///   the account's token.
    public func rotateAuthToken(to newToken: Data, authenticatingWith token: Data) async throws -> Data {
        let body = try encode(AuthTokenRotationBody(newAuthToken: newToken))
        let request = try request("POST", "accounts/me/token", auth: true, token: token, body: body)
        let (data, response) = try await transport { try await self.authenticatedSession.data(for: request) }
        let answer = try check(data, response)
        if (response as? HTTPURLResponse)?.statusCode == 204 { return newToken }
        let chosen: AuthTokenResponse = try decode(answer)
        guard chosen.authToken.count == 32 else { throw RelayError.invalidResponse }
        return chosen.authToken
    }

    /// Sets `SHA-256(accessKey)`, or `nil` to require proof-of-work from everyone.
    public func setAccessKeyHash(_ hash: Data?) async throws {
        try await perform(
            request(
                "PUT", "accounts/me/access-key", auth: true, body: try encode(AccessKeyHashBody(accessKeyHash: hash))))
    }

    /// Irreversibly deletes the account, its keys, mailbox and uploaded media.
    public func deleteAccount() async throws {
        try await perform(request("DELETE", "accounts/me", auth: true))
        setCredentials(nil)
    }

    /// The identity key registered for `account`, or `nil` if it does not exist (§6.3).
    public func identity(of account: AccountId) async throws -> IdentityResponse? {
        try await jsonOrNotFound(request("GET", "accounts/\(account.string)/identity", auth: true))
    }

    // MARK: §4.4 Directory

    /// Exact-match lookup of a username hash, or `nil` if it is not registered.
    public func lookup(usernameHash: Data) async throws -> IdentityResponse? {
        try await jsonOrNotFound(request("GET", "directory/\(usernameHash.base64URLEncodedString())", auth: true))
    }

    // MARK: §4.5 Pre-keys

    public func uploadKeys(_ upload: KeyUploadRequest) async throws {
        try await perform(request("PUT", "keys", auth: true, body: try encode(upload)))
    }

    public func keyStatus() async throws -> KeyStatus {
        try await json(request("GET", "keys/status", auth: true))
    }

    /// Fetches (and consumes one-time keys of) `account`'s bundle.
    public func preKeyBundle(for account: AccountId) async throws -> PreKeyBundleResponse {
        try await json(request("GET", "keys/\(account.string)", auth: true))
    }

    // MARK: §4.6 Messages

    /// Sealed send. Deliberately unauthenticated: the relay must not learn the sender.
    /// - Parameter ephemeral: deliver only if the recipient is connected, never
    ///   store. The relay accepts ephemeral sends only with an access key.
    public func sendMessage(
        _ envelope: Data, to recipient: AccountId, authorization: SendAuthorization, ephemeral: Bool = false
    ) async throws -> SendMessageResponse {
        var request = try request(
            "PUT", "messages/\(recipient.string)",
            query: ephemeral ? [URLQueryItem(name: "ephemeral", value: "1")] : [],
            auth: false, body: envelope, contentType: "application/octet-stream")
        switch authorization {
        case .accessKey(let key):
            request.setValue(key.base64URLEncodedString(), forHTTPHeaderField: "X-Access-Key")
        case .proofOfWork(let solution):
            request.setValue(solution.headerValue, forHTTPHeaderField: "X-PoW")
        }
        return try await json(request, session: anonymousSession)
    }

    /// Fans out ephemeral envelopes (presence, §8.6) in as few requests as the
    /// relay limits allow: at most 200 items and 1 MiB per request. The relay
    /// answers `202 {}` regardless of who was online.
    public func sendEphemeral(_ messages: [EphemeralMessage]) async throws {
        guard messages.allSatisfy({ $0.envelope.count <= Self.maxEphemeralEnvelopeBytes }) else {
            throw RelayError.payloadTooLarge
        }
        var batch: [EphemeralMessage] = []
        var batchBytes = 0
        func flush() async throws {
            guard !batch.isEmpty else { return }
            let body = try encode(EphemeralBatchRequest(messages: batch))
            try await perform(request("POST", "messages/ephemeral", auth: false, body: body), session: anonymousSession)
            batch.removeAll()
            batchBytes = 0
        }
        for message in messages {
            // base64url grows by 4/3; the rest is the UUID, key and JSON punctuation.
            let itemBytes = message.envelope.count * 4 / 3 + 128
            if batch.count == Self.maxEphemeralBatch || batchBytes + itemBytes > Self.maxEphemeralBodyBytes - 64 {
                try await flush()
            }
            batch.append(message)
            batchBytes += itemBytes
        }
        try await flush()
    }

    public func fetchMessages(limit: Int = 100) async throws -> MessagesPage {
        try await json(
            request(
                "GET", "messages", query: [URLQueryItem(name: "limit", value: String(min(max(limit, 1), 500)))],
                auth: true))
    }

    /// Deletes delivered messages from the relay, in batches of at most 500.
    public func acknowledge(_ ids: [String]) async throws {
        for start in stride(from: 0, to: ids.count, by: Self.maxAckBatch) {
            let batch = Array(ids[start..<min(start + Self.maxAckBatch, ids.count)])
            try await perform(request("POST", "messages/ack", auth: true, body: try encode(AckRequest(ids: batch))))
        }
    }

    // MARK: §4.7 WebSocket

    /// Opens the foreground WebSocket (§4.7, §5.4). The token goes in a header,
    /// never in the URL.
    ///
    /// - Parameter keepAliveInterval: seconds between `{"type":"ping"}` frames;
    ///   at most 30 (§4.7), and the default leaves room for scheduling delays.
    public func connect(keepAliveInterval: TimeInterval = 25) throws -> RelayConnection {
        let keepAliveInterval = min(keepAliveInterval, Self.maxKeepAliveInterval)
        var request = try request("GET", "ws", auth: true)
        var components = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!
        components.scheme = components.scheme == "http" ? "ws" : "wss"
        request.url = components.url
        return RelayConnection(
            task: authenticatedSession.webSocketTask(with: request), keepAliveInterval: keepAliveInterval)
    }

    // MARK: §4.9 Media

    /// Uploads an encrypted blob from a file, streaming it from disk.
    public func uploadMedia(fileAt url: URL) async throws -> MediaUploadResponse {
        let request = try request("POST", "media", auth: true, contentType: "application/octet-stream")
        let (data, response) = try await transport {
            try await self.authenticatedSession.upload(for: request, fromFile: url)
        }
        return try decode(try check(data, response))
    }

    /// Downloads a blob to `destination` (replacing it), streaming to disk.
    /// Anonymous: the media id is the capability.
    ///
    /// - Parameter maxBytes: the most this download may write. A longer blob is
    ///   refused with ``RelayError/payloadTooLarge`` as soon as its
    ///   `Content-Length` or its bytes show it, so a lying sender cannot fill
    ///   the disk; recipients know the exact blob size (§7).
    /// `progress` hears the bytes received so far, after every chunk.
    public func downloadMedia(
        id mediaId: Data, to destination: URL, maxBytes: UInt64 = .max, progress: (@Sendable (UInt64) -> Void)? = nil
    ) async throws {
        let request = try request("GET", "media/\(mediaId.base64URLEncodedString())", auth: false)
        let (bytes, response) = try await transport { try await self.anonymousSession.bytes(for: request) }
        guard let http = response as? HTTPURLResponse else { throw RelayError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            // Error bodies are small JSON (§2.1).
            var body = Data()
            for try await byte in bytes {
                body.append(byte)
                if body.count == Self.maxErrorBodyBytes { break }
            }
            throw Self.error(status: http.statusCode, response: http, body: body)
        }
        if http.expectedContentLength >= 0, UInt64(http.expectedContentLength) > maxBytes {
            bytes.task.cancel()
            throw RelayError.payloadTooLarge
        }

        let fileManager = FileManager.default
        let partial = destination.deletingLastPathComponent().appendingPathComponent(".\(UUID().uuidString).part")
        guard fileManager.createFile(atPath: partial.path, contents: nil) else { throw RelayError.invalidResponse }
        defer { try? fileManager.removeItem(at: partial) }
        let handle = try FileHandle(forWritingTo: partial)
        defer { try? handle.close() }
        var buffer: [UInt8] = []
        buffer.reserveCapacity(Self.downloadChunkBytes)
        var written: UInt64 = 0
        do {
            for try await byte in bytes {
                buffer.append(byte)
                guard written + UInt64(buffer.count) <= maxBytes else {
                    bytes.task.cancel()
                    throw RelayError.payloadTooLarge
                }
                if buffer.count == Self.downloadChunkBytes {
                    try handle.write(contentsOf: buffer)
                    written += UInt64(buffer.count)
                    buffer.removeAll(keepingCapacity: true)
                    progress?(written)
                }
            }
        } catch let error as URLError {
            throw RelayError.network(error.code)
        }
        try handle.write(contentsOf: buffer)
        try handle.close()
        progress?(written + UInt64(buffer.count))
        if fileManager.fileExists(atPath: destination.path) { try fileManager.removeItem(at: destination) }
        try fileManager.moveItem(at: partial, to: destination)
    }

    /// Deletes a blob with its delete token (recipient after download, or sender to unsend).
    public func deleteMedia(id mediaId: Data, deleteToken: Data) async throws {
        var request = try request("DELETE", "media/\(mediaId.base64URLEncodedString())", auth: false)
        request.setValue(deleteToken.base64URLEncodedString(), forHTTPHeaderField: "X-Delete-Token")
        try await perform(request, session: anonymousSession)
    }

    // MARK: §4.12 Avatar records

    /// `PUT /v1/accounts/me/avatar`: stores the record (its version is in its
    /// header). `409 stale_version` is an answer, not an error: see
    /// ``AvatarWriteOutcome``. A relay without avatar records answers `404`
    /// or `405` (thrown).
    public func putAvatar(_ record: Data) async throws -> AvatarWriteOutcome {
        guard record.count <= AvatarRecord.maxLength else { throw RelayError.payloadTooLarge }
        var request = try request(
            "PUT", "accounts/me/avatar", auth: true, body: record, contentType: "application/octet-stream")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        return try await avatarWrite(request)
    }

    /// `DELETE /v1/accounts/me/avatar` with `X-Avatar-Version: version`;
    /// `204` also when nothing was stored.
    public func deleteAvatar(version: UInt64) async throws -> AvatarWriteOutcome {
        var request = try request("DELETE", "accounts/me/avatar", auth: true)
        request.setValue(String(version), forHTTPHeaderField: "X-Avatar-Version")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        return try await avatarWrite(request)
    }

    /// `GET /v1/accounts/{accountId}/avatar` (authenticated, rate limited
    /// like the directory). With `ifNoneMatch` (the version of a cached
    /// copy) the relay answers `304` while that is still the stored one.
    /// Never served from or stored in a URL cache; an answer longer than the
    /// largest record is refused as it arrives.
    public func fetchAvatar(of account: AccountId, ifNoneMatch version: UInt64? = nil) async throws
        -> AvatarFetchResult
    {
        var request = try request("GET", "accounts/\(account.string)/avatar", auth: true)
        request.setValue("application/octet-stream", forHTTPHeaderField: "Accept")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        if let version { request.setValue("\"\(version)\"", forHTTPHeaderField: "If-None-Match") }
        let (bytes, response) = try await transport { try await self.authenticatedSession.bytes(for: request) }
        guard let http = response as? HTTPURLResponse else { throw RelayError.invalidResponse }
        switch http.statusCode {
        case 304:
            bytes.task.cancel()
            return .notModified
        case 404:
            bytes.task.cancel()
            return .notFound
        case 200:
            guard http.expectedContentLength <= Int64(AvatarRecord.maxLength) else {
                bytes.task.cancel()
                throw RelayError.payloadTooLarge
            }
            var body = Data()
            do {
                for try await byte in bytes {
                    guard body.count < AvatarRecord.maxLength else {
                        bytes.task.cancel()
                        throw RelayError.payloadTooLarge
                    }
                    body.append(byte)
                }
            } catch let error as URLError {
                throw RelayError.network(error.code)
            }
            return .record(body)
        default:
            // Error bodies are small JSON (§2.1).
            var body = Data()
            for try await byte in bytes {
                body.append(byte)
                if body.count == Self.maxErrorBodyBytes { break }
            }
            bytes.task.cancel()
            guard !(200..<300).contains(http.statusCode) else { throw RelayError.invalidResponse }
            throw Self.error(status: http.statusCode, response: http, body: body)
        }
    }

    private func avatarWrite(_ request: URLRequest) async throws -> AvatarWriteOutcome {
        let (data, response) = try await transport { try await self.authenticatedSession.data(for: request) }
        if let http = response as? HTTPURLResponse, http.statusCode == 409 {
            let error = Self.error(status: 409, response: http, body: data)
            guard error.code == .staleVersion else { throw error }
            return .stale(highestVersion: http.value(forHTTPHeaderField: "X-Avatar-Version").flatMap(Self.avatarVersion))
        }
        _ = try check(data, response)
        return .accepted
    }

    /// A decimal version in 1 … 2^63 − 1 (`X-Avatar-Version`), else `nil`.
    static func avatarVersion(_ text: String) -> UInt64? {
        let digits = text.trimmingCharacters(in: .whitespaces)
        guard !digits.isEmpty, digits.utf8.count <= 19,
            digits.utf8.allSatisfy({ (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) }),
            let version = UInt64(digits), AvatarRecord.versions.contains(version)
        else { return nil }
        return version
    }

    // MARK: Plumbing

    /// - Parameter token: authenticates with this token instead of the
    ///   stored credentials (only the token rotation does).
    private func request(
        _ method: String, _ path: String, query: [URLQueryItem] = [], auth: Bool, token: Data? = nil,
        body: Data? = nil, contentType: String? = nil
    ) throws -> URLRequest {
        var components = URLComponents(
            url: baseURL.appendingPathComponent("v1/" + path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if auth {
            guard let authToken = token ?? credentials?.authToken else { throw RelayError.notAuthenticated }
            request.setValue(authToken.base64URLEncodedString(), forHTTPHeaderField: "X-Auth-Token")
        }
        request.httpBody = body
        if let contentType = contentType ?? (body != nil ? "application/json" : nil) {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        return request
    }

    private func encode(_ value: some Encodable) throws -> Data {
        try JSONEncoder.relay.encode(value)
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do {
            return try JSONDecoder.relay.decode(T.self, from: data)
        } catch {
            throw RelayError.invalidResponse
        }
    }

    private func json<T: Decodable>(_ request: URLRequest, session: URLSession? = nil) async throws -> T {
        let session = session ?? authenticatedSession
        let (data, response) = try await transport { try await session.data(for: request) }
        return try decode(try check(data, response))
    }

    private func jsonOrNotFound<T: Decodable>(_ request: URLRequest) async throws -> T? {
        do {
            return try await json(request) as T
        } catch let error as RelayError where error.isNotFound {
            return nil
        }
    }

    private func perform(_ request: URLRequest, session: URLSession? = nil) async throws {
        let session = session ?? authenticatedSession
        let (data, response) = try await transport { try await session.data(for: request) }
        _ = try check(data, response)
    }

    private func transport<T>(_ operation: () async throws -> (T, URLResponse)) async throws -> (T, URLResponse) {
        do {
            return try await operation()
        } catch let error as URLError {
            throw RelayError.network(error.code)
        }
    }

    /// Maps non-2xx responses to ``RelayError`` (§2.1) and returns the body.
    private func check(_ data: Data, _ response: URLResponse) throws -> Data {
        guard let http = response as? HTTPURLResponse else { throw RelayError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            throw Self.error(status: http.statusCode, response: http, body: data)
        }
        return data
    }

    static func error(status: Int, response: HTTPURLResponse, body: Data) -> RelayError {
        let detail = try? JSONDecoder().decode(RelayErrorBody.self, from: body).error
        if status == 429 {
            let retryAfter = response.value(forHTTPHeaderField: "Retry-After").flatMap(TimeInterval.init)
            if detail?.code == RelayErrorCode.communityInboxFull.rawValue { return .communityInboxFull(retryAfter: retryAfter) }
            return .rateLimited(retryAfter: retryAfter)
        }
        return .server(status: status, code: detail.map { RelayErrorCode(rawValue: $0.code) }, message: detail?.message)
    }
}

/// The relay never redirects (§4). URLSession follows a `3xx` by default and
/// strips only `Authorization` when the origin changes, so a redirect from a
/// compromised relay or an open redirect on its domain would carry
/// `X-Auth-Token`, `X-Access-Key`, `X-Delete-Token` or `X-PoW` elsewhere.
/// Every redirect is refused: the request ends with the `3xx` answer, which
/// the client reports as a relay error (audit 2026-10-28 L-7). An `inner`
/// delegate (tests only) still answers authentication challenges.
final class RedirectRefusingDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let inner: (any URLSessionDelegate)?

    init(inner: (any URLSessionDelegate)? = nil) {
        self.inner = inner
    }

    func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }

    func urlSession(
        _ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard let inner, inner.responds(to: #selector(URLSessionDelegate.urlSession(_:didReceive:completionHandler:)))
        else { return completionHandler(.performDefaultHandling, nil) }
        inner.urlSession?(session, didReceive: challenge, completionHandler: completionHandler)
    }
}
