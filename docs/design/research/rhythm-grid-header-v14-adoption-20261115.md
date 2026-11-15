# Rhythm v14 研究归档与采用评估

日期：2026-11-15。性质：事实性研究摘要与采用建议，不是实施或原生验收报告。

## 归档范围

记录对话中截至 v14 的已解析规则、修正、证据身份与采用评估。不是原始 HTML、CSV、测试记录和全部历史产物的完整归档。本提交不包含网页附件本体；不得表述为“所有原件均已入库”。不改产品代码，不改 App 数据库，不合并 main、不建 PR、不操作设备。BACKUP-SAFE-1B 继续暂停。

## 基线与证据

Rhythm 固定 commit：6f26f963b82ed592ec28f05824bb7f12b65b82ea。
- SongInfoBottomSheet.kt blob：8d16f1ce4f6ac6cbb6fef69d6e5916e4cddba895。定点片段读取，不是全文通读。
- Material3SettingsGroup.kt blob：0c8f5cf209eba10cd9bb806d298686e7a0c624be。此前全文可见。
- MarqueeText.kt blob：7b79cf1ac9fec13d345c72588910059aa818f550。固定版全文读取。
- CollapsibleHeader.kt blob：734d6cb73c80fd73cade3b741b91cd7da8808d04。通用函数完整可见，其他部分不算通读。
- 证据分层沿用 SOURCE / REPORT / WEB-REBUILD / NATIVE-TEST。HTML 原型不是参数来源；本轮没有 NATIVE-TEST。

## 连接式信息表格

MetadataItem：label、value、icon、isWide=false。
处理顺序：先过滤，再统计普通项，再决定通栏，再算行列与圆角。
过滤条件：value.isNotBlank() 且不忽略大小写地等于 Unknown（这里的“不”指排除；实际比较为 !value.equals("Unknown", ignoreCase=true)）。比较前不 trim，故带空格的 Unknown 不会被该等值判断排除；0、null 字符串也不因此排除。
regularCount=过滤后 !isWide 的数量；lastRegularIndex=过滤后最后一个 !isWide 的下标。
isFullWidth(i)=isWide || (i==lastRegularIndex && regularCount%2==1)。最后一个普通项不一定是整张表最后一项。
普通项按两列推进；通栏遇未满行先换行，col=2 表示通栏形状分支，不是第三列；最后按有无未满行计算 totalRows。
connectedGridItemShape：外角24dp，连接角8dp，按首/末行及左/右/通栏决定。角顺序 TL/TR/BR/BL：首行左24/8/8/8，首行右8/24/8/8，末行左8/8/8/24，末行右8/8/24/8，中间8/8/8/8；单行通栏四角24。单行左右格只在各自外侧取24。
InfoGridItem 标签为 titleSmall、tint、Bold；值为 MarqueeText，bodyLarge.copy(fontSize=18.sp)。完整背景、padding、最小高度与调用侧蒙版参数尚未闭合，网页的4px网格间隙、16px内边距不能作为上游事实。

## 普通分组设置项

外角24dp，连接角6dp，项间4dp；单项四角24，首项上24下6，中间全6，末项上6下24。lastItemShape 优先于 itemShape，再使用默认分组形状。
Card 默认 elevation=0dp。内部 Row 水平21dp、垂直16dp；图标容器40dp、图标24dp、后间隔16dp；描述间隔2dp。按压缩放内部 Row 至0.97，不缩外层 Card。缩放为 MediumBouncy / StiffnessMedium；图标底色按压目标 alpha=.86。Row clickable 的 indication=null。禁用及 onClick=null 的行为须保留。

## 数值与跑马灯

formatFileSize 连续除1024，按 GB/MB/KB>=1 判断，前三者 Locale.ROOT 两位小数，否则 bytes B。阈值在舍入前判断，1048575字节可显示1024.00 KB。网页 JS toFixed 极端舍入未与 JVM 全范围对照。
MarqueeText→AutoScrollingTextOnDemand→AutoScrollingText→basicMarquee。局部 enabled 与全局 isMarqueeActive 共同控制；先检测溢出，后续再比较无宽限制测量结果和可用宽度。默认延迟1500ms、速度25dp/s、渐隐宽24dp、文本间隔30dp、左侧渐隐状态颜色动画500ms、Offscreen + DstIn。gradientEdgeColor 的调用侧 alpha 尚待核对。
同文件 rhythmMarquee 扩展默认1200ms/30dp/s，不能混入上述入口。优先用于单行录音名、路径等辅助信息，不用于多行笔记正文或转写段落。

