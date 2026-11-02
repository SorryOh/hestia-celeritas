package org.taumc.celeritas.mixin.core;

import org.embeddedt.embeddium.impl.render.chunk.occlusion.OcclusionCuller;
import org.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.taumc.celeritas.api.TerrainPrograms;
import org.taumc.celeritas.impl.render.terrain.FlatRenderDistance;

/**
 * Hestia build 14 (2026-11-02): with TerrainPrograms.flatRenderDistance a section is within the render distance when
 * its column is (FlatRenderDistance), instead of Celeritas's cylinder around the eye (the section's nearest point, and
 * no farther up or down than across).
 */
@Mixin(value = OcclusionCuller.class, remap = false)
public class OcclusionCullerMixin {
    @Inject(method = "isWithinRenderDistance", at = @At("HEAD"), cancellable = true)
    private static void celeritas$flatRenderDistance(CameraTransform camera, int x, int y, int z, float maxDistance, CallbackInfoReturnable<Boolean> cir) {
        if (TerrainPrograms.flatRenderDistance()) {
            cir.setReturnValue(FlatRenderDistance.column(camera, x << 4, z << 4, maxDistance));
        }
    }
}
