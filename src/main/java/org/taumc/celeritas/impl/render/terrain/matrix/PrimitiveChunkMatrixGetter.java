package org.taumc.celeritas.impl.render.terrain.matrix;

// Hestia build (2026-10-12): uses the copies of the projection and model-view matrices that vanilla's Camera.update
// already reads once per frame, right after the camera transform is set up and before terrain is drawn; upstream's
// 1.8.9 branch read them again with two glGet calls on every terrain layer. The matrices and the result are reused.

import net.minecraft.client.render.Camera;
import org.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import org.joml.Matrix4f;

public class PrimitiveChunkMatrixGetter {
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static final ChunkRenderMatrices MATRICES = new ChunkRenderMatrices(PROJECTION, MODEL_VIEW);

    /** Main thread only: the same object is returned and refilled on every call. */
    public static ChunkRenderMatrices getMatrices() {
        PROJECTION.set(0, Camera.PROJECTION_MATRIX_BUFFER);
        MODEL_VIEW.set(0, Camera.MODELVIEW_MATRIX_BUFFER);
        return MATRICES;
    }
}
