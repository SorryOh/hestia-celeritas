package org.taumc.celeritas.mixin.core;

import net.minecraft.client.resource.model.BlockModelShaper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.taumc.celeritas.impl.render.terrain.compile.pipeline.FaceOcclusion;

/** Drops the slab and stair face shapes worked out from the old models when the block models are rebuilt. */
@Mixin(BlockModelShaper.class)
public class BlockModelShaperMixin {
    @Inject(method = "rebuildCache", at = @At("TAIL"))
    private void celeritas$forgetFaceShapes(CallbackInfo ci) {
        FaceOcclusion.onModelsReloaded();
    }
}
