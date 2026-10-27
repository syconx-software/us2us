import CryptoKit
import Foundation
import LibSignalClient
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// The communities extension (docs/COMMUNITIES.md) between this runtime and
/// the Android runtime over a real relay. The other side is the Kotlin
/// `LiveCommunityInteropTest` (android/runtime): both play the script below
/// and meet in `COMMUNITY_DIR`, where each writes one small JSON file per
/// step (`ios-<step>.json`, `kotlin-<step>.json`) and waits for the other's.
/// Skipped unless `RELAY_URL` and `COMMUNITY_DIR` are set (`TEST_RUNNER_…`
/// with xcodebuild).
///
/// This phone is "S"; Kotlin plays the owner "K1" and a third account "K3".
///
/// 1. K1 creates the public community "Aile 👨‍👩‍👧"; S finds it by search
///    and asks to join; K1 approves (revision 2); S checks the signed roster;
///    S and K1 write to each other.
/// 2. S creates a private community and two invitations; K3 and K1 redeem
///    them, S approves both (revision 3); K3 also joins the public one.
///    Messages go both ways; nobody became anybody's personal contact.
/// 3. K1 and S start a one-to-one conversation (request, acceptance, reply).
/// 3v. Extension v2 (`extensionV2`): a photo with a caption and a file from
///    each platform, downloaded and compared by every recipient; DELIVERY
///    and READ per member (K3 keeps read receipts off, S reads one text with
///    them off); delete for everyone by the sender and by the owner on each
///    platform, blobs gone from the relay; a forged delete dropped; a delete
///    ahead of its message; one-to-one in between.
/// 4. S removes K3 from its private community: K3 can no longer send or
///    fetch there (its queued copy is gone), S and K1 go on writing.
/// 5. K3 leaves the public community (`DELETE …/members/me`, 204); S's next
///    message still reaches K1; K1 publishes revision 4 without K3; S and K1
///    go on, the one-to-one conversation too.
@Suite(
    "Live communities with another platform",
    .serialized,
    .enabled(
        if: LiveRelay.url != nil && CommunityMeeting.directory != nil,
        "set RELAY_URL and COMMUNITY_DIR (TEST_RUNNER_… with xcodebuild)"),
    .timeLimit(.minutes(15)))
struct LiveCommunityInteropTests {
    typealias Party = LiveRuntimeTests.Party

    static let publicName = "Aile 👨‍👩‍👧"
    static let privateName = "Gizli Çay ☕"

