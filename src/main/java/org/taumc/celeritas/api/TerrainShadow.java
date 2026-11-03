package org.taumc.celeritas.api;

// Hestia build 12 (2026-11-01): new, for Kora's shader packs.
// Hestia build 16 (2026-11-02): setCameraCulling (the shadow search kept to the camera's frustum, as OptiFine's).
// Hestia build 21 (2026-11-03): setup with more planes than the shadow camera's six, and its search's lists drawn in
// the same frame when asked.

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Culler;
import net.minecraft.client.render.block.BlockLayer;
import net.minecraft.entity.Entity;
import org.embeddedt.embeddium.impl.gl.device.RenderDevice;
import org.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.embeddedt.embeddium.impl.render.viewport.ViewportProvider;
import org.joml.Vector3d;
import org.taumc.celeritas.impl.render.terrain.CameraFrustum;
import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;
import org.taumc.celeritas.impl.render.terrain.IntersectedFrustum;
import org.taumc.celeritas.impl.render.terrain.PrimitiveRenderSectionManager;
import org.taumc.celeritas.impl.render.terrain.matrix.PrimitiveChunkMatrixGetter;

/**
 * A shadow pass over Celeritas's terrain, for shader packs: celeritas-common's second visibility search (the sections
 * in the shadow camera's frustum, without occlusion or fog culling, kept apart from the camera's lists) and draws of
 * its lists with the provider's "shadow_" programs ({@link TerrainPrograms.Provider#program}). It needs a world
 * renderer made with {@link TerrainPrograms#setShadowPass} on. Each frame, on the render thread: {@link #setup} once,
 * after the camera transform is set up and before vanilla's terrain setup ({@code WorldRenderer.setupRender}), then
 * {@link #draw} per block layer and {@link #drawBlockEntities}. The search runs on Celeritas's search thread like the
 * camera's, so its lists follow the shadow camera one frame late, unless {@link #setup} is asked to wait for it.
 */
public final class TerrainShadow {
    private TerrainShadow() {
    }

    private static boolean active;

    // Used in turn, as the camera's: last frame's may still be read by the search thread until this frame's setup joins it.
    private static final CameraFrustum[] FRUSTUMS = { new CameraFrustum(), new CameraFrustum() };
    private static final IntersectedFrustum[] INTERSECTED = { new IntersectedFrustum(), new IntersectedFrustum() };
    private static int nextFrustum;
    private static final Vector3d POSITION = new Vector3d();
    private static volatile boolean cameraCulling;

    /**
     * Keeps the shadow search to the sections the camera's frustum holds too (off by default), as OptiFine 1.8.9's
     * shadow map holds only what the camera sees: for comparing with it; the complete search is the right one.
     */
    public static void setCameraCulling(boolean on) {
        cameraCulling = on;
    }

    /** True while the shadow pass's search or draws run: Celeritas works on the shadow lists then. */
    public static boolean isActive() {
        return active;
    }

    /** Whether the world renderer has a shadow pass now (made with it on, and attached to a world). */
    public static boolean available() {
        PrimitiveRenderSectionManager manager = manager();
        return manager != null && manager.hasShadowPass();
    }

    private static PrimitiveRenderSectionManager manager() {
        CeleritasWorldRenderer renderer = CeleritasWorldRenderer.instanceNullable();
        return renderer != null ? renderer.getRenderSectionManager() : null;
    }

    /**
     * This frame's shadow search, and the camera's own search when it is due (Celeritas starts both here; vanilla's
     * terrain setup then only uploads and draws). {@code cameraCuller} is the frustum vanilla made for this frame (the
     * one {@code setupRender} gets); {@code planes} the shadow camera's six planes {a, b, c, d}, in the coordinates of
     * vanilla's frustum (relative to the camera entity's interpolated feet), a point being inside when
     * {@code a x + b y + c z + d > 0} for all six. Returns false, doing nothing, when there is no shadow pass.
     */
    public static boolean setup(Culler cameraCuller, float[][] planes, float tickDelta, boolean spectator) {
        return setup(cameraCuller, planes, 6, tickDelta, spectator, false);
    }

