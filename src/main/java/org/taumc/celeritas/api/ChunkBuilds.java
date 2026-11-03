package org.taumc.celeritas.api;

import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;
import org.taumc.celeritas.impl.render.terrain.PrimitiveRenderSectionManager;

import java.util.concurrent.atomic.LongAdder;

/**
 * Chunk builds, for mods that need every chunk section the camera sees drawn as it is now. Hestia's video export
 * keeps a frame only once nothing is pending before and after it is drawn, holding the camera still meanwhile.
 *
 * <p>Hestia build 9 (2026-10-21). Hestia build 12 (2026-11-01): how many sections were built, for timing a full
 * rebuild (Kora's shader packs count the one a vertex format change causes). Hestia build 20 (2026-11-03): the build
 * tasks timed on the worker threads while asked ({@link #setTiming}), for a chunk build budget (Kora's connected
 * textures: Windows counts a thread's CPU time in 15.6 ms ticks, too coarse for it).</p>
 */
public final class ChunkBuilds {
    private static volatile boolean timing;
    private static final LongAdder taskNanos = new LongAdder();
    private static final LongAdder tasks = new LongAdder();

    private ChunkBuilds() {
    }

    /**
     * Whether the terrain the camera sees is still being worked on: a visibility search due or running, sections
     * waiting to be built or sorted (from the last search, or asking for an update since), builds queued or running,
     * or finished builds not yet uploaded. False when no world renderer is attached. Render thread; allocates nothing.
     */
    public static boolean pending() {
        CeleritasWorldRenderer renderer = CeleritasWorldRenderer.instanceNullable();
        return renderer != null && renderer.isBuildWorkPending();
    }

    /** Sections whose new geometry was uploaded since the game started (every world renderer counted). Render thread. */
    public static long built() {
        return PrimitiveRenderSectionManager.builtSections;
    }

    /**
     * Times every section's build task from now on (true) or no longer (false, the default: a volatile read per task
     * then). The sums keep counting across calls; read them before and after.
     */
    public static void setTiming(boolean on) {
        timing = on;
    }

    public static boolean timing() {
        return timing;
    }

    /** The timed build tasks' time, in nanoseconds of wall time on their worker threads. Any thread. */
    public static long taskNanos() {
        return taskNanos.sum();
    }

    /** How many build tasks were timed (each section built, or cancelled partway, once). Any thread. */
    public static long tasksTimed() {
        return tasks.sum();
    }

    /** A timed build task's time: the meshing task, on its worker thread. */
    public static void addTask(long nanos) {
        taskNanos.add(nanos);
        tasks.increment();
    }
}
