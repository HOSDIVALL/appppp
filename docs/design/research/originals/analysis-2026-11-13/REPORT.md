# Rhythm 的 Material 组件体系与动效实现

> 对象：`chromahub.rhythm.app` 5.5.499.1302-fdroid（arm64-v8a，SHA-256 `a13cc67e…52fe`）  
> 对照源码：`github.com/cromaguy/Rhythm` tag `v5.5.499.1302-stable`（commit `6f26f96`，与本二进制同版本）  
> 标注：**[A]** 源码原文 ｜ **[B]** 构建产物实证 ｜ **[C]** 推断（勿当事实引用）

**修订记录（第二轮）**

- §3 第一层：更正——8 个 Expressive 组件（`FloatingToolbar`/`SplitButton`/`WideNavigationRail`/
  `SegmentedButton`/`ToggleButton`/`ButtonGroup`/`SearchBar`/`Tooltip`）**并未使用**，
  上一版把它们当成"在用"是统计口径错误（把 DEX 方法词的"被库内部引用"当成"被业务使用"）。
- §4.6：歌词 catch-up 从 `[C]` 推断升级为 `[A]` 实现原文（阈值 8 行、瞬跳 + 最后一格动画）。
- §7：`MaterialShapesUtils` 已读全（32 形状 / 8 应用位 / 7 预设）。
- 新增 `COVERAGE.md`：逐项覆盖率、判据与数字，以及"APK 调用次数为何不能当使用强度"。
- 新增 `data/` 四份机器可读索引与 `tools/` 三个可复跑脚本（调用点解析、签名级反混淆、源码清点）。

---

## 1. 它是什么，先排掉一个常见误会

很多人以为 Rhythm 是 Flutter 应用。**这一版不是。**

| 判定项   | 观察                                                                                                         | 结论                       |
| ----- | ---------------------------------------------------------------------------------------------------------- | ------------------------ |
| 原生库   | `lib/arm64-v8a/` 只有 `libffmpegJNI.so`、`libtaglib.so`、`libandroidx.graphics.path.so`，**没有 `libflutter.so`** | 不是 Flutter               |
| UI 资源 | `res/` 下只有 `color`(39)、`color-v23`(7)、`color-night-v8`(1)，**零 layout、零 drawable**                          | 界面全代码绘制                  |
| 框架痕迹  | 两个 dex 共 20.36 MB；`META-INF/` 里有 `activity-compose.kotlin_module`、`animation*.kotlin_module`               | Kotlin + Jetpack Compose |
| 页面实现  | 自有类中含大量 `*Kt$*$1$1$1` 形式的 Composable lambda 类                                                              | Compose 声明式 UI           |

**[B]** 顺带可以确认它把音频链路做得很重：`androidx.media3.exoplayer`（1150 个类描述符）、`org.jaudiotagger`（322）、ffmpeg JNI、taglib，以及 `androidx.glance.appwidget`（941，桌面小组件）。这些不在本次分析范围内，只作上下文。

### 依赖版本矩阵（决定"它用的是哪一代 Material"）

| 组件                                                         | 版本               |
| ---------------------------------------------------------- | ---------------- |
| `compose.runtime / ui / foundation / animation / material` | **1.12.1**       |
| **`compose.material3`**                                    | **1.5.0-beta01** |
| `activity`                                                 | 1.13.0           |
| `lifecycle`                                                | 2.11.0           |
| `navigation`                                               | 2.10.2           |
| `dynamicanimation`                                         | 1.1.0            |
| `glance`                                                   | 1.2.0            |

**[B]** 这个组合很关键：Material3 1.5 才把 **Expressive** 的那套东西（`MotionScheme`、`MaterialShapes`、morph 版 `LoadingIndicator`、`FloatingToolbar`、`SplitButton`、`ButtonGroup`、`Carousel`、`WideNavigationRail`）正式铺开。Rhythm 的"高级感"有一半来自"直接用上了这一代 API"，而不是自研。

