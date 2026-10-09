import ImageIO
import SwiftUI
import UIKit

// MARK: - Geometry

/// C15 bubble outline: r20; the last bubble of a run gets a 6 pt tail corner
/// (bottom-left incoming `20/20/20/6`, bottom-right outgoing `20/20/6/20`).
struct BubbleShape: InsettableShape {
    let direction: MessageDirection
    let isTail: Bool
    var insetAmount: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        let r = U2Radius.bubble - insetAmount
        let tail = U2Radius.bubbleTail - insetAmount
        let radii = RectangleCornerRadii(
            topLeading: r,
            bottomLeading: isTail && direction == .incoming ? tail : r,
            bottomTrailing: isTail && direction == .outgoing ? tail : r,
            topTrailing: r)
        return UnevenRoundedRectangle(cornerRadii: radii, style: .continuous)
            .path(in: rect.insetBy(dx: insetAmount, dy: insetAmount))
    }

    func inset(by amount: CGFloat) -> BubbleShape {
        var copy = self
        copy.insetAmount += amount
        return copy
    }
}

/// The bubble column (`flex-direction: column; gap 5`): as wide as its widest
/// child (up to the proposal), children leading-aligned except those marked
/// `.u2BubbleTrailing()` (the time row, `align-self: flex-end`).
struct BubbleStack: Layout {
    var spacing: CGFloat = 5

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        // Stretching children (the reply quote) do not widen the bubble: they
        // count with their ideal width; everything else with the proposal.
        let widths = subviews.map { subview -> CGFloat in
            if subview[BubbleFillKey.self] {
                let ideal = subview.sizeThatFits(.unspecified).width
                return min(ideal, proposal.width ?? ideal)
            }
            return subview.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil)).width
        }
        let width = min(widths.max() ?? 0, proposal.width ?? .greatestFiniteMagnitude)
        let heights = subviews.map { $0.sizeThatFits(ProposedViewSize(width: width, height: nil)).height }
        let height = heights.reduce(0, +) + spacing * CGFloat(max(0, heights.count - 1))
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        var y = bounds.minY
        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            let trailing = subview[BubbleTrailingKey.self]
            let fill = subview[BubbleFillKey.self]
            let x = trailing ? bounds.maxX - size.width : bounds.minX
            subview.place(
                at: CGPoint(x: fill ? bounds.minX : x, y: y), anchor: .topLeading,
                proposal: ProposedViewSize(width: fill ? bounds.width : size.width, height: size.height))
            y += size.height + spacing
        }
    }
}

private struct BubbleTrailingKey: LayoutValueKey {
    static let defaultValue = false
}

private struct BubbleFillKey: LayoutValueKey {
    static let defaultValue = false
}

extension View {
    /// Right-aligns this child inside a `BubbleStack`.
    func u2BubbleTrailing() -> some View { layoutValue(key: BubbleTrailingKey.self, value: true) }
    /// Stretches this child to the bubble's width inside a `BubbleStack`.
    func u2BubbleFill() -> some View { layoutValue(key: BubbleFillKey.self, value: true) }
}

// MARK: - Container

/// C15 — a message bubble: `surface` (incoming) or `accent800` (outgoing)
/// behind the content, max width 290, pad 9 13 8 13, gap 5.
struct MessageBubble<Content: View>: View {
    enum Fill {
        case standard
        /// The deleted-message trace: transparent with an inner 1 pt `divider`.
        case tombstone
    }

    let direction: MessageDirection
    var isTail = false
    var maxWidth: CGFloat = 290
    var padding = EdgeInsets(top: 9, leading: 13, bottom: 8, trailing: 13)
    var fill: Fill = .standard
    @ViewBuilder let content: Content

    init(
        direction: MessageDirection, isTail: Bool = false, maxWidth: CGFloat = 290,
        padding: EdgeInsets = EdgeInsets(top: 9, leading: 13, bottom: 8, trailing: 13), fill: Fill = .standard,
        @ViewBuilder content: () -> Content
    ) {
        self.direction = direction
        self.isTail = isTail
        self.maxWidth = maxWidth
        self.padding = padding
        self.fill = fill
        self.content = content()
    }

    var body: some View {
        let shape = BubbleShape(direction: direction, isTail: isTail)
        BubbleStack(spacing: 5) { content }
            .padding(padding)
            .background {
                switch fill {
                case .standard:
                    shape.fill(direction == .incoming ? Color.u2.bubbleIncoming : Color.u2.bubbleOutgoing)
                case .tombstone:
                    shape.strokeBorder(Color.u2.divider, lineWidth: 1)
                }
            }
            // At most `maxWidth`, but reporting the bubble's own size (a
            // `.frame(maxWidth:)` would make every bubble a 290 pt box — the
            // 5.3 lift and its shadow need the real outline). The side comes
            // from the row's alignment.
            .modifier(BubbleWidthLimit(maxWidth: maxWidth))
    }
}

/// Proposes at most `maxWidth` to the bubble and takes the bubble's own size.
private struct BubbleWidthLimit: ViewModifier {
    let maxWidth: CGFloat

    func body(content: Content) -> some View {
        WidthLimitLayout(maxWidth: maxWidth) { content }
    }
}

private struct WidthLimitLayout: Layout {
    let maxWidth: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        guard let child = subviews.first else { return .zero }
        return child.sizeThatFits(limited(proposal))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        guard let child = subviews.first else { return }
        child.place(at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }

    private func limited(_ proposal: ProposedViewSize) -> ProposedViewSize {
        ProposedViewSize(width: min(proposal.width ?? .infinity, maxWidth), height: proposal.height)
    }
}

// MARK: - Meta row

/// The time row inside a bubble: optional disappearing badge (`ph:timer` 13 +
/// “5 dk”, `accent300`, gap 3) · gap 8 · time (`bubbleTime` 11.5, tabular,
/// `text·68%`) + 15 pt status glyph (outgoing), gap 4.
struct BubbleMeta: View {
    let time: Date
    var status: MessageStatus? = nil
    var disappearing: DisappearingTimer = .off
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(spacing: 8) {
            if disappearing != .off {
                HStack(spacing: 3) {
                    PhosphorIcon(.timer, size: 13)
                    Text(disappearing.shortKey)
                        .u2Font(.monoTimerInBubble)
                }
                .foregroundStyle(Color.u2.accent300)
                .accessibilityElement(children: .combine)
            }
            HStack(spacing: 4) {
                Text(verbatim: BubbleTimeFormat.string(time, locale: locale))
                    .u2Font(.bubbleTime.tabular)
                    .foregroundStyle(Color.u2.textSecondary)
                if let status {
                    // C16: a state change cross-fades in place (0.36 s).
                    StateCrossFade(value: status) { MessageStatusIcon(status: $0, size: 15) }
                }
            }
        }
        .frame(minHeight: status == nil ? 11.5 : 15)
        .accessibilityElement(children: .combine)
    }
}

