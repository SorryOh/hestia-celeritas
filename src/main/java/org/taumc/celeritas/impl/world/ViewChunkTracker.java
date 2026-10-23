package org.taumc.celeritas.impl.world;

// Hestia build 10 (2026-10-23): written for 1.8.9; replaces the radius-1 tracker WorldMixin gave every world.

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkStatus;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTracker;
import org.embeddedt.embeddium.impl.util.PositionUtil;

/**
 * Which chunk columns get render sections, as vanilla 1.8.9 would draw them. Upstream's tracker makes a column ready
 * only when it and its eight neighbours are loaded (Sodium's rule, for chunks that are still being lit and
 * decorated). A 1.8.9 client builds any loaded chunk, reading a missing neighbour as air, and 1.8's protocol unloads a
 * column when a chunk packet arrives without sections (an all-air column sent on its own): around a minigame arena's
 * void, columns next to such a neighbour never became ready and stayed undrawn, at random. Here a loaded column is
 * ready by itself (radius 0).
 *
 * The visibility search also only passes through sections that exist, so a column without sections hides everything
 * behind it, where vanilla's view grid always has render chunks (empty while unloaded). So every column inside the
 * window around the camera (the search's reach, [moveWindow]) is ready too, loaded or not: an unloaded one's sections
 * are visually empty (no build, visible through), and the chunk events' rebuild of its area fills them when it loads.
 * Nothing happens per frame while the camera stays in its column.
 */
public final class ViewChunkTracker extends ChunkTracker {
    private final LongOpenHashSet loaded = new LongOpenHashSet();
    private int windowX;
    private int windowZ;
    private int windowRadius = -1;

    public ViewChunkTracker() {
        super(0);
    }

    @Override
    public void onChunkStatusAdded(int x, int z, int flags) {
        if (this.loaded.add(PositionUtil.packChunk(x, z)) && !this.inWindow(x, z)) {
            super.onChunkStatusAdded(x, z, ChunkStatus.FLAG_ALL);
        }
    }

    @Override
    public void onChunkStatusRemoved(int x, int z, int flags) {
        if (this.loaded.remove(PositionUtil.packChunk(x, z)) && !this.inWindow(x, z)) {
            super.onChunkStatusRemoved(x, z, ChunkStatus.FLAG_ALL);
        }
    }

    private boolean inWindow(int x, int z) {
        return this.windowRadius >= 0 && Math.abs(x - this.windowX) <= this.windowRadius && Math.abs(z - this.windowZ) <= this.windowRadius;
    }

    /**
     * The camera is in column ([cx], [cz]) and the search reaches [radius] columns: the columns that left the window and
     * aren't loaded lose their sections, the ones that came in get them. Render thread, before the frame's chunk events
     * are read.
     */
    public void moveWindow(int cx, int cz, int radius) {
        if (cx == this.windowX && cz == this.windowZ && radius == this.windowRadius) {
            return;
        }
        int ox = this.windowX;
        int oz = this.windowZ;
        int or = this.windowRadius;
        this.windowX = cx;
        this.windowZ = cz;
        this.windowRadius = radius;
        if (or >= 0) {
            for (int x = ox - or; x <= ox + or; x++) {
                for (int z = oz - or; z <= oz + or; z++) {
                    if (!this.inWindow(x, z) && !this.loaded.contains(PositionUtil.packChunk(x, z))) {
                        super.onChunkStatusRemoved(x, z, ChunkStatus.FLAG_ALL);
                    }
                }
            }
        }
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                boolean before = or >= 0 && Math.abs(x - ox) <= or && Math.abs(z - oz) <= or;
                if (!before && !this.loaded.contains(PositionUtil.packChunk(x, z))) {
                    super.onChunkStatusAdded(x, z, ChunkStatus.FLAG_ALL);
                }
            }
        }
    }
}
