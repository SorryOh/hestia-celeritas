package org.taumc.celeritas.impl.render.terrain.compile.task;

// Hestia build (2026-10-12): the 1.8.9 branch only, with gen2 names.
// - Block entities go to the section's culled list, as vanilla draws them only for visible sections; ones whose
//   renderer draws off screen (beacons) also go to the global list, as in vanilla. Upstream's 1.8.9 branch put every
//   block entity in the global list, so all of them were drawn every frame.
// - Blocks, light, biomes and block entities are read from the task's section copies (WorldSlice), never from the
//   live world, which the main thread keeps changing; upstream's 1.8.9 branch read the live chunk.
// - Block models go straight into Celeritas's buffers (BlockModelPipeline); only fluids still go through vanilla's
//   renderer and a BufferBuilder that is copied afterwards, as upstream's 1.8.9 branch drew every block.
// - Visibility comes from vanilla 1.8.9's own ChunkOcclusionGraph instead of upstream's copy of Minecraft 1.16's.

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.world.ChunkOcclusionGraph;
import net.minecraft.client.render.world.OcclusionData;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import org.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import org.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import org.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;
import org.embeddedt.embeddium.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import org.embeddedt.embeddium.impl.render.chunk.occlusion.GraphDirection;
import org.embeddedt.embeddium.impl.render.chunk.occlusion.VisibilityEncoding;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.embeddedt.embeddium.impl.util.task.CancellationToken;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11C;
import org.taumc.celeritas.impl.render.terrain.compile.PrimitiveChunkBuildContext;
import org.taumc.celeritas.impl.world.WorldSlice;
import org.taumc.celeritas.impl.world.cloned.ChunkRenderContext;

public class ChunkBuilderMeshingTask extends ChunkBuilderTask<ChunkBuildOutput> {
    private final RenderSection render;
    private final int buildTime;
    private final Vector3d camera;
    private final ChunkRenderContext renderContext;

    public ChunkBuilderMeshingTask(RenderSection render, ChunkRenderContext context, int time, Vector3d camera) {
        this.render = render;
        this.buildTime = time;
        this.camera = camera;
        this.renderContext = context;
    }

    @Override
    public ChunkBuildOutput execute(ChunkBuildContext context, CancellationToken cancellationToken) {
        PrimitiveChunkBuildContext buildContext = (PrimitiveChunkBuildContext)context;
        WorldSlice slice = buildContext.slice;
        slice.copyData(this.renderContext);

        try {
            return this.build(buildContext, slice, cancellationToken);
        } finally {
            slice.reset();
        }
    }

    private ChunkBuildOutput build(PrimitiveChunkBuildContext buildContext, WorldSlice slice, CancellationToken cancellationToken) {
        var renderData = new MinecraftBuiltRenderSectionData<Object, BlockEntity>();
        ChunkOcclusionGraph occluder = new ChunkOcclusionGraph();

        ChunkBuildBuffers buffers = buildContext.buffers;
        buffers.init(renderData, this.render.getSectionIndex());

        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        int maxX = minX + 16;
        int maxY = minY + 16;
        int maxZ = minZ + 16;

        var tesselator = buildContext.tesselator;
        var models = buildContext.models;
        var passes = buffers.getRenderPassConfiguration();

        // Initialise with minX/minY/minZ so initial getBlockState crash context is correct
        var blockPos = new BlockPos.Mutable(minX, minY, minZ);
        var renderBlocks = Minecraft.getInstance().getBlockRenderDispatcher();
        var blockEntityRenderers = BlockEntityRenderDispatcher.INSTANCE;

        tesselator.offset(-minX, -minY, -minZ);
        models.begin(buffers, minX, minY, minZ);

        try {
            for (int y = minY; y < maxY; y++) {
                if (cancellationToken.isCancelled()) {
                    return null;
                }

                for (int z = minZ; z < maxZ; z++) {
                    for (int x = minX; x < maxX; x++) {
                        blockPos.set(x, y, z);

                        var blockState = slice.getBlockState(x, y, z);
                        var block = blockState.getBlock();

                        if (block == Blocks.AIR) {
                            continue;
                        }

                        if (block.hasBlockEntity()) {
                            BlockEntity blockEntity = slice.getBlockEntity(blockPos);
                            if (blockEntity != null) {
                                BlockEntityRenderer<BlockEntity> renderer = blockEntityRenderers.getRenderer(blockEntity);
                                if (renderer != null) {
                                    renderData.culledBlockEntities.add(blockEntity);
                                    if (renderer.shouldRenderOffScreen()) {
                                        renderData.globalBlockEntities.add(blockEntity);
                                    }
                                }
                            }
                        }

                        var material = passes.getMaterialForRenderType(block.getRenderLayer());

                        // BlockRenderDispatcher.render's cases: 3 model, 1 fluid; the rest draw nothing here.
                        switch (block.getRenderType()) {
                            case 3 -> models.render(slice, renderBlocks.getModel(blockState, slice, blockPos), blockState, blockPos, material);
                            case 1 -> {
                                tesselator.begin(GL11C.GL_QUADS, DefaultVertexFormat.BLOCK);
                                renderBlocks.render(blockState, blockPos, slice, tesselator);
                                tesselator.end();
                                buildContext.copyRawBuffer(buildContext.rawVertexData(), tesselator.getVertexCount(), buffers, material);
                                tesselator.clear();
                            }
                            default -> {
                            }
                        }

                        if (block.isOpaque()) {
                            occluder.close(blockPos);
                        }
                    }
                }
            }
        } finally {
            tesselator.offset(0, 0, 0);
        }

        Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes = BuiltSectionMeshParts.groupFromBuildBuffers(buffers, (float) (camera.x - minX), (float) (camera.y - minY), (float) (camera.z - minZ));

        if (!meshes.isEmpty()) {
            renderData.hasBlockGeometry = true;
        }

        encodeVisibilityData(occluder, renderData);

        return new ChunkBuildOutput(this.render, renderData, meshes, this.buildTime);
    }

    private static final Direction[] FACINGS = new Direction[GraphDirection.COUNT];

    static {
        FACINGS[GraphDirection.UP] = Direction.UP;
        FACINGS[GraphDirection.DOWN] = Direction.DOWN;
        FACINGS[GraphDirection.WEST] = Direction.WEST;
        FACINGS[GraphDirection.EAST] = Direction.EAST;
        FACINGS[GraphDirection.NORTH] = Direction.NORTH;
        FACINGS[GraphDirection.SOUTH] = Direction.SOUTH;
    }

    private static void encodeVisibilityData(ChunkOcclusionGraph occluder, BuiltRenderSectionData renderData) {
        OcclusionData data = occluder.resolve();
        renderData.visibilityData = VisibilityEncoding.encode((from, to) -> data.isVisible(FACINGS[from], FACINGS[to]));
    }

}
