package org.taumc.celeritas.mixin.core;

import net.minecraft.client.render.model.block.BakedQuad;
import net.minecraft.client.render.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.taumc.celeritas.impl.extensions.BakedQuadExtension;
import org.taumc.celeritas.impl.texture.AnimatedSprites;

/** Gives baked quads their sprite (set by FaceBakeryMixin), so chunk builds can pick a render pass per quad. */
@Mixin(BakedQuad.class)
public class BakedQuadMixin implements BakedQuadExtension {
    @Unique
    private TextureAtlasSprite celeritas$sprite;

    @Unique
    private boolean celeritas$spriteTrusted;

    // For quads made elsewhere than FaceBakery: the animated sprite found under their texture coordinates. Builder
    // threads may look it up at the same time; they find the same sprite, and the flag is written after it.
    @Unique
    private TextureAtlasSprite celeritas$foundSprite;

    @Unique
    private volatile boolean celeritas$lookedUp;

    @Override
    public TextureAtlasSprite celeritas$getSprite() {
        return this.celeritas$sprite;
    }

    @Override
    public boolean celeritas$isSpriteTrusted() {
        return this.celeritas$spriteTrusted;
    }

    @Override
    public void celeritas$setSprite(TextureAtlasSprite sprite, boolean trusted) {
        this.celeritas$sprite = sprite;
        this.celeritas$spriteTrusted = trusted;
    }

    @Override
    public TextureAtlasSprite celeritas$getAnimatedSprite() {
        TextureAtlasSprite sprite = this.celeritas$sprite;
        if (sprite == null) {
            if (!this.celeritas$lookedUp) {
                this.celeritas$foundSprite = AnimatedSprites.find(((BakedQuad) (Object) this).getVertices());
                this.celeritas$lookedUp = true;
            }
            sprite = this.celeritas$foundSprite;
        }
        return sprite != null && sprite.isAnimated() ? sprite : null;
    }
}
