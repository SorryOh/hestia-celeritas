package org.taumc.celeritas.api;

import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;
import org.taumc.celeritas.impl.render.terrain.PrimitiveRenderSectionManager;

/**
 * Chunk builds, for mods that need every chunk section the camera sees drawn as it is now. Hestia's video export
 * keeps a frame only once nothing is pending before and after it is drawn, holding the camera still meanwhile.
 *
 * <p>Hestia build 9 (2026-10-21). Hestia build 12 (2026-11-01): how many sections were built, for timing a full
 * rebuild (Kora's shader packs count the one a vertex format change causes).</p>
 */
public final class ChunkBuilds {
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
}
