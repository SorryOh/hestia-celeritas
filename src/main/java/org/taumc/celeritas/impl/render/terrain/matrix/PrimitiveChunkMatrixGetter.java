package org.taumc.celeritas.impl.render.terrain.matrix;

// Hestia build (2026-10-12): uses the copies of the projection and model-view matrices that vanilla's Camera.update
// already reads once per frame, right after the camera transform is set up and before terrain is drawn; upstream's
// 1.8.9 branch read them again with two glGet calls on every terrain layer. The matrices and the result are reused.
// Hestia build 12 (2026-11-01): TerrainShadow's draws give their own matrices (the shadow camera's).

import net.minecraft.client.render.Camera;
import org.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import org.joml.Matrix4f;

public class PrimitiveChunkMatrixGetter {
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static final ChunkRenderMatrices MATRICES = new ChunkRenderMatrices(PROJECTION, MODEL_VIEW);

    private static float[] overrideProjection;
    private static float[] overrideModelView;

    /** Main thread only: the same object is returned and refilled on every call. */
    public static ChunkRenderMatrices getMatrices() {
        if (overrideProjection != null) {
            PROJECTION.set(overrideProjection);
            MODEL_VIEW.set(overrideModelView);
        } else {
            PROJECTION.set(0, Camera.PROJECTION_MATRIX_BUFFER);
            MODEL_VIEW.set(0, Camera.MODELVIEW_MATRIX_BUFFER);
        }
        return MATRICES;
    }

    /** The matrices the next terrain draws use instead of the camera's (column-major), or null for the camera's. */
    public static void override(float[] projection, float[] modelView) {
        overrideProjection = projection;
        overrideModelView = modelView;
    }
}
