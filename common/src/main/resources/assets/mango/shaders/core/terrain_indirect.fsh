#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
flat in float chunkVisibility;

out vec4 fragColor;

const float MANGO_TEXEL_CENTER_HALF = 0.5;
const float MANGO_RGSS_TRANSITION_START_SCALE = 1.0;
const float MANGO_RGSS_TRANSITION_END_SCALE = 2.0;
const int MANGO_RGSS_SAMPLE_COUNT = 4;
const float MANGO_RGSS_SAMPLE_WEIGHT = 0.25;
const vec2 MANGO_RGSS_OFFSETS[4] = vec2[4](
    vec2( 0.125,  0.375),
    vec2(-0.125, -0.375),
    vec2( 0.375, -0.125),
    vec2(-0.375,  0.125)
);

vec4 mangoSampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - MANGO_TEXEL_CENTER_HALF;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    texelOffset = (texelOffset - MANGO_TEXEL_CENTER_HALF) * pixelSize / texelScreenSize + MANGO_TEXEL_CENTER_HALF;
    texelOffset = clamp(texelOffset, 0.0, 1.0);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 mangoSampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return mangoSampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

vec4 mangoSampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * MANGO_RGSS_TRANSITION_START_SCALE;
    float transitionEnd = minPixelSize * MANGO_RGSS_TRANSITION_END_SCALE;
    if (maxTexelSize <= transitionStart) {
        return mangoSampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
    }

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < MANGO_RGSS_SAMPLE_COUNT; ++i) {
        vec2 sampleUV = uv + MANGO_RGSS_OFFSETS[i] * pixelSize;
        rgssColor += textureLod(source, sampleUV, mipLevelExact);
    }
    rgssColor *= MANGO_RGSS_SAMPLE_WEIGHT;

    if (maxTexelSize >= transitionEnd) {
        return rgssColor;
    }

    vec4 nearestColor = mangoSampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

void main() {
    vec2 pixelSize = 1.0 / vec2(textureSize(Sampler0, 0));
    vec4 color = (UseRgss == 1 ? mangoSampleRGSS(Sampler0, texCoord0, pixelSize) : mangoSampleNearest(Sampler0, texCoord0, pixelSize)) * vertexColor;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);

    fragColor = apply_fog(
        color,
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );
}
