# Monolith Finder 开发文档

本文档面向对项目源码感兴趣的开发者，说明整体架构、核心算法、渲染流程与构建方式。

## 概览

本项目是用 **Java + LWJGL2（OpenGL 1.1 固定管线）** 移植的 monolith 查找器。算法逻辑与 Rust 网页版 [monolith-renderer](https://github.com/kahomayo/monolith-renderer) 保持 **bit-exact** 一致，渲染则改为原生窗口 + 异步瓦片加载。

## 目录结构

```
src/monolith/
├── core/              算法核心（无 LWJGL 依赖，可独立验证）
│   ├── Coord.java            方块坐标与封装
│   ├── JavaRandom.java       旧版 Minecraft 的 48 位 Java 随机数
│   ├── PerlinNoise.java      2D/3D Perlin 噪声（含快速采样路径）
│   ├── FractalNoise.java    多倍频合成分形噪声
│   ├── ScaledFractalNoise.java 缩放后的分形噪声
│   ├── SamplingCuboid.java  采样立方体
│   ├── ChunkGenerator.java  世界生成器（地形高度、噪声查询）
│   ├── Search.java          约束搜索（monolith 查找）
│   ├── Finder.java          查找封装（含无分配 searchFast 快速路径）
│   └── SampleJobImpl.java   采样任务实现
├── render/
│   ├── TileRenderer.java    把一块区域栅格化为 BufferedImage（上色）
│   └── TextRenderer.java    用 AWT 把文字渲染为 OpenGL 纹理
└── app/
    ├── MonolithMapApp.java  主窗口、主循环、输入与 UI
    ├── TileCache.java       异步瓦片缓存 + 后台线程池
    └── ...
Verify.java                  与 Rust 测试向量的 bit-exact 校验
```

## 构建与运行

`compile.bat` 负责：

1. 清理并编译 `src/` 到 `build/`；
2. 解压 `lib/windows_natives.jar` 到 `natives/`（首次）；
3. 把 lwjgl/jinput 库类合并进 `build/`；
4. 用 `jar` 打包成根目录 `monolith-finder.jar`（含 `Main-Class`）。

`run.bat [seed]` 只运行该 jar：

```bat
java "-Djava.library.path=natives" -jar monolith-finder.jar %*
```

`verify.bat` 编译并运行 `Verify.java`，校验核心算法（无需 LWJGL）。

## 核心算法

### 旧版 Java 随机数

Minecraft 旧版本使用 `java.util.Random` 的 48 位 LCG。世界生成必须复刻其精确序列，否则噪声坐标对不上。

### 3D Perlin 噪声

`PerlinNoise` 复刻旧版实现，含「仅当 cube_y 变化时才重算插值系数」这一官方 bug，这直接影响位级结果。`sampleSingle` 提供无分配快速路径，用于热循环。

### 地形与 monolith 判定

- `ChunkGenerator` 用多层分形噪声合成地形高度（hill）与深度（depth）。
- monolith 判定沿用网页逻辑（如 `depth abs` 判定陆地、`hill` 阈值判定 monolith），参阅 `Finder` 与 `Search`。

### 缩放噪声（约束搜索）

网页版独有的「缩放噪声」能力：用约束搜索提前终止，允许极限拉远视野而不崩溃。`Search`/`Finder` 中的约束逻辑即对应此实现。

### Infdev ~ Alpha 地形生成器源码逻辑

inf-20100611 ~ inf-20100618 及 inf-20100630 之后的 `ChunkProviderGenerate` 记录了独石柱（monolith）在真实地形高度合成中的成因。核心合成如下（伪代码）：

```
hill  = fractalNoise(10 倍频, scale 1.0)     # 即查找器的 hill
cavity = fractalNoise(16 倍频, scale 100.0)  # 即查找器的 depth

d61 = clampHi((hill + 256) / 512, 1.0)       # 只截顶部，不截底部
baseLine = 8.5 + curve(cavity) * 4           # 该列基准海平面

for y in 0..16:                              # 17 个密度采样层
    cut = (y - baseLine) * 12 / d61          # 往下的挖掘深度
    if cut < 0: cut *= 4
    density = lerp(noise1, noise2, noise3) - cut
    store(density)                           # density > 0 ⇒ 放石头
```

关键点：`d61` 作为分母只截上限不截下限。当 `hill < -256` 时分母变负，`cut` 由「往下挖的深度」反号成「向上堆积的实心」，洼地反转成独石柱；`hill < -512` 时反转最剧烈，即查找器命中的 monolith 核心区。查找器只捕捉原始噪声场（`|depth|>=8000` 判陆地、`hill < -512` 判 monolith），不重建 3D 方块，因此不依赖下述方块落地细节。

### Infdev 20100624~20100629 的地下异常（20100624 出现，20100629 结束，20100630 恢复）

其 `ChunkProviderGenerate` 在生成最后阶段多了一段「清洞 + 基岩上移」逻辑（伪代码）：

```
for each 列:
    i19 = 0
    while i19 < 128 and 该列 y=i19 处是空或水:
        清空该处; i19++      # 从 y=0 向上抹掉空/水，碰到首个实心即停
    for y = 127 down to i19:
        if y <= i19 + rand(6) - 1: 放基岩
        else: 按密度覆盖地表/填充
```

`i19` 是「从 y=0 数到首个实心方块的高度」。在独石柱列，y=0 到海平面之间没有实心，因此它停在主体底部（接近海平面处），即 y≈64。

关键在 `i19`：基岩层被铺在**首个实心 `i19` 上方**，由 `rand(6)` 逐层随机决定是否铺设，越往下概率越高（正常地形首位实心在 y=0，基岩集中在 y=0~5；独石柱首位实心被抬到 y≈64，基岩也跟着上移到 y≈63~66）。因此基岩并非铺错，而是**伴随着 `i19` 整体上移**。真正导致「直通虚空」的是第一个清洞循环：它把 `i19` 以下（y=0~63）的方块抹成空、回填循环又只覆盖 `i19..127`，于是 `i19` 与 y=0 之间的空气层既无方块也无基岩——这就是 inf-20100624 「海平面下几乎无方块、独石柱直抵虚空」的原因。inf-20100630-1340 起恢复为无此清洞段的逻辑，独石柱实心且直顶 128 高度上限。

### Alpha v1.2.0 的修复根因

Alpha v1.2.0 通过给密度合成中的分母补上**下界钳位**，从根源上消除了独石柱（伪代码）：

```
d = clampHi((hill + 256) / 512, 1.0)   # 归一化，只截上限
if d < 0.0: d = 0.0                    # ★ 钳掉负数分母
cut = (y - baseLine) * 12 / d          # 与旧版相同的挖掘深度式子
if cut < 0: cut *= 4
```

旧版独石柱的成因是分母只截上限、可变为负，导致分母为负时 `cut` 反号成「向上堆积的实心」。Alpha v1.2.0 把分母钳到非负，再配合随后的 `+0.5` 抬升，实际最小值为 0.5，**分母永不为负**，`cut` 不再反转，洼地回归为正常深谷、高崖，独石柱现象自此消失。

值得注意的是，修复只改了**钳位逻辑**，`hill`/`cavity` 噪声场本身未变，因此 `hill < -512` 的极端值点依旧存在。只要去掉该钳位、恢复旧版「只截上限、允许负分母」的计算，独石柱判定仍可复现——这也是查找器能继续用同一噪声场捕捉 monolith 的原因。

## 渲染与瓦片

### 瓦片模型

- **stride** = 每像素采样数（对应网页 `scale_factor`），取值 `2^k`，`k ∈ [0,14]`。
- `blocksPerPixel = stride * 4`。
- 每个瓦片覆盖世界大小 `tileWorld = TILE_SIZE * stride * 4`（`TILE_SIZE = 256`）。
- 越过 `stride=1`（k=0）后可继续拉近：`mag`（1~64）把 stride-1 瓦片放大显示，更近但模糊，与网页 Leaflet 放大行为一致。

### 异步加载

`TileCache` 用 4 个后台线程做 CPU 密集的瓦片栅格化，主线程只做 GL 纹理上传与绘制：

- `request(key)`：去重入队；
- `workerLoop()`：后台栅格化，缩放级别变化时丢弃过期成果；
- `uploadIfReady(key)`：就绪则上传为纹理并返回 id，否则返回 -1；
- `onZoomChanged(stride)`：缩放时清空旧级别缓存。

> 关键约束：**GL 上传绝不能在 `glBegin/glEnd` 之间发生**。渲染分三步——先请求、再画未就绪的占位块、最后上传并绘制就绪瓦片。

### 纹理

瓦片用 `GL_NEAREST`、`GL_CLAMP_TO_EDGE`（`0x812F`）避免瓦片边界接缝。颜色按 Rust 语义上色：monolith 红、陆地绿、水域蓝、边境之地橙。

## 输入与 UI

- 鼠标左键拖拽平移；滚轮/`+`/`-` 缩放（向上放大），以鼠标为锚点。
- 左上角：种子输入框 + 应用按钮；左下角：X/Z 坐标输入框 + 应用按钮。
- 输入框支持点击定位光标、拖拽选字、闪烁光标；坐标值仅在位置变化时跟随，静止时保持可编辑。
- 画面中心绘制 MC 样式反色十字准星（`GL_ONE_MINUS_DST_COLOR, GL_ONE_MINUS_SRC_COLOR`）。

## 验证

`Verify.java` 对大量采样点做 bit-exact 比对，确保 Java 移植与 Rust 输出一致。修改算法代码后请运行 `verify.bat` 确认无回归。