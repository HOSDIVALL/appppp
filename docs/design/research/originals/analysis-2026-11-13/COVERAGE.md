# 覆盖率：Rhythm 的 Material 组件与动效，分析到哪一步了

> 结论先说：**没有"完全分析"这回事，但可以给出边界清楚的覆盖率。**
> 组件"有哪些、在哪、用了几次、怎么组装"已经双向闭环；
> 动效"用什么参数"在源码级闭环；**运行起来的实际观感未验证**。
> 本文件给每一档的判据与数字，不把推测写成结论。

分析对象：`Rhythm-5.5.499.1302-fdroidRelease-arm64-v8a.apk`
（23,572,778 B，sha256 `a13cc67e…52fe`）
对应源码：`cromaguy/Rhythm` tag `v5.5.499.1302-stable`（commit `6f26f96`，本次新克隆）

---

## 1. 三档口径

| 档 | 含义 | 判据 |
|---|---|---|
| **A 已确证** | 源码可读到实现 + 二进制里能找到对应调用/类 | 源码行 + DEX 调用点或类定义 |
| **B 部分确证** | 只见其一：要么源码有但二进制不可见（被内联/裁剪），要么二进制有但无法定位到文件 | 单侧证据 |
| **C 未覆盖** | 没有做，或做不到 | 明确列出 |

上一版报告里的 `[A]/[B]/[C]` 与本表同义。**本轮把 3 处 `[B]/[C]` 提升为 `[A]`，并更正了 1 处错误结论（见 §4）。**

---

## 2. 组件：数字

### 2.1 源码侧（主口径）

扫描 `app/src/main` + `app/src/fdroid`（与 fdroidRelease 对应）：

| 项 | 数量 |
|---|---|
| Kotlin 文件 | 373 |
| 引用 `androidx.compose.material3` 的文件 | 176 |
| 不同的 M3 符号（含注解、枚举、形状、颜色方案） | 137 |
| M3 符号使用次数（含 `MaterialTheme.colorScheme` 这类访问） | 5,603 |
| 引用 `androidx.compose.animation*` 的符号 | 52（2,733 次） |

数据文件：`data/source-m3-imports.csv`、`data/source-animation-imports.csv`
（按 `import` 行统计，不受前缀命名干扰；已剔除 import 行本身，避免把导入算成一次调用。）

Top 使用强度（源码调用次数 / 涉及文件数）：

| 符号 | 次数 | 文件 |
|---|---|---|
| `MaterialTheme`（含 colorScheme/typography/shapes 访问） | 2,995 | 81 |
| `Text` | 1,000 | 64 |
| `Surface` | 200 | 55 |
| `CardDefaults` / `Card` | 134 / 118 | 38 / 37 |
| `SheetValue` / `rememberBottomSheetState` / `BottomSheetDefaults` | 117 / 39 / 42 | 27 |
| `MaterialShapes` | 87 | 8 |
| `WindowWidthSizeClass` / `WindowHeightSizeClass` | 71 / 29 | 3 / 1 |
| `Button` / `OutlinedButton` / `ButtonDefaults` | 67 / 35 / 49 | 22 / 18 / 16 |
| `IconButton` / `IconButtonDefaults` / `FilledIconButton` | 36 / 34 / 13 | 21 / 14 / 10 |
| `LinearWavyProgressIndicator` / `CircularWavyProgressIndicator` | 38 / 23 | 40 / 33 |
| `Slider` / `SliderState` | 34 / 32 | 36 / 15 |
| `LoadingIndicator` / `ContainedLoadingIndicator` | 13 / 8 | 4 / 4 |

> `files` 可能大于 `uses`：存在"导入了但只出现在字符串/注释里"的情况。以 `uses` 为准。

### 2.2 APK 侧（存在性确认）

