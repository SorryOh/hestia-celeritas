package org.taumc.celeritas.mixin.core;

import net.minecraft.world.World;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTracker;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives every world the chunk tracker Celeritas reads chunk load and unload events from. */
@Mixin(World.class)
public class WorldMixin implements ChunkTrackerHolder {
    @Unique
    private final ChunkTracker celeritas$tracker = new ChunkTracker();

    @Override
    public ChunkTracker sodium$getTracker() {
        return celeritas$tracker;
    }
}