extension DisappearingTimer {
    /// “30 sn”, “5 dk”, “1 sa”, “1 gün”, “Kapalı”.
    var shortKey: LocalizedStringKey {
        switch self {
        case .off: "disappearing.off"
        case .thirtySeconds: "disappearing.30s"
        case .fiveMinutes: "disappearing.5m"
        case .oneHour: "disappearing.1h"
        case .oneDay: "disappearing.1d"
        }
    }
}

// MARK: - Content bubbles

/// A text bubble: `bubble` (Inter 400 16/22.08) in `text` / `accent100`.
struct TextBubble: View {
    let message: Message
    let text: String
    var isTail = false

    var body: some View {
        MessageBubble(direction: message.direction, isTail: isTail) {
            Text(verbatim: text)
                .u2Font(.bubble)
                .foregroundStyle(message.isOutgoing ? Color.u2.onBubbleOutgoing : Color.u2.text)
                .fixedSize(horizontal: false, vertical: true)
            BubbleMeta(time: message.sentAt, status: message.status, disappearing: message.disappearing)
                .u2BubbleTrailing()
        }
    }
}

/// An outgoing (or incoming) reply: quote block on top (`bg·32%` r14, pad
/// 6 6 6 10) then the text (pad 0 7); max width 270, pad 6 6 8 6.
struct ReplyBubble: View {
    let message: Message
    let text: String
    let quote: QuotedMessage
    var isTail = false

    var body: some View {
        MessageBubble(
            direction: message.direction, isTail: isTail, maxWidth: 270,
            padding: EdgeInsets(top: 6, leading: 6, bottom: 8, trailing: 6)
        ) {
            ReplyQuote(quote: quote)
                .u2BubbleFill()
            Text(verbatim: text)
                .u2Font(.bubble)
                .foregroundStyle(message.isOutgoing ? Color.u2.onBubbleOutgoing : Color.u2.text)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 7)
            BubbleMeta(time: message.sentAt, status: message.status, disappearing: message.disappearing)
                .padding(.trailing, 7)
                .u2BubbleTrailing()
        }
    }
}

/// The quote block: author line (`ph:arrow-bend-up-left` 13 + `quoteAuthor`
/// `accent300`, gap 5) over the kind line (14 pt glyph + `quoteBody`
/// `text·68%`), with a 40 × 40 r8 thumbnail.
struct ReplyQuote: View {
    let quote: QuotedMessage

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    PhosphorIcon(.arrowBendUpLeft, size: 13)
                    Text(verbatim: quote.author).u2Font(.quoteAuthor)
                }
                .foregroundStyle(Color.u2.accent300)
                HStack(spacing: 5) {
                    kindGlyph
                    kindText.u2Font(.quoteBody).lineLimit(1)
                }
                .foregroundStyle(Color.u2.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let thumbnail = quote.thumbnail {
                BubbleMediaView(source: thumbnail, size: CGSize(width: 40, height: 40))
                    .frame(width: 40, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: U2Radius.quoteThumb, style: .continuous))
            }
        }
        .padding(EdgeInsets(top: 6, leading: 10, bottom: 6, trailing: 6))
        .background(RoundedRectangle(cornerRadius: U2Radius.bubbleQuote, style: .continuous).fill(Color.u2.quoteFill))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var kindGlyph: some View {
        switch quote.kind {
        case .photo: PhosphorIcon(.image, size: 14)
        case .video: PhosphorIcon(.play, weight: .fill, size: 14)
        case .voice: PhosphorIcon(.microphone, size: 14)
        case .file: PhosphorIcon(.filePdf, size: 14)
        case .text: EmptyView()
        }
    }

    private var kindText: Text {
        switch quote.kind {
        case .text(let text): Text(verbatim: text)
        case .photo: Text("media.photo")
        case .video: Text("media.video")
        case .voice: Text("media.voice")
        case .file(let name): Text(verbatim: name)
        }
    }
}

/// A photo: pad 5 5 8 5, photo r15 (258 × 176 in the design), caption pad 2 8 0, time with 8 pt trailing margin.
struct PhotoBubble: View {
    let message: Message
    let source: MediaSource
    let caption: String?
    var isTail = false

    var body: some View {
        MessageBubble(
            direction: message.direction, isTail: isTail, padding: EdgeInsets(top: 5, leading: 5, bottom: 8, trailing: 5)
        ) {
            BubbleMediaView(source: source, size: CGSize(width: 258, height: 176))
                .frame(width: 258, height: 176)
                .clipShape(RoundedRectangle(cornerRadius: U2Radius.bubbleMedia, style: .continuous))
                .accessibilityLabel(Text("media.photo"))
            if let caption {
                Text(verbatim: caption)
                    .u2Font(.bubble)
                    .foregroundStyle(message.isOutgoing ? Color.u2.onBubbleOutgoing : Color.u2.text)
                    .padding(EdgeInsets(top: 2, leading: 8, bottom: 0, trailing: 8))
            }
            BubbleMeta(time: message.sentAt, status: message.status, disappearing: message.disappearing)
                .padding(.trailing, 8)
                .u2BubbleTrailing()
        }
    }
}

/// A video poster (236 × 124 r15) with the centred glass play button and the
/// “0:18 · 12,4 MB” label.
struct VideoBubble: View {
    let message: Message
    let poster: MediaSource
    let duration: TimeInterval
    let byteCount: Int64
    var isTail = false
    @Environment(\.locale) private var locale

