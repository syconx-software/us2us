import SwiftUI

/// “Her şey silinsin mi?” — the second confirmation before erasing
/// everything (3.3 › “Her şeyi sil, baştan başla”, 7.1 › Hesap › “Her şeyi
/// sil”). One text, one danger button, “Vazgeç”.
///
/// * `.thisPhone` — 3.3: the code is forgotten, so the vault stays shut and
///   nothing can sign in to the relay. Only this phone is erased; the copy
///   says the account stays on the relay until it is removed after 180 days
///   without a connection (PROTOCOL §4.10).
/// * `.account` — 7.1: the account and username are deleted on the relay
///   first; the title and the button say so (“Hesabın silinsin mi?” ·
///   “Hesabı ve her şeyi sil”), and the button spins (“Hesap siliniyor…”)
///   until the relay answers.
struct EraseConfirmation: View {
    enum Scope: Sendable {
        case thisPhone
        case account
    }

    var scope: Scope = .thisPhone
    var isWorking = false
    var identifiers: (confirm: String, cancel: String)? = ("erase.confirm", "erase.cancel")
    let onConfirm: () -> Void
    let onCancel: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        DestructiveConfirmation(
            title: title,
            message: message,
            confirmTitle: confirmTitle,
            cancelTitle: Text("common.cancel"),
            isWorking: isWorking, identifiers: identifiers, onConfirm: onConfirm, onCancel: onCancel)
    }

    private var title: String {
        switch scope {
        case .thisPhone: L10n.string("erase.confirm.title", locale: locale)
        case .account: StringTable.settings.string("settings.erase.confirm.title", locale: locale)
        }
    }

    private var message: Text {
        switch scope {
        case .thisPhone: Text("erase.confirm.body")
        case .account: Text("settings.erase.confirm.body", table: .settings)
        }
    }

    private var confirmTitle: Text {
        switch scope {
        case .thisPhone: Text("erase.confirm.action")
        case .account:
            isWorking
                ? Text("settings.erase.confirm.working", table: .settings)
                : Text("settings.erase.confirm.action", table: .settings)
        }
    }
}
