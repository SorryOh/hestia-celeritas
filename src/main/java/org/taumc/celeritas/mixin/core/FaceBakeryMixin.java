package org.taumc.celeritas.mixin.core;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.render.model.block.BakedQuad;
import net.minecraft.client.render.model.block.FaceBakery;
import net.minecraft.client.render.texture.TextureAtlasSprite;
import net.minecraft.client.resource.model.BlockElementFace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.taumc.celeritas.impl.extensions.BakedQuadExtension;

/**
 * Remembers each baked quad's sprite, after Celeritas's 1.12.2 version. The sprite only describes the quad's texels when
 * its texture coordinates stay within the sprite (0 to 16): 1.8.9 doesn't check model UVs, so wider ones are possible.
 */
@Mixin(FaceBakery.class)
public class FaceBakeryMixin {
    @ModifyReturnValue(method = "bakeQuad", at = @At("RETURN"))
    private BakedQuad celeritas$rememberSprite(BakedQuad quad, @Local(argsOnly = true) BlockElementFace face, @Local(argsOnly = true) TextureAtlasSprite sprite) {
        boolean trusted = true;
        for (float uv : face.textureCoords.coordinates) {
            if (uv < 0.0f || uv > 16.0f) {
                trusted = false;
                break;
            }
        }
        ((BakedQuadExtension) quad).celeritas$setSprite(sprite, trusted);
        return quad;
    }
}
