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
import org.taumc.celeritas.api.BlockQuads;
import org.taumc.celeritas.api.QuadSprites;
import org.taumc.celeritas.impl.extensions.BakedQuadExtension;
import org.taumc.celeritas.impl.render.terrain.PrimitiveRenderPassConfigurationBuilder;
import org.taumc.celeritas.impl.texture.AnimatedSprites;

import java.util.BitSet;
import java.util.Collection;
import java.util.List;

/**
 * Renders a block's baked model as vanilla's BlockModelRenderer does (smooth or flat lighting, tints, the random offset
 * of plants), writing each quad straight into Celeritas's buffers. On top of vanilla:
 * <ul>
 * <li>faces hidden by a neighbouring slab or stair are left out ({@link FaceOcclusion});</li>
 * <li>quads with fully opaque sprites from cutout blocks go to the solid pass, which skips the alpha test: the grass
 * block's top, bottom and sides, for example, while its tinted side overlay stays in the cutout pass;</li>
 * <li>another mod's {@link QuadSprites} provider (connected textures) may draw a quad with a different sprite;</li>
 * <li>another mod's {@link BlockQuads} handler may draw it turned, recolored, in parts, with overlays, or not at all.</li>
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
    // The block's state and the QuadSprites provider for it (null when none is set).
    private BlockState state;
    private QuadSprites.Provider spriteProvider;
    // The quad being emitted: the sprite it was baked with and the one it is drawn with instead (null: unchanged).
    private TextureAtlasSprite fromSprite;
    private TextureAtlasSprite toSprite;

    // BlockQuads: the handler for this block (null when none is set) and the quad handed to it, with its light, shade
    // (1 on the flat path, where vanilla bakes the face shade into the colors) and own tint (-1 for none).
    private BlockQuads.Handler quadHandler;
    private final QuadOutput quadOutput = new QuadOutput();
    private BakedQuad handledQuad;
    private int[] handledData;
    private TextureAtlasSprite handledSprite;
    private final int[] handledLight = new int[4];
    private final float[] handledShade = new float[4];
    private int handledTint;
    private Material handledMaterial;
    private Material handledOpaqueMaterial;

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
        this.state = state;
        this.spriteProvider = QuadSprites.getProvider();
        this.quadHandler = BlockQuads.getHandler();

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
            int[] light = ao.lightMap;
            if (this.quadHandler != null
                    && this.handleQuad(view, block, pos, quad, face, data, shade, light[0], light[1], light[2], light[3], material, opaqueMaterial)) {
                continue;
            }
            this.chooseSprite(view, pos, quad, face);

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
            if (this.quadHandler != null
                    && this.handleQuad(view, block, pos, quad, quad.getFace(), data, null, light, light, light, light, material, opaqueMaterial)) {
                continue;
            }
            this.chooseSprite(view, pos, quad, quad.getFace());

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

    // QuadSprites: the sprite the quad is drawn with, when a provider gives it another one.
    private void chooseSprite(WorldView view, BlockPos pos, BakedQuad quad, Direction face) {
        this.fromSprite = null;
        this.toSprite = null;
        QuadSprites.Provider provider = this.spriteProvider;
        if (provider == null) {
            return;
        }
        TextureAtlasSprite from = QuadSprites.getSprite(quad);
        if (from == null) {
            return;
        }
        TextureAtlasSprite to = provider.getSprite(view, this.state, pos, face, quad, from);
        if (to != null && to != from && from.getUMax() > from.getUMin() && from.getVMax() > from.getVMin()) {
            this.fromSprite = from;
            this.toSprite = to;
        }
    }

    // BlockQuads: hands the quad (its light and shade worked out) to the handler; true when the handler drew it.
    private boolean handleQuad(WorldView view, Block block, BlockPos pos, BakedQuad quad, Direction face, int[] data, float[] shade,
                               int light0, int light1, int light2, int light3, Material material, Material opaqueMaterial) {
        TextureAtlasSprite sprite = QuadSprites.getSprite(quad);
        if (sprite == null || !(sprite.getUMax() > sprite.getUMin()) || !(sprite.getVMax() > sprite.getVMin())) {
            return false;
        }
        this.handledQuad = quad;
        this.handledData = data;
        this.handledSprite = sprite;
        this.handledLight[0] = light0;
        this.handledLight[1] = light1;
        this.handledLight[2] = light2;
        this.handledLight[3] = light3;
        for (int v = 0; v < 4; v++) {
            this.handledShade[v] = shade != null ? shade[v] : 1.0F;
        }
        this.handledTint = quad.hasTint() ? this.tint(view, block, pos, quad.getTintIndex()) & 0xFFFFFF : -1;
        this.handledMaterial = material;
        this.handledOpaqueMaterial = opaqueMaterial;
        return this.quadHandler.handle(view, this.state, pos, face, quad, sprite, this.quadOutput);
    }

    /** What a BlockQuads handler draws, into this section's buffers. */
    private final class QuadOutput implements BlockQuads.Output {
        @Override
        public void quad(TextureAtlasSprite sprite, int rotation, boolean flip) {
            BlockModelPipeline.this.emitHandled(sprite, rotation, flip, BlockModelPipeline.this.handledTint);
        }

        @Override
        public void tinted(TextureAtlasSprite sprite, int rotation, boolean flip, int color) {
            BlockModelPipeline.this.emitHandled(sprite, rotation, flip, color & 0xFFFFFF);
        }

        @Override
        public void part(TextureAtlasSprite sprite, float s0, float t0, float s1, float t1) {
            BlockModelPipeline.this.emitPart(sprite, s0, t0, s1, t1);
        }

        @Override
        public void overlay(TextureAtlasSprite sprite, int color, boolean fullBright) {
            BlockModelPipeline.this.emitOverlay(sprite, color, fullBright);
        }
    }

    // The handled quad's texture coordinate of vertex v, as a fraction (0-1) across its sprite.
    private float handledS(int v) {
        TextureAtlasSprite from = this.handledSprite;
        return (Float.intBitsToFloat(this.handledData[v * 7 + 4]) - from.getUMin()) / (from.getUMax() - from.getUMin());
    }

    private float handledT(int v) {
        TextureAtlasSprite from = this.handledSprite;
        return (Float.intBitsToFloat(this.handledData[v * 7 + 5]) - from.getVMin()) / (from.getVMax() - from.getVMin());
    }

    // The vertex color of the handled quad: the baked color times the shade, times the tint (0xRRGGBB) unless -1.
    private int handledColor(int v, int tint) {
        float shade = this.handledShade[v];
        if (tint < 0) {
            return multiplyColor(this.handledData[v * 7 + 3], shade, shade, shade);
        }
        return multiplyColor(this.handledData[v * 7 + 3], shade * (float) (tint >> 16 & 255) / 255.0F,
                shade * (float) (tint >> 8 & 255) / 255.0F, shade * (float) (tint & 255) / 255.0F);
    }

    private void emitHandled(TextureAtlasSprite to, int rotation, boolean flip, int tint) {
        ChunkVertexEncoder.Vertex[] out = this.vertices;
        int[] data = this.handledData;
        for (int v = 0; v < 4; v++) {
            ChunkVertexEncoder.Vertex vertex = out[v];
            int base = v * 7;
            vertex.x = this.blockX + Float.intBitsToFloat(data[base]);
            vertex.y = this.blockY + Float.intBitsToFloat(data[base + 1]);
            vertex.z = this.blockZ + Float.intBitsToFloat(data[base + 2]);
            float s = this.handledS(v);
            float t = this.handledT(v);
            // Quarter turns, then the mirror, about the sprite's middle.
            for (int r = rotation & 3; r > 0; r--) {
                float turned = t;
                t = 1.0F - s;
                s = turned;
            }
            if (flip) {
                s = 1.0F - s;
            }
            vertex.u = to.getUMin() + s * (to.getUMax() - to.getUMin());
            vertex.v = to.getVMin() + t * (to.getVMax() - to.getVMin());
            vertex.color = this.handledColor(v, tint);
            vertex.light = this.handledLight[v] + LIGHTMAP_TEXEL_CENTRE;
        }
        this.toSprite = to == this.handledSprite ? null : to;
        this.push(out, this.pick(this.handledQuad, this.handledMaterial, this.handledOpaqueMaterial));
        this.toSprite = null;
    }

    private void emitPart(TextureAtlasSprite to, float s0, float t0, float s1, float t1) {
        // The corners' places on the texture square; parts need each vertex at a different corner.
        int[] corner = this.cornerIndex;
        boolean corners = true;
        for (int v = 0; v < 4 && corners; v++) {
            float s = this.handledS(v);
            float t = this.handledT(v);
            int cs = s < 0.01F ? 0 : s > 0.99F ? 1 : -1;
            int ct = t < 0.01F ? 0 : t > 0.99F ? 1 : -1;
            if (cs < 0 || ct < 0) {
                corners = false;
            } else {
                corner[v] = cs | ct << 1;
            }
        }
        if (corners) {
            int seen = 0;
            for (int v = 0; v < 4; v++) {
                seen |= 1 << corner[v];
            }
            corners = seen == 15;
        }
        if (!corners) {
            this.emitHandled(to, 0, false, this.handledTint);
            return;
        }
        int[] byCorner = this.vertexAtCorner;
        for (int v = 0; v < 4; v++) {
            byCorner[corner[v]] = v;
        }
        int[] data = this.handledData;
        ChunkVertexEncoder.Vertex[] out = this.vertices;
        for (int v = 0; v < 4; v++) {
            ChunkVertexEncoder.Vertex vertex = out[v];
            float s = (corner[v] & 1) == 0 ? s0 : s1;
            float t = (corner[v] & 2) == 0 ? t0 : t1;
            // Bilinear weights of the four corners (0: s0 t0 ... as corner = cs | ct << 1).
            float w0 = (1.0F - s) * (1.0F - t);
            float w1 = s * (1.0F - t);
            float w2 = (1.0F - s) * t;
            float w3 = s * t;
            int a = byCorner[0] * 7, b = byCorner[1] * 7, c = byCorner[2] * 7, d = byCorner[3] * 7;
            vertex.x = this.blockX + w0 * Float.intBitsToFloat(data[a]) + w1 * Float.intBitsToFloat(data[b]) + w2 * Float.intBitsToFloat(data[c]) + w3 * Float.intBitsToFloat(data[d]);
            vertex.y = this.blockY + w0 * Float.intBitsToFloat(data[a + 1]) + w1 * Float.intBitsToFloat(data[b + 1]) + w2 * Float.intBitsToFloat(data[c + 1]) + w3 * Float.intBitsToFloat(data[d + 1]);
            vertex.z = this.blockZ + w0 * Float.intBitsToFloat(data[a + 2]) + w1 * Float.intBitsToFloat(data[b + 2]) + w2 * Float.intBitsToFloat(data[c + 2]) + w3 * Float.intBitsToFloat(data[d + 2]);
            vertex.u = to.getUMin() + s * (to.getUMax() - to.getUMin());
            vertex.v = to.getVMin() + t * (to.getVMax() - to.getVMin());
            vertex.color = blendColors(this.handledColor(byCorner[0], this.handledTint), this.handledColor(byCorner[1], this.handledTint),
                    this.handledColor(byCorner[2], this.handledTint), this.handledColor(byCorner[3], this.handledTint), w0, w1, w2, w3);
            vertex.light = blendLight(this.handledLight[byCorner[0]], this.handledLight[byCorner[1]], this.handledLight[byCorner[2]],
                    this.handledLight[byCorner[3]], w0, w1, w2, w3) + LIGHTMAP_TEXEL_CENTRE;
        }
        this.toSprite = to == this.handledSprite ? null : to;
        this.push(out, this.pick(this.handledQuad, this.handledMaterial, this.handledOpaqueMaterial));
        this.toSprite = null;
    }

    private final int[] cornerIndex = new int[4];
    private final int[] vertexAtCorner = new int[4];

    private void emitOverlay(TextureAtlasSprite to, int color, boolean fullBright) {
        ChunkVertexEncoder.Vertex[] out = this.vertices;
        int[] data = this.handledData;
        for (int v = 0; v < 4; v++) {
            ChunkVertexEncoder.Vertex vertex = out[v];
            int base = v * 7;
            vertex.x = this.blockX + Float.intBitsToFloat(data[base]);
            vertex.y = this.blockY + Float.intBitsToFloat(data[base + 1]);
            vertex.z = this.blockZ + Float.intBitsToFloat(data[base + 2]);
            vertex.u = to.getUMin() + this.handledS(v) * (to.getUMax() - to.getUMin());
            vertex.v = to.getVMin() + this.handledT(v) * (to.getVMax() - to.getVMin());
            if (fullBright) {
                vertex.color = data[base + 3] | 0x00FFFFFF;
                vertex.light = 0x00F000F0 + LIGHTMAP_TEXEL_CENTRE;
            } else {
                vertex.color = this.handledColor(v, color >= 0 ? color & 0xFFFFFF : this.handledTint);
                vertex.light = this.handledLight[v] + LIGHTMAP_TEXEL_CENTRE;
            }
        }
        if (this.animatedSprites != null && AnimatedSprites.isAnimated(to)) {
            this.animatedSprites.add(to);
        }
        this.push(out, PrimitiveRenderPassConfigurationBuilder.CUTOUT_MIPPED_MATERIAL);
    }

    // Per-channel bilinear blend of four little-endian colors.
    private static int blendColors(int c0, int c1, int c2, int c3, float w0, float w1, float w2, float w3) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            float value = w0 * (c0 >>> shift & 255) + w1 * (c1 >>> shift & 255) + w2 * (c2 >>> shift & 255) + w3 * (c3 >>> shift & 255);
            result |= Math.min(255, Math.round(value)) << shift;
        }
        return result;
    }

    // The same for packed lightmap coordinates (block light low, sky light high).
    private static int blendLight(int l0, int l1, int l2, int l3, float w0, float w1, float w2, float w3) {
        int block = Math.round(w0 * (l0 & 0xFFFF) + w1 * (l1 & 0xFFFF) + w2 * (l2 & 0xFFFF) + w3 * (l3 & 0xFFFF));
        int sky = Math.round(w0 * (l0 >>> 16) + w1 * (l1 >>> 16) + w2 * (l2 >>> 16) + w3 * (l3 >>> 16));
        return sky << 16 | block;
    }

    private void emit(int[] data, int light0, int light1, int light2, int light3, Material material) {
        ChunkVertexEncoder.Vertex[] out = this.vertices;
        TextureAtlasSprite from = this.fromSprite;
        TextureAtlasSprite to = this.toSprite;
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
        if (to != null) {
            // The same place in the new sprite.
            float scaleU = (to.getUMax() - to.getUMin()) / (from.getUMax() - from.getUMin());
            float scaleV = (to.getVMax() - to.getVMin()) / (from.getVMax() - from.getVMin());
            for (int v = 0; v < 4; v++) {
                ChunkVertexEncoder.Vertex vertex = out[v];
                vertex.u = to.getUMin() + (vertex.u - from.getUMin()) * scaleU;
                vertex.v = to.getVMin() + (vertex.v - from.getVMin()) * scaleV;
            }
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

    // The quad's material, noting its sprite when that is animated. A quad drawn with another sprite (QuadSprites) moves
    // to the solid pass only if both sprites are opaque, so the order canUseOpaquePass worked out still holds.
    private Material pick(BakedQuad quad, Material material, Material opaqueMaterial) {
        TextureAtlasSprite replacement = this.toSprite;
        if (this.animatedSprites != null) {
            if (replacement != null) {
                if (AnimatedSprites.isAnimated(replacement)) {
                    this.animatedSprites.add(replacement);
                }
            } else {
                TextureAtlasSprite animated = ((BakedQuadExtension) quad).celeritas$getAnimatedSprite();
                if (animated != null) {
                    this.animatedSprites.add(animated);
                }
            }
        }
        if (opaqueMaterial == null || !isOpaque(quad)) {
            return material;
        }
        if (replacement != null && SpriteTransparencyLevel.Holder.getTransparencyLevel(replacement) != SpriteTransparencyLevel.OPAQUE) {
            return material;
        }
        return opaqueMaterial;
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
