import Foundation
import Testing

@testable import Us2usKit

@Suite("Generated backup passwords (§5.5)")
struct BackupPasswordGeneratorTests {
    private static let alphabet = Set(BackupPassword.generatedAlphabet)

    @Test func alphabetHasNoLookAlikes() {
        #expect(BackupPassword.generatedAlphabet.count == 31)
        #expect(Self.alphabet.count == 31)
        for lookAlike: Character in ["0", "o", "1", "l", "i"] {
            #expect(!Self.alphabet.contains(lookAlike))
        }
        #expect(BackupPassword.generatedAlphabet.allSatisfy { $0.isASCII && ($0.isLowercase || $0.isNumber) })
        #expect(abs(BackupPassword.generatedEntropyBits - 20 * log2(31.0)) < 1e-9)
    }

    @Test func shapeAndRules() throws {
        for _ in 0..<1_000 {
            let password = BackupPassword.generate()
            #expect(password.count == 24, "\(password)")
            let groups = password.split(separator: "-", omittingEmptySubsequences: false)
            #expect(groups.count == 5, "\(password)")
            #expect(
                groups.allSatisfy { group in group.count == 4 && group.allSatisfy { Self.alphabet.contains($0) } },
                "\(password)")
            #expect(BackupPassword.isGeneratedFormat(password), "\(password)")
            // Every generated password is acceptable as a backup password.
            try BackupPassword.validate(password)
        }
    }

    @Test func passwordsDoNotRepeat() {
        var seen = Set<String>()
        for _ in 0..<10_000 {
            seen.insert(BackupPassword.generate())
        }
        #expect(seen.count == 10_000)
    }

    /// Rejection sampling: each of the 248 accepted byte values maps to one
    /// symbol, every symbol to exactly 8 of them; 248…255 are drawn again.
    @Test func everySymbolIsEquallyLikely() {
        var tally: [Character: Int] = [:]
        for byte in 0...255 {
            var draws = 0
            let password = BackupPassword.generate { count in
                draws += 1
                return Data(repeating: draws == 1 ? UInt8(byte) : 0, count: count)
            }
            let symbols = Set(password.filter { $0 != "-" })
            if byte < 248 {
                let symbol = BackupPassword.generatedAlphabet[byte % 31]
                #expect(draws == 1)
                #expect(symbols == [symbol])
                tally[symbol, default: 0] += 1
            } else {
                #expect(draws == 2)
                #expect(symbols == ["a"])
            }
        }
        #expect(tally.count == 31)
        #expect(tally.values.allSatisfy { $0 == 8 })
    }

    /// The system generator, counted per position: every symbol close to
    /// 1/31 everywhere (expected 645 of 20,000; the bounds are ±8 σ).
    @Test func positionsAreUniform() {
        let samples = 20_000
        let index = Dictionary(uniqueKeysWithValues: BackupPassword.generatedAlphabet.enumerated().map { ($1, $0) })
        var counts = [[Int]](repeating: [Int](repeating: 0, count: 31), count: 20)
        for _ in 0..<samples {
            for (position, symbol) in BackupPassword.generate().filter({ $0 != "-" }).enumerated() {
                counts[position][index[symbol] ?? 0] += 1
            }
        }
        for position in counts {
            #expect(position.reduce(0, +) == samples)
            #expect(position.allSatisfy { (445...845).contains($0) }, "\(position)")
        }
    }

    @Test(
        "other shapes are not the generator's",
        arguments: [
            "", "k7mq-x2pd-9tfa-wn3h", "k7mq-x2pd-9tfa-wn3h-b8z", "k7mq-x2pd-9tfa-wn3h-b8ze-",
            "K7MQ-X2PD-9TFA-WN3H-B8ZE", "k7mq x2pd 9tfa wn3h b8ze", "o7mq-x2pd-9tfa-wn3h-b8ze", "k7mqx2pd9tfawn3hb8ze",
            "k7mq--x2pd-9tfa-wn3h-b8ze",
        ])
    func otherShapes(_ password: String) {
        #expect(!BackupPassword.isGeneratedFormat(password))
    }

    @Test func theDesignExampleHasTheShape() {
        #expect(BackupPassword.isGeneratedFormat("k7mq-x2pd-9tfa-wn3h-b8ze"))
    }
}
