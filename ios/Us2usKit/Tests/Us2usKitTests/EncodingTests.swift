import Foundation
import Testing

@testable import Us2usKit

@Suite("Encodings (§2)")
struct EncodingTests {
    @Test("base64url without padding, RFC 4648 §5")
    func base64URL() {
        let cases: [(Data, String)] = [
            (Data(), ""),
            (Data([0xFB]), "-w"),
            (Data([0xFB, 0xFF]), "-_8"),
            (Data([0xFB, 0xFF, 0xBF]), "-_-_"),
            (Data("hello world".utf8), "aGVsbG8gd29ybGQ"),
        ]
        for (data, encoded) in cases {
            #expect(data.base64URLEncodedString() == encoded)
            #expect(Data(base64URLEncoded: encoded) == data)
        }
    }

    @Test("base64url decoding is strict", arguments: ["aGVsbG8=", "a+b/", "abc def", "a", "ab\n", "é"])
    func base64URLRejects(_ input: String) {
        #expect(Data(base64URLEncoded: input) == nil)
    }

    @Test func hex() {
        let data = Data([0x00, 0x01, 0xAB, 0xFF])
        #expect(data.hexString == "0001abff")
        #expect(Data(hexString: "0001ABff") == data)
        #expect(Data(hexString: "abc") == nil)
        #expect(Data(hexString: "zz") == nil)
        #expect(Data(hexString: "") == Data())
    }

    @Test("account id: lowercase string in JSON, 16 bytes in protobuf")
    func accountId() throws {
        let string = "8f14e45f-ceea-467a-9f0b-6d3e2a1b4c5d"
        let id = try #require(AccountId(string: string.uppercased()))
        #expect(id.string == string)
        #expect(id.bytes == Data(hex: "8f14e45fceea467a9f0b6d3e2a1b4c5d"))
        #expect(AccountId(bytes: id.bytes) == id)
        #expect(AccountId(bytes: Data(count: 15)) == nil)
        #expect(AccountId(string: "not-a-uuid") == nil)

        let json = try JSONEncoder().encode(["accountId": id])
        #expect(String(decoding: json, as: UTF8.self) == #"{"accountId":"\#(string)"}"#)
        #expect(try JSONDecoder().decode([String: AccountId].self, from: json)["accountId"] == id)
    }

    @Test("libsignal address is the ACI ServiceId with device 1 (§2)")
    func protocolAddress() throws {
        let id = AccountId(uuid: UUID())
        let address = id.protocolAddress
        #expect(address.deviceId == 1)
        #expect(address.name == id.uuid.uuidString)  // libsignal's uppercase form
        #expect(address.serviceId?.serviceIdBinary == id.bytes)  // ACI binary form is the bare UUID
        #expect(AccountId(address: address) == id)
    }

    @Test func accountIdOrderingIsByteLexicographic() throws {
        let low = try #require(AccountId(bytes: Data.id(1)))
        let high = try #require(AccountId(bytes: Data.id(2)))
        #expect(low < high)
        #expect(!(high < low))
    }

    @Test func constantTimeEquals() {
        #expect(ConstantTime.equals(Data([1, 2, 3]), Data([1, 2, 3])))
        #expect(!ConstantTime.equals(Data([1, 2, 3]), Data([1, 2, 4])))
        #expect(!ConstantTime.equals(Data([1, 2, 3]), Data([1, 2])))
        #expect(ConstantTime.equals(Data(), Data()))
    }

    @Test func relayJSONCodersUseBase64URL() throws {
        struct Body: Codable, Equatable { let blob: Data }
        let body = Body(blob: Data([0xFB, 0xFF]))
        let json = try JSONEncoder.relay.encode(body)
        #expect(String(decoding: json, as: UTF8.self) == #"{"blob":"-_8"}"#)
        #expect(try JSONDecoder.relay.decode(Body.self, from: json) == body)
        #expect(throws: DecodingError.self) {
            try JSONDecoder.relay.decode(Body.self, from: Data(#"{"blob":"+/8="}"#.utf8))
        }
    }
}
