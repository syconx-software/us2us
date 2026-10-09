import SwiftUI

extension QRModules {
    /// The design's placeholder pattern (the design file's `buildQr`):
    /// 29 × 29, Lehmer generator seeded 20261008, three finder patterns. Not
    /// a valid QR code — used only for the phone mock on the simulator's
    /// camera placeholder (6.2), so the screen matches the reference.
    static let designMock: QRModules = {
        let n = 29
        var seed: Int64 = 20_261_008
        func random() -> Double {
            seed = (seed * 48_271) % 2_147_483_647
            return Double(seed) / 2_147_483_647
        }
        var grid = (0..<n).map { _ in (0..<n).map { _ in random() > 0.52 } }
        func finder(_ r0: Int, _ c0: Int) {
            for r in -1...7 {
                for c in -1...7 {
                    let rr = r0 + r
                    let cc = c0 + c
                    guard rr >= 0, cc >= 0, rr < n, cc < n else { continue }
                    let ring = max(abs(r - 3), abs(c - 3))
                    grid[rr][cc] = (0...6).contains(r) && (0...6).contains(c) && (ring == 3 || ring <= 1)
                }
            }
        }
        finder(0, 0)
        finder(0, n - 7)
        finder(n - 7, 0)
        return QRModules(size: n, bits: grid.flatMap { $0 })
    }()
}

/// The 12 groups of 5 digits: 4 auto-width columns, column gap 18, row gap
/// 10, Mono 400 15/23.25 `text·88%`, centred.
struct SafetyDigitsGrid: View {
    let groups: [String]
    var color: Color = Color.u2.textSafetyDigits

    var body: some View {
        // Row gap 10 on 23.25 pt rows; the browser snaps each row's baseline
        // to whole device pixels (rows 33 pt apart at 3×), SwiftUI rounds
        // the 23.25 pt rows up — 9.75 lands every row where it was drawn.
        Grid(horizontalSpacing: 18, verticalSpacing: 9.75) {
            ForEach(0..<(groups.count / 4), id: \.self) { row in
                GridRow {
                    ForEach(0..<4, id: \.self) { column in
                        Text(verbatim: groups[row * 4 + column])
                            .u2Font(.monoSafetyDigits)
                            .foregroundStyle(color)
                            .fixedSize()
                    }
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("safety.a11y.digits", table: .verification))
        .accessibilityValue(Text(verbatim: VerificationText.spokenDigits(groups)))
    }
}

#Preview("QR modules") {
    VStack(spacing: 24) {
        QRCodeCard(payload: SafetyQRCodec.encode(Data(DesignFixtures.safetyNumberGroups.joined().utf8)), label: Text(verbatim: "QR"))
        QRModulesView(modules: .designMock)
            .frame(width: 148, height: 148)
            .padding(12)
            .background(Color.u2.qrPaperScan)
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.u2.bg)
}
