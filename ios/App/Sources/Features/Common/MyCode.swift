import SwiftUI

/// “Kodumu göster” — the user's own contact code, everywhere it is offered:
/// 4.1 QR menu, 4.2 (pushed inside the sheet), 6.2 (on the camera surface)
/// and the 7.1 profile card. Proposed by the design as “the 6.1 layout
/// without the peer”: the own end of the line (avatar 48, palette D, the
/// name in `monoMeta` `text·68%`, gap 8) at 26 below the bar, and the 6.1
/// QR card 22 below it. Someone next to you scans it to add *and* verify you.
struct MyCodeView: View {
    let username: String
    let modules: QRModules?

    init(username: String, modules: QRModules?) {
        self.username = username
        self.modules = modules
    }

    /// For a `ContactCode` payload (`MessengerService.ownContactCode()`).
    init(username: String, payload: String?) {
        self.init(username: username, modules: payload.flatMap { QRModules.cached($0) })
    }

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 8) {
                Avatar(username: username, palette: .d, size: .verify)
                Text(verbatim: username)
                    .u2Font(.monoMeta)
                    .foregroundStyle(Color.u2.textSecondary)
                    .lineLimit(1)
            }
            .padding(.top, 26)
            .accessibilityElement(children: .combine)
            QRCodeCard(modules: modules, label: Text("a11y.myCode"))
                .padding(.top, 22)
        }
        .frame(maxWidth: .infinity)
    }
}

/// The own code in a full-height sheet (4.1 › QR › “Kodumu göster”, 7.1 ›
/// QR): sheet bar “Kodum” with “Kapat”, the code, and what it does.
/// The host loads the payload before presenting (`ownContactCode()` also
/// warms the QR cache), so the sheet rises complete.
struct MyCodeSheet: View {
    let username: String
    let payload: String?
    var closeTitle = Text("common.close")
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            SheetBar(title: Text("myCode.title"), cancelTitle: closeTitle, onCancel: onClose)
            MyCodeView(username: username, payload: payload)
            Text("myCode.caption")
                .u2Font(.callout)
                .foregroundStyle(Color.u2.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 290)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 24)
            InviteShareButton(username: username)
                .padding(.top, 8)
            Spacer(minLength: 0)
        }
        .accessibilityIdentifier("myCode.sheet")
    }
}

/// One presentation of `MyCodeSheet`: the payload travels with it, so the
/// sheet never shows a value captured before the code was loaded.
struct MyCodePresentation: Identifiable, Equatable {
    let id = UUID()
    let payload: String?
}

extension MessengerService {
    /// The own contact code with its QR already generated, so a sheet
    /// showing it never draws an empty card first.
    func preparedOwnContactCode() async -> String? {
        let payload = await ownContactCode()
        if let payload { _ = QRModules.cached(payload) }
        return payload
    }
}

#Preview("Kodum") {
    let model = AppModel.preview()
    return MyCodeSheet(username: "selin.n", payload: ContactCode.encode(username: "selin.n"), onClose: {})
        .environment(model)
        .background(Color.u2.surface)
}
