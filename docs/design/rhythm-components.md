# Rhythm 组件与动效核验索引

更新日期：2026-11-13。用途：项目设计知识库。只登记静态源码核验，不是组件运行、性能或无障碍验收。
固定上游：cromaguy/Rhythm，tag v5.5.499.1302-stable，提交 6f26f963b82ed592ec28f05824bb7f12b65b82ea。
源码根：https://github.com/cromaguy/Rhythm/tree/6f26f963b82ed592ec28f05824bb7f12b65b82ea
以下相对路径均以 app/src/main/java/chromahub/rhythm/app/shared/presentation/components/ 为根。
本文件是当前研究的统一入口，不改写 material3-backup-20261110.md 或 m3-impact-estimate.md。

## 证据等级与使用规则

SOURCE：在固定 SHA 完整读到实现；不代表已定位页面调用、不代表已运行。
REPORT：用户解包报告提供的线索，未源核验的数值不得当事实。
WEB-REBUILD：互动网页的独立重建，不是 Compose 或官方精确几何。
NATIVE-TEST：需要编译、指定模拟器、实际运行记录；本索引所有条目目前均没有本轮 NATIVE-TEST。
项目定制参数不是官方默认；内部 token、公开 API、编译成功与运行通过分别记录。源文件标 GPL-3.0-or-later，复制前单独检查许可；本索引只保存事实性分析，不复制完整实现。

## 已核验清单（8份文件）

### RH-01 形变播放按钮
路径：player/MorphingPlayPauseButton.kt
blob：a7941d0611ac856e239e9465b1f9e045439c0c15
等级：SOURCE。分类：官方图形/动画能力与项目编排。
MaterialShapes.Circle 与 Cookie9Sided 用 Morph 插值；形变目标随 isPlaying 为0/1；MediumBouncy+StiffnessLow。按压缩放0.92；播放旋转15000ms/圈、LinearEasing，暂停绘制角0。图标Crossfade 220ms、FastOutSlowInEasing。加载分支使用M3CircularLoader。未验证具体布局调用与运行效果。

### RH-02 波浪滑块
路径：common/WaveSlider.kt
blob：79c72b050d9feb1907e21edf8dcd1f9508558c74
等级：SOURCE。分类：官方轨道+自绘手柄/手势。
轨道直接调用LinearWavyProgressIndicator，不是全自绘波浪。默认strokeWidth=5dp、thumbRadius=8dp；手柄16x16dp到宽strokeWidth*1.2、高24dp，250ms/FastOutSlowInEasing。振幅目标：启用且播放且未拖动为1，否则0，采用ProgressAnimationSpec。波长取库默认，waveSpeed默认该波长一半；gap=thumbRadius+4dp、stopSize=3dp。waveAmplitudeWhenPlaying虽有4dp入参，但函数体未使用。含setProgress语义与提交回调。库内部波形、默认规格和触控运行未验证。

### RH-03 加载封装
路径：common/Loaders.kt
blob：8716f5759d8104048dec9b308d06531ebb251144
等级：SOURCE。分类：官方组件封装。
M3LinearLoader调用LinearWavyProgressIndicator；M3CircularLoader调用CircularWavyProgressIndicator；progress是否为空选择确定/不确定重载。M3FourColorCircularLoader调用LoadingIndicator；SimpleCircularLoader调用ContainedLoadingIndicator。部分strokeWidth、fourColor等封装入参未传到底层。不能凭FourColor/Segmented/Buffered/Dot名称保证相应效果。

### RH-04 自定义选择组
路径：common/ExpressiveButtonGroup.kt
blob：48e085dc219e9874108c5ef90f182edf3e51104d
等级：SOURCE。分类：Row/Box组合，不是官方ButtonGroup直接调用。
单选selectedIndex；等宽、间隔4dp；默认高48dp、Compact高40dp。颜色tween250ms；勾选alpha200ms。选中CircleShape、未选中10dp圆角，未声明连续路径插值。不是ExpressivePlayerControlGroup，不混用权重联动参数。

### RH-05 常规页签
路径：common/TabAnimation.kt
blob：2aba553793f4c88ec8a9de6483f6daee3a80e04a
等级：SOURCE。分类：项目自定义页签，组合Compose基础能力。
选中scale动画1→1.05→1；每段tween250ms/FastOutSlowInEasing，顺序两段名义合计500ms（未被中断时），不是往返总共250ms。graphicsLayer只写scaleX，没有写scaleY。仅相邻未选中项translationX为±12f后归0，每段250ms；graphicsLayer该值为像素，不写成12dp。非相邻偏移和失选scale立即snap。背景/内容颜色200ms。外padding2dp，height44dp，水平padding16dp。LIGHT/HEAVY触觉请求、Role.Tab和selected语义；真实触觉与读屏未验证。

### RH-06 紧凑页签
路径：common/SmallTabAnimation.kt
blob：b19823e75bfce491a99d9c6f12eef037159388a5
等级：SOURCE。分类：项目自定义页签。
scaleX、相邻translationX、两段时序、颜色与触觉语义同RH-05。外padding5dp，本函数没有RH-05的固定44dp高与内部16dp留白，不得套用相同尺寸表。title入参未在函数体直接使用，内容由content提供。