---

## 2. 主题层：动效的地基

### 2.1 根主题只做了一件关键事

**[A]** `shared/presentation/theme/Theme.kt`（APK 内包名 `ui.theme`）：

```kotlin
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
MaterialTheme(
    colorScheme = colorScheme,
    motionScheme = MotionScheme.expressive(),
    typography = typography,
    shapes = Shapes,
    content = content
)
```

一行 `motionScheme = MotionScheme.expressive()` 就把所有 M3 组件（按钮按压、Sheet、Chip、FAB、Switch…）的默认时长与弹簧换成了 Expressive 节奏。**这是"整体看起来是同一套动画"的根本原因**，比逐个组件调 `tween` 省事得多。

对自建项目的直接启示：想统一手感，先统一 `motionScheme`，再谈单个组件的曲线。

### 2.2 颜色不是普通的 Material You

**[A]** 它没有直接吃 `dynamicLightColorScheme` 了事，而是自建了一条色彩管线：

```
种子色(HCT) ─┬─ paletteStyle: TONAL_SPOT / VIBRANT / MONOCHROME / EXPRESSIVE
             ├─ 强度: STANDARD / MEDIUM / VIVID
             └─ 明暗 + AMOLED 覆写 → ColorScheme
```

- **13 个预设**：Default、Warm、Cool、Forest、Rose、Monochrome、Lavender、Ocean、Aurora、Amber、Crimson、Emerald、Mint；其中 Default/Forest 用 `TONAL_SPOT`，Lavender/Mint 用 `EXPRESSIVE`，多数用 `VIBRANT`。
- **强度**：`VIVID → (contrastLevel 0.22, chroma ×1.25)`，`MEDIUM → (0.12, ×1.12)`，其余 `(0.0, ×1.0)`。
- **封面取色**：从专辑封面提 seed，`chroma > 18` 走 `VIBRANT`，否则 `TONAL_SPOT`；`chroma ≤ 8` 判为近灰，强制 `MONOCHROME`，避免灰封面色调映射出脏色。
- **Expressive 色集重映射**（容易被忽略但很出效果）：
  - 深色：`background = surface = surfaceContainerLowest`
  - 浅色：`background = surface = surfaceContainerLow`，`surfaceContainer / surfaceContainerLowest = White`
  - 结果是"面板靠 container 分层、底色统一收进最低层"，看起来更整、更少灰雾。
- **AMOLED**：纯黑 `background/surface/surfaceDim`，container 用 `#0A0A0A`→`#2A2A2A` 阶梯。

### 2.3 形状与字体

**[B]** 形状相关类：`MusicShapes`、`ExpressiveShapeTokens`、`ShapeKt`、`SettingsExpressiveShapes`、`ExpressiveShapes`、`ShapePresetOption`（说明形状是可切换预设，用户能在设置里换）。  
**[A]** 常用令牌（来自 `ExpressiveComponents.kt`）：

| 令牌                                               | 值                                                    |
| ------------------------------------------------ | ---------------------------------------------------- |
| Full                                             | `CircleShape`（胶囊：按钮 / FAB / Chip）                    |
| ExtraLarge / Large / Medium / Small / ExtraSmall | 28 / 24 / 16 / 12 / 8 dp                             |
| Squircle 系                                       | 32 / 28 / 20 / 14 dp                                 |
| 高度层级                                             | Level0–5 = 0 / 1 / 3 / 6 / 8 / 12 dp；按下 2 dp、悬停 4 dp |

字体支持系统字体与自定义字体文件（`FontLoader` + `fontSource = SYSTEM / CUSTOM`），并有一整屏字体选择 BottomSheet。

---

## 3. 组件体系：三层，别只看最外层

理解 Rhythm 的组件，关键是分清三层，否则会把"它自己写的"和"Material 自带的"混在一起。

### 第一层：M3 原生组件

