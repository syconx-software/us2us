import SwiftUI

// 5.1 · whether the line is verified (proposed; the design draws only the
// header seal): a first-contact prompt at the top of the history while the
// safety number was never compared, and a banner above the composer while a
// changed safety number waits for the user. Both open 6.1.

extension ConversationViewModel {
    /// The first-contact prompt: a safety number exists but was never
    /// verified, and no change waits for a decision (the banner covers that).
    var showsVerifyPrompt: Bool { chat?.peer.offersVerification ?? false }

    /// The banner: the peer's safety number changed and sending is held
    /// until the user compares it (or goes on without verifying, in 6.1).
    var showsSafetyNumberChanged: Bool { chat?.peer.safetyNumberChanged ?? false }
}

/// 5.1 first row of the history in an unverified chat: the system pill's
/// look (`text·6%` r14, pad 6 12, a 14 pt glyph + `monoSystemPill`
/// `text·68%`, centred) — “Doğrulanmadı · QR ile doğrula”, tappable → 6.1.
/// It scrolls away with the history, so it is in view only while the chat
/// is new (or when the user reads back to its start), and it goes once the
/// chat is verified.
struct ConversationVerifyPrompt: View {
    let chatID: ChatID
    @Environment(AppModel.self) private var app

    var body: some View {
        Button {
            app.router.push(.safetyNumber(chatID))
        } label: {
            SystemPill(glyph: .qrCode, text: Text("verify.prompt", table: .conversation))
        }
        .buttonStyle(CardButtonStyle())
        .frame(maxWidth: .infinity)
        .accessibilityHint(Text("verify.prompt.hint", table: .conversation))
        .accessibilityIdentifier("conversation.verifyPrompt")
    }
}

/// 5.1 banner 6 pt above the composer while a changed safety number waits
/// for the user (sending is held): the urgent banner's look
/// (`FloatingBanner(.urgent)`: `surface·92%` + blur, inner ring `danger·45%`)
/// with `ph:warning-circle` 18 `danger`, the sentence in `bullet` `text` and
/// “Karşılaştır” in `rowTitle` `accent300`. The whole banner is the button
/// (→ 6.1). It stays until the number is verified or accepted, so every
/// message that arrives meanwhile is read under it.
struct SafetyNumberChangedBanner: View {
    let onCompare: () -> Void

    var body: some View {
        Button(action: onCompare) {
            FloatingBanner(.urgent) {
                HStack(alignment: .center, spacing: 10) {
                    PhosphorIcon(.warningCircle, size: 18)
                        .foregroundStyle(Color.u2.danger)
                    Text("safetyChanged.banner", table: .conversation)
                        .u2Font(.bullet)
                        .foregroundStyle(Color.u2.text)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("safetyChanged.compare", table: .conversation)
                        .u2Font(.rowTitle, matchLineBox: false)
                        .foregroundStyle(Color.u2.accent300)
                        .lineLimit(1)
                        .fixedSize()
                }
            }
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("conversation.safetyChanged")
    }
}

#Preview("5.1 Doğrulama") {
    VStack(spacing: 24) {
        ConversationVerifyPrompt(chatID: "deniz")
        SafetyNumberChangedBanner {}
            .padding(.horizontal, 12)
    }
    .padding(.vertical, 40)
    .frame(maxWidth: .infinity)
    .background(Color.u2.bg)
    .environment(AppModel.preview())
    .environment(\.locale, Locale(identifier: "tr"))
}
