# 文件索引

分三部分：**APK 物理结构**（包里有什么）、**自有代码地图**（它的代码怎么组织）、**类 → 仓库路径对照**（想读源码时去哪找）。

---

## 一、APK 物理结构

**[B]** 共 **2,215** 个条目，解压后 42.5 MB，压缩后 23.6 MB。

### 顶层构成

| 区域 | 关键内容 |
|---|---|
| `classes.dex` / `classes2.dex` | 10.21 MB + 10.15 MB，合计 20.36 MB |
| `lib/arm64-v8a/` | `libffmpegJNI.so` 1.39 MB、`libtaglib.so` 1.20 MB、`libandroidx.graphics.path.so` 0.01 MB |
| `assets/` | `autoeq_profiles.json` 1.58 MB、`sonivox_wave_data.dat` 429 KB、`PublicSuffixDatabase.list` 132 KB、`privacy_policy.html` 8.4 KB |
| `res/` | 仅 `color`(39) / `color-v23`(7) / `color-night-v8`(1)——**无 layout、无 drawable** |
| `META-INF/` | 各 androidx 模块的 `.version` 文件（依赖版本矩阵来源）、`*.kotlin_module` |
| `assets/dexopt/` | 2 个 baseline profile 相关文件 |

> "无 layout / 无 drawable"是判定 Compose-only 的硬证据：任何 XML 布局或矢量图都会在 `res/layout`、`res/drawable` 下留条目。

### 原生库说明

| 库 | 用途推断 |
|---|---|
| `libffmpegJNI.so` | 解码扩展（支持 Media3 原生解不了的格式） |
| `libtaglib.so` | 音频标签读写（对应 `org.jaudiotagger`） |
| `libandroidx.graphics.path.so` | `androidx.graphics:graphics-shapes` 的原生路径运算——**正是 `MaterialShapes` / `Morph` 依赖的库** |

最后一条值得注意：**它证明路径形变（§4.2）确实编译进了这版二进制**，不是只在源码里有。

### 全量条目清单

`data/apk-entries.tsv`：2,215 行，含路径、解压大小、SHA-256。可直接 `grep` 定位：

```bash
grep -c "^lib/"           data/apk-entries.tsv      # 原生库
grep "^assets/"           data/apk-entries.tsv      # 资源
grep "\.version$"         data/apk-entries.tsv      # 依赖版本
```

---

## 二、自有代码地图

**[B]** Rhythm 自有类共 **3,367** 条（含内部类/lambda），去重后 **835** 个顶层类。未做包名混淆，包结构与仓库基本一致。

### 按包统计（顶层类数量）

| 包 | 类数 | 职责 |
|---|---|---|
| `network` | 83 | 网络层 |
| `shared/presentation/screens/settings` | 81 | 设置页群（主题、播放、歌词、备份、实验室…） |
| **`shared/presentation/components/bottomsheets`** | **71** | **BottomSheet 群（队列、歌曲信息、均衡器、标签编辑…）** |
| **`shared/presentation/components/common`** | **71** | **通用组件主战场（Expressive 封装、滑块、滚动条、加载）** |
| `util` | 69 | 工具 |
| `features/local/presentation/screens` | 51 | 本地库页面（专辑/艺术家/播放列表详情等） |
| `shared/data/model` | 33 | 数据模型 |
| **`shared/presentation/components/player`** | **30** | **播放器组件（mini player、morph 按钮、封面 Canvas）** |
| `infrastructure/widget/glance` | 27 | 桌面小组件 |
| `features/local/presentation/navigation` | 19 | 页面导航与转场 |
| `shared/presentation/components/dialogs` | 17 | 对话框 |
| `features/streaming/data/repository` | 16 | 流媒体仓库 |
| `shared/presentation/navigation` | 16 | 主导航 |
| `activities` | 15 | Activity（主、崩溃、外链播放、Guard 超时） |
| **`shared/presentation/components/lyrics`** | **15** | **歌词视图（同步/逐字/编辑器）** |
| `shared/presentation/components` | 14 | 组件根（设置组、权限、音频质量徽章） |
| `shared/presentation/screens` | 14 | 共享页面 |
| **`ui/theme/festive`** | **12** | **节日皮肤（雪花、圣诞装饰、启动问候）** |
| `features/streaming/data/provider` | 10 | 流媒体提供商 |
| `shared/presentation/screens/player` | 10 | 播放器页面 |
| `features/local/data/database/dao` | 9 | Room DAO |
| **`ui/theme`** | **9** | **主题（颜色、字体、形状、尺寸、预设）** |

