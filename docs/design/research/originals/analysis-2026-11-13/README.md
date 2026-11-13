# Rhythm 5.5.499.1302 解包分析包

对被分析的 APK：

| 项 | 值 |
|---|---|
| 文件 | `Rhythm-5.5.499.1302-fdroidRelease-arm64-v8a.apk` |
| 包名 | `chromahub.rhythm.app` |
| 版本 | `versionName 5.5.499.1302-fdroid` / `versionCode 554991302` |
| 大小 | 23,572,778 字节 |
| SHA-256 | `a13cc67e0eb1873451326544ca19391ca0598913fc441110a91455ba852f52fe` |
| 清单 | minSdk 24，targetSdk 37，arm64-v8a |

本包用于回答一个问题：**Rhythm 的 Material 组件体系与动效是怎么搭的，能不能照着做。**

## 先看这四行

1. 它**不是 Flutter**。虽然 Rhythm 早期版本用过 Flutter，这一版是纯 Kotlin + Jetpack Compose，UI 全部代码绘制（APK 里 `res/` 只有 `color*`，一个 layout、一个 drawable 都没有）。
2. 它跑在 **Compose 1.12.1 + Material3 1.5.0-beta01**，并在根主题里显式传入 `MotionScheme.expressive()`——也就是说整套动效的基准节奏来自 Material 3 Expressive，而不是自己手搓一套时间轴。
3. 动效分三层：**M3 原生（MotionScheme 驱动）→ 自家 Expressive 封装（统一弹簧与缩放梯度）→ 少数手工 morph（路径形变、权重联动、歌词 catch-up、雪花粒子）**。真正有参考价值的是第二、三层，本包把它们逐个拆到了参数级。
4. **别把"依赖里有"当成"它在用"**。经指令级调用点核对，`FloatingToolbar`、`SplitButton`、`WideNavigationRail`、`SegmentedButton`、`ToggleButton`、`ButtonGroup`、`SearchBar`、`Tooltip` 这 8 个 Expressive 组件在 5.5.499.1302 里**一次都没被调用**；上一版报告曾把它们错列为"在用"（口径错误，已更正）。逐项覆盖率见 `COVERAGE.md`。

## 包内文件

| 文件 | 内容 |
|---|---|
| `COVERAGE.md` | **先读这个**。逐项覆盖率：已确证 / 部分确证 / 未覆盖，附数字与判据；含一处结论更正与"为什么 APK 调用次数不能当使用强度" |
| `REPORT.md` | **主报告**。技术栈判定、主题层、组件体系、动效体系（含具体时长/弹簧/缩放常量）、复刻配方、许可提醒 |
| `FILE-INDEX.md` | 文件索引。APK 条目结构、自有代码包地图、关键类 → 仓库路径对照表 |
| `COMPONENTS.md` | 组件清单表（按包分组的文件 → 可还原的公开函数） |
| `data/apk-entries.tsv` | APK 全部 2,215 个条目的路径、大小、SHA-256 |
| `data/classes.csv` | Rhythm 自有类全表（3,367 条，含内部类与所在包） |
| `data/functions-by-file.csv` | 从 lambda 内部类还原出的 245 个文件类、1,039 个函数名 |
| `data/edges-lib.tsv` | **指令级调用边** 1,403 条：调用方类 → 目标类 + 方法描述符 + 混淆名 + 次数 |
| `data/api-resolved.csv` | 用官方 AAR 做**签名级反混淆**后的 API 名（含匹配等级：exact / app_shorter / aar_shorter / ambiguous） |
| `data/m3-components.csv` | M3 成员粒度的调用方数量与调用点数 |
| `data/m3-usage-by-class.csv` | 自有类 → 它调用的 M3 API |
| `data/own-calls.csv` | 自有类 → 自有类（组件之间的组装关系） |
| `data/animation-api.csv` | 动画 API 反混淆汇总 |
| `data/source-m3-imports.csv` | **源码侧** M3 符号清点（137 个符号，含使用次数与文件数） |
| `data/source-animation-imports.csv` | 源码侧动画符号清点（52 个） |
| `data/anim-params-source.csv` | 源码动效参数谱：499 个 tween、395 个 spring 的分布 |
| `data/api-usage.csv` | 早期按方法词表统计的引用明细（口径有偏，保留供对照） |
| `data/stats.json` / `callsite-summary.json` / `deobf-summary.json` / `source-summary.json` | 汇总数字 |
| `SHA256SUMS` | 上述文件的校验清单，`sha256sum -c SHA256SUMS` 可验 |
| `tools/` | 可复跑脚本：`callsites.py`（指令级调用点）、`deobf.py`（签名级反混淆）、`source_scan.py`（源码清点）、`gen_data.py`（索引重建） |

## 证据可信度（请按级别引用）

本包里的结论有三种来源，报告正文中已分别标注：

| 级别 | 含义 | 举例 |
|---|---|---|
| **A · 源码原文** | 从 GitHub `cromaguy/Rhythm` 对应文件直接读到并引用 | `MaterialTheme(…, motionScheme = MotionScheme.expressive(), …)`、`Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided)` |
| **B · 构建产物实证** | 从 APK 的 DEX / 清单 / 资源里解析出来 | 依赖版本矩阵、类与方法清单、实际引用了哪些动画 API |
| **C · 推断** | 由类名、函数名、API 引用反推，未逐行核对实现 | 某个组件"大概率"用了某种做法 |

**C 级不要当成事实引用**。报告里凡写"推断"的都属于这一档。

## 两个必须知道的限制

- **源码已对齐版本**：本轮把对照源码锁到 tag `v5.5.499.1302-stable`（commit `6f26f96`，`Release 5.5.499.1302`），与该二进制同一版本，参数级常量可逐字对应（上一版对的是 `main`，存在位移，已修正）。但仍有两点不同步：R8 会内联/合并，二进制里的调用次数**不能**反推源码次数；`COVERAGE.md` §2.4 给了具体反例（源码 34 处 `Slider(`，二进制只剩 1 个调用点）。
- **许可**：Rhythm 是 **GPL-3.0-or-later**。读它、学它、按它的思路自己写没问题；**把它的代码片段拷进别的应用**（尤其闭源/上架应用）会触发 GPL 传染义务。报告的"复刻配方"给的是做法与参数，不是可粘贴的源码。

## 复跑方式

```bash
# 0) 反混淆需要三个官方 AAR（放在 libs/ 下）
#    material3-android-1.5.0-beta01.aar
#    animation-android-1.12.1.aar
#    animation-core-android-1.12.1.aar

# 1) 从 APK 重建基础索引（Python 3.9+，无第三方依赖）
python tools/gen_data.py  "路径/Rhythm-5.5.499.1302-fdroidRelease-arm64-v8a.apk"

# 2) 指令级调用点（谁调了谁，含方法描述符）
python tools/callsites.py  "路径/Rhythm-...apk"

# 3) 签名级反混淆（把 a/b/c 还原成 Button / fadeIn / spring）
python tools/deobf.py

# 4) 源码侧清点（需要源码目录，tag v5.5.499.1302-stable）
python tools/source_scan.py "路径/rhythm-src"

# 5) 校验本包完整性
sha256sum -c SHA256SUMS
```

`tools/callsites.py` 会自己从 APK 解出 `classes*.dex` 到 `dex/`；`tools/dexp.py` 是它依赖的 DEX 解析器，
`tools/analyze.py` / `tools/gen_data.py` 是早期按方法词表统计的脚本（口径有偏，保留供对照）。
