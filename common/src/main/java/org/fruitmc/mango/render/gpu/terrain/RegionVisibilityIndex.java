package org.fruitmc.mango.render.gpu.terrain;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RegionVisibilityIndex {

    private final Long2ObjectOpenHashMap<IntArrayList> regions = new Long2ObjectOpenHashMap<>();
    private final List<IntArrayList> pool = new ArrayList<>();
    private int pooledInUse;

    public void clear() {
        this.regions.clear();
        this.pooledInUse = 0;
    }

    public void add(long regionKey, int slot) {
        IntArrayList slots = this.regions.get(regionKey);
        if (slots == null) {
            slots = acquire();
            this.regions.put(regionKey, slots);
        }
        slots.add(slot);
    }

    public void sort() {
        for (IntArrayList slots : this.regions.values()) {
            Arrays.sort(slots.elements(), 0, slots.size());
        }
    }

    @Nullable
    public IntArrayList slotsFor(long regionKey) {
        return this.regions.get(regionKey);
    }

    public int regionCount() {
        return this.regions.size();
    }

    public Iterable<Long2ObjectMap.Entry<IntArrayList>> entries() {
        return Long2ObjectMaps.fastIterable(this.regions);
    }

    private IntArrayList acquire() {
        if (this.pooledInUse < this.pool.size()) {
            IntArrayList reused = this.pool.get(this.pooledInUse++);
            reused.clear();
            return reused;
        }
        IntArrayList created = new IntArrayList();
        this.pool.add(created);
        this.pooledInUse++;
        return created;
    }
}
