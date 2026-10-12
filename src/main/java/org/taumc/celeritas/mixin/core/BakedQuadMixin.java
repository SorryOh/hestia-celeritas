package org.taumc.celeritas.mixin.core;

import net.minecraft.client.render.model.block.BakedQuad;
import net.minecraft.client.render.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.taumc.celeritas.impl.extensions.BakedQuadExtension;

/** Gives baked quads their sprite (set by FaceBakeryMixin), so chunk builds can pick a render pass per quad. */
@Mixin(BakedQuad.class)
public class BakedQuadMixin implements BakedQuadExtension {
    @Unique
    private TextureAtlasSprite celeritas$sprite;

    @Unique
    private boolean celeritas$spriteTrusted;

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
}
