package org.taumc.celeritas.impl.world.cloned;

// Hestia build (2026-10-12): carries the section copies a rebuild reads (upstream's 1.8.9 branch only had the origin).

import it.unimi.dsi.fastutil.shorts.Short2ReferenceMap;
import net.minecraft.block.entity.BlockEntity;
import org.embeddedt.embeddium.impl.util.position.SectionPos;
import org.jetbrains.annotations.Nullable;

/**
 * What a rebuild task reads: its section's position, the copies of the 3x3x3 sections around it (indexed as
 * {@link org.taumc.celeritas.impl.world.WorldSlice#sectionIndex}) and its own section's block entities, listed when the
 * task was made (see {@link ClonedChunkSection#copyBlockEntities}).
 */
public record ChunkRenderContext(SectionPos origin, ClonedChunkSection[] sections, @Nullable Short2ReferenceMap<BlockEntity> blockEntities) {

}
