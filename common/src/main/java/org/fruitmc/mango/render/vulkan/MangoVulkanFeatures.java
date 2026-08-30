package org.fruitmc.mango.render.vulkan;

public final class MangoVulkanFeatures {

    private static volatile boolean indirectCountEnabled = false;
    private MangoVulkanFeatures() {
    }

    public static boolean isIndirectCountEnabled() {
        return indirectCountEnabled;
    }
    public static void setIndirectCountEnabled(boolean enabled) {
        indirectCountEnabled = enabled;
    }
}