> **本条已修订。** 上一版依据 DEX 的 `method_ids` 词表统计，把"Material3 库内部引用"误当成"Rhythm 使用"，
> 导致 `FloatingToolbar`、`SplitButton`、`WideNavigationRail`、`SegmentedButton`、`ToggleButton`、
> `ButtonGroup`、`SearchBar`、`Tooltip` 被错列为"在用"。本轮改成指令级调用点（136,741 个 invoke）+
> 源码 tag 导入清点后，这 8 个**均未使用**。详见 `COVERAGE.md` §4。

**[A]** 实际在用、且源码与二进制双向可见的 M3 组件（源码调用次数）：

`Text`（1,000）、`Surface`（200）、`Card`/`CardDefaults`（118/134）、`Button` 家族
（`Button` 67、`OutlinedButton` 35、`FilledTonalButton` 8、`TextButton` 3、`FilledIconButton` 13、
`FilledTonalIconButton` 9、`IconButton` 36）、`Slider`/`SliderState`（34/32）、`Checkbox`、`Switch`、
`FilterChip`/`AssistChip`/`InputChip`、`AlertDialog`（含 predictive back）、`DropdownMenu`、
`NavigationBar`、`Scaffold`、`TopAppBar`、`OutlinedTextField`、`BottomSheet` 全家
（`rememberBottomSheetState` 39 处、`SheetValue` 117 次引用）、`Badge`、`Divider`。

Expressive 一代里**真正用到**的只有这几个，别被"依赖里有"误导：

| 组件 | 用法 |
|---|---|
| `MotionScheme.expressive()` | 根主题一行（`theme/Theme.kt:354`），全局节奏来源 |
| `MaterialShapes` | 35 种形状 + 7 套预设 + 8 个应用位（见 `MaterialShapesUtils.kt`） |
| `WavyProgressIndicator` | 线性 38 处 / 圆形 23 处，是"波浪进度"的来源 |
| `LoadingIndicator` / `ContainedLoadingIndicator` | 13 / 8 处 |
| `FloatingActionButtonMenu` | 1 处（Library 页） |
| `PullToRefreshBox` | 3 处 |
| `Carousel` | `HorizontalMultiBrowseCarousel` / `HorizontalUncontainedCarousel` / `HorizontalCenteredHeroCarousel` |
| `WindowSizeClass` | 71 + 29 次引用，用于响应式断点 |

### 第二层：自家 Expressive 封装（真正"Rhythm 味"的来源）

**[A]** `shared/presentation/components/common/ExpressiveComponents.kt` 把 M3 重新包了一层，统一了**按压弹簧 + 缩放梯度**：

| 组件              | 按压缩放              | 弹簧                                               |
| --------------- | ----------------- | ------------------------------------------------ |
| 文字/填充/描边按钮      | `0.96f`           | `spring(DampingRatioMediumBouncy, StiffnessLow)` |
| 图标按钮、FAB（小/中/大） | `0.88f` ~ `0.95f` | MediumBouncy + `StiffnessMedium`                 |
| Chip 系列         | `0.95f`           | MediumBouncy + StiffnessMedium                   |
| 可点 Surface      | `0.98f`           | MediumBouncy + StiffnessLow                      |

规律很清楚：**视觉越"重"的元素缩得越狠、弹得越快；越"轻"的越克制、越软。** 两档弹簧（Low / Medium）覆盖了全部交互，没有第三种曲线——这是它手感统一的技术原因。

### 第三层：手工实现的特殊组件

这些是 M3 没有、自己画的，也是最有参考价值的部分：