    var body: some View {
        MessageBubble(
            direction: message.direction, isTail: isTail, padding: EdgeInsets(top: 5, leading: 5, bottom: 8, trailing: 5)
        ) {
            BubbleMediaView(source: poster, size: CGSize(width: 236, height: 124))
                .frame(width: 236, height: 124)
                .overlay {
                    PhosphorIcon(.play, weight: .fill, size: 19)
                        .foregroundStyle(Color.u2.onMedia)
                        .frame(width: 46, height: 46)
                        .u2Backdrop(.mediaButton, in: Circle())
                        .overlay(Circle().strokeBorder(Color.u2.onMedia.opacity(0.35), lineWidth: 1))
                }
                .overlay(alignment: .bottomLeading) {
                    Text(verbatim: "\(U2Format.voiceDuration(duration)) · \(U2Format.byteCount(byteCount, locale: locale))")
                        .u2Font(.monoFileMeta)
                        .foregroundStyle(Color.u2.onMedia)
                        .padding(EdgeInsets(top: 3, leading: 7, bottom: 3, trailing: 7))
                        .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.u2.onMediaGlass(0.55)))
                        .padding(.leading, 10)
                        .padding(.bottom, 9)
                }
                .clipShape(RoundedRectangle(cornerRadius: U2Radius.bubbleMedia, style: .continuous))
                .accessibilityElement()
                .accessibilityLabel(Text("media.video"))
            BubbleMeta(time: message.sentAt, status: message.status, disappearing: message.disappearing)
                .padding(.trailing, 8)
                .u2BubbleTrailing()
        }
    }
}

/// A voice message: pad 9 12 8 9 — play button (36, 1 pt `accent`,
/// `ph:play` fill 15 `accent300`), 132 × 28 waveform, duration (Mono 11).
struct VoiceBubble: View {
    let message: Message
    let duration: TimeInterval
    let waveform: [Double]
    let playedFraction: Double
    var isTail = false
    var onPlay: () -> Void = {}

    var body: some View {
        MessageBubble(
            direction: message.direction, isTail: isTail, padding: EdgeInsets(top: 9, leading: 9, bottom: 8, trailing: 12)
        ) {
            HStack(spacing: 10) {
                Button(action: onPlay) {
                    PhosphorIcon(.play, weight: .fill, size: 15)
                        .foregroundStyle(Color.u2.accent300)
                        .frame(width: 36, height: 36)
                        .overlay(Circle().strokeBorder(Color.u2.accent, lineWidth: 1))
                        .contentShape(Circle())
                }
                .buttonStyle(CardButtonStyle())
                .accessibilityLabel(Text("a11y.voice.play"))
                Waveform(bars: waveform, playedFraction: playedFraction)
                    .frame(width: 132, height: 28)
                Text(verbatim: U2Format.voiceDuration(duration))
                    .u2Font(.monoVoiceDuration)
                    .foregroundStyle(Color.u2.textSecondary)
            }
            BubbleMeta(time: message.sentAt, status: message.status, disappearing: message.disappearing)
                .u2BubbleTrailing()
        }
    }
}

/// The voice waveform: bars 2.4 wide with round caps at a 4.4 pitch, centred
/// vertically; played part `accent300`, rest `text·38%`.
struct Waveform: View {
    /// Bar heights as fractions of the 28 pt box.
    let bars: [Double]
    let playedFraction: Double

    var body: some View {
        Canvas { context, size in
            let played = Int((Double(bars.count) * playedFraction).rounded())
            let scale = size.height / 28
            for (index, height) in bars.enumerated() {
                let x = (2 + 4.4 * CGFloat(index)) * scale
                let h = CGFloat(height) * size.height
                var path = Path()
                path.move(to: CGPoint(x: x, y: (size.height - h) / 2))
                path.addLine(to: CGPoint(x: x, y: (size.height + h) / 2))
                context.stroke(
                    path, with: .color(index < played ? Color.u2.accent300 : Color.u2.text.opacity(0.38)),
                    style: StrokeStyle(lineWidth: 2.4 * scale, lineCap: .round))
            }
        }
        .accessibilityHidden(true)
    }
}

/// A file: pad 10 12 8 10 — tile 42 × 50 r10 `bg·35%` with `ph:file-pdf` 25
/// `accent300`; name `bubbleTitleS` (one line); meta Mono 10.5 `text·68%`.
struct FileBubble: View {
    let message: Message
    let name: String
    let byteCount: Int64
    let typeLabel: String
    let pageCount: Int?
    var isTail = false
    @Environment(\.locale) private var locale

    var body: some View {
        MessageBubble(
            direction: message.direction, isTail: isTail, padding: EdgeInsets(top: 10, leading: 10, bottom: 8, trailing: 12)
        ) {
            HStack(spacing: 12) {
                PhosphorIcon(.filePdf, size: 25)
                    .foregroundStyle(Color.u2.accent300)
                    .frame(width: 42, height: 50)
                    .background(RoundedRectangle(cornerRadius: U2Radius.iconTileL, style: .continuous).fill(Color.u2.fileIconFill))
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: name)
                        .u2Font(.bubbleTitleS)
                        .foregroundStyle(message.isOutgoing ? Color.u2.onBubbleOutgoing : Color.u2.text)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(verbatim: meta)
                        .u2Font(.monoFileMeta)
                        .foregroundStyle(Color.u2.textSecondary)
                        .lineLimit(1)
                }
            }
            BubbleMeta(time: message.sentAt, status: message.status, disappearing: message.disappearing)
                .u2BubbleTrailing()
        }
    }

    private var meta: String {
        var parts = [U2Format.byteCount(byteCount, locale: locale), typeLabel]
        if let pageCount {
            parts.append(L10n.string("file.pages \(pageCount)", locale: locale))
        }
        return parts.joined(separator: " · ")
    }
}

/// A shared place: pad 8 12 8 8 — 64 × 64 r12 dotted map with the pin, title
/// `bubbleTitleS`, locality `metaInBubble`.
struct LocationBubble: View {
    let message: Message
    let name: String
    let locality: String
    var isTail = false

    var body: some View {
        MessageBubble(
            direction: message.direction, isTail: isTail, padding: EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 12)
        ) {
            HStack(spacing: 12) {
                DottedMap(showsPin: true)
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: U2Radius.iconTileXL, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(verbatim: name)
                        .u2Font(.bubbleTitleS)
                        .foregroundStyle(message.isOutgoing ? Color.u2.onBubbleOutgoing : Color.u2.text)
                    Text(verbatim: locality)
                        .u2Font(.metaInBubble)
                        .foregroundStyle(Color.u2.textSecondary)
                }
            }
            BubbleMeta(time: message.sentAt, status: message.status, disappearing: message.disappearing)
                .u2BubbleTrailing()
        }
    }
}

