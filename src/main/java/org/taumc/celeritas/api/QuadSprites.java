package org.taumc.celeritas.api;

// Hestia build (2026-10-13): new, for Hestia's connected textures (OptiFine/MCPatcher CTM resource packs), which choose
// a block quad's texture by its neighbours while chunks are built.

import net.minecraft.block.state.BlockState;
import net.minecraft.client.render.model.block.BakedQuad;
import net.minecraft.client.render.texture.TextureAtlasSprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;
import org.taumc.celeritas.impl.extensions.BakedQuadExtension;

/**
 * Lets another mod draw a block model's quad with a different sprite of the block atlas while chunks are built, as
 * connected textures do. The quad keeps its shape, light and color; its texture coordinates move from the sprite it was
 * baked with to the same place in the new one.
 */
public final class QuadSprites {
    /** Chooses the sprite a quad is drawn with. */
    @FunctionalInterface
    public interface Provider {
        /**
         * The sprite to draw {@code quad} of {@code state} at {@code pos} with instead of {@code sprite}, the one it was
         * baked with, or null to keep that one. {@code face} is the quad's face and {@code view} the section's copy of
         * the world around it. Called on chunk builder threads for every quad of a model block whose sprite is known
         * (with its texture coordinates inside it), so it must be quick and thread-safe.
         */
        @Nullable
        TextureAtlasSprite getSprite(WorldView view, BlockState state, BlockPos pos, Direction face, BakedQuad quad, TextureAtlasSprite sprite);
    }

    private static volatile Provider provider;

    private QuadSprites() {
    }

    /**
     * Sets the provider (null removes it). Chunks built before keep their quads: set it before the world's chunks are
     * built, for example while resources load (the renderers rebuild every chunk after a reload).
     */
    public static void setProvider(@Nullable Provider newProvider) {
        provider = newProvider;
    }

    public static @Nullable Provider getProvider() {
        return provider;
    }

    /**
     * The sprite FaceBakery baked {@code quad} with, or null for quads made elsewhere or whose texture coordinates
     * reach outside their sprite (the ones the provider is never asked about).
     */
    public static @Nullable TextureAtlasSprite getSprite(BakedQuad quad) {
        BakedQuadExtension extension = (BakedQuadExtension) quad;
        return extension.celeritas$isSpriteTrusted() ? extension.celeritas$getSprite() : null;
    }
}
