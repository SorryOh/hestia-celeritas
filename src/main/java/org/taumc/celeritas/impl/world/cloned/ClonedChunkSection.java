package org.taumc.celeritas.impl.world.cloned;

// Hestia build (2026-10-12): written for 1.8.9 after Celeritas's 1.12.2 version, which keeps references to the live
// section and its light arrays. A 1.8.9 section is small (a char per block and two 2 KiB light arrays), so this one
// copies them: builder threads never read data the main thread may be changing.

import it.unimi.dsi.fastutil.shorts.Short2ReferenceMap;
import it.unimi.dsi.fastutil.shorts.Short2ReferenceOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.state.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkNibbleStorage;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.WorldChunkSection;
import net.minecraft.world.gen.WorldGeneratorType;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * A copy of one 16x16x16 chunk section, made on the main thread, that chunk builder threads read instead of the world.
 * Reads give what vanilla's chunk-compile region cache gets from the chunk: block states, block and sky light (with
 * {@code WorldChunk.getLight}'s rules for sections without storage and for unloaded chunks), the column biomes and the
 * block entities. Immutable once made, so any number of builds may share it.
 */
public final class ClonedChunkSection {
    private static final BlockState AIR = Blocks.AIR.defaultState();
    private static final int SKY_DEFAULT = LightType.SKY.defaultValue;
    private static final int BLOCK_DEFAULT = LightType.BLOCK.defaultValue;

    /** Above or below the world: air, and every light read gives the light type's default, as vanilla's region does. */
    public static final ClonedChunkSection OUTSIDE_WORLD = new ClonedChunkSection();

    // What the copy was made from, so the cache can tell when the chunk or its section object has been replaced; null
    // outside the world.
    final WorldChunk chunk;
    final WorldChunkSection storage;
    final int sectionY;

    // Block state ids (Block.STATE_REGISTRY), indexed y << 8 | z << 4 | x; null when the whole section is air.
    private final char[] blockStates;
    // Light nibbles as ChunkNibbleStorage keeps them; null without section storage, sky light also in worlds without sky.
    private final byte[] blockLight;
    private final byte[] skyLight;
    // For a section without storage: the chunk's height map, as sky light there is 15 at or above it and 0 below.
    private final int[] heightMap;
    // An unloaded chunk (EmptyChunk) or outside the world: light reads give the light type's default.
    private final boolean defaultLight;
    // The column biome ids, z << 4 | x, resolved like WorldChunk.getBiome; null outside the world.
    private final byte[] biomeIds;
    // Block entities by local index; null when the section has none.
    private final Short2ReferenceMap<BlockEntity> blockEntities;

    long lastUsedNanos;

    private ClonedChunkSection() {
        this.chunk = null;
        this.storage = null;
        this.sectionY = -1;
        this.blockStates = null;
        this.blockLight = null;
        this.skyLight = null;
        this.heightMap = null;
        this.defaultLight = true;
        this.biomeIds = null;
        this.blockEntities = null;
    }

    private ClonedChunkSection(World world, WorldChunk chunk, int sectionY) {
        this.chunk = chunk;
        this.storage = getStorage(chunk, sectionY);
        this.sectionY = sectionY;
        this.defaultLight = chunk instanceof EmptyChunk;

        WorldChunkSection storage = this.storage;
        int originX = chunk.chunkX << 4;
        int originY = sectionY << 4;
        int originZ = chunk.chunkZ << 4;

        if (world.getGeneratorType() == WorldGeneratorType.DEBUG_ALL_BLOCK_STATES) {
            // The debug world's blocks come from WorldChunk.getBlockState itself, not from the section storage.
            this.blockStates = copyBlockStatesThroughChunk(chunk, originX, originY, originZ);
        } else {
            this.blockStates = storage != null ? storage.getBlockStates().clone() : null;
        }

        if (storage != null) {
            this.blockLight = copyNibbles(storage.getBlockLightStorage());
            this.skyLight = copyNibbles(storage.getSkyLightStorage());
            this.heightMap = null;
        } else {
            this.blockLight = null;
            this.skyLight = null;
            this.heightMap = this.defaultLight ? null : chunk.getHeightMap().clone();
        }

        this.biomeIds = copyBiomeIds(world, chunk, originX, originY, originZ);
        this.blockEntities = copyBlockEntities(chunk, sectionY);
    }

    /** Copies the section at these section coordinates. Main thread only. */
    static ClonedChunkSection create(World world, int x, int y, int z) {
        return new ClonedChunkSection(world, world.getChunkAt(x, z), y);
    }

    /** Whether the chunk and section objects this copy came from are still the world's. Main thread only. */
    boolean isCurrent(World world, int x, int z) {
        WorldChunk chunk = world.getChunkAt(x, z);
        return chunk == this.chunk && getStorage(chunk, this.sectionY) == this.storage;
    }

