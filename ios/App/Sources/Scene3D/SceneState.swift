// us2us — scene states and palettes, mirroring `S` and `PAL` in us2us-scene.js.

import SwiftUI

/// Where the camera sits and what the line shows. Field names follow the
/// keys of the design's scene states (`S` in us2us-scene.js).
public struct SceneState: Equatable, Sendable {
    /// Camera position (`cx`, `cy`, `cz`).
    public var camera: SIMD3<Double>
    /// Point the camera looks at (`tx`, `ty`, `tz`).
    public var target: SIMD3<Double>
    /// Horizontal offset of the cups on wide canvases (`wx`).
    public var worldOffsetX: Double
    /// Base yaw of the cups, radians (`ry`); the scene sways ±0.07 around it.
    public var yaw: Double
    /// Half the distance between the two cup bases (`L`).
    public var halfLength: Double
    /// Strength of the light packets travelling left → right (`flow`).
    public var flow: Double
    /// Strength of the light packets travelling right → left (`back`).
    public var back: Double
    /// Visibility of the hexagonal tunnel around the line (`tunnel`).
    public var tunnel: Double
    /// Size of the sealed-envelope octahedra on the packets (`seal`).
    /// The design draws them fully transparent, so it has no visible effect.
    public var seal: Double
    /// Visibility of the single "message" packet of the status story (`story`).
    public var story: Double
    /// Progress through the status story, 0…1 (the web page's scroll progress).
    public var storyProgress: Double
    /// Dims the whole scene (`dim`, 1 → 20 % opacity).
    public var dim: Double

    public init(camera: SIMD3<Double>, target: SIMD3<Double>, worldOffsetX: Double, yaw: Double,
                halfLength: Double, flow: Double, back: Double, tunnel: Double, seal: Double,
                story: Double, storyProgress: Double = 0, dim: Double) {
        self.camera = camera
        self.target = target
        self.worldOffsetX = worldOffsetX
        self.yaw = yaw
        self.halfLength = halfLength
        self.flow = flow
        self.back = back
        self.tunnel = tunnel
        self.seal = seal
        self.story = story
        self.storyProgress = storyProgress
        self.dim = dim
    }

    /// The iOS welcome screen (design 1.1).
    public static let welcome = SceneState(
        camera: SIMD3(0, 0.55, 7.8), target: .zero, worldOffsetX: 0, yaw: -0.42, halfLength: 0.95,
        flow: 1, back: 0.8, tunnel: 0.4, seal: 0, story: 0, dim: 0)

    // The website's scroll states, for completeness.
    public static let hero = SceneState(
        camera: SIMD3(0, 0.55, 7.2), target: .zero, worldOffsetX: 1.45, yaw: -0.5, halfLength: 0.95,
        flow: 1, back: 0, tunnel: 0, seal: 0, story: 0, dim: 0)
    public static let line = SceneState(
        camera: SIMD3(0, 0.32, 3.7), target: .zero, worldOffsetX: 0.95, yaw: -0.24, halfLength: 1.2,
        flow: 1, back: 0, tunnel: 1, seal: 1, story: 0, dim: 0)
    public static let status = SceneState(
        camera: SIMD3(0, 0.42, 4.9), target: .zero, worldOffsetX: 1.3, yaw: -0.12, halfLength: 1.2,
        flow: 0, back: 0, tunnel: 0.5, seal: 0, story: 1, dim: 0)
    public static let ghost = SceneState(
        camera: SIMD3(0, 0.75, 6.0), target: .zero, worldOffsetX: 1.4, yaw: -0.42, halfLength: 1.0,
        flow: 0.3, back: 0, tunnel: 0, seal: 0, story: 0, dim: 0)
    public static let features = SceneState(
        camera: SIMD3(0, 1.9, 9.5), target: .zero, worldOffsetX: 0, yaw: 0.2, halfLength: 1.0,
        flow: 0.7, back: 0.5, tunnel: 0.3, seal: 0, story: 0, dim: 0.72)
    public static let verify = SceneState(
        camera: SIMD3(-2.3, 0.62, 1.5), target: SIMD3(0.9, -0.05, -0.6), worldOffsetX: 0, yaw: 0, halfLength: 1.2,
        flow: 1, back: 0, tunnel: 1, seal: 1, story: 0, dim: 0.25)
    public static let cta = SceneState(
        camera: SIMD3(0, 0.35, 5.6), target: .zero, worldOffsetX: 1.3, yaw: -0.6, halfLength: 0.62,
        flow: 1, back: 1, tunnel: 0.35, seal: 0, story: 0, dim: 0)
}

/// Colours and per-theme strengths (`PAL` in us2us-scene.js).
struct ScenePalette: Equatable, Sendable {
    let cup: SceneColor
    let edge: SceneColor
    let line: SceneColor
    let core: SceneColor
    let glow: SceneColor
    let dust: SceneColor
    let dead: SceneColor
    let sky: SceneColor
    let ground: SceneColor
    let key: SceneColor
    let rimLight: SceneColor
    /// Glows add light (dark theme) or are painted normally (light theme).
    let additive: Bool
    let wireOpacity: Double
    let dustOpacity: Double
    let glowOpacity: Double

    static let dark = ScenePalette(
        cup: SceneColor(hex: 0x2B2E3E), edge: SceneColor(hex: 0x9184D9), line: SceneColor(hex: 0xB5ABFC),
        core: SceneColor(hex: 0xE7E5FE), glow: SceneColor(hex: 0x9184D9), dust: SceneColor(hex: 0x9397AB),
        dead: SceneColor(hex: 0x75798C), sky: SceneColor(hex: 0x5D5294), ground: SceneColor(hex: 0x161826),
        key: SceneColor(hex: 0xE9E9ED), rimLight: SceneColor(hex: 0x9184D9),
        additive: true, wireOpacity: 0.34, dustOpacity: 0.5, glowOpacity: 0.10)

    static let light = ScenePalette(
        cup: SceneColor(hex: 0xDFE2F0), edge: SceneColor(hex: 0x796CBF), line: SceneColor(hex: 0x5D5294),
        core: SceneColor(hex: 0x5D5294), glow: SceneColor(hex: 0x796CBF), dust: SceneColor(hex: 0x75798C),
        dead: SceneColor(hex: 0x9397AB), sky: SceneColor(hex: 0xF3F5FE), ground: SceneColor(hex: 0xB2B6CA),
        key: SceneColor(hex: 0xF3F5FE), rimLight: SceneColor(hex: 0xB5ABFC),
        additive: false, wireOpacity: 0.55, dustOpacity: 0.32, glowOpacity: 0.14)

    static func forScheme(_ scheme: ColorScheme) -> ScenePalette {
        scheme == .light ? .light : .dark
    }
}
