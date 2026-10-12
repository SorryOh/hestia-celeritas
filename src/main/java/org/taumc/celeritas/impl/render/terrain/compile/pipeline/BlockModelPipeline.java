package org.taumc.celeritas.impl.render.terrain.compile.pipeline;

// Hestia build (2026-10-12): new. Celeritas's 1.12.2 version has a fast model renderer of its own (VintageBlockRenderer,
// off by default there), which lights quads with Celeritas's smooth-lighting pipeline and so looks different from
// vanilla's. This one keeps vanilla 1.8.9's lighting exactly, by calling vanilla's own ambient occlusion code, and only
// replaces where the quads go: straight into Celeritas's buffers instead of a BufferBuilder copied afterwards.

import net.minecraft.block.Block;
import net.minecraft.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.model.block.BakedModel;
import net.minecraft.client.render.model.block.BakedQuad;
import net.minecraft.client.render.texture.TextureAtlasSprite;
import net.minecraft.client.render.texture.TextureUtil;
import net.minecraft.client.resource.model.BlockModelShaper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldView;
import org.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import org.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.embeddedt.embeddium.impl.util.QuadUtil;
import org.taumc.celeritas.impl.extensions.BakedQuadExtension;
import org.taumc.celeritas.impl.render.terrain.PrimitiveRenderPassConfigurationBuilder;

import java.util.BitSet;
import java.util.Collection;
import java.util.List;

/**
 * Renders a block's baked model as vanilla's BlockModelRenderer does (smooth or flat lighting, tints, the random offset
 * of plants), writing each quad straight into Celeritas's buffers. On top of vanilla:
 * <ul>
 * <li>faces hidden by a neighbouring slab or stair are left out ({@link FaceOcclusion});</li>
 * <li>quads with fully opaque sprites from cutout blocks go to the solid pass, which skips the alpha test: the grass
 * block's top, bottom and sides, for example, while its tinted side overlay stays in the cutout pass.</li>
 * </ul>
 * One per builder thread.
 */
public class BlockModelPipeline {
    private static final Direction[] DIRECTIONS = Direction.values();

    /** Vanilla's lightmap samples at the centre of each light level's texel; Celeritas's shader at its edge. */
    private static final int LIGHTMAP_TEXEL_CENTRE = 0x00080008;

    private final BlockModelRenderer vanilla;
    private final BlockModelShaper models;
    private final BlockModelRenderer.AmbientOcclusionFace ambientOcclusion;
    private final float[] faceShape = new float[DIRECTIONS.length * 2];
    private final BitSet faceFlags = new BitSet(3);
    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
    private final int[] colors = new int[4];
    private final BlockPos.Mutable neighborPos = new BlockPos.Mutable();

    private ChunkBuildBuffers buffers;
    // The section's animated sprites, for visible-only texture animation (VisibleSprites).
    private Collection<Object> animatedSprites;
    // The section's lowest corner, negated, as the BufferBuilder offset vanilla's chunk compile sets.
    private double offsetX, offsetY, offsetZ;

    // The block being rendered: its position in the section (with the random offset) and its last tint.
    private float blockX, blockY, blockZ;
    private int tintIndex;
    private int tint;

    public BlockModelPipeline(BlockModelRenderer vanilla, BlockModelShaper models) {
        this.vanilla = vanilla;
        this.models = models;
        this.ambientOcclusion = vanilla.new AmbientOcclusionFace();
    }

    /**
     * Starts a section: its quads go into these buffers, positioned relative to its lowest corner, and the animated
     * sprites they use into {@code animatedSprites}.
     */
    public void begin(ChunkBuildBuffers buffers, Collection<Object> animatedSprites, int originX, int originY, int originZ) {
        this.buffers = buffers;
        this.animatedSprites = animatedSprites;
        this.offsetX = -originX;
        this.offsetY = -originY;
        this.offsetZ = -originZ;
    }

