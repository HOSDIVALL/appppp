# 第 02～05 批 Rhythm 研究记录汇总（含归档清单）

更新日期：2026-11-13。批次：RHYTHM-KB-BACKUP-06。
用途：把 2026-11-12～13 这几轮已经做出来的东西**按批次登记清楚**——哪些原件已入仓、哪些只剩对话摘要、哪些根本没取得。本文件不重写研究结论，只做登记与索引；结论正文在 `originals/analysis-2026-11-13/` 里那几份原件中。

固定上游：`cromaguy/Rhythm`，tag `v5.5.499.1302-stable`，commit `6f26f963b82ed592ec28f05824bb7f12b65b82ea`。
被分析二进制：`Rhythm-5.5.499.1302-fdroidRelease-arm64-v8a.apk`，23,572,778 字节，SHA-256 `a13cc67e0eb1873451326544ca19391ca0598913fc441110a91455ba852f52fe`，2,215 个条目，`classes.dex` + `classes2.dex`。

## 一、批次与产物对应关系

| 批次 | 内容 | 主要判据 | 原件是否入仓 |
|---|---|---|---|
| 01 | 首轮解包：技术栈判定、主题层、组件三层、动效拆解 | APK 条目 + DEX 方法词表 + 源码抽查 | **否**（初版正文已被第 05 批原地修订覆盖，未留副本） |
| 02 | 指令级调用点：谁真的调了 M3 / 动画 API | Dalvik `invoke-*` 解码 | 是（脚本 + 汇总 + 报告正文） |
| 03 | 签名级反混淆：把 `a/b/c` 还原成 `Card`/`fadeIn` | 官方 AAR 的 `classes.jar` 签名匹配 | 是（脚本 + 汇总 + 反混淆结果表未入仓，仅报告引用） |
| 04 | 源码对齐（tag 锁版本）与源码侧清点 | `import` 行 + 文件内使用次数 | 是（脚本 + 汇总 + 报告正文） |
| 05 | 覆盖率与更正：8 个 Expressive 组件误判翻案 | 交叉核对 02/03/04 三套数字 | 是（`COVERAGE.md` 与修订后的 `REPORT.md`） |
| 06 | 列表项定点核验 + 网页原型 | 定点读源码片段 | 见 `rhythm-list-items-06.md`；网页原型已入仓 |

## 二、第 02 批：指令级调用点

已入仓原件：`originals/analysis-2026-11-13/tools/callsites.py`（11,674 B）、`originals/analysis-2026-11-13/data/callsite-summary.json`（363 B），以及 `REPORT.md` 中引用这些数字的章节。

方法与数字：

- 完整解码 Dalvik 指令（含 `switch` / `fill-array-data` 载荷跳过），不只查 `method_ids` 词表；
- 3,540 个自有类、13,055 个方法、**136,741 个 invoke 点**；
- 其中 M3 **2,378**、animation **1,431**、animation-core **895**（动画合计 2,326）、foundation 3,893；
- 被自有代码真正调用的 M3 成员 **127** 个、涉及 59 个 M3 类、286 个自有类；
- 产出 1,403 条调用边、3,809 个调用点（边与点不是一回事，引用时别混）。

限制：调用点数量**不等于**使用强度。同一 tag 源码 `Slider(` 34 处，二进制里只剩 1 个调用点；R8 会内联或合并类。这条限制在第 05 批被写进覆盖率，属于"口径纠正"，不是补充说明。

## 三、第 03 批：签名级反混淆

已入仓原件：`originals/analysis-2026-11-13/tools/deobf.py`（7,805 B）、`originals/analysis-2026-11-13/data/deobf-summary.json`（630 B）。

方法与数字：