指令级解析：**3,540 个自有类、13,055 个方法、136,741 个 invoke 调用点**。
其中直接打到 M3 的 **2,378 处**，分布在 286 个自有类、59 个 M3 类、127 个成员上。
打到动画 API 的：`animation` 1,431 处、`animation-core` 895 处。

数据文件：`data/edges-lib.tsv`（每个调用边含调用方类、目标类、方法描述符、混淆名、次数）。

### 2.3 反混淆（签名级）

R8 改了方法名，但**参数类型顺序不变**（会削掉恒为常量的尾部参数，如 `changed: Int`、`$default` 的尾标记 `Object`）。
用官方 `material3 1.5.0-beta01` / `animation 1.12.1` / `animation-core 1.12.1` 三个 AAR 的
`classes.jar`（1,832 个类、18,078 个方法）建 (类, 返回类型) 索引，按"参数列表前缀 + 名字唯一"匹配：

| 结果 | 数量 |
|---|---|
| 调用边总数 | 1,403 |
| 匹配成功且名字唯一 | 3,080 / 3,809 调用点（**80.9%**） |
| 其中完全一致 / APK 少尾参 / APK 多尾参 | 173 / 515 / 134 |
| 多候选（同名不同参，单列不作结论） | 205 条边（72 组） |
| 无法匹配 | 54 条边（729 调用点） |

被唯一识别出的动画 API（片段，含调用点数）：

| API | 次数 |
|---|---|
| `spring(...)` | 213 |
| `tween(...)` | 209 |
| `animateDpAsState` | 146 |
| `Animatable.animateTo` | 56 |
| `infiniteRepeatable` | 45 |
| `AnimatedVisibility` | 45 |
| `fadeIn` / `fadeOut` | 42 / 9 |
| `rememberInfiniteTransition` | 23 |
| `Animatable.snapTo` | 18 |
| `CubicBezierEasing.transform` | 14 |

数据文件：`data/api-resolved.csv`、`data/animation-api.csv`、`data/deobf-summary.json`。

### 2.4 一个必须说清的限制

**APK 调用点次数不能当"使用强度"，只能当"确实存在于发布二进制"的下界。**

证据：同一 tag 源码里 `Slider(` 有 **34 处**，而 APK 里 `SliderKt` 只看得见 **1 个**调用点，
`PlaybackBottomSheetKt` 里 4 处 `Slider(` 完全消失，而该类本身存在且仍在调用 `CardDefaults`/`Text`。
这说明 R8 做了内联或类合并（具体是哪种**未定性**），导致部分调用边在 DEX 里不再以 `SliderKt` 为宿主。

所以本报告的"用了多少次"一律取源码口径；APK 侧只回答"这个 API 有没有进发布包"。

---

## 3. 动效：参数谱

源码统计（`data/anim-params-source.csv`）：

| 类别 | 总数 |
|---|---|
| `tween(...)` | 499 |
| `spring(...)` | 395 |
| `animateFloatAsState` | 216 |
| `animateColorAsState` | 78 |
| `animateDpAsState` | 72 |

`tween` 时长分布（未写 `durationMillis` 走默认的占多数）：

| 时长 | 次数 |
|---|---|
| 默认 | 342 |
| 200 / 350 / 300 / 400 ms | 21 / 18 / 16 / 16 |
| 250 / 500 / 0 / 220 ms | 10 / 8 / 8 / 6 |
| 600 / 450 / 80 ms | 5 / 5 / 3 |

缓动：`EaseInOut` 68、`EaseInOutQuart` 43、`LinearEasing` 32、`FastOutSlowInEasing` 30，
其余为 `EaseOutCubic`/`EaseInCubic` 等少量点状使用。

`spring` 组合（前四，占绝大多数）：

| damping | stiffness | 次数 |
|---|---|---|
| `DampingRatioMediumBouncy` | `StiffnessMedium` | 102 |
| `DampingRatioMediumBouncy` | `StiffnessLow` | 99 |
| `DampingRatioNoBouncy` | `StiffnessMedium` | 41 |
| `DampingRatioMediumBouncy` | （用默认 stiffness） | 36 |