    @Test func communitiesWithTheAndroidRuntime() async throws {
        let meeting = CommunityMeeting(directory: try #require(CommunityMeeting.directory))
        let me = try await Party(username: LiveRuntimeTests.username("ios_c"), relay: try #require(LiveRelay.url))
        do {
            try await Self.script(Side(party: me, meeting: meeting))
        } catch {
            try? meeting.post("ios-failed", ["error": "\(error)"])
            await me.shutdown()
            throw error
        }
        await me.shutdown()
    }

    static func script(_ s: Side) async throws {
        try await s.party.connect()
        let own = s.backend.accountId
        let ownKey = try s.backend.protocolStore.identityKeyPair(context: NullContext()).identityKey.serialize()
        let me = CommunityMember(accountId: own, identityKey: ownKey)
        try s.meeting.post(
            "ios-ready",
            ["accountId": own.string, "username": s.party.username, "identityKey": ownKey.base64URLEncodedString()])

        // 1. The Kotlin owner's public community: search, request, approval.
        let created = try await s.meeting.wait("kotlin-public")
        let publicId = try #require(created["communityId"].flatMap(AccountId.init(string:)))
        let owner = try #require(created.member("owner"))
        let third = try #require(created.member("third"))
        #expect(created["name"] == publicName)
        let summary = try await s.find(publicId, query: "Aile")
        #expect(summary.name == publicName)
        #expect(summary.visibility == "public")
        #expect(summary.ownerAccountId == owner.accountId && summary.ownerIdentityKey == owner.identityKey)
        #expect(summary.memberCount == 1 && summary.revision == 1)
        // Prefix search ignores case.
        #expect(try await s.find(publicId, query: "aile").id == publicId)
        try await s.communities.requestJoin(summary)
        try s.meeting.post("ios-requested")

        _ = try await s.meeting.wait("kotlin-approved")
        let approved = try await s.state(publicId, revision: 2)
        #expect(approved.isPublic && approved.name == publicName)
        #expect(approved.owner == owner)
        #expect(Set(approved.members.map(\.accountId)) == [owner.accountId, own])
        #expect(approved.member(own) == me && approved.member(owner.accountId) == owner)
        // The relay's copy verifies against the owner key pinned from the directory.
        let fetched = try await s.backend.messenger.relay.community(publicId).verified(ownerKey: owner.identityKey)
        #expect(fetched == approved)

        let hello = "Merhaba aile 👋 iOS'tan"
        try await s.send(hello, to: publicId)
        try s.meeting.post("ios-public-sent", ["text": hello])
        let reply = try #require(try await s.meeting.wait("kotlin-public-sent")["text"])
        try await s.awaitText(reply, from: owner.accountId, in: publicId)

        // 2. A private community owned here, joined by invitation.
        try await s.communities.create(name: privateName, isPublic: false)
        let privateGroup = try #require(try await s.communities.groups().first { $0.name == privateName })
        let privateId = privateGroup.id
        let privateState = try #require(privateGroup.state)
        #expect(!privateState.isPublic && privateState.name.isEmpty, "a private name never reaches the relay")
        #expect(privateState.owner == me && privateState.revision == 1)
        let inviteThird = try await s.communities.invite(privateId)
        let inviteOwner = try await s.communities.invite(privateId)
        try s.meeting.post(
            "ios-private",
            ["communityId": privateId.string, "inviteThird": inviteThird, "inviteOwner": inviteOwner])

        _ = try await s.meeting.wait("kotlin-redeemed")
        let requests = try await s.communities.requests(privateId)
        let thirdRequest = try #require(requests.first { $0.accountId == third.accountId })
        let ownerRequest = try #require(requests.first { $0.accountId == owner.accountId })
        #expect(thirdRequest.member == third && ownerRequest.member == owner)
        try await s.communities.approve(thirdRequest, in: privateId)
        try await s.communities.approve(ownerRequest, in: privateId)
        let joined = try await s.state(privateId, revision: 3)
        #expect(Set(joined.members.map(\.accountId)) == [own, owner.accountId, third.accountId])
        try s.meeting.post("ios-private-approved", ["revision": "3"])

        let fromThird = try #require(try await s.meeting.wait("kotlin-private-sent")["text"])
        try await s.awaitText(fromThird, from: third.accountId, in: privateId)
        // K1 admitted K3 to the public community meanwhile.
        let withThird = try await s.state(publicId, revision: 3)
        #expect(Set(withThird.members.map(\.accountId)) == [owner.accountId, own, third.accountId])
        // Community traffic made nobody a personal contact.
        #expect(try await s.party.engine.chatSummaries().isEmpty)
        #expect(try await s.party.engine.contactInfo(owner.accountId) == nil)
        #expect(try await s.party.engine.contactInfo(third.accountId) == nil)
        let welcome = "Hoş geldin, gizli çaya ☕"
        try await s.send(welcome, to: privateId)
        try s.meeting.post("ios-private-sent", ["text": welcome])

        // 3. One-to-one with K1, on the ratchet the communities already use.
        let direct = try #require(try await s.meeting.wait("kotlin-direct")["text"])
        try await eventually(timeout: 60, "K1's message request") {
            try await s.party.engine.chatSummaries().contains { $0.id == owner.accountId && $0.isRequest }
        }
        #expect(try await s.party.messages(with: owner.accountId).map(\.body) == [.text(direct)])
        try await s.party.engine.acceptRequest(owner.accountId)
        let directReply = "Birebir: selam, burası iOS"
        try await s.party.engine.send(.text(directReply), to: owner.accountId)
        try s.meeting.post("ios-direct", ["text": directReply])

        // 3v. Extension v2: media, receipts and delete for everyone, the one-to-one path alongside.
        let (directAfterMedia, v2Reply) = try await extensionV2(s, owner: owner, third: third, publicId: publicId, privateId: privateId)

        try await extensionV3(s, owner: owner, privateId: privateId)

        // 4. Removal: K3 leaves the private roster by the owner's signed state.
        let before = try await s.meeting.wait("kotlin-before-remove")
        try await s.awaitText(try #require(before["public"]), from: owner.accountId, in: publicId)
        try await s.awaitText(try #require(before["private"]), from: owner.accountId, in: privateId)
        try await s.communities.remove(third, from: privateId)
        let removed = try await s.state(privateId, revision: 4)
        #expect(Set(removed.members.map(\.accountId)) == [own, owner.accountId])
        try s.meeting.post("ios-removed", ["revision": "4"])
        let afterRemoval = try #require(try await s.meeting.wait("kotlin-after-remove")["text"])
        try await s.awaitText(afterRemoval, from: owner.accountId, in: privateId)
        let stillHere = "Kaldık iki kişi, devam"
        try await s.send(stillHere, to: privateId)
        try s.meeting.post("ios-after-remove", ["text": stillHere])

        // 5. K3 left the public community; its roster still lists K3 until K1 publishes.
        _ = try await s.meeting.wait("kotlin-left")
        let afterLeave = "K3 ayrıldı, mesajlar sürüyor"
        // The relay refuses the leaver (404): this phone reports the message partial, K1 still gets it.
        try await s.send(afterLeave, to: publicId, settled: [.sent, .partial])
        try s.meeting.post("ios-after-leave", ["text": afterLeave])
        let updated = try await s.meeting.wait("kotlin-owner-updated")
        try await s.awaitText(try #require(updated["text"]), from: owner.accountId, in: publicId)
        let withoutThird = try await s.state(publicId, revision: 4)
        #expect(Set(withoutThird.members.map(\.accountId)) == [owner.accountId, own])

        let lastDirect = "Birebir: hâlâ buradayım"
        try await s.party.engine.send(.text(lastDirect), to: owner.accountId)
        try s.meeting.post("ios-final", ["text": lastDirect])
        _ = try await s.meeting.wait("kotlin-done")

        // Neither path leaked into the other.
        #expect(try await s.party.engine.chatSummaries().map(\.id) == [owner.accountId])
        #expect(try await s.party.engine.contactInfo(third.accountId) == nil)
        let directTexts = Set(try await s.party.messages(with: owner.accountId).compactMap { item -> String? in
            if case .text(let text) = item.body { return text }
            return nil
        })
        #expect(directTexts == [direct, directReply, directAfterMedia, v2Reply, lastDirect])
        var communityTexts = Set<String>()
        for id in [publicId, privateId] { for item in try await s.communities.messages(id) { communityTexts.insert(item.text) } }
        #expect(communityTexts.isDisjoint(with: directTexts))
        #expect(communityTexts.isSuperset(of: [hello, reply, welcome, fromThird, stillHere, afterLeave]))
        try await s.communities.delete(privateId)
    }

    static func extensionV2(
        _ s: Side, owner: CommunityMember, third: CommunityMember, publicId: AccountId, privateId: AccountId
    ) async throws -> (String, String) {
        let own = s.backend.accountId
        let k1 = owner.accountId
        let k3 = third.accountId

        // A text read with read receipts off: DELIVERY goes, READ never.
        let keep = try await s.meeting.wait("kotlin-v2-text")
        let keepId = try #require(keep["messageId"].flatMap(AccountId.init(string:)))
        try await s.awaitText(try #require(keep["text"]), from: k1, in: privateId)
        try await s.display(privateId, readReceipts: false)
        try s.meeting.post("ios-v2-text-seen")

        // K1's photo and file: downloaded, compared, displayed with read receipts on (READ for both).
        let sent = try await s.meeting.wait("kotlin-v2-sent")
        let kImage = try #require(sent["imageId"].flatMap(AccountId.init(string:)))
        let kFile = try #require(sent["fileId"].flatMap(AccountId.init(string:)))
        let kImageFacts = try #require(MediaFacts(sent, prefix: "image"))
        let kFileFacts = try #require(MediaFacts(sent, prefix: "file"))
        #expect(kImageFacts.kind == .image && kFileFacts.kind == .file && kFileFacts.fileName == "plan.pdf")
        try await s.receiveMedia(kImageFacts, from: k1, messageId: kImage, in: privateId)
        try await s.receiveMedia(kFileFacts, from: k1, messageId: kFile, in: privateId)
        try await s.display(privateId, readReceipts: true)
        let publicThirdId = try #require(sent["publicThirdId"].flatMap(AccountId.init(string:)))
        try await s.awaitText(try #require(sent["publicThirdText"]), from: k3, in: publicId)

        // This phone's photo with a caption and file, and a text K1 will remove as the owner.
        let photo = Data((0..<4000).map { UInt8($0 % 241) })
        let file = Data((0..<1200).map { UInt8($0 % 17) })
        let caption = "Gün batımı, iOS'tan 🌇"
        let imageId = try await s.sendMedia(photo, kind: .image, contentType: "image/jpeg", caption: caption, to: privateId)
        let fileId = try await s.sendMedia(file, kind: .file, contentType: "text/plain", fileName: "notlar.txt", to: privateId)
        let imageFacts = try await s.facts(imageId, in: privateId)
        let fileFacts = try await s.facts(fileId, in: privateId)
        #expect(imageFacts.caption == caption && fileFacts.fileName == "notlar.txt" && fileFacts.caption.isEmpty)
        let moderated = "Sahip bunu silecek"
        try await s.send(moderated, to: publicId)
        let moderatedId = try #require(try await s.communities.messages(publicId).first { $0.outgoing && $0.text == moderated }?.messageId)
        var values = [
            "imageId": imageId.string, "fileId": fileId.string, "publicModeratedText": moderated, "publicModeratedId": moderatedId.string,
        ]
        values.merge(imageFacts.values(prefix: "image")) { _, new in new }
        values.merge(fileFacts.values(prefix: "file")) { _, new in new }
        try s.meeting.post("ios-v2-sent", values)
        // K1 (read receipts on) delivered and read both; K3 (off) delivered only.
        for id in [imageId, fileId] {
            try await s.awaitReceipt(id, in: privateId, from: k1, read: true)
            try await s.awaitReceipt(id, in: privateId, from: k3, read: false)
            #expect(try await s.recipient(id, in: privateId, member: k3).readAt == nil, "K3 keeps read receipts off")
        }

        // K1 deleted its photo: the placeholder here, the blob gone from the relay.
        _ = try await s.meeting.wait("kotlin-v2-deleted")
        try await s.awaitPlaceholder(from: k1, messageId: kImage, in: privateId, by: .bySender)
        #expect(try await s.mediaStatus(kImageFacts.mediaId) == 404)

        // The same from here: this phone's photo, K1's file as the owner, a forged delete in K1's
        // community (K3's text, neither this phone's nor its community), a delete ahead of its text.
        try await s.communities.deleteForEveryone(sender: own, messageId: imageId, in: privateId)
        #expect(await s.communities.flushNow() == nil)
        try await s.assertPlaceholder(from: own, messageId: imageId, in: privateId, by: .bySender)
        try await eventually(timeout: 60, "this phone's blob deleted") { try await s.mediaStatus(imageFacts.mediaId) == 404 }
        try await s.communities.deleteForEveryone(sender: k1, messageId: kFile, in: privateId)
        #expect(await s.communities.flushNow() == nil)
        try await s.assertPlaceholder(from: k1, messageId: kFile, in: privateId, by: .byOwner)
        let publicState = try await s.state(publicId, revision: 3)
        try await s.sendRaw(
            .delete(target: publicThirdId.bytes, sender: k3, community: publicId, revision: publicState.revision), to: k1, in: publicState)
        let afterForged = "iOS: sahte silmeden sonra"
        try await s.send(afterForged, to: publicId)
        let privateState = try await s.state(privateId, revision: 3)
        let earlyId = try #require(AccountId(bytes: RandomId.make()))
        try await s.sendRaw(.delete(target: earlyId.bytes, sender: own, community: privateId, revision: privateState.revision), to: k1, in: privateState)
        try await s.sendRaw(.text("Silme önce geldi (iOS)", community: privateId, revision: privateState.revision, messageId: earlyId.bytes), to: k1, in: privateState)
        try s.meeting.post("ios-v2-deleted", ["imageId": imageId.string, "fileId": kFile.string, "afterForgedText": afterForged, "earlyId": earlyId.string])

        // K1 removed this phone's public text as the owner, K3 forged a delete, K1 sent one early.
        let events = try await s.meeting.wait("kotlin-v2-events")
        try await s.awaitPlaceholder(from: own, messageId: moderatedId, in: publicId, by: .byOwner)
        try await s.awaitText(try #require(events["afterForgedText"]), from: k3, in: privateId)
        let kept = try #require(try await s.communities.messages(privateId).first { $0.sender == k1 && $0.messageId == keepId })
        #expect(kept.deletion == nil && kept.text == keep["text"], "K3's forged delete of K1's text was dropped")
        let kEarly = try #require(events["earlyId"].flatMap(AccountId.init(string:)))
        try await s.awaitPlaceholder(from: k1, messageId: kEarly, in: privateId, by: .bySender)
        // K1's file (removed by this owner) is gone from the relay; this phone's file stays.
        #expect(try await s.mediaStatus(kFileFacts.mediaId) == 404)
        #expect(try await s.communities.mediaData(sender: own, messageId: fileId, in: privateId) == file)

        // One-to-one between the same accounts still works.
        let direct = try #require(try await s.meeting.wait("kotlin-v2-direct")["text"])
        try await eventually(timeout: 60, "K1's one-to-one text after media") {
            try await s.party.messages(with: k1).contains { $0.body == .text(direct) }
        }
        let reply = "Birebir: iOS'ta da medyadan sonra"
        try await s.party.engine.send(.text(reply), to: k1)
        try s.meeting.post("ios-v2-direct", ["text": reply])
        return (direct, reply)
    }

    static func extensionV3(_ s: Side, owner: CommunityMember, privateId: AccountId) async throws {
        let sent = try await s.meeting.wait("kotlin-v3-sent")
        let stickerId = try #require(sent["stickerId"].flatMap(AccountId.init(string:)))
        let voiceId = try #require(sent["voiceId"].flatMap(AccountId.init(string:)))
        let facts = try #require(MediaFacts(sent, prefix: "voice"))
        try await s.receiveMedia(facts, from: owner.accountId, messageId: voiceId, in: privateId)
        let items = try await s.communities.messages(privateId)
        let sticker = try #require(items.first { $0.sender == owner.accountId && $0.messageId == stickerId }?.sticker)
        #expect(sticker.packID == "us2us.cups" && sticker.stickerID == 1 && sticker.emoji == "👋")
        let pointer = try #require(items.first { $0.messageId == voiceId }?.attachment?.descriptor)
        #expect(pointer.waveform == Data([0, 128, 255]) && pointer.durationMs == 2000)
        _ = try await s.communities.sendSticker(sticker, to: privateId)
        _ = await s.communities.flushNow()
        let ownSticker = try #require(try await s.communities.messages(privateId).last { $0.outgoing && $0.sticker != nil })
        let audio = Data((0..<4000).map { UInt8($0 % 239) })
        let voice = try await s.sendMedia(audio, kind: .voice, contentType: "audio/mp4", to: privateId)
        var values = try await s.facts(voice, in: privateId).values(prefix: "voice")
        values["stickerId"] = ownSticker.messageId.string
        values["voiceId"] = voice.string
        try s.meeting.post("ios-v3-sent", values)
        _ = try await s.meeting.wait("kotlin-v3-seen")
    }

    /// What both platforms compare about one media message: the pointer every member got and the plaintext.
    struct MediaFacts: Sendable {
        let kind: BRPAttachment.Kind
        let mediaId: String
        let digest: String
        let size: UInt64
        let sha256: Data
        let caption: String
        let fileName: String

        init(kind: BRPAttachment.Kind, mediaId: String, digest: String, size: UInt64, sha256: Data, caption: String, fileName: String) {
            self.kind = kind
            self.mediaId = mediaId
            self.digest = digest
            self.size = size
            self.sha256 = sha256
            self.caption = caption
            self.fileName = fileName
        }

        /// The Kotlin side's names: `IMAGE`, `VIDEO`, `FILE`.
        init?(_ values: [String: String], prefix: String) {
            let kinds: [String: BRPAttachment.Kind] = ["IMAGE": .image, "VIDEO": .video, "FILE": .file, "VOICE": .voice]
            guard let kind = values[prefix + "Kind"].flatMap({ kinds[$0] }), let mediaId = values[prefix + "MediaId"],
                  let digest = values[prefix + "Digest"], let size = values[prefix + "Size"].flatMap(UInt64.init),
                  let sha256 = values[prefix + "Sha256"].flatMap(Data.init(base64URLEncoded:)), sha256.count == 32,
                  let caption = values[prefix + "Caption"], let fileName = values[prefix + "FileName"] else { return nil }
            self.init(kind: kind, mediaId: mediaId, digest: digest, size: size, sha256: sha256, caption: caption, fileName: fileName)
        }

        func values(prefix: String) -> [String: String] {
            let names: [BRPAttachment.Kind: String] = [.image: "IMAGE", .video: "VIDEO", .file: "FILE", .voice: "VOICE"]
            return [
                prefix + "Kind": names[kind] ?? "", prefix + "MediaId": mediaId, prefix + "Digest": digest, prefix + "Size": String(size),
                prefix + "Sha256": sha256.base64URLEncodedString(), prefix + "Caption": caption, prefix + "FileName": fileName,
            ]
        }
    }

    static func sha256(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }

    /// One account of the script with its community engine and the meeting place.
    struct Side: Sendable {
        let party: Party
        let meeting: CommunityMeeting
        let backend: MessengerBackend
        let communities: CommunityEngine

        init(party: Party, meeting: CommunityMeeting) throws {
            self.party = party
            self.meeting = meeting
            backend = try #require(party.backend as? MessengerBackend)
            communities = CommunityEngine(backend: backend, storage: party.storage)
        }

        /// Pages through the public directory until `id` turns up.
        func find(_ id: AccountId, query: String) async throws -> CommunitySummary {
            var after: String?
            repeat {
                let page = try await communities.search(query, after: after)
                if let found = page.communities.first(where: { $0.id == id }) { return found }
                after = page.nextCursor
            } while after != nil
            throw CommunityError.invalidState
        }

        /// Syncs until the community is active at `revision`; its verified state.
        func state(_ id: AccountId, revision: UInt64) async throws -> CommunityState {
            try await eventually(timeout: 60, "revision \(revision) of \(id)") {
                _ = try await communities.sync()
                let group = try await communities.groups().first { $0.id == id }
                return group?.isActive == true && group?.state?.revision == revision
            }
            return try #require(try await communities.groups().first { $0.id == id }?.state)
        }

        /// Sends `text` and waits until the outbox has settled it.
        func send(_ text: String, to id: AccountId, settled: Set<CommunityDeliveryStatus> = [.sent]) async throws {
            #expect(try await communities.send(text, to: id).isEmpty)
            try await eventually(timeout: 60, "\"\(text)\" delivered") {
                if let issue = await communities.flushNow() { Issue.record("flush: \(issue)") }
                let status = try await communities.messages(id).first { $0.outgoing && $0.text == text }?.status
                return status.map(settled.contains) ?? false
            }
        }

        /// Stores and delivers a photo or file (uploaded once) and waits until the outbox has settled it.
        func sendMedia(
            _ bytes: Data, kind: AttachmentItem.Kind, contentType: String, fileName: String? = nil, caption: String = "", to id: AccountId
        ) async throws -> AccountId {
            let staged = party.storage.makeStagingURL()
            try bytes.write(to: staged)
            let prepared = PreparedMedia(
                file: staged, kind: kind, contentType: contentType, fileName: fileName, size: UInt64(bytes.count),
                width: kind == .image ? 40 : 0, height: kind == .image ? 30 : 0,
                durationMs: kind == .voice ? 2000 : 0,
                thumbnail: kind == .image ? Data([0xFF, 0xD8, 0xFF, 0xD9]) : nil,
                waveform: kind == .voice ? Data([0, 128, 255]) : nil)
            let messageId = try await communities.storeMedia(prepared, caption: caption, messageId: nil, to: id)
            #expect(await communities.deliverMedia(messageId, in: id) == nil)
            try await eventually(timeout: 60, "media \(messageId) delivered") {
                if let issue = await communities.flushNow() { Issue.record("flush: \(issue)") }
                return try await communities.messages(id).first { $0.outgoing && $0.messageId == messageId }?.status == .sent
            }
            let item = try #require(try await communities.messages(id).first { $0.outgoing && $0.messageId == messageId })
            #expect(item.attachment?.state == .uploaded && item.attachment?.hasFile == true)
            #expect(item.attachment?.descriptor.deleteToken.isEmpty == true, "the token never enters the pointer")
            return messageId
        }

        /// The pointer and plaintext of an own media message, for the other side to compare.
        func facts(_ messageId: AccountId, in id: AccountId) async throws -> MediaFacts {
            let item = try #require(try await communities.messages(id).first { $0.outgoing && $0.messageId == messageId })
            let pointer = try #require(item.attachment?.descriptor)
            let bytes = try await communities.mediaData(sender: backend.accountId, messageId: messageId, in: id)
            return MediaFacts(
                kind: pointer.kind, mediaId: pointer.mediaID.base64URLEncodedString(), digest: pointer.digest.base64URLEncodedString(),
                size: pointer.size, sha256: LiveCommunityInteropTests.sha256(bytes), caption: item.text, fileName: pointer.fileName)
        }

        /// Syncs until the media message is here, checks its pointer against `facts`, downloads and checks the bytes.
        func receiveMedia(_ facts: MediaFacts, from sender: AccountId, messageId: AccountId, in id: AccountId) async throws {
            func item() async throws -> CommunityTextItem? {
                try await communities.messages(id).first { !$0.outgoing && $0.sender == sender && $0.messageId == messageId }
            }
            try await eventually(timeout: 90, "media \(messageId) from \(sender) in \(id)") {
                _ = try await communities.sync()
                if try await item() != nil { return true }
                try await Task.sleep(for: .milliseconds(300))
                return false
            }
            let received = try #require(try await item())
            let attachment = try #require(received.attachment)
            #expect(received.kind == .media && received.status == .received && !received.isDisplayed)
            #expect(attachment.kind == facts.kind && attachment.state == .remote && !attachment.hasFile)
            #expect(attachment.descriptor.mediaID.base64URLEncodedString() == facts.mediaId)
            #expect(attachment.descriptor.digest.base64URLEncodedString() == facts.digest)
            #expect(attachment.size == facts.size && received.text == facts.caption && attachment.descriptor.fileName == facts.fileName)
            #expect(attachment.descriptor.deleteToken.isEmpty && attachment.descriptor.caption.isEmpty)
            try await communities.download(sender: sender, messageId: messageId, in: id) { _ in }
            let bytes = try await communities.mediaData(sender: sender, messageId: messageId, in: id)
            #expect(UInt64(bytes.count) == facts.size && LiveCommunityInteropTests.sha256(bytes) == facts.sha256)
            #expect(try await item()?.attachment?.state == .downloaded)
        }

        /// The conversation is on screen: READ is queued (while read receipts are on) and sent.
        func display(_ id: AccountId, readReceipts: Bool) async throws {
            communities.readReceipts.withLock { $0 = readReceipts }
            #expect(try await communities.markDisplayed(id, readReceipts: readReceipts) > 0)
            if let issue = await communities.sendReads() { Issue.record("reads: \(issue)") }
        }

        func recipient(_ messageId: AccountId, in id: AccountId, member: AccountId) async throws -> CommunityRecipientItem {
            try #require(try await communities.recipients(of: messageId, in: id).first { $0.recipient == member })
        }

        /// Syncs until `member` has delivered (and, if `read`, read) this own message.
        func awaitReceipt(_ messageId: AccountId, in id: AccountId, from member: AccountId, read: Bool) async throws {
            try await eventually(timeout: 90, "\(read ? "READ" : "DELIVERY") from \(member) for \(messageId)") {
                _ = try await communities.sync()
                let recipient = try await recipient(messageId, in: id, member: member)
                if recipient.state == .accepted && recipient.deliveredAt != nil && (!read || recipient.readAt != nil) { return true }
                try await Task.sleep(for: .milliseconds(300))
                return false
            }
        }

        /// The message is the placeholder: nothing of it but who deleted it.
        func assertPlaceholder(from sender: AccountId, messageId: AccountId, in id: AccountId, by: CommunityDeletion) async throws {
            let placeholder = try #require(try await communities.messages(id).first { $0.sender == sender && $0.messageId == messageId })
            #expect(placeholder.deletion == by && placeholder.text.isEmpty && placeholder.attachment == nil)
            await #expect(throws: (any Error).self) { try await communities.mediaData(sender: sender, messageId: messageId, in: id) }
        }

        func awaitPlaceholder(from sender: AccountId, messageId: AccountId, in id: AccountId, by: CommunityDeletion) async throws {
            try await eventually(timeout: 90, "placeholder of \(messageId) in \(id)") {
                _ = try await communities.sync()
                if try await communities.messages(id).contains(where: { $0.sender == sender && $0.messageId == messageId && $0.deletion == by }) {
                    return true
                }
                try await Task.sleep(for: .milliseconds(300))
                return false
            }
            try await assertPlaceholder(from: sender, messageId: messageId, in: id, by: by)
        }

        /// One envelope straight to `recipient`, as a client would that bypasses the engine.
        func sendRaw(_ message: BRPCommunityMessage, to recipient: AccountId, in state: CommunityState) async throws {
            let member = try #require(state.member(recipient))
            let envelope = try await backend.messenger.sealCommunity(message, to: member)
            _ = try await backend.messenger.relay.sendCommunityEnvelope(
                envelope, community: state.id, recipient: recipient, messageId: try #require(AccountId(bytes: message.messageID)),
                revision: state.revision)
        }

        /// `GET /v1/media/{id}` (anonymous, like a recipient's download), answered with its bare status.
        func mediaStatus(_ mediaId: String) async throws -> Int {
            let url = try #require(LiveRelay.url).appendingPathComponent("v1/media/" + mediaId)
            let (_, response) = try await URLSession.shared.data(from: url)
            return try #require(response as? HTTPURLResponse).statusCode
        }

        /// Syncs until `text` from `sender` is stored in the community.
        func awaitText(_ text: String, from sender: AccountId, in id: AccountId) async throws {
            try await eventually(timeout: 90, "\"\(text)\" in \(id)") {
                _ = try await communities.sync()
                if try await communities.messages(id).contains(where: { !$0.outgoing && $0.sender == sender && $0.text == text }) {
                    return true
                }
                try await Task.sleep(for: .milliseconds(300))
                return false
            }
        }
    }
}

/// The directory both platforms meet in: one JSON object of strings per step.
struct CommunityMeeting: Sendable {
    static var directory: URL? {
        ProcessInfo.processInfo.environment["COMMUNITY_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
    }

    enum Failure: Error { case peerFailed(String), timedOut(String) }

    let directory: URL

    func post(_ step: String, _ values: [String: String] = [:]) throws {
        try JSONSerialization.data(withJSONObject: values).write(to: file(step), options: .atomic)
    }

    func wait(_ step: String, timeout: TimeInterval = 300) async throws -> [String: String] {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let data = try? Data(contentsOf: file(step)) {
                return try #require(try JSONSerialization.jsonObject(with: data) as? [String: String])
            }
            if FileManager.default.fileExists(atPath: file("kotlin-failed").path) { throw Failure.peerFailed(step) }
            try await Task.sleep(for: .milliseconds(200))
        }
        throw Failure.timedOut(step)
    }

    private func file(_ step: String) -> URL { directory.appendingPathComponent(step + ".json") }
}

extension [String: String] {
    /// `<role>Id` and `<role>Key` (base64url) as a roster member.
    func member(_ role: String) -> CommunityMember? {
        guard let id = self[role + "Id"].flatMap(AccountId.init(string:)),
              let key = self[role + "Key"].flatMap(Data.init(base64URLEncoded:)) else { return nil }
        return CommunityMember(accountId: id, identityKey: key)
    }
}
