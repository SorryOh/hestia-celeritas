package org.taumc.celeritas.mixin.core;

import org.embeddedt.embeddium.impl.render.chunk.lists.RenderListManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

/** The visibility search submitted and not yet joined (null when none), for ChunkBuilds (Hestia build 9). */
@Mixin(value = RenderListManager.class, remap = false)
public interface RenderListManagerAccessor {
    @Accessor("currentOcclusionFuture")
    CompletableFuture<?> celeritas$getCurrentOcclusionFuture();
}
