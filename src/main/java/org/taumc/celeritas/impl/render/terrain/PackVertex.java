package org.taumc.celeritas.impl.render.terrain;

// Hestia build 12 (2026-11-01): new, for TerrainPrograms' pack format.

import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;

/**
 * A chunk vertex with what shader packs read on top of Celeritas's own: the block's ID and render type
 * ({@code mc_Entity}), the quad's middle texture coordinate ({@code mc_midTexCoord}) and its tangent
 * ({@code at_tangent}). The builders fill them once per quad ({@link #fillQuad}) while the pack format is in use;
 * Celeritas's own format never reads them.
 */
public final class PackVertex extends ChunkVertexEncoder.Vertex {
    /** {@code mc_Entity}: the block's ID in the low 16 bits, its render type in the high 16, written as two shorts. */
    public int entity;
    /** {@code mc_midTexCoord}: the mean of the quad's four texture coordinates. */
    public float midU, midV;
    /** {@code at_tangent} as four signed bytes, packed like the normal: x, y, z, then the handedness (127 or -127). */
    public int tangent;

    /** Four new vertices, for the builders' reused quad arrays. */
    public static PackVertex[] quad() {
        PackVertex[] quad = new PackVertex[4];
        for (int i = 0; i < 4; i++) {
            quad[i] = new PackVertex();
        }
        return quad;
    }

    /** {@code mc_Entity}'s value for a block ID (as the pack maps it) and render type. */
    public static int entity(int blockId, int renderType) {
        return (blockId & 0xFFFF) | (renderType & 0xFFFF) << 16;
    }

    /**
     * Fills the pack attributes of a finished quad: its positions, texture coordinates and {@code trueNormal} are
     * set, {@code entity} is the block's. The tangent is the direction of increasing u on the plane of the first
     * triangle (its edges against their texture coordinate deltas), normalized; its handedness is 1 unless
     * tangent × normal points against increasing v (-1), as OptiFine 1.8.9 gives it (Kora's probe: 1 on every
     * block face and the water, whose v runs down the texture). Quads whose texture coordinates don't span the
     * triangle get the tangent of a flat face (+x, or +z for faces along x) instead, handedness 1.
     */
    public static void fillQuad(ChunkVertexEncoder.Vertex[] quad, int entity) {
        ChunkVertexEncoder.Vertex v0 = quad[0], v1 = quad[1], v2 = quad[2], v3 = quad[3];
        float midU = (v0.u + v1.u + v2.u + v3.u) * 0.25F;
        float midV = (v0.v + v1.v + v2.v + v3.v) * 0.25F;

        int normal = v0.trueNormal;
        float nx = (byte) normal / 127.0F;
        float ny = (byte) (normal >> 8) / 127.0F;
        float nz = (byte) (normal >> 16) / 127.0F;

        float e1x = v1.x - v0.x, e1y = v1.y - v0.y, e1z = v1.z - v0.z;
        float e2x = v2.x - v0.x, e2y = v2.y - v0.y, e2z = v2.z - v0.z;
        float du1 = v1.u - v0.u, dv1 = v1.v - v0.v;
        float du2 = v2.u - v0.u, dv2 = v2.v - v0.v;
        float det = du1 * dv2 - du2 * dv1;

        float tx, ty, tz, w;
        if (det != 0.0F && Float.isFinite(det)) {
            float r = 1.0F / det;
            tx = (e1x * dv2 - e2x * dv1) * r;
            ty = (e1y * dv2 - e2y * dv1) * r;
            tz = (e1z * dv2 - e2z * dv1) * r;
            float bx = (e2x * du1 - e1x * du2) * r;
            float by = (e2y * du1 - e1y * du2) * r;
            float bz = (e2z * du1 - e1z * du2) * r;
            // (t x n) . b (build 12 took n x t: every face's handedness came out the opposite of OptiFine's)
            float cx = ty * nz - tz * ny, cy = tz * nx - tx * nz, cz = tx * ny - ty * nx;
            w = cx * bx + cy * by + cz * bz < 0.0F ? -1.0F : 1.0F;
        } else {
            tx = Math.abs(nx) > 0.9F ? 0.0F : 1.0F;
            ty = 0.0F;
            tz = Math.abs(nx) > 0.9F ? 1.0F : 0.0F;
            w = 1.0F;
        }
        float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (length > 0.0F) {
            tx /= length;
            ty /= length;
            tz /= length;
        }
        int tangent = encode(tx) | encode(ty) << 8 | encode(tz) << 16 | encode(w) << 24;

        for (int i = 0; i < 4; i++) {
            if (quad[i] instanceof PackVertex pack) {
                pack.entity = entity;
                pack.midU = midU;
                pack.midV = midV;
                pack.tangent = tangent;
            }
        }
    }

    // As NormI8: -1..1 to a signed byte.
    private static int encode(float value) {
        return Math.round(Math.max(-1.0F, Math.min(1.0F, value)) * 127.0F) & 0xFF;
    }
}
