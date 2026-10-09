// us2us — welcome scene shaders (Metal Shading Language).
//
// A line-for-line port of the three.js r160 shading the design's scene uses
// (us2us-scene.js), so the native view matches the design pixel for pixel:
//
//   us2us_lit_*     MeshStandardMaterial: GGX specular + Lambert diffuse for
//                   two directional lights, hemisphere light as indirect
//                   diffuse, DoubleSide normal flip, sRGB output transfer.
//   us2us_basic_*   MeshBasicMaterial: flat colour (rims; the string's depth).
//   us2us_line_*    LineBasicMaterial: 1 px lines, analytically antialiased.
//   us2us_ribbon_*  The string's colour: its cylinder silhouette, antialiased.
//   us2us_sprite_*  THREE.Sprite: camera-facing quad of a world-space size.
//   us2us_points_*  THREE.Points with size attenuation, drawn as instanced
//                   quads (gl_PointSize = size * scale / -z, min 1 px).
//
// Like WebGL's default framebuffer, the render target is a non-sRGB format:
// every shader writes sRGB-encoded colour and blending happens on encoded
// values, exactly as in the browser.
//
// The source is compiled at runtime (MTLDevice.makeLibrary(source:)), so
// building the app needs no Metal toolchain; compilation is asynchronous and
// happens once per process (see SceneResources).