/// Live location: max 266, pad 5 5 8 5 — 256 × 92 r14 map with the pulsing
/// live dot; footer “Canlı konum” + remaining minutes and the “Durdur” pill.
struct LiveLocationBubble: View {
    let message: Message
    let expiresAt: Date
    let isActive: Bool
    var isTail = false
    var onStop: () -> Void = {}
    @Environment(\.locale) private var locale

    var body: some View {
        MessageBubble(
            direction: message.direction, isTail: isTail, maxWidth: 266,
            padding: EdgeInsets(top: 5, leading: 5, bottom: 8, trailing: 5)
        ) {
            DottedMap(showsPin: false)
                .frame(width: 256, height: 92)
                .overlay {
                    GeometryReader { proxy in
                        ZStack {
                            if isActive {
                                PulseRings()
                            }
                            Circle().fill(Color.u2.accent400)
                                .frame(width: 18, height: 18)
                                .u2OuterRing(Color.u2.bg, width: 3, in: Circle())
                                .u2BoxShadow(.glowLiveDot, in: Circle())
                        }
                        .position(x: proxy.size.width * 0.42, y: proxy.size.height * 0.46)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: U2Radius.bubbleMediaS, style: .continuous))
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        PhosphorIcon(.navigationArrow, size: 15).foregroundStyle(Color.u2.accent300)
                        Text("location.live")
                            .u2Font(.bubbleTitleS.weight(500))
                            .foregroundStyle(message.isOutgoing ? Color.u2.onBubbleOutgoing : Color.u2.text)
                    }
                    U2TimeView(interval: 30) { now in
                        let minutes = max(0, Int((expiresAt.timeIntervalSince(now) / 60).rounded(.up)))
                        Text("location.live.remaining \(minutes)")
                            .u2Font(.monoFileMeta)
                            .foregroundStyle(Color.u2.textSecondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if isActive && message.isOutgoing {
                    Button(action: onStop) { Text("location.live.stop") }
                        .buttonStyle(U2InlinePillButtonStyle())
                }
            }
            .padding(EdgeInsets(top: 2, leading: 6, bottom: 0, trailing: 4))
            .u2BubbleFill()
            BubbleMeta(time: message.sentAt, status: message.status, disappearing: message.disappearing)
                .padding(.trailing, 6)
                .u2BubbleTrailing()
        }
    }
}

/// The stylised dotted map of the location bubbles: `bg` with an 8 pt dot grid
/// (`text·18%`), two dotted “roads” (`text·46%` / `text·38%`), a park of
/// `accent·70%` dots and, for a place, the `ph:map-pin` fill 24 pin.
struct DottedMap: View {
    var showsPin: Bool

    var body: some View {
        Canvas { context, size in
            let w = size.width
            let h = size.height
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color.u2.bg))
            // Grid: `radial-gradient(text·18% 1px, transparent 1.4px) 0 0 / 8px 8px`.
            let grid = Color.u2.text.opacity(0.18)
            var y: CGFloat = 4
            while y < h + 2 {
                var x: CGFloat = 4
                while x < w + 2 {
                    Self.dot(&context, at: CGPoint(x: x, y: y), solid: 1, edge: 1.4, color: grid)
                    x += 8
                }
                y += 8
            }
            // Road 1: box x −20 %…120 %, y 54 % (+10), rotate(−11°) about its
            // centre; dots every 8 pt along its centre line.
            let road1 = CGPoint(x: 0.5 * w, y: 0.54 * h + 5)
            var x1 = -0.2 * w + 4
            while x1 <= 1.2 * w {
                let p = Self.rotate(CGPoint(x: x1, y: road1.y), about: road1, degrees: -11)
                Self.dot(&context, at: p, solid: 1.6, edge: 2, color: Color.u2.text.opacity(0.46))
                x1 += 8
            }
            // Road 2: box x 30 % (+10), y −20 %…120 %, rotate(+16°) about its centre.
            let road2 = CGPoint(x: 0.3 * w + 5, y: 0.5 * h)
            var y2 = -0.2 * h + 4
            while y2 <= 1.2 * h {
                let p = Self.rotate(CGPoint(x: road2.x, y: y2), about: road2, degrees: 16)
                Self.dot(&context, at: p, solid: 1.6, edge: 2, color: Color.u2.text.opacity(0.38))
                y2 += 8
            }
            // Park (after the roads, so on top): right 0 bottom 0, 48 % × 44 %,
            // top-left radius 46 (CSS scales it down to fit the box).
            let park = CGRect(x: 0.52 * w, y: 0.56 * h, width: 0.48 * w, height: 0.44 * h)
            let radius = min(46, park.width, park.height)
            let clip = UnevenRoundedRectangle(topLeadingRadius: radius, style: .circular).path(in: park)
            context.drawLayer { layer in
                layer.clip(to: clip)
                var py = park.minY + 4
                while py < park.maxY + 2 {
                    var px = park.minX + 4
                    while px < park.maxX + 2 {
                        Self.dot(&layer, at: CGPoint(x: px, y: py), solid: 1.3, edge: 1.7, color: Color.u2.accent.opacity(0.7))
                        px += 8
                    }
                    py += 8
                }
            }
        }
        .overlay {
            if showsPin {
                // `left 42 %, top 46 %, translate(−50 %, −100 %)`: the 24 pt
                // glyph box stands on that point.
                GeometryReader { proxy in
                    PhosphorIcon(.mapPin, weight: .fill, size: 24)
                        .foregroundStyle(Color.u2.accent300)
                        .shadow(color: Color.u2.shadowBlack(0.4), radius: 3, x: 0, y: 4)
                        .position(x: 0.42 * proxy.size.width, y: 0.46 * proxy.size.height - 12)
                }
            }
        }
        .accessibilityHidden(true)
    }

    /// One CSS `radial-gradient(circle, c solid, transparent edge)` dot.
    private static func dot(_ context: inout GraphicsContext, at center: CGPoint, solid: CGFloat, edge: CGFloat, color: Color) {
        let gradient = Gradient(stops: [
            .init(color: color, location: 0), .init(color: color, location: solid / edge),
            .init(color: color.opacity(0), location: 1),
        ])
        context.fill(
            Path(ellipseIn: CGRect(x: center.x - edge, y: center.y - edge, width: edge * 2, height: edge * 2)),
            with: .radialGradient(gradient, center: center, startRadius: 0, endRadius: edge))
    }

    /// CSS `rotate(θ)` (clockwise on screen) of `point` about `center`.
    private static func rotate(_ point: CGPoint, about center: CGPoint, degrees: Double) -> CGPoint {
        let a = degrees * .pi / 180
        let dx = point.x - center.x
        let dy = point.y - center.y
        return CGPoint(x: center.x + dx * cos(a) - dy * sin(a), y: center.y + dx * sin(a) + dy * cos(a))
    }
}

