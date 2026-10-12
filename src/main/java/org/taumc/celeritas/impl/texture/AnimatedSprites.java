package org.taumc.celeritas.impl.texture;

// Hestia build (2026-10-12): new, for visible-only texture animation (VisibleSprites).

import net.minecraft.client.render.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The block atlas's animated sprites, copied each time it is stitched (TextureAtlasMixin), to find the sprite of a quad
 * that doesn't name one: vanilla bakes every block quad through FaceBakery, which names it, but other mods may not.
 */
public final class AnimatedSprites {
    private static volatile TextureAtlasSprite[] sprites = new TextureAtlasSprite[0];

    private AnimatedSprites() {
    }

    public static void onStitched(List<TextureAtlasSprite> animated) {
        sprites = animated.toArray(new TextureAtlasSprite[0]);
    }

    /** The animated sprite whose area holds the middle of the quad's texture coordinates, or null. */
    public static @Nullable TextureAtlasSprite find(int[] vertices) {
        // 1.8.9's block vertex format: 7 ints per vertex, u and v at 4 and 5.
        if (vertices.length < 28) {
            return null;
        }
        float u = 0.0F, v = 0.0F;
        for (int i = 0; i < 4; i++) {
            u += Float.intBitsToFloat(vertices[i * 7 + 4]);
            v += Float.intBitsToFloat(vertices[i * 7 + 5]);
        }
        u *= 0.25F;
        v *= 0.25F;
        for (TextureAtlasSprite sprite : sprites) {
            if (u >= sprite.getUMin() && u <= sprite.getUMax() && v >= sprite.getVMin() && v <= sprite.getVMax()) {
                return sprite;
            }
        }
        return null;
    }
}
