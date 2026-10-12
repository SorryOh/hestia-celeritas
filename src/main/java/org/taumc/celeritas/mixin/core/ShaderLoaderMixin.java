package org.taumc.celeritas.mixin.core;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.apache.logging.log4j.LogManager;
import org.embeddedt.embeddium.impl.render.shader.ShaderLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives Celeritas's chunk shaders vanilla 1.8.9's linear fog. celeritas-common's fog include eases the fog in with
 * smoothstep between the fog start and end, where 1.8.9's fixed-function GL_LINEAR fog rises in a straight line:
 * a quarter of the way in, vanilla is 25% fogged, smoothstep 16%. The jar stays as it is; only the source text it
 * loads is changed. (Vanilla also switches NVIDIA cards to radial fog, which Celeritas's spherical distance matches.)
 * {@code -Dceleritas.upstreamFog=true} keeps celeritas-common's curve, for comparisons.
 */
@Mixin(value = ShaderLoader.class, remap = false)
public class ShaderLoaderMixin {
    @Unique
    private static final String SMOOTHSTEP = "smoothstep(fogStart, fogEnd, fragDistance)";

    @Unique
    private static final String LINEAR = "clamp((fragDistance - fogStart) / (fogEnd - fogStart), 0.0, 1.0)";

    // The first time the fog include loads, whether it could be changed is logged.
    @Unique
    private static boolean celeritas$warned;

    @Unique
    private static final boolean UPSTREAM_FOG = Boolean.getBoolean("celeritas.upstreamFog");

    @ModifyReturnValue(method = "getShaderSource", at = @At("RETURN"))
    private static String celeritas$linearFog(String source, @Local(argsOnly = true) String name) {
        if (UPSTREAM_FOG || !"sodium:include/fog.glsl".equals(name)) {
            return source;
        }
        boolean expected = source.contains(SMOOTHSTEP);
        if (!celeritas$warned) {
            celeritas$warned = true;
            if (expected) {
                LogManager.getLogger("Celeritas").info("Terrain fog uses vanilla's linear curve");
            } else {
                LogManager.getLogger("Celeritas").warn("The fog shader doesn't look as expected; terrain fog keeps celeritas-common's curve");
            }
        }
        return expected ? source.replace(SMOOTHSTEP, LINEAR) : source;
    }
}
