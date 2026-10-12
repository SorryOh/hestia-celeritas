package org.taumc.celeritas.impl.render.terrain;

// Hestia build (2026-10-12): the 1.8.9 branch only, with gen2 names. Empty chunk sections count as visually empty (as
// in Celeritas's 1.12.2 version), so they get no rebuild tasks; upstream's 1.8.9 branch only skipped empty chunks. The
// camera block lookup reuses one BlockPos. No sky-light section list (pre-1.8 only). The constructor call names the
// shadow pass (none) instead of using the deprecated overload that does the same. Rebuild tasks get copies of the
// sections around theirs (WorldSlice.prepare, cached as in Celeritas's 1.12.2 version), and F3 shows the build rate.

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.WorldChunkSection;
import org.embeddedt.embeddium.impl.gl.device.CommandList;
import org.embeddedt.embeddium.impl.gl.device.RenderDevice;
import org.embeddedt.embeddium.impl.render.chunk.DefaultChunkRenderer;
import org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import org.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import org.embeddedt.embeddium.impl.render.chunk.fog.FogService;
import org.embeddedt.embeddium.impl.render.chunk.occlusion.AsyncOcclusionMode;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderTextureSlot;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.jetbrains.annotations.Nullable;
import org.taumc.celeritas.impl.render.terrain.compile.PrimitiveChunkBuildContext;
import org.taumc.celeritas.impl.render.terrain.compile.task.ChunkBuilderMeshingTask;
import org.taumc.celeritas.impl.render.terrain.fog.GLStateManagerFogService;
import org.taumc.celeritas.impl.world.WorldSlice;
import org.taumc.celeritas.impl.world.cloned.ChunkRenderContext;
import org.taumc.celeritas.impl.world.cloned.ClonedChunkSectionCache;

import java.util.Collection;

public class PrimitiveRenderSectionManager extends RenderSectionManager {
    private final World world;
    private final BlockPos.Mutable cameraBlockPos = new BlockPos.Mutable();
    private final BlockPos.Mutable blockEntityPos = new BlockPos.Mutable();
    private final ClonedChunkSectionCache sectionCache;
    // How long taking a rebuild's section copies takes on the render thread, averaged.
    private double prepareNanos;

    public PrimitiveRenderSectionManager(RenderPassConfiguration<?> configuration, World world, int renderDistance, CommandList commandList, int minSection, int maxSection, int requestedThreads) {
        super(configuration, () -> new PrimitiveChunkBuildContext(world, configuration), ChunkRenderer::new, renderDistance, commandList, minSection, maxSection, requestedThreads, false);
        this.world = world;
        this.sectionCache = new ClonedChunkSectionCache(world);
    }

    public static PrimitiveRenderSectionManager create(ChunkVertexType vertexType, World world, int renderDistance, CommandList commandList) {
        // TODO support thread option
        int idealThreadCount = 0;
        int maxSection = world.getHeight() / 16;
        return new PrimitiveRenderSectionManager(PrimitiveRenderPassConfigurationBuilder.build(vertexType), world, renderDistance, commandList,
                0, maxSection,
                idealThreadCount);
    }

    @Override
    protected AsyncOcclusionMode getAsyncOcclusionMode() {
        return AsyncOcclusionMode.EVERYTHING;
    }

    @Override
    protected boolean shouldRespectUpdateTaskQueueSizeLimit() {
        return true;
    }

    @Override
    protected boolean useRasterOcclusionCulling() {
        return false;
    }

    @Override
    public FogService getFogService() {
        return GLStateManagerFogService.INSTANCE;
    }

    @Override
    protected boolean useFogOcclusion() {
        return true;
    }

    @Override
    protected boolean shouldUseOcclusionCulling(Viewport positionedViewport, boolean spectator) {
        var camBlockPos = positionedViewport.getBlockCoord();
        var block = this.world.getBlockState(this.cameraBlockPos.set(camBlockPos.x(), camBlockPos.y(), camBlockPos.z())).getBlock();
        return !spectator || !block.isOpaque();
    }

    @Override
    protected boolean isSectionVisuallyEmpty(int x, int y, int z) {
        WorldChunk chunk = this.world.getChunkAt(x, z);
        if (chunk.isEmpty()) {
            return true;
        }
        WorldChunkSection[] sections = chunk.getSections();
        if (y < 0 || y >= sections.length) {
            return true;
        }
        WorldChunkSection section = sections[y];
        return section == null || section.isEmpty();
    }

