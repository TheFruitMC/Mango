package org.fruitmc.mango.render.vulkan.compute;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.fruitmc.mango.render.vulkan.cache.MangoPipelineCache;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.fruitmc.mango.render.vulkan.MangoVulkanConstants.*;

public final class MangoComputePipeline implements Destroyable, AutoCloseable {

    private static final int SHADERC_COMPUTE_SHADER = Shaderc.shaderc_glsl_compute_shader;
    private static final int SHADERC_OPTIMIZATION_LEVEL_PERFORMANCE = Shaderc.shaderc_optimization_level_performance;
    private static final int SHADERC_TARGET_ENV_VULKAN = Shaderc.shaderc_target_env_vulkan;
    private static final int SHADERC_TARGET_VULKAN_1_2 = Shaderc.shaderc_env_version_vulkan_1_2;

    private final VulkanDevice device;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long shaderModule;
    private final long pipeline;
    private boolean closed;

    public MangoComputePipeline(
        VulkanDevice device,
        String label,
        Identifier shader,
        List<MangoComputeBinding.Type> layout
    ) throws IOException {
        this.device = device;
        long createdDescriptorSetLayout = MemoryUtil.NULL;
        long createdPipelineLayout = MemoryUtil.NULL;
        long createdShaderModule = MemoryUtil.NULL;
        long createdPipeline = MemoryUtil.NULL;
        try {
            createdDescriptorSetLayout = createDescriptorSetLayout(device, label, layout);
            createdPipelineLayout = createPipelineLayout(device, label, createdDescriptorSetLayout);
            createdShaderModule = compileShader(device, label, readShader(shader));
            createdPipeline = createPipeline(device, label, createdPipelineLayout, createdShaderModule);
        } catch (IOException | RuntimeException e) {
            destroyPartialPipeline(
                device,
                createdDescriptorSetLayout,
                createdPipelineLayout,
                createdShaderModule,
                createdPipeline
            );
            throw e;
        }
        this.descriptorSetLayout = createdDescriptorSetLayout;
        this.pipelineLayout = createdPipelineLayout;
        this.shaderModule = createdShaderModule;
        this.pipeline = createdPipeline;
    }

    public void dispatch(
        MangoVulkanCommandAccess encoder,
        List<MangoComputeBinding> bindings,
        int groupCountX,
        int groupCountY,
        int groupCountZ
    ) {
        if (this.closed) {
            throw new IllegalStateException("Compute pipeline is closed");
        }
        if (encoder.mango$getDevice() != this.device) {
            throw new IllegalArgumentException("Compute pipeline belongs to a different Vulkan device");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindings.size(), stack);
            for (int i = 0; i < bindings.size(); i++) {
                MangoComputeBinding binding = bindings.get(i);
                VkWriteDescriptorSet write = writes.get(i).sType$Default()
                    .dstBinding(binding.binding())
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(descriptorType(binding.type()));

                if (binding.type() == MangoComputeBinding.Type.UNIFORM_BUFFER
                    || binding.type() == MangoComputeBinding.Type.STORAGE_BUFFER) {
                    GpuBufferSlice buffer = binding.requireBuffer();
                    VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(((VulkanGpuBuffer)buffer.buffer()).vkBuffer())
                        .offset(buffer.offset())
                        .range(buffer.length());
                    write.pBufferInfo(bufferInfo);
                } else {
                    VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageView(((VulkanGpuTextureView)binding.requireTextureView()).vkImageView())
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    if (binding.type() == MangoComputeBinding.Type.SAMPLED_IMAGE) {
                        imageInfo.sampler(((VulkanGpuSampler)binding.requireSampler()).vkSampler());
                    }
                    write.pImageInfo(imageInfo);
                }
            }

            VK12.vkCmdBindPipeline(encoder.mango$getCommandBuffer(), VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(
                encoder.mango$getCommandBuffer(),
                VK_PIPELINE_BIND_POINT_COMPUTE,
                this.pipelineLayout,
                0,
                writes
            );
            VK12.vkCmdDispatch(encoder.mango$getCommandBuffer(), groupCountX, groupCountY, groupCountZ);
        }
    }

