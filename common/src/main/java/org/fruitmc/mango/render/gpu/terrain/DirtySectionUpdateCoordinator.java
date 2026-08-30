package org.fruitmc.mango.render.gpu.terrain;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;

public final class DirtySectionUpdateCoordinator {

    private static final long UNSET_VISIBLE_REVISION = Long.MIN_VALUE;
    private static final int NOT_VISIBLE = -1;
    private static final DirtySectionUpdateCoordinator INSTANCE = new DirtySectionUpdateCoordinator();

    private final Long2ObjectOpenHashMap<SectionRenderDispatcher.RenderSection> visibleSectionsByNode =
        new Long2ObjectOpenHashMap<>();
    private final Reference2IntOpenHashMap<SectionRenderDispatcher.RenderSection> visibleSectionOrder =
        new Reference2IntOpenHashMap<>();
    private final LongOpenHashSet candidateNodes = new LongOpenHashSet();
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> candidateSections =
        new ObjectArrayList<>();

    private @Nullable SectionUpdateTracker tracker;
    private long visibleRevision = UNSET_VISIBLE_REVISION;

    private DirtySectionUpdateCoordinator() {
        this.visibleSectionOrder.defaultReturnValue(NOT_VISIBLE);
    }

    public static DirtySectionUpdateCoordinator get() {
        return INSTANCE;
    }

    public void setTracker(@Nullable SectionUpdateTracker tracker) {
        if (this.tracker == tracker) {
            return;
        }
        this.tracker = tracker;
        this.visibleRevision = UNSET_VISIBLE_REVISION;
        this.visibleSectionsByNode.clear();
        this.visibleSectionOrder.clear();
        this.candidateNodes.clear();
        this.candidateSections.clear();
    }

    public void onSectionDirty(long sectionNode) {
        SectionUpdateTracker activeTracker = this.tracker;
        if (activeTracker == null) {
            return;
        }
        SectionUpdateTracker.SectionDirtyState dirtyState = activeTracker.getDirtyState(sectionNode);
        if (dirtyState != null && dirtyState.isDirty() && this.visibleSectionsByNode.containsKey(sectionNode)) {
            this.candidateNodes.add(sectionNode);
        }
    }

    public void onVisibleSectionNodesChanged(ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections) {
        if (this.tracker != null) {
            this.rebuildVisibleSections(visibleSections);
        }
    }

    public ObjectArrayList<SectionRenderDispatcher.RenderSection> selectCandidates(
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
        @Nullable SectionUpdateTracker tracker
    ) {
        this.setTracker(tracker);
        SectionUpdateTracker activeTracker = this.tracker;
        if (activeTracker == null || !(visibleSections instanceof TrackedVisibleSectionList trackedSections)) {
            return visibleSections;
        }

        trackedSections.settleChanges();
        long currentRevision = trackedSections.contentRevision();
        if (currentRevision != this.visibleRevision) {
            this.visibleRevision = currentRevision;
            this.rebuildVisibleSections(visibleSections);
        }

        this.candidateSections.clear();
        LongIterator iterator = this.candidateNodes.iterator();
        while (iterator.hasNext()) {
            long sectionNode = iterator.nextLong();
            SectionUpdateTracker.SectionDirtyState dirtyState = activeTracker.getDirtyState(sectionNode);
            SectionRenderDispatcher.RenderSection section = this.visibleSectionsByNode.get(sectionNode);
            if (dirtyState == null || !dirtyState.isDirty() || section == null) {
                iterator.remove();
                continue;
            }
            this.candidateSections.add(section);
        }

        this.candidateSections.sort(
            (left, right) -> Integer.compare(this.visibleSectionOrder.getInt(left), this.visibleSectionOrder.getInt(right))
        );
        return this.candidateSections;
    }

    private void rebuildVisibleSections(ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections) {
        this.visibleSectionsByNode.clear();
        this.visibleSectionOrder.clear();
        this.candidateNodes.clear();

        SectionUpdateTracker activeTracker = this.tracker;
        if (activeTracker == null) {
            return;
        }

        for (int index = 0; index < visibleSections.size(); index++) {
            SectionRenderDispatcher.RenderSection section = visibleSections.get(index);
            long sectionNode = section.getSectionNode();
            this.visibleSectionsByNode.put(sectionNode, section);
            this.visibleSectionOrder.put(section, index);
            SectionUpdateTracker.SectionDirtyState dirtyState = activeTracker.getDirtyState(sectionNode);
            if (dirtyState != null && dirtyState.isDirty()) {
                this.candidateNodes.add(sectionNode);
            }
        }
    }
}
