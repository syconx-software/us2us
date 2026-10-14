import SwiftUI

/// C20 — the line mark: two rings joined by a bar (“İki uç. Bir hat.”).
struct LineMark: View {
    struct Metrics: Sendable {
        var ringDiameter: CGFloat
        var ringStroke: CGFloat
        var width: CGFloat
        var barHeight: CGFloat

        /// 3.1 lock brand block: Ø14, stroke 2, 52 wide.
        static let lock = Metrics(ringDiameter: 14, ringStroke: 2, width: 52, barHeight: 2)
        /// 3.4 privacy cover at full size (thumbnail 60 × 16 ÷ 0.7015).
        static let cover = Metrics(ringDiameter: 23, ringStroke: 3, width: 86, barHeight: 3)
        /// 4.1 “Hat açık”: Ø9, stroke 2, 60 wide, gradient bar with packets.
        static let connection = Metrics(ringDiameter: 9, ringStroke: 2, width: 60, barHeight: 2)
        /// 5.2 sticker mini mark: Ø10, stroke 2, 36 wide, bar 2 (the design's
        /// own box sizes — not the −6° card's rotated bounding box).
        static let sticker = Metrics(ringDiameter: 10, ringStroke: 2, width: 36, barHeight: 2)
    }

    var metrics: Metrics = .lock
    var color: Color = Color.u2.accent400
    /// The 4.1 bar: `lineGradient` with flowing light packets.
    var isFlowing = false

    var body: some View {
        HStack(spacing: 0) {
            ring
            if isFlowing {
                LineBar(height: metrics.barHeight, isFlowing: true)
            } else {
                Rectangle().fill(color).frame(height: metrics.barHeight)
            }
            ring
        }
        .frame(width: metrics.width, height: metrics.ringDiameter)
        .accessibilityHidden(true)
    }

    private var ring: some View {
        Circle()
            .strokeBorder(color, lineWidth: metrics.ringStroke)
            .frame(width: metrics.ringDiameter, height: metrics.ringDiameter)
    }
}

/// The bar of the line: `lineGradient` (accent400 → accent·30 % → accent400),
/// with light packets travelling both ways when flowing.
struct LineBar: View {
    var height: CGFloat = 2
    var isFlowing = true

    var body: some View {
        Rectangle()
            .fill(Color.u2.lineGradient)
            .frame(height: height)
            .frame(maxWidth: .infinity)
            .overlay {
                if isFlowing {
                    LinePackets()
                        .transition(.u2Fade)
                }
            }
            .u2Animation(U2Motion.state, value: isFlowing)
    }
}

/// `linePacket` (design keyframes u2-packet-r / u2-packet-l): a 44 × 4 capsule
/// (transparent → `accent200` → transparent, glow `0 0 12px 2px accent·50%`)
/// whose centre travels the full bar in 6 s on `cubic-bezier(.45,0,.55,1)`:
/// 0–6 % fade in · 0–60 % travel · 54–60 % fade out · 60–100 % rest. The
/// right-to-left packet starts 3 s later. Hidden under Reduce Motion; paused
/// (holding its frame) while the app is not active and while its screen is
/// covered by another one on the stack (4.1 under 5.1 / 7.1 …), so a hidden
/// screen never re-renders 60 times a second.
struct LinePackets: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.u2IsLayerVisible) private var isLayerVisible
    @Environment(\.u2Clock) private var clock

    private static let period: Double = 6
    private static let curve = UnitCurve.bezier(
        startControlPoint: UnitPoint(x: 0.45, y: 0), endControlPoint: UnitPoint(x: 0.55, y: 1))

    var body: some View {
        if reduceMotion {
            EmptyView()
        } else if clock.isFrozen {
            // The references are paused 2 s into the loop.
            packets(at: 2)
        } else {
            TimelineView(.animation(minimumInterval: 1 / 60, paused: scenePhase != .active || !isLayerVisible)) { context in
                packets(at: context.date.timeIntervalSinceReferenceDate)
            }
        }
    }

    private func packets(at time: TimeInterval) -> some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            ZStack(alignment: .leading) {
                packet(state(time: time, delay: 0), width: width, leftToRight: true)
                packet(state(time: time, delay: 3), width: width, leftToRight: false)
            }
            .frame(width: width, height: proxy.size.height)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// (position 0…1, opacity) for a packet.
    private func state(time: TimeInterval, delay: Double) -> (position: Double, opacity: Double) {
        let local = time - delay
        // Before its first run the packet is hidden (its inline opacity is 0).
        guard local >= 0 || time > 1_000 else { return (0, 0) }
        let phase = (local.truncatingRemainder(dividingBy: Self.period) + Self.period)
            .truncatingRemainder(dividingBy: Self.period) / Self.period
        let travel = phase < 0.6 ? Self.curve.value(at: phase / 0.6) : 1
        let opacity: Double
        switch phase {
        case ..<0.06: opacity = Self.curve.value(at: phase / 0.06)
        case ..<0.54: opacity = 1
        case ..<0.6: opacity = 1 - Self.curve.value(at: (phase - 0.54) / 0.06)
        default: opacity = 0
        }
        return (travel, opacity)
    }

    private func packet(_ state: (position: Double, opacity: Double), width: CGFloat, leftToRight: Bool) -> some View {
        let fraction = leftToRight ? state.position : 1 - state.position
        let shape = Capsule()
        return shape
            .fill(Color.u2.linePacketGradient)
            .frame(width: 44, height: 4)
            .u2BoxShadow(.glowPacket, in: shape)
            .position(x: width * fraction, y: 1)
            .opacity(state.opacity)
    }
}

