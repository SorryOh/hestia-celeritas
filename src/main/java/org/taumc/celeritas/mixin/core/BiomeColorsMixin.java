package org.taumc.celeritas.mixin.core;

import net.minecraft.client.world.color.BiomeColors;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.taumc.celeritas.impl.world.WorldSlice;

/**
 * Grass, foliage and water colours blended over the 3x3 columns around a block (vanilla BiomeColorHelper). Chunk
 * builds take them from their {@link WorldSlice}, which works out each column's colour once per build instead of nine
 * times per tinted face; everything else keeps vanilla's average, without its iterator.
 */
@Mixin(BiomeColors.class)
public abstract class BiomeColorsMixin {
    /**
     * @author Hestia build
     * @reason Chunk builds read colours from their section copies, cached per column
     */
    @Overwrite
    private static int getColor(WorldView view, BlockPos pos, BiomeColors.ColorProvider provider) {
        if (view instanceof WorldSlice slice) {
            return slice.getBlendedColor(provider, pos);
        }

        // Vanilla: the provider's colour at the block's height in each of the nine columns, averaged per channel.
        int r = 0, g = 0, b = 0;
        BlockPos.Mutable column = new BlockPos.Mutable();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                column.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                int color = provider.getColor(view.getBiome(column), column);
                r += (color & 0xFF0000) >> 16;
                g += (color & 0xFF00) >> 8;
                b += color & 0xFF;
            }
        }
        return (r / 9 & 255) << 16 | (g / 9 & 255) << 8 | b / 9 & 255;
    }
}
