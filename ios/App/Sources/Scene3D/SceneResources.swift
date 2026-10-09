// us2us — GPU resources shared by every welcome-scene view: shader library,
// pipelines, static meshes and the dot texture. Built once per process; the
// shader and pipeline compilation is awaited, so the main thread never blocks.

import Metal
import os

@MainActor
final class SceneResources {
    static let colorFormat: MTLPixelFormat = .bgra8Unorm
    static let depthFormat: MTLPixelFormat = .depth32Float

    struct Mesh {
        let vertices: any MTLBuffer
        let indices: any MTLBuffer
        let indexCount: Int
    }

    struct Lines {
        let positions: any MTLBuffer
        let vertexCount: Int
    }

    struct Points {
        let centers: any MTLBuffer
        let count: Int
        let boundsCenter: SIMD3<Float>
    }

    let device: any MTLDevice
    let queue: any MTLCommandQueue
    /// 2× MSAA where supported (low power: never more).
    let sampleCount: Int

    let litPipeline: any MTLRenderPipelineState
    let basicPipeline: any MTLRenderPipelineState
    let depthOnlyPipeline: any MTLRenderPipelineState
    let linePipeline: any MTLRenderPipelineState
    let ribbonPipeline: any MTLRenderPipelineState
    let spritePipelines: (normal: any MTLRenderPipelineState, additive: any MTLRenderPipelineState)
    let pointsPipelines: (normal: any MTLRenderPipelineState, additive: any MTLRenderPipelineState)
    let depthWrite: any MTLDepthStencilState
    let depthTestOnly: any MTLDepthStencilState

    let cup: Mesh
    let rim: Mesh
    let string: Mesh
    let cupWire: Lines
    let lattice: Lines
    let scanRing: Lines
    let relayRing: Lines
    let glowLine: Points
    let dust: Points
    let dotTexture: any MTLTexture
    let dotSampler: any MTLSamplerState

    private static let log = Logger(subsystem: "com.syconx.us2us", category: "Scene3D")
    private static var shared: SceneResources?
    private static var loading: Task<SceneResources?, Never>?

    /// The process-wide resources, or nil when Metal is unavailable (the
    /// scene then simply stays empty — it is decorative).
    static func load() async -> SceneResources? {
        if let shared { return shared }
        if let loading { return await loading.value }
        let task = Task { @MainActor () -> SceneResources? in
            do {
                return try await SceneResources()
            } catch {
                log.error("3D scene unavailable: \(String(describing: error), privacy: .public)")
                return nil
            }
        }
        loading = task
        let resources = await task.value
        shared = resources
        loading = nil
        return resources
    }

    enum SetupError: Error {
        case noDevice
        case missingFunction(String)
        case allocation(String)
    }

    private enum Blend { case normal, additive, depthOnly }

    private init() async throws {
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
            throw SetupError.noDevice
        }
        self.device = device
        self.queue = queue
        queue.label = "us2us.scene"
        sampleCount = device.supportsTextureSampleCount(2) ? 2 : 1

        let library = try await device.makeLibrary(source: SceneShaders.source, options: MTLCompileOptions())
        let sampleCount = sampleCount

        func function(_ name: String) throws -> any MTLFunction {
            guard let f = library.makeFunction(name: name) else { throw SetupError.missingFunction(name) }
            return f
        }

        func pipeline(_ label: String, vertex: String, fragment: String, blend: Blend) async throws -> any MTLRenderPipelineState {
            let d = MTLRenderPipelineDescriptor()
            d.label = label
            d.vertexFunction = try function(vertex)
            d.fragmentFunction = try function(fragment)
            d.rasterSampleCount = sampleCount
            d.depthAttachmentPixelFormat = Self.depthFormat
            guard let c = d.colorAttachments[0] else { throw SetupError.allocation("color attachment") }
            c.pixelFormat = Self.colorFormat
            c.isBlendingEnabled = true
            c.rgbBlendOperation = .add
            c.alphaBlendOperation = .add
            switch blend {
            case .normal: // three.js NormalBlending (straight alpha)
                c.sourceRGBBlendFactor = .sourceAlpha
                c.destinationRGBBlendFactor = .oneMinusSourceAlpha
                c.sourceAlphaBlendFactor = .one
                c.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            case .additive: // three.js AdditiveBlending (straight alpha)
                c.sourceRGBBlendFactor = .sourceAlpha
                c.destinationRGBBlendFactor = .one
                c.sourceAlphaBlendFactor = .sourceAlpha
                c.destinationAlphaBlendFactor = .one
            case .depthOnly:
                c.isBlendingEnabled = false
                c.writeMask = []
            }
            return try await device.makeRenderPipelineState(descriptor: d)
        }

