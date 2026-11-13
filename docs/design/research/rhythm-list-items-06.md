# RH-09 列表项：播放列表行（参数、接线与边界）

更新日期：2026-11-13。批次：RHYTHM-KB-BACKUP-06。
固定上游：`cromaguy/Rhythm`，tag `v5.5.499.1302-stable`，commit `6f26f963b82ed592ec28f05824bb7f12b65b82ea`。
本文只登记**静态源码**读到的东西；没有编译、没有安装、没有在模拟器或真机上看过一行动画。凡"代码这么写"与"屏幕上长这样"的差别，本文一律不合并成一句结论。

## 0. 先声明阅读范围（这是本文最容易被误读的部分）

本轮是**定点核验**，不是通读源代码库：

| 文件 | 相对路径（`app/src/main/java/chromahub/rhythm/app/`） | 字节 | 行数 | git blob | 本轮阅读方式 |
|---|---|---|---|---|---|
| `PlaylistDetailScreen.kt` | `features/local/presentation/screens/PlaylistDetailScreen.kt` | 166,578 | 3,114 | `a141fe5c304568097b4fbc55740070d63eba4886` | **只做片段核验**：按标识符命中 + 读 2753–3114 行（`PlaylistSongItem` 与 `groupedPlaylistDetailItemShape`）。**没有通读** 1–2752 行 |
| `DragDropList.kt` | `shared/presentation/components/common/DragDropList.kt` | 6,877 | 161 | `c416d88d8ce90bc5840cb614393a10dc85b19d24` | **完整读取**（161/161 行） |
| `RhythmGroupedMenu.kt` | `shared/presentation/components/common/RhythmGroupedMenu.kt` | 6,009 | 149 | `eb6843d8fb54c6da2396b5cfacf1348129833295` | **完整读取**（149/149 行） |
| `libs.versions.toml` | `gradle/libs.versions.toml` | 8,967 | 123 | `33bd54b0325762dc04dcfc40ad2866c0aff08f6c` | 只读依赖声明（版本号，不是解析结果） |

blob 由 `git hash-object` 对固定 commit 工作树内的文件现算，算法与 GitHub 的文件 blob 一致；换版本后这些值会变，不能跨版本引用。

阅读范围的直接后果：

- 两个小文件（各 161、149 行）可以按"整个文件都看过"来说；
- `PlaylistDetailScreen.kt` 只能说"这一屏的第 2753–3114 行看过"，**不能**说"这个页面已通读"。页头、筛选、排序菜单、拖拽接线之外的分支（1–2752 行）本文不作断言；
- 没有运行，所以下文所有"看起来会怎样"都只是源码声明，不是实测观感。

## 1. 行缩放：1.005 / 0.98 / 1

这一条是本轮被纠正的数值，先写清楚。

`PlaylistDetailScreen.kt:2865-2873` 用 `animateFloatAsState` 驱动 `itemScale`，目标值三态：

| 状态 | 目标值 | 判定条件 | 依据行 |
|---|---|---|---|
| 拖动中 | **1.005** | `isDragging` | 2866 |
| 排序模式 / 多选模式 | **0.98** | `isReorderMode \|\| isMultiSelectMode` | 2866 |
| 常态 | **1**（`1f`） | 其余 | 2866 |

- 动画规格：`spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)`（2867–2870）。
- 落点：`Modifier.graphicsLayer { scaleX = itemScale; scaleY = itemScale }`（2893–2896），两轴同时写，不是只写一轴。
- 单位：`scale` 是**无量纲倍率**，不是 dp，也不是 px。把 1.005 写成"1.005dp 抬升"是错的。

**关于 1.025。** 先前互动网页里那种"按下行微微抬起"的 1.025 属于 **WEB-REBUILD**（浏览器重建），是为了在网页上做出可感知的抬升而选的观感值，不是 Rhythm 的源码值。本轮对现存三份原型 HTML 做字面检索，`1.025` 命中 0 次，也就是说连这个原型值本身都已无法从现存文件追溯。结论只有一句：**1.025 不得作为源值引用，也不得写进参数表**；行缩放以源码的 1.005 / 0.98 / 1 为准。

## 2. 一行的几何与颜色

同一函数 `PlaylistSongItem`（`PlaylistDetailScreen.kt:2753` 起）内读到的常量：