这印证了上一版的判断：**两档弹簧（Medium/Low）承载了绝大部分交互**，
并且 `MediumBouncy` 出现次数是 `NoBouncy` 的两倍以上——"有弹性但不失控"是它的默认手感。

---

## 4. 更正：上一版有一处结论是错的

上一版 §3 写"Expressive 专属的也都在：`MotionScheme`、`MaterialShapes`、`LoadingIndicator`、
`FloatingToolbar`、`SplitButton`、`ButtonGroup`、`Carousel`、`ModalWideNavigationRail`、`ToggleButton`"。
**其中 8 个并未被使用。**

按源码 tag 逐项核对（`import` 与直接调用分别计数）：

| 组件 | import | 调用 | 实际状态 |
|---|---|---|---|
| `FloatingToolbar` | 0 | 0 | 未使用 |
| `SplitButton` / `SplitButtonLayout` | 2 | **0** | 仅导入未调用（死导入） |
| `WideNavigationRail` | 0 | 0 | 未使用 |
| `SegmentedButton` | 0 | 0 | 未使用 |
| `ToggleButton` / `IconToggleButton` | 0 | 0 | 未使用 |
| `ButtonGroup` | 0（命中的是自家 `ButtonGroupStyle`） | 0 | 未使用 |
| `SearchBar` / `DockedSearchBar` | 0 | 0 | 未使用 |
| `Tooltip` | 0 | 0 | 未使用 |

**错在哪：** 上一版统计的是 DEX 的 `method_ids` 词表——只要某个方法**被任何代码**引用（包括
Material3 自己内部的调用）就会出现在词表里。我把"库里引用了"误读成"Rhythm 用了"。
本轮改成指令级调用点后，答案才正确。

**真正在用的 Expressive 组件**（源码与 APK 双向可见）：
`MotionScheme.expressive()`（`theme/Theme.kt:354`，全项目一行）、`MaterialShapes`（35 种形状 + 7 套预设）、
`LoadingIndicator` / `ContainedLoadingIndicator`、`WavyProgressIndicator`（线性 38 处 / 圆形 23 处）、
`FloatingActionButtonMenu`、`PullToRefreshBox`、`Carousel`（`HorizontalMultiBrowseCarousel` /
`HorizontalUncontainedCarousel` / `HorizontalCenteredHeroCarousel`）、`WindowSizeClass` 自适应。

---

## 5. 本轮新坐实的两块（原 `[C]`）

### 5.1 歌词 catch-up：两段式，不是"缓动追上"

`components/lyrics/SyncedLyricsView.kt:48-77`（`WordByWordLyricsView.kt` 同构）：

```kotlin
private const val LARGE_SCROLL_CATCH_UP_DELTA = 8

if (delta >= LARGE_SCROLL_CATCH_UP_DELTA) {
    val prePositionIndex = if (safeTargetIndex > currentIndex)
        (safeTargetIndex - 1).coerceAtLeast(0) else (safeTargetIndex + 1).coerceAtMost(lastIndex)
    if (prePositionIndex != safeTargetIndex) scrollToItem(prePositionIndex, scrollOffset)
}
animateScrollToItem(safeTargetIndex, scrollOffset)
```

- 阈值 `8` 行：差距 ≥ 8 时**先瞬时 `scrollToItem` 到目标的前一格/后一格**，再 `animateScrollToItem` 走最后一步。
- 效果上等于"跳过去 90%，再动画最后一格"——避免跨十几行做长距离动画（用户等待 + 列表疯狂复用）。
- `noAnimation = true` 时直接 `scrollToItem`（例如拖动进度条）。
- 相关常量：`MIN_VOCAL_GAP_MS = 5200L`（停顿超过 5.2 s 插入 `Gap` 项），
  行间隔中位数兜底 `2000L`（源无有效间隔时）。