    @Nullable
    static WorldChunkSection getStorage(WorldChunk chunk, int sectionY) {
        WorldChunkSection[] sections = chunk.getSections();
        return sectionY >= 0 && sectionY < sections.length ? sections[sectionY] : null;
    }

    private static char[] copyBlockStatesThroughChunk(WorldChunk chunk, int originX, int originY, int originZ) {
        char[] states = new char[16 * 16 * 16];
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    states[y << 8 | z << 4 | x] = (char) Block.STATE_REGISTRY.getId(chunk.getBlockState(pos.set(originX + x, originY + y, originZ + z)));
                }
            }
        }
        return states;
    }

    @Nullable
    private static byte[] copyNibbles(@Nullable ChunkNibbleStorage storage) {
        return storage != null ? storage.getData().clone() : null;
    }

    private static byte[] copyBiomeIds(World world, WorldChunk chunk, int originX, int originY, int originZ) {
        byte[] ids = chunk.getBiomes().clone();
        if (chunk instanceof EmptyChunk) {
            // An unloaded chunk (shared by all of them) has no biomes; 255 reads as plains. Vanilla's region would ask
            // the client's biome generator here, but rebuilds only run while all neighbouring chunks are loaded.
            return ids;
        }
        for (int i = 0; i < ids.length; i++) {
            if ((ids[i] & 255) == 255) {
                // Not generated yet: WorldChunk.getBiome asks the biome source and stores the answer in the chunk.
                Biome biome = chunk.getBiome(new BlockPos(originX + (i & 15), originY, originZ + (i >> 4)), world.dimension.getBiomeSource());
                ids[i] = (byte) biome.id;
            }
        }
        return ids;
    }

    /**
     * The block entities of one section by local index, or null when it has none. Main thread only. Rebuilds take their
     * own section's list fresh when the task is made: the client creates block entities without scheduling a rebuild
     * (a sign's text packet does), so a cached copy's list can be behind.
     */
    @Nullable
    public static Short2ReferenceMap<BlockEntity> copyBlockEntities(WorldChunk chunk, int sectionY) {
        Map<BlockPos, BlockEntity> chunkEntities = chunk.getBlockEntities();
        if (chunkEntities.isEmpty()) {
            return null;
        }
        Short2ReferenceMap<BlockEntity> entities = null;
        for (Map.Entry<BlockPos, BlockEntity> entry : chunkEntities.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockEntity blockEntity = entry.getValue();
            // Removed block entities read as absent, as WorldChunk.getBlockEntity treats them.
            if (pos.getY() >> 4 != sectionY || blockEntity == null || blockEntity.isRemoved()) {
                continue;
            }
            if (entities == null) {
                entities = new Short2ReferenceOpenHashMap<>();
            }
            entities.put(localIndex(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15), blockEntity);
        }
        return entities;
    }

    public static short localIndex(int x, int y, int z) {
        return (short) (y << 8 | z << 4 | x);
    }

    /** The block state at these coordinates inside the section (0-15 each). */
    public BlockState getBlockState(int x, int y, int z) {
        char[] states = this.blockStates;
        if (states == null) {
            return AIR;
        }
        // WorldChunkSection.getBlockState: ids missing from the registry read as air.
        BlockState state = Block.STATE_REGISTRY.get(states[y << 8 | z << 4 | x]);
        return state != null ? state : AIR;
    }

    /**
     * Sky or block light at these coordinates inside the section, as {@code WorldChunk.getLight} gives it; the world's
     * lack of sky is handled by the caller, which never asks for sky light then.
     */
    public int getLight(boolean sky, int x, int y, int z) {
        if (this.defaultLight) {
            return sky ? SKY_DEFAULT : BLOCK_DEFAULT;
        }
        int[] heightMap = this.heightMap;
        if (heightMap != null) {
            // WorldChunk.hasSkyAccess: at or above the height map
            return (this.sectionY << 4) + y >= heightMap[z << 4 | x] ? (sky ? SKY_DEFAULT : BLOCK_DEFAULT) : 0;
        }
        byte[] nibbles = sky ? this.skyLight : this.blockLight;
        if (nibbles == null) {
            return 0;
        }
        // ChunkNibbleStorage: two values per byte, the even index in the low half
        int index = y << 8 | z << 4 | x;
        return (nibbles[index >> 1] >> ((index & 1) << 2)) & 15;
    }

    /** The biome of the column at these coordinates inside the chunk (0-15 each). */
    public Biome getBiome(int x, int z) {
        byte[] ids = this.biomeIds;
        if (ids == null) {
            return Biome.PLAINS;
        }
        Biome biome = Biome.byId(ids[z << 4 | x] & 255);
        return biome != null ? biome : Biome.PLAINS;
    }

    @Nullable
    public BlockEntity getBlockEntity(int x, int y, int z) {
        Short2ReferenceMap<BlockEntity> entities = this.blockEntities;
        return entities != null ? entities.get(localIndex(x, y, z)) : null;
    }
}
