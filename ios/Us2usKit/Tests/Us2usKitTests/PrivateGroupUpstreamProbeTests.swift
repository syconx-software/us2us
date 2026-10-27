import Foundation
import LibSignalClient
import Testing

/// Pinned upstream feasibility only. No production transport, membership policy or keys.
@Suite("Private-group upstream feasibility; not a BRP transport")
struct PrivateGroupUpstreamProbeTests {
    struct Fixture: Decodable {
        let libsignalVersion: String
        let accountId: String
        let redemptionTime: UInt64
        let verificationTime: UInt64
        let masterKeyHex: String
        let saltHex: String
        let serverSecretParamsHex: String
        let serverPublicParamsHex: String
        let groupPublicParamsHex: String
        let memberCiphertextHex: String
        let credentialResponseHex: String
        let presentationHex: String
    }

    let fixture: Fixture
    let aci: Aci
    let group: GroupSecretParams
    let issuer: ServerZkAuthOperations
    let client: ClientZkAuthOperations

    init() throws {
        let url = RepositoryPaths.testVectors.appendingPathComponent("communities-privacy-probe.json")
        let f = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
        fixture = f
        aci = Aci(fromUUID: try #require(UUID(uuidString: f.accountId)))
        group = try GroupSecretParams.deriveFromMasterKey(groupMasterKey: GroupMasterKey(contents: Data(hex: f.masterKeyHex)))
        issuer = ServerZkAuthOperations(serverSecretParams: try ServerSecretParams(contents: Data(hex: f.serverSecretParamsHex)))
        client = ClientZkAuthOperations(serverPublicParams: try ServerPublicParams(contents: Data(hex: f.serverPublicParamsHex)))
    }

    var now: Date { Date(timeIntervalSince1970: TimeInterval(fixture.verificationTime)) }
    func proof() throws -> AuthCredentialPresentation { try AuthCredentialPresentation(contents: Data(hex: fixture.presentationHex)) }
    func credential() throws -> AuthCredentialWithPni {
        try client.receiveAuthCredentialWithoutPni(aci: aci, salt: Data(hex: fixture.saltHex),
            redemptionTime: fixture.redemptionTime,
            authCredentialResponse: AuthCredentialWithPniResponse(contents: Data(hex: fixture.credentialResponseHex)))
    }
    func otherGroup() throws -> GroupSecretParams {
        try GroupSecretParams.deriveFromMasterKey(groupMasterKey: GroupMasterKey(contents: Data(repeating: 0x12, count: 32)))
    }
    func verify(_ p: AuthCredentialPresentation, group g: GroupSecretParams? = nil, at date: Date? = nil) throws {
        try issuer.verifyAuthCredentialPresentation(groupPublicParams: (g ?? group).getPublicParams(),
            authCredentialPresentation: p, now: date ?? now)
    }

    @Test func sharedJavaFixtureAndNoPhoneCredentialVerify() throws {
        #expect(fixture.libsignalVersion == "0.105.0")
        #expect(try group.getPublicParams().serialize() == Data(hex: fixture.groupPublicParamsHex))
        let supplied = try proof()
        try verify(supplied)
        #expect(try supplied.getUuidCiphertext().serialize() == Data(hex: fixture.memberCiphertextHex))
        let fresh = try client.createAuthCredentialPresentation(groupSecretParams: group, authCredential: credential())
        try verify(fresh)
        #expect(try fresh.getUuidCiphertext().serialize() == Data(hex: fixture.memberCiphertextHex))
    }

    @Test func encryptedIdentifiersAreScopedToTheGroup() throws {
        let cipher = ClientZkGroupCipher(groupSecretParams: group)
        let encrypted = try cipher.encrypt(aci)
        #expect(encrypted.serialize() == Data(hex: fixture.memberCiphertextHex))
        #expect(try encrypted.serialize() == cipher.encrypt(aci).serialize())
        #expect(try cipher.decrypt(encrypted) == aci)
        let other = ClientZkGroupCipher(groupSecretParams: try otherGroup())
        #expect(try encrypted.serialize() != other.encrypt(aci).serialize())
        #expect(throws: (any Error).self) { try other.decrypt(encrypted) }
    }

    @Test func credentialRejectsAnotherAccountSaltOrIssuer() throws {
        let response = try AuthCredentialWithPniResponse(contents: Data(hex: fixture.credentialResponseHex))
        #expect(throws: (any Error).self) {
            try client.receiveAuthCredentialWithoutPni(aci: Aci(fromUUID: UUID(uuidString: "00000000-0000-0000-0000-000000000002")!),
                salt: Data(hex: fixture.saltHex), redemptionTime: fixture.redemptionTime, authCredentialResponse: response)
        }
        #expect(throws: (any Error).self) {
            try client.receiveAuthCredentialWithoutPni(aci: aci, salt: Data(repeating: 0x45, count: 32),
                redemptionTime: fixture.redemptionTime, authCredentialResponse: response)
        }
        let other = ClientZkAuthOperations(serverPublicParams: try ServerSecretParams.generate().getPublicParams())
        #expect(throws: (any Error).self) {
            try other.receiveAuthCredentialWithoutPni(aci: aci, salt: Data(hex: fixture.saltHex),
                redemptionTime: fixture.redemptionTime, authCredentialResponse: response)
        }
    }

    @Test func proofRejectsWrongGroupIssuerAndExpiry() throws {
        let p = try proof()
        let other = try otherGroup()
        #expect(throws: (any Error).self) { try verify(p, group: other) }
        let wrongIssuer = ServerZkAuthOperations(serverSecretParams: try ServerSecretParams.generate())
        #expect(throws: (any Error).self) {
            try wrongIssuer.verifyAuthCredentialPresentation(groupPublicParams: group.getPublicParams(), authCredentialPresentation: p, now: now)
        }
        #expect(throws: (any Error).self) { try verify(p, at: now.addingTimeInterval(7 * 86400)) }
    }

    @Test func randomizedPresentationsKeepTheSameMemberCiphertext() throws {
        let c = try credential()
        let first = try client.createAuthCredentialPresentation(groupSecretParams: group, authCredential: c)
        let second = try client.createAuthCredentialPresentation(groupSecretParams: group, authCredential: c)
        #expect(first.serialize() != second.serialize())
        for p in [first, second] {
            try verify(p)
            #expect(try p.getUuidCiphertext().serialize() == Data(hex: fixture.memberCiphertextHex))
        }
        // Different serialized bytes alone do not establish unlinkability.
    }

    @Test func validAuthenticationProofDoesNotEnforceTheApprovedRoster() throws {
        let outsider = Aci(fromUUID: UUID(uuidString: "00000000-0000-0000-0000-000000000003")!)
        let salt = Data(hex: fixture.saltHex)
        let response = try issuer.issueAuthCredentialZkcWithoutPni(aci: outsider, salt: salt, redemptionTime: fixture.redemptionTime)
        let c = try client.receiveAuthCredentialWithoutPni(aci: outsider, salt: salt,
            redemptionTime: fixture.redemptionTime, authCredentialResponse: response)
        let p = try client.createAuthCredentialPresentation(groupSecretParams: group, authCredential: c)
        try verify(p)
        #expect(try p.getUuidCiphertext().serialize() != Data(hex: fixture.memberCiphertextHex))
        // A future service must additionally enforce active membership, role and revision.
    }

    @Test func stateBlobRejectsTamperingAndOtherGroupSecrets() throws {
        let cipher = ClientZkGroupCipher(groupSecretParams: group)
        let plaintext = Data("SYNTHETIC-GROUP-STATE".utf8)
        let blob = try cipher.encryptBlob(plaintext: plaintext)
        #expect(try cipher.decryptBlob(blobCiphertext: blob) == plaintext)
        let other = ClientZkGroupCipher(groupSecretParams: try otherGroup())
        #expect(throws: (any Error).self) { try other.decryptBlob(blobCiphertext: blob) }
        var changed = blob
        // The upstream reserved trailer is ignored; corrupt authenticated ciphertext instead.
        changed[0] ^= 1
        #expect(throws: (any Error).self) { try cipher.decryptBlob(blobCiphertext: changed) }
    }

    @Test func reservedBlobTrailerIsNotAnAuthenticatedApplicationVersion() throws {
        let cipher = ClientZkGroupCipher(groupSecretParams: group)
        let plaintext = Data("SYNTHETIC-GROUP-STATE".utf8)
        var blob = try cipher.encryptBlob(plaintext: plaintext)
        blob[blob.count - 1] ^= 1
        #expect(try cipher.decryptBlob(blobCiphertext: blob) == plaintext)
        // Future version/state bindings must use a reviewed authenticated encoding.
    }

    @Test func oneHundredEncryptedMemberIdentifiersRoundtrip() throws {
        let cipher = ClientZkGroupCipher(groupSecretParams: group)
        var ciphertexts = Set<Data>()
        for value in 1...100 {
            let id = Aci(fromUUID: UUID(uuidString: String(format: "00000000-0000-0000-0000-%012x", value))!)
            let encrypted = try cipher.encrypt(id)
            ciphertexts.insert(encrypted.serialize())
            #expect(try cipher.decrypt(encrypted) == id)
        }
        #expect(ciphertexts.count == 100)
        // Identifier handling only, not a service capacity/authorization benchmark.
    }
}
