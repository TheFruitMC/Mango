package org.fruitmc.mango.render.gpu.particle;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

public final class InstancedParticleFormats {

    public static final VertexFormat MESH_VERTEX_FORMAT = VertexFormat.builder(0)
        .addAttribute("Corner", GpuFormat.RG32_FLOAT)
        .build();

    public static final VertexFormat INSTANCE_FORMAT = VertexFormat.builder(1)
        .addAttribute("InstancePosition", GpuFormat.RGB32_FLOAT)
        .addAttribute("InstanceRotation", GpuFormat.RGBA32_FLOAT)
        .addAttribute("InstanceScale", GpuFormat.R32_FLOAT)
        .addAttribute("InstanceUv", GpuFormat.RGBA32_FLOAT)
        .addAttribute("InstanceColor", GpuFormat.RGBA8_UNORM)
        .addAttribute("InstanceUv2", GpuFormat.RG16_SINT)
        .build();

    private InstancedParticleFormats() {
    }
}
