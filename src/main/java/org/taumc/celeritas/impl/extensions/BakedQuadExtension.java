package org.taumc.celeritas.impl.extensions;

import net.minecraft.client.render.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;

/**
 * The sprite a baked quad was made with, which 1.8.9's BakedQuad doesn't keep (Hestia build; see BakedQuadMixin).
 */
public interface BakedQuadExtension {
    /** The sprite FaceBakery baked this quad with; null for quads made elsewhere. */
    @Nullable
    TextureAtlasSprite celeritas$getSprite();

    /** Whether the quad's texture coordinates stay inside its sprite, so the sprite describes every texel it shows. */
    boolean celeritas$isSpriteTrusted();

    void celeritas$setSprite(TextureAtlasSprite sprite, boolean trusted);

    /**
     * The quad's sprite if it is animated, else null. Quads made elsewhere than FaceBakery are looked up once by their
     * texture coordinates among the block atlas's animated sprites ({@link org.taumc.celeritas.impl.texture.AnimatedSprites}).
     */
    @Nullable
    TextureAtlasSprite celeritas$getAnimatedSprite();
}
