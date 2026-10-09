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
    /// to a TLS server with a self-signed certificate.
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
            URLSession(configuration: authenticated, delegate: delegate, delegateQueue: nil),
            URLSession(configuration: anonymous, delegate: delegate, delegateQueue: nil)
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

    /// Rotates the auth token; the old one stops working immediately (§4.3, §5.5).
    @discardableResult
    public func rotateAuthToken() async throws -> RelayCredentials {
        guard let current = credentials else { throw RelayError.notAuthenticated }
        let response: AuthTokenResponse = try await json(request("POST", "accounts/me/token", auth: true))
        guard response.authToken.count == 32 else { throw RelayError.invalidResponse }
        let rotated = RelayCredentials(accountId: current.accountId, authToken: response.authToken)
        setCredentials(rotated)
        return rotated
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
    public func downloadMedia(id mediaId: Data, to destination: URL, maxBytes: UInt64 = .max) async throws {
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
                }
            }
        } catch let error as URLError {
            throw RelayError.network(error.code)
        }
        try handle.write(contentsOf: buffer)
        try handle.close()
        if fileManager.fileExists(atPath: destination.path) { try fileManager.removeItem(at: destination) }
        try fileManager.moveItem(at: partial, to: destination)
    }

    /// Deletes a blob with its delete token (recipient after download, or sender to unsend).
    public func deleteMedia(id mediaId: Data, deleteToken: Data) async throws {
        var request = try request("DELETE", "media/\(mediaId.base64URLEncodedString())", auth: false)
        request.setValue(deleteToken.base64URLEncodedString(), forHTTPHeaderField: "X-Delete-Token")
        try await perform(request, session: anonymousSession)
    }

    // MARK: Plumbing

    private func request(
        _ method: String, _ path: String, query: [URLQueryItem] = [], auth: Bool, body: Data? = nil,
        contentType: String? = nil
    ) throws -> URLRequest {
        var components = URLComponents(
            url: baseURL.appendingPathComponent("v1/" + path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if auth {
            guard let credentials else { throw RelayError.notAuthenticated }
            request.setValue(credentials.authToken.base64URLEncodedString(), forHTTPHeaderField: "X-Auth-Token")
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
        if status == 429 {
            let retryAfter = response.value(forHTTPHeaderField: "Retry-After").flatMap(TimeInterval.init)
            return .rateLimited(retryAfter: retryAfter)
        }
        let detail = try? JSONDecoder().decode(RelayErrorBody.self, from: body).error
        return .server(status: status, code: detail.map { RelayErrorCode(rawValue: $0.code) }, message: detail?.message)
    }
}
