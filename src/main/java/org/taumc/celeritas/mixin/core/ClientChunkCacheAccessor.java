package org.taumc.celeritas.mixin.core;

import net.minecraft.client.world.chunk.ClientChunkCache;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Every chunk the client holds, for the entity loop (EntityGatherer). */
@Mixin(ClientChunkCache.class)
public interface ClientChunkCacheAccessor {
    @Accessor("chunks")
    List<WorldChunk> getAllChunks();
}
