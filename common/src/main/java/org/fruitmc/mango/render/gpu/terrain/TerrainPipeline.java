package org.fruitmc.mango.render.gpu.terrain;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.fruitmc.mango.render.chunk.vertex.CompactTerrainVertex;

public final class TerrainPipeline {

    public static final BindGroupLayout SECTION_TABLE_LAYOUT = BindGroupLayout.builder()
        .withUniform("MangoChunkSections", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_SINT)
        .build();

    private static final float CUTOUT_THRESHOLD = 0.5F;
    private static final float TRANSLUCENT_CUTOUT_THRESHOLD = 0.1F;
    private static volatile RenderPipeline solid;
    private static volatile RenderPipeline cutout;
    private static volatile RenderPipeline translucent;
    private static volatile RenderPipeline wireframe;
    private static volatile RenderPipeline translucentWireframe;

    public static RenderPipeline solid() {
        RenderPipeline local = solid;
        if (local == null) {
            synchronized (TerrainPipeline.class) {
                if (solid == null) {
                    solid = opaqueBuilder("solid").build();
                }
                local = solid;
            }
        }
        return local;
    }

    public static RenderPipeline cutout() {
        RenderPipeline local = cutout;
        if (local == null) {
            synchronized (TerrainPipeline.class) {
                if (cutout == null) {
                    cutout = opaqueBuilder("cutout")
                        .withShaderDefine("ALPHA_CUTOUT", CUTOUT_THRESHOLD)
                        .build();
                }
                local = cutout;
            }
        }
        return local;
    }

    public static RenderPipeline wireframe() {
        RenderPipeline local = wireframe;
        if (local == null) {
            synchronized (TerrainPipeline.class) {
                if (wireframe == null) {
                    wireframe = opaqueBuilder("wireframe")
                        .withPolygonMode(PolygonMode.WIREFRAME)
                        .build();
                }
                local = wireframe;
            }
        }
        return local;
    }

    public static RenderPipeline translucentWireframe() {
        RenderPipeline local = translucentWireframe;
        if (local == null) {
            synchronized (TerrainPipeline.class) {
                if (translucentWireframe == null) {
                    translucentWireframe = translucentBuilder("translucent_wireframe")
                        .withPolygonMode(PolygonMode.WIREFRAME)
                        .build();
                }
                local = translucentWireframe;
            }
        }
        return local;
    }

    public static RenderPipeline translucent() {
        RenderPipeline local = translucent;
        if (local == null) {
            synchronized (TerrainPipeline.class) {
                if (translucent == null) {
                    translucent = translucentBuilder("translucent")
                        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                        .withShaderDefine("ALPHA_CUTOUT", TRANSLUCENT_CUTOUT_THRESHOLD)
                        .build();
                }
                local = translucent;
            }
        }
        return local;
    }

    private static RenderPipeline.Builder opaqueBuilder(String variant) {
        return builder(variant, "terrain_indirect_compact", CompactTerrainVertex.FORMAT);
    }

    private static RenderPipeline.Builder translucentBuilder(String variant) {
        return builder(variant, "terrain_indirect", DefaultVertexFormat.BLOCK);
    }

    private static RenderPipeline.Builder builder(String variant, String vertexShader, VertexFormat vertexFormat) {
        return RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("mango", "pipeline/terrain_indirect_" + variant))
            .withVertexShader(Identifier.fromNamespaceAndPath("mango", "core/" + vertexShader))
            .withFragmentShader(Identifier.fromNamespaceAndPath("mango", "core/terrain_indirect"))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withBindGroupLayout(SECTION_TABLE_LAYOUT)
            .withVertexBinding(0, vertexFormat)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT);
    }

    private TerrainPipeline() {
    }
}
