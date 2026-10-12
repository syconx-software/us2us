import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
import Us2usKit

@testable import Us2usRuntime

@Suite("Key material in memory")
struct SecretKeyMaterialTests {
    @Test func wipeZeroesAndRefusesFurtherUse() throws {
        let key = SecretKeyMaterial(copying: Data(repeating: 0xAB, count: 32))
        try key.withData { #expect($0 == Data(repeating: 0xAB, count: 32)) }
        key.wipe()
        #expect(key.isWiped)
        #expect(throws: SecretKeyMaterialError.wiped) { try key.withData { _ in } }
    }

    @Test func comparisonIsByValue() {
        let a = SecretKeyMaterial(copying: Data(repeating: 1, count: 32))
        let b = SecretKeyMaterial(copying: Data(repeating: 1, count: 32))
        let c = SecretKeyMaterial(copying: Data(repeating: 2, count: 32))
        #expect(a.matches(b))
        #expect(!a.matches(c))
        b.wipe()
        #expect(!a.matches(b))
    }

    @Test func zeroizeOverwritesInPlace() {
        var data = Data(repeating: 7, count: 16)
        SecretKeyMaterial.zeroize(&data)
        #expect(data == Data(count: 16))
    }
}

@Suite("Keychain items")
struct KeychainTests {
    @Test func inMemoryStore() throws {
        let store = InMemorySecureItemStore()
        try store.write(Data([1]), for: "a")
        #expect(try store.read("a") == Data([1]))
        try store.delete("a")
        #expect(try store.read("a") == nil)
        let secret = DeviceSecretItem(store: store)
        try secret.storeDeviceSecret(Data(repeating: 5, count: 32))
        #expect(try secret.loadDeviceSecret() == Data(repeating: 5, count: 32))
        try store.deleteAll()
        #expect(try secret.loadDeviceSecret() == nil)
    }

    /// Runs where the process has a keychain (app-hosted tests, devices);
    /// Apple's xctest runner on the Simulator has none (-34018).
    @Test(.enabled(if: KeychainItemStore.isUsable()))
    func realKeychainUsesThisDeviceOnlyAndNoSync() throws {
        let store = KeychainItemStore(service: "com.syconx.us2us.runtime.tests.\(UUID().uuidString)")
        defer { try? store.deleteAll() }
        try store.write(Data([9, 9]), for: "device-secret")
        #expect(try store.read("device-secret") == Data([9, 9]))
        let attributes = try #require(try store.attributes(of: "device-secret"))
        #expect(attributes.accessible == (kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String))
        #expect(attributes.synchronizable == false)

        // The vault's Secure Enclave key of format v2 is kept as an item of
        // the same store (only its blob; the key never leaves the hardware).
        let hardwareKey = SecureEnclaveVaultKey(store: store)
        if hardwareKey.isAvailable {
            try hardwareKey.createKey()
            #expect(try hardwareKey.evaluate(codeKey: Data(repeating: 1, count: 32)).count == 32)
        } else {
            try store.write(Data([7]), for: SecureEnclaveVaultKey.itemName)
        }
        let keyAttributes = try #require(try store.attributes(of: SecureEnclaveVaultKey.itemName))
        #expect(keyAttributes.accessible == (kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String))
        #expect(keyAttributes.synchronizable == false)
    }
}

@Suite("Network policy")
struct NetworkPolicyTests {
    @Test func onlyTheRelayOverHTTPS() throws {
        #expect(throws: Never.self) { try NetworkPolicy.validatedRelayURL(URL(string: "https://relay.us2us.app")!) }
        #expect(throws: Never.self) { try NetworkPolicy.validatedRelayURL(URL(string: "http://127.0.0.1:3000")!) }
        #expect(throws: Never.self) { try NetworkPolicy.validatedRelayURL(URL(string: "http://localhost:3000")!) }
        for bad in ["http://relay.us2us.app", "ftp://relay.us2us.app", "https://user:pw@relay.us2us.app",
            "https://relay.us2us.app?x=1", "http://192.168.1.10:3000"]
        {
            #expect(throws: NetworkPolicyError.insecureRelayURL) { try NetworkPolicy.validatedRelayURL(URL(string: bad)!) }
        }
    }