    public static void barrier(
        MangoVulkanCommandAccess encoder,
        long sourceStage,
        long sourceAccess,
        long destinationStage,
        long destinationAccess
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer memoryBarrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage)
                .dstAccessMask(destinationAccess);
            VkDependencyInfo dependencyInfo = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(memoryBarrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(encoder.mango$getCommandBuffer(), dependencyInfo);
        }
    }

    public static void barrierImage(
        MangoVulkanCommandAccess encoder,
        long sourceStage,
        long sourceAccess,
        long destinationStage,
        long destinationAccess,
        int sourceLayout,
        int destinationLayout,
        GpuTextureView textureView
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer imageBarrier = VkImageMemoryBarrier2.calloc(1, stack);
            writeImageBarrier(
                imageBarrier.get(0),
                sourceStage, sourceAccess,
                destinationStage, destinationAccess,
                sourceLayout, destinationLayout,
                textureView
            );
            VkDependencyInfo dependencyInfo = VkDependencyInfo.calloc(stack).sType$Default()
                .pImageMemoryBarriers(imageBarrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(encoder.mango$getCommandBuffer(), dependencyInfo);
        }
    }

    public static void barrierBuffer(
        MangoVulkanCommandAccess encoder,
        long sourceStage,
        long sourceAccess,
        long destinationStage,
        long destinationAccess,
        GpuBufferSlice bufferSlice
    ) {
        barrierBuffers(encoder, sourceStage, sourceAccess, destinationStage, destinationAccess, bufferSlice, null);
    }

    public static void barrierBuffers(
        MangoVulkanCommandAccess encoder,
        long sourceStage,
        long sourceAccess,
        long destinationStage,
        long destinationAccess,
        GpuBufferSlice primary,
        @Nullable GpuBufferSlice secondary
    ) {
        int barrierCount = secondary != null ? 2 : 1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier2.Buffer bufferBarriers = VkBufferMemoryBarrier2.calloc(barrierCount, stack);
            writeBufferBarrier(bufferBarriers.get(0), sourceStage, sourceAccess, destinationStage, destinationAccess, primary);
            if (secondary != null) {
                writeBufferBarrier(bufferBarriers.get(1), sourceStage, sourceAccess, destinationStage, destinationAccess, secondary);
            }
            VkDependencyInfo dependencyInfo = VkDependencyInfo.calloc(stack).sType$Default()
                .pBufferMemoryBarriers(bufferBarriers);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(encoder.mango$getCommandBuffer(), dependencyInfo);
        }
    }

    private static void writeBufferBarrier(
        VkBufferMemoryBarrier2 barrier,
        long sourceStage,
        long sourceAccess,
        long destinationStage,
        long destinationAccess,
        GpuBufferSlice slice
    ) {
        VulkanGpuBuffer vulkanBuffer = (VulkanGpuBuffer) slice.buffer();
        long range = slice.length();
        barrier.sType$Default()
            .srcStageMask(sourceStage)
            .srcAccessMask(sourceAccess)
            .dstStageMask(destinationStage)
            .dstAccessMask(destinationAccess)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .buffer(vulkanBuffer.vkBuffer())
            .offset(slice.offset())
            .size(range > 0L ? range : VK_WHOLE_SIZE);
    }

    public static BarrierBatch barrierBatch(MemoryStack stack, int maxImageBarriers, int maxBufferBarriers) {
        return new BarrierBatch(stack, maxImageBarriers, maxBufferBarriers);
    }

    public static final class BarrierBatch {
        private final VkImageMemoryBarrier2.Buffer imageBarriers;
        private final VkBufferMemoryBarrier2.Buffer bufferBarriers;
        private int imageCount;
        private int bufferCount;

        private BarrierBatch(MemoryStack stack, int maxImageBarriers, int maxBufferBarriers) {
            this.imageBarriers = maxImageBarriers > 0
                ? VkImageMemoryBarrier2.calloc(maxImageBarriers, stack)
                : null;
            this.bufferBarriers = maxBufferBarriers > 0
                ? VkBufferMemoryBarrier2.calloc(maxBufferBarriers, stack)
                : null;
            this.imageCount = 0;
            this.bufferCount = 0;
        }

        public BarrierBatch addImage(
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess,
            int sourceLayout,
            int destinationLayout,
            GpuTextureView textureView
        ) {
            if (this.imageBarriers == null || this.imageCount >= this.imageBarriers.capacity()) {
                throw new IllegalStateException("Image barrier capacity exceeded");
            }
            writeImageBarrier(
                this.imageBarriers.get(this.imageCount),
                sourceStage, sourceAccess,
                destinationStage, destinationAccess,
                sourceLayout, destinationLayout,
                textureView
            );
            this.imageCount++;
            return this;
        }

        public BarrierBatch addBuffer(
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess,
            GpuBufferSlice bufferSlice
        ) {
            if (this.bufferBarriers == null || this.bufferCount >= this.bufferBarriers.capacity()) {
                throw new IllegalStateException("Buffer barrier capacity exceeded");
            }
            writeBufferBarrier(
                this.bufferBarriers.get(this.bufferCount),
                sourceStage, sourceAccess,
                destinationStage, destinationAccess,
                bufferSlice
            );
            this.bufferCount++;
            return this;
        }

        public void submit(MangoVulkanCommandAccess encoder) {
            if (this.imageCount == 0 && this.bufferCount == 0) {
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDependencyInfo dependencyInfo = VkDependencyInfo.calloc(stack).sType$Default();
                if (this.imageCount > 0) {
                    this.imageBarriers.limit(this.imageCount);
                    dependencyInfo.pImageMemoryBarriers(this.imageBarriers);
                }
                if (this.bufferCount > 0) {
                    this.bufferBarriers.limit(this.bufferCount);
                    dependencyInfo.pBufferMemoryBarriers(this.bufferBarriers);
                }
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(encoder.mango$getCommandBuffer(), dependencyInfo);
            }
        }
    }

    private static void writeImageBarrier(
        VkImageMemoryBarrier2 barrier,
        long sourceStage,
        long sourceAccess,
        long destinationStage,
        long destinationAccess,
        int sourceLayout,
        int destinationLayout,
        GpuTextureView textureView
    ) {
        VulkanGpuTextureView vulkanView = (VulkanGpuTextureView) textureView;
        VulkanGpuTexture vulkanTexture = vulkanView.texture();
        int aspectMask = vulkanTexture.getFormat().hasColorAspect()
            ? VK_IMAGE_ASPECT_COLOR_BIT
            : VK_IMAGE_ASPECT_DEPTH_BIT;
        barrier.sType$Default()
            .srcStageMask(sourceStage)
            .srcAccessMask(sourceAccess)
            .dstStageMask(destinationStage)
            .dstAccessMask(destinationAccess)
            .oldLayout(sourceLayout)
            .newLayout(destinationLayout)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(vulkanTexture.vkImage());
        VkImageSubresourceRange subresourceRange = barrier.subresourceRange();
        subresourceRange
            .aspectMask(aspectMask)
            .baseMipLevel(vulkanView.baseMipLevel())
            .levelCount(vulkanView.mipLevels())
            .baseArrayLayer(0)
            .layerCount(vulkanTexture.getDepthOrLayers());
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.device.createCommandEncoder().queueForDestroy(this);
        }
    }

    @Override
    public void destroy() {
        VK12.vkDestroyPipeline(this.device.vkDevice(), this.pipeline, null);
        VK12.vkDestroyShaderModule(this.device.vkDevice(), this.shaderModule, null);
        VK12.vkDestroyPipelineLayout(this.device.vkDevice(), this.pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(this.device.vkDevice(), this.descriptorSetLayout, null);
    }

    private static String readShader(Identifier id) throws IOException {
        Resource resource = Minecraft.getInstance().getResourceManager().getResource(id)
            .orElseThrow(() -> new IOException("Missing compute shader resource: " + id));
        try (InputStream input = resource.open()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void destroyPartialPipeline(
        VulkanDevice device,
        long descriptorSetLayout,
        long pipelineLayout,
        long shaderModule,
        long pipeline
    ) {
        if (pipeline != MemoryUtil.NULL) {
            VK12.vkDestroyPipeline(device.vkDevice(), pipeline, null);
        }
        if (shaderModule != MemoryUtil.NULL) {
            VK12.vkDestroyShaderModule(device.vkDevice(), shaderModule, null);
        }
        if (pipelineLayout != MemoryUtil.NULL) {
            VK12.vkDestroyPipelineLayout(device.vkDevice(), pipelineLayout, null);
        }
        if (descriptorSetLayout != MemoryUtil.NULL) {
            VK12.vkDestroyDescriptorSetLayout(device.vkDevice(), descriptorSetLayout, null);
        }
    }

    private static long createDescriptorSetLayout(
        VulkanDevice device,
        String label,
        List<MangoComputeBinding.Type> layout
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(layout.size(), stack);
            for (int i = 0; i < layout.size(); i++) {
                bindings.get(i)
                    .binding(i)
                    .descriptorType(descriptorType(layout.get(i)))
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }

            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                .flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT)
                .pBindings(bindings);
            LongBuffer pointer = stack.callocLong(1);
            VulkanUtils.crashIfFailure(
                device,
                VK12.vkCreateDescriptorSetLayout(device.vkDevice(), info, null, pointer),
                "Failed to create compute descriptor layout " + label
            );
            return pointer.get(0);
        }
    }

    private static long createPipelineLayout(VulkanDevice device, String label, long descriptorSetLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pointer = stack.callocLong(1);
            VulkanUtils.crashIfFailure(
                device,
                VK12.vkCreatePipelineLayout(device.vkDevice(), info, null, pointer),
                "Failed to create compute pipeline layout " + label
            );
            return pointer.get(0);
        }
    }

    private static long compileShader(VulkanDevice device, String label, String source) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == MemoryUtil.NULL || options == MemoryUtil.NULL) {
            throw new IllegalStateException("Failed to initialize Shaderc for " + label);
        }

        ByteBuffer sourceBuffer = MemoryUtil.memUTF8(source, false);
        ByteBuffer filenameBuffer = MemoryUtil.memUTF8(label);
        ByteBuffer entrypointBuffer = MemoryUtil.memUTF8("main");
        long result = MemoryUtil.NULL;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, SHADERC_TARGET_ENV_VULKAN, SHADERC_TARGET_VULKAN_1_2);
            Shaderc.shaderc_compile_options_set_optimization_level(options, SHADERC_OPTIMIZATION_LEVEL_PERFORMANCE);
            result = Shaderc.shaderc_compile_into_spv(
                compiler,
                sourceBuffer,
                SHADERC_COMPUTE_SHADER,
                filenameBuffer,
                entrypointBuffer,
                options
            );
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != 0) {
                throw new IllegalStateException("Failed to compile compute shader " + label + ": " + Shaderc.shaderc_result_get_error_message(result));
            }

            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
                LongBuffer pointer = stack.callocLong(1);
                VulkanUtils.crashIfFailure(
                    device,
                    VK12.vkCreateShaderModule(device.vkDevice(), info, null, pointer),
                    "Failed to create compute shader module " + label
                );
                return pointer.get(0);
            }
        } finally {
            if (result != MemoryUtil.NULL) {
                Shaderc.shaderc_result_release(result);
            }
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
            MemoryUtil.memFree(entrypointBuffer);
            MemoryUtil.memFree(filenameBuffer);
            MemoryUtil.memFree(sourceBuffer);
        }
    }

    private static long createPipeline(VulkanDevice device, String label, long pipelineLayout, long shaderModule) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(shaderModule)
                .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                .stage(stage)
                .layout(pipelineLayout);
            LongBuffer pointer = stack.callocLong(1);
            VulkanUtils.crashIfFailure(
                device,
                VK12.vkCreateComputePipelines(device.vkDevice(), MangoPipelineCache.get().handle(device), info, null, pointer),
                "Failed to create compute pipeline " + label
            );
            return pointer.get(0);
        }
    }

    private static int descriptorType(MangoComputeBinding.Type type) {
        return switch (type) {
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case SAMPLED_IMAGE -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case STORAGE_IMAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        };
    }
}
