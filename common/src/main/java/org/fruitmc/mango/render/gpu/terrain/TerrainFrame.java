package org.fruitmc.mango.render.gpu.terrain;

import com.mojang.blaze3d.systems.RenderPass;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.renderer.DynamicUniforms;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;

public final class TerrainFrame {

    public static final int NO_SECTION_INDEX = -1;

    public static final long NO_VISIBLE_SECTIONS_REVISION = 0L;

    private static final TerrainFrame EMPTY = new TerrainFrame(
        new Matrix4f(),
        new Matrix4f(),
        0,
        0,
        0,
        0.0F,
        0.0F,
        0.0F,
        List.of(),
        new Reference2IntOpenHashMap<>(),
        null,
        null,
        NO_VISIBLE_SECTIONS_REVISION,
        false
    );

    private final Matrix4f modelView;
    private final Matrix4f viewProjection;
    private final int cameraBlockX;
    private final int cameraBlockY;
    private final int cameraBlockZ;
    private final float cameraOffsetX;
    private final float cameraOffsetY;
    private final float cameraOffsetZ;
    private final List<DynamicUniforms.ChunkSectionInfo> sections;
    private final Reference2IntOpenHashMap<RenderPass.Draw<?>> sectionIndices;
    @Nullable private final TerrainSectionRegistry.Snapshot registrySnapshot;
    @Nullable private final IntSet visibleSlots;
    private final long visibleSectionsRevision;
    private final boolean valid;

    public TerrainFrame(
        Matrix4fc modelView,
        Matrix4fc viewProjection,
        int cameraBlockX,
        int cameraBlockY,
        int cameraBlockZ,
        float cameraOffsetX,
        float cameraOffsetY,
        float cameraOffsetZ,
        List<DynamicUniforms.ChunkSectionInfo> sections,
        Reference2IntOpenHashMap<RenderPass.Draw<?>> sectionIndices,
        @Nullable TerrainSectionRegistry.Snapshot registrySnapshot,
        @Nullable IntSet visibleSlots,
        long visibleSectionsRevision
    ) {
        this(
            modelView,
            viewProjection,
            cameraBlockX,
            cameraBlockY,
            cameraBlockZ,
            cameraOffsetX,
            cameraOffsetY,
            cameraOffsetZ,
            sections,
            sectionIndices,
            registrySnapshot,
            visibleSlots,
            visibleSectionsRevision,
            true
        );
    }

    private TerrainFrame(
        Matrix4fc modelView,
        Matrix4fc viewProjection,
        int cameraBlockX,
        int cameraBlockY,
        int cameraBlockZ,
        float cameraOffsetX,
        float cameraOffsetY,
        float cameraOffsetZ,
        List<DynamicUniforms.ChunkSectionInfo> sections,
        Reference2IntOpenHashMap<RenderPass.Draw<?>> sectionIndices,
        @Nullable TerrainSectionRegistry.Snapshot registrySnapshot,
        @Nullable IntSet visibleSlots,
        long visibleSectionsRevision,
        boolean valid
    ) {
        this.modelView = new Matrix4f(modelView);
        this.viewProjection = new Matrix4f(viewProjection);
        this.cameraBlockX = cameraBlockX;
        this.cameraBlockY = cameraBlockY;
        this.cameraBlockZ = cameraBlockZ;
        this.cameraOffsetX = cameraOffsetX;
        this.cameraOffsetY = cameraOffsetY;
        this.cameraOffsetZ = cameraOffsetZ;
        this.sections = sections;
        this.sectionIndices = sectionIndices;
        this.sectionIndices.defaultReturnValue(NO_SECTION_INDEX);
        this.registrySnapshot = registrySnapshot;
        this.visibleSlots = visibleSlots;
        this.visibleSectionsRevision = visibleSectionsRevision;
        this.valid = valid;
    }

    public static TerrainFrame empty() {
        return EMPTY;
    }

    public boolean isReady() {
        return this.valid
            && !this.sections.isEmpty()
            && !this.sectionIndices.isEmpty();
    }

    public boolean hasView() {
        return this.valid;
    }

    public Matrix4fc modelView() {
        return this.modelView;
    }

    public Matrix4fc viewProjection() {
        return this.viewProjection;
    }

    public int cameraBlockX() {
        return this.cameraBlockX;
    }

    public int cameraBlockY() {
        return this.cameraBlockY;
    }

    public int cameraBlockZ() {
        return this.cameraBlockZ;
    }

    public float cameraOffsetX() {
        return this.cameraOffsetX;
    }

    public float cameraOffsetY() {
        return this.cameraOffsetY;
    }

    public float cameraOffsetZ() {
        return this.cameraOffsetZ;
    }

    public float cameraWorldX() {
        return this.cameraBlockX - this.cameraOffsetX;
    }

    public float cameraWorldY() {
        return this.cameraBlockY - this.cameraOffsetY;
    }

    public float cameraWorldZ() {
        return this.cameraBlockZ - this.cameraOffsetZ;
    }

    @Nullable public TerrainSectionRegistry.Snapshot registrySnapshot() {
        return this.registrySnapshot;
    }

    @Nullable public IntSet visibleSlots() {
        return this.visibleSlots;
    }

    public long visibleSectionsRevision() {
        return this.visibleSectionsRevision;
    }

    public List<DynamicUniforms.ChunkSectionInfo> sections() {
        return this.sections;
    }

    public int sectionIndexOrAbsent(RenderPass.Draw<?> draw) {
        return this.sectionIndices.getInt(draw);
    }

    public int sectionIndex(RenderPass.Draw<?> draw) {
        int index = this.sectionIndices.getInt(draw);
        if (index == NO_SECTION_INDEX) {
            throw new IllegalArgumentException(
                "Terrain draw has no captured section index; captured draws: " + this.sectionIndices.size()
            );
        }
        return index;
    }
}