- 依据：R8 改方法名，不改参数类型顺序；会削掉恒为常量的尾部参数（Composable 的 `changed: Int`、`$default` 的尾 `Object`）；
- 用官方 AAR 建 `(类, 返回类型)` 索引，做**参数前缀匹配 + 名字唯一**判定，多候选一律标 `ambiguous` 不猜；
- 三个 AAR：`material3-android-1.5.0-beta01`（1,328 类 / 14,659 方法）、`animation-android-1.12.1`（326 / 1,981）、`animation-core-android-1.12.1`（178 / 1,438）；
- 匹配分布：`exact` 173、`app_shorter` 515、`aar_shorter` 134、`ambiguous` 205；
- 已定名 121 个成员、3,080 个调用点（占 3,809 的 **80.9%**）；未定名 54 条边、729 个调用点；动画成员定名 28 个。

限制：80.9% 是**按调用点算的命中率**，不是"反混淆准确率"；`ambiguous` 的 205 条没有强行给名字。AAR 本体未入仓（见第四节）。

## 四、第 04 批：源码对齐与源码侧清点

已入仓原件：`originals/analysis-2026-11-13/tools/source_scan.py`（3,326 B）、`originals/analysis-2026-11-13/data/source-summary.json`（162 B），以及 `REPORT.md` / `COVERAGE.md` 里引用这些数字的章节。

方法与数字：

- 源码锁到 tag `v5.5.499.1302-stable`（commit `6f26f96`），不再对 `main`；
- 按 **`import` 行**统计（先剔除 import 行再计使用次数，否则每次导入都算一次）；扫描 `main` + `fdroid` 两个源集；
- 373 个 `.kt`、176 个文件 import 了 material3、137 个 M3 符号、**5,603 次使用**；动画 52 个符号、2,733 次；
- 动效参数谱：499 个 `tween`、395 个 `spring`（分布表 `anim-params-source.csv` 未入仓）；
- 定点结论：`MaterialShapesUtils.kt` 的 **35** 种形状枚举、8 个 `ShapeTarget`、7 套预设；歌词 catch-up 为"阈值 8 行 + `scrollToItem(目标±1)` 瞬跳 + `animateScrollToItem` 补最后一格"。

限制：源码侧次数与二进制侧调用点**不同源**，不能相除当覆盖率；R8 内联让两边无法逐项对齐。

## 五、第 05 批：覆盖率与更正

已入仓原件：`originals/analysis-2026-11-13/COVERAGE.md`（12,178 B）、`originals/analysis-2026-11-13/REPORT.md`（21,855 B，修订后版本）、`originals/analysis-2026-11-13/README.md`（6,474 B）。

本批做的事：

- 推翻第 01 批的一处误判：`FloatingToolbar`、`SplitButton`、`WideNavigationRail`、`SegmentedButton`、`ToggleButton`、`ButtonGroup`、`SearchBar`、`Tooltip` 这 8 个组件曾被列为"在用"，实际源码 tag 里调用数为 0；错因是把 DEX `method_ids` 词表当成了使用；
- 把覆盖率分成"已确证 / 部分 / 未覆盖"三档，逐项给判据；
- 明确写进 README 的两条限制：源码已对齐版本（但 R8 不可逐项对齐）、GPL-3.0-or-later 的传染边界。

**这批有一个必须说清的缺口**：第 01 批的初版正文是在原文件上直接改的，没有留旧副本。所以"第 01 批当时到底怎么写的"**没有原件可查**，只能给对话摘要（本文件第一至五节即摘要）。任何人要复核"误判是怎么发生的"，只能看修订后的正文与 `COVERAGE.md` 里的更正说明，看不到被删掉的原文。

## 六、网页原型（WEB-REBUILD）

已入仓原件（`originals/web-2026-11-13/`）：

| 文件 | 字节 | 性质 |
|---|---|---|
| `Rhythm-Pinned-Component-Reader.html` | 50,742 | 固定版式阅读器，逐个组件配静态演示 |
| `Rhythm-Expressive-Interactive.html` | 30,796 | 交互演示（按压缩放、页签、形变按钮） |
| `Material-Official-Component-Lab.html` | 39,178 | 官方组件对照实验室 |
| `rhythm-interaction-review.json` | 2,906 | 交互事件记录（形变按钮点击序列等），首行自述 `browser-reconstruction-not-native` |

