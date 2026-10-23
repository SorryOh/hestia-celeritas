package org.taumc.celeritas.mixin.core;

import net.minecraft.world.World;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTracker;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.taumc.celeritas.impl.world.ViewChunkTracker;

/**
 * Gives every world the chunk tracker Celeritas reads chunk load and unload events from. Hestia build 10: vanilla's
 * rule for which columns get sections ({@link ViewChunkTracker}) instead of upstream's ready-with-all-neighbours one.
 */
@Mixin(World.class)
public class WorldMixin implements ChunkTrackerHolder {
    @Unique
    private final ChunkTracker celeritas$tracker = new ViewChunkTracker();

    @Override
    public ChunkTracker sodium$getTracker() {
        return celeritas$tracker;
    }
}