    /**
     * {@link #setup(Culler, float[][], float, boolean)} with the first {@code count} of {@code planes} (at most
     * {@link CameraFrustum#MAX_PLANES}; the shadow camera's six and any others): a section is searched when inside all,
     * so the caller can narrow the shadow set (Kora keeps the casters whose shadows can fall in the camera's view). With
     * {@code sameFrame}, setup waits for the search it started, and {@link #draw} draws its lists this frame instead of
     * the next (the camera's own search is waited for too when one started here).
     */
    public static boolean setup(Culler cameraCuller, float[][] planes, int count, float tickDelta, boolean spectator, boolean sameFrame) {
        CeleritasWorldRenderer renderer = CeleritasWorldRenderer.instanceNullable();
        PrimitiveRenderSectionManager manager = renderer != null ? renderer.getRenderSectionManager() : null;
        Entity view = Minecraft.getInstance().getCamera();
        if (manager == null || !manager.hasShadowPass() || view == null) {
            return false;
        }
        RenderDevice.enterManagedCode();
        try {
            Viewport camera = ((ViewportProvider) cameraCuller).sodium$createViewport();
            CameraTransform eye = camera.getTransform();
            // The searches pass boxes relative to the eye; the planes are relative to the feet.
            double feetX = view.prevX + (view.x - view.prevX) * tickDelta;
            double feetY = view.prevY + (view.y - view.prevY) * tickDelta;
            double feetZ = view.prevZ + (view.z - view.prevZ) * tickDelta;
            CameraFrustum frustum = FRUSTUMS[nextFrustum].set(planes, count, eye.x - feetX, eye.y - feetY, eye.z - feetZ);
            IntersectedFrustum both = INTERSECTED[nextFrustum].set(frustum, camera.getFrustum());
            nextFrustum ^= 1;
            // Both test boxes relative to the eye.
            Viewport shadow = new Viewport(cameraCulling ? both : frustum, POSITION.set(eye.x, eye.y, eye.z));
            active = true;
            try {
                renderer.setupShadowTerrain(camera, shadow, CeleritasWorldRenderer.captureCameraState(tickDelta), renderer.frame, spectator);
                if (sameFrame) {
                    // The searches just started, joined: their lists are this frame's (the next setup finds none pending).
                    manager.finishAllGraphUpdates();
                }
            } finally {
                active = false;
            }
        } finally {
            RenderDevice.exitManagedCode();
        }
        return true;
    }

    /**
     * Draws vanilla's {@code layer} from the shadow search's lists, each pass with the provider's program for
     * "shadow_" + its name, with these matrices (column-major, as GL's): the camera-relative positions go through
     * {@code modelView}, then {@code projection}.
     */
    public static void draw(BlockLayer layer, float tickDelta, float[] projection, float[] modelView) {
        CeleritasWorldRenderer renderer = CeleritasWorldRenderer.instanceNullable();
        PrimitiveRenderSectionManager manager = renderer != null ? renderer.getRenderSectionManager() : null;
        Entity view = Minecraft.getInstance().getCamera();
        if (manager == null || !manager.hasShadowPass() || view == null) {
            return;
        }
        double x = view.prevX + (view.x - view.prevX) * tickDelta;
        double y = view.prevY + (view.y - view.prevY) * tickDelta;
        double z = view.prevZ + (view.z - view.prevZ) * tickDelta;
        PrimitiveChunkMatrixGetter.override(projection, modelView);
        active = true;
        RenderDevice.enterManagedCode();
        try {
            renderer.drawChunkLayer(layer, x, y, z);
        } finally {
            RenderDevice.exitManagedCode();
            active = false;
            PrimitiveChunkMatrixGetter.override(null, null);
        }
    }

    /** The block entities of the shadow lists' sections and the global ones (beacons), as vanilla draws them. */
    public static int drawBlockEntities(float tickDelta) {
        CeleritasWorldRenderer renderer = CeleritasWorldRenderer.instanceNullable();
        PrimitiveRenderSectionManager manager = renderer != null ? renderer.getRenderSectionManager() : null;
        if (manager == null || !manager.hasShadowPass()) {
            return 0;
        }
        active = true;
        try {
            return renderer.renderBlockEntities(tickDelta);
        } finally {
            active = false;
        }
    }
}
