#version 330

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

const uint BONE_TEXELS_PER_MATRIX = 4u;

in vec3 Position;
in vec2 UV0;
in vec4 Normal;
in uint BoneIndex;

in vec3 InstanceModelMat0;
in vec3 InstanceModelMat1;
in vec3 InstanceModelMat2;
in vec3 InstanceModelMat3;
in ivec2 InstanceUv2;
in ivec2 InstanceUv1;
in vec4 InstanceTint;
in uint InstanceBonePaletteOffset;

uniform samplerBuffer BonePalette;

#ifndef NO_OVERLAY
uniform sampler2D Sampler1;
#endif
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
#else
out vec4 vertexColor;
#endif
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

mat3 normal_transform(mat3 transform) {
    vec3 cofactor0 = cross(transform[1], transform[2]);
    vec3 cofactor1 = cross(transform[2], transform[0]);
    vec3 cofactor2 = cross(transform[0], transform[1]);
    float orientation = sign(dot(transform[0], cofactor0));
    return mat3(cofactor0, cofactor1, cofactor2) * orientation;
}

void main() {
    mat4 instanceModelMat = mat4(
        vec4(InstanceModelMat0, 0.0),
        vec4(InstanceModelMat1, 0.0),
        vec4(InstanceModelMat2, 0.0),
        vec4(InstanceModelMat3, 1.0)
    );
    int firstBoneTexel = int((InstanceBonePaletteOffset + BoneIndex) * BONE_TEXELS_PER_MATRIX);
    vec4 col0 = texelFetch(BonePalette, firstBoneTexel);
    vec4 col1 = texelFetch(BonePalette, firstBoneTexel + 1);
    vec4 col2 = texelFetch(BonePalette, firstBoneTexel + 2);
    vec4 col3 = texelFetch(BonePalette, firstBoneTexel + 3);

    mat4 boneMatrix = mat4(col0, col1, col2, col3);

    vec4 modelPos = boneMatrix * vec4(Position, 1.0);
    vec4 worldPos = instanceModelMat * modelPos;
    worldPos.xyz += CameraOffset;

    gl_Position = ProjMat * ModelViewMat * worldPos;

    sphericalVertexDistance = fog_spherical_distance(worldPos.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(worldPos.xyz);

    mat3 modelTransform = mat3(instanceModelMat) * mat3(boneMatrix);
    vec3 normal = normalize(normal_transform(modelTransform) * Normal.xyz);
#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, InstanceTint);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, InstanceTint);
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, InstanceTint);
#endif

    lightMapColor = sample_lightmap(Sampler2, InstanceUv2);
#ifdef NO_OVERLAY
    overlayColor = vec4(0.0);
#else
    overlayColor = texelFetch(Sampler1, InstanceUv1, 0);
#endif

    texCoord0 = UV0;
}
