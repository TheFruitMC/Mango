package org.fruitmc.mango.mixin.vulkan.pipeline;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.*;
import net.minecraft.SharedConstants;
import org.fruitmc.mango.render.vulkan.MangoIndirectRenderPass;
import org.fruitmc.mango.render.vulkan.TextureViewAndSamplerAccess;
import org.fruitmc.mango.render.vulkan.TextureViewAndSamplerHolder;
import org.fruitmc.mango.render.vulkan.pipeline.MangoTexelViewKey;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.LongBuffer;
import java.util.*;

import static org.fruitmc.mango.render.vulkan.MangoVulkanConstants.*;

@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassMixin implements MangoIndirectRenderPass {

    @Shadow
    @Final
    private VulkanDevice device;

    @Shadow
    @Final
    private VulkanCommandEncoder encoder;

    @Shadow
    @Final
    private VkCommandBuffer commandBuffer;

    @Shadow
    @Nullable
    protected VulkanRenderPipeline pipeline;

    @Shadow
    @Final
    protected HashMap<String, GpuBufferSlice> uniforms;

    @Shadow
    @Final
    protected HashMap<String, TextureViewAndSamplerAccess> textures;

    @Shadow
    private boolean anyDescriptorDirty;

    private @Nullable RenderPipeline mango$lastPipelineInfo;
    private final Map<String, GpuBufferSlice> mango$lastUniforms = new HashMap<>();
    private final Map<String, TextureViewAndSamplerHolder> mango$lastTextures = new HashMap<>();

    private final GpuBufferSlice[] mango$lastVertexBuffers = new GpuBufferSlice[RenderPass.MAX_VERTEX_BUFFERS];
    private @Nullable GpuBuffer mango$lastIndexBuffer;
    private @Nullable IndexType mango$lastIndexType;

    private final Set<String> mango$dirtyUniforms = new HashSet<>();
    private final Set<String> mango$dirtyTextures = new HashSet<>();
    private boolean mango$pipelineChanged = true;

    // Texel views are pass-local. The encoder's two-submit destruction queue keeps them alive until
    // this render pass has finished submitting, so no ownership tracking is needed here.
    private final Map<MangoTexelViewKey, Long> mango$texelViewCache = new HashMap<>();

    @Inject(
            method = "setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void mango$onSetPipelineHead(RenderPipeline pipeline, CallbackInfo ci) {
        if (pipeline == this.mango$lastPipelineInfo) {
            ci.cancel();
        }
    }

    @Inject(
            method = "setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void mango$onSetPipelineReturn(RenderPipeline pipeline, CallbackInfo ci) {
        this.mango$lastPipelineInfo = pipeline;
        this.mango$pipelineChanged = true;
    }

    @Inject(
            method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void mango$onSetUniformBufferHead(String name, GpuBuffer value, CallbackInfo ci) {
        if (mango$uniformEquals(name, value, 0L, value.size())) {
            ci.cancel();
            return;
        }

        this.mango$lastUniforms.put(name, value.slice());
        this.mango$dirtyUniforms.add(name);
    }

    @Inject(
            method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void mango$onSetUniformSliceHead(String name, GpuBufferSlice value, CallbackInfo ci) {
        if (mango$uniformEquals(name, value.buffer(), value.offset(), value.length())) {
            ci.cancel();
            return;
        }

        this.mango$lastUniforms.put(name, value);
        this.mango$dirtyUniforms.add(name);
    }

    @Inject(
            method = "bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void mango$onBindTextureHead(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler, CallbackInfo ci) {
        if (textureView == null && sampler == null) {
            if (!this.mango$lastTextures.containsKey(name)) {
                ci.cancel();
            } else {
                this.mango$lastTextures.remove(name);
            }
            return;
        }

        if (textureView == null || sampler == null) {
            return;
        }

        TextureViewAndSamplerHolder previous = this.mango$lastTextures.get(name);
        if (previous != null
            && previous.view() == textureView
            && previous.sampler() == sampler) {
            ci.cancel();
            return;
        }

        this.mango$lastTextures.put(name, new TextureViewAndSamplerHolder(textureView, sampler));
        this.mango$dirtyTextures.add(name);
    }

    @Inject(
            method = "setVertexBuffer(ILcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void mango$onSetVertexBufferHead(int slot, @Nullable GpuBufferSlice vertexBuffer, CallbackInfo ci) {
        if (slot < 0 || slot >= mango$lastVertexBuffers.length) {
            return;
        }

        GpuBufferSlice cached = mango$lastVertexBuffers[slot];
        if (mango$vertexBufferEquals(cached, vertexBuffer)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "setVertexBuffer(ILcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void mango$onSetVertexBufferReturn(int slot, @Nullable GpuBufferSlice vertexBuffer, CallbackInfo ci) {
        if (slot < 0 || slot >= mango$lastVertexBuffers.length) {
            return;
        }

        mango$lastVertexBuffers[slot] = vertexBuffer;
    }

    @Inject(
            method = "setIndexBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void mango$onSetIndexBufferHead(GpuBuffer indexBuffer, IndexType indexType, CallbackInfo ci) {
        if (indexBuffer == mango$lastIndexBuffer && indexType == mango$lastIndexType) {
            ci.cancel();
        }
    }

    @Inject(
            method = "setIndexBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void mango$onSetIndexBufferReturn(GpuBuffer indexBuffer, IndexType indexType, CallbackInfo ci) {
        mango$lastIndexBuffer = indexBuffer;
        mango$lastIndexType = indexType;
    }

    @Overwrite
    private void pushDescriptors() {
        if (!this.mango$pipelineChanged && this.mango$dirtyUniforms.isEmpty() && this.mango$dirtyTextures.isEmpty()) {
            return;
        }

        mango$pushDescriptorsBody();
    }

    @Unique
    private void mango$pushDescriptorsBody() {
        if (SharedConstants.IS_RUNNING_IN_IDE) {
            for (BindGroupLayout.UniformDescription uniform : BindGroupLayout.flattenUniforms(this.pipeline.info().getBindGroupLayouts())) {
                GpuBufferSlice value = this.uniforms.get(uniform.name());
                if (value == null) {
                    throw new IllegalStateException("Missing uniform " + uniform.name() + " (should be " + uniform.type() + ")");
                }

                if (uniform.type() == UniformType.UNIFORM_BUFFER) {
                    if (value.buffer().isClosed()) {
                        throw new IllegalStateException("Uniform buffer " + uniform.name() + " is already closed");
                    }

                    if ((value.buffer().usage() & GpuBuffer.USAGE_UNIFORM) == 0) {
                        throw new IllegalStateException("Uniform buffer " + uniform.name() + " must have GpuBuffer.USAGE_UNIFORM");
                    }
                }

                if (uniform.type() == UniformType.TEXEL_BUFFER) {
                    if (value.offset() != 0L || value.length() != value.buffer().size()) {
                        throw new IllegalStateException("Uniform texel buffers do not support a slice of a buffer, must be entire buffer");
                    }

                    if ((value.buffer().usage() & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) == 0) {
                        throw new IllegalStateException("Uniform texel buffer " + uniform.name() + " must have GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER");
                    }

                    if (uniform.gpuFormat() == null) {
                        throw new IllegalStateException("Invalid uniform texel buffer " + uniform.name() + " (missing a texture format)");
                    }
                }
            }
        }

        assert this.pipeline != null;
        VulkanBindGroupLayout layout = this.pipeline.layout();
        List<VulkanBindGroupLayout.Entry> entries = layout.entries();
        boolean pushAll = this.mango$pipelineChanged;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int pushCount = 0;
            for (VulkanBindGroupLayout.Entry entry : entries) {
                if (pushAll || mango$isEntryDirty(entry)) {
                    pushCount++;
                }
            }

            if (pushCount == 0) {
                mango$clearDirty();
                return;
            }

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(pushCount, stack);

            for (int i = 0; i < entries.size(); i++) {
                VulkanBindGroupLayout.Entry entry = entries.get(i);
                if (!pushAll && !mango$isEntryDirty(entry)) {
                    continue;
                }

                VkWriteDescriptorSet set = writes.get();
                set.sType$Default();
                set.dstBinding(i);
                set.dstArrayElement(0);
                set.descriptorCount(1);

                if (entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER) {
                    GpuBufferSlice buffer = this.uniforms.get(entry.name());
                    if (buffer == null) {
                        throw new IllegalStateException("Missing uniform " + entry.name() + " (should be " + entry.type() + ")");
                    }

                    VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                    bufferInfo.buffer(((VulkanGpuBuffer) buffer.buffer()).vkBuffer());
                    bufferInfo.offset(buffer.offset());
                    bufferInfo.range(buffer.length());
                    set.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                    set.pBufferInfo(bufferInfo);
                } else if (entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE) {
                    TextureViewAndSamplerAccess holder = this.textures.get(entry.name());
                    if (holder == null) {
                        throw new IllegalStateException("Missing sampler " + entry.name());
                    }

                    VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                    imageInfo.sampler(((VulkanGpuSampler)holder.mango$sampler()).vkSampler());
                    imageInfo.imageView(((VulkanGpuTextureView)holder.mango$view()).vkImageView());
                    imageInfo.imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    set.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                    set.pImageInfo(imageInfo);
                } else if (entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER) {
                    GpuBufferSlice value = this.uniforms.get(entry.name());
                    if (value == null) {
                        throw new IllegalStateException("Missing uniform " + entry.name() + " (should be " + entry.type() + ")");
                    }

                    LongBuffer bufferViewPtr = stack.callocLong(1);
                    int vkFormat = VulkanConst.toVk(entry.texelBufferFormat());
                    MangoTexelViewKey cacheKey = new MangoTexelViewKey(
                        value.buffer(), value.offset(), value.length(), vkFormat
                    );
                    Long cachedView = mango$texelViewCache.get(cacheKey);
                    if (cachedView != null && !value.buffer().isClosed()) {
                        bufferViewPtr.put(0, cachedView);
                    } else {
                        try (MemoryStack innerStack = stack.push()) {
                            assert entry.texelBufferFormat() != null;
                            VkBufferViewCreateInfo viewCreateInfo = VkBufferViewCreateInfo.calloc(innerStack).sType$Default();
                            viewCreateInfo.buffer(((VulkanGpuBuffer) value.buffer()).vkBuffer());
                            viewCreateInfo.offset(value.offset());
                            viewCreateInfo.range(value.length());
                            viewCreateInfo.format(vkFormat);
                            VulkanUtils.crashIfFailure(
                                    this.device,
                                    VK12.vkCreateBufferView(this.device.vkDevice(), viewCreateInfo, null, bufferViewPtr),
                                    "Couldn't create buffer view for texel buffer"
                            );
                            long bufferViewHandle = bufferViewPtr.get(0);
                            mango$texelViewCache.put(cacheKey, bufferViewHandle);
                            this.encoder.queueForDestroy(() -> VK12.vkDestroyBufferView(this.device.vkDevice(), bufferViewHandle, null));
                        }
                    }

                    set.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER);
                    set.pTexelBufferView(bufferViewPtr);
                }
            }

            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(this.commandBuffer, 0, this.pipeline.pipelineLayout(), 0, writes.flip());
        }

        mango$clearDirty();
    }

    @Override
    public void mango$drawIndexedIndirectCount(GpuBufferSlice commands, GpuBufferSlice countBuffer, int maxDrawCount) {
        if (this.pipeline == null || !this.pipeline.isValid()) {
            throw new IllegalStateException("Pipeline is missing or not valid");
        }
        this.pushDescriptors();
        KHRDrawIndirectCount.vkCmdDrawIndexedIndirectCountKHR(
            this.commandBuffer,
            ((VulkanGpuBuffer) commands.buffer()).vkBuffer(),
            commands.offset(),
            ((VulkanGpuBuffer) countBuffer.buffer()).vkBuffer(),
            countBuffer.offset(),
            maxDrawCount,
            VkDrawIndexedIndirectCommand.SIZEOF
        );
    }

    private boolean mango$isEntryDirty(VulkanBindGroupLayout.Entry entry) {
        if (entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE) {
            return this.mango$dirtyTextures.contains(entry.name());
        }
        return this.mango$dirtyUniforms.contains(entry.name());
    }

    private void mango$clearDirty() {
        this.mango$dirtyUniforms.clear();
        this.mango$dirtyTextures.clear();
        this.mango$pipelineChanged = false;
        this.anyDescriptorDirty = false;
    }

    private boolean mango$uniformEquals(String name, GpuBuffer buffer, long offset, long length) {
        GpuBufferSlice previous = this.mango$lastUniforms.get(name);
        return previous != null
                && previous.buffer() == buffer
                && previous.offset() == offset
                && previous.length() == length;
    }

    private static boolean mango$vertexBufferEquals(@Nullable GpuBufferSlice cached, @Nullable GpuBufferSlice current) {
        if (cached == null || current == null) {
            return cached == current;
        }
        return cached.buffer() == current.buffer()
                && cached.offset() == current.offset()
                && cached.length() == current.length();
    }
}