| 项 | 值 | 依据行 | 备注 |
|---|---|---|---|
| 行外边距 | 水平 8dp、垂直 2dp | 2892 | 行与行之间因此是 4dp 视觉间隙（2+2），不是 2dp |
| 行容器 | `Surface(onClick = …)`，`tonalElevation = itemElevation`，`shadowElevation = 0.dp` | 2887–2896 | 阴影恒为 0，抬升只靠 tonalElevation |
| 行容器高度（tonalElevation） | 拖动 0dp / 排序 1dp / 其余 2dp | 2875–2881 | 拖动时反而压平，与"拖动=抬起"的直觉相反 |
| 行圆角 | 分组形状：单行 24dp；首行 24/24/6/6；末行 6/6/24/24；中间 6dp | 3097–3113 | 圆角顺序为 topStart/topEnd/bottomStart/bottomEnd |
| 行内边距 | 水平 16dp、垂直 10dp | 2901 | 与行外 8dp 叠加后，内容距屏幕边缘 24dp |
| 封面 | 56dp；形状取 `rememberExpressiveShapeFor(SONG_ART, fallback = RoundedCornerShape(12.dp))`；tonalElevation 4dp | 2929–2938 | 形状由全局 Expressive 形状提供者决定，12dp 只是兜底 |
| 封面描边 | 2dp；当前曲用 `onPrimary`，选中用 `tertiary`，否则无 | 2938 | 两条条件互斥（当前曲且未选中 / 已选中） |
| 播放角标 | 20dp 圆，偏移 (4dp, 4dp) 贴封面右下；内部图标 12×10dp、3 条 | 2946–2960 | 只在"当前曲且正在播放"时出现 |
| 标题 | `titleMedium`；当前曲 Bold，其余 Medium；单行省略 | 2978–2983 | |
| 副标题 | `bodyMedium`，单行省略 | 2984–2991 | 歌手 |
| 第三行 | `bodySmall`，仅当 `album` 非空且不等于歌手名 | 2994–3002 | 专辑；与歌手同名时整行不出现，行高会变 |
| 时长 | `bodySmall`，右内边距 4dp；**排序模式下隐藏** | 3006–3012 | 隐藏是为了给手柄腾位置 |
| 文字颜色 | 四处 `animateColorAsState`，均为 `tween(300)` | 2777–2797 | 播放态用 `onPrimary`，透明度 0.75 / 0.6 / 0.5 分档 |
| 行底色 | 选中 `tertiaryContainer` α0.5；当前曲 `primary`；其余 `surfaceContainer`；弹簧同上 | 2851–2862 | 颜色与缩放共用一组弹簧常量 |

单行 24dp 与中间 6dp 的差值是"分组卡片"观感的来源；把每一行都做成 24dp 会把列表变成一叠独立卡片，不是这个设计。

## 3. 尾部操作的三种形态

同一个位置按模式切换，三者互斥：

| 模式 | 控件 | 尺寸 / 形状 | 颜色与触觉 | 依据行 |
|---|---|---|---|---|
| 排序 | `Surface` + 拖拽图标 | 36dp，圆角 12dp，图标 20dp | 拖动中 `primaryContainer` / tonal 0；否则 `secondaryContainer` / tonal 1 | 3019–3034 |
| 多选 | `FilledIconButton`（移除） | 40dp，图标 20dp | `errorContainer` α0.8 / `onErrorContainer`；LIGHT 触觉；点击弹移除确认框（圆角 24dp） | 2799–2849、3040–3060 |
| 常态 | `FilledIconButton`（更多） | 宽 32dp × 高 44dp，`RoundedCornerShape(50)`，图标 22dp | 当前曲 `onPrimary`/`primary`，否则 `primaryContainer`/`onPrimaryContainer`；**HEAVY** 触觉 | 3062–3095 |

多选模式左侧还有一个 `Checkbox`，容器用 `AnimatedVisibility` 做 `expandHorizontally` / `shrinkHorizontally`，弹簧同样是 MediumBouncy + Medium，配 `fadeIn` / `fadeOut`（2905–2926）。也就是说多选进入时行是"横向长出来"的，不是直接出现。

## 4. 相邻的两个小文件（完整读取）

### 4.1 `DragDropList.kt`：**这个页面没有用它**

先记一条容易看错的结论：`PlaylistDetailScreen.kt` 用的是**裸 `LazyColumn` + `ReorderableItem`**（1455 / 1673、2084 / 2356），并没有调用 `DragDropLazyColumn`。全仓检索 `DragDropLazyColumn` 的调用点都在 bottom sheet 里：

- `features/local/presentation/components/bottomsheets/EqualizerPresetOrderBottomSheet.kt:231`
- `features/local/presentation/components/settings/HomeSectionOrderBottomSheet.kt:265`
- `features/local/presentation/components/settings/LibraryTabOrderBottomSheet.kt:144`
- `features/streaming/presentation/components/settings/StreamingHomeSectionOrderBottomSheet.kt:268`

所以不能用它来替播放列表的行为背书，反过来也一样。

文件本身（161 行）的要点：