/// The deleted-message trace: transparent, inner 1 pt `divider`,
/// `ph:prohibit` 16 + “Bu mesaj herkesten silindi” Inter 400 *italic* 14.5 `text·68%`.
struct TombstoneBubble: View {
    let message: Message
    var isTail = false

    var body: some View {
        // The synthetic oblique overhangs the text frame: 4 pt of room on
        // the trailing side (taken from the bubble's padding, so the bubble
        // keeps its width) keeps the last glyph whole (“silindi”, “everyone”).
        MessageBubble(
            direction: message.direction, isTail: isTail,
            padding: EdgeInsets(top: 9, leading: 13, bottom: 8, trailing: 9), fill: .tombstone
        ) {
            HStack(spacing: 8) {
                PhosphorIcon(.prohibit, size: 16)
                Text("message.deleted").u2Font(.bubbleTombstone)
                    .padding(.trailing, 4)
            }
            .foregroundStyle(Color.u2.textSecondary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// View-once media: pad 9 13 8 9 — a 36 pt circle with a **1.5 pt dashed**
/// `accent300` ring and “1” (Mono 13), title `bubbleTitle`, subtitle
/// `metaInBubble`. Opened: solid `text·40%` ring and “Açıldı” (proposed).
struct ViewOnceBubble: View {
    let message: Message
    let isVideo: Bool
    let state: ViewOnceState
    var isTail = false

    var body: some View {
        MessageBubble(
            direction: message.direction, isTail: isTail, padding: EdgeInsets(top: 9, leading: 9, bottom: 8, trailing: 13)
        ) {
            HStack(spacing: 12) {
                ViewOnceBadge(diameter: 36, isOpened: state != .unopened)
                VStack(alignment: .leading, spacing: 2) {
                    Text(isVideo ? "media.video" : "media.photo")
                        .u2Font(.bubbleTitle)
                        .foregroundStyle(message.isOutgoing ? Color.u2.onBubbleOutgoing : Color.u2.text)
                    // CSS greedy wrapping (“…açmak için / dokun”): SwiftUI's
                    // widow avoidance would push “için” down.
                    GreedyParagraph(
                        text: L10n.string(subtitleKey, locale: locale), style: .metaInBubble,
                        color: Color.u2.textSecondary
                    )
                    .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: 220, alignment: .leading)
            }
            .padding(.vertical, 2)
            BubbleMeta(time: message.sentAt, status: message.status, disappearing: message.disappearing)
                .u2BubbleTrailing()
        }
        .accessibilityElement(children: .combine)
    }

    @Environment(\.locale) private var locale

    private var subtitleKey: String.LocalizationValue {
        switch state {
        case .unopened: "viewOnce.tapToOpen"
        case .opened: "viewOnce.opened"
        case .expired: "viewOnce.expired"
        }
    }
}

/// The view-once mark: a dashed `accent300` ring around “1” (1.3: 18 pt with
/// Mono 9; 5.2: 36 pt with Mono 13).
struct ViewOnceBadge: View {
    var diameter: CGFloat = 36
    var isOpened = false

    var body: some View {
        ZStack {
            if isOpened {
                Circle().strokeBorder(Color.u2.textTertiary, lineWidth: 1.5)
            } else {
                Circle().strokeBorder(Color.u2.accent300, style: StrokeStyle(lineWidth: 1.5, dash: [3, 2.5]))
            }
            Text(verbatim: "1")
                .u2Font(diameter < 24 ? .monoMicro : .monoLink, matchLineBox: false)
                .foregroundStyle(isOpened ? Color.u2.textTertiary : Color.u2.accent300)
        }
        .frame(width: diameter, height: diameter)
        .accessibilityHidden(true)
    }
}

/// A Doto text sticker (5.2): no bubble — a card rotated −6°, `accent200`,
/// r22, 4 pt `#f5f4ff` ring and a soft drop shadow, the word in
/// `displaySticker` `accent900` over a mini line mark; time beside it.
struct TextStickerMessage: View {
    let message: Message
    let text: String
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(alignment: .bottom, spacing: 10) {
            let shape = RoundedRectangle(cornerRadius: U2Radius.sticker, style: .continuous)
            VStack(spacing: 7) {
                Text(verbatim: text)
                    .u2Font(.displaySticker)
                    .foregroundStyle(Color.u2.accent900)
                LineMark(metrics: .sticker, color: Color.u2.accent700)
            }
            .padding(EdgeInsets(top: 12, leading: 18, bottom: 11, trailing: 18))
            .background(shape.fill(Color.u2.accent200))
            .u2OuterRing(Color.u2.stickerRing, width: 4, in: shape)
            .u2BoxShadow(.sticker, in: shape)
            .rotationEffect(.degrees(-6))
            Text(verbatim: BubbleTimeFormat.string(message.sentAt, locale: locale))
                .u2Font(.monoTag)
                .foregroundStyle(Color.u2.textSecondary)
        }
        .padding(EdgeInsets(top: 8, leading: 8, bottom: 0, trailing: 0))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text("a11y.sticker \(text)"))
    }
}

/// The reaction under a bubble: 34 × 27, r14, `accent800`, cut-out ring
/// 2.5 pt `bg`, emoji 14 — 12 pt in from the bubble's edge, overlapping its
/// bottom by 15 pt (the bubble keeps 14 pt free below).
struct ReactionChip: View {
    let reaction: Reaction

    var body: some View {
        HStack(spacing: 4) {
            // iOS draws Apple Color Emoji much larger than Chrome at small
            // sizes (14 pt → a 16 pt heart): 11 pt gives the drawn 12.3 pt
            // heart, in the 18 pt advance Chrome gives a 14 px emoji
            // (chip 8 + 18 + 8 = 34 wide).
            Text(verbatim: reaction.emoji)
                .font(.custom("AppleColorEmoji", fixedSize: 11))
                .frame(width: 18, height: 14, alignment: .leading)
                .offset(y: -1)
            if reaction.count > 1 {
                Text(verbatim: "\(reaction.count)")
                    .u2Font(.monoTag)
                    .foregroundStyle(Color.u2.accent100)
            }
        }
        .padding(.horizontal, 8)
        .frame(minWidth: 34, minHeight: 27, maxHeight: 27)
        .background(Capsule().fill(Color.u2.accent800))
        .u2OuterRing(Color.u2.bg, width: 2.5, in: Capsule())
        .accessibilityLabel(Text(verbatim: reaction.emoji))
    }
}

// MARK: - Typing (proposed)

/// The other side is writing (PROTOCOL §8.4 typing — accepted chats only,
/// optional on both sides): an incoming C15 bubble (`surface`, tail) holding
/// three 7 pt dots in `text·68%` that brighten in turn on the design curve
/// (0.45 s a step), gently. Reduce Motion or a frozen clock: the dots stand
/// still. Not drawn in the design — it uses the bubble language as it is.
struct TypingIndicatorBubble: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.u2Clock) private var clock

