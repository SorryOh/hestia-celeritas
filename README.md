# Celeritas (Hestia build) for Minecraft 1.8.9

An unofficial build of [Celeritas](https://git.taumc.org/embeddedt/celeritas)'s terrain renderer for Minecraft 1.8.9 on
Ornithe, made for the Hestia client. Celeritas's developers don't support third-party builds: report problems with this
jar to Hestia, not to them.

- **Based on** upstream's `ornithe` project, 1.8.9 target, at commit
  [7818b33](https://git.taumc.org/embeddedt/celeritas/commit/7818b33172086d0576456a3acf04e1548ddbed74) (2026-10-10),
  with `celeritas-common` 2.5.0-pre.1 from upstream's Maven (https://maven.taumc.org/releases).
- **Needs** Minecraft 1.8.9, Fabric Loader 0.19.5 or newer on Ornithe's intermediary generation 2, Java 21 or newer,
  and [Pylon](https://github.com/notdevcody/pylon) (LWJGL 3).

## License

LGPL-3.0, like upstream: see `COPYING.LESSER` and `COPYING`. The jar nests two unmodified jars with Fabric's jar-in-jar:

| Jar | License |
|---|---|
| celeritas-common 2.5.0-pre.1 | LGPL-3.0 (includes grondag/bitraster, Apache-2.0) |
| JOML 1.10.5 | MIT |

Fabric Loom adds a generated `fabric.mod.json` to each nested jar; their other files are byte-identical to the
published ones.

## Building

```
gradlew build
```

The jar lands in `build/libs/`. Gradle downloads the JDK 25 toolchain if it isn't installed. `gradlew publishToMavenLocal`
puts the jar where Hestia's build looks for it.

## Changes from upstream

The first commit in this repository is upstream's `ornithe` sources as they were at 7818b33; the commits after it hold
every change. In short (2026-10-12):

- **Built for Ornithe's intermediary generation 2** (Ploceus 1.18.1, Feather gen2 build 2, Loom 1.18.4), which Hestia,
  Sarcio and the other Ornithe 1.8.9 mods use; upstream builds 1.8.9 on generation 1 (Feather build 28), and jars of
  the two generations can't run together. Only the 1.8.9 branch of upstream's multi-version sources is kept, renamed to
  generation 2 names, in a plain Gradle build instead of Stonecutter.
- **celeritas-common and JOML are nested**, not shaded into the jar, so either can be swapped.
- **No glGet per frame:** fog values come from GlStateManager's cached state and GameRenderer's fog colour (as in
  Celeritas's 1.12.2 version), and the matrices from the copy vanilla's Camera reads once per frame. Upstream asked the
  driver with seven glGet calls per terrain pass; each one waits for the driver. (Vanilla's own camera and frustum
  setup makes seven more per frame, for the matrices and the viewport; Hestia answers those from CPU copies.)
- **Frustum:** the background visibility search gets its own copy of vanilla's frustum planes, which vanilla rewrites at
  the start of every frame while that search may still run; and boxes are moved back from the eye to the camera
  entity's feet, where vanilla's planes are anchored (upstream was off by the eye height, about 1.6 blocks).
- **GL state:** the solid pass turns the alpha test off through GlStateManager and restores its cached state; upstream
  called GL11 directly, which left GlStateManager's cache wrong.
- **Lightmap:** vanilla 1.8.9 samples the lightmap at `(light + 8) / 256`, the centre of each light level's texel
  (`GameRenderer.enableLightMap`'s texture matrix); Celeritas's shader samples at `light / 256`, the edge between two
  levels, which blends them: full daylight came out about 10% darker than vanilla. The block and sky light of every
  vertex get the half texel added when the vertices are copied, so the shader samples where vanilla does.
- **OpenGL debug output:** `-Dceleritas.enableGLDebug=true` (upstream's switch) installs celeritas-common's debug
  message callback at the first world load; upstream's 1.8.9 target never installed it.
- **Vanilla's unused terrain renderer isn't created:** no chunk grid (at render distance 12, 10,000 sections with four
  vertex buffers each) and no vanilla chunk builder threads.
- **Block entities** are drawn only for visible sections, as in vanilla (beacon-style ones that draw off screen also
  globally, as in vanilla); upstream's 1.8.9 branch drew every loaded block entity every frame.
- **Visibility inside a section** comes from vanilla 1.8.9's own occlusion graph instead of upstream's copy of
  Minecraft 1.16's code.
- **Chunk snapshots (as in Celeritas's 1.12.2 version):** builder threads no longer read the live world, which the main
  thread keeps changing; upstream's 1.8.9 branch read the live chunk. When a rebuild task is made, the main thread hands
  it copies of the 3x3x3 sections around its section (block states, block and sky light, column biomes, block
  entities), cached and shared between neighbouring tasks; a copy is dropped when its section is scheduled for a
  rebuild (every block, light or chunk data change schedules the sections around it), when its chunk or section object
  is replaced, or after 5 s unused. The 1.12.2 version keeps references to the live section; a 1.8.9 section is small
  (12 KiB), so this one copies it. The task's own section's block entities are listed fresh for each task, since the
  client creates some (a sign's text packet does) without scheduling a rebuild.
- **Reads give vanilla's answers:** `WorldSlice` follows vanilla's chunk-compile region cache (`RenderChunkRegion`,
  `WorldRegion`, `WorldChunk.getLight`) with the same per-position block state and light caches. Two differences: a
  light read applies each call's minimum block light instead of reusing the first call's, and biomes below y 0 or above
  255 are the column's (vanilla asks the biome generator there; nothing there gets tinted). Biome colours
  (grass, leaves, water) average vanilla's 3x3 columns with vanilla's rounding, but each column's colour is worked out
  once per task instead of nine times per tinted face (`BiomeColorsMixin`); celeritas-common's box blur rounds twice
  and could be off by one per channel. Hestia's "Smooth biomes" off still gives each block its own column's colour.
- **Block models go straight into Celeritas's buffers** (`BlockModelPipeline`): upstream's 1.8.9 branch drew every
  block with vanilla's renderer into a BufferBuilder and copied it afterwards; now only fluids do. Lighting stays
  vanilla's exactly, because the pipeline calls vanilla's own ambient occlusion code (`BlockModelRenderer`'s
  `AmbientOcclusionFace` and face shape helper) and repeats vanilla's tints, colour rounding and plant offsets,
  including the flat-lighting path's offset hash that leaves out the height. Celeritas's 1.12.2 version has its own fast
  renderer (off by default there) that lights with Celeritas's smooth-lighting pipeline, which looks different from
  vanilla's.
- **Faces hidden by slabs and stairs are culled** (`FaceOcclusion`): vanilla culls a face only against a full opaque
  cube (and a slab against a matching slab). A face on the block boundary can only be seen from the neighbour's side,
  so where a neighbouring slab's or stair's own boundary faces cover it (their closed boxes touch the boundary there),
  it is left out: a block's side against a stair's back, stairs side by side, a slab against a stair, the ground under a
  slab. Coverage is a 16 x 16 grid per side worked out from the neighbour's model (with the stair's shape), cached per
  block state and dropped when the models reload. Only faces vanilla's models mark as culled faces qualify: leaves, for
  example, have none in 1.8.9, so vanilla and this build both draw every leaf face.
- **Opaque quads move to the solid pass:** quads from cutout and cutout-mipped blocks whose sprite has no transparent
  pixel in any animation frame (measured when the sprite loads; `TextureAtlasSpriteMixin`) and whose texture
  coordinates stay inside the sprite (`FaceBakeryMixin`, `BakedQuadMixin`) go to the solid pass, which skips the alpha
  test, without mipmaps where the block's layer had none. In practice that is the grass block's top, bottom and side
  faces; its tinted side overlay stays in the cutout pass and still draws over them. As in Celeritas's quad analyzer, a
  face's quads only move when no opaque quad comes after a see-through one. The render pass configuration's default
  solid material is now the solid one (upstream passed cutout_mipped).
- **Fog like vanilla's:** celeritas-common's fog include eases the fog in with `smoothstep(start, end, distance)`, where
  1.8.9's fixed-function GL_LINEAR fog rises in a straight line (a quarter of the way in, vanilla is 25% fogged,
  smoothstep 16%). `ShaderLoaderMixin` changes that expression in the shader source as it is loaded; the nested jar
  stays as it is. `-Dceleritas.upstreamFog=true` keeps celeritas-common's curve. The fog's shape already matched:
  vanilla switches NVIDIA cards to radial fog, and Celeritas measures the distance spherically.
- **Settings for other mods** (`org.taumc.celeritas.api.CeleritasSettings`, used by Hestia's settings screen): the
  chunk builder thread count (0 is celeritas-common's own choice), and whether fully fogged chunks are drawn. Celeritas
  searches a circle of the render distance and skips sections behind the fog, so the corners of the square of loaded
  chunks, which vanilla draws as fog-coloured shapes against the sky, are left out; with the option on, the search
  reaches the corners (the render distance times the square root of 2) and fog occlusion is off. Both apply when the
  world renderer is made again (F3+A).
- **Section visibility for other mods** (`org.taumc.celeritas.api.SectionVisibility`, 2026-10-13, build 5): whether
  the current frame's visibility search reached any chunk section a box touches, answered by celeritas-common's own
  `isBoxVisible` (the question Sodium's entity culling asks). Hestia uses it to skip entities hidden from the camera.
- **Quad sprites for other mods** (`org.taumc.celeritas.api.QuadSprites`, 2026-10-13, build 6): a provider another mod
  sets may draw a block model's quad with a different sprite of the block atlas while chunks are built; the quad keeps
  its shape, light and color, and its texture coordinates move to the same place in the new sprite. It is asked for
  every quad whose sprite FaceBakery recorded and whose coordinates stay inside it. A quad drawn with another sprite
  moves to the solid pass only if both sprites are opaque, and an animated replacement is reported like the quad's own
  sprite would be. With no provider nothing changes. Hestia uses it for resource packs' connected textures.
- **Block quads for other mods** (`org.taumc.celeritas.api.BlockQuads`, 2026-10-14, build 7): a handler another mod
  sets is handed every model block quad whose sprite is known, with its light and shade already worked out as vanilla
  does, and may draw it differently: with another sprite turned by quarter turns and mirrored, with another color in
  place of its tint (also on quads without one), in parts (a rectangle of its texture square drawn with the same part
  of another sprite, the corners' light and color blended across it), with extra quads over it in the cutout pass
  (optionally at full brightness), or not at all. Quads the handler leaves alone go on to the `QuadSprites` provider
  and are drawn as before. With no handler nothing changes. Hestia uses it for resource packs' natural textures,
  better grass, connected textures' overlay and compact methods, emissive block textures and custom block colors.
- **Section occlusion for other mods** (`org.taumc.celeritas.api.SectionOcclusion`, 2026-10-14, build 8): while
  another mod sets a test, the chunk renderer draws without the visible sections the test reports hidden. The frame's
  render lists are filtered once (`OccludedRenderLists`, on the first terrain pass after `beginFrame`) and every pass
  draws from them; a region's filtered list is kept while its sections stay the same. The visibility search, block
  entities, entities and animated sprites still use the unfiltered lists. With no test nothing changes. A listener
  (`setGeometryListener`) hears of every section whose geometry changes: each upload of a rebuild, and each section
  added or removed (`updateSectionInfo`, on the render thread before the terrain draws). Hestia uses both to leave out
  sections wholly behind the depth of an earlier frame while the GPU is the slower side, trusting that depth nowhere a
  section changed since it was drawn.
- **Visible-only texture animation:** every frame, the visible sections report the animated sprites they use to
  `org.taumc.celeritas.api.VisibleSprites`, where another mod (Hestia) can listen and advance only those sprites; with
  no listener nothing changes. celeritas-common's `GenericSectionSpriteTicker` marks the sprites of every section of a
  render region (8 x 4 x 8 sections) that has one visible section with sprites, and builds a new set after every
  search; `VisibleSectionSpriteTicker` marks only the visible sections' sprites and allocates only when that set
  changes. Block models note their quads' animated sprites while a section builds, and fluids their still and flowing
  sprites. A quad that didn't come from FaceBakery (vanilla bakes every block quad there; other mods may not) is looked
  up once by its texture coordinates among the block atlas's animated sprites (`AnimatedSprites`, `TextureAtlasMixin`).
- **F3** shows the chunk build rate and average build time, the number of section copies and the main thread's time per
  rebuild task for taking them. The left column's `C:` line ends with the number of visible animated sprites
  (celeritas-common's place for the sprite ticker's line).
- **Empty chunk sections** get no rebuild tasks (as in Celeritas's 1.12.2 version).
- **Fewer per-frame allocations:** reused matrices, fog colour array, frustum copies, entity BlockPos and block entity
  context; indexed loops instead of iterators. A few remain in celeritas-common's own API (the viewport, camera state
  and shader option objects each frame). Rebuild tasks reuse their thread's slice instead of a new 64 KiB region cache
  each, and look for missing block entities without a new BlockPos per block; the model pipeline reuses the face shape
  arrays and ambient occlusion object that vanilla's renderer allocates for every block.
- **Small:** the render section manager calls celeritas-common's non-deprecated constructor (no shadow pass, as the
  deprecated one did).
- **Dropped:** the mixins and helpers for versions before 1.8; `MinecraftMixin` (beta 1.7.3 window fixes; 1.8.9 already
  makes a resizable window with a 24-bit depth buffer, and Pylon owns the window); `ChunkCacheMixin` (on 1.8.9 the
  client world has its own chunk cache, so the server-side events it recorded were never read and piled up in
  singleplayer); the dynamic mixin plugin (a fixed list instead).
- **Sarcio:** `fabric.mod.json` asks Sarcio to skip its `bugfix.render.WorldRendererMixin`, which patches
  `WorldRenderer.setupRender`, a method Celeritas replaces. Celeritas's entity loop already draws entities outside the
  world's height, which is what that patch was for.
