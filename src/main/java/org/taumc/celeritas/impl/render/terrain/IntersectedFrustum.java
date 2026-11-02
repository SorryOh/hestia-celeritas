package org.taumc.celeritas.impl.render.terrain;

// Hestia build 16 (2026-11-02): new, for TerrainShadow.setCameraCulling.

import org.embeddedt.embeddium.impl.render.viewport.frustum.Frustum;

/**
 * The boxes inside both of two frusta (testing boxes relative to the same position): the shadow camera's and the
 * camera's, for a shadow search that keeps only the sections the camera sees, as OptiFine 1.8.9's shadow map does.
 */
public final class IntersectedFrustum implements Frustum {
    private Frustum first, second;

    public IntersectedFrustum set(Frustum first, Frustum second) {
        this.first = first;
        this.second = second;
        return this;
    }

    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return this.intersectAab(minX, minY, minZ, maxX, maxY, maxZ) != OUTSIDE;
    }

    @Override
    public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int a = this.first.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);
        if (a == OUTSIDE) {
            return OUTSIDE;
        }
        int b = this.second.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);
        if (b == OUTSIDE) {
            return OUTSIDE;
        }
        return a == FULLY_INSIDE && b == FULLY_INSIDE ? FULLY_INSIDE : PARTIALLY_INSIDE;
    }
}
