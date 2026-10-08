# AR Velocity

AR Velocity (`ar_velocity`) is a client-side NeoForge 1.21.1 mod. It gives Iris shader packs a per-vertex motion vector attribute, `at_velocity`, for everything Accelerated Rendering draws through its compute path: entities, block entities and the items they carry. Its purpose is to stop moving entities from ghosting under temporal upscalers.

This is an unofficial community mod. It is not affiliated with or endorsed by the authors of Accelerated Rendering, Iris or Super Resolution. Please do not report problems with it to those projects.

## Why

Temporal upscalers and temporal anti-aliasing (DLSS, FSR, TAA) build each frame from the frames before it. To find where a pixel was one frame ago they need a motion vector for every pixel, and for a moving model a shader pack can only work that out when it is told, per vertex, how far the vertex went since the previous frame. Super Resolution's Iris velocity extension defines the vertex attribute `at_velocity` for this.

Accelerated Rendering moves entity vertices in compute shaders on the GPU. Their final positions never exist on the CPU, so nothing on the CPU can provide their motion. Without it a moving entity is handled like static geometry: it blurs and leaves a trail.

AR Velocity computes the motion vector where the vertex is transformed, in the same compute pass.

## Supported Environment

- Minecraft 1.21.1, NeoForge 21.1.x, Java 21. Client only.
- Accelerated Rendering 1.0.14, exactly the build `1.0.14-1.21.1-alpha`.
- Iris 1.8.x for NeoForge.
- Super Resolution 0.9.2 with `enable_iris_extension = true` in the root of its config.
- A shader pack that reads `at_velocity` behind the macros `SR_IRIS_EXT_ENABLED` / `SR_IRIS_EXT_VELOCITY`.
- OpenGL 4.6, as for the compute shaders of Accelerated Rendering itself.

## Installation

1. Install the mods listed above and set `enable_iris_extension = true` in the config of Super Resolution.
2. Place `ar_velocity-0.1.4.jar` in the client's `mods` directory. SHA-256 of the released jar: `091dc165f34c37d63f20edeb8534be37d1abdcfa49eeab44e543e7284bb82ea5`.
3. Start the game and select a shader pack that reads `at_velocity`.

The log then holds, among others, these lines:

```text
ar_velocity: applying mixins (Accelerated Rendering and Iris are present)
ar_velocity: active; at_velocity is vertex attribute 9, fed from a per-vertex buffer (shader storage binding 6) ...
ar_velocity: the level render the game itself makes in a frame (GameRenderer.render -> renderLevel -> LevelRenderer.renderLevel) is the one whose draws get motion vectors, while a shader pack program reads them; two frames are compared by the timer of the game, and a pause is one second of real time
ar_velocity: a program that reads at_velocity from attribute 9 is in use (first: ...)
ar_velocity: velocity buffer in use (12 bytes per vertex of the entity vertex buffer)
```

The third line comes once, when a world is first entered. `ar_velocity: no program reads at_velocity any more` appears around every shader pack reload, at a change of dimension and on leaving a world, and is expected; the line `... is in use` follows it when a program reads the attribute again.

The following lines are logged once each, when something is not as usual:

- An error that contains `no motion vectors` (it begins with `ar_velocity: inert`, `ar_velocity: inactive` or `ar_velocity: no motion vectors from now on`) says what the mod did not find in the installed Iris or Accelerated Rendering as this build expects it. The mod then writes no motion vectors.
- The warnings `ar_velocity: the level is drawn more than once in a frame ...` and `ar_velocity: a frame of the game had a level render, but none made by the game's own call ...` say that the level is drawn a second time in a frame, or not by the game's own call; see Known Limitations.
- The information line `ar_velocity: a renderer (..., for ...) draws in more than one compute cycle of a frame` and the warning that a later draw `turned out to be that draw` are explained under Known Limitations as well.

## Settings

There is no config file. The JVM argument `-Darvelocity.disable=true` switches the mod off: no mixin is applied and nothing else of the mod runs.

## How It Works

- `at_velocity` is bound to vertex attribute 9 in every shader program whose vertex format leaves that location free. A program that gets no motion vectors reads (0, 0, 0), which shader packs take as "no data".
- For every draw, a second entry is put behind the transform entry of the draw in Accelerated Rendering's own per-draw buffer: the difference between the model-to-view matrix of the draw now and that of the same draw one frame ago. The draw of the previous frame is found in a history kept per entity or block entity and per renderer object (a model part, a bone), compared in world space with the camera movement taken out.
- Two compute shaders, modified copies of the two with which Accelerated Rendering writes Iris entity vertices, multiply each vertex with that difference and write the result, 12 bytes per vertex, into a separate buffer at the index of the vertex.
- That buffer is attached to the vertex array Accelerated Rendering draws with, as attribute 9.

All of this happens only while a shader pack program really reads the attribute. A draw gets no motion vector when it has no counterpart in the previous frame, when the pairing is not plausible, and in the shadow and hand passes.

## What It Does Not Do

- It changes no vertex format.
- It replaces no shader or program of Accelerated Rendering: its two programs are loaded next to them under names of their own and run in their place only for vertices that get motion vectors.
- With no shader pack, or one that does not read `at_velocity`, Accelerated Rendering runs exactly as it does without this mod.
- It gives no motion vectors to anything Accelerated Rendering does not draw through its compute path.
- It has no network access and writes no files.

