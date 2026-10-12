package org.taumc.celeritas.api;

// Hestia build (2026-10-12): new, for Hestia's settings screen. Upstream's 1.8.9 branch had no options.

import org.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkBuilder;

/**
 * Settings another mod (Hestia's GUI) may change at any time, from any thread. Both apply when the world renderer is
 * created again: reload the chunks (F3+A) after changing them.
 */
public final class CeleritasSettings {
    /** Chunk builder threads; 0 picks a count from the CPU ({@link #autoBuilderThreads()}). */
    public static volatile int builderThreads = 0;

    /**
     * Whether chunks the fog hides completely are drawn, as vanilla does: at the corners of the render distance they
     * show as fog-coloured shapes against the sky. Celeritas otherwise searches a circle of the render distance and
     * skips sections behind the fog (fog occlusion), which leaves about a fifth fewer sections to draw.
     */
    public static volatile boolean drawFoggedChunks = false;

    private CeleritasSettings() {
    }

    /** The count celeritas-common's ChunkBuilder starts for {@link #builderThreads} 0 (its getOptimalThreadCount). */
    public static int autoBuilderThreads() {
        int max = ChunkBuilder.getMaxThreadCount();
        return Math.min(Math.max(Math.max(max / 3, max - 6), 1), 10);
    }

    /** The most threads celeritas-common's ChunkBuilder starts; larger {@link #builderThreads} are capped to it. */
    public static int maxBuilderThreads() {
        return ChunkBuilder.getMaxThreadCount();
    }
}