    /**
     * BlockModelRenderer.render for one block. {@code model} comes from BlockRenderDispatcher.getModel, as vanilla's
     * dispatch gets it; {@code material} is the block's render layer's.
     */
    public void render(WorldView view, BakedModel model, BlockState state, BlockPos pos, Material material) {
        Block block = state.getBlock();
        block.updateShape(view, pos);
        boolean smooth = Minecraft.isAmbientOcclusionEnabled() && block.getLight() == 0 && model.useAmbientOcclusion();

        this.placeBlock(block, pos, smooth);
        this.tintIndex = Integer.MIN_VALUE;

        // Opaque quads may move to the solid pass: the mipmapped one from cutout_mipped, one without mipmaps from
        // cutout. The translucent pass blends, so its quads stay.
        Material opaqueMaterial = null;
        if (material == PrimitiveRenderPassConfigurationBuilder.CUTOUT_MIPPED_MATERIAL) {
            opaqueMaterial = PrimitiveRenderPassConfigurationBuilder.SOLID_MATERIAL;
        } else if (material == PrimitiveRenderPassConfigurationBuilder.CUTOUT_MATERIAL) {
            opaqueMaterial = PrimitiveRenderPassConfigurationBuilder.SOLID_UNMIPPED_MATERIAL;
        }
        // A plant's random offset moves its model off the block's boundaries.
        boolean checkNeighbors = block.getOffsetType() == Block.OffsetType.NONE;
        boolean opaquePassForGeneral = opaqueMaterial != null;

        for (Direction direction : DIRECTIONS) {
            List<BakedQuad> quads = model.getQuads(direction);
            if (quads.isEmpty()) {
                continue;
            }
            BlockPos.Mutable neighbor = this.neighborPos.set(pos.getX() + direction.getOffsetX(), pos.getY() + direction.getOffsetY(), pos.getZ() + direction.getOffsetZ());
            if (!block.shouldRenderFace(view, neighbor, direction)) {
                continue;
            }
            long[] covered = checkNeighbors ? FaceOcclusion.getCoveredCells(view, view.getBlockState(neighbor), neighbor, direction.getOpposite(), this.models) : null;
            boolean opaquePass = opaqueMaterial != null && canUseOpaquePass(quads);
            // As Celeritas's quad analyzer does: if any face's quads can't move, the quads without a cull face stay too.
            opaquePassForGeneral &= opaquePass;
            Material opaque = opaquePass ? opaqueMaterial : null;

            if (smooth) {
                this.renderSmooth(view, block, pos, quads, material, opaque, direction, covered);
            } else {
                this.renderFlat(view, block, pos, quads, block.getLightColor(view, neighbor), material, opaque, direction, covered);
            }
        }

        List<BakedQuad> quads = model.getQuads();
        if (quads.size() > 0) {
            Material opaque = opaquePassForGeneral && canUseOpaquePass(quads) ? opaqueMaterial : null;
            if (smooth) {
                this.renderSmooth(view, block, pos, quads, material, opaque, null, null);
            } else {
                this.renderFlat(view, block, pos, quads, -1, material, opaque, null, null);
            }
        }
    }