| 组件                                                            | 文件                                             | 做了什么                          |
| ------------------------------------------------------------- | ---------------------------------------------- | ----------------------------- |
| `MorphingPlayPauseButton`                                     | `components/player/MorphingPlayPauseButton.kt` | **播放/暂停按钮的容器在圆形与九瓣曲奇之间做路径形变** |
| `ExpressivePlayerControlGroup`                                | `components/common/ExpressiveComponents.kt`    | 多按钮"权重联动"形变 + 圆角 morph        |
| `ExpressiveMorphingToggleButton`                              | 同上                                             | 激活时收窄成图标、非激活展开显示文字            |
| `TabAnimation` / `SmallTabAnimation`                          | `components/common/`                           | 选中页签"弹一下"、相邻页签让位              |
| `WaveSlider` / `ArcProgressSlider` / `CookieHorizontalSlider` | `components/common/`                           | 自绘滑块（波形 / 弧形 / 曲奇形）           |
| `ExpressiveScrollBar`                                         | `components/common/`                           | 带网格线与标签解析的自绘滚动条               |
| `SyncedLyricsView` / `WordByWordLyricsView`                   | `components/lyrics/`                           | 歌词同步滚动、"追赶式"定位、逐字高亮           |
| `SnowfallEffect` / `ChristmasDecorations`                     | `theme/festive/`                               | 节日粒子层                         |
| `CanvasArtworkPlayer`                                         | `components/player/`                           | Canvas 绘制封面（随播放状态变形/旋转）       |
| `DigitTickerText`                                             | `components/common/`                           | 数字滚动（时间、进度）                   |

完整清单见 `COMPONENTS.md`。

---

## 4. 动效体系：七个可拆解的做法

### 4.1 全局节奏：交给 `MotionScheme`

**[A]** 见 §2.1。所有 M3 组件的时长/曲线由 `MotionScheme.expressive()` 决定，业务代码不重复声明。**建议照抄这个决策**，而不是在每个组件上手写 `tween(300)`。

### 4.2 形变（morph）：路径插值，不是缩放

最出彩的一处。**[A]** `MorphingPlayPauseButton`：

```kotlin
val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
val morphProgress by animateFloatAsState(
    targetValue = if (isPlaying) 1f else 0f,
    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
)
```

要点：

- `androidx.graphics.shapes.Morph` 在两个 `MaterialShapes` 之间做**顶点插值**，`morph.toPath(progress, path)` 每帧拿到 `Path`，再用 `Canvas` 的 `drawPath` 画。不是改变圆角，是真的换轮廓。
- `Matrix` 把归一化路径（0..1）拉伸到控件尺寸，再按 `bounds.center` 平移居中——这是 `MaterialShapes` 路径的标准用法，忘了这步就会画在左上角。
- 播放中额外叠加 **15 秒一圈的线性旋转**（`infiniteRepeatable(tween(15000, LinearEasing), Restart)`）；暂停时旋转角直接归零。
- 图标切换用 `Crossfade(220ms, FastOutSlowInEasing)`，并且图标尺寸 = `size * 0.5f`。
- 按压反馈：`graphicsLayer { scaleX = scaleY = pressScale }`，`0.92f`，同一条 MediumBouncy + Low 弹簧。
- 加载态：内部换成 `M3CircularLoader`，尺寸 `size * 0.42f`，线宽 2.5，track alpha 0.24——**尺寸用比例而非固定 dp**，所以 48/56/72 dp 下都不歪。

> 复刻门槛：`androidx.graphics:graphics-shapes` + Material3 1.5 的 `MaterialShapes`。低版本 M3 拿不到 `Cookie9Sided`，只能自己用 `Path` 手写曲线。

### 4.3 权重联动形变：一组按钮像流体

**[A]** `ExpressivePlayerControlGroup` 里，按下任一按钮时整组按钮的 `weight` 同时变化：

| 状态   | 播放/暂停  | 其它按钮    |
| ---- | ------ | ------- |
| 按下   | `1.3f` | `1.1f`  |
| 正在播放 | `1.2f` | `1.0f`  |
| 空闲   | `0.8f` | `0.65f` |

弹簧 MediumBouncy + StiffnessMedium。同时播放键圆角在 `60dp ↔ 26dp` 之间 morph（NoBouncy + StiffnessMedium），容器圆角随断点 `40 / 36 / 32 / 28 dp`。

