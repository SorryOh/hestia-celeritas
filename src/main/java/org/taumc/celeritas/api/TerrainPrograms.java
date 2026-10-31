package org.taumc.celeritas.api;

// Hestia build 11 (2026-11-01): new, for Kora's shader packs: another mod's programs draw the terrain passes on
// Celeritas's own buffers, draw lists and state, and the terrain can be drawn in vanilla 1.8.9's four block layers.

import org.embeddedt.embeddium.impl.gl.attribute.GlVertexAttribute;
import org.taumc.celeritas.impl.render.terrain.PrimitiveRenderPassConfigurationBuilder;

import java.util.Collection;

/**
 * Terrain programs from another mod, drawn with in place of Celeritas's own, one per terrain pass. Celeritas still
 * draws: its region buffers in its vertex format, its draw lists, its state; it sets {@code u_ModelViewMatrix},
 * {@code u_ProjectionMatrix} and {@code u_RegionOffset} where the program declares them. Everything else the program
 * reads (textures included) is the provider's to set in {@link Provider#begin}. With no provider nothing changes.
 */
public final class TerrainPrograms {
    private TerrainPrograms() {
    }

    /** Gives the programs. Every call comes on the render thread. */
    public interface Provider {
        /**
         * The linked program to draw a pass with, or 0 for Celeritas's own: asked the first time the pass draws after
         * the provider was set or {@link #reload()} was called. The provider owns the program (Celeritas never deletes
         * it) and binds each of {@link #attributes()} to its index before linking.
         *
         * @param pass "solid", "cutout_mipped", "translucent", and in {@link #setVanillaLayers vanilla's layers} "cutout"
         */
        int program(String pass);

        /** The pass's draws start: its program from {@link #program} is bound. */
        void begin(String pass, int program);

        /** The pass's draws are done; its program is still bound (Celeritas unbinds it next). */
        void end(String pass, int program);
    }

    private static volatile Provider provider;
    private static volatile int generation;
    private static volatile boolean vanillaLayers;
    private static String[] attributes;

    /** Sets the provider (null: Celeritas's own programs, at no cost); every pass asks it again. */
    public static void setProvider(Provider newProvider) {
        provider = newProvider;
        generation++;
    }

    public static Provider getProvider() {
        return provider;
    }

    /** Every pass asks the provider for its program again (it made new ones). */
    public static void reload() {
        generation++;
    }

    /** Changes each time the passes must ask for their programs again. */
    public static int generation() {
        return generation;
    }

    /**
     * The terrain vertex format's attribute names in the order of their indices (0, 1, ...): what every pass's buffers
     * hold, in celeritas-common's VANILLA_LIKE format.
     */
    public static String[] attributes() {
        String[] names = attributes;
        if (names == null) {
            Collection<GlVertexAttribute> list = PrimitiveRenderPassConfigurationBuilder.SOLID_PASS.vertexType().getVertexFormat().getAttributes();
            names = new String[list.size()];
            int i = 0;
            for (GlVertexAttribute attribute : list) {
                names[i++] = attribute.getName();
            }
            attributes = names;
        }
        return names.clone();
    }

    /**
     * Whether terrain is drawn in vanilla's four block layers, each when vanilla draws it and in the GL state vanilla
     * sets for it, as shader packs expect: solid at the solid layer (alpha test off), cutout_mipped at the
     * cutout_mipped layer (alpha test on), cutout (the cutout layer's blocks) at the cutout layer, whose block atlas has
     * no mipmaps, and translucent at the translucent layer. Off (the default), Celeritas draws its solid and
     * cutout_mipped passes at the solid layer, the cutout layer's blocks in them with their own sampling, and nothing at
     * the two cutout layers. Applies when the world renderer is made again: reload the chunks after changing it.
     */
    public static void setVanillaLayers(boolean on) {
        vanillaLayers = on;
    }

    public static boolean vanillaLayers() {
        return vanillaLayers;
    }
}
