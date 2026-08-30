package org.fruitmc.mango.render.gpu.pipeline;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.fruitmc.mango.render.gpu.entity.InstancedEntityPipeline;
import org.fruitmc.mango.render.gpu.item.InstancedItemPipeline;
import org.fruitmc.mango.render.gpu.particle.InstancedParticlePipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class MangoPipelinePrecompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MangoPipelinePrecompiler.class);

    private MangoPipelinePrecompiler() {
    }

    public static void precompileAll(ShaderSource shaderSource) {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }

        List<RenderPipeline> pipelines = collectPipelines();
        int failed = 0;
        for (RenderPipeline pipeline : pipelines) {
            CompiledRenderPipeline compiled = device.precompilePipeline(pipeline, shaderSource);
            if (!compiled.isValid()) {
                failed++;
                LOGGER.warn("Failed to precompile Mango pipeline {}; it will be retried on first use", pipeline.getLocation());
            }
        }
        LOGGER.debug("Precompiled {} of {} Mango pipelines", pipelines.size() - failed, pipelines.size());
    }

    private static List<RenderPipeline> collectPipelines() {
        List<RenderPipeline> pipelines = new ArrayList<>(InstancedEntityPipeline.variants());
        pipelines.addAll(InstancedItemPipeline.variants());
        pipelines.addAll(InstancedParticlePipeline.variants());
        return pipelines;
    }
}