### RH-07 骨架渐变
路径：common/ShimmerBox.kt
blob：0f55d33f8e8ee0606204f0fc66251b4d568516db
等级：SOURCE。分类：项目自定义渐变动画。
默认surfaceVariant透明度0.6/0.2/0.6。animateFloat 0→1000f；tween1200ms、delay300ms，infiniteRepeatable。Brush.linearGradient起点Offset.Zero，终点(x=值,y=值)沿对角变化，不是明确写了横向平移；原报告1000ms/横向描述需修正。未显式给easing，不填猜测常量；数字坐标不写为dp。

### RH-08 逐字符数字滚动
路径：common/DigitTickerText.kt
blob：d149dc0dd8b51a47b8a529b75cfa7338427237da
等级：SOURCE。符号：AnimatedDigitTickerText。
按索引逐字符AnimatedContent。前后均为数字时，按字符大小判断方向，上下半个内容高度滑入/滑出并淡入淡出；非数字变化只淡入淡出。不按整个整数大小决定方向，如9→0按字符比较处理。当前实现没有显式600ms规格，不能把报告别处的计数动画参数套来。关闭font padding，行高底部对齐/trim both，softWrap=false。位数变化、快速中断与真实排版未测。

## 版本与原报告限制

版本目录gradle/libs.versions.toml blob 33bd54b0325762dc04dcfc40ad2866c0aff08f6c声明Material3 1.5.0-beta01；不是本轮依赖解析或APK字节证明。笔记项目声明beta02，迁移先核对接口，不擅自升级依赖。
原REPORT.md分析fdroid APK并对照main；本索引只对上述固定Release源码负责，尚未证明fdroid与stable二进制相同。
MotionScheme影响实际使用其规格的组件，不保证覆盖全部自定义动画；70%高级感不是测量结果。
报告未核验项目：ExpressivePlayerControlGroup、其他播放器组合、歌词catch-up、弧形/曲奇slider、页面调用链。目录存在不能升级为实现/运行确认。

## 下一步与维护

先补调用位置，再选原生独立样板；不修改笔记生产UI、不操作真机。网页重建保留WEB-REBUILD标记，不能反向充当NATIVE-TEST。
新增组件继续使用RH编号，记录固定SHA/路径/blob/参数单位/覆盖范围/未知项；修订只追加版本，不改写历史原件。
本索引经Git提交后，以分支docs/material-context-1的返回提交SHA作为后续对话入口；本地文件生成不等于远端已入库。

## 增补：研究入口与第 06 批修订（2026-11-13）

上面八条（RH-01～RH-08）与全部历史说明**保持原样**，本节只追加，不改写任何一条既有结论。

### 研究入口

| 路径 | 用途 |
|---|---|
| `docs/design/research/README.md` | 批次索引、证据等级、三条边界（未运行 / 二进制不可逐项对齐 / 原型不是证据） |
| `docs/design/research/records-02-05.md` | 第 02～05 批的产物与数字，以及"已归档 / 只有对话摘要 / 未取得"三张账 |
| `docs/design/research/rhythm-list-items-06.md` | RH-09：播放列表行（`PlaylistSongItem`）的定点核验 |
| `docs/design/research/SHA256SUMS` | **只覆盖 `research/` 目录**；同目录的 `docs/design/SHA256SUMS` 仍只覆盖两份历史原件，未被扩写为全库清单 |

### RH-09 摘要（完整参数见研究文档）

对象：`features/local/presentation/screens/PlaylistDetailScreen.kt`（166,578 B / 3,114 行 / blob `a141fe5c304568097b4fbc55740070d63eba4886`），`PlaylistSongItem` 声明于 2753 行。
等级：SOURCE；**无 NATIVE-TEST**。

- 行缩放三态：拖动 **1.005**、排序或选择态 **0.98**、常态 **1**；`spring(MediumBouncy, StiffnessMedium)`；`graphicsLayer` 同时写 scaleX/scaleY（2865–2896）。
- 行容器：外 8dp/2dp、内 16dp/10dp；分组圆角 24/6dp；tonalElevation 拖动 0 / 排序 1 / 其余 2，shadowElevation 恒 0（2875–2901、3097–3113）。
- 封面 56dp + `SONG_ART` 表达形状（兜底 12dp）；播放角标 20dp、3 条 12×10dp。
- 尾部三态：排序 36dp 手柄（12dp 圆角）／多选 40dp 移除按钮（errorContainer α0.8）／常态 32×44dp 更多按钮（HEAVY 触觉）。

### 第 06 批的三条更正与两条边界

1. **1.025 作废。** 先前互动网页里的"按下抬升 1.025"是 WEB-REBUILD 的观感值，不是源码值；现存三份原型 HTML 中检索 `1.025` 为 0 命中，无法追溯，故不得引用。行缩放以 1.005 / 0.98 / 1 为准。
2. **`PlaylistDetailScreen.kt` 未通读。** 只核验 2753–3114 行与若干命中行；1–2752 行的页头、筛选、排序菜单逻辑不在结论范围内，不得据本文推断。
3. **容器不是 `DragDropLazyColumn`。** 该页用裸 `LazyColumn` + `ReorderableItem`（1455/1673、2084/2356）；`DragDropLazyColumn`（161 行，已完整读取）只被四个 bottom sheet 使用（EQ 预设排序、首页分区排序、库页签排序、流媒体分区排序）。
4. 边界一：仍未运行原生，声明、接线、实际观感三者不得合并。
5. 边界二：`RhythmGroupedMenu.kt`（149 行，已完整读取）的圆角是 16/4dp，与列表项的 24/6dp 是两套常量，不能互相套用。


