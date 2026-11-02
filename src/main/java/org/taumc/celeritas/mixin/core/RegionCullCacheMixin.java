package org.taumc.celeritas.mixin.core;

import org.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.taumc.celeritas.api.TerrainPrograms;
import org.taumc.celeritas.impl.render.terrain.FlatRenderDistance;

/**
 * Hestia build 14 (2026-11-02): with TerrainPrograms.flatRenderDistance a region (8 × 4 × 8 sections) is outside when
 * none of its columns is within the render distance (FlatRenderDistance), else partly in: each of its sections is then
 * tested, the distance as OcclusionCullerMixin has it and the frustum. Celeritas's own test would leave out every region
 * farther below or above the eye than the render distance.
 */
@Mixin(targets = "org.embeddedt.embeddium.impl.render.chunk.occlusion.RegionCullCache", remap = false)
public class RegionCullCacheMixin {
    @Shadow
    private CameraTransform camera;

    @Shadow
    private float maxDistance;

    // RegionCullCache's PARTIAL and OUTSIDE.
    @Unique
    private static final int PARTIAL = 1;
    @Unique
    private static final int OUTSIDE = 4;

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true)
    private void celeritas$flatRenderDistance(int x, int y, int z, CallbackInfoReturnable<Integer> cir) {
        if (TerrainPrograms.flatRenderDistance()) {
            cir.setReturnValue(FlatRenderDistance.region(this.camera, x, z, this.maxDistance) ? PARTIAL : OUTSIDE);
        }
    }
}
