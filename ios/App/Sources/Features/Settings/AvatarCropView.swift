import SwiftUI

/// “Fotoğrafı yerleştir” (proposed — the design has no avatar editor): a
/// picked or taken photo framed for the profile picture, in a full-screen
/// fade cover over the full-screen picker. The sheet bar (“Vazgeç”, title) on
/// top; a square viewport (screen gutter 20, r20) where the photo fills the
/// square and moves with one finger and zooms with two (or a double tap),
/// the circle that will be shown lit and the corners veiled; a hint, and —
/// while “Aramada göster” is on — who will be able to see it; “Kullan” (C1)
/// at the bottom. VoiceOver: the viewport is adjustable (zoom in and out)
/// and has actions that move the frame left, right, up and down.
///
/// Nothing leaves the phone from here: “Kullan” hands the framed square to
/// the messenger, which scales it to ≤ 256 px without metadata.
struct AvatarCropView: View {
    @Bindable var model: AvatarEditorModel
    let source: AvatarSourceImage

    @State private var zoom: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var side: CGFloat = 0
    @GestureState private var pinch: CGFloat = 1
    @GestureState private var drag: CGSize = .zero

    private var geometry: AvatarCropGeometry {
        AvatarCropGeometry(imageSize: source.pixelSize, side: max(side, 1))
    }

    private var isSaving: Bool { model.phase == .saving }

