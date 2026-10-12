package org.taumc.celeritas.impl.render.terrain;

// Hestia build (2026-10-12): the 1.8.9 branch only, with gen2 names. Block entities are drawn with a reused context
// object instead of a boxed Float per frame; -Dceleritas.enableGLDebug=true installs OpenGL's debug output; no sprite
// transparency tracker or sky-light section count (pre-1.8 only).

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.embeddedt.embeddium.impl.gl.debug.GLDebug;
import org.embeddedt.embeddium.impl.gl.device.CommandList;
import org.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import org.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import org.taumc.celeritas.impl.extensions.RenderGlobalExtension;
import org.taumc.celeritas.impl.render.terrain.fog.GLStateManagerFogService;
import org.taumc.celeritas.impl.render.terrain.matrix.PrimitiveChunkMatrixGetter;

import java.util.List;
import java.util.Objects;

/**
 * Provides an extension to vanilla's world renderer.
 */
public class CeleritasWorldRenderer extends SimpleWorldRenderer<World, PrimitiveRenderSectionManager, Object, BlockEntity, CeleritasWorldRenderer.BlockEntityRenderContext> {
    /** What vanilla's block entity renderer needs per frame; one instance, filled in before each use. */
    public static final class BlockEntityRenderContext {
        public float partialTicks;
    }

    private final BlockEntityRenderContext blockEntityRenderContext = new BlockEntityRenderContext();

    private static boolean glDebugInstalled;

    @Override
    protected void loadWorld(World world) {
        // -Dceleritas.enableGLDebug=true (upstream's switch) prints OpenGL's debug messages, for development. The
        // first world load is the first point where the game's GL context is certain to be current.
        if (!glDebugInstalled && Boolean.getBoolean("celeritas.enableGLDebug")) {
            glDebugInstalled = true;
            GLDebug.setupDebugMessageCallback();
        }
        super.loadWorld(world);
    }

    /**
     * @return The CeleritasWorldRenderer based on the current dimension
     */
    public static CeleritasWorldRenderer instance() {
        var instance = instanceNullable();

        if (instance == null) {
            throw new IllegalStateException("No renderer attached to active world");
        }

        return instance;
    }

    /**
     * @return The CeleritasWorldRenderer based on the current dimension, or null if none is attached
     */
    public static CeleritasWorldRenderer instanceNullable() {
        var world = Minecraft.getInstance().worldRenderer;

        if (world instanceof RenderGlobalExtension extension) {
            return extension.sodium$getWorldRenderer();
        }

        return null;
    }

    public static CameraState captureCameraState(double ticks) {
        Entity viewEntity = Minecraft.getInstance().getCamera();

        Objects.requireNonNull(viewEntity, "Client must have view entity");

        double x = viewEntity.prevX + (viewEntity.x - viewEntity.prevX) * ticks;
        double y = viewEntity.prevY + (viewEntity.y - viewEntity.prevY) * ticks + (double) viewEntity.getEyeHeight();
        double z = viewEntity.prevZ + (viewEntity.z - viewEntity.prevZ) * ticks;

        float pitch = viewEntity.pitch;
        float yaw = viewEntity.yaw;
        float fogDistance = GLStateManagerFogService.INSTANCE.getFogCutoff();

        return new CameraState(x, y, z, pitch, yaw, fogDistance);
    }

    public int renderBlockEntities(float partialTicks) {
        this.blockEntityRenderContext.partialTicks = partialTicks;
        return this.renderBlockEntities(this.blockEntityRenderContext);
    }

    @Override
    public int getEffectiveRenderDistance() {
        return Minecraft.getInstance().options.viewDistance;
    }

    @Override
    public int getMinimumBuildHeight() {
        return 0;
    }

    @Override
    public int getMaximumBuildHeight() {
        return this.world.getHeight();
    }

    @Override
    protected ChunkRenderMatrices createChunkRenderMatrices() {
        return PrimitiveChunkMatrixGetter.getMatrices();
    }

    @Override
    protected PrimitiveRenderSectionManager createRenderSectionManager(CommandList commandList) {
        return PrimitiveRenderSectionManager.create(ChunkMeshFormats.VANILLA_LIKE, this.world, this.renderDistance, commandList);
    }

    @Override
    protected void renderBlockEntityList(List<BlockEntity> list, BlockEntityRenderContext context) {
        float partialTicks = context.partialTicks;
        for (int i = 0, n = list.size(); i < n; i++) {
            var blockEntity = list.get(i);
            try {
                BlockEntityRenderDispatcher.INSTANCE.render(blockEntity, partialTicks, -1);
            } catch(RuntimeException e) {
                if(blockEntity.isRemoved()) {
                    System.err.println("Suppressing crash from invalid tile entity");
                } else {
                    throw e;
                }
            }
        }
    }
}
