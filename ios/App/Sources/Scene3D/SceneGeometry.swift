// us2us — geometry of the welcome scene, ported 1:1 from us2us-scene.js and
// the three.js r160 geometry generators it uses (LatheGeometry,
// TorusGeometry, CylinderGeometry), including their vertex order, winding
// and normal smoothing, so shading matches the design exactly.

import Foundation
import simd

/// Cup proportions (us2us-scene.js `H`, `RB`, `RT`, `W`).
enum CupShape {
    static let height = 1.1
    static let bottomRadius = 0.36
    static let topRadius = 0.52
    static let wall = 0.025
}

/// Indexed triangle mesh: positions (and normals when lit) as packed float3.
struct SceneMesh {
    var vertices: [Float]
    var indices: [UInt16]
}

enum SceneGeometry {
    // MARK: Cup

    /// `LatheGeometry` of the cup profile with 72 segments; interleaved
    /// position + normal (packed float3 each).
    static func cup() -> SceneMesh {
        let h = CupShape.height, rb = CupShape.bottomRadius, rt = CupShape.topRadius, w = CupShape.wall
        let profile: [SIMD2<Double>] = [
            SIMD2(0.001, 0), SIMD2(rb, 0), SIMD2(rt, h), SIMD2(rt - w, h), SIMD2(rb - w, w), SIMD2(0.001, w),
        ]
        return lathe(profile, segments: 72)
    }

    static func lathe(_ points: [SIMD2<Double>], segments: Int) -> SceneMesh {
        // Pre-computed normals of the initial meridian, exactly as three.js
        // does it (un-normalised neighbours summed, so the bottom edge is
        // smoothed into the wall).
        var initNormals: [SIMD2<Double>] = []
        var prev = SIMD2<Double>(0, 0)
        let last = points.count - 1
        for j in 0...last {
            switch j {
            case 0:
                let d = points[1] - points[0]
                let n = SIMD2(d.y, -d.x)
                prev = n
                initNormals.append(normalize2(n))
            case last:
                initNormals.append(prev)
            default:
                let d = points[j + 1] - points[j]
                let cur = SIMD2(d.y, -d.x)
                initNormals.append(normalize2(cur + prev))
                prev = cur
            }
        }

        var vertices: [Float] = []
        vertices.reserveCapacity((segments + 1) * points.count * 6)
        for i in 0...segments {
            let phi = Double(i) * (1.0 / Double(segments)) * (Double.pi * 2)
            let s = sin(phi), c = cos(phi)
            for j in 0...last {
                let p = points[j], n = initNormals[j]
                vertices += [Float(p.x * s), Float(p.y), Float(p.x * c)]
                vertices += [Float(n.x * s), Float(n.y), Float(n.x * c)]
            }
        }

        var indices: [UInt16] = []
        let stride = points.count
        for i in 0..<segments {
            for j in 0..<last {
                let base = j + i * stride
                let a = base, b = base + stride, c = base + stride + 1, d = base + 1
                indices += [a, b, d, c, d, b].map { UInt16($0) }
            }
        }
        return SceneMesh(vertices: vertices, indices: indices)
    }

    private static func normalize2(_ v: SIMD2<Double>) -> SIMD2<Double> {
        let l = (v.x * v.x + v.y * v.y).squareRoot()
        return l > 0 ? v / l : v
    }

    /// Rim: `TorusGeometry(RT - W / 2, 0.018, 10, 96)` (positions only, unlit).
    static func rim() -> SceneMesh {
        torus(radius: CupShape.topRadius - CupShape.wall / 2, tube: 0.018, radialSegments: 10, tubularSegments: 96)
    }

    static func torus(radius: Double, tube: Double, radialSegments: Int, tubularSegments: Int) -> SceneMesh {
        var vertices: [Float] = []
        for j in 0...radialSegments {
            for i in 0...tubularSegments {
                let u = Double(i) / Double(tubularSegments) * Double.pi * 2
                let v = Double(j) / Double(radialSegments) * Double.pi * 2
                vertices += [
                    Float((radius + tube * cos(v)) * cos(u)),
                    Float((radius + tube * cos(v)) * sin(u)),
                    Float(tube * sin(v)),
                ]
            }
        }
        var indices: [UInt16] = []
        for j in 1...radialSegments {
            for i in 1...tubularSegments {
                let a = (tubularSegments + 1) * j + i - 1
                let b = (tubularSegments + 1) * (j - 1) + i - 1
                let c = (tubularSegments + 1) * (j - 1) + i
                let d = (tubularSegments + 1) * j + i
                indices += [a, b, d, b, c, d].map { UInt16($0) }
            }
        }
        return SceneMesh(vertices: vertices, indices: indices)
    }

    /// The line: `CylinderGeometry(0.009, 0.009, 1, 8, 1, true).rotateZ(π / 2)`
    /// — an open tube of length 1 along x (scaled to 2L per frame).
    static func string() -> SceneMesh {
        let radius = 0.009, height = 1.0, radialSegments = 8, heightSegments = 1
        let rz = Double.pi / 2, cz = cos(rz), sz = sin(rz)
        var vertices: [Float] = []
        var grid: [[Int]] = []
        var index = 0
        for y in 0...heightSegments {
            var row: [Int] = []
            let v = Double(y) / Double(heightSegments)
            for x in 0...radialSegments {
                let theta = Double(x) / Double(radialSegments) * Double.pi * 2
                let px = radius * sin(theta), py = -v * height + height / 2, pz = radius * cos(theta)
                vertices += [Float(px * cz - py * sz), Float(px * sz + py * cz), Float(pz)]
                row.append(index)
                index += 1
            }
            grid.append(row)
        }
        var indices: [UInt16] = []
        for x in 0..<radialSegments {
            for y in 0..<heightSegments {
                let a = grid[y][x], b = grid[y + 1][x], c = grid[y + 1][x + 1], d = grid[y][x + 1]
                indices += [a, b, d, b, c, d].map { UInt16($0) }
            }
        }
        return SceneMesh(vertices: vertices, indices: indices)
    }

