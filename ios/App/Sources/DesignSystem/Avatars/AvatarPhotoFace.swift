import ImageIO
import SwiftUI
import UIKit
import Us2usKit
import Us2usRuntime

/// A profile photo in C12's circle: decoded off the main thread, in memory,
/// once per app session (`AvatarImageCache`); `placeholder` (the initial)
/// shows until it is ready and stays when it never decodes. The photo
/// fades in over it (0.24 s; Reduce Motion: at once).
struct AvatarPhotoFace<Placeholder: View>: View {
    let photo: AvatarPhotoData
    let diameter: CGFloat
    @ViewBuilder let placeholder: Placeholder

    @State private var decoded: (key: String, image: UIImage)? = nil

    var body: some View {
        let image = AvatarImageCache.shared.cached(photo) ?? (decoded?.key == photo.cacheKey ? decoded?.image : nil)
        ZStack {
            placeholder
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFill()
                    .frame(width: diameter, height: diameter)
                    .clipShape(Circle())
                    // A hairline keeps light and dark photos from melting into `bg`.
                    .overlay(Circle().strokeBorder(Color.u2.text.opacity(0.08), lineWidth: 0.5))
                    .transition(.u2Fade)
            }
        }
        .frame(width: diameter, height: diameter)
        .u2Animation(U2Motion.micro, value: image == nil)
        .task(id: photo.cacheKey) {
            guard AvatarImageCache.shared.cached(photo) == nil else { return }
            if let image = await AvatarImageCache.shared.image(for: photo) {
                decoded = (photo.cacheKey, image)
            }
        }
    }
}

/// Decoded profile photos, in memory only and only while the app is in the
/// foreground: emptied when it leaves (the app locks) and on a memory
/// warning. Keyed by the bytes' digest, so the same photo in the chat list,
/// the header and the map decodes once.
@MainActor
final class AvatarImageCache {
    static let shared = AvatarImageCache()

    private let images = NSCache<NSString, UIImage>()
    private var running: [String: Task<UIImage?, Never>] = [:]
    /// Photos that did not decode (they keep drawing the initial; not tried again).
    private var refused: Set<String> = []

    private init() {
        images.countLimit = 96
        let center = NotificationCenter.default
        for name in [UIApplication.didEnterBackgroundNotification, UIApplication.didReceiveMemoryWarningNotification] {
            _ = center.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { AvatarImageCache.shared.removeAll() }
            }
        }
    }

    func cached(_ photo: AvatarPhotoData) -> UIImage? {
        images.object(forKey: photo.cacheKey as NSString)
    }

    /// The decoded photo; `nil` when it is not a profile photo this app
    /// draws (§8.9: JPEG or HEIC, at most 256 × 256 by the decoder's own
    /// reading of the header).
    func image(for photo: AvatarPhotoData) async -> UIImage? {
        let key = photo.cacheKey
        if let image = images.object(forKey: key as NSString) { return image }
        guard !refused.contains(key) else { return nil }
        if let task = running[key] { return await task.value }
        let data = photo.data
        let contentType = photo.contentType
        let task = Task<UIImage?, Never> {
            let decoded = await Task.detached(priority: .userInitiated) {
                AvatarPhotoDecoder.decode(data, contentType: contentType)
            }.value
            return decoded.map { UIImage(cgImage: $0.image) }
        }
        running[key] = task
        let image = await task.value
        running[key] = nil
        if let image {
            images.setObject(image, forKey: key as NSString)
        } else {
            refused.insert(key)
        }
        return image
    }

    func removeAll() {
        images.removeAllObjects()
        refused = []
    }
}

/// A decoded image handed from the decoding task to the main actor.
struct DecodedAvatarImage: @unchecked Sendable {
    let image: CGImage
}

/// The receiver's side of §8.9 before anything is drawn: the kit's header
/// checks (type and signature, header size ≤ 256, byte limit), then the
/// decoder's own reading of the size, then the decode — in memory.
enum AvatarPhotoDecoder {
    nonisolated static func decode(_ data: Data, contentType: String) -> DecodedAvatarImage? {
        guard let photo = try? AvatarPhoto(contentType: contentType, data: data),
            let image = AvatarPhotoProcessor.image(of: photo)
        else { return nil }
        return DecodedAvatarImage(image: image)
    }
}

// MARK: - The user's own picture

extension EnvironmentValues {
    /// The user's own profile picture, for the places that draw the own
    /// avatar (palette D): 4.1's top bar, “Kodumu göster”, the line of 6.1 /
    /// 6.3, the live map. Set once at the root from `MessengerService.ownAvatar`.
    @Entry var u2OwnAvatar: AvatarPicture = .initials
}

// MARK: - Loading

/// A search result's picture on its way (4.2): a quarter of an `accent`
/// ring travels round the avatar, 3 pt outside it, over a faint track; it
/// stands still under Reduce Motion.
struct AvatarLoadingRing: View {
    let diameter: CGFloat

    var body: some View {
        MediaProgressRing(
            fraction: nil, shape: Circle(), lineWidth: 2, tint: Color.u2.accent, track: Color.u2.accent.opacity(0.18)
        )
        .frame(width: diameter + 6, height: diameter + 6)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
