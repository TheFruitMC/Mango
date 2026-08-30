package org.fruitmc.mango.render.gpu.item;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.fruitmc.mango.render.gpu.entity.InstancedEntityFormats;

import java.util.List;

public final class InstancedItemPipeline {

    private static final String CUTOUT_LOCATION = "pipeline/mango/item_instanced_cutout";
    private static final String ITEM_SHADER = "core/item_instanced";
    private static final String ALPHA_CUTOUT_DEFINE = "ALPHA_CUTOUT";
    private static final float ALPHA_CUTOUT_THRESHOLD = 0.1F;
    private static final int MESH_BINDING = 0;
    private static final int INSTANCE_BINDING = 1;

    private static volatile RenderPipeline cachedCutout;

    private InstancedItemPipeline() {
    }

    public static boolean isSupported(RenderPipeline source) {
        return source == RenderPipelines.ITEM_CUTOUT;
    }

    public static List<RenderPipeline> variants() {
        return List.of(get(RenderPipelines.ITEM_CUTOUT));
    }

    public static RenderPipeline get(RenderPipeline source) {
        if (!isSupported(source)) {
            throw new IllegalArgumentException("Unsupported item pipeline: " + source.getLocation());
        }

        RenderPipeline local = cachedCutout;
        if (local == null) {
            synchronized (InstancedItemPipeline.class) {
                if (cachedCutout == null) {
                    cachedCutout = buildCutout();
                }
                local = cachedCutout;
            }
        }
        return local;
    }

    private static RenderPipeline buildCutout() {
        return RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("mango", CUTOUT_LOCATION))
            .withVertexShader(Identifier.fromNamespaceAndPath("mango", ITEM_SHADER))
            .withFragmentShader(Identifier.fromNamespaceAndPath("mango", ITEM_SHADER))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.LIGHTING)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2)
            .withVertexBinding(MESH_BINDING, InstancedEntityFormats.MESH_VERTEX_FORMAT)
            .withVertexBinding(INSTANCE_BINDING, InstancedEntityFormats.INSTANCE_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withShaderDefine(ALPHA_CUTOUT_DEFINE, ALPHA_CUTOUT_THRESHOLD)
            .build();
    }
}
