import CoreGraphics
import SwiftUI

/// 7.1 › Profil görseli (proposed): the state of the full-screen picker and
/// of the framing screen over it. Choosing the initial or a preset saves at once;
/// a photo is picked (or taken), framed on `AvatarCropView`, then handed to
/// the messenger with the square the user framed. Nothing here keeps the
/// photo after it was saved or given up.
@Observable
@MainActor
final class AvatarEditorModel {
    /// Where a save stands (the picker's mark, the framing screen's button).
    enum Phase: Equatable {
        case idle
        /// A picked photo is being read (before the framing screen).
        case loadingPhoto
        case saving
    }

    let messenger: any MessengerService
    private let sources: AvatarPhotoSourcing

    /// The photo on the framing screen; `nil` closes it.
    var framing: AvatarSourceImage?
    private(set) var phase: Phase = .idle
    /// A short note on the picker or the framing screen (“Fotoğraf
    /// kullanılamadı.”).
    var toast: Text?
    /// Set when a change was saved: the picker closes.
    private(set) var savedChanges = 0
    /// The UI language for what opens outside SwiftUI (the camera-access
    /// sheet); the picker keeps it current.
    @ObservationIgnored var presentationLocale: Locale = .current

    init(messenger: any MessengerService, sources: AvatarPhotoSourcing) {
        self.messenger = messenger
        self.sources = sources
    }

    /// The picker's mark: the current choice.
    var current: AvatarChoice { messenger.ownAvatar.picture.choice }

    /// The own username (the initial's letter).
    var username: String { messenger.ownUsername }

    /// “Aramada göster” is on: the picker and the framing screen say who can
    /// see the picture (PROTOCOL §8.10).
    var notesSearch: Bool { messenger.ownAvatar.showInSearch }

    var isBusy: Bool { phase != .idle }

    // MARK: Initial and presets

    /// A tap on the initial or a preset: saved at once (the picker has
    /// moved its mark already). `false` when it could not be saved.
    @discardableResult
    func choose(_ choice: AvatarChoice) async -> Bool {
        toast = nil
        let edit: AvatarEdit
        switch choice {
        case .initials: edit = .initials
        case .preset(let id): edit = .preset(id)
        case .photo: return false
        }
        guard choice != current else {
            savedChanges += 1
            return true
        }
        return await save(edit)
    }

    // MARK: Photo

    /// “Fotoğraf yükle”: the system picker, then the framing screen.
    func pickPhoto() async {
        guard !isBusy else { return }
        toast = nil
        phase = .loadingPhoto
        let image = await sources.pick()
        phase = .idle
        if let image { framing = image }
    }

    /// “Kamera”: the camera (access asked the first time), then framing.
    func takePhoto() async {
        guard !isBusy else { return }
        toast = nil
        switch await sources.cameraAccess() {
        case .available:
            phase = .loadingPhoto
            let image = await sources.take()
            phase = .idle
            if let image { framing = image }
        case .denied:
            toast = Text("avatar.camera.denied", table: .settings)
            sources.explainCameraDenied(presentationLocale)
        case .restricted:
            toast = Text("avatar.camera.restricted", table: .settings)
        case .unavailable:
            toast = Text("avatar.camera.unavailable", table: .settings)
        }
    }

    /// “Kullan” on the framing screen: the framed square of the photo in
    /// view, in its pixels. The screen closes once it is saved; it stays
    /// (with a note) when the photo cannot be used.
    @discardableResult
    func useFramedPhoto(crop: CGRect) async -> Bool {
        guard let framing else { return false }
        let saved = await save(.photo(framing.jpeg, crop: crop))
        if saved { self.framing = nil }
        return saved
    }

    /// “Vazgeç” on the framing screen: the photo is dropped.
    func cancelFraming() {
        guard phase != .saving else { return }
        framing = nil
    }

    private func save(_ edit: AvatarEdit) async -> Bool {
        guard phase != .saving else { return false }
        phase = .saving
        defer { phase = .idle }
        do {
            try await messenger.setAvatar(edit)
            savedChanges += 1
            return true
        } catch AvatarEditError.unusablePhoto {
            toast = Text("avatar.error.photo", table: .settings)
        } catch {
            toast = Text("avatar.error.save", table: .settings)
        }
        return false
    }
}

