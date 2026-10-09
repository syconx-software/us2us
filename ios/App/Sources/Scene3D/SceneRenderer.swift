// us2us — per-frame logic of the welcome scene: a port of `frame()` in
// us2us-scene.js (camera, sway, packets, tunnel, dust, end glows) and of
// three.js' transparent render list (every object is transparent, sorted
// back to front by its centre, ties by creation order).

import MetalKit
import simd

@MainActor
final class SceneRenderer: NSObject, MTKViewDelegate {
    var palette: ScenePalette = .dark
    var state: SceneState = .welcome
    /// When set, the scene is pinned to this time (Reduce Motion still frame,
    /// snapshots); otherwise it runs on the clock advanced by `advance(by:)`.
    var frozenTime: Double?
    var resources: SceneResources?

    /// Canvas opacity (`dim`, narrow canvases), reported when it changes.
    var onOpacityChange: ((Double) -> Void)?
    /// Called once, after the first frame has been committed.
    var onFirstFrame: (() -> Void)?

    private var time: Double = 0
    private var dustTime: Double = 0
    private var lastOpacity: Double = -1
    private var didRenderFirstFrame = false
    private var multisampleTarget: (any MTLTexture)?
    private var depthTarget: (any MTLTexture)?

    /// Advances the scene clock; like the web version a long gap (app was
    /// paused) counts as at most 50 ms, so nothing jumps on resume.
    func advance(by dt: Double) {
        let step = min(0.05, max(0, dt))
        time += step
        dustTime += step
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        guard let resources else { return }
        let size = view.bounds.size
        guard size.width >= 1, size.height >= 1, view.drawableSize.width >= 1, view.drawableSize.height >= 1,
              let drawable = view.currentDrawable,
              let commandBuffer = resources.queue.makeCommandBuffer() else { return }
        prepareTargets(resources, width: drawable.texture.width, height: drawable.texture.height)

        let pass = MTLRenderPassDescriptor()
        guard let color = pass.colorAttachments[0] else { return }
        if resources.sampleCount > 1, let msaa = multisampleTarget {
            color.texture = msaa
            color.resolveTexture = drawable.texture
            color.storeAction = .multisampleResolve
        } else {
            color.texture = drawable.texture
            color.storeAction = .store
        }
        color.loadAction = .clear
        color.clearColor = MTLClearColorMake(0, 0, 0, 0)
        pass.depthAttachment.texture = depthTarget
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.clearDepth = 1
        pass.depthAttachment.storeAction = .dontCare

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else { return }
        encoder.label = "us2us.scene"
        encode(encoder, resources: resources,
               canvas: SIMD2(Double(size.width), Double(size.height)),
               target: SIMD2(Float(drawable.texture.width), Float(drawable.texture.height)))
        encoder.endEncoding()
        commandBuffer.present(drawable)
        commandBuffer.commit()

        if !didRenderFirstFrame {
            didRenderFirstFrame = true
            onFirstFrame?()
        }
    }

