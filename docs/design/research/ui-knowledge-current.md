# Rhythm UI知识库 · 当前恢复入口

当前数据快照：20261113-1213；基于30d66c5d82daa28d5a39471d221f58aff1ab08b8的历史1136快照补回v7/v8/v9真实HTML。

## 恢复文件

- SQLite：snapshots/20261113-1213/Rhythm-UI-Knowledge-20261113.sqlite3
- 完整JSON：snapshots/20261113-1213/Rhythm-UI-Knowledge-20261113.json
- 原件：snapshots/20261113-1213/originals/ 下三份HTML
- 当前校验与发布状态：snapshots/20261113-1213/manifest.json

本文件与上述文件实际入仓并核验后，才算远端补档完成；本地准备不等于上传。

## 本次状态变化

assets仍11条；有正文由8条变11条；missing_assets为0。11份正文为v2-v9八份HTML与三份参考文档。
97条源码记录、53条组件/机制记录、240项事实、35形状ID、7默认目标不变。仅改变资产恢复内容及快照metadata，不重写研究结论。
新补的三份HTML在独立originals目录中，并同时嵌入SQLite BLOB和JSON base64，三种载体逐字节一致。

## 历史与边界

1136目录及旧登记/manifest保留为历史时点，旧missing状态不是当前快照状态。当前请先读本入口，再读1213 manifest，不回写历史快照。
数据库/JSON内的制作时prepared标记及部分record_archive_states属于历史生成字段；当前远端状态以本目录manifest及真实Git对象为准。
旧整合草稿只作为输入原件保留，不复活已作废的综合站。
v7-v9原件齐全不表示原始测试JSON已经补齐。原始测试JSON仍不可得；未重跑浏览器、未构建、未做Rhythm原生或设备验收。
ZIP未归档。research/SHA256SUMS仍是其历史覆盖范围，不扩称全库校验表；新快照按自己的manifest校验。

## 独立网页精修：v10.1

- 原页保留：ui-refinements/v10.1/original/Rhythm-Reading-Layout-Lab-v10.html
- 精修网页源码：ui-refinements/v10.1/Rhythm-Reading-Layout-Lab-v10.1.html
- 修改说明、浏览器检查JSON与MANIFEST：同目录。

这是用户上传v10的Web UI精修，不是新增Rhythm源码完整核验，也不是Android App改版。包内记录37项选定浏览器检查，其中包含87组机制输出对照；本次入库不等于重新执行这些检查。原生/设备验收未开展。
这些文件独立归档，尚未嵌入1213 SQLite/JSON；现有数据库统计、1136/1213历史快照和校验范围不因此改变。包内repository_written等标记保留制作时值，远端存在性以本次Git提交为准。ZIP未归档。
