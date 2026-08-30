package org.fruitmc.mango.render.gpu.hiz;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;

import java.nio.ByteBuffer;
import java.util.List;

public final class HiZDebugOverlay {

    private static final int VISIBILITY_BYTES_PER_SECTION = 4;

    private static int tableSlots;
    private static int processedDraws;
    private static int visibleSections;
    private static boolean readbackFailed;

    private HiZDebugOverlay() {
    }

    public static boolean isReadbackRequested() {
        return Minecraft.getInstance().debugEntries.isOverlayVisible();
    }

    public static void collectStats() {
        HiZCulling hiZ = HiZCulling.get();
        if (!hiZ.isReady()) {
            tableSlots = 0;
            processedDraws = 0;
            visibleSections = 0;
            readbackFailed = false;
            return;
        }

        int sectionCount = hiZ.visibilitySectionCount();
        if (sectionCount <= 0) {
            tableSlots = 0;
            processedDraws = 0;
            visibleSections = 0;
            readbackFailed = false;
            return;
        }

        tableSlots = sectionCount;
        processedDraws = hiZ.stagingDrawCount();

        try (GpuBufferSlice.MappedView view = hiZ.mapVisibilityForRead()) {
            if (view == null) {
                visibleSections = -1;
                readbackFailed = true;
                return;
            }
            ByteBuffer visibility = view.data();
            int visible = 0;
            for (int slot = 0; slot < sectionCount; slot++) {
                if (visibility.getInt(slot * VISIBILITY_BYTES_PER_SECTION) != 0) {
                    visible++;
                }
            }
            visibleSections = visible;
            readbackFailed = false;
        }
    }

    public static void appendDebugLines(List<String> lines) {
        HiZCulling hiZ = HiZCulling.get();
        if (!hiZ.isReady()) {
            return;
        }
        if (!hiZ.isStagingReadbackReady()) {
            lines.add("[Mango] Hi-Z: warming up readback...");
            return;
        }
        int visible = visibleSections;
        if (readbackFailed || visible < 0) {
            lines.add("[Mango] Hi-Z: readback pending");
        } else {
            lines.add("[Mango] Hi-Z: visible slots=" + visible + "/" + tableSlots + ", draws=" + processedDraws);
        }
    }
}
