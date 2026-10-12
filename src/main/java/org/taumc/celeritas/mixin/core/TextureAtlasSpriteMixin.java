package org.taumc.celeritas.mixin.core;

import net.minecraft.client.render.texture.TextureAtlasSprite;
import net.minecraft.client.resource.metadata.AnimationMetadata;
import org.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Measures how transparent each sprite is when its image loads (again on every resource reload), from the full-size
 * pixels of all its animation frames: chunk builds draw quads with fully opaque sprites in the solid pass. Sprites that
 * weren't loaded from an image (the missing texture) report null and keep their block's pass.
 */
@Mixin(TextureAtlasSprite.class)
public class TextureAtlasSpriteMixin implements SpriteTransparencyLevel.Holder {
    @Shadow
    protected List<int[][]> frames;

    @Unique
    private SpriteTransparencyLevel celeritas$transparency;

    @Inject(method = "load", at = @At("TAIL"))
    private void celeritas$measureTransparency(BufferedImage[] images, AnimationMetadata animation, CallbackInfo ci) {
        SpriteTransparencyLevel level = SpriteTransparencyLevel.OPAQUE;
        for (int[][] frame : this.frames) {
            int[] pixels = frame[0];
            if (pixels == null) {
                continue;
            }
            for (int pixel : pixels) {
                int alpha = pixel >>> 24;
                if (alpha == 0) {
                    level = SpriteTransparencyLevel.TRANSPARENT;
                } else if (alpha != 255) {
                    this.celeritas$transparency = SpriteTransparencyLevel.TRANSLUCENT;
                    return;
                }
            }
        }
        this.celeritas$transparency = level;
    }

    @Override
    public SpriteTransparencyLevel embeddium$getTransparencyLevel() {
        return this.celeritas$transparency;
    }
}
