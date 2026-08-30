package org.fruitmc.mango.render.gpu.terrain;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.fruitmc.mango.Constants;
import org.fruitmc.mango.render.chunk.vertex.CompactTerrainVertex;
import org.fruitmc.mango.render.gpu.IndirectCommandBuffer;
import org.fruitmc.mango.render.gpu.buffer.PersistentBufferUploader;
import org.fruitmc.mango.render.gpu.hiz.HiZCulling;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanUsage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Mirrors the vanilla chunk dispatcher's allocations into draw groups that can be reused across frames.
 * The registry owns CPU-side tables only; GPU buffers are created lazily by the terrain renderer.
 */
public final class TerrainSectionRegistry implements AutoCloseable {

    private static final TerrainSectionRegistry INSTANCE = new TerrainSectionRegistry();
    private static final int SECTION_RECORD_INTS = 4;
    private static final int SECTION_RECORD_BYTES = SECTION_RECORD_INTS * Integer.BYTES;
    private static final int INITIAL_SECTION_CAPACITY = 4096;
    private static final int GROWTH_FACTOR = 2;
    private static final int HASH_MULTIPLIER = 31;
    private static final float FULL_VISIBILITY = 1.0F;
    private static final int COMMAND_TEMPLATE_USAGE = GpuBuffer.USAGE_INDIRECT_PARAMETERS | MangoVulkanUsage.STORAGE_BUFFER;

    private static final int BLOCKS_TO_SECTION_SHIFT = 4;
    private static final int REGION_SHIFT_X = 3;
    private static final int REGION_SHIFT_Y = 2;
    private static final int REGION_SHIFT_Z = 3;
    private static final int REGION_KEY_BITS = 21;
    private static final long REGION_KEY_MASK = (1L << REGION_KEY_BITS) - 1L;
    private static final int REGION_KEY_SHIFT_X = REGION_KEY_BITS * 2;
    private static final int REGION_KEY_SHIFT_Z = REGION_KEY_BITS;
    private static final long UNKNOWN_REGION_KEY = Long.MIN_VALUE;
    private static final int MINIMUM_BATCH_CAPACITY = 1;
    private static final ChunkSectionLayer[] SECTION_LAYERS = ChunkSectionLayer.values();
    private static final Comparator<DrawGroup> DRAW_GROUP_LAYER_ORDER =
        Comparator.comparingInt(group -> group.key().layer().ordinal());

    public static final int BLOCKS_TO_SECTION_SHIFT_CAMERA = 4;

    private static final int SECTION_DISTANCE_MARGIN = 4;
    private static final int PRUNE_DISABLED = Integer.MIN_VALUE;

    private final ConcurrentLinkedQueue<RegistryEvent> pendingEvents = new ConcurrentLinkedQueue<>();
    private final Set<SectionRenderDispatcher> disposedDispatchers = Collections.newSetFromMap(new WeakHashMap<>());
    private final Int2ObjectOpenHashMap<SectionRecord> sections = new Int2ObjectOpenHashMap<>();
    private final IntOpenHashSet dirtySectionSlots = new IntOpenHashSet();
    private final IntArrayList pruneScratch = new IntArrayList();
    private final Map<GroupKey, DrawGroup> groups = new HashMap<>();
    private List<DrawGroup> cachedGroupList = List.of();
    private boolean groupListDirty = true;
    @Nullable private SectionRenderDispatcher dispatcher;
    @Nullable private Snapshot cachedSnapshot;
    private ByteBuffer sectionTable = MemoryUtil.memCalloc(INITIAL_SECTION_CAPACITY * SECTION_RECORD_BYTES);
    private volatile boolean closed;
    private int highestSlot = -1;
    private boolean highestSlotDirty;
    private long sectionRevision;
    private boolean sectionRevisionPending;
    private boolean snapshotDirty = true;
    private int lastPruneCameraSecX = PRUNE_DISABLED;
    private int lastPruneCameraSecZ = PRUNE_DISABLED;

    private TerrainSectionRegistry() {
    }

    public static TerrainSectionRegistry get() {
        return INSTANCE;
    }

