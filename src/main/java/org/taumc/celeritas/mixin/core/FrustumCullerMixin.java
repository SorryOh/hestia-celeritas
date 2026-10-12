package org.taumc.celeritas.mixin.core;

// Hestia build (2026-10-12): the search gets a copy of vanilla's planes with the eye offset (CameraFrustum), and the
// matrix and vectors are reused instead of allocated every frame.

import net.minecraft.client.render.FrustumCuller;
import net.minecraft.client.render.FrustumData;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.embeddedt.embeddium.impl.render.viewport.ViewportProvider;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.taumc.celeritas.impl.render.terrain.CameraFrustum;

/**
 * Turns vanilla's per-frame frustum (built in GameRenderer and positioned at the camera entity's interpolated feet)
 * into Celeritas's viewport, positioned at the eye.
 */
@Mixin(FrustumCuller.class)
public class FrustumCullerMixin implements ViewportProvider {
    @Shadow
    private FrustumData frustum;

    @Shadow
    private double offsetX, offsetY, offsetZ;

    // Main thread only, reused every frame.
    @Unique
    private static final Matrix4f celeritas$modelMatrix = new Matrix4f();
    @Unique
    private static final Vector3f celeritas$eyeOffset = new Vector3f();
    @Unique
    private static final Vector3d celeritas$eyePosition = new Vector3d();
    // Used in turn: last frame's copy may still be read by the search thread until this frame's setup joins it.
    @Unique
    private static final CameraFrustum[] celeritas$frustums = { new CameraFrustum(), new CameraFrustum() };
    @Unique
    private static int celeritas$nextFrustum;

    @Override
    public Viewport sodium$createViewport() {
        Matrix4f modelMatrix = celeritas$modelMatrix.set(this.frustum.modelMatrix);
        modelMatrix.invert();
        Vector3f offset = modelMatrix.transformPosition(celeritas$eyeOffset.zero());
        CameraFrustum cullTester = celeritas$frustums[celeritas$nextFrustum].set(this.frustum.frustum, offset.x, offset.y, offset.z);
        celeritas$nextFrustum ^= 1;
        return new Viewport(cullTester,
                celeritas$eyePosition.set(this.offsetX + offset.x, this.offsetY + offset.y, this.offsetZ + offset.z));
    }
}
