package org.taumc.celeritas.mixin.core;

import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Whether a chunk has ever held an entity, so the entity loop skips the rest. Feather names vanilla's hasEntities field
 * lastSaveHadEntities.
 */
@Mixin(WorldChunk.class)
public interface WorldChunkAccessor {
    @Accessor("lastSaveHadEntities")
    boolean getHasEntities();
}
