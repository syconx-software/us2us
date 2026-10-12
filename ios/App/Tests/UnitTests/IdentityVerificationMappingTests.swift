import Foundation
import Testing

@testable import us2us

// The live service's mapping of the contact's identity state (5.1 prompt and
// banner, 6.1 reload) and of the safety-number system lines.

private let now = Date(timeIntervalSince1970: 1_791_000_000)

private func record(verified: Bool = false, changed: Bool = false, key: Data? = nil) -> LiveChatRecord {
    LiveChatRecord(
        peer: "6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10", username: "deniz", usernameVerified: true, displayName: nil,
        requestState: .accepted, verified: verified, safetyNumberChanged: changed, presenceSharing: false,
        ghostEnabled: false, ghostTimeoutSeconds: 0, ghostDeadline: nil, expireTimerSeconds: 0, unreadCount: 0,
        lastActivity: now, lastMessage: nil, identityKey: key)
}

@Suite("Live service · identity state")
@MainActor
struct IdentityVerificationMappingTests {
    let key = Data([5] + Array(repeating: UInt8(7), count: 32))
    let otherKey = Data([5] + Array(repeating: UInt8(8), count: 32))

    private func peer(_ chat: LiveChatRecord) -> Peer {
        LiveMapping.peer(chat, presence: nil, now: now, ownSharing: true)
    }

    @Test func noKeyNoPrompt() {
        let unknown = peer(record())
        #expect(unknown.identityFingerprint == nil)
        #expect(!unknown.offersVerification, "nothing to compare before the first key")
    }

    @Test func anUnverifiedKeyOffersVerification() {
        let unverified = peer(record(key: key))
        #expect(unverified.identityFingerprint == LiveMapping.identityFingerprint(key))
        #expect(unverified.offersVerification)
        #expect(!peer(record(verified: true, key: key)).offersVerification)
    }

    @Test func aPendingChangeShowsTheBannerNotThePrompt() {
        let changed = peer(record(verified: true, changed: true, key: key))
        #expect(changed.safetyNumberChanged)
        #expect(!changed.isVerified)
        #expect(!changed.offersVerification)
    }

    @Test func theFingerprintFollowsTheKey() {
        #expect(peer(record(key: key)).identityFingerprint != peer(record(key: otherKey)).identityFingerprint)
        #expect(peer(record(key: key)) != peer(record(key: otherKey)), "a new key changes the peer, so 6.1 reloads")
    }

    @Test func acceptingAndVerifyingAreDifferentLines() {
        #expect(LiveMapping.events(of: .safetyNumberAccepted, ownUsername: "selin_n", peerUsername: "deniz") == [.safetyNumberAccepted])
        #expect(LiveMapping.events(of: .safetyNumberVerified, ownUsername: "selin_n", peerUsername: "deniz") == [.safetyNumberVerified])
        #expect(LiveMapping.events(of: .safetyNumberChanged, ownUsername: "selin_n", peerUsername: "deniz") == [.safetyNumberChanged])
    }

    @Test func aVerificationWithoutARecordShowsNoDate() {
        let info = SafetyNumberInfo(
            groups: [], scannablePayload: Data(), verifiedAt: nil, method: nil, isVerifiedWithoutRecord: true)
        #expect(info.isVerified)
        #expect(VerificationText.verifiedLine(info, locale: Locale(identifier: "tr")) == nil)
    }
}
