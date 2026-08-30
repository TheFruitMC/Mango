package org.fruitmc.mango.render.gpu.particle;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.List;

public final class InstancedParticlePipeline {

    private static final String OPAQUE_LOCATION = "pipeline/mango/particle_instanced_opaque";
    private static final String TRANSLUCENT_LOCATION = "pipeline/mango/particle_instanced_translucent";
    private static final String PARTICLE_SHADER = "core/particle_instanced";
    private static final String ALPHA_CUTOUT_DEFINE = "ALPHA_CUTOUT";
    private static final float ALPHA_CUTOUT_THRESHOLD = 0.1F;
    private static final int MESH_BINDING = 0;
    private static final int INSTANCE_BINDING = 1;

    private static volatile RenderPipeline cachedOpaque;
    private static volatile RenderPipeline cachedTranslucent;

    private InstancedParticlePipeline() {
    }

    public static boolean isSupported(RenderPipeline source) {
        return source == RenderPipelines.OPAQUE_PARTICLE
            || source == RenderPipelines.TRANSLUCENT_PARTICLE;
    }

    public static List<RenderPipeline> variants() {
        return List.of(get(RenderPipelines.OPAQUE_PARTICLE), get(RenderPipelines.TRANSLUCENT_PARTICLE));
    }

    public static RenderPipeline get(RenderPipeline source) {
        if (!isSupported(source)) {
            throw new IllegalArgumentException("Unsupported particle pipeline: " + source.getLocation());
        }

        if (source == RenderPipelines.TRANSLUCENT_PARTICLE) {
            RenderPipeline local = cachedTranslucent;
            if (local == null) {
                synchronized (InstancedParticlePipeline.class) {
                    if (cachedTranslucent == null) {
                        cachedTranslucent = build(TRANSLUCENT_LOCATION, true);
                    }
                    local = cachedTranslucent;
                }
            }
            return local;
        }

        RenderPipeline local = cachedOpaque;
        if (local == null) {
            synchronized (InstancedParticlePipeline.class) {
                if (cachedOpaque == null) {
                    cachedOpaque = build(OPAQUE_LOCATION, false);
                }
                local = cachedOpaque;
            }
        }
        return local;
    }

    private static RenderPipeline build(String location, boolean translucent) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("mango", location))
            .withVertexShader(Identifier.fromNamespaceAndPath("mango", PARTICLE_SHADER))
            .withFragmentShader(Identifier.fromNamespaceAndPath("mango", PARTICLE_SHADER))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withVertexBinding(MESH_BINDING, InstancedParticleFormats.MESH_VERTEX_FORMAT)
            .withVertexBinding(INSTANCE_BINDING, InstancedParticleFormats.INSTANCE_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withShaderDefine(ALPHA_CUTOUT_DEFINE, ALPHA_CUTOUT_THRESHOLD);

        if (translucent) {
            builder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
        }
        return builder.build();
    }
}