三条必须保留的限定：

1. 这些页面是**浏览器重建**，不是 Compose，也不是官方几何；不能反向充当 NATIVE-TEST，也不能当参数来源。
2. 页面里的"抬升 1.025"属于原型观感值，已作废。本轮对上面三份 HTML 做字面检索，`1.025` 命中 **0** 次——连原型值本身都无法再追溯，所以更不能当源值；行缩放以源码 1.005 / 0.98 / 1 为准（见 `rhythm-list-items-06.md` §1）。
3. 没有取得 Rhythm 官方发布的任何网页/文档快照，因此本目录**不构成对官方站点的备份**。

`rhythm-interaction-review.json` 只记录了"点了什么、什么时候点的"，不记录结论；它证明的是"原型被操作过"，不证明任何参数正确。

## 七、只有对话摘要、没有原件的部分

以下内容在会话里说过，但没有可归档的原件，本目录**不为它们伪造附件、截图、录屏或源码副本**：

- 第 01 批初版正文（被第 05 批原地覆盖）；
- 第 02/03/04 批的运行过程与控制台输出（只留下 `data/*.json` 汇总，无完整日志）；
- 第 05 批逐次编辑的过程（改了哪几行、先后顺序）；
- 网页原型的制作过程与调试记录（只有最终 HTML 与事件 JSON）；
- 任何"在设备上看起来如何"的描述——这一项不是漏记，而是**从未运行**。

## 八、明确未归档 / 未取得

| 材料 | 状态 | 原因 |
|---|---|---|
| Rhythm APK 本体（23.5 MB） | 未入仓 | 体积与第三方许可；哈希已登记，可自行取同版本核对 |
| 3 个官方 AAR（material3 1.5.0-beta01、animation 1.12.1、animation-core 1.12.1） | 未入仓 | 二进制；来源说明写在原始分析包的 `libs/README.md`（未随本目录入仓） |
| `classes*.dex` 解包中间物 | 未入仓、已清理 | 可复跑生成 |
| `data/` 下 14 个 CSV/TSV（含 `apk-entries.tsv`、`classes.csv`、`edges-lib.tsv`、`api-resolved.csv`、`source-m3-imports.csv`、`anim-params-source.csv` 等，约 2.1 MB） | 未入仓 | 可复跑生成的数据表，不是文档；数字已写进报告正文与汇总 JSON |
| Rhythm 源码副本 | 未入仓 | GPL-3.0-or-later；只引用路径、blob 与参数，不复制实现 |
| 截图 / 录屏 / 真机日志 | 不存在 | 本轮及此前各批均未运行原生 |
| 官方网页快照 | 未取得 | 未抓取，故不声称"网页已备份" |

## 九、哈希口径（重要，别拿错清单）

- 本目录的校验清单是 `docs/design/research/SHA256SUMS`，**只覆盖 `docs/design/research/` 下的文件**（不含它自己）。
- `docs/design/SHA256SUMS` 仍然**只覆盖两份历史原件**（`material3-backup-20261110.md`、`m3-impact-estimate.md`），不是全库清单；本轮没有改它，也没有把它扩写成"全库校验"。
- 两份清单互不替代：用 research 的清单去验 `docs/design/` 顶层文件会失败，反之亦然。

## 十、复验

```bash
cd docs/design/research && sha256sum -c SHA256SUMS     # 期望全部 OK
cd docs/design && sha256sum -c SHA256SUMS              # 仍是 2 条，期望 OK
```

原件内部还带一份 `originals/analysis-2026-11-13/` 的来源包清单（原始包里的 `SHA256SUMS` 覆盖 29 个文件）；那份清单没有随原件复制进来，因为其中 14 个数据表与 APK 派生物未入仓，复制进来会造成"清单比文件多"的假象。