    private func prepareTargets(_ resources: SceneResources, width: Int, height: Int) {
        if let depth = depthTarget, depth.width == width, depth.height == height { return }
        let samples = resources.sampleCount
        #if targetEnvironment(simulator)
        let storage: MTLStorageMode = .private
        #else
        let storage: MTLStorageMode = .memoryless
        #endif
        func texture(_ format: MTLPixelFormat) -> (any MTLTexture)? {
            let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: width, height: height, mipmapped: false)
            d.textureType = samples > 1 ? .type2DMultisample : .type2D
            d.sampleCount = samples
            d.usage = .renderTarget
            d.storageMode = storage
            return resources.device.makeTexture(descriptor: d)
        }
        multisampleTarget = samples > 1 ? texture(SceneResources.colorFormat) : nil
        depthTarget = texture(SceneResources.depthFormat)
    }

    // MARK: - Frame

    private struct FrameUniforms {
        var view: simd_float4x4
        var projection: simd_float4x4
        var viewport: SIMD4<Float>
        var keyDirection: SIMD4<Float>
        var keyColor: SIMD4<Float>
        var rimDirection: SIMD4<Float>
        var rimColor: SIMD4<Float>
        var hemiDirection: SIMD4<Float>
        var skyColor: SIMD4<Float>
        var groundColor: SIMD4<Float>
    }

    private struct DrawUniforms {
        var model: simd_float4x4
        var normalMatrix: simd_float4x4 = matrix_identity_float4x4
        var color: SIMD4<Float>
        var params: SIMD4<Float> = .zero
    }

    private enum Item {
        case cup(simd_float4x4)
        case solid(SceneResources.Mesh, simd_float4x4, SIMD4<Float>)
        case string(simd_float4x4, SIMD4<Float>)
        case lines(SceneResources.Lines, simd_float4x4, SIMD4<Float>)
        case glowLine(simd_float4x4)
        case sprite(SIMD3<Float>, size: Float, color: SIMD4<Float>)
        case dust
    }

    private struct Entry {
        let depth: Float
        let order: Int
        let item: Item
    }

    private func encode(_ encoder: any MTLRenderCommandEncoder, resources r: SceneResources,
                        canvas: SIMD2<Double>, target: SIMD2<Float>) {
        let s = state, p = palette
        let t = frozenTime ?? time
        let dustT = frozenTime ?? dustTime
        let sm = SceneMath.smoothstep

        // Camera (narrow canvases pull back and dim a little, as on the web).
        let narrow = SceneMath.clamp((1.2 - canvas.x / canvas.y) / 0.6, 0, 1)
        let kk = 1 + narrow * 0.75
        let eye = s.target + (s.camera - s.target) * kk
        let view = simd_float4x4.lookAt(eye: SIMD3<Float>(eye), target: SIMD3<Float>(s.target))
        let projection = simd_float4x4.perspective(fovyDegrees: 34, aspect: Float(canvas.x / canvas.y), near: 0.05, far: 100)

        let opacity = ((1 - s.dim * 0.8) * (1 - narrow * 0.45) * 100).rounded() / 100
        if opacity != lastOpacity {
            lastOpacity = opacity
            onOpacityChange?(opacity)
        }

        // World group: slow sway around the state's yaw (Euler XYZ).
        let world = simd_float4x4.translation(Float(s.worldOffsetX * (1 - narrow)), 0, 0)
            * simd_float4x4.rotationX(Float(sin(t * 0.21) * 0.03))
            * simd_float4x4.rotationY(Float(s.yaw + sin(t * 0.3) * 0.07))

        // (The web scene's "ghost" dissolve is driven by page scroll and not
        // used by the app, so every part is fully solid here.)
        let L = s.halfLength, Lf = Float(L)
        let H = Float(CupShape.height)

        var frame = FrameUniforms(
            view: view,
            projection: projection,
            viewport: SIMD4(target.x, target.y, target.y * 0.5, 0),
            keyDirection: SIMD4(view.transformDirection(SIMD3(-3, 4, 5)), 0),
            keyColor: p.key.linear(opacity: 1, intensity: 1.7),
            rimDirection: SIMD4(view.transformDirection(SIMD3(3, 1.5, -4)), 0),
            rimColor: p.rimLight.linear(opacity: 1, intensity: 2.4),
            hemiDirection: SIMD4(view.transformDirection(SIMD3(0, 1, 0)), 0),
            skyColor: p.sky.linear(opacity: 1, intensity: 1.1),
            groundColor: p.ground.linear(opacity: 1, intensity: 1.1))

        var entries: [Entry] = []
        entries.reserveCapacity(64)
        var order = 0
        func add(_ item: Item, center: SIMD3<Float>) {
            entries.append(Entry(depth: -(view * SIMD4(center, 1)).z, order: order, item: item))
            order += 1
        }

        // Status story (only when `story` > 0).
        var rA = 0.0, rB = 0.0
        let qs = s.storyProgress, sv = s.story
        var storySprite: (x: Double, size: Double, color: SceneColor, opacity: Double)?
        var receiptSprite: (x: Double, opacity: Double)?
        if sv > 0.01 {
            var tt: Double, dead = 0.0, so = 1.0, scl = 0.28, rc = -1.0
            if qs < 0.25 {
                tt = 0.5 * sm(0, 0.17, qs)
                if qs > 0.17 { scl = 0.28 + 0.05 * sin(t * 4) }
            } else if qs < 0.5 {
                tt = 0.5 + 0.5 * sm(0.25, 0.4, qs)
            } else if qs < 0.75 {
                tt = 1
                rc = 1 - sm(0.5, 0.66, qs)
            } else {
                tt = 0.5
                dead = sm(0.75, 0.8, qs)
                so = 1 - sm(0.82, 0.96, qs)
            }
            rB = sm(0.38, 0.42, qs) * (1 - sm(0.74, 0.76, qs))
            rA = rc >= 0 ? sm(0.6, 0.66, qs) * (1 - sm(0.74, 0.76, qs)) : 0
            storySprite = (-L + 2 * L * tt, scl, p.core.mixed(with: p.dead, dead), sv * so)
            receiptSprite = (-L + 2 * L * max(rc, 0), rc > 0.02 && rc < 0.98 ? sv * 0.9 : 0)
        }

        // Cups (base towards the line, mouth outwards) with rim and wireframe.
        let tunnel = s.tunnel
        for (side, rimMix) in [(-1.0, rA), (1.0, rB)] {
            let cup = world * simd_float4x4.translation(Float(side) * Lf, 0, 0) * simd_float4x4.rotationZ(Float(-side) * .pi / 2)
            let center = cup.transformPoint(SIMD3(0, H / 2, 0))
            add(.cup(cup), center: center)
            let rim = cup * simd_float4x4.translation(0, H, 0) * simd_float4x4.rotationX(.pi / 2)
            add(.solid(r.rim, rim, p.edge.mixed(with: p.core, rimMix).encoded(opacity: 1)), center: rim.transformPoint(.zero))
            add(.lines(r.cupWire, cup, p.edge.encoded(opacity: p.wireOpacity)), center: center)
        }

        // The line and its glow.
        let origin = world.transformPoint(.zero)
        let lineScale = world * simd_float4x4.scale(2 * Lf, 1, 1)
        add(.string(lineScale, p.line.encoded(opacity: 1)), center: origin)
        add(.glowLine(lineScale), center: origin)

        // Tunnel lattice, scanning ring, relay ring.
        let tk = Float(0.55 + 0.45 * s.tunnel)
        let latticeOpacity = tunnel * 0.42
        if latticeOpacity > 0.004 {
            let m = world * simd_float4x4.rotationX(Float(t * 0.25)) * simd_float4x4.scale(2 * Lf * 0.78, tk, tk)
            add(.lines(r.lattice, m, p.edge.encoded(opacity: latticeOpacity)), center: origin)
        } else { order += 1 }
        let sp = SceneMath.fract(t * 0.32)
        let scanOpacity = tunnel * 0.9 * sin(sp * .pi)
        if scanOpacity > 0.004 {
            let m = world * simd_float4x4.translation(Float((sp - 0.5) * 2 * L * 0.78), 0, 0)
                * simd_float4x4.rotationX(Float(t * 0.25)) * simd_float4x4.scale(1, tk, tk)
            add(.lines(r.scanRing, m, p.line.encoded(opacity: scanOpacity)), center: m.transformPoint(.zero))
        } else { order += 1 }
        let relayOpacity = sv * 0.7 + tunnel * 0.2
        if relayOpacity > 0.004 {
            let m = world * simd_float4x4.rotationX(Float(t * 0.6))
            add(.lines(r.relayRing, m, p.edge.encoded(opacity: relayOpacity)), center: origin)
        } else { order += 1 }

        // Light packets: four each way, a head and a fading four-dot trail.
        func sprite(x: Double, size: Double, color: SceneColor, opacity: Double) {
            let c = world.transformPoint(SIMD3(Float(x), 0, 0))
            if opacity > 0 {
                add(.sprite(c, size: Float(size), color: color.linear(opacity: opacity)), center: c)
            } else {
                order += 1
            }
        }
        func pulse(_ pt: Double, amp: Double, dir: Double) {
            sprite(x: -L + 2 * L * pt, size: 0.2, color: p.core, opacity: amp)
            for j in 0..<4 {
                let tj = SceneMath.clamp(pt - dir * Double(j + 1) * 0.014, 0, 1)
                sprite(x: -L + 2 * L * tj, size: 0.16 - Double(j) * 0.02, color: p.core, opacity: amp * (0.55 - Double(j) * 0.12))
            }
        }
        for i in 0..<4 {
            let pt = SceneMath.fract(t * 0.17 + Double(i) / 4)
            pulse(pt, amp: s.flow * sm(0, 0.08, pt) * (1 - sm(0.9, 1, pt)), dir: 1)
        }
        for i in 0..<4 {
            let tb = 1 - SceneMath.fract(t * 0.14 + Double(i) / 4 + 0.13)
            pulse(tb, amp: s.back * sm(0, 0.08, 1 - tb) * (1 - sm(0.9, 1, 1 - tb)), dir: -1)
        }
        if let story = storySprite {
            sprite(x: story.x, size: story.size, color: story.color, opacity: story.opacity)
        } else { order += 1 }
        if let receipt = receiptSprite {
            sprite(x: receipt.x, size: 0.16, color: p.core, opacity: receipt.opacity)
        } else { order += 1 }

        // Soft glows at both cup bases.
        sprite(x: -L, size: 0.55 + 0.35 * rA, color: p.core, opacity: 0.3 + 0.7 * rA)
        sprite(x: L, size: 0.55 + 0.35 * rB, color: p.core, opacity: 0.3 + 0.7 * rB)

        // Dust lives in the scene, not in the swaying world group.
        add(.dust, center: r.dust.boundsCenter)

        entries.sort { a, b in a.depth != b.depth ? a.depth > b.depth : a.order < b.order }

        // Encode.
        encoder.setFrontFacing(.counterClockwise)
        encoder.setVertexBytes(&frame, length: MemoryLayout<FrameUniforms>.stride, index: 1)
        encoder.setFragmentBytes(&frame, length: MemoryLayout<FrameUniforms>.stride, index: 1)
        encoder.setFragmentTexture(r.dotTexture, index: 0)
        encoder.setFragmentSamplerState(r.dotSampler, index: 0)

        // Glows add light in the dark theme and are painted normally in the light one.
        let glowSprites = p.additive ? r.spritePipelines.additive : r.spritePipelines.normal
        let glowPoints = p.additive ? r.pointsPipelines.additive : r.pointsPipelines.normal

        func setDraw(_ u: DrawUniforms) {
            var u = u
            encoder.setVertexBytes(&u, length: MemoryLayout<DrawUniforms>.stride, index: 2)
            encoder.setFragmentBytes(&u, length: MemoryLayout<DrawUniforms>.stride, index: 2)
        }

        for entry in entries {
            switch entry.item {
            case .cup(let model):
                encoder.setRenderPipelineState(r.litPipeline)
                encoder.setDepthStencilState(r.depthWrite)
                encoder.setCullMode(.none)
                encoder.setVertexBuffer(r.cup.vertices, offset: 0, index: 0)
                setDraw(DrawUniforms(model: model, normalMatrix: (view * model).normalMatrix,
                                     color: p.cup.linear(opacity: 1), params: SIMD4(0, 0.62, 0.08, 0)))
                encoder.drawIndexedPrimitives(type: .triangle, indexCount: r.cup.indexCount, indexType: .uint16,
                                              indexBuffer: r.cup.indices, indexBufferOffset: 0)
            case .solid(let mesh, let model, let color):
                encoder.setRenderPipelineState(r.basicPipeline)
                encoder.setDepthStencilState(r.depthWrite)
                encoder.setCullMode(.back)
                encoder.setVertexBuffer(mesh.vertices, offset: 0, index: 0)
                setDraw(DrawUniforms(model: model, color: color))
                encoder.drawIndexedPrimitives(type: .triangle, indexCount: mesh.indexCount, indexType: .uint16,
                                              indexBuffer: mesh.indices, indexBufferOffset: 0)
            case .string(let model, let color):
                // Depth from the real cylinder, colour from the antialiased ribbon.
                encoder.setRenderPipelineState(r.depthOnlyPipeline)
                encoder.setDepthStencilState(r.depthWrite)
                encoder.setCullMode(.back)
                encoder.setVertexBuffer(r.string.vertices, offset: 0, index: 0)
                setDraw(DrawUniforms(model: model, color: color, params: SIMD4(0.009, 0, 0, 0)))
                encoder.drawIndexedPrimitives(type: .triangle, indexCount: r.string.indexCount, indexType: .uint16,
                                              indexBuffer: r.string.indices, indexBufferOffset: 0)
                encoder.setRenderPipelineState(r.ribbonPipeline)
                encoder.setDepthStencilState(r.depthTestOnly)
                encoder.setCullMode(.none)
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            case .lines(let lines, let model, let color):
                encoder.setRenderPipelineState(r.linePipeline)
                encoder.setDepthStencilState(r.depthTestOnly)
                encoder.setCullMode(.none)
                encoder.setVertexBuffer(lines.positions, offset: 0, index: 0)
                setDraw(DrawUniforms(model: model, color: color))
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: lines.vertexCount / 2)
            case .glowLine(let model):
                encoder.setRenderPipelineState(glowPoints)
                encoder.setDepthStencilState(r.depthTestOnly)
                encoder.setCullMode(.none)
                encoder.setVertexBuffer(r.glowLine.centers, offset: 0, index: 0)
                setDraw(DrawUniforms(model: model, color: p.glow.linear(opacity: p.glowOpacity), params: SIMD4(0.24, 0, 0, 0)))
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: r.glowLine.count)
            case .sprite(let center, let size, let color):
                encoder.setRenderPipelineState(glowSprites)
                encoder.setDepthStencilState(r.depthTestOnly)
                encoder.setCullMode(.none)
                setDraw(DrawUniforms(model: .translation(center.x, center.y, center.z), color: color, params: SIMD4(size, 0, 0, 0)))
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            case .dust:
                encoder.setRenderPipelineState(r.pointsPipelines.normal)
                encoder.setDepthStencilState(r.depthTestOnly)
                encoder.setCullMode(.none)
                encoder.setVertexBuffer(r.dust.centers, offset: 0, index: 0)
                setDraw(DrawUniforms(model: matrix_identity_float4x4, color: p.dust.linear(opacity: p.dustOpacity),
                                     params: SIMD4(0.045, 0, 1, Float((dustT * 0.05).truncatingRemainder(dividingBy: 10)))))
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: r.dust.count)
            }
        }
    }
}
