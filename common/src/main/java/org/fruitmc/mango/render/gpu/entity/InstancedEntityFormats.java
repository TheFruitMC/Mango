package org.fruitmc.mango.render.gpu.entity;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;

public final class InstancedEntityFormats {

    public static final VertexFormat MESH_VERTEX_FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
        .build();

    public static final VertexFormat INSTANCE_FORMAT = VertexFormat.builder(1)
        .addAttribute("InstanceModelMat0", GpuFormat.RGB32_FLOAT)
        .addAttribute("InstanceModelMat1", GpuFormat.RGB32_FLOAT)
        .addAttribute("InstanceModelMat2", GpuFormat.RGB32_FLOAT)
        .addAttribute("InstanceModelMat3", GpuFormat.RGB32_FLOAT)
        .addAttribute("InstanceUv2", GpuFormat.RG16_SINT)
        .addAttribute("InstanceUv1", GpuFormat.RG16_SINT)
        .addAttribute("InstanceTint", GpuFormat.RGBA8_UNORM)
        .build();

    public static final VertexFormat SKINNED_MESH_VERTEX_FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
        .addAttribute("BoneIndex", GpuFormat.R32_UINT)
        .build();

    public static final VertexFormat SKINNED_INSTANCE_FORMAT = VertexFormat.builder(1)
        .addAttribute("InstanceModelMat0", GpuFormat.RGB32_FLOAT)
        .addAttribute("InstanceModelMat1", GpuFormat.RGB32_FLOAT)
        .addAttribute("InstanceModelMat2", GpuFormat.RGB32_FLOAT)
        .addAttribute("InstanceModelMat3", GpuFormat.RGB32_FLOAT)
        .addAttribute("InstanceUv2", GpuFormat.RG16_SINT)
        .addAttribute("InstanceUv1", GpuFormat.RG16_SINT)
        .addAttribute("InstanceTint", GpuFormat.RGBA8_UNORM)
        .addAttribute("InstanceBonePaletteOffset", GpuFormat.R32_UINT)
        .build();

    public static final BindGroupLayout BONE_PALETTE_LAYOUT = BindGroupLayout.builder()
        .withUniform("BonePalette", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
        .build();

    private InstancedEntityFormats() {
    }
}
