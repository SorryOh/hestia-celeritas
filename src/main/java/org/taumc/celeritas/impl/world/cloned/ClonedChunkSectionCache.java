package org.taumc.celeritas.impl.world.cloned;

// Hestia build (2026-10-12): after Celeritas's 1.12.2 version. Render thread only, so no locking; a copy is also made
// again when its chunk or section object was replaced; expired copies are dropped oldest first without scanning the
// whole cache.

import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import net.minecraft.world.World;
import org.embeddedt.embeddium.impl.util.PositionUtil;

import java.util.concurrent.TimeUnit;

/**
 * Section copies shared by the rebuild tasks of neighbouring sections: each task needs the 27 sections around its own,
 * so one copy serves up to 27 tasks. A copy stays until its section is scheduled for a rebuild (every block, light or
 * chunk data change schedules the sections around it), it hasn't been used for a few seconds, or the cache is full.
 * All methods run on the render thread.
 */
public final class ClonedChunkSectionCache {
    private static final int MAX_ENTRIES = 1024;
    private static final long MAX_IDLE_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final World world;

    // In order of last use, oldest first.
    private final Long2ReferenceLinkedOpenHashMap<ClonedChunkSection> entries = new Long2ReferenceLinkedOpenHashMap<>();

    private long now = System.nanoTime();

    public ClonedChunkSectionCache(World world) {
        this.world = world;
    }

    /** The copy of the section at these section coordinates, made now if there is no current one. */
    public ClonedChunkSection acquire(int x, int y, int z) {
        if (y < 0 || y >= this.world.getHeight() >> 4) {
            return ClonedChunkSection.OUTSIDE_WORLD;
        }

        long key = PositionUtil.packSection(x, y, z);
        ClonedChunkSection section = this.entries.getAndMoveToLast(key);

        if (section == null || !section.isCurrent(this.world, x, z)) {
            section = ClonedChunkSection.create(this.world, x, y, z);

            if (!this.entries.containsKey(key)) {
                while (this.entries.size() >= MAX_ENTRIES) {
                    this.entries.removeFirst();
                }
            }

            this.entries.putAndMoveToLast(key, section);
        }

        section.lastUsedNanos = this.now;

        return section;
    }

    public void invalidate(int x, int y, int z) {
        this.entries.remove(PositionUtil.packSection(x, y, z));
    }

    /** Called once per frame: drops the copies that haven't been used for a while. */
    public void cleanup() {
        this.now = System.nanoTime();

        while (!this.entries.isEmpty()) {
            long oldest = this.entries.firstLongKey();
            if (this.now - this.entries.get(oldest).lastUsedNanos <= MAX_IDLE_NANOS) {
                break;
            }
            this.entries.removeFirst();
        }
    }

    public void clear() {
        this.entries.clear();
    }

    public int size() {
        return this.entries.size();
    }
}