enum SceneShaders {
    static let source = """
    #include <metal_stdlib>
    using namespace metal;

    struct FrameUniforms {
        float4x4 view;
        float4x4 projection;
        float4 viewport;        // x, y: render target size (px); z: point scale (target height / 2)
        float4 keyDirection;    // view space, towards the light
        float4 keyColor;        // linear rgb × intensity
        float4 rimDirection;
        float4 rimColor;
        float4 hemiDirection;   // view-space "up" of the hemisphere light
        float4 skyColor;
        float4 groundColor;
    };

    struct DrawUniforms {
        float4x4 model;
        float4x4 normalMatrix;  // view-space normal matrix (upper 3×3), lit meshes only
        float4 color;           // rgb + opacity: linear for lit / textured shaders, sRGB-encoded for basic
        float4 params;          // x: size, y: roughness, z: metalness | dust wrap flag, w: dust drift
    };

    constant float kReciprocalPi = 0.3183098861837907;

    constant float2 kQuad[4] = {
        float2(-0.5, -0.5), float2(0.5, -0.5), float2(-0.5, 0.5), float2(0.5, 0.5)
    };

    // three.js sRGBTransferOETF
    static float3 linear_to_srgb(float3 c) {
        float3 lo = c * 12.92;
        float3 hi = pow(max(c, float3(0.0)), float3(0.41666)) * 1.055 - 0.055;
        return select(hi, lo, c <= float3(0.0031308));
    }

    // MARK: - Lit (MeshStandardMaterial)

    struct LitVertex {
        packed_float3 position;
        packed_float3 normal;
    };

    struct LitOut {
        float4 position [[position]];
        float3 viewPosition;
        float3 normal;
    };

    vertex LitOut us2us_lit_vertex(uint vid [[vertex_id]],
                                   const device LitVertex *vertices [[buffer(0)]],
                                   constant FrameUniforms &frame [[buffer(1)]],
                                   constant DrawUniforms &draw [[buffer(2)]]) {
        LitVertex v = vertices[vid];
        float4 mv = frame.view * (draw.model * float4(float3(v.position), 1.0));
        LitOut out;
        out.position = frame.projection * mv;
        out.viewPosition = mv.xyz;
        out.normal = normalize((draw.normalMatrix * float4(float3(v.normal), 0.0)).xyz);
        return out;
    }

    static float3 F_Schlick(float3 f0, float f90, float dotVH) {
        float fresnel = exp2((-5.55473 * dotVH - 6.98316) * dotVH);
        return f0 * (1.0 - fresnel) + f90 * fresnel;
    }

    static float V_GGX_SmithCorrelated(float alpha, float dotNL, float dotNV) {
        float a2 = alpha * alpha;
        float gv = dotNL * sqrt(a2 + (1.0 - a2) * dotNV * dotNV);
        float gl = dotNV * sqrt(a2 + (1.0 - a2) * dotNL * dotNL);
        return 0.5 / max(gv + gl, 1e-6);
    }

    static float D_GGX(float alpha, float dotNH) {
        float a2 = alpha * alpha;
        float denom = dotNH * dotNH * (a2 - 1.0) + 1.0;
        return kReciprocalPi * a2 / (denom * denom);
    }

    static void direct_light(float3 lightDir, float3 lightColor, float3 normal, float3 viewDir,
                             float3 diffuseColor, float3 specularColor, float roughness,
                             thread float3 &diffuse, thread float3 &specular) {
        float dotNL = saturate(dot(normal, lightDir));
        float3 irradiance = dotNL * lightColor;
        float alpha = roughness * roughness;
        float3 halfDir = normalize(lightDir + viewDir);
        float dotNV = saturate(dot(normal, viewDir));
        float dotNH = saturate(dot(normal, halfDir));
        float dotVH = saturate(dot(viewDir, halfDir));
        float3 F = F_Schlick(specularColor, 1.0, dotVH);
        float V = V_GGX_SmithCorrelated(alpha, dotNL, dotNV);
        float D = D_GGX(alpha, dotNH);
        specular += irradiance * F * (V * D);
        diffuse += irradiance * kReciprocalPi * diffuseColor;
    }

    fragment float4 us2us_lit_fragment(LitOut in [[stage_in]],
                                       bool frontFacing [[front_facing]],
                                       constant FrameUniforms &frame [[buffer(1)]],
                                       constant DrawUniforms &draw [[buffer(2)]]) {
        float3 normal = normalize(in.normal) * (frontFacing ? 1.0 : -1.0);
        float3 viewDir = normalize(-in.viewPosition);

        float3 albedo = draw.color.rgb;
        float metalness = draw.params.z;
        float3 diffuseColor = albedo * (1.0 - metalness);
        float3 dxy = max(abs(dfdx(normal)), abs(dfdy(normal)));
        float geometryRoughness = max(max(dxy.x, dxy.y), dxy.z);
        float roughness = min(max(draw.params.y, 0.0525) + geometryRoughness, 1.0);
        float3 specularColor = mix(float3(0.04), albedo, metalness);

        float3 diffuse = float3(0.0);
        float3 specular = float3(0.0);
        direct_light(frame.keyDirection.xyz, frame.keyColor.rgb, normal, viewDir,
                     diffuseColor, specularColor, roughness, diffuse, specular);
        direct_light(frame.rimDirection.xyz, frame.rimColor.rgb, normal, viewDir,
                     diffuseColor, specularColor, roughness, diffuse, specular);

        float hemiWeight = 0.5 * dot(normal, frame.hemiDirection.xyz) + 0.5;
        float3 irradiance = mix(frame.groundColor.rgb, frame.skyColor.rgb, hemiWeight);
        diffuse += irradiance * kReciprocalPi * diffuseColor;

        return float4(linear_to_srgb(diffuse + specular), draw.color.a);
    }

    // MARK: - Basic (MeshBasicMaterial)

    struct BasicOut {
        float4 position [[position]];
    };

    vertex BasicOut us2us_basic_vertex(uint vid [[vertex_id]],
                                       const device packed_float3 *positions [[buffer(0)]],
                                       constant FrameUniforms &frame [[buffer(1)]],
                                       constant DrawUniforms &draw [[buffer(2)]]) {
        BasicOut out;
        out.position = frame.projection * (frame.view * (draw.model * float4(float3(positions[vid]), 1.0)));
        return out;
    }

    fragment float4 us2us_basic_fragment(BasicOut in [[stage_in]],
                                         constant DrawUniforms &draw [[buffer(2)]]) {
        return draw.color;
    }

    // MARK: - Lines (LineBasicMaterial)
    //
    // WebGL draws 1 px lines that the browser's 4× MSAA smooths. Metal's
    // line primitive is aliased, so every segment is drawn as a quad 2 px
    // wide with analytic coverage: a 1 px line seen through a 1 px pixel,
    // i.e. a triangle filter 1 − |d| — the same amount of "ink" per pixel.

    struct LineOut {
        float4 position [[position]];
        float offset [[center_no_perspective]];
    };

    static float2 to_pixels(float4 clip, float2 viewport) {
        return clip.xy / clip.w * 0.5 * viewport;
    }

    static float2 screen_normal(float2 a, float2 b) {
        float2 d = b - a;
        float len = length(d);
        d = len > 1e-5 ? d / len : float2(1.0, 0.0);
        return float2(-d.y, d.x);
    }

    vertex LineOut us2us_line_vertex(uint vid [[vertex_id]],
                                     uint iid [[instance_id]],
                                     const device packed_float3 *positions [[buffer(0)]],
                                     constant FrameUniforms &frame [[buffer(1)]],
                                     constant DrawUniforms &draw [[buffer(2)]]) {
        float4x4 mvp = frame.projection * (frame.view * draw.model);
        float4 c0 = mvp * float4(float3(positions[iid * 2]), 1.0);
        float4 c1 = mvp * float4(float3(positions[iid * 2 + 1]), 1.0);
        float2 viewport = frame.viewport.xy;
        float2 n = screen_normal(to_pixels(c0, viewport), to_pixels(c1, viewport));
        float side = (vid & 1) ? 1.0 : -1.0;
        float4 c = vid >= 2 ? c1 : c0;
        c.xy += n * side / (0.5 * viewport) * c.w;
        LineOut out;
        out.position = c;
        out.offset = side;
        return out;
    }

    fragment float4 us2us_line_fragment(LineOut in [[stage_in]],
                                        constant DrawUniforms &draw [[buffer(2)]]) {
        float coverage = saturate(1.0 - abs(in.offset));
        return float4(draw.color.rgb, draw.color.a * coverage);
    }

    // MARK: - The string (a thin open cylinder, MeshBasicMaterial)
    //
    // Its depth comes from the real cylinder (depth-only pass, so packets
    // drawn later are hidden behind it exactly as in three.js); its colour is
    // this ribbon: the cylinder's silhouette with analytic edge coverage.

    struct RibbonOut {
        float4 position [[position]];
        float offset [[center_no_perspective]];
        float halfWidth [[center_no_perspective]];
    };

    vertex RibbonOut us2us_ribbon_vertex(uint vid [[vertex_id]],
                                         constant FrameUniforms &frame [[buffer(1)]],
                                         constant DrawUniforms &draw [[buffer(2)]]) {
        float4x4 mv = frame.view * draw.model;
        float4 v0 = mv * float4(-0.5, 0.0, 0.0, 1.0);
        float4 v1 = mv * float4(0.5, 0.0, 0.0, 1.0);
        float4 c0 = frame.projection * v0;
        float4 c1 = frame.projection * v1;
        float2 viewport = frame.viewport.xy;
        float2 n = screen_normal(to_pixels(c0, viewport), to_pixels(c1, viewport));
        bool end1 = vid >= 2;
        float side = (vid & 1) ? 1.0 : -1.0;
        float radius = draw.params.x;
        float focal = frame.projection[1][1] * 0.5 * viewport.y;
        float4 v = end1 ? v1 : v0;
        float4 c = end1 ? c1 : c0;
        float halfWidth = radius * focal / c.w;
        float extent = halfWidth + 1.0;
        c.xy += n * side * extent / (0.5 * viewport) * c.w;
        // Depth just in front of the tube (two radii towards the camera), so
        // the ribbon always passes over the cylinder's own depth but is still
        // hidden by anything really in front of the string (the cups).
        float4 front = frame.projection * float4(v.xy, v.z + 2.0 * radius, 1.0);
        c.z = front.z / front.w * c.w;
        RibbonOut out;
        out.position = c;
        out.offset = side * extent;
        out.halfWidth = halfWidth;
        return out;
    }

    fragment float4 us2us_ribbon_fragment(RibbonOut in [[stage_in]],
                                          constant DrawUniforms &draw [[buffer(2)]]) {
        float coverage = saturate(in.halfWidth + 0.5 - abs(in.offset));
        return float4(draw.color.rgb, draw.color.a * coverage);
    }

    // MARK: - Sprites and points

    struct SpriteOut {
        float4 position [[position]];
        float2 uv;
    };

    vertex SpriteOut us2us_sprite_vertex(uint vid [[vertex_id]],
                                         constant FrameUniforms &frame [[buffer(1)]],
                                         constant DrawUniforms &draw [[buffer(2)]]) {
        float2 corner = kQuad[vid];
        float4 mv = frame.view * float4(draw.model[3].xyz, 1.0);
        mv.xy += corner * draw.params.x;
        SpriteOut out;
        out.position = frame.projection * mv;
        out.uv = corner + 0.5;
        return out;
    }

    vertex SpriteOut us2us_points_vertex(uint vid [[vertex_id]],
                                         uint iid [[instance_id]],
                                         const device packed_float3 *centers [[buffer(0)]],
                                         constant FrameUniforms &frame [[buffer(1)]],
                                         constant DrawUniforms &draw [[buffer(2)]]) {
        float3 c = float3(centers[iid]);
        if (draw.params.z > 0.5) {
            // Dust drifts upwards and wraps from y = 5 back to y = -5.
            c.y = fmod(c.y + draw.params.w + 5.0, 10.0) - 5.0;
        }
        float4 mv = frame.view * (draw.model * float4(c, 1.0));
        float4 clip = frame.projection * mv;
        float sizePx = max(draw.params.x * frame.viewport.z / -mv.z, 1.0);
        float2 corner = kQuad[vid];
        clip.xy += corner * (sizePx * 2.0) / frame.viewport.xy * clip.w;
        SpriteOut out;
        out.position = clip;
        out.uv = corner + 0.5;
        return out;
    }

    fragment float4 us2us_sprite_fragment(SpriteOut in [[stage_in]],
                                          constant DrawUniforms &draw [[buffer(2)]],
                                          texture2d<float> dot [[texture(0)]],
                                          sampler dotSampler [[sampler(0)]]) {
        float4 t = dot.sample(dotSampler, in.uv);
        return float4(linear_to_srgb(draw.color.rgb * t.rgb), draw.color.a * t.a);
    }
    """
}
