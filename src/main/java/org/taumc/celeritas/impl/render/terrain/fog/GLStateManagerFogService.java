package org.taumc.celeritas.impl.render.terrain.fog;

// Hestia build (2026-10-12): reads GlStateManager's cached fog state and GameRenderer's fog colour, as Celeritas's
// 1.12.2 version does. Upstream's 1.8.9 branch asked the driver with five glGet calls on every terrain pass, each of
// which waits for the driver. The colour array is reused instead of allocated per call.

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import org.embeddedt.embeddium.impl.render.chunk.fog.FogService;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkFogMode;

public class GLStateManagerFogService implements FogService {
    public static final GLStateManagerFogService INSTANCE = new GLStateManagerFogService();

    private final float[] fogColor = new float[] {0.0F, 0.0F, 0.0F, 1.0F};

    @Override
    public float getFogEnd() {
        return GlStateManager.FOG.end;
    }

    @Override
    public float getFogStart() {
        return GlStateManager.FOG.start;
    }

    @Override
    public float getFogDensity() {
        return GlStateManager.FOG.density;
    }

    @Override
    public int getFogShapeIndex() {
        return 0;
    }

    @Override
    public float getFogCutoff() {
        return GlStateManager.FOG.end;
    }

    /** Main thread only: the same array is returned and refilled on every call. */
    @Override
    public float[] getFogColor() {
        GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
        float[] color = this.fogColor;
        color[0] = gameRenderer.fogRed;
        color[1] = gameRenderer.fogGreen;
        color[2] = gameRenderer.fogBlue;
        return color;
    }

    @Override
    public ChunkFogMode getFogMode() {
        if (!GlStateManager.FOG.state.enabled) {
            return ChunkFogMode.NONE;
        }
        return ChunkFogMode.fromGLMode(GlStateManager.FOG.mode);
    }
}