    var body: some View {
        U2Screen {
            VStack(spacing: 0) {
                SheetBar(
                    title: Text("avatar.crop.title", table: .settings), cancelTitle: Text("common.cancel"),
                    onCancel: model.cancelFraming
                )
                .disabled(isSaving)
                .u2Top(62)
                .accessibilityIdentifier("avatarCrop.bar")
                Spacer(minLength: 16)
                viewport
                    .padding(.horizontal, U2Spacing.screenGutter)
                VStack(spacing: 10) {
                    Text("avatar.crop.hint", table: .settings)
                        .u2Font(.monoMeta)
                        .foregroundStyle(Color.u2.textSecondary)
                        .multilineTextAlignment(.center)
                    if model.notesSearch {
                        HStack(alignment: .top, spacing: 8) {
                            PhosphorIcon(.eye, size: 16)
                                .foregroundStyle(Color.u2.accent300)
                                .padding(.top, 1)
                            Text("avatar.search.note", table: .settings)
                                .u2Font(.footnote)
                                .foregroundStyle(Color.u2.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                .padding(.horizontal, 28)
                .padding(.top, 16)
                Spacer(minLength: 16)
                Button(action: use) {
                    U2ButtonLabel(Text("avatar.crop.use", table: .settings), glyph: .check, isWorking: isSaving)
                }
                .buttonStyle(.u2Primary)
                .disabled(isSaving || side <= 0)
                .padding(.horizontal, U2Spacing.screenGutter)
                .u2Bottom(U2Spacing.ctaBottom)
                .accessibilityIdentifier("avatarCrop.use")
            }
        }
        .u2Toast($model.toast)
    }

    // MARK: Viewport

    private var viewport: some View {
        let geometry = self.geometry
        let liveZoom = geometry.clampedZoom(zoom * pinch)
        let liveOffset = geometry.clampedOffset(
            CGSize(width: offset.width + drag.width, height: offset.height + drag.height), zoom: liveZoom)
        let displayed = geometry.displayedSize(zoom: liveZoom)
        let shape = RoundedRectangle(cornerRadius: U2Radius.cardL, style: .continuous)
        return Color.u2.surface
            .aspectRatio(1, contentMode: .fit)
            .frame(maxWidth: 420)
            .overlay {
                if side > 0 {
                    Image(decorative: source.image, scale: 1)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: displayed.width, height: displayed.height)
                        .offset(liveOffset)
                }
            }
            .overlay { AvatarCropVeil() }
            .clipShape(shape)
            .contentShape(shape)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { side = $0 }
            .gesture(SimultaneousGesture(magnify, move))
            .onTapGesture(count: 2, perform: toggleZoom)
            .accessibilityElement()
            .accessibilityLabel(Text("avatar.crop.area", table: .settings))
            .accessibilityValue(Text("avatar.crop.zoom \(Int((zoom * 100).rounded()))", table: .settings))
            .accessibilityHint(Text("avatar.crop.area.hint", table: .settings))
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: setZoom(geometry.steppedZoom(zoom, in: 1))
                case .decrement: setZoom(geometry.steppedZoom(zoom, in: -1))
                @unknown default: break
                }
            }
            // Moving the frame: a quarter of the free travel a step.
            .accessibilityAction(named: Text("avatar.crop.move.left", table: .settings)) { moveFrame(x: -1, y: 0) }
            .accessibilityAction(named: Text("avatar.crop.move.right", table: .settings)) { moveFrame(x: 1, y: 0) }
            .accessibilityAction(named: Text("avatar.crop.move.up", table: .settings)) { moveFrame(x: 0, y: -1) }
            .accessibilityAction(named: Text("avatar.crop.move.down", table: .settings)) { moveFrame(x: 0, y: 1) }
            .accessibilityIdentifier("avatarCrop.viewport")
    }

    private var magnify: some Gesture {
        MagnifyGesture()
            .updating($pinch) { value, state, _ in state = value.magnification }
            .onEnded { value in setZoom(zoom * value.magnification) }
    }

    private var move: some Gesture {
        DragGesture(minimumDistance: 2)
            .updating($drag) { value, state, _ in state = value.translation }
            .onEnded { value in
                let moved = CGSize(
                    width: offset.width + value.translation.width, height: offset.height + value.translation.height)
                withU2Animation(U2Motion.state) { offset = geometry.clampedOffset(moved, zoom: zoom) }
            }
    }

    private func setZoom(_ new: CGFloat) {
        let geometry = self.geometry
        let zoom = geometry.clampedZoom(new)
        withU2Animation(U2Motion.state) {
            self.zoom = zoom
            offset = geometry.clampedOffset(offset, zoom: zoom)
        }
    }

    /// VoiceOver's “move the frame” actions: the frame goes `x`, `y` steps
    /// over the photo (the photo the other way), a quarter of the free
    /// travel each, never past the photo's edge.
    private func moveFrame(x: CGFloat, y: CGFloat) {
        let geometry = self.geometry
        let size = geometry.displayedSize(zoom: zoom)
        let stepX = max(0, size.width - geometry.side) / 4
        let stepY = max(0, size.height - geometry.side) / 4
        let moved = CGSize(width: offset.width - x * stepX, height: offset.height - y * stepY)
        withU2Animation(U2Motion.state) { offset = geometry.clampedOffset(moved, zoom: zoom) }
    }

    /// Double tap: in to 2× (or as far as it goes), and back out.
    private func toggleZoom() {
        setZoom(zoom > 1.01 ? 1 : min(2, geometry.maxZoom))
    }

    private func use() {
        let crop = geometry.cropRect(zoom: zoom, offset: offset)
        Task { await model.useFramedPhoto(crop: crop) }
    }
}

/// The corners outside the circle under a `bg` veil, and the circle's edge.
private struct AvatarCropVeil: View {
    var body: some View {
        GeometryReader { proxy in
            let rect = CGRect(origin: .zero, size: proxy.size)
            let circle = rect.insetBy(dx: 1, dy: 1)
            ZStack {
                Path { path in
                    path.addRect(rect)
                    path.addEllipse(in: circle)
                }
                .fill(Color.u2.bg.opacity(0.62), style: FillStyle(eoFill: true))
                Circle()
                    .strokeBorder(Color.u2.accent300.opacity(0.9), lineWidth: 1.5)
                    .frame(width: circle.width, height: circle.height)
                    .position(x: rect.midX, y: rect.midY)
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