/// C20 · the sen ↔ peer line of 6.1 / 6.3, laid out like the design's CSS:
/// the two ends (avatar 48 + Mono 11 label, gap 8) and between them the
/// 2 pt bar with a 22 pt bottom margin, centred on the row — so the bar (and
/// the seal on its midpoint) sits 25.5 pt below the avatars' top, 1.5 pt
/// under their centre line, exactly as drawn. The bar overlaps each avatar
/// by 2 pt (painted over the leading avatar, under the trailing one, like
/// the DOM order).
struct ConnectionLine: View {
    let ownUsername: String
    let ownLabel: Text
    let peer: Peer
    var isFlowing = true
    var showsSeal = false
    /// Plays the seal's entrance (scale 0.6 → 1 with its glow, 0.5 s ease-out;
    /// a 0.36 s fade under Reduce Motion).
    var animatesSeal = false
    /// Seconds before the seal's entrance starts (6.3 after the 6.2 cover has gone).
    var entranceDelay: Double = 0

    @State private var sealShown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The own profile picture (proposed), set at the root.
    @Environment(\.u2OwnAvatar) private var ownAvatar

    var body: some View {
        HStack(alignment: .center, spacing: 0) {
            end(Avatar(username: ownUsername, palette: .d, size: .verify, picture: ownAvatar), label: ownLabel)
            LineBar(height: 2, isFlowing: isFlowing)
                .overlay(alignment: .top) {
                    if showsSeal {
                        VerifiedSeal()
                            .scaleEffect(sealShown || reduceMotion ? 1 : 0.6)
                            .opacity(sealShown ? 1 : 0)
                            .offset(y: -15)
                            .transition(.u2Fade)
                    }
                }
                .padding(.horizontal, -2)
                .padding(.bottom, 22)
            end(
                Avatar(username: peer.username, palette: peer.palette, size: .verify, picture: peer.avatar),
                label: Text(verbatim: peer.username))
        }
        .padding(.horizontal, 30)
        .onAppear {
            guard animatesSeal else {
                sealShown = true
                return
            }
            withAnimation(
                (reduceMotion ? U2Motion.reducedCrossFade : U2Motion.out(U2Motion.Duration.screen)).delay(entranceDelay)
            ) {
                sealShown = true
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func end(_ avatar: Avatar, label: Text) -> some View {
        VStack(spacing: 8) {
            avatar
            label
                .u2Font(.monoMeta)
                .foregroundStyle(Color.u2.textSecondary)
                .lineLimit(1)
                .fixedSize()
        }
        .frame(width: 48)
    }
}

/// 6.3 verified seal: 32 circle `bg`, ring `0 0 0 1px accent800`, glow
/// `0 0 24px accent·55%`, `ph:seal-check` fill 22 `accent`.
struct VerifiedSeal: View {
    var body: some View {
        PhosphorIcon(.sealCheck, weight: .fill, size: 22, label: Text("a11y.verified"))
            .foregroundStyle(Color.u2.accent)
            .frame(width: 32, height: 32)
            .background(Circle().fill(Color.u2.bg))
            .u2OuterRing(Color.u2.accent800, width: 1, in: Circle())
            .u2BoxShadow(.glowSeal, in: Circle())
    }
}

#Preview("Line mark") {
    VStack(spacing: 32) {
        LineMark()
        LineMark(metrics: .connection, isFlowing: true)
        LineMark(metrics: .cover)
        ConnectionLine(
            ownUsername: "selin_n", ownLabel: Text(verbatim: "sen"),
            peer: Peer(username: "deniz", palette: .a, isVerified: true, isAcceptedContact: true, presence: .justNow))
        ConnectionLine(
            ownUsername: "selin_n", ownLabel: Text(verbatim: "sen"),
            peer: Peer(username: "deniz", palette: .a, isVerified: true, isAcceptedContact: true, presence: .justNow),
            showsSeal: true, animatesSeal: true)
    }
    .padding(.vertical, 40)
    .frame(maxWidth: .infinity)
    .background(Color.u2.bg)
}
