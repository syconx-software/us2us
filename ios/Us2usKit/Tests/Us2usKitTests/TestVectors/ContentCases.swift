import Foundation
import SwiftProtobuf

@testable import Us2usKit

/// The named messages of spec/test-vectors/README.md, "Content cases".
enum ContentCases {
    struct Case {
        let name: String
        let content: BRPContent
    }

    static func data(_ id: UInt8, sentAt: UInt64, _ build: (inout BRPDataMessage) -> Void) -> BRPContent {
        var message = BRPDataMessage()
        message.messageID = .id(id)
        message.sentAt = sentAt
        build(&message)
        return BRPContent(data: message)
    }

    static var all: [Case] {
        [
            Case(
                name: "text",
                content: data(1, sentAt: 1_760_000_000_000) {
                    $0.text = BRPText.with { $0.body = "Merhaba 👋 dünya" }
                }),
            Case(
                name: "text_expiring_quote",
                content: data(2, sentAt: 1_760_000_000_001) {
                    $0.expireTimerSeconds = 3600
                    $0.quote = BRPQuote.with {
                        $0.targetMessageID = .id(1)
                        $0.previewText = "Merhaba"
                    }
                    $0.text = BRPText.with { $0.body = "Hi" }
                }),
            Case(
                name: "attachment",
                content: data(3, sentAt: 1_760_000_000_002) {
                    $0.viewOnce = true
                    $0.attachment = BRPAttachment.with {
                        $0.kind = .image
                        $0.mediaID = .id(4)
                        $0.deleteToken = Data(repeating: 0x05, count: 32)
                        $0.key = Data(repeating: 0x06, count: 64)
                        $0.digest = Data(repeating: 0x07, count: 32)
                        $0.size = 123_456
                        $0.contentType = "image/jpeg"
                        $0.width = 1920
                        $0.height = 1080
                        $0.caption = "Foto"
                    }
                }),
            Case(
                name: "location",
                content: data(8, sentAt: 1_760_000_000_003) {
                    $0.location = BRPLocation.with {
                        $0.latitude = 41.0082
                        $0.longitude = 28.9784
                        $0.accuracyMeters = 12.5
                        $0.name = "İstanbul"
                    }
                }),
            Case(
                name: "live_location",
                content: data(9, sentAt: 1_760_000_000_004) {
                    $0.liveLocation = BRPLiveLocation.with {
                        $0.sessionID = .id(10)
                        $0.latitude = 39.9334
                        $0.longitude = 32.8597
                        $0.accuracyMeters = 5
                        $0.headingDegrees = -1
                        $0.speedMps = -1
                        $0.expiresAt = 1_760_003_600_000
                        $0.sequence = 7
                    }
                }),
            Case(
                name: "sticker",
                content: data(11, sentAt: 1_760_000_000_005) {
                    $0.sticker = BRPSticker.with {
                        $0.packID = "cb.basic"
                        $0.stickerID = 3
                        $0.emoji = "😂"
                    }
                }),
            Case(
                name: "sticker_lang",
                content: data(13, sentAt: 1_760_000_000_008) {
                    $0.sticker = BRPSticker.with {
                        $0.packID = "us2us.words"
                        $0.stickerID = 101
                        $0.emoji = "👋"
                        $0.lang = "tr"
                    }
                }),
            Case(
                name: "reaction",
                content: data(12, sentAt: 1_760_000_000_006) {
                    $0.reaction = BRPReaction.with {
                        $0.targetMessageID = .id(1)
                        $0.emoji = "\u{2764}\u{FE0F}"
                    }
                }),
            Case(name: "receipt", content: BRPContent(receipt: .read, messageIds: [.id(1), .id(2)])),
            Case(name: "receipt_saved", content: BRPContent(receipt: .saved, messageIds: [.id(3)])),
            Case(name: "typing", content: BRPContent(typingStarted: true)),
            Case(
                name: "presence",
                content: BRPContent(
                    presence: BRPPresenceMessage.with {
                        $0.state = .online
                        $0.validForSeconds = 300
                        $0.reply = true
                    })),
            Case(
                name: "ghost_settings",
                content: BRPContent(
                    control: .settings(
                        BRPConversationSettings.with {
                            $0.updatedAt = 1_760_000_000_007
                            $0.defaultExpireTimerSeconds = 0
                            $0.ghost = BRPGhostMode.with {
                                $0.enabled = true
                                $0.timeoutSeconds = 3600
                                $0.wipeContact = false
                            }
                        }))),
            Case(
                name: "wipe",
                content: BRPContent(
                    control: .wipe(
                        BRPConversationWipe.with {
                            $0.requestedAt = 1_760_000_000_008
                            $0.reason = .ghostTimeout
                        }))),
            Case(name: "access_key", content: .accessKeyUpdate(.id(13))),
            Case(
                name: "profile",
                content: BRPContent(
                    control: .profile(
                        BRPProfileUpdate.with {
                            $0.displayName = "Kağan"
                            $0.updatedAt = 1_760_000_000_009
                            $0.username = "kagan_y"
                            $0.presenceSharing = true
                        }))),
            Case(
                name: "profile_avatar_preset",
                content: BRPContent(
                    control: .profile(
                        BRPProfileUpdate.with {
                            $0.displayName = "Kağan"
                            $0.updatedAt = 1_760_000_000_010
                            $0.username = "kagan_y"
                            $0.presenceSharing = true
                            $0.profileAvatar = BRPProfileAvatar.with { $0.presetID = 1 }
                        }))),
            Case(
                name: "profile_avatar_photo",
                content: BRPContent(
                    control: .profile(
                        BRPProfileUpdate.with {
                            $0.updatedAt = 1_760_000_000_011
                            $0.username = "kagan_y"
                            $0.profileAvatar = BRPProfileAvatar.with {
                                $0.photo = BRPAvatarPhoto.with {
                                    $0.contentType = "image/jpeg"
                                    $0.data = Data([0xFF, 0xD8, 0xFF, 0xD9])
                                }
                            }
                        }))),
        ]
    }

    /// Cases tolerated as missing in the other implementation's file until it
    /// writes them. Empty: both implementations write every case
    /// (`profile_avatar_*` since profile pictures, `receipt_saved` since SAVED).
    static let newestCases: Set<String> = []

    /// The `sealed_content` case, which is a `SealedContent`, not a `Content`.
    static var sealedContent: BRPSealedContent {
        BRPSealedContent.with {
            $0.senderAccountID = .id(14)
            $0.senderDeviceID = 1
            $0.type = 3
            $0.ciphertext = Data([1, 2, 3, 4, 5])
            $0.senderUsername = "alice_01"
        }
    }

    static let sealedContentName = "sealed_content"
}