    // BlockModelRenderer's position for the block: plants (Block.getOffsetType) move by a hash of their position. The
    // smooth-lighting path hashes x, y and z (MathHelper.hashCode); the flat one has its own copy that leaves out y, so
    // vanilla moves plants when smooth lighting is switched.
    private void placeBlock(Block block, BlockPos pos, boolean smooth) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        Block.OffsetType offsetType = block.getOffsetType();
        if (offsetType != Block.OffsetType.NONE) {
            long hash;
            if (smooth) {
                hash = MathHelper.hashCode(pos);
            } else {
                hash = (long) (pos.getX() * 3129871) ^ (long) pos.getZ() * 116129781L;
                hash = hash * hash * 42317861L + hash * 11L;
            }
            x += ((double) ((float) (hash >> 16 & 15L) / 15.0F) - 0.5D) * 0.5D;
            z += ((double) ((float) (hash >> 24 & 15L) / 15.0F) - 0.5D) * 0.5D;
            if (offsetType == Block.OffsetType.XYZ) {
                y += ((double) ((float) (hash >> 20 & 15L) / 15.0F) - 1.0D) * 0.2D;
            }
        }
        // BufferBuilder.postPosition: (float) (x + offset) + the vertex's own coordinate
        this.blockX = (float) (x + this.offsetX);
        this.blockY = (float) (y + this.offsetY);
        this.blockZ = (float) (z + this.offsetZ);
    }

    // tesselateFaceWithAmbientOcclusion: vanilla's AmbientOcclusionFace works out each vertex's light and shade.
    private void renderSmooth(WorldView view, Block block, BlockPos pos, List<BakedQuad> quads, Material material, Material opaqueMaterial,
                              Direction cullFace, long[] covered) {
        BlockModelRenderer.AmbientOcclusionFace ao = this.ambientOcclusion;
        for (int i = 0, n = quads.size(); i < n; i++) {
            BakedQuad quad = quads.get(i);
            int[] data = quad.getVertices();
            if (covered != null && FaceOcclusion.isHidden(data, cullFace, covered)) {
                continue;
            }
            Direction face = quad.getFace();
            this.vanilla.computeFaceShape(block, data, face, this.faceShape, this.faceFlags);
            ao.compute(view, block, pos, face, this.faceShape, this.faceFlags);
            float[] shade = ao.brightness;

            if (quad.hasTint()) {
                int tint = this.tint(view, block, pos, quad.getTintIndex());
                float red = (float) (tint >> 16 & 255) / 255.0F;
                float green = (float) (tint >> 8 & 255) / 255.0F;
                float blue = (float) (tint & 255) / 255.0F;
                for (int v = 0; v < 4; v++) {
                    this.colors[v] = multiplyColor(data[v * 7 + 3], shade[v] * red, shade[v] * green, shade[v] * blue);
                }
            } else {
                for (int v = 0; v < 4; v++) {
                    this.colors[v] = multiplyColor(data[v * 7 + 3], shade[v], shade[v], shade[v]);
                }
            }

            int[] light = ao.lightMap;
            this.emit(data, light[0], light[1], light[2], light[3], pick(quad, material, opaqueMaterial));
        }
    }

    // tesselateFaceWithoutAmbientOcclusion: one light value per face, or per quad for quads without a cull face.
    private void renderFlat(WorldView view, Block block, BlockPos pos, List<BakedQuad> quads, int faceLight, Material material, Material opaqueMaterial,
                            Direction cullFace, long[] covered) {
        for (int i = 0, n = quads.size(); i < n; i++) {
            BakedQuad quad = quads.get(i);
            int[] data = quad.getVertices();
            if (covered != null && FaceOcclusion.isHidden(data, cullFace, covered)) {
                continue;
            }
            int light = faceLight;
            if (cullFace == null) {
                Direction face = quad.getFace();
                this.vanilla.computeFaceShape(block, data, face, null, this.faceFlags);
                light = this.faceFlags.get(0)
                        ? block.getLightColor(view, this.neighborPos.set(pos.getX() + face.getOffsetX(), pos.getY() + face.getOffsetY(), pos.getZ() + face.getOffsetZ()))
                        : block.getLightColor(view, pos);
            }

            if (quad.hasTint()) {
                int tint = this.tint(view, block, pos, quad.getTintIndex());
                float red = (float) (tint >> 16 & 255) / 255.0F;
                float green = (float) (tint >> 8 & 255) / 255.0F;
                float blue = (float) (tint & 255) / 255.0F;
                for (int v = 0; v < 4; v++) {
                    this.colors[v] = multiplyColor(data[v * 7 + 3], red, green, blue);
                }
            } else {
                for (int v = 0; v < 4; v++) {
                    this.colors[v] = data[v * 7 + 3];
                }
            }

            this.emit(data, light, light, light, light, pick(quad, material, opaqueMaterial));
        }
    }

    // Block.getColorTint for a tint index, the same for every quad of the block that uses it; anaglyph 3D as vanilla.
    private int tint(WorldView view, Block block, BlockPos pos, int index) {
        if (index != this.tintIndex) {
            int tint = block.getColorTint(view, pos, index);
            if (GameRenderer.anaglyphEnabled) {
                tint = TextureUtil.getAnaglyphColor(tint);
            }
            this.tint = tint;
            this.tintIndex = index;
        }
        return this.tint;
    }

    // BufferBuilder.multiplyColor on a little-endian color: red in the low byte.
    private static int multiplyColor(int color, float red, float green, float blue) {
        int r = (int) ((float) (color & 255) * red);
        int g = (int) ((float) (color >> 8 & 255) * green);
        int b = (int) ((float) (color >> 16 & 255) * blue);
        return color & 0xFF000000 | b << 16 | g << 8 | r;
    }

    private void emit(int[] data, int light0, int light1, int light2, int light3, Material material) {
        ChunkVertexEncoder.Vertex[] out = this.vertices;
        for (int v = 0; v < 4; v++) {
            ChunkVertexEncoder.Vertex vertex = out[v];
            int base = v * 7;
            vertex.x = this.blockX + Float.intBitsToFloat(data[base]);
            vertex.y = this.blockY + Float.intBitsToFloat(data[base + 1]);
            vertex.z = this.blockZ + Float.intBitsToFloat(data[base + 2]);
            vertex.color = this.colors[v];
            vertex.u = Float.intBitsToFloat(data[base + 4]);
            vertex.v = Float.intBitsToFloat(data[base + 5]);
        }
        out[0].light = light0 + LIGHTMAP_TEXEL_CENTRE;
        out[1].light = light1 + LIGHTMAP_TEXEL_CENTRE;
        out[2].light = light2 + LIGHTMAP_TEXEL_CENTRE;
        out[3].light = light3 + LIGHTMAP_TEXEL_CENTRE;

        this.push(out, material);
    }

    /** Adds a finished quad to the section's buffers, sorted by the direction it faces. */
    protected void push(ChunkVertexEncoder.Vertex[] quad, Material material) {
        int trueNormal = QuadUtil.calculateNormal(quad);
        for (int v = 0; v < 4; v++) {
            quad[v].trueNormal = trueNormal;
        }
        ModelQuadFacing facing = QuadUtil.findNormalFace(trueNormal);
        this.buffers.get(material).getVertexBuffer(facing).push(quad, material);
    }

    // The quad's material, noting its sprite when that is animated.
    private Material pick(BakedQuad quad, Material material, Material opaqueMaterial) {
        if (this.animatedSprites != null) {
            TextureAtlasSprite animated = ((BakedQuadExtension) quad).celeritas$getAnimatedSprite();
            if (animated != null) {
                this.animatedSprites.add(animated);
            }
        }
        return opaqueMaterial != null && isOpaque(quad) ? opaqueMaterial : material;
    }

    /**
     * Whether a face's quads can move to the solid pass: the solid pass draws before the cutout one, so an opaque quad
     * listed after a see-through one (and drawn over it) would end up under it.
     */
    private static boolean canUseOpaquePass(List<BakedQuad> quads) {
        boolean seenSeeThrough = false;
        for (int i = 0, n = quads.size(); i < n; i++) {
            if (isOpaque(quads.get(i))) {
                if (seenSeeThrough) {
                    return false;
                }
            } else {
                seenSeeThrough = true;
            }
        }
        return true;
    }

    private static boolean isOpaque(BakedQuad quad) {
        BakedQuadExtension extension = (BakedQuadExtension) quad;
        TextureAtlasSprite sprite = extension.celeritas$getSprite();
        return sprite != null && extension.celeritas$isSpriteTrusted()
                && SpriteTransparencyLevel.Holder.getTransparencyLevel(sprite) == SpriteTransparencyLevel.OPAQUE;
    }
}
