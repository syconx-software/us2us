import SwiftUI

/// “Her şey silinsin mi?” — the second confirmation before erasing
/// everything on this device (3.3 › “Her şeyi sil, baştan başla”, 7.1 ›
/// Hesap › “Her şeyi sil”). One text, one danger button, “Vazgeç”.
struct EraseConfirmation: View {
    var isWorking = false
    var identifiers: (confirm: String, cancel: String)? = ("erase.confirm", "erase.cancel")
    let onConfirm: () -> Void
    let onCancel: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        DestructiveConfirmation(
            title: L10n.string("erase.confirm.title", locale: locale),
            message: Text("erase.confirm.body"),
            confirmTitle: Text("erase.confirm.action"),
            cancelTitle: Text("common.cancel"),
            isWorking: isWorking, identifiers: identifiers, onConfirm: onConfirm, onCancel: onCancel)
    }
}
