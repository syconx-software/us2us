/*
 * The verification card's QR code and safety number are an illustration,
 * not a real code: the same seeded patterns as the design ("us2us Web",
 * buildQr() and the safety digits), computed once at build time.
 */

const QR_SIZE = 29

/** A 29×29 QR-like pattern with the three finder squares, as one SVG path (viewBox 0 0 29 29). */
export function exampleQrPath(): string {
  let seed = 20261008
  const random = () => (seed = (seed * 48271) % 2147483647) / 2147483647
  const cells = Array.from({ length: QR_SIZE }, () => Array.from({ length: QR_SIZE }, () => random() > 0.52))
  const finder = (row0: number, col0: number) => {
    for (let r = -1; r <= 7; r++) {
      for (let c = -1; c <= 7; c++) {
        const row = row0 + r
        const col = col0 + c
        if (row < 0 || col < 0 || row >= QR_SIZE || col >= QR_SIZE) continue
        const ring = Math.max(Math.abs(r - 3), Math.abs(c - 3))
        cells[row]![col] = r >= 0 && r <= 6 && c >= 0 && c <= 6 && (ring === 3 || ring <= 1)
      }
    }
  }
  finder(0, 0)
  finder(0, QR_SIZE - 7)
  finder(QR_SIZE - 7, 0)
  // One rectangle per horizontal run of dark cells.
  let d = ''
  for (let row = 0; row < QR_SIZE; row++) {
    let col = 0
    while (col < QR_SIZE) {
      if (!cells[row]![col]) {
        col++
        continue
      }
      const start = col
      while (col < QR_SIZE && cells[row]![col]) col++
      d += `M${start} ${row}h${col - start}v1H${start}z`
    }
  }
  return d
}

/** Twelve groups of five digits, like a real safety number. */
export function exampleSafetyNumber(): string[] {
  let seed = 1337
  const random = () => (seed = (seed * 16807) % 2147483647) / 2147483647
  return Array.from({ length: 12 }, () => Array.from({ length: 5 }, () => Math.floor(random() * 10)).join(''))
}
