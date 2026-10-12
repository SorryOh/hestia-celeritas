package org.taumc.celeritas.mixin.core;

import net.minecraft.client.render.world.RenderChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hestia build (2026-10-12): vanilla's chunk grid is created empty. Celeritas replaces vanilla's chunk rendering, and
 * the only callers that look up sections in this grid are the methods it replaces; at render distance 12 vanilla would
 * still allocate 10,000 sections with four vertex buffers each.
 */
@Mixin(RenderChunkStorage.class)
public abstract class RenderChunkStorageMixin {
    @Shadow
    protected int sizeX;

    @Shadow
    protected int sizeZ;

    @Inject(method = "setViewDistance", at = @At("TAIL"))
    private void celeritas$emptyGrid(int viewDistance, CallbackInfo ci) {
        this.sizeX = 0;
        this.sizeZ = 0;
    }
}
