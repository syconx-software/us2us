// us2us — small math helpers for the welcome scene (three.js conventions:
// right-handed, camera looking down -z, column vectors, Euler order XYZ).

import Foundation
import simd

enum SceneMath {
    static func clamp(_ x: Double, _ a: Double, _ b: Double) -> Double { min(b, max(a, x)) }

    /// `sm` in us2us-scene.js (smoothstep).
    static func smoothstep(_ a: Double, _ b: Double, _ x: Double) -> Double {
        let t = clamp((x - a) / (b - a), 0, 1)
        return t * t * (3 - 2 * t)
    }

    static func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }

    /// JavaScript `x % 1` for the non-negative values the scene uses.
    static func fract(_ x: Double) -> Double { x.truncatingRemainder(dividingBy: 1) }

    /// three.js `SRGBToLinear` (what `Color.setHex` stores with colour management on).
    static func srgbToLinear(_ c: Double) -> Double {
        c < 0.04045 ? c * 0.0773993808 : pow(c * 0.9478672986 + 0.0521327014, 2.4)
    }

    /// three.js `LinearToSRGB`.
    static func linearToSRGB(_ c: Double) -> Double {
        c < 0.0031308 ? c * 12.92 : 1.055 * pow(c, 0.41666) - 0.055
    }
}

/// An sRGB colour given as 0xRRGGBB, as in the scene's palette.
struct SceneColor: Equatable, Sendable {
    let r: Double
    let g: Double
    let b: Double

    init(hex: UInt32) {
        r = Double((hex >> 16) & 0xFF) / 255
        g = Double((hex >> 8) & 0xFF) / 255
        b = Double(hex & 0xFF) / 255
    }

    private init(r: Double, g: Double, b: Double) {
        self.r = r
        self.g = g
        self.b = b
    }

    /// Linear-light components (three.js working colour space).
    var linear: SIMD3<Double> {
        SIMD3(SceneMath.srgbToLinear(r), SceneMath.srgbToLinear(g), SceneMath.srgbToLinear(b))
    }

    /// `Color.lerp` on the stored (linear) values, returned as sRGB again.
    func mixed(with other: SceneColor, _ t: Double) -> SceneColor {
        if t <= 0 { return self }
        let l = linear + (other.linear - linear) * t
        return SceneColor(r: SceneMath.linearToSRGB(l.x), g: SceneMath.linearToSRGB(l.y), b: SceneMath.linearToSRGB(l.z))
    }

    /// sRGB-encoded rgb + alpha for the flat-colour shaders.
    func encoded(opacity: Double) -> SIMD4<Float> {
        SIMD4(Float(r), Float(g), Float(b), Float(opacity))
    }

    /// Linear rgb (optionally × intensity) + alpha for the lit and textured shaders.
    func linear(opacity: Double, intensity: Double = 1) -> SIMD4<Float> {
        let l = linear * intensity
        return SIMD4(Float(l.x), Float(l.y), Float(l.z), Float(opacity))
    }
}

/// mulberry32 — a tiny deterministic generator, so the dust field (and the
/// Reduce Motion still frame) is identical on every launch.
struct SceneRandom {
    private var state: UInt32

    init(seed: UInt32) { state = seed }

    mutating func next() -> Double {
        state = state &+ 0x6D2B_79F5
        var t = (state ^ (state >> 15)) &* (1 | state)
        t = (t &+ ((t ^ (t >> 7)) &* (61 | t))) ^ t
        return Double(t ^ (t >> 14)) / 4_294_967_296
    }
}

extension simd_float4x4 {
    static func translation(_ x: Float, _ y: Float, _ z: Float) -> simd_float4x4 {
        simd_float4x4(SIMD4(1, 0, 0, 0), SIMD4(0, 1, 0, 0), SIMD4(0, 0, 1, 0), SIMD4(x, y, z, 1))
    }

    static func scale(_ x: Float, _ y: Float, _ z: Float) -> simd_float4x4 {
        simd_float4x4(diagonal: SIMD4(x, y, z, 1))
    }

    static func rotationX(_ a: Float) -> simd_float4x4 {
        let c = cos(a), s = sin(a)
        return simd_float4x4(SIMD4(1, 0, 0, 0), SIMD4(0, c, s, 0), SIMD4(0, -s, c, 0), SIMD4(0, 0, 0, 1))
    }

    static func rotationY(_ a: Float) -> simd_float4x4 {
        let c = cos(a), s = sin(a)
        return simd_float4x4(SIMD4(c, 0, -s, 0), SIMD4(0, 1, 0, 0), SIMD4(s, 0, c, 0), SIMD4(0, 0, 0, 1))
    }

    static func rotationZ(_ a: Float) -> simd_float4x4 {
        let c = cos(a), s = sin(a)
        return simd_float4x4(SIMD4(c, s, 0, 0), SIMD4(-s, c, 0, 0), SIMD4(0, 0, 1, 0), SIMD4(0, 0, 0, 1))
    }

    /// View matrix of a camera at `eye` looking at `target` (three.js `Camera.lookAt`).
    static func lookAt(eye: SIMD3<Float>, target: SIMD3<Float>, up: SIMD3<Float> = SIMD3(0, 1, 0)) -> simd_float4x4 {
        let z = simd_normalize(eye - target)
        let x = simd_normalize(simd_cross(up, z))
        let y = simd_cross(z, x)
        return simd_float4x4(
            SIMD4(x.x, y.x, z.x, 0),
            SIMD4(x.y, y.y, z.y, 0),
            SIMD4(x.z, y.z, z.z, 0),
            SIMD4(-simd_dot(x, eye), -simd_dot(y, eye), -simd_dot(z, eye), 1)
        )
    }

    /// Right-handed perspective projection with Metal's 0…1 clip depth.
    static func perspective(fovyDegrees: Float, aspect: Float, near: Float, far: Float) -> simd_float4x4 {
        let ys = 1 / tan(fovyDegrees * .pi / 360)
        let xs = ys / aspect
        let zs = far / (near - far)
        return simd_float4x4(SIMD4(xs, 0, 0, 0), SIMD4(0, ys, 0, 0), SIMD4(0, 0, zs, -1), SIMD4(0, 0, zs * near, 0))
    }

    /// Inverse-transpose of the upper 3×3, embedded in a 4×4 (three.js `normalMatrix`).
    var normalMatrix: simd_float4x4 {
        let m = simd_float3x3(columns.0.xyz, columns.1.xyz, columns.2.xyz).inverse.transpose
        return simd_float4x4(SIMD4(m.columns.0, 0), SIMD4(m.columns.1, 0), SIMD4(m.columns.2, 0), SIMD4(0, 0, 0, 1))
    }

    func transformPoint(_ p: SIMD3<Float>) -> SIMD3<Float> { (self * SIMD4(p, 1)).xyz }

    func transformDirection(_ d: SIMD3<Float>) -> SIMD3<Float> { simd_normalize((self * SIMD4(d, 0)).xyz) }
}

extension SIMD4 where Scalar == Float {
    var xyz: SIMD3<Float> { SIMD3(x, y, z) }
}
