import Foundation
import Us2usKit

/// User-facing strings the runtime produces itself (the Face ID prompt,
/// local system lines, the in-app banner). Each key has an English and a
/// Turkish text in one place, so neither can be missing; the app's own
/// String Catalog covers its UI. Kit strings come from Us2usKit's `Localizer`.
public enum RuntimeString: String, CaseIterable, Sendable {
    /// Face ID / Touch ID prompt reason (`LAContext.localizedReason`).
    case biometricReason
    /// Cancel button of the biometric prompt: falls back to the code keypad.
    case biometricUseCode
    /// Local system line after the user took a screenshot (the contact was told).
    case screenshotTakenByMe
    /// Local system line when screen recording started in a conversation.
    case screenRecordingByMe
    /// System line when the contact's safety number changed (sending is blocked).
    case safetyNumberChangedLine
    /// System line when the user accepted a changed safety number (without verifying it).
    case safetyNumberAcceptedLine
    /// System line when the user verified the safety number.
    case safetyNumberVerifiedLine
    /// System line when a message request was accepted.
    case requestAcceptedLine
    /// Tombstone of a message deleted for everyone.
    case deletedForEveryone
    /// Connection "hat" line on the chat list.
    case lineOpen
    case lineConnecting
    case lineSyncing
    case lineOffline
    case lineWaitingForNetwork
    case lineRevoked
    case lineLocked

    var texts: (en: String, tr: String) {
        switch self {
        case .biometricReason: return ("Unlock us2us", "us2us'un kilidini aç")
        case .biometricUseCode: return ("Use code", "Kodla aç")
        case .screenshotTakenByMe:
            return ("You took a screenshot. They were notified.", "Ekran görüntüsü aldın. Karşı taraf bilgilendirildi.")
        case .screenRecordingByMe:
            return ("Screen recording detected. They were notified.", "Ekran kaydı algılandı. Karşı taraf bilgilendirildi.")
        case .safetyNumberChangedLine:
            return ("The safety number changed. Verify it before sending.", "Güvenlik numarası değişti. Göndermeden önce doğrula.")
        case .safetyNumberAcceptedLine:
            return (
                "You accepted the new safety number without verifying it.",
                "Yeni güvenlik numarasını doğrulamadan kabul ettin."
            )
        case .safetyNumberVerifiedLine: return ("You verified the safety number.", "Güvenlik numarasını doğruladın.")
        case .requestAcceptedLine: return ("Message request accepted.", "Mesaj isteği kabul edildi.")
        case .deletedForEveryone: return ("This message was deleted for everyone", "Bu mesaj herkesten silindi")
        case .lineOpen: return ("Line open · messages arriving", "Hat açık · mesajlar geliyor")
        case .lineConnecting: return ("Opening the line…", "Hat kuruluyor…")
        case .lineSyncing: return ("Receiving waiting messages…", "Bekleyen mesajlar alınıyor…")
        case .lineOffline: return ("Line closed", "Hat kapalı")
        case .lineWaitingForNetwork: return ("No internet · waiting", "İnternet yok · bekleniyor")
        case .lineRevoked:
            // A revoked token: the account was deleted, or restored on another phone.
            return (
                "This phone's line was closed: the account was deleted or is used on another phone",
                "Bu telefonun hattı kapandı: hesap silindi ya da başka bir telefonda kullanılıyor"
            )
        case .lineLocked: return ("Locked", "Kilitli")
        }
    }
}

/// Resolves ``RuntimeString``s in the device language (Turkish or English;
/// anything else falls back to English), like Us2usKit's `Localizer`.
public struct RuntimeStrings: Sendable {
    public let languageCode: String

    public init(languageCode: String) {
        self.languageCode = languageCode == "tr" ? "tr" : "en"
    }

    public static var current: RuntimeStrings {
        RuntimeStrings(languageCode: Localizer.current.languageCode)
    }

    public func string(_ key: RuntimeString) -> String {
        languageCode == "tr" ? key.texts.tr : key.texts.en
    }
}

extension String {
    /// The longest prefix whose UTF-8 encoding fits in `maxBytes`, cut at a character boundary.
    func utf8Prefix(_ maxBytes: Int) -> Substring {
        var bytes = 0
        var end = startIndex
        for index in indices {
            let size = self[index].utf8.count
            guard bytes + size <= maxBytes else { break }
            bytes += size
            end = self.index(after: index)
        }
        return self[startIndex..<end]
    }
}
