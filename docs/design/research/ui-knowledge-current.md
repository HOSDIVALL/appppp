# Rhythm UI知识库 · 当前恢复入口

当前数据快照：20261113-1553。基于已归档1213快照，新增用户提供的v11.1完整HTML资产；本地制作不等于已推送，发布状态以新manifest及真实Git对象为准。

## 当前恢复文件

- SQLite：snapshots/20261113-1553/Rhythm-UI-Knowledge-20261113.sqlite3
- 完整JSON：snapshots/20261113-1553/Rhythm-UI-Knowledge-20261113.json
- v11.1原文件：snapshots/20261113-1553/originals/Rhythm-Player-Container-Control-Lab-v11.1.html
- 当前校验与发布状态：snapshots/20261113-1553/manifest.json

## 本次入库范围

assets由11条变12条，12条均带正文，missing_assets=0。正文是v2-v9及v11.1九份HTML，加三份历史参考文档。v10与v10.1仍独立归档，不在该资产计数中；没有把所有历史网页都算作数据库已嵌入。
97条源码记录、53条组件/机制、240项事实、35个形状ID、7个默认目标不变。本次只新增HTML原字节和存储metadata，不改组件结论、事实或测试记录，不把网页文字自动提升为已核验源码证据。
v11.1被登记为用户提供的源码重建实验修订，不冒称原v11的字节级补丁。本次没有执行其JavaScript或重新运行浏览器，没有新增原生、设备、读屏或视频解码验收。

## 历史与状态口径

1136与1213快照、两份1136登记及research/SHA256SUMS保持不变。新快照用自己的manifest，不扩大旧校验表的覆盖范围。
v7-v9正文继续保留在数据库及JSON中，其独立文件仍在1213/originals；原始测试JSON仍未补回。
文件内prepared及部分archive_state是生成时/历史字段；当前远端存在性以仓库manifest为准，不为改这些字段而破坏原件哈希。ZIP未归档。
旧整合草稿只保留为输入证据，不复活已作废的综合站。

## 独立网页精修：v10.1

- 原页保留：ui-refinements/v10.1/original/Rhythm-Reading-Layout-Lab-v10.html
- 精修网页源码：ui-refinements/v10.1/Rhythm-Reading-Layout-Lab-v10.1.html
- 修改说明、浏览器检查JSON与MANIFEST：同目录。

这是用户上传v10的Web UI精修，不是新增Rhythm源码完整核验，也不是Android App改版。包内记录37项选定浏览器检查，其中包含87组机制输出对照；本次入库不等于重新执行这些检查。原生/设备验收未开展。
该批仍为独立归档，尚未嵌入1553 SQLite/JSON；其原文件与证据未修改。
