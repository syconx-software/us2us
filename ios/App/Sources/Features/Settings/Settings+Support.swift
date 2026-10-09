import SwiftUI
import UIKit

// Building blocks only section 07 uses.

// MARK: - Status bar fade

/// 7.1 and its pickers scroll their header away, and the rows then pass under
/// the status bar. A short `bg` fade behind the clock keeps it legible; it
/// ends above the header line (y 56), so the resting layout is untouched.
struct SettingsStatusBarFade: View {
    @Environment(\.u2SafeArea) private var safeArea

    var body: some View {
        let height = max(safeArea.top - 6, 0)
        let solid = height > 0 ? max(safeArea.top - 20, 0) / height : 0
        LinearGradient(
            stops: [
                .init(color: Color.u2.bg, location: 0),
                .init(color: Color.u2.bg, location: solid),
                .init(color: Color.u2.bg.opacity(0), location: 1),
            ], startPoint: .top, endPoint: .bottom
        )
        .frame(height: height)
        .frame(maxWidth: .infinity)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

// MARK: - File card (7.2, 7.3)

/// The backup file card: `surface` r20, pad 14 16, gap 14 — file tile 46 × 56
/// r10 (`accent900` + 1 pt `accent800`, `ph:file-lock` 26 `accent300`) · name
/// `monoFileName` (one line, …) over a `caption` line in `text·68%` · an
/// optional trailing status glyph.
struct BackupFileCard<Trailing: View>: View {
    let name: Text
    let caption: Text
    var captionColor: Color = Color.u2.textSecondary
    @ViewBuilder var trailing: Trailing

    var body: some View {
        let tile = RoundedRectangle(cornerRadius: U2Radius.iconTileL, style: .continuous)
        HStack(spacing: 14) {
            ZStack {
                tile.fill(Color.u2.accent900)
                tile.strokeBorder(Color.u2.accent800, lineWidth: 1)
                PhosphorIcon(.fileLock, size: 26)
                    .foregroundStyle(Color.u2.accent300)
            }
            .frame(width: U2Size.fileTile.width, height: U2Size.fileTile.height)
            .accessibilityHidden(true)
            // The reference's renderer rounds each baseline to a whole point;
            // at the card's position that puts the name 1.5 pt and the caption
            // ⅓ pt lower than the exact CSS line boxes do.
            VStack(alignment: .leading, spacing: 4) {
                name
                    .u2Font(.monoFileName)
                    .foregroundStyle(Color.u2.text)
                    .lineLimit(1)
                    // CSS `text-overflow: ellipsis`: cut at the end, as specified.
                    .truncationMode(.tail)
                    .offset(y: 1.5)
                caption
                    .u2Font(U2TextStyle.caption.proportional)
                    .foregroundStyle(captionColor)
                    .lineLimit(1)
                    .contentTransition(.opacity)
                    .offset(y: 1 / 3)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16))
        .frame(minHeight: 84)
        .background(RoundedRectangle(cornerRadius: U2Radius.cardL, style: .continuous).fill(Color.u2.surface))
        .accessibilityElement(children: .combine)
    }
}

extension BackupFileCard where Trailing == EmptyView {
    init(name: Text, caption: Text, captionColor: Color = Color.u2.textSecondary) {
        self.init(name: name, caption: caption, captionColor: captionColor) { EmptyView() }
    }
}
