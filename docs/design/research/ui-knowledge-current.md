# Rhythm UI知识库 · 当前恢复入口

当前快照：20261113-harden01；发布状态以对应manifest与实际Git对象为准。

## 当前文件

- SQLite：snapshots/20261113-harden01/Rhythm-UI-Knowledge-20261113.sqlite3
- 完整JSON：同目录Rhythm-UI-Knowledge-20261113.json
- 来源增量、研究结论、迁移候选：source-delta.json / findings.md / pilot-list-state.md
- 当前校验与发布状态：同目录manifest.json

## 本轮变化

三份source由pending升为完整静态读取；机制53→56、事实240→305，路线图7→8。97条source记录不增加；96文件范围内读取32完整/3部分/61待完成，另有feature定点1条。完整读取不等于调用链/SDK/原生通过。
资产仍12条，原正文、校验值与历史验证记录完全不变；没有新增网页或提高测试等级。列表/录音重排只记候选，App基线未选定，尚未实施。

## 历史与边界

保留1136/1213/1553全部历史快照和旧校验范围。v11.1独立原文件仍在1553/originals；v7-v9原文件仍在1213/originals。ZIP未归档。依赖只核到声明，实际解析/二进制/构建未核。旧生成字段不是实时发布状态；原生/浏览器/App测试未新增。

## 独立网页精修：v10.1

- 原页保留：ui-refinements/v10.1/original/Rhythm-Reading-Layout-Lab-v10.html
- 精修网页源码：ui-refinements/v10.1/Rhythm-Reading-Layout-Lab-v10.1.html
- 修改说明、浏览器检查JSON与MANIFEST：同目录。

这是用户上传v10的Web UI精修，不是新增Rhythm源码完整核验，也不是Android App改版。包内记录37项选定浏览器检查，其中包含87组机制输出对照；本次入库不等于重新执行这些检查。原生/设备验收未开展。
该批仍为独立归档，尚未嵌入1553 SQLite/JSON；其原文件与证据未修改。
