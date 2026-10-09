import Foundation

@testable import Us2usKit

/// A request as the stubbed server saw it.
struct RecordedRequest: Sendable {
    let method: String
    let url: URL
    let headers: [String: String]
    let body: Data

    var path: String { url.path }

    func header(_ name: String) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    func json() throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: body) as? [String: Any] ?? [:]
    }
}

/// Routes URLSession requests to an in-process handler chosen by host, so
/// every test can run its own fake server in parallel.
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (RecordedRequest) -> (status: Int, headers: [String: String], body: Data)

    private static let handlers = Locked<[String: Handler]>([:])

    /// A unique host for a fake server, and a client configuration routed here.
    static func makeEndpoint() -> (baseURL: URL, configuration: URLSessionConfiguration) {
        let configuration = RelayClient.defaultConfiguration()
        configuration.protocolClasses = [StubURLProtocol.self]
        return (URL(string: "https://relay-\(UUID().uuidString.lowercased()).test")!, configuration)
    }

    static func setHandler(_ handler: @escaping Handler, for baseURL: URL) {
        handlers.withLock { $0[baseURL.host!] = handler }
    }

    /// A unique host routed to `handler`, and a client configuration using it.
    static func register(_ handler: @escaping Handler) -> (baseURL: URL, configuration: URLSessionConfiguration) {
        let endpoint = makeEndpoint()
        setHandler(handler, for: endpoint.baseURL)
        return endpoint
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let host = url.host, let handler = Self.handlers.withLock({ $0[host] }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotFindHost))
            return
        }
        let recorded = RecordedRequest(
            method: request.httpMethod ?? "GET", url: url,
            headers: request.allHTTPHeaderFields ?? [:],
            body: request.httpBody ?? request.httpBodyStream.map(Self.readAll) ?? Data())
        let reply = handler(recorded)
        let response = HTTPURLResponse(
            url: url, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: reply.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: reply.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func readAll(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 64 * 1024)
        while true {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}
