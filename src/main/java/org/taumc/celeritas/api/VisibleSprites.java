package org.taumc.celeritas.api;

// Hestia build (2026-10-12): new. Celeritas's 1.12.2 version marks visible animated sprites on the sprite itself and
// filters vanilla's animation loop; here the texture animation loop belongs to another mod (Hestia), so it is told.

import net.minecraft.client.render.texture.TextureAtlasSprite;

import java.util.function.Consumer;

/**
 * Tells a listener, every frame, which animated sprites the visible chunk sections use, so texture animation can skip
 * the ones nobody sees. Without a listener nothing is reported and vanilla animates every sprite.
 */
public final class VisibleSprites {
    private static volatile Consumer<TextureAtlasSprite> listener;

    private VisibleSprites() {
    }

    /** Sets the listener (null for none). It is called on the render thread, once per visible sprite per frame. */
    public static void setListener(Consumer<TextureAtlasSprite> listener) {
        VisibleSprites.listener = listener;
    }

    /** Called by the section ticker for each animated sprite in a visible section. Render thread. */
    public static void report(Object sprite) {
        Consumer<TextureAtlasSprite> listener = VisibleSprites.listener;
        if (listener != null) {
            listener.accept((TextureAtlasSprite) sprite);
        }
    }
}
