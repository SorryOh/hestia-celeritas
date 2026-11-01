package org.taumc.celeritas.impl.render.terrain;

// Hestia build (2026-10-12): the 1.8.9 branch only, with gen2 names. The solid pass turns the alpha test off through
// GlStateManager and restores its cached state afterwards; upstream called GL11 directly, which left GlStateManager's
// cache saying the opposite of the real state. A solid material without mipmaps takes the opaque quads of cutout
// blocks, and the configuration's default solid material is the solid one (upstream passed cutout_mipped).
// Hestia build 11 (2026-11-01): vanilla's four block layers as passes (TerrainPrograms.setVanillaLayers): cutout_mipped
// at vanilla's cutout_mipped layer and a cutout pass at its cutout layer, for shader packs.
// Hestia build 12 (2026-11-01): the passes and materials exist once per vertex format (Celeritas's own and the pack
// format, TerrainPrograms.setPackFormat), since a pass names the format its buffers hold; build() takes the format.

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.client.render.block.BlockLayer;
import net.minecraft.client.render.platform.GlStateManager;
import org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import org.taumc.celeritas.api.TerrainPrograms;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class PrimitiveRenderPassConfigurationBuilder {
    /** One vertex format's passes and materials. */
    public static final class Passes {
        public final ChunkVertexType vertexType;
        public final TerrainRenderPass solidPass, cutoutMippedPass, translucentPass;
        /** Vanilla's cutout layer as a pass of its own, only in vanilla's layers (TerrainPrograms.setVanillaLayers). */
        public final TerrainRenderPass cutoutPass;
        public final Material solidMaterial, cutoutMaterial, cutoutMippedMaterial, translucentMaterial;
        /** The solid pass sampled without mipmaps, as vanilla draws the cutout layer: for cutout quads with opaque sprites. */
        public final Material solidUnmippedMaterial;
        /**
         * The cutout layer's blocks in vanilla's layers, drawn when vanilla draws that layer (the block atlas without
         * mipmaps); not the material the opaque-quad move looks for, so their quads all stay in that pass.
         */
        public final Material layerCutoutMaterial;

        private Passes(ChunkVertexType vertexType) {
            this.vertexType = vertexType;
            this.solidPass = builderForRenderType(vertexType, true)
                    .name("solid")
                    .fragmentDiscard(false)
                    .useReverseOrder(false)
                    .build();
            this.cutoutMippedPass = builderForRenderType(vertexType, false)
                    .name("cutout_mipped")
                    .fragmentDiscard(true)
                    .useReverseOrder(false)
                    .build();
            this.translucentPass = builderForRenderType(vertexType, false)
                    .name("translucent")
                    .fragmentDiscard(false)
                    .useReverseOrder(true)
                    .useTranslucencySorting(true) // TODO allow disabling
                    .build();
            this.cutoutPass = builderForRenderType(vertexType, false)
                    .name("cutout")
                    .fragmentDiscard(true)
                    .useReverseOrder(false)
                    .build();

            this.translucentMaterial = new Material(this.translucentPass, AlphaCutoffParameter.ZERO, true);
            this.solidMaterial = new Material(this.solidPass, AlphaCutoffParameter.ZERO, true);
            this.solidUnmippedMaterial = new Material(this.solidPass, AlphaCutoffParameter.ZERO, false);
            this.cutoutMippedMaterial = new Material(this.cutoutMippedPass, AlphaCutoffParameter.ONE_TENTH, true);
            this.cutoutMaterial = new Material(this.cutoutMippedPass, AlphaCutoffParameter.ONE_TENTH, false);
            this.layerCutoutMaterial = new Material(this.cutoutPass, AlphaCutoffParameter.ONE_TENTH, false);
        }
    }

    /** Celeritas's own format's passes, and the pack format's. */
    public static final Passes VANILLA_LIKE = new Passes(ChunkMeshFormats.VANILLA_LIKE);
    public static final Passes PACK = new Passes(PackChunkVertex.INSTANCE);

    // Celeritas's own format's, under build 11's names.
    public static final TerrainRenderPass SOLID_PASS = VANILLA_LIKE.solidPass;
    public static final TerrainRenderPass CUTOUT_MIPPED_PASS = VANILLA_LIKE.cutoutMippedPass;
    public static final TerrainRenderPass TRANSLUCENT_PASS = VANILLA_LIKE.translucentPass;
    public static final TerrainRenderPass CUTOUT_PASS = VANILLA_LIKE.cutoutPass;

    /** The passes a material belongs to (by its pass's vertex format). */
    public static Passes of(Material material) {
        return material.pass.vertexType() == PackChunkVertex.INSTANCE ? PACK : VANILLA_LIKE;
    }

    private static final class PrimitivePipelineState implements TerrainRenderPass.PipelineState {
        private final boolean disableAlphaTest;
        private boolean alphaTestWasEnabled;

        private PrimitivePipelineState(boolean disableAlphaTest) {
            this.disableAlphaTest = disableAlphaTest;
        }

        @Override
        public void setup() {
            if (this.disableAlphaTest) {
                this.alphaTestWasEnabled = GlStateManager.ALPHA_TEST.state.enabled;
                GlStateManager.disableAlphaTest();
            }
        }

        @Override
        public void clear() {
            if (this.disableAlphaTest && this.alphaTestWasEnabled) {
                GlStateManager.enableAlphaTest();
            }
        }
    }

    private static TerrainRenderPass.TerrainRenderPassBuilder builderForRenderType(ChunkVertexType vertexType, boolean disableAlphaTest) {
        var builder = TerrainRenderPass.builder();
        builder.pipelineState(new PrimitivePipelineState(disableAlphaTest));
        builder.vertexType(vertexType).primitiveType(QuadPrimitiveType.TRIANGULATED);
        return builder;
    }

    public static RenderPassConfiguration<?> build(ChunkVertexType vertexType) {
        Passes p = vertexType == PackChunkVertex.INSTANCE ? PACK : VANILLA_LIKE;
        boolean layers = TerrainPrograms.vanillaLayers();
        Map<BlockLayer, Collection<TerrainRenderPass>> vanillaRenderStages = new Reference2ReferenceOpenHashMap<>();
        if (layers) {
            vanillaRenderStages.put(BlockLayer.SOLID, List.of(p.solidPass));
            vanillaRenderStages.put(BlockLayer.CUTOUT_MIPPED, List.of(p.cutoutMippedPass));
            vanillaRenderStages.put(BlockLayer.CUTOUT, List.of(p.cutoutPass));
        } else {
            vanillaRenderStages.put(BlockLayer.SOLID, List.of(p.solidPass, p.cutoutMippedPass));
        }
        vanillaRenderStages.put(BlockLayer.TRANSLUCENT, List.of(p.translucentPass));

        Map<BlockLayer, Material> renderTypeToMaterialMap = new Reference2ReferenceOpenHashMap<>();
        renderTypeToMaterialMap.put(BlockLayer.SOLID, p.solidMaterial);
        renderTypeToMaterialMap.put(BlockLayer.CUTOUT, layers ? p.layerCutoutMaterial : p.cutoutMaterial);
        renderTypeToMaterialMap.put(BlockLayer.CUTOUT_MIPPED, p.cutoutMippedMaterial);
        renderTypeToMaterialMap.put(BlockLayer.TRANSLUCENT, p.translucentMaterial);

        return new RenderPassConfiguration<>(renderTypeToMaterialMap,
                vanillaRenderStages,
                p.solidMaterial,
                p.cutoutMippedMaterial,
                p.translucentMaterial);
    }
}