这套组合的效果是：**按一下，主按钮胖起来、邻居被挤窄，松手回弹**。它没有任何位移动画，全靠 `weight` + `cornerRadius` 两个连续量。

### 4.4 宽度 morph：胶囊 ↔ 图标态

**[A]** `ExpressiveMorphingToggleButton`（随机播放、歌词、循环）：

```
激活   : 48dp（紧凑 40dp）       ← 只剩图标
非激活 : 100dp（紧凑 84dp）      ← 图标 + 文字
弹簧   : spring(MediumBouncy, StiffnessLow)
```

文字用 `AnimatedVisibility` + `fadeIn() + expandHorizontally()` 展开，**进入用 MediumBouncy/Low，退出用 NoBouncy/StiffnessHigh**——收得比开得快，视觉上不会拖泥带水。标签样式 `labelMedium + Bold + letterSpacing 0.8sp`。

`ExpressiveMorphingPlayPauseButton` 是同一个思路的另一个用例：宽度 `110↔120`（或 `130↔140`、`140↔150`），圆角恒等于 `height / 2`，并且**暂停且无 seek 按钮时延迟 2000 ms 才把 "PAUSE" 文字淡入**（避免一暂停就跳字）。

### 4.5 页签：弹一下 + 邻居让位

**[A]** `TabAnimation`，参数给得很典型：

| 项       | 值                                       |
| ------- | --------------------------------------- |
| 主曲线     | `tween(250, FastOutSlowInEasing)`       |
| 选中"弹一下" | `scale 1f → 1.05f → 1f`（同一条 250ms）      |
| 邻居让位    | 仅相邻页签，`translationX ±12f`，出去再回来         |
| 颜色过渡    | `animateColorAsState(tween(200))`       |
| 触觉      | 点击 `HapticType.LIGHT`，长按 `HEAVY`        |
| 尺寸      | 高 44dp，水平 padding 16dp，`CircleShape` 裁剪 |

两个实现细节值得抄：

1. **非选中页签立即 `snapTo(1f)`**，不做反向动画——否则来回切换时会有"追赶"的滞后感。
2. **只让相邻页签位移**，隔得远的直接 `snapTo(0f)`，避免整排抖动。

### 4.6 歌词：catch-up 滚动是"瞬跳 + 最后一格动画"

**[A]** 已取到实现（`components/lyrics/SyncedLyricsView.kt:48-77`，`WordByWordLyricsView.kt` 同构）：

```kotlin
private const val LARGE_SCROLL_CATCH_UP_DELTA = 8

if (delta >= LARGE_SCROLL_CATCH_UP_DELTA) {
    val prePositionIndex = if (safeTargetIndex > currentIndex)
        (safeTargetIndex - 1).coerceAtLeast(0) else (safeTargetIndex + 1).coerceAtMost(lastIndex)
    if (prePositionIndex != safeTargetIndex) scrollToItem(prePositionIndex, scrollOffset)
}
animateScrollToItem(safeTargetIndex, scrollOffset)
```

- 差距 ≥ **8 行**时，先 `scrollToItem` 瞬移到目标的**前一格/后一格**，再 `animateScrollToItem` 补最后一格。
  等于"跳过去 90%，只动画最后一格"——避免跨十几行做长距离动画（用户等 + 列表复用抖动）。
- `noAnimation = true`（拖动进度条）直接 `scrollToItem`。
- 配套常量：`MIN_VOCAL_GAP_MS = 5200L`（停顿 >5.2 s 插 `Gap` 项）、行间隔中位数兜底 `2000L`。

> 上一版写"先快速追上、再缓入对齐的两段式缓动"，方向对、机制错：**不是两条曲线，是一次瞬跳加一次动画。**

### 4.7 加载、占位与装饰

