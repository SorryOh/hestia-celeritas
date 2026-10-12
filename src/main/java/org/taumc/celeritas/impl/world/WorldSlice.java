package org.taumc.celeritas.impl.world;

// Hestia build (2026-10-12): written for 1.8.9 after Celeritas's 1.12.2 WorldSlice. It gives the answers vanilla 1.8.9's
// chunk-compile region cache (RenderChunkRegion and WorldRegion) gives, from section copies instead of the live world,
// with the same per-position block state and light caches, and a biome colour cache for BiomeColors.

import it.unimi.dsi.fastutil.shorts.Short2ReferenceMap;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockState;
import net.minecraft.client.world.color.BiomeColors;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.WorldGeneratorType;
import org.embeddedt.embeddium.impl.util.position.SectionPos;
import org.jetbrains.annotations.Nullable;
import org.taumc.celeritas.impl.world.cloned.ChunkRenderContext;
import org.taumc.celeritas.impl.world.cloned.ClonedChunkSection;
import org.taumc.celeritas.impl.world.cloned.ClonedChunkSectionCache;

import java.util.Arrays;

/**
 * The world as one chunk rebuild sees it: copies of the 3x3x3 sections around the section being built, taken on the
 * main thread when the task was created. Each builder thread owns one slice and reuses it for every task.
 * <p>
 * Positions within {@link #RADIUS} blocks of the section (the area vanilla's region cache covers) have their block
 * state and light cached for the task, as vanilla does; anything else inside the copied sections is read straight from
 * the copies. Outside them, reads give air, default light, no block entity and the nearest copied column's biome:
 * vanilla's block renderers never read more than a few blocks out.
 */
public final class WorldSlice implements WorldView {
    private static final BlockState AIR = Blocks.AIR.defaultState();
    private static final int SKY_DEFAULT = LightType.SKY.defaultValue;
    private static final int BLOCK_DEFAULT = LightType.BLOCK.defaultValue;

    /** The section copies cover this many sections on each side of the origin. */
    private static final int SECTION_RADIUS = 1;
    private static final int SECTION_LENGTH = SECTION_RADIUS * 2 + 1;
    public static final int SECTION_COUNT = SECTION_LENGTH * SECTION_LENGTH * SECTION_LENGTH;

    /** Blocks around the section with cached block states and light: vanilla's RenderChunkRegion covers the same. */
    private static final int RADIUS = 2;
    private static final int CACHE_LENGTH = 16 + RADIUS * 2;
    private static final int CACHE_SIZE = CACHE_LENGTH * CACHE_LENGTH * CACHE_LENGTH;

    /** Biome colours are blended over vanilla's 3x3 columns, so the colour cache covers the section plus one column. */
    private static final int BLEND_RADIUS = 1;
    private static final int COLOR_LENGTH = 16 + BLEND_RADIUS * 2;
    private static final int COLOR_LAYER = COLOR_LENGTH * COLOR_LENGTH;
    // grass, foliage and water fog: BiomeColors' three colour providers
    private static final int COLOR_PROVIDERS = 3;

    private final boolean hasNoSky;
    private final WorldGeneratorType generatorType;

    private ClonedChunkSection[] sections;
    // The origin section's block entities, listed when the task was made; null when it has none.
    private Short2ReferenceMap<BlockEntity> originBlockEntities;
    // The origin section's coordinates and its lowest corner in blocks.
    private int originX, originY, originZ;
    private int minX, minY, minZ;

    // Cached per task; null and -1 mean not read yet.
    private final BlockState[] blockStates = new BlockState[CACHE_SIZE];
    private final int[] light = new int[CACHE_SIZE];

    // The unblended colour of each column around the section, per provider and height; valid where the stamp is the task's.
    private final BiomeColors.ColorProvider[] colorProviders = new BiomeColors.ColorProvider[COLOR_PROVIDERS];
    private final int[] colors = new int[COLOR_PROVIDERS * 16 * COLOR_LAYER];
    private final int[] colorStamps = new int[COLOR_PROVIDERS * 16 * COLOR_LAYER];
    private int stamp;
    private final BlockPos.Mutable colorPos = new BlockPos.Mutable();

    public WorldSlice(World world) {
        this.hasNoSky = world.dimension.hasNoSky();
        this.generatorType = world.getGeneratorType();
    }

    /**
     * Takes the copies a rebuild of this section needs from the cache, making the missing ones, and lists the section's
     * block entities. Render thread only.
     */
    public static ChunkRenderContext prepare(ClonedChunkSectionCache cache, WorldChunk chunk, int x, int y, int z) {
        ClonedChunkSection[] sections = new ClonedChunkSection[SECTION_COUNT];

        for (int dy = -SECTION_RADIUS; dy <= SECTION_RADIUS; dy++) {
            for (int dz = -SECTION_RADIUS; dz <= SECTION_RADIUS; dz++) {
                for (int dx = -SECTION_RADIUS; dx <= SECTION_RADIUS; dx++) {
                    sections[sectionIndex(dx + SECTION_RADIUS, dy + SECTION_RADIUS, dz + SECTION_RADIUS)] = cache.acquire(x + dx, y + dy, z + dz);
                }
            }
        }

        return new ChunkRenderContext(new SectionPos(x, y, z), sections, ClonedChunkSection.copyBlockEntities(chunk, y));
    }

