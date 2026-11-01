package org.taumc.celeritas.api;

// Hestia build 11 (2026-11-01): new, for Kora's shader packs: another mod's programs draw the terrain passes on
// Celeritas's own buffers, draw lists and state, and the terrain can be drawn in vanilla 1.8.9's four block layers.
// Hestia build 12 (2026-11-01): the pack vertex format (normal, tangent, middle texture coordinate, block ID), the
// block IDs it carries, a shadow pass (TerrainShadow), and fog culling as a switch.

import org.embeddedt.embeddium.impl.gl.attribute.GlVertexAttribute;
import org.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import org.taumc.celeritas.impl.render.terrain.PackChunkVertex;

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
         * it) and binds each of {@link #attributes()} to its index before linking. In the {@link TerrainShadow shadow
         * pass} the names start with "shadow_", and 0 leaves the pass undrawn there (Celeritas's own program only
         * draws the camera's passes).
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
    private static volatile boolean packFormat;
    private static volatile boolean shadowPass;
    private static volatile boolean fogCulling = true;
    private static volatile short[] blockIds;

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
     * hold, in celeritas-common's VANILLA_LIKE format, or in the {@link #setPackFormat pack format} while that is set.
     */
    public static String[] attributes() {
        return names(packFormat ? PackChunkVertex.VERTEX_FORMAT : ChunkMeshFormats.VANILLA_LIKE.getVertexFormat());
    }

    /** The {@link #setPackFormat pack format}'s attribute names in the order of their indices, whether it's set or not. */
    public static String[] packAttributes() {
        return names(PackChunkVertex.VERTEX_FORMAT);
    }

    private static String[] names(GlVertexFormat format) {
        Collection<GlVertexAttribute> list = format.getAttributes();
        String[] names = new String[list.size()];
        int i = 0;
        for (GlVertexAttribute attribute : list) {
            names[i++] = attribute.getName();
        }
        return names;
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

    /**
     * Whether terrain vertices are in the pack format ({@code PackChunkVertex}: every attribute float or normalized,
     * with the quad's normal, tangent ({@code at_tangent}), middle texture coordinate ({@code mc_midTexCoord}) and the
     * block's ID and render type ({@code mc_Entity})), for shader packs. Only programs from the provider can draw it:
     * set a provider that gives one for every pass. Applies when the world renderer is made again (all chunks are
     * rebuilt): reload the chunks after changing it.
     */
    public static void setPackFormat(boolean on) {
        packFormat = on;
    }

    public static boolean packFormat() {
        return packFormat;
    }

    /**
     * The block ID the pack format gives each block state ({@code mc_Entity.x}), indexed by {@code
     * Block.STATE_REGISTRY}'s ID (block ID << 4 | metadata); null (the default): the block's own numeric ID. The array
     * is read as it is while chunks build: give a new one, never change one given. Takes effect for sections built
     * afterwards (reload the chunks to apply it everywhere).
     */
    public static void setBlockIds(short[] ids) {
        blockIds = ids;
    }

    public static short[] blockIds() {
        return blockIds;
    }

    /**
     * Whether the world renderer keeps a second visibility search for a shadow pass ({@link TerrainShadow}). Applies
     * when the world renderer is made again: reload the chunks after changing it.
     */
    public static void setShadowPass(boolean on) {
        shadowPass = on;
    }

    public static boolean shadowPass() {
        return shadowPass;
    }

    /**
     * Whether the camera's search leaves out sections the fog hides entirely (Celeritas's fog culling, when the
     * settings allow it). Shader packs draw their own fog, so they want every section in render distance. Read by each
     * search.
     */
    public static void setFogCulling(boolean on) {
        fogCulling = on;
    }

    public static boolean fogCulling() {
        return fogCulling;
    }
}
