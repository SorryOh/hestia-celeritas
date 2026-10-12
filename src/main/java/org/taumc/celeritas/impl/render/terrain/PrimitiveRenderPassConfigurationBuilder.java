package org.taumc.celeritas.impl.render.terrain;

// Hestia build (2026-10-12): the 1.8.9 branch only, with gen2 names. The solid pass turns the alpha test off through
// GlStateManager and restores its cached state afterwards; upstream called GL11 directly, which left GlStateManager's
// cache saying the opposite of the real state.

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

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class PrimitiveRenderPassConfigurationBuilder {
    public static final TerrainRenderPass SOLID_PASS, CUTOUT_MIPPED_PASS, TRANSLUCENT_PASS;
    public static final Material SOLID_MATERIAL, CUTOUT_MATERIAL, CUTOUT_MIPPED_MATERIAL, TRANSLUCENT_MATERIAL;

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

        TRANSLUCENT_MATERIAL = new Material(TRANSLUCENT_PASS, AlphaCutoffParameter.ZERO, true);
        SOLID_MATERIAL = new Material(SOLID_PASS, AlphaCutoffParameter.ZERO, true);
        CUTOUT_MIPPED_MATERIAL = new Material(CUTOUT_MIPPED_PASS, AlphaCutoffParameter.ONE_TENTH, true);
        CUTOUT_MATERIAL = new Material(CUTOUT_MIPPED_PASS, AlphaCutoffParameter.ONE_TENTH, false);
    }

    public static RenderPassConfiguration<?> build(ChunkVertexType vertexType) {
        Map<BlockLayer, Collection<TerrainRenderPass>> vanillaRenderStages = new Reference2ReferenceOpenHashMap<>();
        vanillaRenderStages.put(BlockLayer.SOLID, List.of(SOLID_PASS, CUTOUT_MIPPED_PASS));
        vanillaRenderStages.put(BlockLayer.TRANSLUCENT, List.of(TRANSLUCENT_PASS));

        Map<BlockLayer, Material> renderTypeToMaterialMap = new Reference2ReferenceOpenHashMap<>();
        renderTypeToMaterialMap.put(BlockLayer.SOLID, SOLID_MATERIAL);
        renderTypeToMaterialMap.put(BlockLayer.CUTOUT, CUTOUT_MATERIAL);
        renderTypeToMaterialMap.put(BlockLayer.CUTOUT_MIPPED, CUTOUT_MIPPED_MATERIAL);
        renderTypeToMaterialMap.put(BlockLayer.TRANSLUCENT, TRANSLUCENT_MATERIAL);

        return new RenderPassConfiguration<>(renderTypeToMaterialMap,
                vanillaRenderStages,
                CUTOUT_MIPPED_MATERIAL,
                CUTOUT_MIPPED_MATERIAL,
                TRANSLUCENT_MATERIAL);
    }
}
