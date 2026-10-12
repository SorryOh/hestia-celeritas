package org.taumc.celeritas.impl.render.terrain.compile.pipeline;

// Hestia build (2026-10-12): new. Vanilla 1.8.9 culls a face only against full opaque cubes (and a slab against a
// matching slab), so faces pressed against a slab's or stair's solid side were still drawn.

import net.minecraft.block.Block;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.state.BlockState;
import net.minecraft.client.render.block.BlockLayer;
import net.minecraft.client.render.model.block.BakedModel;
import net.minecraft.client.render.model.block.BakedQuad;
import net.minecraft.client.resource.model.BlockModelShaper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Culls block faces hidden by a neighbouring slab or stair. A face that lies on the boundary between two blocks faces
 * into the neighbour, and faces are one-sided, so it can only be seen from inside that neighbour; where the neighbour's
 * solid geometry touches the boundary, nothing can be there to see it. That part of the boundary is where the
 * neighbour's model has faces culled toward this block (vanilla's cullface faces), which for slabs and stairs are sides
 * of their closed, opaque boxes.
 * <p>
 * Each side's covered part is a 16 x 16 grid over the face, one cell per model pixel: covered cells are those the
 * neighbour's faces cover completely, and a face is hidden only when every cell it touches is covered.
 */
public final class FaceOcclusion {
    private static final Direction[] DIRECTIONS = Direction.values();

    /** Per neighbour block state (as rendered, stairs with their shape): the covered cells of each side. */
    private static final ConcurrentHashMap<BlockState, Shape> SHAPES = new ConcurrentHashMap<>();
    private static volatile int generation;

    private record Shape(int generation, long[][] sides) {
    }

    private FaceOcclusion() {
    }

    /** Block models were rebuilt (resource reload): shapes worked out from the old ones no longer apply. */
    public static void onModelsReloaded() {
        generation++;
        SHAPES.clear();
    }

    /**
     * The covered cells of the neighbour's side that faces back toward the block being built ({@code side}), or null
     * when it covers nothing (or isn't a slab or stair).
     */
    @Nullable
    public static long[] getCoveredCells(WorldView view, BlockState neighbor, BlockPos neighborPos, Direction side, BlockModelShaper models) {
        Block block = neighbor.getBlock();
        // Double slabs are full opaque cubes, which vanilla's own check already culls against.
        if (!(block instanceof SlabBlock || block instanceof StairsBlock) || block.isOpaque()
                || block.getRenderType() != 3 || block.getRenderLayer() != BlockLayer.SOLID) {
            return null;
        }

        BlockState rendered = neighbor;
        try {
            // A stair's shape (straight, inner or outer corner) comes from its neighbours, as BlockRenderDispatcher reads it.
            rendered = block.resolveVirtualProperties(neighbor, view, neighborPos);
        } catch (Exception ignored) {
        }

        int current = generation;
        Shape shape = SHAPES.get(rendered);
        if (shape == null || shape.generation() != current) {
            shape = new Shape(current, measure(models.getModel(rendered)));
            SHAPES.put(rendered, shape);
        }
        return shape.sides()[side.getId()];
    }

    private static long[][] measure(BakedModel model) {
        long[][] sides = new long[DIRECTIONS.length][];
        for (Direction side : DIRECTIONS) {
            long[] cells = null;
            List<BakedQuad> quads = model.getQuads(side);
            for (int i = 0, n = quads.size(); i < n; i++) {
                int[] vertices = quads.get(i).getVertices();
                if (!isOnBoundary(vertices, side)) {
                    continue;
                }
                if (cells == null) {
                    cells = new long[4];
                }
                // Only cells the face covers completely
                fill(cells, (int) Math.ceil(minU(vertices, side) * 16.0f), (int) Math.floor(maxU(vertices, side) * 16.0f),
                        (int) Math.ceil(minV(vertices, side) * 16.0f), (int) Math.floor(maxV(vertices, side) * 16.0f));
            }
            sides[side.getId()] = cells;
        }
        return sides;
    }

