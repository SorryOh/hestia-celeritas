package org.taumc.celeritas.impl.render.terrain;

// Hestia build 11 (2026-11-01): new. A program another mod made (TerrainPrograms) as Celeritas's chunk program type, so
// the chunk renderer binds and draws with it as with its own; the GL program stays the provider's.

import org.embeddedt.embeddium.impl.gl.shader.GlProgram;
import org.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformFloat3v;
import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformMatrix4f;
import org.embeddedt.embeddium.impl.gl.tessellation.GlPrimitiveType;
import org.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderTextureSlot;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.joml.Matrix4fc;

/** Another mod's terrain program ({@link org.taumc.celeritas.api.TerrainPrograms.Provider}) as a chunk program. */
final class PackChunkProgram extends GlProgram<ChunkShaderInterface> {
    PackChunkProgram(int handle) {
        super(handle, Interface::new);
    }

    // The provider deletes its programs; Celeritas only drops this wrapper.
    @Override
    protected void destroyInternal() {
    }

    /** The uniforms Celeritas sets, where the program has them; textures and the rest are the provider's. */
    private static final class Interface implements ChunkShaderInterface {
        private final GlUniformMatrix4f modelView;
        private final GlUniformMatrix4f projection;
        private final GlUniformFloat3v regionOffset;
        private GlPrimitiveType primitiveType = GlPrimitiveType.TRIANGLES;

        Interface(ShaderBindingContext context) {
            this.modelView = context.bindUniformIfPresent("u_ModelViewMatrix", GlUniformMatrix4f::new);
            this.projection = context.bindUniformIfPresent("u_ProjectionMatrix", GlUniformMatrix4f::new);
            this.regionOffset = context.bindUniformIfPresent("u_RegionOffset", GlUniformFloat3v::new);
        }

        // As DefaultChunkShaderInterface: quads drawn as they are, or as two triangles each.
        @Override
        public void setupState(TerrainRenderPass pass) {
            if (pass.primitiveType() == QuadPrimitiveType.DIRECT) {
                this.primitiveType = GlPrimitiveType.QUADS;
            } else if (pass.primitiveType() == QuadPrimitiveType.TRIANGULATED) {
                this.primitiveType = GlPrimitiveType.TRIANGLES;
            } else {
                throw new IllegalArgumentException("Unknown primitive type");
            }
        }

        @Override
        public GlPrimitiveType getPrimitiveType() {
            return this.primitiveType;
        }

        @Override
        public void setProjectionMatrix(Matrix4fc matrix) {
            if (this.projection != null) {
                this.projection.set(matrix);
            }
        }

        @Override
        public void setModelViewMatrix(Matrix4fc matrix) {
            if (this.modelView != null) {
                this.modelView.set(matrix);
            }
        }

        @Override
        public void setRegionOffset(float x, float y, float z) {
            if (this.regionOffset != null) {
                this.regionOffset.set(x, y, z);
            }
        }

        @Override
        public void setTextureSlot(ChunkShaderTextureSlot slot, int unit) {
        }
    }
}
