package org.fruitmc.mango.render.gpu;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.Nullable;

public final class RenderDeviceCapabilities {

    private static final String VULKAN_BACKEND_MARKER = "vulkan";
    private static final String MOLTENVK_DRIVER_MARKER = "moltenvk";

    private static volatile ResolvedDevice capabilities;

    private RenderDeviceCapabilities() {
    }

    private record ResolvedDevice(GpuDevice device, boolean vulkan, boolean moltenVk) {
    }

    public static boolean isVulkanDeviceActive() {
        ResolvedDevice resolved = resolveDevice();
        return resolved != null && resolved.vulkan();
    }

    public static boolean isMoltenVk() {
        ResolvedDevice resolved = resolveDevice();
        return resolved != null && resolved.moltenVk();
    }

    @Nullable
    private static ResolvedDevice resolveDevice() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return null;
        }
        ResolvedDevice cached = capabilities;
        if (cached != null && cached.device() == device) {
            return cached;
        }
        DeviceInfo info = device.getDeviceInfo();
        boolean vulkan = isVulkan(info);
        ResolvedDevice resolved = new ResolvedDevice(
            device,
            vulkan,
            vulkan && containsIgnoreCase(safeDriverInfo(info), MOLTENVK_DRIVER_MARKER)
        );
        capabilities = resolved;
        return resolved;
    }

    private static String safeDriverInfo(DeviceInfo info) {
        String driverInfo = info.driverInfo();
        return driverInfo == null ? "" : driverInfo;
    }

    private static boolean isVulkan(DeviceInfo deviceInfo) {
        return containsIgnoreCase(deviceInfo.backendName(), VULKAN_BACKEND_MARKER);
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        if (value == null || needle.length() > value.length()) {
            return false;
        }
        int finalStart = value.length() - needle.length();
        for (int start = 0; start <= finalStart; start++) {
            if (value.regionMatches(true, start, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }
}