    var body: some View {
        MessageBubble(direction: .incoming, isTail: true, padding: EdgeInsets(top: 9, leading: 14, bottom: 8, trailing: 14)) {
            if reduceMotion || clock.isFrozen {
                TypingDots(active: nil)
            } else {
                PhaseAnimator([0, 1, 2]) { phase in
                    TypingDots(active: phase)
                } animation: { _ in
                    U2Motion.soft(0.45)
                }
            }
        }
    }
}

/// Three dots, the `active` one brighter (`nil`: all at rest).
private struct TypingDots: View {
    let active: Int?

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Color.u2.textSecondary)
                    .frame(width: 7, height: 7)
                    .opacity(active == nil ? 0.7 : (active == index ? 1 : 0.38))
                    .scaleEffect(active == index ? 1.08 : 1)
            }
        }
        .frame(height: 22.08)
        .accessibilityHidden(true)
    }
}

// MARK: - Cross-fade

/// Shows `content(value)`; when `value` changes, the old and the new content
/// cross-fade on top of each other (0.36 s on the design curve; Reduce Motion:
/// the short cross-fade). State-driven — it does not rely on an identity
/// transition, which a scrolling column may not run.
struct StateCrossFade<Value: Equatable, Content: View>: View {
    let value: Value
    let alignment: Alignment
    let content: (Value) -> Content

    /// What is on screen: the new value replaces it in `onChange`, so the
    /// frame in between still shows the old one (no flash of the new).
    @State private var shown: Value
    @State private var previous: Value?
    @State private var progress: Double = 1
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(value: Value, alignment: Alignment = .center, @ViewBuilder content: @escaping (Value) -> Content) {
        self.value = value
        self.alignment = alignment
        self.content = content
        _shown = State(initialValue: value)
    }

    var body: some View {
        let current = shown
        ZStack(alignment: alignment) {
            if let previous {
                content(previous)
                    .opacity(1 - progress)
                    .accessibilityHidden(true)
            }
            content(current)
                .opacity(previous == nil ? 1 : progress)
        }
        .onChange(of: value) { _, new in
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                previous = shown
                shown = new
                progress = 0
            }
            withAnimation(U2Motion.resolve(U2Motion.state, reduceMotion: reduceMotion), completionCriteria: .removed) {
                progress = 1
            } completion: {
                if progress == 1 { previous = nil }
            }
        }
    }
}

// MARK: - Time & media helpers (scrolling lists)

/// `HH:mm` for the bubbles' time rows: `U2Format.clockTime`, whose
/// formatters are cached per locale, calendar and time zone — a bubble's
/// body never builds a `DateFormatter` while a long conversation scrolls.
enum BubbleTimeFormat {
    static func string(_ date: Date, locale: Locale, calendar: Calendar = .current) -> String {
        U2Format.clockTime(date, locale: locale, calendar: calendar)
    }
}

/// A bubble's photo, video poster or quote thumbnail, in the fixed frame the
/// design gives it (so nothing moves when the image arrives): the design's
/// painted artwork, or an image file decoded **off the main thread** at the
/// pixel size it is shown (ImageIO thumbnail), fading in over `surface`.
/// Decoded images stay in memory only (never on disk) and are dropped when
/// the app leaves the foreground or memory runs low.
struct BubbleMediaView: View {
    let source: MediaSource
    /// The point size the media is shown at.
    let size: CGSize
    @Environment(\.displayScale) private var displayScale
    @State private var decoded: UIImage?

    var body: some View {
        switch source {
        case .designArtwork(let artwork):
            DesignArtworkView(artwork: artwork)
        case .memory(let data):
            let pixels = Int((max(size.width, size.height) * displayScale).rounded(.up))
            MemoryImageView(data: data, maxPixel: pixels)
                .frame(width: size.width, height: size.height)
                .clipped()
        case .file(let url):
            let pixels = Int((max(size.width, size.height) * displayScale).rounded(.up))
            let image = decoded ?? BubbleImageCache.shared.image(for: url, maxPixel: pixels)
            ZStack {
                Color.u2.surface
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                }
            }
            .frame(width: size.width, height: size.height)
            .clipped()
            .task(id: BubbleImageCache.Key(url: url, maxPixel: pixels)) {
                guard image == nil else { return }
                let loaded = await BubbleImageCache.shared.load(url, maxPixel: pixels)
                withAnimation(U2Motion.micro) { decoded = loaded }
            }
        }
    }
}

/// Decoded bubble images, in memory only.
@MainActor
final class BubbleImageCache {
    static let shared = BubbleImageCache()

    struct Key: Hashable, Sendable {
        let url: URL
        let maxPixel: Int
    }

    private let cache = NSCache<NSString, UIImage>()
    private var observers: [any NSObjectProtocol] = []

