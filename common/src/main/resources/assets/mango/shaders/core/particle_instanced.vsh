#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec2 Corner;

in vec3 InstancePosition;
in vec4 InstanceRotation;
in float InstanceScale;
in vec4 InstanceUv;
in vec4 InstanceColor;
in ivec2 InstanceUv2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;

mat3 quaternion_matrix(vec4 q) {
    float xx = q.x * q.x;
    float yy = q.y * q.y;
    float zz = q.z * q.z;
    float ww = q.w * q.w;
    float k = 1.0 / (xx + yy + zz + ww);
    float xy = q.x * q.y;
    float xz = q.x * q.z;
    float yz = q.y * q.z;
    float xw = q.x * q.w;
    float yw = q.y * q.w;
    float zw = q.z * q.w;
    return mat3(
        vec3((xx - yy - zz + ww) * k, 2.0 * (xy + zw) * k, 2.0 * (xz - yw) * k),
        vec3(2.0 * (xy - zw) * k, (yy - xx - zz + ww) * k, 2.0 * (yz + xw) * k),
        vec3(2.0 * (xz + yw) * k, 2.0 * (yz - xw) * k, (zz - xx - yy + ww) * k)
    );
}

void main() {
    vec3 offset = quaternion_matrix(InstanceRotation) * vec3(Corner, 0.0) * InstanceScale;
    vec3 worldPos = InstancePosition + offset;

    gl_Position = ProjMat * ModelViewMat * vec4(worldPos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(worldPos);
    cylindricalVertexDistance = fog_cylindrical_distance(worldPos);

    texCoord0 = vec2(
        Corner.x > 0.0 ? InstanceUv.y : InstanceUv.x,
        Corner.y > 0.0 ? InstanceUv.z : InstanceUv.w
    );

    vertexColor = InstanceColor * sample_lightmap(Sampler2, InstanceUv2);
}
