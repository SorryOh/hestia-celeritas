package org.taumc.celeritas.impl.render.terrain;

import org.embeddedt.embeddium.impl.render.viewport.CameraTransform;

/**
 * Hestia build 14 (2026-11-02): the chunk columns OptiFine 1.8.9 draws with a shader pack (TerrainPrograms
 * .flatRenderDistance; Kora's probe P10 mapped them): a column is drawn when its origin (lowest x and z) lies within the
 * render distance of the camera's block, measured flat, whatever the height.
 */
public final class FlatRenderDistance {
    private FlatRenderDistance() {
    }

    /** The camera's block on one axis: CameraTransform's integer part is truncated toward zero, its fraction signed. */
    private static int block(int integral, float fraction) {
        return fraction < 0.0f ? integral - 1 : integral;
    }

    private static boolean within(long dx, long dz, float distance) {
        return dx * dx + dz * dz <= (double) distance * distance;
    }

    /** Whether the column whose origin is ({@code x}, {@code z}), in blocks, lies within {@code distance}. */
    public static boolean column(CameraTransform camera, int x, int z, float distance) {
        return within(x - block(camera.intX, camera.fracX), z - block(camera.intZ, camera.fracZ), distance);
    }

    /** Whether any of the 8 × 8 columns of the region whose origin is ({@code x}, {@code z}) lies within it. */
    public static boolean region(CameraTransform camera, int x, int z, float distance) {
        int cx = block(camera.intX, camera.fracX);
        int cz = block(camera.intZ, camera.fracZ);
        return within(nearestOrigin(x, cx) - cx, nearestOrigin(z, cz) - cz, distance);
    }

    /** Of the origins {@code start + 16k} (k 0 to 7), the nearest to {@code c}. */
    private static int nearestOrigin(int start, int c) {
        int k = Math.round((c - start) / 16.0f);
        return start + Math.max(0, Math.min(7, k)) * 16;
    }
}
