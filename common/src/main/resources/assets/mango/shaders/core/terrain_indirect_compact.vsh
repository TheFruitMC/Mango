#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in uvec3 Position;
in uvec2 UV0;
in uvec2 UV2;
in vec4 Color;

uniform isamplerBuffer MangoChunkSections;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
flat out float chunkVisibility;

const float MANGO_POSITION_MIN = -8.0;
const float MANGO_POSITION_SCALE = 1.0 / 2048.0;
const uint MANGO_TEXTURE_VALUE_BITS = 15u;
const uint MANGO_TEXTURE_SCALE = 1u << MANGO_TEXTURE_VALUE_BITS;
const uint MANGO_TEXTURE_VALUE_MASK = MANGO_TEXTURE_SCALE - 1u;

vec2 mangoDecodeTexture(uvec2 encoded) {
    vec2 value = vec2(encoded & MANGO_TEXTURE_VALUE_MASK) / float(MANGO_TEXTURE_SCALE);
    bvec2 upperSide = bvec2(encoded >> MANGO_TEXTURE_VALUE_BITS);
    vec2 correction = mix(vec2(-1.0), vec2(1.0), upperSide) / float(MANGO_TEXTURE_SCALE);
    return value + correction;
}

void main() {
    ivec4 section = texelFetch(MangoChunkSections, gl_InstanceID);
    vec3 localPosition = vec3(Position) * MANGO_POSITION_SCALE + MANGO_POSITION_MIN;
    vec3 pos = localPosition + (section.xyz - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * sample_lightmap(Sampler2, ivec2(UV2));
    texCoord0 = mangoDecodeTexture(UV0);
    chunkVisibility = intBitsToFloat(section.w);
}