// MARK: - Framing geometry

/// The framing screen's arithmetic (pure): the photo fills a square viewport
/// at zoom 1 (its shorter side edge to edge) and zooms up to `maxZoom`; it
/// can be moved only as far as it still covers the square. `cropRect` is the
/// square of the photo's pixels the viewport shows — what is sent.
struct AvatarCropGeometry: Equatable {
    /// The photo's size in pixels (orientation applied).
    let imageSize: CGSize
    /// The viewport's side in points.
    let side: CGFloat

    /// The framed square never gets smaller than this many pixels (the
    /// picture is 256 at most; a smaller square only blurs).
    static let minimumCropPixels: CGFloat = 96
    static let zoomCap: CGFloat = 6

    /// Points per pixel at zoom 1.
    var baseScale: CGFloat {
        let shorter = min(imageSize.width, imageSize.height)
        return shorter > 0 ? side / shorter : 1
    }

    var maxZoom: CGFloat {
        let shorter = min(imageSize.width, imageSize.height)
        return max(1, min(Self.zoomCap, shorter / Self.minimumCropPixels))
    }

    func clampedZoom(_ zoom: CGFloat) -> CGFloat {
        guard zoom.isFinite else { return 1 }
        return min(max(zoom, 1), maxZoom)
    }

    /// The photo's size on screen at `zoom`.
    func displayedSize(zoom: CGFloat) -> CGSize {
        let scale = baseScale * zoom
        return CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
    }

    /// How far the photo's centre may sit from the viewport's centre.
    func clampedOffset(_ offset: CGSize, zoom: CGFloat) -> CGSize {
        let size = displayedSize(zoom: zoom)
        let maxX = max(0, (size.width - side) / 2)
        let maxY = max(0, (size.height - side) / 2)
        let x = offset.width.isFinite ? offset.width : 0
        let y = offset.height.isFinite ? offset.height : 0
        return CGSize(width: min(max(x, -maxX), maxX), height: min(max(y, -maxY), maxY))
    }

    /// The framed square in the photo's pixels (origin top left), in whole
    /// pixels and inside the photo.
    func cropRect(zoom: CGFloat, offset: CGSize) -> CGRect {
        let zoom = clampedZoom(zoom)
        let offset = clampedOffset(offset, zoom: zoom)
        let scale = baseScale * zoom
        let shorter = min(imageSize.width, imageSize.height)
        // A hair over the quotient, so floating-point noise never costs a pixel.
        let cropSide = min((side / scale + 1e-6).rounded(.down), shorter.rounded(.down))
        guard cropSide >= 1 else { return CGRect(origin: .zero, size: imageSize) }
        // The viewport's centre, in the photo's pixels.
        let centreX = imageSize.width / 2 - offset.width / scale
        let centreY = imageSize.height / 2 - offset.height / scale
        let x = min(max((centreX - cropSide / 2).rounded(), 0), (imageSize.width - cropSide).rounded(.down))
        let y = min(max((centreY - cropSide / 2).rounded(), 0), (imageSize.height - cropSide).rounded(.down))
        return CGRect(x: x, y: y, width: cropSide, height: cropSide)
    }

    /// VoiceOver's adjustable action: one step in or out.
    func steppedZoom(_ zoom: CGFloat, in direction: Int) -> CGFloat {
        let step = max(0.25, (maxZoom - 1) / 6)
        return clampedZoom(zoom + CGFloat(direction) * step)
    }
}

#if DEBUG
    extension AvatarEditorModel {
        /// The framing screen's photo in the screen harness
        /// (`-U2Route settings:avatar-crop`): the 5.1 night-pier painting.
        static func harnessSource() -> AvatarSourceImage? {
            DesignArtworkView.renderedImage(.nightPier, pixelWidth: 1_536).flatMap { AvatarSourceImage.make(from: $0) }
        }
    }
#endif