    // MARK: Line segments (pairs of packed float3)

    /// Cup wireframe: 18 meridians + rings at 0, ⅓ and ⅔ of the height.
    static func cupWire() -> [Float] {
        let h = CupShape.height, rb = CupShape.bottomRadius, rt = CupShape.topRadius
        let n = 18, k = 1.004
        var p: [Float] = []
        for i in 0..<n {
            let a = Double(i) / Double(n) * Double.pi * 2, s = sin(a), c = cos(a)
            p += [Float(rb * k * s), 0, Float(rb * k * c), Float(rt * k * s), Float(h), Float(rt * k * c)]
        }
        for y in [0.0, 0.33, 0.66] {
            let r = (rb + (rt - rb) * y) * k, yy = y * h, m = 72
            for i in 0..<m {
                let a0 = Double(i) / Double(m) * Double.pi * 2, a1 = Double(i + 1) / Double(m) * Double.pi * 2
                p += [Float(r * sin(a0)), Float(yy), Float(r * cos(a0)), Float(r * sin(a1)), Float(yy), Float(r * cos(a1))]
            }
        }
        return p
    }

    /// Tunnel lattice around the line: 13 staggered hexagonal rings.
    static func lattice() -> [Float] {
        let r = 0.3, rings = 13
        func v(_ j: Int, _ i: Int) -> [Float] {
            let a = (Double(i) + Double(j % 2) * 0.5) / 6 * Double.pi * 2
            return [Float(Double(j) / Double(rings - 1) - 0.5), Float(r * cos(a)), Float(r * sin(a))]
        }
        var p: [Float] = []
        for j in 0..<rings {
            for i in 0..<6 {
                p += v(j, i) + v(j, (i + 1) % 6)
                if j < rings - 1 {
                    p += v(j, i) + v(j + 1, i)
                    p += v(j, (i + 1) % 6) + v(j + 1, i)
                }
            }
        }
        return p
    }

    /// Hexagon in the YZ plane (scan ring, relay ring).
    static func hexRing(radius r: Double) -> [Float] {
        var p: [Float] = []
        for i in 0..<6 {
            let a0 = Double(i) / 6 * Double.pi * 2, a1 = Double(i + 1) / 6 * Double.pi * 2
            p += [0, Float(r * cos(a0)), Float(r * sin(a0)), 0, Float(r * cos(a1)), Float(r * sin(a1))]
        }
        return p
    }

    // MARK: Points (packed float3 centres)

    /// 180 glow points along the unit line (scaled to 2L per frame).
    static func glowLine() -> [Float] {
        let n = 180
        var p: [Float] = []
        for i in 0..<n { p += [Float(Double(i) / Double(n - 1) - 0.5), 0, 0] }
        return p
    }

    static let dustSeed: UInt32 = 0x7532_7573
    static let dustCount = 420

    /// 420 dust motes in a 16 × 10 × 9 box in front of and behind the cups.
    static func dust(seed: UInt32 = dustSeed) -> [Float] {
        var random = SceneRandom(seed: seed)
        var p: [Float] = []
        p.reserveCapacity(dustCount * 3)
        for _ in 0..<dustCount {
            let x = (random.next() - 0.5) * 16
            let y = (random.next() - 0.5) * 10
            let z = -6 + random.next() * 9
            p += [Float(x), Float(y), Float(z)]
        }
        return p
    }

    /// Centre of the axis-aligned bounds of packed float3 points (three.js
    /// `computeBoundingSphere` centre, used for transparent sorting).
    static func boundsCenter(_ p: [Float]) -> SIMD3<Float> {
        var lo = SIMD3<Float>(repeating: .greatestFiniteMagnitude), hi = -lo
        for i in stride(from: 0, to: p.count, by: 3) {
            let v = SIMD3(p[i], p[i + 1], p[i + 2])
            lo = simd_min(lo, v)
            hi = simd_max(hi, v)
        }
        return (lo + hi) / 2
    }

    // MARK: Dot texture

    static let dotTextureSize = 64

    /// The scene's 64 × 64 radial "dot" (canvas radial gradient: alpha 1 at
    /// the centre, 0.6 at 22 %, 0 at the edge), straight (non-premultiplied)
    /// RGBA8 — white wherever alpha > 0, as WebGL un-premultiplies a canvas.
    static func dotTexture() -> [UInt8] {
        let n = dotTextureSize, c = Double(n) / 2
        var px = [UInt8](repeating: 0, count: n * n * 4)
        for y in 0..<n {
            for x in 0..<n {
                let dx = Double(x) + 0.5 - c, dy = Double(y) + 0.5 - c
                let r = min((dx * dx + dy * dy).squareRoot() / c, 1)
                let a = r < 0.22 ? 1 - 0.4 * (r / 0.22) : 0.6 * (1 - (r - 0.22) / 0.78)
                let a8 = UInt8((max(0, min(1, a)) * 255).rounded())
                let o = (y * n + x) * 4
                let rgb: UInt8 = a8 > 0 ? 255 : 0
                px[o] = rgb
                px[o + 1] = rgb
                px[o + 2] = rgb
                px[o + 3] = a8
            }
        }
        return px
    }
}
