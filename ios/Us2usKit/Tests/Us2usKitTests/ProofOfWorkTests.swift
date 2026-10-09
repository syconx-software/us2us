import CryptoKit
import Foundation
import Testing

@testable import Us2usKit

@Suite("Proof-of-work (§4.2)")
struct ProofOfWorkTests {
    let token = "v1.message.8f14e45f-ceea-467a-9f0b-6d3e2a1b4c5d.1760000600.AAAAAAAAAAAAAAAAAAAAAA.sig"

    private func leadingZeroBits(token: String, nonce: UInt64) -> Int {
        var input = Data(token.utf8)
        input.appendBigEndian(nonce)
        return Data(SHA256.hash(data: input)).withUnsafeBytes { ProofOfWork.leadingZeroBits(in: $0) }
    }

    @Test func leadingZeroBitCounting() {
        func count(_ bytes: [UInt8]) -> Int { bytes.withUnsafeBytes { ProofOfWork.leadingZeroBits(in: $0) } }
        #expect(count([0x80]) == 0)
        #expect(count([0x01]) == 7)
        #expect(count([0x00, 0x40]) == 9)
        #expect(count([0x00, 0x00]) == 16)
        #expect(count([]) == 0)
    }

    @Test("hash input is UTF-8(token) ‖ uint64_be(nonce)")
    func validityMatchesDefinition() {
        for nonce: UInt64 in [0, 1, 255, 256, 1 << 40, .max] {
            let zeros = leadingZeroBits(token: token, nonce: nonce)
            #expect(ProofOfWork.isValid(token: token, nonce: nonce, difficulty: zeros))
            #expect(!ProofOfWork.isValid(token: token, nonce: nonce, difficulty: zeros + 1))
        }
        #expect(!ProofOfWork.isValid(token: token, nonce: 0, difficulty: 257))
        #expect(!ProofOfWork.isValid(token: token, nonce: 0, difficulty: -1))
    }

    @Test("solves difficulty 16 on all cores", .timeLimit(.minutes(1)))
    func solve() async throws {
        let challenge = PowChallenge(token: token, difficulty: 16, expiresAt: 0)
        let solution = try await ProofOfWork.solve(challenge)
        #expect(solution.token == token)
        let nonce = try #require(UInt64(solution.nonce))
        #expect(ProofOfWork.isValid(token: token, nonce: nonce, difficulty: 16))
        #expect(solution.headerValue == "\(token):\(nonce)")
    }

    @Test func sequentialSearchFindsSmallestNonce() throws {
        let nonce = try #require(ProofOfWork.search(token: token, difficulty: 8, start: 0, stride: 1))
        #expect(ProofOfWork.isValid(token: token, nonce: nonce, difficulty: 8))
        for smaller in 0..<nonce {
            #expect(!ProofOfWork.isValid(token: token, nonce: smaller, difficulty: 8))
        }
    }

    @Test("cancellation stops the solver promptly", .timeLimit(.minutes(1)))
    func cancellation() async {
        let task = Task { try await ProofOfWork.solve(token: token, difficulty: 200) }
        try? await Task.sleep(nanoseconds: 50_000_000)
        task.cancel()
        await #expect(throws: CancellationError.self) { try await task.value }
    }

    @Test func rejectsImpossibleDifficulty() async {
        await #expect(throws: ProofOfWorkError.invalidDifficulty(300)) {
            try await ProofOfWork.solve(token: token, difficulty: 300)
        }
    }

    @Test("solution JSON carries the nonce as a decimal string (§2)")
    func solutionEncoding() throws {
        let solution = PowSolution(token: "t", nonce: .max)
        let json = String(decoding: try JSONEncoder.relay.encode(solution), as: UTF8.self)
        #expect(json.contains(#""nonce":"18446744073709551615""#))
    }
}
