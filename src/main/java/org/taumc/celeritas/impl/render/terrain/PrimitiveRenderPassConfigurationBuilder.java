package org.taumc.celeritas.impl.render.terrain;

// Hestia build (2026-10-12): the 1.8.9 branch only, with gen2 names. The solid pass turns the alpha test off through
// GlStateManager and restores its cached state afterwards; upstream called GL11 directly, which left GlStateManager's
// cache saying the opposite of the real state. A solid material without mipmaps takes the opaque quads of cutout
// blocks, and the configuration's default solid material is the solid one (upstream passed cutout_mipped).
// Hestia build 11 (2026-11-01): vanilla's four block layers as passes (TerrainPrograms.setVanillaLayers): cutout_mipped
// at vanilla's cutout_mipped layer and a cutout pass at its cutout layer, for shader packs.

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
    public static final TerrainRenderPass SOLID_PASS, CUTOUT_MIPPED_PASS, TRANSLUCENT_PASS;
    public static final Material SOLID_MATERIAL, CUTOUT_MATERIAL, CUTOUT_MIPPED_MATERIAL, TRANSLUCENT_MATERIAL;
    /** The solid pass sampled without mipmaps, as vanilla draws the cutout layer: for cutout quads with opaque sprites. */
    public static final Material SOLID_UNMIPPED_MATERIAL;
    /** Vanilla's cutout layer as a pass of its own, only in vanilla's layers (TerrainPrograms.setVanillaLayers). */
    public static final TerrainRenderPass CUTOUT_PASS;
    /**
     * The cutout layer's blocks in vanilla's layers, drawn when vanilla draws that layer (the block atlas without
     * mipmaps); not the material the opaque-quad move looks for, so their quads all stay in that pass.
     */
    public static final Material LAYER_CUTOUT_MATERIAL;

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

    private static TerrainRenderPass.TerrainRenderPassBuilder builderForRenderType(boolean disableAlphaTest) {
        var builder = TerrainRenderPass.builder();
        builder.pipelineState(new PrimitivePipelineState(disableAlphaTest));
        builder.vertexType(ChunkMeshFormats.VANILLA_LIKE).primitiveType(QuadPrimitiveType.TRIANGULATED);
        return builder;
    }

    static {
        SOLID_PASS = builderForRenderType(true)
                .name("solid")
                .fragmentDiscard(false)
                .useReverseOrder(false)
                .build();
        CUTOUT_MIPPED_PASS = builderForRenderType(false)
                .name("cutout_mipped")
                .fragmentDiscard(true)
                .useReverseOrder(false)
                .build();
        TRANSLUCENT_PASS = builderForRenderType(false)
                .name("translucent")
                .fragmentDiscard(false)
                .useReverseOrder(true)
                .useTranslucencySorting(true) // TODO allow disabling
                .build();
        CUTOUT_PASS = builderForRenderType(false)
                .name("cutout")
                .fragmentDiscard(true)
                .useReverseOrder(false)
                .build();

        TRANSLUCENT_MATERIAL = new Material(TRANSLUCENT_PASS, AlphaCutoffParameter.ZERO, true);
        SOLID_MATERIAL = new Material(SOLID_PASS, AlphaCutoffParameter.ZERO, true);
        SOLID_UNMIPPED_MATERIAL = new Material(SOLID_PASS, AlphaCutoffParameter.ZERO, false);
        CUTOUT_MIPPED_MATERIAL = new Material(CUTOUT_MIPPED_PASS, AlphaCutoffParameter.ONE_TENTH, true);
        CUTOUT_MATERIAL = new Material(CUTOUT_MIPPED_PASS, AlphaCutoffParameter.ONE_TENTH, false);
        LAYER_CUTOUT_MATERIAL = new Material(CUTOUT_PASS, AlphaCutoffParameter.ONE_TENTH, false);
    }

    public static RenderPassConfiguration<?> build(ChunkVertexType vertexType) {
        boolean layers = TerrainPrograms.vanillaLayers();
        Map<BlockLayer, Collection<TerrainRenderPass>> vanillaRenderStages = new Reference2ReferenceOpenHashMap<>();
        if (layers) {
            vanillaRenderStages.put(BlockLayer.SOLID, List.of(SOLID_PASS));
            vanillaRenderStages.put(BlockLayer.CUTOUT_MIPPED, List.of(CUTOUT_MIPPED_PASS));
            vanillaRenderStages.put(BlockLayer.CUTOUT, List.of(CUTOUT_PASS));
        } else {
            vanillaRenderStages.put(BlockLayer.SOLID, List.of(SOLID_PASS, CUTOUT_MIPPED_PASS));
        }
        vanillaRenderStages.put(BlockLayer.TRANSLUCENT, List.of(TRANSLUCENT_PASS));

        Map<BlockLayer, Material> renderTypeToMaterialMap = new Reference2ReferenceOpenHashMap<>();
        renderTypeToMaterialMap.put(BlockLayer.SOLID, SOLID_MATERIAL);
        renderTypeToMaterialMap.put(BlockLayer.CUTOUT, layers ? LAYER_CUTOUT_MATERIAL : CUTOUT_MATERIAL);
        renderTypeToMaterialMap.put(BlockLayer.CUTOUT_MIPPED, CUTOUT_MIPPED_MATERIAL);
        renderTypeToMaterialMap.put(BlockLayer.TRANSLUCENT, TRANSLUCENT_MATERIAL);

        return new RenderPassConfiguration<>(renderTypeToMaterialMap,
                vanillaRenderStages,
                SOLID_MATERIAL,
                CUTOUT_MIPPED_MATERIAL,
                TRANSLUCENT_MATERIAL);
    }
}
