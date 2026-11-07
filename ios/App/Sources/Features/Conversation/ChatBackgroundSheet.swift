import SwiftUI

/// “Sohbet arka planı” (proposed, in 5.5's language): this chat's own
/// background. A content-height sheet: the kicker, a preview with a bubble on
/// each side, the swatches (the app's own first), “Fotoğraf seç”, the note
/// that it stays on this phone, and “Kaydet”. A choice is only previewed until
/// “Kaydet”; closing the sheet leaves the chat as it was.
struct ChatBackgroundSheet: View {
    private enum Problem: Equatable {
        case unusablePhoto
        case notSaved
    }

    let chat: ChatID
    let messenger: any MessengerService
    /// The system photo picker; replaceable so tests run without it.
    var pickPhoto: @MainActor () async -> AvatarSourceImage? = { await AvatarSystemPickers.pickPhoto() }
    let onClose: () -> Void

    @Environment(\.locale) private var locale
    @State private var saved = ChatBackground.standard
    @State private var selection = ChatBackground.standard
    @State private var look = ChatBackgroundLook.standard
    @State private var isBusy = false
    @State private var problem: Problem?

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 5)

    var body: some View {
        VStack(spacing: 18) {
            MonoKicker(title: Text("background.kicker", table: .contact), glyph: .image)
            preview
            swatches
            Button {
                choosePhoto()
            } label: {
                U2ButtonLabel(Text("background.photo", table: .contact), glyph: .image)
            }
            .buttonStyle(.u2Secondary)
            .disabled(isBusy)
            .accessibilityIdentifier("background.photo")
            note
            Button {
                save()
            } label: {
                U2ButtonLabel(Text("common.save"), isWorking: isBusy)
            }
            .buttonStyle(.u2Primary)
            .disabled(isBusy)
            .accessibilityIdentifier("background.save")
        }
        .padding(.top, 20)
        .padding(.horizontal, 18)
        .u2Bottom(40)
        .task {
            saved = await messenger.chatBackground(of: chat)
            selection = saved
            look = await ChatBackgroundLook.make(saved)
        }
    }

    // MARK: Parts

    private var preview: some View {
        VStack(spacing: 6) {
            MessageBubble(direction: .incoming) {
                Text("background.sample.incoming", table: .contact)
                    .u2Font(.bubble)
                    .foregroundStyle(Color.u2.text)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            MessageBubble(direction: .outgoing) {
                Text("background.sample.outgoing", table: .contact)
                    .u2Font(.bubble)
                    .foregroundStyle(Color.u2.onBubbleOutgoing)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 148, alignment: .center)
        .background { ChatBackgroundLayer(look: look) }
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(Color.u2.divider, lineWidth: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityIdentifier("background.preview")
    }

    private var swatches: some View {
        LazyVGrid(columns: columns, spacing: 10) {
            swatch(.standard, look: .standard, name: Text("background.standard", table: .contact), id: "standard")
            ForEach(ChatBackgroundPreset.allCases) { preset in
                swatch(
                    .preset(preset), look: .preset(preset),
                    name: StringTable.contact.text(preset.nameKey, locale: locale), id: preset.id)
            }
        }
    }

    private func swatch(_ background: ChatBackground, look swatchLook: ChatBackgroundLook, name: Text, id: String)
        -> some View
    {
        let isSelected = selection == background
        return Button {
            selection = background
            look = swatchLook
            problem = nil
        } label: {
            ChatBackgroundLayer(look: swatchLook)
                .frame(height: 52)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(isSelected ? Color.u2.accent : Color.u2.divider, lineWidth: isSelected ? 2 : 1)
                }
        }
        .buttonStyle(.plain)
        .disabled(isBusy)
        .accessibilityLabel(name)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("background.swatch.\(id)")
    }

    @ViewBuilder private var note: some View {
        switch problem {
        case .unusablePhoto:
            noteText(Text("background.problem.photo", table: .contact), color: Color.u2.danger)
        case .notSaved:
            noteText(Text("background.problem.save", table: .contact), color: Color.u2.danger)
        case nil:
            noteText(Text("background.note", table: .contact), color: Color.u2.textSecondary)
        }
    }

    private func noteText(_ text: Text, color: Color) -> some View {
        text
            .u2Font(.captionCompact)
            .foregroundStyle(color)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("background.note")
    }

    // MARK: Actions

    private func choosePhoto() {
        Task {
            problem = nil
            isBusy = true
            defer { isBusy = false }
            guard let picked = await pickPhoto() else { return }
            let source = picked.jpeg
            let prepared = await Task.detached(priority: .userInitiated) { ChatBackgroundPhoto.prepare(source) }.value
            guard let prepared else {
                problem = .unusablePhoto
                return
            }
            let background = ChatBackground.photo(prepared)
            let shown = await ChatBackgroundLook.make(background)
            guard shown != .standard else {
                problem = .unusablePhoto
                return
            }
            selection = background
            look = shown
        }
    }

    private func save() {
        guard selection != saved else {
            onClose()
            return
        }
        Task {
            problem = nil
            isBusy = true
            defer { isBusy = false }
            if await messenger.setChatBackground(selection, for: chat) {
                onClose()
            } else {
                problem = .notSaved
            }
        }
    }
}
