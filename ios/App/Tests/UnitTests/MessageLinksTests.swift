import XCTest
@testable import us2us

final class MessageLinksTests: XCTestCase {
    func testWebLinksAndUTF16Ranges() {
        let text = "👋 Bak: https://example.com/a_(b)). ve www.example.org!"
        let matches = MessageLinks.detect(text)
        XCTAssertEqual(matches.map { (text as NSString).substring(with: $0.range) }, ["https://example.com/a_(b)", "www.example.org"])
        XCTAssertEqual(matches.last?.target, .web(URL(string: "https://www.example.org")!))
    }
    func testInvitesStayLocalInMixedMessages() {
        let code = "us2us-community:v1:YWJj_-09"
        let matches = MessageLinks.detect("Davet: \(code)\nhttps://example.org")
        XCTAssertEqual(matches.count, 2)
        XCTAssertEqual(matches.first?.target, .communityInvite(code))
    }
    func testUnsafeSchemesAndCredentialsAreNotLinks() {
        XCTAssertTrue(MessageLinks.detect("javascript:alert(1) file:///tmp/a data:text/plain,a https://user:pass@example.com").isEmpty)
    }
    func testMalformedAndOversizedInvitesAreNotLinks() {
        XCTAssertTrue(MessageLinks.detect("us2us-community:v2:abc us2us-community:v1: us2us-community:v1:" + String(repeating: "a", count: 2048)).isEmpty)
    }
    func testLiteralMarkdownAndUppercaseScheme() {
        let matches = MessageLinks.detect("[görünür](HTTPS://example.org)")
        XCTAssertEqual(matches.count, 1)
        XCTAssertEqual(matches.first?.target, .web(URL(string: "HTTPS://example.org")!))
    }
    func testLongPunctuationAndEmptyText() {
        let matches = MessageLinks.detect("https://example.org" + String(repeating: ")", count: 20_000))
        XCTAssertEqual(matches.first?.target, .web(URL(string: "https://example.org")!))
        XCTAssertTrue(MessageLinks.detect("").isEmpty)
    }
    func testInternalActionURLsCarryOnlyAnIndex() {
        let url = URL(string: "https://message-link.invalid/0")!
        XCTAssertEqual(url.path, "/0")
        XCTAssertEqual(url.host, "message-link.invalid")
    }
}
