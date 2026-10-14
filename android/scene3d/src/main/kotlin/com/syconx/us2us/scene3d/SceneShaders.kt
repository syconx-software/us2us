// us2us — welcome scene shaders (GLSL ES 3.00).
//
// A line-for-line port of the Metal shaders of the iOS app, themselves a port
// of the three.js r160 shading the design's scene uses (us2us-scene.js):
//
//   LIT      MeshStandardMaterial: GGX specular + Lambert diffuse for two
//            directional lights, hemisphere light as indirect diffuse,
//            DoubleSide normal flip, sRGB output transfer.
//   BASIC    MeshBasicMaterial: flat colour (rims; the string's depth).
//   LINE     LineBasicMaterial: 1 px lines, analytically antialiased.
//   RIBBON   The string's colour: its cylinder silhouette, antialiased.
//   SPRITE   THREE.Sprite: camera-facing quad of a world-space size.
//   POINTS   THREE.Points with size attenuation, drawn as instanced quads
//            (gl_PointSize = size * scale / -z, min 1 px).
//
// Like WebGL's default framebuffer, the render target is a non-sRGB format:
// every shader writes sRGB-encoded colour and blending happens on encoded
// values, exactly as in the browser.
//
// GLSL ES 3.00 has no `noperspective`; the varyings Metal interpolates with
// `center_no_perspective` are written multiplied by clip w and multiplied by
// gl_FragCoord.w (= 1 / w) in the fragment shader, which is the same
// screen-linear interpolation.

package com.syconx.us2us.scene3d

internal object SceneShaders {
    /** Binding point of the [FRAME] uniform block. */
    const val FRAME_BINDING = 0