### 架构分区

```
chromahub.rhythm.app
├── activities/            单 Activity + 少量专用 Activity
├── core/                  领域模型、设备能力
├── features/
│   ├── local/             本地库（数据库、DAO、页面、导航）
│   └── streaming/         流媒体（提供商、仓库、页面）
├── infrastructure/        Media3 播放服务、音频、Glance 小组件
├── shared/
│   ├── data/model/        共享模型
│   └── presentation/
│       ├── components/    ← 组件层（common / player / lyrics / bottomsheets / dialogs / icons）
│       ├── screens/       ← 页面层（settings / player / 共享页）
│       ├── navigation/
│       └── theme/         ← 主题层（与 APK 内 ui/theme 同内容，版本不同位置）
├── ui/theme/              ← APK 内实际包名（含 festive/）
└── util/ utils/           工具
```

一句话概括：**feature 分包 + shared 层做设计与组件复用**，UI 代码主要集中在 `shared/presentation/`。这意味着它的设计系统是"沉淀出来给别人用"的，不是散落在页面里——这也是为什么能整套换肤、整批换动效。

---

## 三、类 → 仓库路径对照

APK 里只有包名，没有文件名。下表把 DEX 中看到的类映射回 `github.com/cromaguy/Rhythm` 的源码路径（以 `main` 为准；`app/src/main/java/chromahub/rhythm/app/` 为公共前缀）。

### 主题层

| DEX 类 | 仓库文件 |
|---|---|
| `ui.theme.ThemeKt` | `shared/presentation/theme/Theme.kt` |
| `ui.theme.ColorKt` | `shared/presentation/theme/Color.kt` |
| `ui.theme.TypeKt` | `shared/presentation/theme/Type.kt` |
| `ui.theme.ShapeKt`、`MusicShapes` | `shared/presentation/theme/Shape.kt` |
| `ui.theme.MusicDimensions`、`Spacing` | `shared/presentation/theme/Dimensions.kt` |
| `ui.theme.ExpressiveShapeTokens`、`ThemePresetDefinition`、`ColorSchemeOption` | `shared/presentation/theme/Theme.kt`（同文件） |
| `ui.theme.festive.SnowfallEffectKt` | `shared/presentation/theme/festive/SnowfallEffect.kt` |
| `ui.theme.festive.ChristmasDecorationsKt` | `shared/presentation/theme/festive/ChristmasDecorations.kt` |
| `ui.theme.festive.FestiveOverlayKt` | `shared/presentation/theme/festive/FestiveOverlay.kt` |
| `ui.theme.festive.FestiveSplashGreetingKt` | `shared/presentation/theme/festive/FestiveSplashGreeting.kt` |
| `ui.theme.festive.Snowflake*` | `shared/presentation/theme/festive/Snowflake.kt` |

> **注意版本位移**：APK 内是 `ui/theme`，仓库 `main` 已改成 `shared/presentation/theme`。文件内容对应，路径不同。

### 组件层（重点文件）

