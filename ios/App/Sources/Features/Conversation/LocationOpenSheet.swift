import SwiftUI

/// 5.2 › “Haritada aç” (proposed: the design notes “location → map sheet”
/// without drawing it): a tap on a place bubble — or “Haritada aç” on the
/// live-location map — opens this floating sheet in 3.3's language.
///
/// Text block (pad 6 4 0 4, gap 10): the Doto sheet title, the privacy
/// hint (`sheetBody`, `text·68%`: the location is handed to the chosen app;
/// for a disappearing message also that the app may keep it in its own
/// history) and, in `monoFileMeta`, exactly what would be handed over. Two option
/// cards (gap 10): Apple Maps (`ph:map-pin` fill) and Google Maps
/// (`ph:navigation-arrow`; the app when it is installed, otherwise
/// google.com/maps in the browser). C3 “Vazgeç”. The host sheet floats;
/// the padding is the sheet's (18 18 28 18).
struct LocationOpenSheet: View {
    let choice: MapChoice
    let onChoose: (MapApp) -> Void
    let onCancel: () -> Void

    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 10) {
                U2DisplayText(
                    verbatim: StringTable.conversation.string("location.open.title", locale: locale),
                    style: .displaySheetTitle.balanced, isHeader: true)
                Text("location.open.body", table: .conversation)
                    .u2Font(.sheetBody)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if choice.isDisappearing {
                    Text("location.open.disappearing", table: .conversation)
                        .u2Font(.sheetBody)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Text(verbatim: handedOver)
                    .u2Font(.monoFileMeta)
                    .foregroundStyle(Color.u2.textSecondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 6, leading: 4, bottom: 0, trailing: 4))
            VStack(spacing: 10) {
                OptionCard(
                    glyph: .mapPin, glyphWeight: .fill, title: Text("location.open.apple", table: .conversation),
                    description: Text("location.open.apple.detail", table: .conversation)
                ) {
                    onChoose(.apple)
                }
                .accessibilityIdentifier("location.open.apple")
                OptionCard(
                    glyph: .navigationArrow, title: Text("location.open.google", table: .conversation),
                    description: choice.hasGoogleMapsApp
                        ? Text("location.open.google.detail.app", table: .conversation)
                        : Text("location.open.google.detail.web", table: .conversation)
                ) {
                    onChoose(.google)
                }
                .accessibilityIdentifier("location.open.google")
            }
            Button(action: onCancel) { U2ButtonLabel(Text("common.cancel")) }
                .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.textButtonS))
                .accessibilityIdentifier("location.open.cancel")
        }
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
    }

    /// “Moda İskelesi · 40.98460, 29.02490” (“Konum · …” without a name).
    private var handedOver: String {
        let name = choice.place.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let title = name.isEmpty ? L10n.string("media.location", locale: locale) : name
        return title + " · " + MapLinks.displayCoordinates(choice.place)
    }
}

#Preview("5.2 › Haritada aç") {
    LocationOpenSheet(
        choice: MapChoice(
            place: MapPlace(name: "Moda İskelesi", latitude: 40.9846, longitude: 29.0249), hasGoogleMapsApp: false),
        onChoose: { _ in }, onCancel: {}
    )
    .background(Color.u2.surface)
    .environment(\.locale, Locale(identifier: "tr"))
}

#Preview("5.2 › Haritada aç · süreli mesaj") {
    LocationOpenSheet(
        choice: MapChoice(
            place: MapPlace(name: "Moda İskelesi", latitude: 40.9846, longitude: 29.0249), hasGoogleMapsApp: true,
            isDisappearing: true),
        onChoose: { _ in }, onCancel: {}
    )
    .background(Color.u2.surface)
    .environment(\.locale, Locale(identifier: "tr"))
}
