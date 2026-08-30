package org.fruitmc.mango.render.vulkan.cache;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.minecraft.client.Minecraft;
import org.fruitmc.mango.Constants;
import org.fruitmc.mango.render.vulkan.MangoVulkanConstants;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public final class MangoPipelineCache implements AutoCloseable {

    private static final MangoPipelineCache INSTANCE = new MangoPipelineCache();

    private static final byte[] MAGIC = "MNGPLC".getBytes(StandardCharsets.UTF_8);
    private static final int FORMAT_VERSION = 1;
    private static final int MAGIC_LENGTH = MAGIC.length;
    private static final int HEADER_BYTES = MAGIC_LENGTH + Integer.BYTES + Integer.BYTES;
    private static final String CACHE_DIR_NAME = "mango-cache";
    private static final String CACHE_FILE_NAME = "pipeline_cache.bin";
    private static final String TEMP_FILE_SUFFIX = ".tmp";

    private volatile long handle = MemoryUtil.NULL;
    private volatile VulkanDevice cachedDevice;
    private volatile Path cacheFilePath;

    private MangoPipelineCache() {
    }

    public static MangoPipelineCache get() {
        return INSTANCE;
    }

    public long handle(VulkanDevice device) {
        if (this.handle != MemoryUtil.NULL && this.cachedDevice == device) {
            return this.handle;
        }
        if (this.handle != MemoryUtil.NULL) {
            close();
        }
        this.cachedDevice = device;
        this.cacheFilePath = resolveCachePath();
        this.handle = createCache(device, this.cacheFilePath);
        return this.handle;
    }

    @Override
    public void close() {
        VulkanDevice device = this.cachedDevice;
        long cache = this.handle;
        if (cache != MemoryUtil.NULL && device != null) {
            saveCacheData(device, cache, this.cacheFilePath);
            VK12.vkDestroyPipelineCache(device.vkDevice(), cache, null);
        }
        this.handle = MemoryUtil.NULL;
        this.cachedDevice = null;
        this.cacheFilePath = null;
    }

    private static Path resolveCachePath() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve(CACHE_DIR_NAME)
            .resolve(CACHE_FILE_NAME);
    }

    private static long createCache(VulkanDevice device, Path cacheFile) {
        ByteBuffer initialData = loadInitialData(device, cacheFile);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineCacheCreateInfo createInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            if (initialData != null) {
                createInfo.pInitialData(initialData);
            }
            LongBuffer pCache = stack.mallocLong(1);
            int result = VK12.vkCreatePipelineCache(device.vkDevice(), createInfo, null, pCache);
            if (result != MangoVulkanConstants.VK_SUCCESS && initialData != null) {
                Constants.LOG.warn("vkCreatePipelineCache rejected initial data (0x{}); retrying empty", Integer.toHexString(result));
                createInfo.pInitialData(null);
                result = VK12.vkCreatePipelineCache(device.vkDevice(), createInfo, null, pCache);
            }
            if (result != MangoVulkanConstants.VK_SUCCESS) {
                Constants.LOG.warn("vkCreatePipelineCache failed: 0x{}", Integer.toHexString(result));
                return MemoryUtil.NULL;
            }
            return pCache.get(0);
        } finally {
            if (initialData != null) {
                MemoryUtil.memFree(initialData);
            }
        }
    }

    private static ByteBuffer loadInitialData(VulkanDevice device, Path cacheFile) {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(cacheFile);
        } catch (IOException e) {
            Constants.LOG.warn("Failed to read pipeline cache from {}", cacheFile, e);
            return null;
        }
        if (fileBytes.length < HEADER_BYTES) {
            return null;
        }
        ByteBuffer fileData = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < MAGIC_LENGTH; i++) {
            if (fileData.get() != MAGIC[i]) {
                return null;
            }
        }
        int version = fileData.getInt();
        if (version != FORMAT_VERSION) {
            return null;
        }
        int fingerprintLength = fileData.getInt();
        if (fingerprintLength <= 0 || fingerprintLength > fileData.remaining()) {
            return null;
        }
        byte[] storedFingerprint = new byte[fingerprintLength];
        fileData.get(storedFingerprint);
        byte[] expectedFingerprint = buildFingerprint(device).getBytes(StandardCharsets.UTF_8);
        if (!Arrays.equals(storedFingerprint, expectedFingerprint)) {
            Constants.LOG.info("Pipeline cache fingerprint mismatch (device or driver changed); starting fresh");
            return null;
        }
        int dataLength = fileData.remaining();
        if (dataLength == 0) {
            return null;
        }
        ByteBuffer initialData = MemoryUtil.memAlloc(dataLength);
        initialData.put(fileData);
        initialData.flip();
        return initialData;
    }

    private static String buildFingerprint(VulkanDevice device) {
        DeviceInfo info = device.getDeviceInfo();
        return info.name() + "\n" + info.driverInfo() + "\n" + info.vendorName();
    }

    private static void saveCacheData(VulkanDevice device, long cacheHandle, Path cacheFile) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pSize = stack.mallocPointer(1);
            pSize.put(0, 0L);
            int result = VK12.vkGetPipelineCacheData(device.vkDevice(), cacheHandle, pSize, null);
            if (result != MangoVulkanConstants.VK_SUCCESS) {
                Constants.LOG.warn("vkGetPipelineCacheData size query failed: 0x{}", Integer.toHexString(result));
                return;
            }
            int dataSize = (int) pSize.get(0);
            if (dataSize <= 0) {
                return;
            }
            ByteBuffer data = MemoryUtil.memAlloc(dataSize);
            try {
                pSize.put(0, dataSize);
                result = VK12.vkGetPipelineCacheData(device.vkDevice(), cacheHandle, pSize, data);
                if (result != MangoVulkanConstants.VK_SUCCESS) {
                    Constants.LOG.warn("vkGetPipelineCacheData read failed: 0x{}", Integer.toHexString(result));
                    return;
                }
                int actualSize = (int) pSize.get(0);
                writeCacheFile(device, cacheFile, data, actualSize);
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }

    private static void writeCacheFile(VulkanDevice device, Path cacheFile, ByteBuffer vkData, int vkDataLength) {
        byte[] fingerprintBytes = buildFingerprint(device).getBytes(StandardCharsets.UTF_8);
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempFile = cacheFile.resolveSibling(CACHE_FILE_NAME + TEMP_FILE_SUFFIX);
            try (FileChannel channel = FileChannel.open(tempFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                header.put(MAGIC);
                header.putInt(FORMAT_VERSION);
                header.putInt(fingerprintBytes.length);
                header.flip();
                channel.write(header);
                channel.write(ByteBuffer.wrap(fingerprintBytes));
                channel.write(vkData.slice(0, vkDataLength));
            }
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Constants.LOG.warn("Failed to persist pipeline cache to {}", cacheFile, e);
        }
    }
}