## Measured Results

Measured on one machine, not a benchmark: under DLSS through Super Resolution 0.9.2 at ratio 3.0, a moving entity kept about 98 % of the sharpness it has when it stands still, against about 85 % without the motion vectors. Version 0.1.4 was compared with 0.1.3 in the same test scenes on that machine: the sharpness figures were the same within noise, and items of one kind that swap slots on a campfire no longer get a false motion vector for a frame.

## Known Limitations

- The mod works with one build of Accelerated Rendering and refers to internals of it and of Iris. The mod loader refuses another Accelerated Rendering version; if a member the mod uses is missing in the installed Iris or Accelerated Rendering, the mod stays inert and logs one error.
- Only one view gets motion vectors: the level render the game itself makes in a frame. A second view drawn before, after or inside it (another camera, a portal, a mirror, a panorama) gets none and does not take them from the player's view. A mod that makes the game's own call twice in a frame makes two such renders, which nothing tells apart: the first is followed, and when that is the mod's view, the player's view gets no motion vectors; when the mod does so only in some frames, motion vectors can be false in the frames around each change. In a frame in which the game's own call is not made, the level render that starts first is followed, with one warning; the first such frame after frames with the game's own call gets no motion vectors, and neither does the first frame after each change between the two.
- A draw that goes more than 8 blocks in one frame (a teleport) and every draw in a frame that begins more than one second of real time after the one before it, at any tick rate, get no motion vector for that frame.
- A motion vector can be false for one frame in three cases:
  - A renderer that draws once per frame for changing things, when the thing is replaced by another one less than 4 blocks away. An item on a block entity (a belt, a campfire slot) is told apart by its stack: an equal stack in another object, or a stack of another item, in the place of one that had stayed the same is taken for it only where the old one could have been itself (within half a block of an item at rest). A stack of the same item with other content is still taken for the stack that was there.
  - A thing that newly appears among several draws of one renderer right around a single very long frame, close to where an old thing was heading. In the frame after the long one, close means within half a block, or within twice the distance the old thing covers in that frame.
  - A new thing that a renderer draws at an earlier point of the frame than the old one and within 4 blocks of it, when the two draws reach the compute programs in different compute cycles of the frame, so that the first can no longer be taken back. A compute cycle ends each time Accelerated Rendering hands the draws it has collected to its compute programs, which can be more than once in a frame.
- An item on a block entity gets no motion vector for a frame in which the block entity reads its stacks again while the item moves abruptly: the equal stack in another object cannot be told from the next item taking its place.
- The blob shadow under an entity gets no motion vector.
- Once per session the log may warn that the first draw of a renderer was given a motion vector and a draw of a later compute cycle "turned out to be that draw". That is the third case above: one draw had a false motion vector in one frame, and for that owner the renderer is held to the rules for several draws from then on. The information line about a renderer that "draws in more than one compute cycle of a frame" does not mean that anything went wrong.

## Build

See [BUILDING.md](BUILDING.md). The changes of each version are in [CHANGELOG.md](CHANGELOG.md).

## License And Credits

AR Velocity is licensed under the [GNU General Public License, version 3](LICENSE); the source files allow any later version as well.

- The motion vector math is adapted from the Iris velocity extension of Super Resolution by 187J3X1-114514, which is licensed under the GPL version 3.
- The two compute shaders are modified copies of shaders of Accelerated Rendering 1.0.14 by Argon4W, which is distributed under the MIT License; see [LICENSE-AcceleratedRendering.txt](src/main/resources/LICENSE-AcceleratedRendering.txt).

## 简体中文

AR Velocity（`ar_velocity`）是一个仅客户端的 NeoForge 1.21.1 模组。它为 Accelerated Rendering 通过计算着色器绘制的实体、方块实体及其携带的物品提供逐顶点运动矢量属性 `at_velocity`，供 Iris 光影包读取，用来消除 DLSS、FSR、TAA 等时域超分辨率和抗锯齿下移动实体的拖影。

- 需要：Minecraft 1.21.1、NeoForge 21.1.x、Accelerated Rendering 1.0.14（`1.0.14-1.21.1-alpha`）、Iris 1.8.x、Super Resolution 0.9.2（配置文件根部设置 `enable_iris_extension = true`），以及在宏 `SR_IRIS_EXT_ENABLED` / `SR_IRIS_EXT_VELOCITY` 下读取 `at_velocity` 的光影包。
- 安装：把 `ar_velocity-0.1.4.jar` 放进客户端的 `mods` 文件夹。发布的 jar 文件的 SHA-256 为 `091dc165f34c37d63f20edeb8534be37d1abdcfa49eeab44e543e7284bb82ea5`。
- 关闭：加上 JVM 参数 `-Darvelocity.disable=true`。
- 不修改任何顶点格式；光影包不读取该属性时，Accelerated Rendering 保持原样运行。
- 许可证：GPL-3.0。运动矢量的算法改编自 Super Resolution 的 Iris velocity 扩展；两个计算着色器修改自 Accelerated Rendering（MIT 许可证）。
- 这是非官方的社区模组，与上述项目无关，也未获得它们的认可；出现问题请不要向这些项目反馈。