    /** Size of the frame block in std140: two mat4 and eight vec4. */
    const val FRAME_BYTES = 2 * 64 + 8 * 16

    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
"""

    /** `FrameUniforms`, shared by every program (std140, binding [FRAME_BINDING]). */
    private const val FRAME = """
layout(std140) uniform Frame {
    mat4 uView;
    mat4 uProjection;
    vec4 uViewport;       // x, y: render target size (px); z: point scale (target height / 2)
    vec4 uKeyDirection;   // view space, towards the light
    vec4 uKeyColor;       // linear rgb × intensity
    vec4 uRimDirection;
    vec4 uRimColor;
    vec4 uHemiDirection;  // view-space "up" of the hemisphere light
    vec4 uSkyColor;
    vec4 uGroundColor;
};
"""

    /** `DrawUniforms`. */
    private const val DRAW = """
uniform mat4 uModel;
uniform mat4 uNormalMatrix;  // view-space normal matrix (upper 3×3), lit meshes only
uniform vec4 uColor;         // rgb + opacity: linear for lit / textured shaders, sRGB-encoded for basic
uniform vec4 uParams;        // x: size, y: roughness, z: metalness | dust wrap flag, w: dust drift
"""

    private const val QUAD = """
const vec2 kQuad[4] = vec2[4](vec2(-0.5, -0.5), vec2(0.5, -0.5), vec2(-0.5, 0.5), vec2(0.5, 0.5));
"""

    private const val SRGB = """
// three.js sRGBTransferOETF
vec3 linearToSrgb(vec3 c) {
    vec3 lo = c * 12.92;
    vec3 hi = pow(max(c, vec3(0.0)), vec3(0.41666)) * 1.055 - 0.055;
    return mix(hi, lo, lessThanEqual(c, vec3(0.0031308)));
}
"""

    private const val SCREEN = """
vec2 toPixels(vec4 clip, vec2 viewport) {
    return clip.xy / clip.w * 0.5 * viewport;
}

vec2 screenNormal(vec2 a, vec2 b) {
    vec2 d = b - a;
    float len = length(d);
    d = len > 1e-5 ? d / len : vec2(1.0, 0.0);
    return vec2(-d.y, d.x);
}
"""

    // Lit (MeshStandardMaterial)

    const val LIT_VERTEX = HEADER + FRAME + DRAW + """
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
out vec3 vViewPosition;
out vec3 vNormal;

void main() {
    vec4 mv = uView * (uModel * vec4(aPosition, 1.0));
    gl_Position = uProjection * mv;
    vViewPosition = mv.xyz;
    vNormal = normalize((uNormalMatrix * vec4(aNormal, 0.0)).xyz);
}
"""

    const val LIT_FRAGMENT = HEADER + FRAME + DRAW + SRGB + """
in vec3 vViewPosition;
in vec3 vNormal;
out vec4 fragColor;

const float kReciprocalPi = 0.3183098861837907;

vec3 F_Schlick(vec3 f0, float f90, float dotVH) {
    float fresnel = exp2((-5.55473 * dotVH - 6.98316) * dotVH);
    return f0 * (1.0 - fresnel) + f90 * fresnel;
}

float V_GGX_SmithCorrelated(float alpha, float dotNL, float dotNV) {
    float a2 = alpha * alpha;
    float gv = dotNL * sqrt(a2 + (1.0 - a2) * dotNV * dotNV);
    float gl = dotNV * sqrt(a2 + (1.0 - a2) * dotNL * dotNL);
    return 0.5 / max(gv + gl, 1e-6);
}

float D_GGX(float alpha, float dotNH) {
    float a2 = alpha * alpha;
    float denom = dotNH * dotNH * (a2 - 1.0) + 1.0;
    return kReciprocalPi * a2 / (denom * denom);
}

void directLight(vec3 lightDir, vec3 lightColor, vec3 normal, vec3 viewDir,
                 vec3 diffuseColor, vec3 specularColor, float roughness,
                 inout vec3 diffuse, inout vec3 specular) {
    float dotNL = clamp(dot(normal, lightDir), 0.0, 1.0);
    vec3 irradiance = dotNL * lightColor;
    float alpha = roughness * roughness;
    vec3 halfDir = normalize(lightDir + viewDir);
    float dotNV = clamp(dot(normal, viewDir), 0.0, 1.0);
    float dotNH = clamp(dot(normal, halfDir), 0.0, 1.0);
    float dotVH = clamp(dot(viewDir, halfDir), 0.0, 1.0);
    vec3 F = F_Schlick(specularColor, 1.0, dotVH);
    float V = V_GGX_SmithCorrelated(alpha, dotNL, dotNV);
    float D = D_GGX(alpha, dotNH);
    specular += irradiance * F * (V * D);
    diffuse += irradiance * kReciprocalPi * diffuseColor;
}

void main() {
    vec3 normal = normalize(vNormal) * (gl_FrontFacing ? 1.0 : -1.0);
    vec3 viewDir = normalize(-vViewPosition);

    vec3 albedo = uColor.rgb;
    float metalness = uParams.z;
    vec3 diffuseColor = albedo * (1.0 - metalness);
    vec3 dxy = max(abs(dFdx(normal)), abs(dFdy(normal)));
    float geometryRoughness = max(max(dxy.x, dxy.y), dxy.z);
    float roughness = min(max(uParams.y, 0.0525) + geometryRoughness, 1.0);
    vec3 specularColor = mix(vec3(0.04), albedo, metalness);

    vec3 diffuse = vec3(0.0);
    vec3 specular = vec3(0.0);
    directLight(uKeyDirection.xyz, uKeyColor.rgb, normal, viewDir,
                diffuseColor, specularColor, roughness, diffuse, specular);
    directLight(uRimDirection.xyz, uRimColor.rgb, normal, viewDir,
                diffuseColor, specularColor, roughness, diffuse, specular);

    float hemiWeight = 0.5 * dot(normal, uHemiDirection.xyz) + 0.5;
    vec3 irradiance = mix(uGroundColor.rgb, uSkyColor.rgb, hemiWeight);
    diffuse += irradiance * kReciprocalPi * diffuseColor;

    fragColor = vec4(linearToSrgb(diffuse + specular), uColor.a);
}
"""

    // Basic (MeshBasicMaterial)

    const val BASIC_VERTEX = HEADER + FRAME + DRAW + """
layout(location = 0) in vec3 aPosition;

void main() {
    gl_Position = uProjection * (uView * (uModel * vec4(aPosition, 1.0)));
}
"""

    const val BASIC_FRAGMENT = HEADER + DRAW + """
out vec4 fragColor;

void main() {
    fragColor = uColor;
}
"""

    // Lines (LineBasicMaterial)
    //
    // WebGL draws 1 px lines that the browser's MSAA smooths. GL ES line
    // primitives are aliased (and their width is not portable), so every
    // segment is drawn as a quad 2 px wide with analytic coverage: a 1 px
    // line seen through a 1 px pixel, i.e. a triangle filter 1 − |d| — the
    // same amount of "ink" per pixel.

    const val LINE_VERTEX = HEADER + FRAME + DRAW + SCREEN + """
layout(location = 0) in vec3 aStart;  // per segment
layout(location = 1) in vec3 aEnd;
out float vOffsetW;

void main() {
    mat4 mvp = uProjection * (uView * uModel);
    vec4 c0 = mvp * vec4(aStart, 1.0);
    vec4 c1 = mvp * vec4(aEnd, 1.0);
    vec2 viewport = uViewport.xy;
    vec2 n = screenNormal(toPixels(c0, viewport), toPixels(c1, viewport));
    float side = (gl_VertexID & 1) == 1 ? 1.0 : -1.0;
    vec4 c = gl_VertexID >= 2 ? c1 : c0;
    c.xy += n * side / (0.5 * viewport) * c.w;
    gl_Position = c;
    vOffsetW = side * c.w;
}
"""

    const val LINE_FRAGMENT = HEADER + DRAW + """
in float vOffsetW;
out vec4 fragColor;

void main() {
    float offset = vOffsetW * gl_FragCoord.w;
    float coverage = clamp(1.0 - abs(offset), 0.0, 1.0);
    fragColor = vec4(uColor.rgb, uColor.a * coverage);
}
"""

    // The string (a thin open cylinder, MeshBasicMaterial)
    //
    // Its depth comes from the real cylinder (depth-only pass, so packets
    // drawn later are hidden behind it exactly as in three.js); its colour is
    // this ribbon: the cylinder's silhouette with analytic edge coverage.

    const val RIBBON_VERTEX = HEADER + FRAME + DRAW + SCREEN + """
out float vOffsetW;
out float vHalfWidthW;

void main() {
    mat4 mv = uView * uModel;
    vec4 v0 = mv * vec4(-0.5, 0.0, 0.0, 1.0);
    vec4 v1 = mv * vec4(0.5, 0.0, 0.0, 1.0);
    vec4 c0 = uProjection * v0;
    vec4 c1 = uProjection * v1;
    vec2 viewport = uViewport.xy;
    vec2 n = screenNormal(toPixels(c0, viewport), toPixels(c1, viewport));
    bool end1 = gl_VertexID >= 2;
    float side = (gl_VertexID & 1) == 1 ? 1.0 : -1.0;
    float radius = uParams.x;
    float focal = uProjection[1][1] * 0.5 * viewport.y;
    vec4 v = end1 ? v1 : v0;
    vec4 c = end1 ? c1 : c0;
    float halfWidth = radius * focal / c.w;
    float extent = halfWidth + 1.0;
    c.xy += n * side * extent / (0.5 * viewport) * c.w;
    // Depth just in front of the tube (two radii towards the camera), so the
    // ribbon always passes over the cylinder's own depth but is still hidden
    // by anything really in front of the string (the cups).
    vec4 front = uProjection * vec4(v.xy, v.z + 2.0 * radius, 1.0);
    c.z = front.z / front.w * c.w;
    gl_Position = c;
    vOffsetW = side * extent * c.w;
    vHalfWidthW = halfWidth * c.w;
}
"""

    const val RIBBON_FRAGMENT = HEADER + DRAW + """
in float vOffsetW;
in float vHalfWidthW;
out vec4 fragColor;

void main() {
    float offset = vOffsetW * gl_FragCoord.w;
    float halfWidth = vHalfWidthW * gl_FragCoord.w;
    float coverage = clamp(halfWidth + 0.5 - abs(offset), 0.0, 1.0);
    fragColor = vec4(uColor.rgb, uColor.a * coverage);
}
"""

    // Sprites and points

    const val SPRITE_VERTEX = HEADER + FRAME + DRAW + QUAD + """
out vec2 vUv;

void main() {
    vec2 corner = kQuad[gl_VertexID];
    vec4 mv = uView * vec4(uModel[3].xyz, 1.0);
    mv.xy += corner * uParams.x;
    gl_Position = uProjection * mv;
    vUv = corner + 0.5;
}
"""

    const val POINTS_VERTEX = HEADER + FRAME + DRAW + QUAD + """
layout(location = 0) in vec3 aCenter;  // per point
out vec2 vUv;

void main() {
    vec3 c = aCenter;
    if (uParams.z > 0.5) {
        // Dust drifts upwards and wraps from y = 5 back to y = -5.
        c.y = mod(c.y + uParams.w + 5.0, 10.0) - 5.0;
    }
    vec4 mv = uView * (uModel * vec4(c, 1.0));
    vec4 clip = uProjection * mv;
    float sizePx = max(uParams.x * uViewport.z / -mv.z, 1.0);
    vec2 corner = kQuad[gl_VertexID];
    clip.xy += corner * (sizePx * 2.0) / uViewport.xy * clip.w;
    gl_Position = clip;
    vUv = corner + 0.5;
}
"""

    const val SPRITE_FRAGMENT = HEADER + DRAW + SRGB + """
uniform sampler2D uDot;
in vec2 vUv;
out vec4 fragColor;

void main() {
    vec4 t = texture(uDot, vUv);
    fragColor = vec4(linearToSrgb(uColor.rgb * t.rgb), uColor.a * t.a);
}
"""

    // Present: the resolved frame copied 1:1 into the window surface.

    const val COPY_VERTEX = HEADER + """
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 2.0 - 1.0;
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

    const val COPY_FRAGMENT = HEADER + """
uniform sampler2D uFrame;
out vec4 fragColor;

void main() {
    fragColor = texelFetch(uFrame, ivec2(gl_FragCoord.xy), 0);
}
"""
}