    public void onSectionChanged(
        SectionRenderDispatcher dispatcher,
        SectionRenderDispatcher.RenderSection section
    ) {
        if (this.closed) {
            return;
        }
        if (RenderSystem.isOnRenderThread()) {
            apply(dispatcher, section);
        } else {
            this.pendingEvents.add(new SectionChangedEvent(dispatcher, section));
        }
    }

    public void onDispatcherDisposed(SectionRenderDispatcher dispatcher) {
        if (this.closed) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            this.pendingEvents.add(new DispatcherDisposedEvent(dispatcher));
            return;
        }
        disposeDispatcher(dispatcher);
    }

    @Nullable public Snapshot snapshot() {
        RenderSystem.assertOnRenderThread();
        if (this.closed) {
            return null;
        }
        drainEvents();
        pruneDistantSections();
        if (hasClosedBindings()) {
            clearRecords();
            Constants.LOG.warn("Persistent terrain registry referenced a closed chunk buffer; falling back to per-frame terrain preparation");
            return null;
        }
        if (!this.snapshotDirty) {
            if (this.cachedSnapshot != null) {
                return this.cachedSnapshot;
            }
        }
        settleSectionRevision();
        if (this.highestSlotDirty) {
            recomputeHighestSlot();
        }

        if (this.groups.isEmpty() || this.highestSlot < 0) {
            this.cachedSnapshot = null;
        } else {
            int sectionBytes = Math.multiplyExact(this.highestSlot + 1, SECTION_RECORD_BYTES);
            this.cachedSnapshot =
                new Snapshot(
                    this.sectionTable.slice(0, sectionBytes),
                    groupList(),
                    this.dirtySectionSlots.toIntArray(),
                    this.sectionRevision
                );
        }
        this.dirtySectionSlots.clear();
        this.snapshotDirty = false;
        return this.cachedSnapshot;
    }

    private List<DrawGroup> groupList() {
        if (this.groupListDirty) {
            List<DrawGroup> orderedGroups = new ArrayList<>(this.groups.values());
            orderedGroups.sort(DRAW_GROUP_LAYER_ORDER);
            this.cachedGroupList = List.copyOf(orderedGroups);
            this.groupListDirty = false;
        }
        return this.cachedGroupList;
    }

    private static ByteBuffer ensureNativeCapacity(ByteBuffer current, int requiredBytes) {
        if (current.capacity() >= requiredBytes) {
            return current;
        }
        int capacity = current.capacity();
        while (capacity < requiredBytes) {
            capacity = Math.multiplyExact(capacity, GROWTH_FACTOR);
        }
        return MemoryUtil.memRealloc(current, capacity);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.pendingEvents.clear();
        this.disposedDispatchers.clear();
        clearRecords();
        MemoryUtil.memFree(this.sectionTable);
    }

    private void drainEvents() {
        RegistryEvent event;
        while ((event = this.pendingEvents.poll()) != null) {
            if (event instanceof SectionChangedEvent changed) {
                apply(changed.dispatcher(), changed.section());
            } else if (event instanceof DispatcherDisposedEvent disposed) {
                disposeDispatcher(disposed.dispatcher());
            }
        }
    }

    private void apply(
        SectionRenderDispatcher owner,
        SectionRenderDispatcher.RenderSection section
    ) {
        if (this.disposedDispatchers.contains(owner)) {
            return;
        }
        if (this.dispatcher == null || this.dispatcher != owner) {
            clearRecords();
            this.dispatcher = owner;
        }
        this.snapshotDirty = true;

        removeSection(section.index);
        SectionMesh mesh = section.getSectionMesh();
        if (!mesh.hasRenderableLayers()) {
            return;
        }

        BlockPos origin = section.getRenderOrigin();
        if (!isWithinRenderDistance(origin)) {
            return;
        }
        long sectionRegionKey = regionKey(origin);
        List<DrawRecord> draws = new ArrayList<>(SECTION_LAYERS.length);
        owner.lock();
        try {
            for (ChunkSectionLayer layer : SECTION_LAYERS) {
                if (layer == ChunkSectionLayer.TRANSLUCENT) {
                    continue;
                }
                SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
                SectionRenderDispatcher.RenderSectionBufferSlice slice = owner.getRenderSectionSlice(mesh, layer);
                if (draw == null || slice == null || draw.indexCount() <= 0) {
                    continue;
                }
                if (draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) {
                    continue;
                }

                GpuBuffer indexBuffer = draw.hasCustomIndexBuffer()
                    ? slice.indexBuffer()
                    : null;
                IndexType indexType = draw.hasCustomIndexBuffer()
                    ? draw.indexType()
                    : null;
                int firstIndex = draw.hasCustomIndexBuffer()
                    ? Math.toIntExact(slice.indexBufferOffset() / draw.indexType().bytes)
                    : 0;
                int vertexStride = CompactTerrainVertex.STRIDE;
                int baseVertex = Math.toIntExact(slice.vertexBufferOffset() / vertexStride);
                GroupKey key = new GroupKey(layer, slice.vertexBuffer(), indexBuffer, indexType);
                DrawRecord record = new DrawRecord(
                    section.index,
                    draw.indexCount(),
                    firstIndex,
                    baseVertex,
                    sectionRegionKey,
                    key
                );
                DrawGroup group = this.groups.get(key);
                if (group == null) {
                    group = new DrawGroup(key);
                    this.groups.put(key, group);
                    this.groupListDirty = true;
                }
                group.put(record);
                draws.add(record);
            }
        } finally {
            owner.unlock();
        }

        if (!draws.isEmpty()) {
            ensureSectionTableCapacity(Math.multiplyExact(section.index + 1, SECTION_RECORD_BYTES));
            writeSectionSlot(section.index, origin);
            this.sections.put(
                section.index,
                new SectionRecord(draws, sectionRegionKey, origin.getX(), origin.getY(), origin.getZ())
            );
            this.highestSlot = Math.max(this.highestSlot, section.index);
        }
    }

    private void removeSection(int slot) {
        SectionRecord previous = this.sections.remove(slot);
        if (previous == null) {
            return;
        }
        for (DrawRecord draw : previous.draws()) {
            DrawGroup group = this.groups.get(draw.groupKey());
            if (group != null) {
                group.remove(draw.regionKey(), slot);
                if (group.isEmpty()) {
                    this.groups.remove(draw.groupKey());
                    this.groupListDirty = true;
                    group.close();
                }
            }
        }
        clearSectionSlot(slot);
        if (slot == this.highestSlot) {
            this.highestSlotDirty = true;
        }
    }

    private void recomputeHighestSlot() {
        int highest = -1;
        IntIterator iterator = this.sections.keySet().intIterator();
        while (iterator.hasNext()) {
            highest = Math.max(highest, iterator.nextInt());
        }
        this.highestSlot = highest;
        this.highestSlotDirty = false;
    }

    private void disposeDispatcher(SectionRenderDispatcher owner) {
        this.disposedDispatchers.add(owner);
        if (this.dispatcher != null && this.dispatcher == owner) {
            clearRecords();
            this.dispatcher = null;
        }
    }

    private static int cameraSectionX() {
        var player = Minecraft.getInstance().player;
        return player != null ? player.blockPosition().getX() >> BLOCKS_TO_SECTION_SHIFT : PRUNE_DISABLED;
    }

    private static int cameraSectionZ() {
        var player = Minecraft.getInstance().player;
        return player != null ? player.blockPosition().getZ() >> BLOCKS_TO_SECTION_SHIFT : PRUNE_DISABLED;
    }

    private static int renderDistanceSections() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance();
    }

    private static boolean isWithinRenderDistance(BlockPos origin) {
        int camSecX = cameraSectionX();
        int camSecZ = cameraSectionZ();
        if (camSecX == PRUNE_DISABLED) {
            return true;
        }
        int secX = origin.getX() >> BLOCKS_TO_SECTION_SHIFT;
        int secZ = origin.getZ() >> BLOCKS_TO_SECTION_SHIFT;
        int maxDistance = renderDistanceSections() + SECTION_DISTANCE_MARGIN;
        return Math.abs(secX - camSecX) <= maxDistance && Math.abs(secZ - camSecZ) <= maxDistance;
    }

    private void pruneDistantSections() {
        int camSecX = cameraSectionX();
        int camSecZ = cameraSectionZ();
        if (camSecX == PRUNE_DISABLED) {
            return;
        }
        if (camSecX == this.lastPruneCameraSecX && camSecZ == this.lastPruneCameraSecZ) {
            return;
        }
        this.lastPruneCameraSecX = camSecX;
        this.lastPruneCameraSecZ = camSecZ;

        int maxDistance = renderDistanceSections() + SECTION_DISTANCE_MARGIN;
        this.pruneScratch.clear();
        for (Int2ObjectMap.Entry<SectionRecord> entry : Int2ObjectMaps.fastIterable(this.sections)) {
            SectionRecord record = entry.getValue();
            int secX = record.originX() >> BLOCKS_TO_SECTION_SHIFT;
            int secZ = record.originZ() >> BLOCKS_TO_SECTION_SHIFT;
            if (Math.abs(secX - camSecX) > maxDistance || Math.abs(secZ - camSecZ) > maxDistance) {
                this.pruneScratch.add(entry.getIntKey());
            }
        }
        if (!this.pruneScratch.isEmpty()) {
            for (int i = 0; i < this.pruneScratch.size(); i++) {
                removeSection(this.pruneScratch.getInt(i));
            }
            this.snapshotDirty = true;
        }
    }

    private boolean hasClosedBindings() {
        for (GroupKey key : this.groups.keySet()) {
            if (key.vertexBuffer().isClosed()
                    || key.indexBuffer() != null && key.indexBuffer().isClosed()) {
                return true;
            }
        }
        return false;
    }

    private void clearRecords() {
        if (!this.sections.isEmpty()) {
            this.dirtySectionSlots.addAll(this.sections.keySet());
            this.sectionRevisionPending = true;
        }
        this.sections.clear();
        this.groups.values().forEach(DrawGroup::close);
        this.groups.clear();
        this.cachedGroupList = List.of();
        this.groupListDirty = false;
        this.highestSlot = -1;
        this.highestSlotDirty = false;
        this.cachedSnapshot = null;
        this.snapshotDirty = true;
        MemoryUtil.memSet(MemoryUtil.memAddress(this.sectionTable), 0, this.sectionTable.capacity());
    }

    private void ensureSectionTableCapacity(int requiredBytes) {
        if (requiredBytes <= this.sectionTable.capacity()) {
            return;
        }
        int oldCapacity = this.sectionTable.capacity();
        int capacity = oldCapacity;
        while (capacity < requiredBytes) {
            capacity = Math.multiplyExact(capacity, GROWTH_FACTOR);
        }
        this.sectionTable = MemoryUtil.memRealloc(this.sectionTable, capacity);
        MemoryUtil.memSet(MemoryUtil.memAddress(this.sectionTable) + oldCapacity, 0, capacity - oldCapacity);
        this.snapshotDirty = true;
    }

    private void writeSectionSlot(int slot, BlockPos origin) {
        int offset = Math.multiplyExact(slot, SECTION_RECORD_BYTES);
        this.sectionTable.putInt(offset, origin.getX());
        this.sectionTable.putInt(offset + Integer.BYTES, origin.getY());
        this.sectionTable.putInt(offset + Integer.BYTES * 2, origin.getZ());
        this.sectionTable.putInt(offset + Integer.BYTES * 3, Float.floatToRawIntBits(FULL_VISIBILITY));
        markSectionDirty(slot);
    }

    private void clearSectionSlot(int slot) {
        int offset = Math.multiplyExact(slot, SECTION_RECORD_BYTES);
        if (offset < this.sectionTable.capacity()) {
            MemoryUtil.memSet(MemoryUtil.memAddress(this.sectionTable) + offset, 0, SECTION_RECORD_BYTES);
            markSectionDirty(slot);
        }
    }

    public void buildVisibleRegions(IntSet visibleSlots, RegionVisibilityIndex out) {
        RenderSystem.assertOnRenderThread();
        out.clear();
        IntIterator it = visibleSlots.intIterator();
        while (it.hasNext()) {
            int slot = it.nextInt();
            SectionRecord record = this.sections.get(slot);
            if (record != null) {
                out.add(record.regionKey(), slot);
            }
        }
        out.sort();
    }

    private void markSectionDirty(int slot) {
        this.dirtySectionSlots.add(slot);
        this.sectionRevisionPending = true;
    }

    private void settleSectionRevision() {
        if (this.sectionRevisionPending) {
            this.sectionRevision = Math.incrementExact(this.sectionRevision);
            this.sectionRevisionPending = false;
        }
    }

    public static long regionKey(BlockPos origin) {
        int regionX = origin.getX() >> (BLOCKS_TO_SECTION_SHIFT + REGION_SHIFT_X);
        int regionY = origin.getY() >> (BLOCKS_TO_SECTION_SHIFT + REGION_SHIFT_Y);
        int regionZ = origin.getZ() >> (BLOCKS_TO_SECTION_SHIFT + REGION_SHIFT_Z);
        return ((long) regionX & REGION_KEY_MASK) << REGION_KEY_SHIFT_X
            | ((long) regionZ & REGION_KEY_MASK) << REGION_KEY_SHIFT_Z
            | ((long) regionY & REGION_KEY_MASK);
    }

    public long regionKeyFor(int slot) {
        SectionRecord record = this.sections.get(slot);
        return record != null ? record.regionKey() : UNKNOWN_REGION_KEY;
    }

    public record Snapshot(
        ByteBuffer sectionTable,
        Collection<DrawGroup> groups,
        int[] dirtySectionSlots,
        long sectionRevision
    ) {
    }

    public static final class DrawGroup implements AutoCloseable {
        private static final long NO_PREPARED_REVISION = -1L;

        private final GroupKey key;
        private final Int2ObjectOpenHashMap<DrawRecord> draws = new Int2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<List<DrawRecord>> drawsByRegion = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<RegionBatch> batches = new Long2ObjectOpenHashMap<>();
        private final List<RegionBatch> preparedBatches = new ArrayList<>();
        private final IndirectCommandBuffer commandTemplate = new IndirectCommandBuffer();
        private final PersistentBufferUploader gpuCommandTemplate = new PersistentBufferUploader(
            () -> "Mango terrain persistent indirect template",
            COMMAND_TEMPLATE_USAGE
        );
        private boolean templateDirty = true;
        private boolean gpuTemplateDirty = true;
        private long preparedVisibleRevision = NO_PREPARED_REVISION;
        private long preparedSectionRevision = NO_PREPARED_REVISION;
        private int maxIndexCount;
        private int preparedCommandCount;

        private DrawGroup(GroupKey key) {
            this.key = key;
        }

        public GroupKey key() {
            return this.key;
        }

        public int drawCount() {
            return this.draws.size();
        }

        public int prepareVisibleCommands(
            RegionVisibilityIndex visibility,
            long visibleRevision,
            long sectionRevision,
            boolean cacheAcrossFrames
        ) {
            if (cacheAcrossFrames
                && visibleRevision != TerrainFrame.NO_VISIBLE_SECTIONS_REVISION
                && visibleRevision == this.preparedVisibleRevision
                && sectionRevision == this.preparedSectionRevision) {
                return this.preparedCommandCount;
            }
            this.preparedBatches.clear();
            this.preparedCommandCount = 0;
            if (visibility.regionCount() <= this.drawsByRegion.size()) {
                for (Long2ObjectMap.Entry<IntArrayList> entry : visibility.entries()) {
                    List<DrawRecord> regionDraws = this.drawsByRegion.get(entry.getLongKey());
                    if (regionDraws != null) {
                        prepareRegion(entry.getLongKey(), regionDraws, entry.getValue());
                    }
                }
            } else {
                for (Long2ObjectMap.Entry<List<DrawRecord>> entry
                    : Long2ObjectMaps.fastIterable(this.drawsByRegion)) {
                    IntArrayList visibleInRegion = visibility.slotsFor(entry.getLongKey());
                    if (visibleInRegion != null) {
                        prepareRegion(entry.getLongKey(), entry.getValue(), visibleInRegion);
                    }
                }
            }
            this.preparedVisibleRevision = visibleRevision;
            this.preparedSectionRevision = sectionRevision;
            return this.preparedCommandCount;
        }

        private void prepareRegion(
            long regionKey, List<DrawRecord> regionDraws, IntArrayList visibleInRegion
        ) {
            RegionBatch batch = this.batches.get(regionKey);
            if (batch == null) {
                batch = new RegionBatch();
                this.batches.put(regionKey, batch);
            }
            if (!batch.isValidFor(visibleInRegion)) {
                rebuildBatch(batch, regionDraws, visibleInRegion);
            }
            int drawCount = batch.drawCount();
            if (drawCount == 0) {
                return;
            }
            this.preparedBatches.add(batch);
            this.preparedCommandCount = Math.addExact(this.preparedCommandCount, drawCount);
        }

        public int writePreparedCommands(ByteBuffer destination) {
            int requiredBytes = Math.multiplyExact(
                this.preparedCommandCount, IndirectCommandBuffer.COMMAND_SIZE
            );
            if (destination.remaining() < requiredBytes) {
                throw new IllegalArgumentException(
                    "Terrain command destination is too small: " + destination.remaining() + " < "
                        + requiredBytes + " bytes for " + this.preparedCommandCount + " commands in "
                        + this.preparedBatches.size() + " regions"
                );
            }
            long address = MemoryUtil.memAddress(destination);
            int written = 0;
            for (RegionBatch batch : this.preparedBatches) {
                address += batch.copyCommandsTo(address);
                written += batch.drawCount();
            }
            return written;
        }

        private void rebuildBatch(
            RegionBatch batch, List<DrawRecord> regionDraws, IntArrayList visibleInRegion
        ) {
            IndirectCommandBuffer target = batch.commandBuffer();
            target.clear();
            target.ensureCapacity(
                Math.max(regionDraws.size(), MINIMUM_BATCH_CAPACITY)
            );
            int[] slots = visibleInRegion.elements();
            int slotCount = visibleInRegion.size();

            for (DrawRecord draw : regionDraws) {
                if (Arrays.binarySearch(slots, 0, slotCount, draw.sectionSlot()) < 0) {
                    continue;
                }
                target.addDraw(draw.indexCount(), draw.firstIndex(), draw.baseVertex(), draw.sectionSlot());
            }
            batch.markBuilt(visibleInRegion);
        }

        public int maxIndexCount() {
            return this.maxIndexCount;
        }

        public ByteBuffer commandTemplate() {
            rebuildTemplateIfDirty();
            return commandTemplateSlice();
        }

        public GpuBufferSlice gpuCommandTemplate() {
            rebuildTemplateIfDirty();
            if (this.commandTemplate.drawCount() == 0) {
                throw new IllegalStateException("Terrain draw group has no commands to upload");
            }
            if (this.gpuTemplateDirty || !this.gpuCommandTemplate.hasBuffer()) {
                this.gpuCommandTemplate.upload(commandTemplateSlice());
                this.gpuTemplateDirty = false;
            }
            return this.gpuCommandTemplate.slice();
        }

        public boolean isEmpty() {
            return this.draws.isEmpty();
        }

        @Override
        public void close() {
            this.commandTemplate.close();
            this.gpuCommandTemplate.close();
            this.draws.clear();
            this.drawsByRegion.clear();
            this.preparedBatches.clear();
            this.preparedCommandCount = 0;
            this.preparedVisibleRevision = NO_PREPARED_REVISION;
            this.preparedSectionRevision = NO_PREPARED_REVISION;
            for (RegionBatch batch : this.batches.values()) {
                batch.close();
            }
            this.batches.clear();
        }

        private void put(DrawRecord draw) {
            this.draws.put(draw.sectionSlot(), draw);
            this.drawsByRegion
                .computeIfAbsent(draw.regionKey(), unused -> new ArrayList<>())
                .add(draw);
            invalidateRegion(draw.regionKey());
            this.maxIndexCount = Math.max(this.maxIndexCount, draw.indexCount());
            markTemplateDirty();
        }

        private void remove(long regionKey, int sectionSlot) {
            if (this.draws.remove(sectionSlot) == null) {
                return;
            }
            List<DrawRecord> regionDraws = this.drawsByRegion.get(regionKey);
            if (regionDraws != null) {
                for (int drawIndex = 0; drawIndex < regionDraws.size(); drawIndex++) {
                    if (regionDraws.get(drawIndex).sectionSlot() == sectionSlot) {
                        regionDraws.remove(drawIndex);
                        break;
                    }
                }
                if (regionDraws.isEmpty()) {
                    this.drawsByRegion.remove(regionKey);
                    RegionBatch removed = this.batches.remove(regionKey);
                    if (removed != null) {
                        removed.close();
                    }
                }
            }
            invalidateRegion(regionKey);
            markTemplateDirty();
        }

        private void markTemplateDirty() {
            this.templateDirty = true;
            this.gpuTemplateDirty = true;
        }

        private void invalidateRegion(long regionKey) {
            this.preparedBatches.clear();
            this.preparedCommandCount = 0;
            this.preparedVisibleRevision = NO_PREPARED_REVISION;
            this.preparedSectionRevision = NO_PREPARED_REVISION;
            RegionBatch batch = this.batches.get(regionKey);
            if (batch != null) {
                batch.markGeometryDirty();
            }
        }

        private void rebuildTemplateIfDirty() {
            if (!this.templateDirty) {
                return;
            }
            this.commandTemplate.clear();
            this.commandTemplate.ensureCapacity(Math.max(this.draws.size(), MINIMUM_BATCH_CAPACITY));
            for (DrawRecord draw : this.draws.values()) {
                this.commandTemplate.addDraw(draw.indexCount(), draw.firstIndex(), draw.baseVertex(), draw.sectionSlot());
            }
            this.templateDirty = false;
        }

        private ByteBuffer commandTemplateSlice() {
            int commandBytes = Math.multiplyExact(this.commandTemplate.drawCount(), IndirectCommandBuffer.COMMAND_SIZE);
            return this.commandTemplate.buffer().slice(0, commandBytes);
        }
    }

    private record DrawRecord(
        int sectionSlot,
        int indexCount,
        int firstIndex,
        int baseVertex,
        long regionKey,
        GroupKey groupKey
    ) {
    }

    public record GroupKey(
        ChunkSectionLayer layer,
        GpuBuffer vertexBuffer,
        @Nullable GpuBuffer indexBuffer,
        @Nullable IndexType indexType
    ) {
        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof GroupKey other)) {
                return false;
            }
            return this.layer == other.layer
                && this.vertexBuffer == other.vertexBuffer
                && sameIdentity(this.indexBuffer, other.indexBuffer)
                && sameTypeIdentity(this.indexType, other.indexType);
        }

        @Override
        public int hashCode() {
            int result = this.layer.hashCode();
            result = HASH_MULTIPLIER * result + System.identityHashCode(this.vertexBuffer);
            int indexBufferHash = this.indexBuffer != null
                ? System.identityHashCode(this.indexBuffer)
                : 0;
            result = HASH_MULTIPLIER * result + indexBufferHash;
            int indexTypeHash = this.indexType != null ? this.indexType.hashCode() : 0;
            result = HASH_MULTIPLIER * result + indexTypeHash;
            return result;
        }

        private static boolean sameIdentity(@Nullable GpuBuffer first, @Nullable GpuBuffer second) {
            return first == null ? second == null : second != null && first == second;
        }

        private static boolean sameTypeIdentity(@Nullable IndexType first, @Nullable IndexType second) {
            return first == null ? second == null : first == second;
        }
    }

    private record SectionRecord(
        List<DrawRecord> draws,
        long regionKey,
        int originX,
        int originY,
        int originZ
    ) {
    }

    private sealed interface RegistryEvent permits SectionChangedEvent, DispatcherDisposedEvent {
    }

    private record SectionChangedEvent(
        SectionRenderDispatcher dispatcher,
        SectionRenderDispatcher.RenderSection section
    ) implements RegistryEvent {
    }

    private record DispatcherDisposedEvent(
        SectionRenderDispatcher dispatcher
    ) implements RegistryEvent {
    }

}
