package org.fruitmc.mango.render.gpu.skinning;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public final class BonePalette implements AutoCloseable {

    private static final int FLOAT_BYTES = Float.BYTES;
    private static final int MATRIX_FLOATS = 16;
    public static final int MATRIX_BYTES = MATRIX_FLOATS * FLOAT_BYTES;
    private static final int KIB_BYTES = 1024;
    private static final int MIB_BYTES = KIB_BYTES * KIB_BYTES;
    private static final int INITIAL_CAPACITY_BYTES = 256 * KIB_BYTES;
    private static final int MAX_CAPACITY_BYTES = 64 * MIB_BYTES;
    private static final int MAX_BONE_ENTRIES = MAX_CAPACITY_BYTES / MATRIX_BYTES;
    private static final int GROWTH_FACTOR = 2;
    private static final int FIRST_FRAME_INDEX = 1;
    private static final int MISSING_PAGE_INDEX = -1;
    private static final int PAGE_RETENTION_FRAMES = 120;
    public static final int NO_PAGE_OFFSET = -1;

    private final IntOpenHashSet dirtyRecords = new IntOpenHashSet();
    private final Long2ObjectOpenHashMap<Page> pages = new Long2ObjectOpenHashMap<>();
    private final List<Page> freePages = new ArrayList<>();
    private final List<Page> transientPages = new ArrayList<>();
    private ByteBuffer buffer;
    private FloatBuffer floatView;
    private int entryCount;
    private int frameIndex;
    private long revision;

    public BonePalette() {
        this.buffer = allocateBuffer(INITIAL_CAPACITY_BYTES);
        this.floatView = this.buffer.asFloatBuffer();
    }

    public void beginFrame() {
        this.frameIndex++;
        if (this.frameIndex < FIRST_FRAME_INDEX) {
            this.frameIndex = FIRST_FRAME_INDEX;
        }
        this.dirtyRecords.clear();
        this.freePages.addAll(this.transientPages);
        this.transientPages.clear();
        releaseUnusedPages();
    }

    public boolean hasRoom(int boneCount) {
        return boneCount > 0 && boneCount <= MAX_BONE_ENTRIES;
    }

    public boolean canStorePage(long pageKey, int boneCount) {
        if (!hasRoom(boneCount)) {
            return false;
        }
        if (this.pages.containsKey(pageKey) && this.pages.get(pageKey).boneCount == boneCount) {
            return true;
        }
        if (findFreePageIndex(boneCount) != MISSING_PAGE_INDEX) {
            return true;
        }
        return boneCount <= MAX_BONE_ENTRIES - this.entryCount;
    }

    public boolean canStoreTransientPage(int boneCount) {
        if (!hasRoom(boneCount)) {
            return false;
        }
        if (findFreePageIndex(boneCount) != MISSING_PAGE_INDEX) {
            return true;
        }
        return boneCount <= MAX_BONE_ENTRIES - this.entryCount;
    }

    public int addInstance(float[] matrices, int boneCount) {
        validateMatrices(matrices, boneCount);
        Page page = takeFreePageOrAllocate(boneCount);
        page.boneCount = boneCount;
        page.lastUsedFrame = this.frameIndex;
        this.transientPages.add(page);
        writePage(page.offsetEntries, matrices, boneCount);
        return page.offsetEntries;
    }

    public OptionalInt findReusablePage(long pageKey, long fingerprint, int boneCount, int maxFrameAge) {
        int offset = findReusablePageOffset(pageKey, fingerprint, boneCount, maxFrameAge);
        if (offset == NO_PAGE_OFFSET) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(offset);
    }

    public int findReusablePageOffset(long pageKey, long fingerprint, int boneCount, int maxFrameAge) {
        Page page = this.pages.get(pageKey);
        if (page == null) {
            return NO_PAGE_OFFSET;
        }
        if (page.boneCount != boneCount || page.fingerprint != fingerprint) {
            return NO_PAGE_OFFSET;
        }
        if (this.frameIndex - page.lastUsedFrame > maxFrameAge) {
            return NO_PAGE_OFFSET;
        }
        page.lastUsedFrame = this.frameIndex;
        return page.offsetEntries;
    }

    public int putPersistentPage(long pageKey, long fingerprint, float[] matrices, int boneCount) {
        validateMatrices(matrices, boneCount);
        Page page = pageFor(pageKey, boneCount);
        page.fingerprint = fingerprint;
        page.lastUsedFrame = this.frameIndex;
        writePage(page.offsetEntries, matrices, boneCount);
        return page.offsetEntries;
    }

    public int frameIndex() {
        return this.frameIndex;
    }

    public int[] dirtyRecords() {
        return this.dirtyRecords.toIntArray();
    }

    public long revision() {
        return this.revision;
    }

    public void clear() {
        this.pages.clear();
        this.freePages.clear();
        this.transientPages.clear();
        this.dirtyRecords.clear();
        this.entryCount = 0;
        this.frameIndex = 0;
        this.revision++;
    }

    public int entryCount() {
        return this.entryCount;
    }

    public int dataBytes() {
        return this.entryCount * MATRIX_BYTES;
    }

    public ByteBuffer dataSlice() {
        return this.buffer.slice(0, dataBytes());
    }

    @Override
    public void close() {
        MemoryUtil.memFree(this.buffer);
    }

    private void validateMatrices(float[] matrices, int boneCount) {
        int requiredFloats = Math.multiplyExact(boneCount, MATRIX_FLOATS);
        if (matrices.length < requiredFloats) {
            throw new IllegalArgumentException(
                "Bone matrix input too short: " + matrices.length + " < " + requiredFloats
            );
        }
        if (!hasRoom(boneCount)) {
            throw new IllegalStateException("Bone palette capacity exceeded: requested " + boneCount + " entries");
        }
    }

    private void writePage(int offsetEntries, float[] matrices, int boneCount) {
        int requiredFloats = Math.multiplyExact(boneCount, MATRIX_FLOATS);
        int destinationFloatOffset = Math.multiplyExact(offsetEntries, MATRIX_FLOATS);
        this.floatView.position(destinationFloatOffset);
        MemoryUtil.memCopy(matrices, this.floatView, 0, requiredFloats);

        for (int bone = 0; bone < boneCount; bone++) {
            this.dirtyRecords.add(offsetEntries + bone);
        }
        this.revision++;
    }

    private Page pageFor(long pageKey, int boneCount) {
        if (this.pages.containsKey(pageKey)) {
            Page page = this.pages.get(pageKey);
            if (page.boneCount == boneCount) {
                return page;
            }
            this.pages.remove(pageKey);
            this.freePages.add(page);
        }

        Page page = takeFreePageOrAllocate(boneCount);
        page.boneCount = boneCount;
        this.pages.put(pageKey, page);
        return page;
    }

    private int findFreePageIndex(int boneCount) {
        for (int index = 0; index < this.freePages.size(); index++) {
            if (this.freePages.get(index).boneCount == boneCount) {
                return index;
            }
        }
        return MISSING_PAGE_INDEX;
    }

    private Page takeFreePageOrAllocate(int boneCount) {
        int index = findFreePageIndex(boneCount);
        if (index == MISSING_PAGE_INDEX) {
            return allocatePage(boneCount);
        }
        return this.freePages.remove(index);
    }

    private Page allocatePage(int boneCount) {
        if (boneCount > MAX_BONE_ENTRIES - this.entryCount) {
            throw new IllegalStateException(
                "Bone palette capacity exceeded: requested " + boneCount
                    + " entries with " + this.entryCount + " already allocated"
            );
        }
        int offsetEntries = this.entryCount;
        int requiredBytes = Math.multiplyExact(offsetEntries + boneCount, MATRIX_BYTES);
        ensureCapacity(requiredBytes);
        this.entryCount += boneCount;
        return new Page(offsetEntries, boneCount, this.frameIndex);
    }

    private void releaseUnusedPages() {
        LongArrayList expiredKeys = new LongArrayList();
        for (Long2ObjectMap.Entry<Page> entry : this.pages.long2ObjectEntrySet()) {
            Page page = entry.getValue();
            if (this.frameIndex - page.lastUsedFrame > PAGE_RETENTION_FRAMES) {
                expiredKeys.add(entry.getLongKey());
            }
        }
        for (int index = 0; index < expiredKeys.size(); index++) {
            Page page = this.pages.remove(expiredKeys.getLong(index));
            if (page != null) {
                this.freePages.add(page);
            }
        }
    }

    private void ensureCapacity(int requiredBytes) {
        if (requiredBytes <= this.buffer.capacity()) {
            return;
        }

        int newCapacity = this.buffer.capacity();
        while (newCapacity < requiredBytes) {
            newCapacity *= GROWTH_FACTOR;
        }
        newCapacity = Math.min(newCapacity, MAX_CAPACITY_BYTES);
        if (newCapacity < requiredBytes) {
            throw new IllegalStateException(
                "Bone palette capacity exceeded: " + requiredBytes + " > " + MAX_CAPACITY_BYTES
            );
        }

        ByteBuffer replacement = allocateBuffer(newCapacity);
        replacement.put(this.buffer.slice(0, dataBytes()));
        replacement.position(0);
        MemoryUtil.memFree(this.buffer);
        this.buffer = replacement;
        this.floatView = this.buffer.asFloatBuffer();
    }

    private static ByteBuffer allocateBuffer(int bytes) {
        return MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
    }

    private static final class Page {
        private final int offsetEntries;
        private int boneCount;
        private long fingerprint;
        private int lastUsedFrame;

        private Page(int offsetEntries, int boneCount, int lastUsedFrame) {
            this.offsetEntries = offsetEntries;
            this.boneCount = boneCount;
            this.lastUsedFrame = lastUsedFrame;
        }
    }
}
