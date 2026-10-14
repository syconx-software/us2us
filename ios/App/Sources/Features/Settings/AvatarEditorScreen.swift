import SwiftUI

/// 7.1 › Profil görseli (proposed): the full-screen picker
/// (`AvatarPresetPicker`) in a fade cover over 7.1, and the framing screen
/// (`AvatarCropView`) that fades in over it — also full screen — for a
/// picked or taken photo. The system photo picker and the camera rise from
/// the top of this cover (`SystemPresenter`), never from 7.1 under it, and
/// the framing screen only once they have gone. The initial or a preset is
/// saved on tap; once a change is saved the page fades out by itself (after
/// the framing screen has), over a 7.1 that already shows the new picture.
struct AvatarEditorScreen: View {
    @Bindable var model: AvatarEditorModel
    let onClose: () -> Void

    @State private var closing = false
    @Environment(\.locale) private var locale

    var body: some View {
        AvatarPresetPicker(
            username: model.username, current: model.current,
            onChoose: { choice in Task { await model.choose(choice) } },
            onUploadPhoto: { Task { await model.pickPhoto() } },
            onTakePhoto: { Task { await model.takePhoto() } },
            onClose: onClose,
            footnote: model.notesSearch ? Text("avatar.search.note", table: .settings) : nil,
            alert: model.framing == nil ? model.toast : nil
        )
        .disabled(model.isBusy || closing)
        .u2Animation(U2Motion.state, value: model.toast == nil)
        .u2FadeCover(item: $model.framing, onEscape: model.cancelFraming) { source in
            AvatarCropView(model: model, source: source)
        }
        .onChange(of: model.savedChanges) { _, _ in closeAfterSaving() }
        .onChange(of: locale, initial: true) { _, locale in model.presentationLocale = locale }
        .onDisappear { model.toast = nil }
    }

    /// The moved mark (and the framing screen's fade) are seen first.
    private func closeAfterSaving() {
        guard !closing else { return }
        closing = true
        Task {
            try? await Task.sleep(for: .milliseconds(Int(U2Motion.Duration.state * 1_000) + 120))
            onClose()
        }
    }
}

/// One opening of “Profil görseli”: the cover's item, carrying that
/// opening's model. The model travels *in* the item on purpose: kept in
/// 7.1's `@State` and read only inside the cover's content closure (which
/// SwiftUI evaluates with the state as of 7.1's last body pass), the first
/// opening showed an empty sheet until something redrew 7.1 (1.0.7).
struct AvatarEditorPresentation: Identifiable, Equatable {
    let id = UUID()
    let model: AvatarEditorModel

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.id == rhs.id }
}