> 上一版把它写成"先快速追上、再缓入对齐的两段式缓动"，方向对、机制错：**不是两条曲线，是一次瞬跳加一次动画。**

### 5.2 `MaterialShapesUtils.kt`：形状是真形状，不是圆角

`shared/presentation/theme/MaterialShapesUtils.kt`（333 行）：

- `enum class ExpressiveMaterialShape`：**35 种**（CIRCLE/SQUARE/OVAL/PILL/DIAMOND/TRIANGLE/PENTAGON、
  FLOWER/HEART、BOOM/SOFT_BOOM/BURST/SOFT_BURST/SUNNY/VERY_SUNNY、GHOSTISH/PUFFY/PUFFY_DIAMOND/BUN/FAN/ARROW、
  ARCH/CLAM_SHELL/GEM/SEMI_CIRCLE/SLANTED、PIXEL_CIRCLE/PIXEL_TRIANGLE 等），按 `ShapeCategory` 分 7 组。
- `RoundedPolygon.toComposeShape()`：把 M3 的 `RoundedPolygon` 包成 Compose `Shape`，
  **非均匀缩放**（`scaleX`/`scaleY` 分别算）拉满目标尺寸，`Matrix` 平移居中，
  并用 `lastSize` + `cachedOutline` 做单槽缓存——避免每帧重算 Outline。
- `ShapeTarget`：8 个应用位（ALBUM_ART / FAB / CARDS / BUTTONS / CHIPS / PLAYER_CONTROLS / MINI_PLAYER /
  NAVIGATION_INDICATOR），配 7 套预设（Default / Friendly / Modern / Playful / Organic / Geometric / Retro）。
- 关闭 Expressive 时用 `getDefaultShape()` 兜底：封面 28dp、卡片 20dp、迷你播放器 16dp、其余 `CircleShape`。

也就是说：**"曲奇/四叶草/像素圆"是 M3 的 `MaterialShapes` 真路径，不是给 `RoundedCornerShape` 换数字**；
而它把"用哪种形状"做成了用户可见的主题设置（8 个位 × 35 种形状 × 7 预设）。

---

## 6. 仍然不闭环的部分

| 项 | 状态 | 原因 |
|---|---|---|
| 动效的**实际观感** | 未验证 | 未安装、未抓帧；所有参数来自源码静态阅读 |
| M3 组件**内部**怎么动 | 不在范围 | 那是 material3 1.5 的实现，不是 Rhythm 的代码 |
| APK 逐 API 调用**次数** | 不可靠 | R8 内联/类合并，见 §2.4 |
| 54 条边（729 点）的 API 名 | 未定 | 签名被改到无法与 AAR 唯一对齐 |
| 每个 M3 调用处的**实参值** | 未整理 | 源码可读，但逐处摘录成本高，未做成表 |
| Glance 桌面小组件 / 音频链路 | 未覆盖 | 与 Material 组件主题无关，本轮未做 |
| 手势/触觉细节（HapticType 具体档位） | 部分 | 只到"用了几种"这一层 |

---

## 7. 复现方式

```bash
# 1) 依赖（官方 AAR，用于签名级反混淆）
#    libs/material3-android-1.5.0-beta01.aar
#    libs/animation-android-1.12.1.aar
#    libs/animation-core-android-1.12.1.aar
# 2) 解析
python tools/callsites.py <apk>     # 指令级调用点 -> data/edges-lib.tsv
python tools/deobf.py               # 反混淆      -> data/api-resolved.csv
python tools/source_scan.py <repo>  # 源码清点    -> data/source-*.csv
# 3) 校验
sha256sum -c SHA256SUMS
```

`tools/deobf.py` 里 `match()` 的三种匹配等级（exact / app_shorter / aar_shorter）与
`ambiguous` 的分流是刻意保留的——**多候选的一律不猜**，宁可少说。
