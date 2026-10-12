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
  driver with seven glGet calls per terrain pass; each one waits for the driver.
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
  Minecraft 1.16's code; blocks are read through vanilla's own chunk-compile region cache.
- **Empty chunk sections** get no rebuild tasks (as in Celeritas's 1.12.2 version).
- **Fewer per-frame allocations:** reused matrices, fog colour array, frustum copies, entity BlockPos and block entity
  context; indexed loops instead of iterators. A few remain in celeritas-common's own API (the viewport, camera state
  and shader option objects each frame).
- **Small:** the render section manager calls celeritas-common's non-deprecated constructor (no shadow pass, as the
  deprecated one did).
- **Dropped:** the mixins and helpers for versions before 1.8; `MinecraftMixin` (beta 1.7.3 window fixes; 1.8.9 already
  makes a resizable window with a 24-bit depth buffer, and Pylon owns the window); `ChunkCacheMixin` (on 1.8.9 the
  client world has its own chunk cache, so the server-side events it recorded were never read and piled up in
  singleplayer); the dynamic mixin plugin (a fixed list instead).
- **Sarcio:** `fabric.mod.json` asks Sarcio to skip its `bugfix.render.WorldRendererMixin`, which patches
  `WorldRenderer.setupRender`, a method Celeritas replaces. Celeritas's entity loop already draws entities outside the
  world's height, which is what that patch was for.
