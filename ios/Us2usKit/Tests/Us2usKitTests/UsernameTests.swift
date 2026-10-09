import Foundation
import Testing

@testable import Us2usKit

@Suite("Usernames (§3)")
struct UsernameTests {
    @Test(
        "normalization: trim, NFKC, lowercase",
        arguments: [
            ("alice", "alice"),
            ("Alice_01", "alice_01"),
            ("  bob  ", "bob"),
            ("\tdave\n", "dave"),
            ("\u{3000}eve\u{00A0}", "eve"),  // ideographic and no-break spaces are Unicode whitespace
            ("ｃａｒｏｌ", "carol"),  // fullwidth → ASCII via NFKC
            ("ＡＬＩＣＥ", "alice"),
            ("abc", "abc"),
            ("abcdefghijklmnopqrstuvwx", "abcdefghijklmnopqrstuvwx"),
        ])
    func valid(_ input: String, _ expected: String) throws {
        #expect(try NormalizedUsername(input).value == expected)
    }

    @Test(
        "validation errors",
        arguments: [
            ("ab", UsernameError.tooShort),
            ("", .tooShort),
            ("abcdefghijklmnopqrstuvwxy", .tooLong),
            ("1alice", .mustStartWithLetter),
            ("_alice", .mustStartWithLetter),
            ("al-ice", .invalidCharacters),
            ("al.ice", .invalidCharacters),
            ("şule", .invalidCharacters),
            ("ılgaz", .invalidCharacters),
            ("İstanbul", .invalidCharacters),  // lowercases to i + combining dot
            ("user name", .invalidCharacters),
            ("kağan", .invalidCharacters),
        ])
    func invalid(_ input: String, _ error: UsernameError) {
        #expect(throws: error) { try NormalizedUsername(input) }
    }

    @Test("lowercasing is locale independent: I becomes i, never ı")
    func turkishCapitalI() throws {
        #expect(try NormalizedUsername("ILGAZ").value == "ilgaz")
    }

    @Test("hash is PBKDF2-HMAC-SHA256(username, \"BRP/username/v1\", 100000, 32)")
    func hash() throws {
        let hash = try NormalizedUsername("alice").hash()
        #expect(hash.count == 32)
        #expect(
            hash == PBKDF2.sha256(password: Data("alice".utf8), salt: Data("BRP/username/v1".utf8), iterations: 100_000)
        )
        #expect(try NormalizedUsername("  ALICE ").hash() == hash)
        #expect(try NormalizedUsername("alicf").hash() != hash)
    }

    @Test("PBKDF2 matches RFC 7914 §11 test vector")
    func pbkdf2KnownAnswer() {
        let derived = PBKDF2.sha256(
            password: Data("passwd".utf8), salt: Data("salt".utf8), iterations: 1, keyLength: 64)
        #expect(
            derived.hexString
                == "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
                + "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783")
    }

    @Test func codableRoundTripValidates() throws {
        let encoded = try JSONEncoder().encode(try NormalizedUsername("alice_01"))
        #expect(String(decoding: encoded, as: UTF8.self) == #""alice_01""#)
        #expect(try JSONDecoder().decode(NormalizedUsername.self, from: encoded).value == "alice_01")
        #expect(throws: DecodingError.self) {
            try JSONDecoder().decode(NormalizedUsername.self, from: Data(#""1x""#.utf8))
        }
    }

    @Test func errorsAreLocalized() {
        #expect(UsernameError.tooShort.errorDescription?.isEmpty == false)
    }
}
