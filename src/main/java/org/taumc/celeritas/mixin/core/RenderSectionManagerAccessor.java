package org.taumc.celeritas.mixin.core;

import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.ConcurrentLinkedDeque;

/** Finished builds waiting to be uploaded, for ChunkBuilds (Hestia build 9). */
@Mixin(value = RenderSectionManager.class, remap = false)
public interface RenderSectionManagerAccessor {
    @Accessor("buildResults")
    ConcurrentLinkedDeque<?> celeritas$getBuildResults();
}
