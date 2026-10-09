import SwiftUI

/// The content of the security window: nothing, the privacy cover, or the
/// lock flow over the cover. Appearing is instant (it must be in the
/// snapshot); leaving dissolves softly (0.36 s).
///
/// Coming back from the background, the 3.4 cover gives way to the lock
/// with a 0.36 s cross-fade over an opaque base — so nothing of the app
/// beneath ever shows through while both are half transparent.
struct SecurityOverlayView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            if model.overlay != .none {
                ZStack {
                    Color.u2.bg.ignoresSafeArea()
                    switch model.overlay {
                    case .none:
                        EmptyView()
                    case .privacyCover:
                        PrivacyCoverView(snapshot: model.privacySnapshot)
                            .transition(.opacity)
                    case .lock:
                        ZStack {
                            PrivacyCoverView(snapshot: nil, showsMark: false)
                            LockFlowView()
                        }
                        .transition(.opacity)
                    }
                }
                .transition(.opacity)
            }
        }
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
        .dynamicTypeSize(...DynamicTypeSize.accessibility3)
        #if DEBUG
            .u2FrameProbe()
        #endif
    }
}

/// 3.4 — the cover in the app switcher: the current screen blurred (≈13 pt)
/// at 75 % opacity, a `bg·45%` tint, and the centred line mark, wordmark and
/// “Kilitli”. The design draws it at 0.70 scale inside the switcher card;
/// here it is full size (× 1 / 0.7015). Without a snapshot: solid `bg` with
/// the dot grid.
struct PrivacyCoverView: View {
    let snapshot: UIImage?
    var showsMark = true

    private static let scale: CGFloat = 1 / 0.7015

    var body: some View {
        ZStack {
            Color.u2.bg
            if let snapshot {
                Image(uiImage: snapshot)
                    .resizable()
                    .scaledToFill()
                    .opacity(U2Opacity.coverContent)
            } else {
                DotGridBackdrop(mask: .lock, color: Color.u2.dotGridSoft)
            }
            Color.u2.coverTint
            if showsMark {
                VStack(spacing: 14 * Self.scale) {
                    LineMark(metrics: .cover)
                    U2Wordmark(style: U2TextStyle.brandWordmarkL.scaled(Self.scale))
                    HStack(spacing: 6 * Self.scale) {
                        PhosphorIcon(.lockSimple, size: 13 * Self.scale)
                        Text("lock.cover.locked").u2Font(U2TextStyle.monoMeta.scaled(Self.scale))
                    }
                    .foregroundStyle(Color.u2.textSecondary)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .ignoresSafeArea()
    }
}