    private init() {
        cache.countLimit = 120
        cache.totalCostLimit = 96 * 1024 * 1024
        let center = NotificationCenter.default
        for name in [UIApplication.didEnterBackgroundNotification, UIApplication.didReceiveMemoryWarningNotification] {
            observers.append(
                center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                    MainActor.assumeIsolated { self?.cache.removeAllObjects() }
                })
        }
    }

    private func cacheKey(_ url: URL, _ maxPixel: Int) -> NSString { "\(maxPixel)|\(url.path)" as NSString }

    /// Already decoded (no work).
    func image(for url: URL, maxPixel: Int) -> UIImage? {
        cache.object(forKey: cacheKey(url, maxPixel))
    }

    /// Decodes on a background thread; `nil` if the file is not an image.
    func load(_ url: URL, maxPixel: Int) async -> UIImage? {
        if let cached = image(for: url, maxPixel: maxPixel) { return cached }
        let decoded = await Task.detached(priority: .userInitiated) { Self.decode(url, maxPixel: maxPixel) }.value
        guard let decoded else { return nil }
        let image = UIImage(cgImage: decoded)
        cache.setObject(image, forKey: cacheKey(url, maxPixel), cost: decoded.bytesPerRow * decoded.height)
        return image
    }

    nonisolated private static func decode(_ url: URL, maxPixel: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, maxPixel),
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}

// MARK: - Any message

/// One message as a C15 bubble, with the conversation's interactions: tap a
/// photo / video (viewer), a view-once bubble (5.6), “Durdur” in a live
/// location (5.2). Live location switches to its ended state by itself. The
/// reaction chip sits 12 pt in from the bubble's leading edge on both sides.
///
/// A change of kind (text → “herkesten silindi” trace, live → ended, view
/// once → opened) morphs when the column passes the message it `replacing`:
/// the old bubble shrinks a touch and fades while the new one fades in, and
/// the box between them changes size frame by frame (bottom-aligned), so the
/// rows above glide instead of jumping — never two bubbles stacked.
struct MessageBubbleView: View {
    let message: Message
    var isTail = false
    /// The message as it was before its kind changed (the column's morph).
    var replacing: Message? = nil
    /// The morph finished.
    var onReplaced: () -> Void = {}
    var onStopLiveLocation: () -> Void = {}
    var onOpenViewOnce: () -> Void = {}
    var onOpenMedia: () -> Void = {}

    @State private var morphProgress: Double = 0
    @State private var finishedMorph: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var morphKey: String? { replacing.map { "\($0.id)|\($0.contentKind)" } }

    var body: some View {
        let key = morphKey
        let previous = key != nil && key != finishedMorph ? replacing : nil
        let progress = previous == nil ? 1 : morphProgress
        KindMorphLayout(progress: progress, alignsTrailing: message.isOutgoing) {
            content(message, isTail: isTail)
                .opacity(progress)
            if let previous {
                content(previous, isTail: isTail)
                    .scaleEffect(reduceMotion ? 1 : 1 - 0.06 * progress, anchor: message.isOutgoing ? .bottomTrailing : .bottomLeading)
                    .opacity(1 - progress)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        // The chip sits 12 pt in from the bubble's leading edge on both
        // sides: on an outgoing bubble the trailing corner holds the time
        // and the delivery state, which must stay readable (5.1 draws only
        // the incoming case).
        .overlay(alignment: .bottomLeading) {
            if let reaction = message.reactions.first {
                ReactionChip(reaction: reaction)
                    .padding(.leading, 12)
                    .offset(y: 15)
                    .transition(.u2Pop)
            }
        }
        // Animatable, so the column's height grows frame by frame and
        // the rows above glide instead of jumping (a plain padding change
        // resizes the bottom-anchored list at once).
        .modifier(AnimatedBottomSpace(space: message.reactions.isEmpty ? 0 : 14))
        .u2Animation(U2Motion.state, value: message.reactions)
        .onChange(of: key, initial: true) { _, key in
            guard let key, key != finishedMorph else { return }
            withAnimation(U2Motion.resolve(U2Motion.state, reduceMotion: reduceMotion), completionCriteria: .logicallyComplete) {
                morphProgress = 1
            } completion: {
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    finishedMorph = key
                    morphProgress = 0
                }
                onReplaced()
            }
        }
    }

    @ViewBuilder private func content(_ message: Message, isTail: Bool) -> some View {
        switch message.content {
        case .text(let text):
            if let quote = message.replyTo {
                ReplyBubble(message: message, text: text, quote: quote, isTail: isTail)
            } else {
                TextBubble(message: message, text: text, isTail: isTail)
            }
        case .photo(let source, let caption):
            PhotoBubble(message: message, source: source, caption: caption, isTail: isTail)
                .onTapGesture(perform: onOpenMedia)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction(named: Text("a11y.media.open"), onOpenMedia)
        case .video(let poster, let duration, let byteCount):
            VideoBubble(message: message, poster: poster, duration: duration, byteCount: byteCount, isTail: isTail)
                .onTapGesture(perform: onOpenMedia)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction(named: Text("a11y.media.open"), onOpenMedia)
        case .voice(let duration, let waveform, let played):
            VoiceBubble(message: message, duration: duration, waveform: waveform, playedFraction: played, isTail: isTail)
        case .file(let name, let byteCount, let typeLabel, let pageCount):
            FileBubble(message: message, name: name, byteCount: byteCount, typeLabel: typeLabel, pageCount: pageCount, isTail: isTail)
        case .location(let name, let locality, _, _):
            LocationBubble(message: message, name: name, locality: locality, isTail: isTail)
        case .liveLocation(let expiresAt, let isActive):
            LiveLocationContent(message: message, expiresAt: expiresAt, isActive: isActive, isTail: isTail, onStop: onStopLiveLocation)
        case .textSticker(let text):
            TextStickerMessage(message: message, text: text)
        case .viewOnce(let isVideo, let state):
            ViewOnceBubble(message: message, isVideo: isVideo, state: state, isTail: isTail)
                .onTapGesture {
                    if state == .unopened { onOpenViewOnce() }
                }
                .accessibilityAddTraits(state == .unopened && !message.isOutgoing ? .isButton : [])
        case .deleted:
            TombstoneBubble(message: message, isTail: isTail)
        case .system(let event):
            SystemEventPill(event: event)
                .frame(maxWidth: .infinity)
        }
    }
}

/// The morph's box: the current bubble's size, or — while a previous bubble
/// is still fading out — the size between the two (`progress` 0 = the old
/// one's, 1 = the new one's). Every child sits on the bottom edge, at the
/// bubble's side.
private struct KindMorphLayout: Layout {
    var progress: Double
    let alignsTrailing: Bool

