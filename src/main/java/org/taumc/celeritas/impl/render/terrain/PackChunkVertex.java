package org.taumc.celeritas.impl.render.terrain;

// Hestia build 12 (2026-11-01): new. The terrain vertex format for shader packs (TerrainPrograms.setPackFormat).

import org.embeddedt.embeddium.impl.gl.attribute.GlVertexAttributeFormat;
import org.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.taumc.celeritas.lwjgl.LWJGLService;
import org.taumc.celeritas.lwjgl.LWJGLServiceProvider;

/**
 * Celeritas's own VANILLA_LIKE vertex with what shader packs read added, every attribute float or normalized so a
 * GLSL 1.20 program reads it (no integer attributes):
 * <pre>
 *  0 a_PosId        3 floats         position in the section, as VANILLA_LIKE
 * 12 a_Color        4 ubytes, norm.  color
 * 16 a_TexCoord     2 floats         texture coordinate
 * 24 a_LightCoord   2 ushorts        block and sky light as vanilla's lightmap coordinates (0-240)
 * 28 a_Section      4 ubytes         the section's place in its region (x 0-7, y 0-3, z 0-7), the material bits
 * 32 a_Normal       4 bytes, norm.   the quad's normal (NormI8), w 0
 * 36 at_tangent     4 bytes, norm.   tangent, w the handedness
 * 40 mc_midTexCoord 2 floats         the mean of the quad's texture coordinates
 * 48 mc_Entity      2 shorts         block ID, render type
 * </pre>
 * The section's translation is {@code u_RegionOffset + a_Section.xyz * 16}, as Celeritas's own shader decodes it from
 * the draw id.
 */
public final class PackChunkVertex implements ChunkVertexType {
    public static final PackChunkVertex INSTANCE = new PackChunkVertex();

    public static final int STRIDE = 52;

    public static final GlVertexFormat VERTEX_FORMAT = GlVertexFormat.builder(STRIDE)
            .addElement("a_PosId", 0, GlVertexAttributeFormat.FLOAT, 3, false, false)
            .addElement("a_Color", 12, GlVertexAttributeFormat.UNSIGNED_BYTE, 4, true, false)
            .addElement("a_TexCoord", 16, GlVertexAttributeFormat.FLOAT, 2, false, false)
            .addElement("a_LightCoord", 24, GlVertexAttributeFormat.UNSIGNED_SHORT, 2, false, false)
            .addElement("a_Section", 28, GlVertexAttributeFormat.UNSIGNED_BYTE, 4, false, false)
            .addElement("a_Normal", 32, GlVertexAttributeFormat.BYTE, 4, true, false)
            .addElement("at_tangent", 36, GlVertexAttributeFormat.BYTE, 4, true, false)
            .addElement("mc_midTexCoord", 40, GlVertexAttributeFormat.FLOAT, 2, false, false)
            .addElement("mc_Entity", 48, GlVertexAttributeFormat.SHORT, 2, false, false)
            .build();

    private PackChunkVertex() {
    }

    @Override
    public float getPositionScale() {
        return 1.0F;
    }

    @Override
    public float getPositionOffset() {
        return 0.0F;
    }

    @Override
    public float getTextureScale() {
        return 1.0F;
    }

    @Override
    public GlVertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder createEncoder() {
        return PackChunkVertex::write;
    }

    private static long write(long ptr, org.embeddedt.embeddium.impl.render.chunk.terrain.material.Material material, ChunkVertexEncoder.Vertex vertex, int section) {
        LWJGLService lwjgl = LWJGLServiceProvider.LWJGL;
        lwjgl.memPutFloat(ptr, vertex.x);
        lwjgl.memPutFloat(ptr + 4L, vertex.y);
        lwjgl.memPutFloat(ptr + 8L, vertex.z);
        lwjgl.memPutInt(ptr + 12L, vertex.color);
        lwjgl.memPutFloat(ptr + 16L, vertex.u);
        lwjgl.memPutFloat(ptr + 20L, vertex.v);
        // The builders add half a texel (8) to each light value for Celeritas's own shader; packs get vanilla's.
        int light = vertex.light;
        int block = Math.max(0, (light & 0xFFFF) - 8);
        int sky = Math.max(0, (light >>> 16) - 8);
        lwjgl.memPutInt(ptr + 24L, block | sky << 16);
        // celeritas-common's LocalSectionIndex packing: x = index >> 5 & 7, y = index & 3, z = index >> 2 & 7.
        lwjgl.memPutInt(ptr + 28L, (section >> 5 & 7) | (section & 3) << 8 | (section >> 2 & 7) << 16 | (material.bits() & 0xFF) << 24);
        lwjgl.memPutInt(ptr + 32L, vertex.trueNormal & 0x00FFFFFF);
        if (vertex instanceof PackVertex pack) {
            lwjgl.memPutInt(ptr + 36L, pack.tangent);
            lwjgl.memPutFloat(ptr + 40L, pack.midU);
            lwjgl.memPutFloat(ptr + 44L, pack.midV);
            lwjgl.memPutInt(ptr + 48L, pack.entity);
        } else {
            lwjgl.memPutInt(ptr + 36L, 0x7F00007F);
            lwjgl.memPutFloat(ptr + 40L, vertex.u);
            lwjgl.memPutFloat(ptr + 44L, vertex.v);
            lwjgl.memPutInt(ptr + 48L, 0);
        }
        return ptr + STRIDE;
    }
}
