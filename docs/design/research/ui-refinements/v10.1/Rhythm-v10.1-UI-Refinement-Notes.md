# Rhythm v10.1 · 原报告网页UI精修

## 输入与范围

输入：用户上传的Rhythm-Reading-Layout-Lab-v10.html，23,659字节。
输入SHA-256：e142b2ee147a450a91038c6c8bfaa866dfc00043bb01c8f75194b6a6930cc10f。
本次是这份原HTML的编辑，不是重新制作四个简化Demo；原四专题、来源表、提示与43个原ID均保留。未追加Rhythm源码通读或调用链核验。

## 可见改动

1. 增加顶部专题导航、证据范围折叠区、清晰的状态/参数层级；保留全部原说明。
2. Range增加数值，Header增加0/.5/1预设与阶段反馈；Both模式标明图标策略不参与，showAppIcon继续作为未接线参数展示。
3. Marquee增加静态未遮罩原文对照、alpha快捷控制、Web暂停和溢出/启用/等待状态。透明遮罩导致内容消失时仍能看到原文，不把原报告的实验改成“自动修复”。
4. Modifier路径的alpha控件标为不适用；直接路径绕过全局/enable的原分支保持不变。
5. EdgeBlend增加当前位置、步进和鼠标拖动；越过Web拖动阈值后不误触条目。触屏仍用浏览器原有横向滚动。未改变smoothstep与state优先级。
6. Tooltip增加清楚标记的定位参照与长文本排布，仍手动控制可见；没有添加SDK自动避边、超时、hover或长按阈值。布局参照为Web LTR，不宣称RTL核验。
7. 加入减动效偏好：默认暂停Web位移；这是网页体验选择，不是上游全局设置。
8. 不活跃/暂停状态不重复绘制Canvas；活跃时按约30FPS绘制，Web虚拟时钟与真实原生basicMarquee不等价。进入动画和CSS过渡仍标为近似。

## 特意没有改变的内容

- CollapsibleHeader的fraction显隐条件、尺寸公式和初始收起非永久锁定。
- 各Marquee路径enabled/global/respect判定、延迟/速度配置。
- renderMarquee函数本体（包含两次DstIn数学）与原版逐字一致。新增时钟暂停与绘制调度在函数外。
- EdgeBlend平滑函数、渐变stop、宽度与softness关系、state空合并优先级。
- Tooltip的8种arrowPosition首选映射、箭头形状与原有来源范围。
- Header整文件部分读取、外部调用链/SDK/原生待证的边界。

## 实际检查

无头Chromium：1440x1080桌面；390x844、360x844移动仿真；另检查prefers-reduced-motion。
原页基线桌面/390px加载未捕获JS错误，也未发现文档横向溢出；因此本轮不把体验改进包装成“修复原页严重故障”。
精修版37项选定检查通过，其中4项机制对照包含87组输出：Header36、启用逻辑32、渐变11、Tooltip映射8。数量不能相加成组件完成数。
覆盖透明清空/alpha1、暂停像素与绘制计数、编辑、步进、鼠标拖动不误点、边界状态、长提示、深色、减动效与窄屏。另做JS语法、原ID保留与原文件未变检查。
没有Android/Compose/设备/读屏验收，没有全部浏览器性能或像素级视觉保证。临时截图只作检查过程资料，未当原生证据。

## 保存状态

本次只生成原版保留副本、v10.1、检查JSON和包内manifest。没有写入GitHub或更新远端SQLite/JSON，也没有修改笔记App。
原版与新版本各自保留，不覆盖已归档材料；后续若归档，应新增研究修订记录并使用实际文件哈希。
GPL-3.0-or-later及原作者信息保持在HTML中。
