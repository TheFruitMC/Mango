package org.fruitmc.mango.render.gpu.terrain;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

public final class TrackedVisibleSectionList
    extends ObjectArrayList<SectionRenderDispatcher.RenderSection> {

    private static final long INITIAL_CONTENT_REVISION = 1L;
    private static final long NO_CAPTURED_REVISION = 0L;

    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> settledSections;
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> translucentSections;
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> translucentScratch;
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> blockEntitySections;
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> blockEntityScratch;
    private boolean rebuildPending;
    private boolean sequenceChanged;
    private int comparedSectionCount;
    private long contentRevision = INITIAL_CONTENT_REVISION;
    private long translucentContentRevision = INITIAL_CONTENT_REVISION;
    private long translucentVisibleRevision = NO_CAPTURED_REVISION;
    private long translucentGeometryRevision = NO_CAPTURED_REVISION;
    private long blockEntityVisibleRevision = NO_CAPTURED_REVISION;
    private long blockEntityContentRevision = NO_CAPTURED_REVISION;
    private boolean translucentSelectionStable;
    private boolean blockEntitySelectionStable;

    public TrackedVisibleSectionList(int initialCapacity) {
        super(initialCapacity);
        this.settledSections = new ObjectArrayList<>(initialCapacity);
        this.translucentSections = new ObjectArrayList<>();
        this.translucentScratch = new ObjectArrayList<>();
        this.blockEntitySections = new ObjectArrayList<>();
        this.blockEntityScratch = new ObjectArrayList<>();
    }

    @Override
    public void clear() {
        super.clear();
        this.rebuildPending = true;
        this.sequenceChanged = false;
        this.comparedSectionCount = 0;
    }

    @Override
    public boolean add(SectionRenderDispatcher.RenderSection section) {
        if (!this.rebuildPending) {
            this.rebuildPending = true;
            this.sequenceChanged = this.size() != this.settledSections.size();
            this.comparedSectionCount = this.size();
        }
        if (!this.sequenceChanged
            && (this.comparedSectionCount >= this.settledSections.size()
                || this.settledSections.get(this.comparedSectionCount) != section)) {
            this.sequenceChanged = true;
        }
        this.comparedSectionCount++;
        return super.add(section);
    }

    public boolean settleChanges() {
        if (!this.rebuildPending) {
            return false;
        }
        boolean changed = this.sequenceChanged
            || this.comparedSectionCount != this.size()
            || this.comparedSectionCount != this.settledSections.size();
        if (changed) {
            this.settledSections.clear();
            this.settledSections.addAll(this);
            this.contentRevision = Math.incrementExact(this.contentRevision);
        }
        this.rebuildPending = false;
        this.sequenceChanged = false;
        this.comparedSectionCount = 0;
        return changed;
    }

    public long contentRevision() {
        return this.contentRevision;
    }

    public ObjectArrayList<SectionRenderDispatcher.RenderSection> translucentSections() {
        this.settleChanges();
        this.refreshContentSubsets();
        return this.translucentSelectionStable ? this.translucentSections : this;
    }

    public long translucentContentRevision() {
        return this.translucentContentRevision;
    }

    public boolean isTranslucentSelectionStable() {
        return this.translucentSelectionStable;
    }

    public ObjectArrayList<SectionRenderDispatcher.RenderSection> blockEntitySections() {
        this.settleChanges();
        this.refreshContentSubsets();
        return this.blockEntitySelectionStable ? this.blockEntitySections : this;
    }

    public void resetTracking() {
        super.clear();
        this.settledSections.clear();
        this.translucentSections.clear();
        this.translucentScratch.clear();
        this.blockEntitySections.clear();
        this.blockEntityScratch.clear();
        this.rebuildPending = false;
        this.sequenceChanged = false;
        this.comparedSectionCount = 0;
        this.contentRevision = Math.incrementExact(this.contentRevision);
        this.translucentContentRevision = Math.incrementExact(this.translucentContentRevision);
        this.translucentVisibleRevision = NO_CAPTURED_REVISION;
        this.translucentGeometryRevision = NO_CAPTURED_REVISION;
        this.blockEntityVisibleRevision = NO_CAPTURED_REVISION;
        this.blockEntityContentRevision = NO_CAPTURED_REVISION;
        this.translucentSelectionStable = false;
        this.blockEntitySelectionStable = false;
    }

    private void refreshContentSubsets() {
        long currentTranslucentRevision = VisibleSectionContentTracker.translucentGeometryRevision();
        long currentBlockEntityRevision = VisibleSectionContentTracker.blockEntityRevision();
        boolean translucentNeedsRefresh = this.translucentVisibleRevision != this.contentRevision
            || this.translucentGeometryRevision != currentTranslucentRevision;
        boolean blockEntityNeedsRefresh = this.blockEntityVisibleRevision != this.contentRevision
            || this.blockEntityContentRevision != currentBlockEntityRevision;
        if (!translucentNeedsRefresh && !blockEntityNeedsRefresh) {
            this.translucentSelectionStable = true;
            this.blockEntitySelectionStable = true;
            return;
        }

        if (this.translucentVisibleRevision != this.contentRevision
            && this.blockEntityVisibleRevision != this.contentRevision) {
            this.rebuildBothSubsets(currentTranslucentRevision, currentBlockEntityRevision);
            return;
        }
        if (translucentNeedsRefresh) {
            this.rebuildTranslucentSubset(currentTranslucentRevision);
        }
        if (blockEntityNeedsRefresh) {
            this.rebuildBlockEntitySubset(currentBlockEntityRevision);
        }
    }

    private void rebuildBothSubsets(long translucentRevision, long blockEntityRevision) {
        this.translucentScratch.clear();
        this.blockEntityScratch.clear();
        for (int index = 0, size = this.size(); index < size; index++) {
            SectionRenderDispatcher.RenderSection section = this.get(index);
            SectionMesh mesh = section.getSectionMesh();
            if (mesh.hasTranslucentGeometry()) {
                this.translucentScratch.add(section);
            }
            if (!mesh.getRenderableBlockEntities().isEmpty()) {
                this.blockEntityScratch.add(section);
            }
        }

        long settledTranslucentRevision = VisibleSectionContentTracker.translucentGeometryRevision();
        long settledBlockEntityRevision = VisibleSectionContentTracker.blockEntityRevision();
        if (translucentRevision == settledTranslucentRevision) {
            this.commitTranslucentSubset(settledTranslucentRevision);
        } else {
            this.invalidateTranslucentSelection();
        }
        if (blockEntityRevision == settledBlockEntityRevision) {
            this.commitBlockEntitySubset(settledBlockEntityRevision);
        } else {
            this.invalidateBlockEntitySelection();
        }
    }

    private void rebuildTranslucentSubset(long translucentRevision) {
        this.translucentScratch.clear();
        for (int index = 0, size = this.size(); index < size; index++) {
            SectionRenderDispatcher.RenderSection section = this.get(index);
            if (section.getSectionMesh().hasTranslucentGeometry()) {
                this.translucentScratch.add(section);
            }
        }
        long settledRevision = VisibleSectionContentTracker.translucentGeometryRevision();
        if (translucentRevision == settledRevision) {
            this.commitTranslucentSubset(settledRevision);
        } else {
            this.invalidateTranslucentSelection();
        }
    }

    private void rebuildBlockEntitySubset(long blockEntityRevision) {
        this.blockEntityScratch.clear();
        for (int index = 0, size = this.size(); index < size; index++) {
            SectionRenderDispatcher.RenderSection section = this.get(index);
            if (!section.getSectionMesh().getRenderableBlockEntities().isEmpty()) {
                this.blockEntityScratch.add(section);
            }
        }
        long settledRevision = VisibleSectionContentTracker.blockEntityRevision();
        if (blockEntityRevision == settledRevision) {
            this.commitBlockEntitySubset(settledRevision);
        } else {
            this.invalidateBlockEntitySelection();
        }
    }

    private void commitTranslucentSubset(long geometryRevision) {
        if (!hasSameIdentityOrder(this.translucentSections, this.translucentScratch)) {
            ObjectArrayList<SectionRenderDispatcher.RenderSection> previous = this.translucentSections;
            this.translucentSections = this.translucentScratch;
            this.translucentScratch = previous;
            this.translucentContentRevision = Math.incrementExact(this.translucentContentRevision);
        }
        this.translucentScratch.clear();
        this.translucentVisibleRevision = this.contentRevision;
        this.translucentGeometryRevision = geometryRevision;
        this.translucentSelectionStable = true;
    }

    private void commitBlockEntitySubset(long contentRevision) {
        if (!hasSameIdentityOrder(this.blockEntitySections, this.blockEntityScratch)) {
            ObjectArrayList<SectionRenderDispatcher.RenderSection> previous = this.blockEntitySections;
            this.blockEntitySections = this.blockEntityScratch;
            this.blockEntityScratch = previous;
        }
        this.blockEntityScratch.clear();
        this.blockEntityVisibleRevision = this.contentRevision;
        this.blockEntityContentRevision = contentRevision;
        this.blockEntitySelectionStable = true;
    }

    private void invalidateTranslucentSelection() {
        this.translucentScratch.clear();
        this.translucentVisibleRevision = NO_CAPTURED_REVISION;
        this.translucentGeometryRevision = NO_CAPTURED_REVISION;
        this.translucentSelectionStable = false;
    }

    private void invalidateBlockEntitySelection() {
        this.blockEntityScratch.clear();
        this.blockEntityVisibleRevision = NO_CAPTURED_REVISION;
        this.blockEntityContentRevision = NO_CAPTURED_REVISION;
        this.blockEntitySelectionStable = false;
    }

    private static boolean hasSameIdentityOrder(
        ObjectArrayList<SectionRenderDispatcher.RenderSection> first,
        ObjectArrayList<SectionRenderDispatcher.RenderSection> second
    ) {
        int size = first.size();
        if (size != second.size()) {
            return false;
        }
        for (int index = 0; index < size; index++) {
            if (first.get(index) != second.get(index)) {
                return false;
            }
        }
        return true;
    }
}