    @Test func sessionsCarryNothingIdentifying() {
        let configuration = NetworkPolicy.sessionConfiguration()
        let headers = configuration.httpAdditionalHeaders as? [String: String]
        #expect(headers?["User-Agent"] == "us2us")
        #expect(headers?["Accept-Language"] == "en")
        #expect(configuration.httpCookieStorage == nil)
        #expect(configuration.urlCache == nil)
        #expect(configuration.urlCredentialStorage == nil)
        #expect(configuration.httpShouldSetCookies == false)
        #expect(configuration.tlsMinimumSupportedProtocolVersion == .TLSv12)
        #expect(configuration.multipathServiceType == .none)
    }
}

@Suite("Files")
struct ProtectedFilesTests {
    @Test func directoriesAreCompleteAndNotBackedUp() throws {
        let temporary = try TemporaryDirectory()
        let directories = temporary.directories
        try directories.prepare()
        // Protection classes where the platform reports them (devices do; the
        // Simulator may not).
        for directory in [directories.root, directories.vault, directories.runtime, directories.staging] {
            if let protection = ProtectedFiles.protection(of: directory) { #expect(protection == .complete) }
        }
        #expect(ProtectedFiles.isExcludedFromBackup(directories.root))
        let file = directories.staging.appendingPathComponent("x")
        try ProtectedFiles.write(Data([1]), to: file)
        if let protection = ProtectedFiles.protection(of: file) { #expect(protection == .complete) }
        directories.purgeStaging()
        #expect(!FileManager.default.fileExists(atPath: file.path))
    }

    @Test func eraseRemovesEverything() throws {
        let temporary = try TemporaryDirectory()
        let directories = temporary.directories
        try directories.prepare()
        try ProtectedFiles.write(Data([1]), to: directories.vault.appendingPathComponent("vault.brpv"))
        try directories.eraseAll()
        #expect(!FileManager.default.fileExists(atPath: directories.root.path))
    }
}

@MainActor
final class FakePasteboard: PasteboardWriting {
    var changeCount = 0
    var items: [[String: Any]] = []
    var localOnly = false
    var expiresAt: Date?

    func setItems(_ items: [[String: Any]], localOnly: Bool, expiresAt: Date?) {
        self.items = items
        self.localOnly = localOnly
        self.expiresAt = expiresAt
        changeCount += 1
    }

    func clear() {
        items = []
        changeCount += 1
    }

    /// Another app copied something.
    func externalCopy() {
        items = [["public.utf8-plain-text": "theirs"]]
        changeCount += 1
    }
}

@Suite("Pasteboard hygiene")
@MainActor
struct PasteboardTests {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func copiesAreLocalAndExpire() {
        let board = FakePasteboard()
        let now = self.now
        let pasteboard = SecurePasteboard(pasteboard: board, now: { now })
        #expect(pasteboard.copy("12345 67890", sensitivity: .sensitive))
        #expect(board.localOnly)
        #expect(board.expiresAt == now.addingTimeInterval(60))
        #expect(pasteboard.copy("hello", sensitivity: .ordinary))
        #expect(board.expiresAt == now.addingTimeInterval(600))
        #expect(!pasteboard.copy("disappearing", sensitivity: .forbidden))
    }

    @Test func clearsOnlyItsOwnCopy() {
        let board = FakePasteboard()
        let pasteboard = SecurePasteboard(pasteboard: board)
        pasteboard.copy("code", sensitivity: .sensitive)
        pasteboard.clearIfOwned()
        #expect(board.items.isEmpty)
        pasteboard.copy("code", sensitivity: .sensitive)
        board.externalCopy()
        pasteboard.clearIfOwned()
        #expect(board.items.count == 1)
    }

    /// The generated backup password is copied to be pasted into a password
    /// manager: leaving the app (which locks it) must not clear it, so only
    /// its one-minute expiry does; it never leaves the device.
    @Test func aHandOffSurvivesLeavingTheAppAndExpires() {
        let board = FakePasteboard()
        let now = self.now
        let pasteboard = SecurePasteboard(pasteboard: board, now: { now })
        #expect(pasteboard.copy("k7mq-x2pd-9tfa-wn3h-b8ze", sensitivity: .handOff))
        #expect(board.localOnly)
        #expect(board.expiresAt == now.addingTimeInterval(60))
        pasteboard.clearIfOwned()
        #expect(board.items.count == 1, "cleared before it could be pasted")
        // A sensitive copy made before is replaced, and nothing clears the hand-off.
        pasteboard.copy("12345 67890", sensitivity: .sensitive)
        pasteboard.copy("k7mq-x2pd-9tfa-wn3h-b8ze", sensitivity: .handOff)
        pasteboard.clearIfOwned()
        #expect(board.items.count == 1)
    }
}

@Suite("Strings")
struct RuntimeStringsTests {
    @Test func everyKeyHasBothLanguages() {
        for key in RuntimeString.allCases {
            #expect(!key.texts.en.isEmpty)
            #expect(!key.texts.tr.isEmpty)
            #expect(key.texts.en != key.texts.tr, "\(key)")
        }
        #expect(RuntimeStrings(languageCode: "tr").string(.biometricReason) == "us2us'un kilidini aç")
        #expect(RuntimeStrings(languageCode: "de").string(.biometricReason) == "Unlock us2us")
    }

    @Test func hatLines() {
        let tr = RuntimeStrings(languageCode: "tr")
        #expect(ConnectionStatus.open.line(strings: tr) == "Hat açık · mesajlar geliyor")
        #expect(ConnectionStatus.connecting.line(strings: RuntimeStrings(languageCode: "en")) == "Opening the line…")
    }

    @Test func utf8PrefixCutsAtCharacters() {
        #expect("ağaç".utf8Prefix(3) == "ağ")
        #expect("abc".utf8Prefix(10) == "abc")
    }
}

@Suite("Media preparation (§7: no metadata)")
struct MediaPreparationTests {
    @Test func imagesAreResizedAndStripped() throws {
        let temporary = try TemporaryDirectory()
        let directories = temporary.directories
        try directories.prepare()
        let source = temporary.url.appendingPathComponent("source.jpg")
        try makeJPEGWithMetadata(width: 3000, height: 1500, at: source)
        let original = try Data(contentsOf: source)
        let originalKeys = MediaPreparation.metadataKeys(of: original)
        #expect(originalKeys.contains(kCGImagePropertyGPSDictionary as String))

        let prepared = try MediaPreparation.prepareImage(at: source, directories: directories, caption: "hi")
        let output = try Data(contentsOf: prepared.file)
        let keys = MediaPreparation.metadataKeys(of: output)
        #expect(!keys.contains(kCGImagePropertyGPSDictionary as String))
        #expect(!keys.contains(kCGImagePropertyTIFFDictionary as String))
        let exif = (CGImageSourceCopyPropertiesAtIndex(CGImageSourceCreateWithData(output as CFData, nil)!, 0, nil)
            as? [String: Any])?[kCGImagePropertyExifDictionary as String] as? [String: Any]
        #expect(exif?[kCGImagePropertyExifUserComment as String] == nil)
        #expect(output.range(of: Data("secret place".utf8)) == nil)
        #expect(output.range(of: Data("PhoneMaker".utf8)) == nil)
        #expect(prepared.width == 2048 && prepared.height == 1024)
        #expect(prepared.contentType == "image/jpeg")
        #expect(prepared.caption == "hi")
        let thumbnail = try #require(prepared.thumbnail)
        #expect(thumbnail.count <= ContentLimits.maxThumbnailBytes)
        #expect(MediaPreparation.metadataKeys(of: thumbnail).contains(kCGImagePropertyGPSDictionary as String) == false)
        #expect(prepared.pendingAttachment.size == UInt64(output.count))
        #expect(prepared.pendingAttachment.mediaID.isEmpty)
    }
}

/// Static checks over the module's sources: the privacy rules of
/// `RuntimeLog` and "no network except the relay" hold everywhere.
@Suite("Privacy lint")
struct PrivacyLintTests {
    static func sources() throws -> [(name: String, text: String)] {
        let root = RepositoryPaths.runtimeSources
        let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil)
        var result: [(String, String)] = []
        while let url = enumerator?.nextObject() as? URL {
            guard url.pathExtension == "swift" else { continue }
            result.append((url.lastPathComponent, try String(contentsOf: url, encoding: .utf8)))
        }
        return result
    }

    @Test func noPrintingOrForeignLoggers() throws {
        let sources = try Self.sources()
        #expect(sources.count > 10)
        let forbidden = ["print(", "NSLog(", "debugPrint(", "dump(", "os_log(", "Logger(subsystem"]
        for (name, text) in sources {
            for (number, line) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
                let code = line.split(separator: "//", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""
                for token in forbidden where code.contains(token) {
                    if token == "Logger(subsystem" && name == "RuntimeLog.swift" { continue }
                    Issue.record("\(name):\(number + 1) uses \(token)")
                }
            }
        }
    }

    @Test func logInterpolationsStatePrivacy() throws {
        let pattern = try NSRegularExpression(pattern: #"RuntimeLog\.\w+\.(debug|info|notice|error|fault)\("(.*)"\)"#)
        for (name, text) in try Self.sources() {
            for line in text.split(separator: "\n") {
                let string = String(line)
                guard let match = pattern.firstMatch(in: string, range: NSRange(string.startIndex..., in: string)),
                    let range = Range(match.range(at: 2), in: string)
                else { continue }
                let message = string[range]
                var rest = message[...]
                while let start = rest.range(of: #"\("#) {
                    let tail = rest[start.upperBound...]
                    guard let end = tail.range(of: ")") else { break }
                    let interpolation = tail[..<end.lowerBound]
                    let ok =
                        interpolation.contains("privacy: .private")
                        || (interpolation.contains("privacy: .public")
                            && (interpolation.contains("logLabel") || interpolation.contains("rawValue")
                                || interpolation.contains("code")))
                    #expect(ok, "\(name): \(interpolation)")
                    rest = tail[end.upperBound...]
                }
            }
        }
    }

    @Test func noNetworkBesidesTheRelay() throws {
        for (name, text) in try Self.sources() {
            #expect(!text.contains("URLSession.shared"), "\(name) uses URLSession.shared")
            #expect(!text.contains("URLSession("), "\(name) creates its own URLSession")
            #expect(!text.contains("NWConnection"), "\(name) opens raw connections")
            for scheme in ["http://", "https://"] where text.contains(scheme) {
                // Only documentation examples and the loopback rule may name a URL.
                let lines = text.split(separator: "\n").filter { $0.contains(scheme) }
                for line in lines {
                    let trimmed = line.trimmingCharacters(in: .whitespaces)
                    #expect(trimmed.hasPrefix("///") || trimmed.hasPrefix("//"), "\(name): \(trimmed)")
                }
            }
        }
    }
}