## 顶部、正文与轮播

项目通用头：LargeTopAppBar + exitUntilCollapsedScrollBehavior；字号32−8c sp、图标56−8c dp；撤回旧说“折叠图标28dp”。标题显示设置的0.5判断、Material语义切换、吸附端点判断是不同机制。
顶部留白10dp，底边渐隐24dp，alpha序列1/.72/.32/0。contentWindowInsets=0不能单独证明整个页面画入系统状态栏。
正文延迟50ms，alpha400ms，translationY从30px至0、450ms；只作用于正文，不是顶栏。内容顶部padding按测得头高和heightOffset动态计算。
AndroidX参考blob 36e68661f0a2d5bc585b72176ca7ebce356ee241 可见底部alpha=1−c、顶部CubicBezier(.8,0,.8,.15)。beta01版本提升锚点AppBar.kt blob为7c7677bfad3c9c6bcc3215e9de8b8942f11a699b；文件不同不证明曲线不同。发布构件绑定及完整惯性/吸附尚未完成，参考半折叠顶部alpha约.04567不是已绑定原版值。
轮播已读参数包括4500ms前进片段、45000ms推荐集合刷新、MultiBrowse首选宽度280/360dp与16dp间隙、Uncontained屏宽项与0间隙。设备分支对应与完整调用链未闭合，不作为首页整页替换依据。系统栏图标明暗与应用标题是两层，不画假状态栏。

## 用户软件源码评估

只读评估基线 main=61dff28e4041aa5a8d9376ba1da872a87a009301。其他 ux/* 分支和用户已安装版本未逐个核对，不能称为当前安装状态审计。
- SettingsScreen.kt blob 13d0dc97fd248fc9db8228cfac205bdd7787e2d5：完整读取。当前 SettingsRow 包装透明 ListItem，外观/数据/关于分组及各回调已存在。优先替换分组容器、形状、内边距与按压层，保留主题/语言/字体/回收站/导入导出回调和返回逻辑。备份功能只换入口外观，不改暂停中的业务。
- NotesListScreen.kt blob c869941c29eae73b1bc08625f5b9a7590a8abcf5：部分读取，后段截断。已用 LargeTopAppBar、exitUntilCollapsed、116dp expandedHeight，存在搜索、筛选、置顶分组、滑删、官方滚动条。不要整页替换。候选是列表形状与分组边界，置顶/普通组分别重算首尾；SwipeToDismiss 背景和前景同形状，保留key、滑删35%阈值、撤销及回调。待编译测试后再采纳动效。
- RecordingDetailScreen.kt blob 1f5f65c12aacba6ac61b38b00a52a8079b360039：完整读取。现有标题/时长/sizeText、转写列表和底部播放器。可选新增录音信息表，使用真实已有字段；不能伪造采样率、位深或声道。保留播放/seek/转写高亮/自动跟随/倍速/循环/删除。正文与转写不应用单行marquee。
推荐顺序：设置分组→笔记列表形状→录音详情辅助信息表→必要的单行长文本→顶栏发布版本与手势核实后局部试验。轮播不是当前必须项。编辑器保存、录音/转写引擎、播放器状态、数据实体与迁移不在此次替换范围。

## 许可与验收

上游Rhythm标注GPL-3.0-or-later。采用事实性参数与直接移植代码不是一回事；项目分发方案未确认前不粘贴整段GPL实现。实际分发修改/组合代码须核对适用许可义务，不能把“重写”当作自动免责。
实施前须指定工作分支和基线，按流程批准写入。验收至少覆盖深浅主题、中文/英文、字体放大、空/单/奇偶/通栏、搜索过滤、滚动与滑删冲突、长文本开关、无障碍及回调回归。源码阅读、网页语法通过、浏览器运行和原生验收分开记账；不声称已编译或原生100%一致。
