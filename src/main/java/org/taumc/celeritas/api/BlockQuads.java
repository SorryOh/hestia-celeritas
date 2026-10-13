package org.taumc.celeritas.api;

// Hestia build (2026-10-14): new, for Hestia's resource pack features that change block quads while chunks are built:
// natural textures (turned and mirrored textures), better grass, connected textures' overlay and compact methods,
// emissive block textures and custom block colors.

import net.minecraft.block.state.BlockState;
import net.minecraft.client.render.model.block.BakedQuad;
import net.minecraft.client.render.texture.TextureAtlasSprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

/**
 * Lets another mod change how a model block's quad is drawn while chunks are built: with another sprite of the block
 * atlas, its texture turned or mirrored, recolored, in parts with a sprite each, with extra quads over it, or not at
 * all. The quad keeps its shape and its light (worked out as vanilla does) unless said otherwise. When a handler is
 * set it is asked before {@link QuadSprites}' provider, which is then asked only about quads the handler left alone.
 */
public final class BlockQuads {
    /** Decides how one quad is drawn. */
    @FunctionalInterface
    public interface Handler {
        /**
         * Called on chunk builder threads for every quad of a model block whose sprite is known ({@code sprite}, the
         * one it was baked with, its texture coordinates inside it), so it must be quick and thread-safe. Returns false
         * to leave the quad alone; true when the handler drew what it wanted through {@code out}, which may be
         * nothing (the quad isn't drawn). {@code face} is the quad's face and {@code view} the section's copy of the
         * world around it.
         */
        boolean handle(WorldView view, BlockState state, BlockPos pos, Direction face, BakedQuad quad, TextureAtlasSprite sprite, Output out);
    }

    /** What a handler can draw in place of the quad. Only valid during {@link Handler#handle}. */
    public interface Output {
        /**
         * The quad with {@code sprite} (its texture coordinates moved to the same place in it), turned {@code rotation}
         * quarter turns and then mirrored when {@code flip}, with its own color and tint.
         */
        void quad(TextureAtlasSprite sprite, int rotation, boolean flip);

        /** The same, colored by {@code color} (0xRRGGBB) as a tint colors a quad, in place of the quad's own tint. */
        void tinted(TextureAtlasSprite sprite, int rotation, boolean flip, int color);

        /**
         * The part of the quad between {@code s0} and {@code s1} across and {@code t0} and {@code t1} down its texture
         * (0 to 1 over the sprite it was baked with), drawn with the same part of {@code sprite}. Works on quads whose
         * corners sit at their sprite's corners (whole faces); others are drawn whole with {@code sprite}.
         */
        void part(TextureAtlasSprite sprite, float s0, float t0, float s1, float t1);

        /**
         * An extra quad over this one: its shape with {@code sprite}, in the cutout pass (so its see-through pixels show
         * what is beneath), colored by {@code color} (0xRRGGBB) or with the quad's own color and tint when -1; when
         * {@code fullBright}, lit at full brightness and not shaded, for glowing parts.
         */
        void overlay(TextureAtlasSprite sprite, int color, boolean fullBright);
    }

    private static volatile Handler handler;

    private BlockQuads() {
    }

    /**
     * Sets the handler (null removes it). Chunks built before keep their quads: set it before the world's chunks are
     * built, for example while resources load (the renderers rebuild every chunk after a reload).
     */
    public static void setHandler(@Nullable Handler newHandler) {
        handler = newHandler;
    }

    public static @Nullable Handler getHandler() {
        return handler;
    }
}