    public static int sectionIndex(int x, int y, int z) {
        return (y * SECTION_LENGTH + z) * SECTION_LENGTH + x;
    }

    /** Points this slice at a task's copies and forgets the previous task's cached values. */
    public void copyData(ChunkRenderContext context) {
        SectionPos origin = context.origin();
        this.sections = context.sections();
        this.originBlockEntities = context.blockEntities();
        this.originX = origin.x();
        this.originY = origin.y();
        this.originZ = origin.z();
        this.minX = origin.minX();
        this.minY = origin.minY();
        this.minZ = origin.minZ();

        Arrays.fill(this.blockStates, null);
        Arrays.fill(this.light, -1);

        if (++this.stamp == 0) {
            // After 2^32 tasks: start the stamps over, so no old colour can pass for a current one.
            Arrays.fill(this.colorStamps, 0);
            this.stamp = 1;
        }
    }

    /** Lets go of the task's copies once it is done. */
    public void reset() {
        this.sections = null;
        this.originBlockEntities = null;
    }

    // The cache index of a position relative to the cached area's lowest corner, or -1 outside it.
    private static int cacheIndex(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= CACHE_LENGTH || y >= CACHE_LENGTH || z >= CACHE_LENGTH) {
            return -1;
        }
        return (y * CACHE_LENGTH + z) * CACHE_LENGTH + x;
    }

    /** The copy holding this block, or null outside the copied sections. */
    @Nullable
    private ClonedChunkSection sectionAt(int x, int y, int z) {
        int sx = (x >> 4) - this.originX + SECTION_RADIUS;
        int sy = (y >> 4) - this.originY + SECTION_RADIUS;
        int sz = (z >> 4) - this.originZ + SECTION_RADIUS;
        if ((sx | sy | sz) < 0 || sx >= SECTION_LENGTH || sy >= SECTION_LENGTH || sz >= SECTION_LENGTH) {
            return null;
        }
        return this.sections[sectionIndex(sx, sy, sz)];
    }

    // Blocks

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState getBlockState(int x, int y, int z) {
        int index = cacheIndex(x - this.minX + RADIUS, y - this.minY + RADIUS, z - this.minZ + RADIUS);
        if (index < 0) {
            return this.readBlockState(x, y, z);
        }
        BlockState state = this.blockStates[index];
        if (state == null) {
            state = this.readBlockState(x, y, z);
            this.blockStates[index] = state;
        }
        return state;
    }

    private BlockState readBlockState(int x, int y, int z) {
        ClonedChunkSection section = this.sectionAt(x, y, z);
        return section != null ? section.getBlockState(x & 15, y & 15, z & 15) : AIR;
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (x >> 4 == this.originX && y >> 4 == this.originY && z >> 4 == this.originZ) {
            Short2ReferenceMap<BlockEntity> entities = this.originBlockEntities;
            return entities != null ? entities.get(ClonedChunkSection.localIndex(x & 15, y & 15, z & 15)) : null;
        }
        ClonedChunkSection section = this.sectionAt(x, y, z);
        return section != null ? section.getBlockEntity(x & 15, y & 15, z & 15) : null;
    }

    @Override
    public boolean isAir(BlockPos pos) {
        return this.getBlockState(pos).getBlock().getMaterial() == Material.AIR;
    }

    @Override
    public boolean isEmpty() {
        // A slice only exists for a rebuild, and rebuilds only run for sections with blocks in them.
        return false;
    }

    @Override
    public int getDirectSignal(BlockPos pos, Direction direction) {
        BlockState state = this.getBlockState(pos);
        return state.getBlock().getDirectSignal(this, pos, state, direction);
    }

    @Override
    public WorldGeneratorType getGeneratorType() {
        return this.generatorType;
    }

    // Light

    /**
     * WorldRegion.getLightColor: sky light in bits 20-23, block light (at least {@code minBlockLight}) in bits 4-7.
     * Vanilla's region caches the first answer per position, whatever minimum later calls ask for; this caches the
     * light itself and applies each call's minimum, which only differs where a block gives off more light than its
     * own position holds.
     */
    @Override
    public int getLightColor(BlockPos pos, int minBlockLight) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int index = cacheIndex(x - this.minX + RADIUS, y - this.minY + RADIUS, z - this.minZ + RADIUS);
        int light;
        if (index < 0) {
            light = this.computeLight(x, y, z);
        } else {
            light = this.light[index];
            if (light == -1) {
                light = this.computeLight(x, y, z);
                this.light[index] = light;
            }
        }
        int block = (light >> 4) & 15;
        if (block < minBlockLight) {
            block = minBlockLight;
        }
        return (light & ~0xF0) | block << 4;
    }

    private int computeLight(int x, int y, int z) {
        int sky = this.hasNoSky ? 0 : this.getBrightness(true, x, y, z);
        int block = this.getBrightness(false, x, y, z);
        return sky << 20 | block << 4;
    }

    // WorldRegion.getBrightness: blocks like slabs and stairs take the brightest of their six neighbours.
    private int getBrightness(boolean sky, int x, int y, int z) {
        if (y < 0 || y >= 256) {
            return sky ? SKY_DEFAULT : BLOCK_DEFAULT;
        }
        if (!this.getBlockState(x, y, z).getBlock().usesNeighborLight()) {
            return this.getLight(sky, x, y, z);
        }
        int max = this.getLight(sky, x, y - 1, z);
        max = Math.max(max, this.getLight(sky, x, y + 1, z));
        max = Math.max(max, this.getLight(sky, x, y, z - 1));
        max = Math.max(max, this.getLight(sky, x, y, z + 1));
        max = Math.max(max, this.getLight(sky, x - 1, y, z));
        return Math.max(max, this.getLight(sky, x + 1, y, z));
    }

    // WorldRegion.getLight; outside the copies, as for an unloaded chunk.
    private int getLight(boolean sky, int x, int y, int z) {
        if (y < 0 || y >= 256) {
            return sky ? SKY_DEFAULT : BLOCK_DEFAULT;
        }
        ClonedChunkSection section = this.sectionAt(x, y, z);
        if (section == null) {
            return sky ? SKY_DEFAULT : BLOCK_DEFAULT;
        }
        return section.getLight(sky, x & 15, y & 15, z & 15);
    }

    // Biomes

    @Override
    public Biome getBiome(BlockPos pos) {
        return this.getBiome(pos.getX(), pos.getZ());
    }

    /** The biome of a column; columns outside the copied chunks take the nearest copied one's. */
    public Biome getBiome(int x, int z) {
        int cx = Math.clamp((x >> 4) - this.originX, -SECTION_RADIUS, SECTION_RADIUS) + SECTION_RADIUS;
        int cz = Math.clamp((z >> 4) - this.originZ, -SECTION_RADIUS, SECTION_RADIUS) + SECTION_RADIUS;
        int localX = x - ((this.originX + cx - SECTION_RADIUS) << 4);
        int localZ = z - ((this.originZ + cz - SECTION_RADIUS) << 4);
        // The origin's row of copies is always inside the world, so it holds the column biomes.
        return this.sections[sectionIndex(cx, SECTION_RADIUS, cz)].getBiome(Math.clamp(localX, 0, 15), Math.clamp(localZ, 0, 15));
    }

    /**
     * BiomeColors' blended colour: the average of the provider's colour over the 3x3 columns around the block, at the
     * block's height, rounded down per channel as vanilla does. Each column's colour is worked out once per task.
     */
    public int getBlendedColor(BiomeColors.ColorProvider provider, BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int slot = this.colorSlot(provider);
        int relY = y - this.minY;
        int relX = x - this.minX + BLEND_RADIUS;
        int relZ = z - this.minZ + BLEND_RADIUS;

        int r = 0, g = 0, b = 0;
        boolean cached = slot >= 0 && relY >= 0 && relY < 16 && relX >= BLEND_RADIUS && relX < 16 + BLEND_RADIUS && relZ >= BLEND_RADIUS && relZ < 16 + BLEND_RADIUS;

        for (int dz = -BLEND_RADIUS; dz <= BLEND_RADIUS; dz++) {
            for (int dx = -BLEND_RADIUS; dx <= BLEND_RADIUS; dx++) {
                int color = cached
                        ? this.columnColor(slot, provider, relY, relX + dx, relZ + dz)
                        : provider.getColor(this.getBiome(x + dx, z + dz), this.colorPos.set(x + dx, y, z + dz));
                r += (color & 0xFF0000) >> 16;
                g += (color & 0xFF00) >> 8;
                b += color & 0xFF;
            }
        }

        int count = (BLEND_RADIUS * 2 + 1) * (BLEND_RADIUS * 2 + 1);
        return (r / count & 255) << 16 | (g / count & 255) << 8 | b / count & 255;
    }

    private int columnColor(int slot, BiomeColors.ColorProvider provider, int relY, int relX, int relZ) {
        int index = (slot * 16 + relY) * COLOR_LAYER + relZ * COLOR_LENGTH + relX;
        if (this.colorStamps[index] == this.stamp) {
            return this.colors[index];
        }
        int x = this.minX - BLEND_RADIUS + relX;
        int z = this.minZ - BLEND_RADIUS + relZ;
        int color = provider.getColor(this.getBiome(x, z), this.colorPos.set(x, this.minY + relY, z));
        this.colors[index] = color;
        this.colorStamps[index] = this.stamp;
        return color;
    }

    // BiomeColors has three providers; each gets a slot the first time it shows up. -1: no slot left (another mod's).
    private int colorSlot(BiomeColors.ColorProvider provider) {
        BiomeColors.ColorProvider[] providers = this.colorProviders;
        for (int i = 0; i < providers.length; i++) {
            if (providers[i] == provider) {
                return i;
            }
            if (providers[i] == null) {
                providers[i] = provider;
                return i;
            }
        }
        return -1;
    }
}