        litPipeline = try await pipeline("lit", vertex: "us2us_lit_vertex", fragment: "us2us_lit_fragment", blend: .normal)
        basicPipeline = try await pipeline("basic", vertex: "us2us_basic_vertex", fragment: "us2us_basic_fragment", blend: .normal)
        depthOnlyPipeline = try await pipeline("depth", vertex: "us2us_basic_vertex", fragment: "us2us_basic_fragment", blend: .depthOnly)
        linePipeline = try await pipeline("line", vertex: "us2us_line_vertex", fragment: "us2us_line_fragment", blend: .normal)
        ribbonPipeline = try await pipeline("ribbon", vertex: "us2us_ribbon_vertex", fragment: "us2us_ribbon_fragment", blend: .normal)
        spritePipelines = (
            try await pipeline("sprite", vertex: "us2us_sprite_vertex", fragment: "us2us_sprite_fragment", blend: .normal),
            try await pipeline("sprite+", vertex: "us2us_sprite_vertex", fragment: "us2us_sprite_fragment", blend: .additive)
        )
        pointsPipelines = (
            try await pipeline("points", vertex: "us2us_points_vertex", fragment: "us2us_sprite_fragment", blend: .normal),
            try await pipeline("points+", vertex: "us2us_points_vertex", fragment: "us2us_sprite_fragment", blend: .additive)
        )

        func depthState(write: Bool) throws -> any MTLDepthStencilState {
            let d = MTLDepthStencilDescriptor()
            d.depthCompareFunction = .lessEqual
            d.isDepthWriteEnabled = write
            guard let s = device.makeDepthStencilState(descriptor: d) else { throw SetupError.allocation("depth state") }
            return s
        }
        depthWrite = try depthState(write: true)
        depthTestOnly = try depthState(write: false)

        func buffer<T>(_ array: [T], _ label: String) throws -> any MTLBuffer {
            let made = array.withUnsafeBytes { raw -> (any MTLBuffer)? in
                guard let base = raw.baseAddress, raw.count > 0 else { return nil }
                return device.makeBuffer(bytes: base, length: raw.count, options: .storageModeShared)
            }
            guard let b = made else {
                throw SetupError.allocation(label)
            }
            b.label = label
            return b
        }
        func mesh(_ m: SceneMesh, _ label: String) throws -> Mesh {
            Mesh(vertices: try buffer(m.vertices, label), indices: try buffer(m.indices, label + ".indices"), indexCount: m.indices.count)
        }
        func lines(_ p: [Float], _ label: String) throws -> Lines {
            Lines(positions: try buffer(p, label), vertexCount: p.count / 3)
        }
        func points(_ p: [Float], _ label: String) throws -> Points {
            Points(centers: try buffer(p, label), count: p.count / 3, boundsCenter: SceneGeometry.boundsCenter(p))
        }

        cup = try mesh(SceneGeometry.cup(), "cup")
        rim = try mesh(SceneGeometry.rim(), "rim")
        string = try mesh(SceneGeometry.string(), "string")
        cupWire = try lines(SceneGeometry.cupWire(), "cupWire")
        lattice = try lines(SceneGeometry.lattice(), "lattice")
        scanRing = try lines(SceneGeometry.hexRing(radius: 0.3), "scan")
        relayRing = try lines(SceneGeometry.hexRing(radius: 0.13), "relay")
        glowLine = try points(SceneGeometry.glowLine(), "glowLine")
        dust = try points(SceneGeometry.dust(), "dust")

        // Dot texture: sRGB colour (like three.js' SRGBColorSpace canvas
        // texture), linear alpha, full mip chain, trilinear sampling.
        let size = SceneGeometry.dotTextureSize
        let td = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm_srgb, width: size, height: size, mipmapped: true)
        td.usage = .shaderRead
        td.storageMode = .shared
        guard let texture = device.makeTexture(descriptor: td) else { throw SetupError.allocation("dot texture") }
        let pixels = SceneGeometry.dotTexture()
        pixels.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            texture.replace(region: MTLRegionMake2D(0, 0, size, size), mipmapLevel: 0, withBytes: base, bytesPerRow: size * 4)
        }
        if let cb = queue.makeCommandBuffer(), let blit = cb.makeBlitCommandEncoder() {
            blit.generateMipmaps(for: texture)
            blit.endEncoding()
            cb.commit()
        }
        dotTexture = texture

        let sd = MTLSamplerDescriptor()
        sd.minFilter = .linear
        sd.magFilter = .linear
        sd.mipFilter = .linear
        sd.sAddressMode = .clampToEdge
        sd.tAddressMode = .clampToEdge
        guard let sampler = device.makeSamplerState(descriptor: sd) else { throw SetupError.allocation("sampler") }
        dotSampler = sampler
    }
}