    /**
     * Whether this face of the block (on its {@code side}, model coordinates 0 to 1) lies on the boundary and inside the
     * neighbour's covered cells.
     */
    public static boolean isHidden(int[] vertices, Direction side, long[] covered) {
        if (!isOnBoundary(vertices, side)) {
            return false;
        }
        // Every cell the face touches
        int u0 = Math.max(0, (int) Math.floor(minU(vertices, side) * 16.0f));
        int u1 = Math.min(16, (int) Math.ceil(maxU(vertices, side) * 16.0f));
        int v0 = Math.max(0, (int) Math.floor(minV(vertices, side) * 16.0f));
        int v1 = Math.min(16, (int) Math.ceil(maxV(vertices, side) * 16.0f));
        if (u0 >= u1 || v0 >= v1) {
            return false;
        }
        int rowMask = ((1 << (v1 - v0)) - 1) << v0;
        for (int u = u0; u < u1; u++) {
            int row = (int) (covered[u >> 2] >>> ((u & 3) << 4)) & 0xFFFF;
            if ((row & rowMask) != rowMask) {
                return false;
            }
        }
        return true;
    }

    private static void fill(long[] cells, int u0, int u1, int v0, int v1) {
        u0 = Math.max(0, u0);
        u1 = Math.min(16, u1);
        v0 = Math.max(0, v0);
        v1 = Math.min(16, v1);
        if (u0 >= u1 || v0 >= v1) {
            return;
        }
        long rowMask = (long) (((1 << (v1 - v0)) - 1) << v0);
        for (int u = u0; u < u1; u++) {
            cells[u >> 2] |= rowMask << ((u & 3) << 4);
        }
    }

    // A quad's vertices in BakedQuad's vertex data: 7 ints per vertex, x, y and z first.
    private static float coordinate(int[] vertices, int vertex, int axis) {
        return Float.intBitsToFloat(vertices[vertex * 7 + axis]);
    }

    // The axis across the side (0 x, 1 y, 2 z) and the two axes along it, u and v.
    private static int normalAxis(Direction side) {
        return side.getOffsetX() != 0 ? 0 : side.getOffsetY() != 0 ? 1 : 2;
    }

    private static int uAxis(Direction side) {
        return normalAxis(side) == 0 ? 1 : 0;
    }

    private static int vAxis(Direction side) {
        return normalAxis(side) == 2 ? 1 : 2;
    }

    private static boolean isOnBoundary(int[] vertices, Direction side) {
        if (vertices.length < 28) {
            return false;
        }
        int axis = normalAxis(side);
        float boundary = (side.getOffsetX() + side.getOffsetY() + side.getOffsetZ()) > 0 ? 1.0f : 0.0f;
        for (int i = 0; i < 4; i++) {
            if (coordinate(vertices, i, axis) != boundary) {
                return false;
            }
        }
        return true;
    }

    private static float minU(int[] vertices, Direction side) {
        int axis = uAxis(side);
        return Math.min(Math.min(coordinate(vertices, 0, axis), coordinate(vertices, 1, axis)), Math.min(coordinate(vertices, 2, axis), coordinate(vertices, 3, axis)));
    }

    private static float maxU(int[] vertices, Direction side) {
        int axis = uAxis(side);
        return Math.max(Math.max(coordinate(vertices, 0, axis), coordinate(vertices, 1, axis)), Math.max(coordinate(vertices, 2, axis), coordinate(vertices, 3, axis)));
    }

    private static float minV(int[] vertices, Direction side) {
        int axis = vAxis(side);
        return Math.min(Math.min(coordinate(vertices, 0, axis), coordinate(vertices, 1, axis)), Math.min(coordinate(vertices, 2, axis), coordinate(vertices, 3, axis)));
    }

    private static float maxV(int[] vertices, Direction side) {
        int axis = vAxis(side);
        return Math.max(Math.max(coordinate(vertices, 0, axis), coordinate(vertices, 1, axis)), Math.max(coordinate(vertices, 2, axis), coordinate(vertices, 3, axis)));
    }
}
