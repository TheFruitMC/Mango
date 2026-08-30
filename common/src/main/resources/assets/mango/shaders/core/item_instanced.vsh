#version 330

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Normal;

in vec3 InstanceModelMat0;
in vec3 InstanceModelMat1;
in vec3 InstanceModelMat2;
in vec3 InstanceModelMat3;
in ivec2 InstanceUv2;
in ivec2 InstanceUv1;
in vec4 InstanceTint;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
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
    vec4 worldPos = instanceModelMat * vec4(Position, 1.0);
    worldPos.xyz += CameraOffset;

    gl_Position = ProjMat * ModelViewMat * worldPos;

    sphericalVertexDistance = fog_spherical_distance(worldPos.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(worldPos.xyz);

    vec3 normal = normalize(normal_transform(mat3(instanceModelMat)) * Normal.xyz);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, InstanceTint);
    lightMapColor = sample_lightmap(Sampler2, InstanceUv2);
    overlayColor = texelFetch(Sampler1, InstanceUv1, 0);

    texCoord0 = UV0;
}