    nonisolated var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    nonisolated func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        guard let current = subviews.first else { return .zero }
        let to = current.sizeThatFits(proposal)
        guard subviews.count > 1, progress < 1 else { return to }
        let from = subviews[1].sizeThatFits(proposal)
        let t = CGFloat(max(0, min(1, progress)))
        return CGSize(width: from.width + (to.width - from.width) * t, height: from.height + (to.height - from.height) * t)
    }

    nonisolated func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        for subview in subviews {
            let size = subview.sizeThatFits(proposal)
            let x = alignsTrailing ? bounds.maxX - size.width : bounds.minX
            subview.place(at: CGPoint(x: x, y: bounds.maxY - size.height), anchor: .topLeading, proposal: ProposedViewSize(size))
        }
    }
}

/// Bottom padding whose value is interpolated every frame (layout follows).
private struct AnimatedBottomSpace: ViewModifier, Animatable {
    var space: CGFloat

    nonisolated var animatableData: CGFloat {
        get { space }
        set { space = newValue }
    }

    func body(content: Content) -> some View {
        content.padding(.bottom, space)
    }
}

/// Live location: active (countdown in the bubble + “Durdur”) until it is
/// stopped or runs out — then the ended state (5.2 › States). Switches by
/// itself when the time is up.
private struct LiveLocationContent: View {
    let message: Message
    let expiresAt: Date
    let isActive: Bool
    let isTail: Bool
    let onStop: () -> Void

    var body: some View {
        U2TimeView(interval: 30) { now in
            if isActive && expiresAt > now {
                LiveLocationBubble(message: message, expiresAt: expiresAt, isActive: true, isTail: isTail, onStop: onStop)
                    .transition(.u2Fade)
            } else {
                EndedLiveLocationBubble(message: message, isTail: isTail)
                    .transition(.u2Fade)
            }
        }
    }
}

// MARK: - Live location, ended (5.2 state)

/// Live location after sharing stopped or expired: the same bubble, the map
/// greyed and still, the footer “Canlı konum paylaşımı bitti” in `text·68%`
/// (inventory 5.2 › States).
struct EndedLiveLocationBubble: View {
    let message: Message
    var isTail = false

    var body: some View {
        MessageBubble(
            direction: message.direction, isTail: isTail, maxWidth: 266,
            padding: EdgeInsets(top: 5, leading: 5, bottom: 8, trailing: 5)
        ) {
            DottedMap(showsPin: false)
                .frame(width: 256, height: 92)
                .saturation(0)
                .opacity(0.55)
                .overlay {
                    GeometryReader { proxy in
                        Circle().fill(Color.u2.textSecondary)
                            .frame(width: 14, height: 14)
                            .u2OuterRing(Color.u2.bg, width: 3, in: Circle())
                            .position(x: proxy.size.width * 0.42, y: proxy.size.height * 0.46)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: U2Radius.bubbleMediaS, style: .continuous))
            HStack(spacing: 6) {
                PhosphorIcon(.navigationArrow, size: 15)
                Text("liveLocation.ended")
                    .u2Font(.bubbleTitleS.weight(400))
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(Color.u2.textSecondary)
            .padding(EdgeInsets(top: 2, leading: 6, bottom: 0, trailing: 4))
            .u2BubbleFill()
            BubbleMeta(time: message.sentAt, status: message.status, disappearing: message.disappearing)
                .padding(.trailing, 6)
                .u2BubbleTrailing()
        }
        .accessibilityElement(children: .combine)
    }
}

/// A system line in the chat (5.1 item 1 and the 5.5 note's proposed events).
struct SystemEventPill: View {
    let event: SystemEvent

    var body: some View {
        switch event {
        case .ghostEnabled(let period):
            SystemPill(glyph: .ghost, text: Text("system.ghost.on \(Text(period.longKey))"))
        case .ghostDisabled:
            SystemPill(glyph: .ghost, text: Text("system.ghost.off"))
        case .disappearingChanged(let timer):
            SystemPill(glyph: .timer, text: Text("system.disappearing \(Text(timer.shortKey))"))
        case .screenshotTaken(let user):
            SystemPill(glyph: .camera, text: Text("system.screenshot \(user)"))
        case .safetyNumberChanged:
            SystemPill(glyph: .warningCircle, text: Text("system.safetyNumber.changed"))
        case .safetyNumberVerified:
            SystemPill(glyph: .sealCheck, glyphWeight: .fill, text: Text("system.safetyNumber.verified"))
        }
    }
}

extension GhostPeriod {
    /// Chip labels (5.5): “1 sa”, “6 sa”, “24 sa”, “3 gün”, “7 gün”.
    var chipKey: LocalizedStringKey {
        switch self {
        case .oneHour: "ghost.period.chip.1h"
        case .sixHours: "ghost.period.chip.6h"
        case .oneDay: "ghost.period.chip.24h"
        case .threeDays: "ghost.period.chip.3d"
        case .sevenDays: "ghost.period.chip.7d"
        }
    }

    /// Long labels (7.1, system lines): “1 saat” … “7 gün”.
    var longKey: LocalizedStringKey {
        switch self {
        case .oneHour: "ghost.period.long.1h"
        case .sixHours: "ghost.period.long.6h"
        case .oneDay: "ghost.period.long.24h"
        case .threeDays: "ghost.period.long.3d"
        case .sevenDays: "ghost.period.long.7d"
        }
    }
}

#Preview("Bubbles — 5.1 + 5.2") {
    let messages = DesignFixtures.denizMessages(english: false) + DesignFixtures.mertMessages(english: false)
    return ScrollView {
        VStack(spacing: U2Spacing.bubbleGap) {
            ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                MessageBubbleView(message: message, isTail: index + 1 == messages.count || messages[index + 1].direction != message.direction)
                    .frame(maxWidth: .infinity, alignment: message.isOutgoing ? .trailing : .leading)
            }
            MessageStatusSentence(status: .sent)
        }
        .padding(.horizontal, U2Spacing.chatGutter)
        .padding(.vertical, 20)
    }
    .background(Color.u2.bg)
    .environment(\.locale, Locale(identifier: "tr"))
    .environment(\.u2Clock, .frozen(at: DesignFixtures.referenceDate))
}
