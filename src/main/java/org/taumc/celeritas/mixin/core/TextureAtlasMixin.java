package org.taumc.celeritas.mixin.core;

// Hestia build (2026-10-12): new, for visible-only texture animation.

import net.minecraft.client.render.texture.TextureAtlas;
import net.minecraft.client.render.texture.TextureAtlasSprite;
import net.minecraft.client.resource.manager.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.taumc.celeritas.impl.texture.AnimatedSprites;

import java.util.List;

/** Copies the block atlas's animated sprites once it is stitched (AnimatedSprites). */
@Mixin(TextureAtlas.class)
public class TextureAtlasMixin {
    @Shadow
    @Final
    private List<TextureAtlasSprite> animatedSprites;

    @Shadow
    @Final
    private String path;

    @Inject(method = "loadAndStitch", at = @At("TAIL"))
    private void celeritas$copyAnimatedSprites(ResourceManager resourceManager, CallbackInfo ci) {
        // Minecraft's block atlas is the one made with the path "textures".
        if ("textures".equals(this.path)) {
            AnimatedSprites.onStitched(this.animatedSprites);
        }
    }
}
