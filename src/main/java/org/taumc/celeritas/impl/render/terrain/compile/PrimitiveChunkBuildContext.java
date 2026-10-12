package org.taumc.celeritas.impl.render.terrain.compile;

// Hestia build (2026-10-12): the 1.8.9 branch only, with gen2 names. The vertex data is read through one int view per
// buffer instead of a new view per block; light values get vanilla's half-texel lightmap offset; no sprite
// transparency tracker (pre-1.5 only).

import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.world.World;
import org.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.embeddedt.embeddium.impl.util.QuadUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class PrimitiveChunkBuildContext extends ChunkBuildContext {
    public final World world;

    public final BufferBuilder tesselator;

    private ByteBuffer rawDataSource;
    private IntBuffer rawData;

    public PrimitiveChunkBuildContext(World world, RenderPassConfiguration renderPassConfiguration) {
        super(renderPassConfiguration);
        this.world = world;
        this.tesselator = new BufferBuilder(8192);
    }

    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();

    /**
     * The tessellator's vertex data as ints from index 0. The view is only rebuilt when the tessellator has grown its
     * buffer, which replaces the buffer object.
     */
    public IntBuffer rawVertexData() {
        ByteBuffer buffer = this.tesselator.getBuffer();
        if (buffer != this.rawDataSource) {
            this.rawDataSource = buffer;
            // duplicate() resets the byte order to big-endian; keep the tessellator's native order.
            this.rawData = buffer.duplicate().order(buffer.order()).clear().asIntBuffer();
        }
        return this.rawData;
    }

    public void copyRawBuffer(IntBuffer rawBuffer, int vertexCount, ChunkBuildBuffers buffers, Material material) {
        if (vertexCount == 0) {
            return;
        }

        // Require
        if ((vertexCount & 0x3) != 0) {
            throw new IllegalStateException();
        }

        outputQuads(rawBuffer, this.vertices, vertexCount / 4, buffers, material);
    }

    private void outputQuads(IntBuffer rawBuffer, ChunkVertexEncoder.Vertex[] celeritasVertices, int numQuads, ChunkBuildBuffers buffers, Material material) {
        int ptr = 0;
        for (int quadIdx = 0; quadIdx < numQuads; quadIdx++) {
            for (int vIdx = 0; vIdx < 4; vIdx++) {
                var vertex = celeritasVertices[vIdx];
                vertex.x = Float.intBitsToFloat(rawBuffer.get(ptr++));
                vertex.y = Float.intBitsToFloat(rawBuffer.get(ptr++));
                vertex.z = Float.intBitsToFloat(rawBuffer.get(ptr++));
                // In 1.8+, color comes before all UVs.
                vertex.color = rawBuffer.get(ptr++);
                vertex.u = Float.intBitsToFloat(rawBuffer.get(ptr++));
                vertex.v = Float.intBitsToFloat(rawBuffer.get(ptr++));
                // Vanilla 1.8.9 samples the lightmap at (light + 8) / 256 (GameRenderer.enableLightMap's texture
                // matrix), the centre of each light level's texel; Celeritas's shader samples at light / 256, the
                // edge between two levels, which blends them (full daylight came out about 10% darker). Adding half a
                // texel to the block and sky values here gives vanilla's coordinates; 240 + 8 still fits in 8 bits.
                vertex.light = rawBuffer.get(ptr++) + 0x00080008;
            }
            int trueNormal = QuadUtil.calculateNormal(celeritasVertices);
            for (int vIdx = 0; vIdx < 4; vIdx++) {
                celeritasVertices[vIdx].trueNormal = trueNormal;
            }
            ModelQuadFacing facing = QuadUtil.findNormalFace(trueNormal);
            buffers.get(material).getVertexBuffer(facing).push(celeritasVertices, material);
        }
    }

    @Override
    public void cleanup() {
        super.cleanup();
    }
}