    /**
     * Creates the block entities the section's blocks call for and doesn't have yet, as the chunk would when asked for
     * them (vanilla's region asks with QUEUED, which creates them on a later tick).
     */
    private void populateTileEntities(WorldChunk chunk, int sectionY) {
        WorldChunkSection section = chunk.getSections()[sectionY];
        BlockPos.Mutable pos = this.blockEntityPos;

        int sectionBlockY = sectionY * 16;
        int chunkBlockX = chunk.chunkX * 16;
        int chunkBlockZ = chunk.chunkZ * 16;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    var block = section.getBlock(x, y, z);
                    if (block.hasBlockEntity() && chunk.getBlockEntity(pos.set(chunkBlockX + x, sectionBlockY + y, chunkBlockZ + z), WorldChunk.BlockEntityCreationType.CHECK) == null) {
                        // The new block entity keeps the position it is created with, so it gets its own.
                        chunk.getBlockEntity(new BlockPos(chunkBlockX + x, sectionBlockY + y, chunkBlockZ + z), WorldChunk.BlockEntityCreationType.IMMEDIATE);
                    }
                }
            }
        }
    }

    @Override
    protected @Nullable ChunkBuilderTask<ChunkBuildOutput> createRebuildTask(RenderSection render, int frame) {
        int x = render.getChunkX(), y = render.getChunkY(), z = render.getChunkZ();

        if (isSectionVisuallyEmpty(x, y, z)) {
            return null;
        }

        WorldChunk chunk = this.world.getChunkAt(x, z);
        populateTileEntities(chunk, y);

        long start = System.nanoTime();
        ChunkRenderContext context = WorldSlice.prepare(this.sectionCache, chunk, x, y, z);
        long elapsed = System.nanoTime() - start;
        // A moving average for F3, like celeritas-common's build times.
        this.prepareNanos = this.prepareNanos == 0 ? elapsed : this.prepareNanos + 0.05 * (elapsed - this.prepareNanos);

        return new ChunkBuilderMeshingTask(render, context, frame, this.cameraPosition);
    }

    @Override
    protected boolean allowImportantRebuilds() {
        return false;
    }

    // Every block, light or chunk data change schedules the sections around it, which drops their copies; that also
    // holds for sections of chunks without a render section yet (the edge of the loaded area), whose copies other
    // sections' rebuilds use.
    @Override
    protected void scheduleSectionForRebuild(int x, int y, int z, boolean important) {
        this.sectionCache.invalidate(x, y, z);
        super.scheduleSectionForRebuild(x, y, z, important);
    }

    @Override
    protected void invalidateCachedSectionData(RenderSection section) {
        this.sectionCache.invalidate(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        super.invalidateCachedSectionData(section);
    }

    @Override
    public void updateChunks(boolean updateImmediately) {
        this.sectionCache.cleanup();
        super.updateChunks(updateImmediately);
    }

    @Override
    public void destroy() {
        super.destroy();
        this.sectionCache.clear();
    }

    @Override
    public Collection<String> getDebugStrings() {
        Collection<String> strings = super.getDebugStrings();
        var builds = this.jobMetricsTracker.getMetrics().get(ChunkBuildOutput.class);
        if (builds != null) {
            strings.add(String.format("Chunk builds: %d/s, %.2f ms avg", builds.getObservationsInLastTimeInterval(), builds.getAverageNanos(0) / 1.0e6));
        }
        strings.add(String.format("Section copies: %d, %.3f ms per rebuild", this.sectionCache.size(), this.prepareNanos / 1.0e6));
        return strings;
    }

    private static class ChunkRenderer extends DefaultChunkRenderer {

        public ChunkRenderer(RenderDevice device, RenderPassConfiguration<?> renderPassConfiguration) {
            super(device, renderPassConfiguration, GLStateManagerFogService.INSTANCE);
        }

        @Override
        protected void configureShaderInterface(ChunkShaderInterface shader) {
            shader.setTextureSlot(ChunkShaderTextureSlot.BLOCK, 0);
            shader.setTextureSlot(ChunkShaderTextureSlot.LIGHT, 1);
        }
    }
}
