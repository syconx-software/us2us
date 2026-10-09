import SwiftUI

/// A destructive confirmation inside a floating sheet — the inventory's
/// proposal for 3.3's second confirmation (“the same floating sheet
/// re-rendered with the danger button as C1 in `danger`”), used for every
/// irreversible step: 3.3 / 7.1 “Her şey silinsin mi?”, 7.3 cancel.
///
/// Text block (pad 6 4 0 4, gap 10): the Doto sheet title (balanced: a
/// heading that wraps never leaves one word alone) and the `sheetBody`
/// paragraph in `text·68%`; 18 below it the danger C1 and the C3 “Vazgeç”
/// (`accent300`, gap 8). The host sheet supplies the padding (18 18 28 18).
struct DestructiveConfirmation: View {
    let title: String
    let message: Text
    let confirmTitle: Text
    var confirmGlyph: PhosphorGlyph? = .trash
    var cancelTitle = Text("common.cancel")
    /// The confirm button shows a spinner and both buttons ignore taps.
    var isWorking = false
    /// Accessibility identifiers of the two buttons (`nil`: none — e.g. a
    /// measuring copy that UI tests must never find).
    var identifiers: (confirm: String, cancel: String)? = ("confirm.destructive", "confirm.cancel")
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 10) {
                U2DisplayText(verbatim: title, style: .displaySheetTitle.balanced, isHeader: true)
                message
                    .u2Font(.sheetBody)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 6, leading: 4, bottom: 0, trailing: 4))
            VStack(spacing: 8) {
                Button(action: onConfirm) {
                    U2ButtonLabel(confirmTitle, glyph: confirmGlyph, isWorking: isWorking)
                }
                .buttonStyle(.u2Danger)
                .disabled(isWorking)
                .accessibilityIdentifier(identifiers?.confirm ?? "")
                Button(action: onCancel) { U2ButtonLabel(cancelTitle) }
                    .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.textButtonS))
                    .disabled(isWorking)
                    .accessibilityIdentifier(identifiers?.cancel ?? "")
            }
        }
    }
}
