package org.taumc.celeritas.api;

// Hestia build (2026-10-13): new, for Hestia's entity culling. Sodium's entity culling asks its world renderer the same
// question (isBoxVisible) from EntityRenderer.shouldRender.

import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;

/** Whether the terrain's visibility search reached any chunk section a box touches, for skipping hidden entities. */
public final class SectionVisibility {
    private SectionVisibility() {
    }

    /**
     * Whether any chunk section the box (world coordinates; celeritas-common widens it by half a block) touches was
     * reached by the current frame's visibility search: false means the box lies only in sections hidden from the
     * camera, behind walls or underground. True when there is no world renderer, for boxes above or below the world,
     * and for very large boxes. Render thread, once the frame's terrain setup has run.
     */
    public static boolean isBoxVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        CeleritasWorldRenderer renderer = CeleritasWorldRenderer.instanceNullable();
        return renderer == null || renderer.isBoxVisible(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