| DEX 类 | 仓库文件 |
|---|---|
| `components.common.ExpressiveComponentsKt` | `shared/presentation/components/common/ExpressiveComponents.kt` |
| `components.common.TabAnimationKt` | `.../common/TabAnimation.kt` |
| `components.common.SmallTabAnimationKt` | `.../common/SmallTabAnimation.kt` |
| `components.common.ExpressiveScrollBarKt` + `...MetricsKt` + `...LabelResolversKt` | `.../common/ExpressiveScrollBar*.kt`（3 个文件） |
| `components.common.ExpressiveShapeProviderKt`、`ExpressiveShapes` | `.../common/ExpressiveShapeProvider.kt` |
| `components.common.WaveSliderKt` | `.../common/WaveSlider.kt` |
| `components.common.ArcProgressSliderKt` | `.../common/ArcProgressSlider.kt` |
| `components.common.CookieHorizontalSliderKt` | `.../common/CookieHorizontalSlider.kt` |
| `components.common.MarqueeTextKt`（含 `AutoScrollingText`） | `.../common/MarqueeText.kt` |
| `components.common.DragDropListKt` | `.../common/DragDropList.kt` |
| `components.common.CollapsibleHeaderKt` | `.../common/CollapsibleHeader.kt` |
| `components.common.RhythmWavyProgressLoaderKt` | `.../common/RhythmWavyProgressLoader.kt` |
| `components.common.RhythmControlButtonKt`、`RhythmButtonGroupKt` | `.../common/RhythmControlButton.kt`、`RhythmButtonGroup.kt` |
| `components.common.M3PlaceholdersKt`、`ShimmerBox` | `.../common/M3Placeholders.kt`、`ShimmerBox.kt` |
| `components.player.MorphingPlayPauseButtonKt` | `.../components/player/MorphingPlayPauseButton.kt` |
| `components.player.ExpressiveMiniPlayerKt` | `.../player/ExpressiveMiniPlayer.kt` |
| `components.player.CanvasArtworkPlayerKt` | `.../player/CanvasArtworkPlayer.kt` |
| `components.player.AnimatedPlaybackControlsKt` | `features/local/presentation/components/player/AnimatedPlaybackControls.kt` |
| `components.lyrics.SyncedLyricsViewKt` | `.../components/lyrics/SyncedLyricsView.kt` |
| `components.lyrics.WordByWordLyricsViewKt` | `.../lyrics/WordByWordLyricsView.kt` |
| `components.lyrics.WordAnimationPreset` | `.../lyrics/WordAnimationPreset`（枚举/数据类） |
| `components.icons.MaterialSymbolIcon`、`RhythmIconFontCache` | `.../components/icons/`（Material Symbols 图标字体方案） |

### 页面与导航

| DEX 类 | 仓库文件 |
|---|---|
| `features.local.presentation.navigation.LocalNavigationKt`、`Screen` | `features/local/presentation/navigation/LocalNavigation.kt` |
| `shared.presentation.navigation.*` | `shared/presentation/navigation/` |
| `shared.presentation.screens.settings.SettingsScreenKt` | `shared/presentation/screens/settings/SettingsScreen.kt` |
| `...settings.ThemeCustomizationSettingsScreenKt` | `.../settings/ThemeCustomizationSettingsScreen.kt` |
| `...settings.ExpressiveShapesSettingsScreenKt` | `.../settings/ExpressiveShapesSettingsScreen.kt` |

完整列表见 `data/classes.csv`（3,367 行）与 `data/functions-by-file.csv`（245 个文件类 + 1,039 个函数名）。

---

## 四、怎么用这份索引查东西

```bash
# 1. 找某个功能在哪个包
grep -i "lyrics" data/classes.csv | head

# 2. 看某个文件类里有哪些函数（从 lambda 类反推）
grep "ExpressiveComponents" data/functions-by-file.csv

# 3. 确认某个 Compose API 是否被用到
grep -i "MotionScheme" data/api-usage.csv
grep -i "SharedTransition" data/api-usage.csv

# 4. 查 APK 里某个资源/库
grep "graphics" data/apk-entries.tsv
```

**能查到什么**：包名、类名、函数名（部分）、API 引用、资源。
**查不到什么**：方法体、参数默认值、具体数值常量（DEX 方法名被 R8 压缩，字符串表里也没有 UI 常量）。要拿参数必须读源码——这也是 `REPORT.md` 里参数标注 `[A]` 的原因。