- 泛型 `DragDropLazyColumn`，依赖 `sh.calvin.reorderable` 的 `rememberReorderableLazyListState` / `ReorderableItem`；
- 触觉分四档：长按起拖 **HEAVY**、拖动落位 **MEDIUM**、拖动中每次移动 **LIGHT**、松手 **LIGHT**（96–104、147、150）；
- `animateItemPlacement = true` 时入场/退场用 `tween(0)`，位移用 `spring(NoBouncy, StiffnessMediumLow)`（126–134）——即"新位置滑过去，但不要淡入淡出"；
- 拖动期间 `userScrollEnabled = !isAnyItemDragging`（111），防边拖边滚；
- 落位回调只在起点与终点不同时触发 `onDrop`，否则走 `onDragCancel`（97–102）；
- 空列表、`isStickyHeader`、`itemSpacing`、`dragTopInset` / `dragBottomInset` 都有入参，但本文件不决定这些值，由调用方给。

未做：没有运行，没验证长按判定时长、触觉在设备上的实际强度、以及和 `Modifier.animateItem` 的相互作用。

### 4.2 `RhythmGroupedMenu.kt`：分组菜单行

149 行，文件自述"shared by the song item and header 3-dot menus"。调用点在 `PlaylistDetailScreen.kt:964`（播放列表 3 点菜单）、`RhythmSongMenu.kt:223`、`EqualizerScreen.kt:671`。

| 项 | 值 | 依据行 |
|---|---|---|
| 外圆角 / 内圆角 / 行间距 | 16dp / 4dp / 3dp | 37–39 |
| 外边距 | 水平 8dp、垂直 4dp | 70 |
| 行内边距 | 水平 14dp、垂直 10dp | 85 |
| 图标容器 | 28dp 圆，图标 16dp，图标与文字间距 10dp | 88–115 |
| 文本 | `bodyMedium` + `FontWeight.Medium`；破坏性操作用 `error` | 117–127 |
| 点击 | MEDIUM 触觉后执行回调 | 75–78 |
| 行形状 | 单行 16dp；首行 16/16/4/4；末行 4/4/16/16；中间 4dp | 134–149 |

和列表项是同一种"分组圆角"语法，但外/内圆角是 16/4，不是列表项的 24/6；两套常量各自独立，不能互相套用。

## 5. 接线（WIRING）与它不能证明什么

- `PlaylistSongItem` 声明在 2753；两处调用：1699（排序/常规列表）与 2382（另一分支，同样是 `ReorderableItem` 包裹）。
- 点击语义按模式短路：多选 → 切换 `selectedSongs` 后 `return@PlaylistSongItem`；排序模式 → 直接 `return`；否则 LIGHT 触觉后播放（1701–1717）。
- 拖拽手柄通过 `dragHandleModifier = handleModifier`（1735、2418）注入 3028 行的 `.then(dragHandleModifier)`；手柄的长按、`reorderHandleInUse` 标志与 MEDIUM 触觉都在调用侧（1680–1697、2360–2380）。
- 这些只证明"声明接得上"。**不能**由此推断：重排后列表如何落库、`LazyColumn` 的 key 是否稳定、快速连拖会不会错位、以及真机上 1.005 的抬升是否肉眼可见。

## 6. 不能声称的部分

1. **未运行**：没有 NATIVE-TEST。任何"手感""流畅度""掉帧"都没有证据。
2. **`PlaylistDetailScreen.kt` 未通读**：只核验 2753–3114 与若干命中行；1–2752 行的页头、筛选、排序、菜单逻辑不在本文结论范围内。
3. **源码与二进制未逐项对齐**：本文对着 tag `v5.5.499.1302-stable` 源码，fdroid APK 只用于"该类是否存在"这一层；R8 会内联/合并，调用次数不能反推。
4. **网页原型不是证据**：`WEB-REBUILD` 只用于讨论观感，1.025 已明确作废。
5. **单位**：scale 无量纲；dp 与 px 不互换；`itemSpacing` 类的值由调用方给，本文没有替它填。

## 7. 复核方式

```bash
# 取固定版本
git clone --depth 1 --branch v5.5.499.1302-stable https://github.com/cromaguy/Rhythm.git

# 校验本文引用的 blob（应在该 commit 工作树内执行）
git hash-object app/src/main/java/chromahub/rhythm/app/features/local/presentation/screens/PlaylistDetailScreen.kt
git hash-object app/src/main/java/chromahub/rhythm/app/shared/presentation/components/common/DragDropList.kt
git hash-object app/src/main/java/chromahub/rhythm/app/shared/presentation/components/common/RhythmGroupedMenu.kt

# 复核三态缩放与分组圆角
sed -n '2860,2900p' app/src/main/java/chromahub/rhythm/app/features/local/presentation/screens/PlaylistDetailScreen.kt
sed -n '3097,3114p' app/src/main/java/chromahub/rhythm/app/features/local/presentation/screens/PlaylistDetailScreen.kt
```

许可：Rhythm 为 GPL-3.0-or-later。本文只记录事实性参数，不含可粘贴的实现片段；照做请自行重写，不要复制源码。
