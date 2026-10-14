import Foundation
import Us2usKit

/// One photo or video picked in “+” › Fotoğraf ve video (the system photo
/// picker, out of process: no photo-library permission). A pick holds up to
/// `PickedMediaPlan.selectionLimit` of them, in the order they were selected.
struct PickedMediaItem: Sendable {
    enum Kind: Hashable, Sendable {
        case photo
        case video
    }

    enum Source: Sendable {
        /// The live picker's copy in the protected pick folder
        /// (`SystemPickers`), still being handed over when the pick returns:
        /// a long or iCloud item takes a while. `nil`: it could not be read.
        case copy(Task<URL?, Never>)
        /// One of the design's pictures (the fixtures send the design's content).
        case artwork(DesignArtwork)
    }

    let kind: Kind
    let source: Source
}

/// One message of a pick: the item and, on the first photo only, the
/// composer's text as its caption.
struct PickedMediaMessage: Sendable {
    let item: PickedMediaItem
    let caption: String?
}

/// How a pick leaves: every item as a message of its own, in the order it
/// was selected — never an album, so each one can fail, be sent again or be
/// deleted on its own, and an older app on the other side shows them all.
///
/// * At most `selectionLimit` items (the picker's own limit; anything
///   beyond it is dropped).
/// * The composer's text, if any, becomes the caption of the first photo —
///   trimmed, and only while it fits the protocol's caption limit
///   (`ContentLimits.maxCaptionBytes`); otherwise it stays in the composer.
///   A video carries none: its bubble has no caption.
/// * View-once is a single item without a caption (a caption would be
///   visible before it is opened; the runtime refuses one).
enum PickedMediaPlan {
    /// Ten at a time.
    static let selectionLimit = 10

    /// The picker's limit: one for view-once.
    static func limit(viewOnce: Bool) -> Int {
        viewOnce ? 1 : selectionLimit
    }

    /// The messages of a pick, in the order the items were selected.
    static func messages(
        for items: [PickedMediaItem], caption: String?, viewOnce: Bool = false
    ) -> [PickedMediaMessage] {
        let kept = items.prefix(limit(viewOnce: viewOnce))
        let text = viewOnce ? nil : usableCaption(caption)
        let captioned = text == nil ? nil : kept.firstIndex { $0.kind == .photo }
        return kept.indices.map { index in
            PickedMediaMessage(item: kept[index], caption: index == captioned ? text : nil)
        }
    }

    /// The composer's text as a caption, or `nil` when there is none or it
    /// is too long for one (it then stays in the composer).
    static func usableCaption(_ text: String?) -> String? {
        guard let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty,
            trimmed.utf8.count <= ContentLimits.maxCaptionBytes
        else { return nil }
        return trimmed
    }
}