| 场景        | 做法                                       | 参数                                              | 级别      |
| --------- | ---------------------------------------- | ----------------------------------------------- | ------- |
| 骨架屏       | `ShimmerBox`：线性渐变横向位移                    | `animateFloat 0→1000f`，`tween(1000, delay 300)` | [A]     |
| 播放等待      | 脉冲                                       | `infiniteRepeatable(tween(1000), Reverse)`，60dp | [A]     |
| 呼吸容器      | 脉冲                                       | `infiniteRepeatable(tween(1500), Reverse)`      | [A]     |
| 数字变化      | 计数过渡                                     | `animateFloatAsState(tween(600))`               | [A]     |
| 折叠卡       | 箭头旋转 180° ↔ 0°                           | `tween(300)` + `fadeIn + expandVertically`      | [A]     |
| M3 loader | Expressive 形变 loader                     | M3 1.5 `LoadingIndicator`（13 处）/ `ContainedLoadingIndicator`（8 处） | [A]     |
| 节日雪花      | `SnowfallEffect` / `SnowfallWithSparkle` | Canvas 粒子 + 无限动画，支持雪花形状生成器                      | [B]/[C] |
| 崩溃页背景     | `RotatingBackgroundCookies`（曲奇形旋转背景）     | —                                               | [B]     |

---

## 5. 如果要复刻，按这个顺序做

1. **先把 `motionScheme` 统一。** 升级到 Material3 1.5，根主题传 `MotionScheme.expressive()`。这一步能白拿 70% 的"高级感"。
2. **建两档弹簧常量**：`Soft = spring(MediumBouncy, Low)`、`Snappy = spring(MediumBouncy, Medium)`，全项目只用这两个，配一套缩放梯度（0.98 / 0.96 / 0.95 / 0.92 / 0.88）。
3. **再挑 2–3 个"重点 morph"**：播放键路径形变、按钮组权重联动、页签弹跳。这三个是记忆点，其余保持克制。
4. **最后加一层氛围**：Expressive 色集重映射（底色收进 container 最低层）+ 骨架屏 shimmer + 一个节日粒子层（可选）。

反面教材也在包里：它没有给每个页面写自定义转场，页面级动画主要靠 `AnimatedContent` / `Crossfade` / `SharedTransitionScope`（**[B]** 三者都有引用）。**动效浓度靠集中在少数组件上，而不是到处加。**

---

## 6. 许可与合规（重要）

Rhythm 的每个源文件头都是：

```
SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi
SPDX-License-Identifier: GPL-3.0-or-later
```

也就是说：

- **可以**读源码、学思路、照着参数自己实现、在自己的项目里用同样的设计语言。
- **不可以**把它的 Kotlin 代码片段直接拷进闭源或上架商业应用——GPL-3.0 具传染性，会要求你的整个作品以 GPL 分发。


- 本报告给的是**做法、参数与判定依据**，没有搬运可直接编译的源码片段；`MorphingPlayPauseButton` 那段引用仅用于说明技术路径，不构成可用代码。

---

## 7. 本次没覆盖的

逐项覆盖率、判据与数字见 `COVERAGE.md`；这里只列缺口：

- 音频链路（ffmpeg / taglib / Media3 / AutoEQ / 播放引擎的 overlap transition）只做了存在性确认，未深入。
- 桌面小组件（Glance，941 个类描述符）未分析。
- 每个 M3 调用处的**实参值**没整理成表（源码可读，逐处摘录成本高）。
- 54 条调用边（729 个调用点）的 API 名未能唯一确定——R8 改了签名，与官方 AAR 无法唯一对齐。
- **未做动态验证**（没有安装运行、没有抓帧比对）。所有动效参数来自源码静态阅读；
  **实际观感是否与参数一致，未验证。**
- 源码已锁定到 tag `v5.5.499.1302-stable`（commit `6f26f96`），常量与该二进制同一版本；
  但 R8 会内联/合并，二进制里的调用次数不能反推源码次数（见 `COVERAGE.md` §2.4）。
