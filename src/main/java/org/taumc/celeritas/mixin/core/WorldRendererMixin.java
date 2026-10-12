package org.taumc.celeritas.mixin.core;

// Hestia build (2026-10-12): the 1.8.9 branch only, with gen2 names. setupRender uses the spectator flag vanilla passes;
// markDirty keeps vanilla's private access and ignores updates before a world is loaded; the entity loop reuses its
// BlockPos and reads the camera entity once per frame instead of once per entity.

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Culler;
import net.minecraft.client.render.block.BlockLayer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.client.render.world.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.living.LivingEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.embeddedt.embeddium.impl.gl.device.RenderDevice;
import org.embeddedt.embeddium.impl.render.viewport.ViewportProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.taumc.celeritas.impl.extensions.RenderGlobalExtension;
import org.taumc.celeritas.impl.render.entity.EntityGatherer;
import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;

import java.util.List;

/**
 * Hands vanilla's terrain rendering to Celeritas: terrain setup, the layer draws, chunk updates and dirty marking are
 * replaced; block entities and entities are drawn from Celeritas's sections and the client's chunk lists, since
 * vanilla's own visible-chunk list stays empty.
 */
@Mixin(value = WorldRenderer.class, priority = 900)
public abstract class WorldRendererMixin implements RenderGlobalExtension {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private int lastViewDistance;

    @Shadow
    public abstract void reload();

    @Shadow
    private ClientWorld world;

    @Shadow
    @Final
    private EntityRenderDispatcher entityRenderDispatcher;

    @Shadow
    private int renderedEntityCount;

    @Unique
    private CeleritasWorldRenderer renderer;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(Minecraft minecraft, CallbackInfo ci) {
        this.renderer = new CeleritasWorldRenderer();
    }

    @Override
    public CeleritasWorldRenderer sodium$getWorldRenderer() {
        return this.renderer;
    }

    @Inject(method = "setWorld", at = @At("RETURN"))
    private void onWorldChanged(ClientWorld world, CallbackInfo ci) {
        RenderDevice.enterManagedCode();

        try {
            this.renderer.setWorld(world);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    /**
     * @reason Redirect the chunk layer render passes to our renderer
     * @author JellySquid
     */
    @Overwrite
    public int render(BlockLayer layer, double ticks, int anaglyphRenderPass, Entity viewEntity) {
        RenderDevice.enterManagedCode();

        Lighting.turnOff();

        double d3 = viewEntity.prevX + (viewEntity.x - viewEntity.prevX) * ticks;
        // Do not apply eye height here or weird offsets will happen
        double d4 = viewEntity.prevY + (viewEntity.y - viewEntity.prevY) * ticks;
        double d5 = viewEntity.prevZ + (viewEntity.z - viewEntity.prevZ) * ticks;

        this.minecraft.gameRenderer.enableLightMap();

        try {
            this.renderer.drawChunkLayer(layer, d3, d4, d5);
        } finally {
            RenderDevice.exitManagedCode();
        }

        this.minecraft.gameRenderer.disableLightMap();

        return 1;
    }

    @Unique
    private int frame = 0;

    /**
     * @reason Redirect the terrain setup phase to our renderer
     * @author JellySquid
     */
    @Overwrite
    public void setupRender(Entity camera, double tickDelta, Culler culler, int frame, boolean spectator) {
        if (this.minecraft.options.viewDistance != this.lastViewDistance) {
            this.reload();
        }

        RenderDevice.enterManagedCode();

        try {
            this.renderer.setupTerrain(((ViewportProvider) culler).sodium$createViewport(),
                    CeleritasWorldRenderer.captureCameraState(tickDelta),
                    this.frame++, spectator, false);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    /**
     * @reason Redirect chunk updates to our renderer
     * @author JellySquid
     */
    @Overwrite
    private void markDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (this.renderer.getRenderSectionManager() != null) {
            this.renderer.scheduleRebuildForBlockArea(minX, minY, minZ, maxX, maxY, maxZ, false);
        }
    }

    @Inject(method = "reload()V", at = @At("RETURN"))
    private void onReload(CallbackInfo ci) {
        RenderDevice.enterManagedCode();

        try {
            this.renderer.reload();
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    /**
     * @reason Celeritas builds and uploads chunks during terrain setup
     * @author JellySquid
     */
    @Overwrite
    public void compileChunksUntil(long time) {

    }

    @Inject(method = "renderEntities", at = @At(value = "FIELD", target = "Lnet/minecraft/client/render/world/WorldRenderer;globalBlockEntities:Ljava/util/Set;", ordinal = 0))
    private void sodium$renderTileEntities(Entity camera, Culler culler, float partialTicks, CallbackInfo ci) {
        this.renderer.renderBlockEntities(partialTicks);
    }

    @Unique
    private final EntityGatherer celeritas$entityGatherer = new EntityGatherer();

    @Unique
    private final BlockPos.Mutable celeritas$entityBlockPos = new BlockPos.Mutable();

    @Inject(method = "renderEntities", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = "ldc=entities"))
    private void celeritas$renderEntities(Entity camera, Culler culler, float tickDelta, CallbackInfo ci, @Local(ordinal = 0) double d, @Local(ordinal = 1) double e, @Local(ordinal = 2) double g) {
        celeritas$entityGatherer.clear();
        List<Entity> entityList = celeritas$entityGatherer.getLoadedEntityList(this.world);

        BlockPos.Mutable entityBlockPos = this.celeritas$entityBlockPos;
        Entity cameraEntity = this.minecraft.getCamera();
        boolean isSelfSleeping = cameraEntity instanceof LivingEntity living && living.isSleeping();

        for (int i = 0, n = entityList.size(); i < n; i++) {
            Entity entity = entityList.get(i);

            if (!this.entityRenderDispatcher.shouldRender(entity, culler, d, e, g) && entity.rider != this.minecraft.player) {
                if (entity instanceof WitherSkullEntity) {
                    this.minecraft.getEntityRenderDispatcher().renderNameTag(entity, tickDelta);
                }
                continue;
            }

            if (entity == cameraEntity && this.minecraft.options.perspective == 0 && !isSelfSleeping) {
                continue;
            }

            if (entity.y >= 0.0 && entity.y < 256.0) {
                entityBlockPos.set(MathHelper.floor(entity.x), MathHelper.floor(entity.y), MathHelper.floor(entity.z));
                if (!this.world.isChunkLoaded(entityBlockPos)) {
                    continue;
                }
            }

            this.renderedEntityCount++;
            this.entityRenderDispatcher.render(entity, tickDelta);
        }
    }

    /**
     * @reason Replace the debug string
     * @author JellySquid
     */
    @Overwrite
    public String getChunkDebugInfo() {
        return this.renderer.getChunksDebugString();
    }
}
