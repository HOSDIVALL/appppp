# RHYTHM-KB-HARDEN-01 · 从演示资产走向结构化源码事实

## 已执行

固定上游6f26f963b82ed592ec28f05824bb7f12b65b82ea。本轮完整重读RhythmPlayerSheet.kt、VolumeSlider.kt、CanvasArtworkPlayer.kt，核对app/build.gradle.kts和gradle/libs.versions.toml。前3文件的blob及事实在source-delta.json。
数据库基线c4aae793310c7567fe3c8371d6fa7a069b589b15（1553快照）。旧数据仍把这三份source记录为pending，而v11.1仅是已归档HTML。研究记录落后于已读源码，因此本批不增加演示页，先补事实台账。

## 依赖结论

app使用libs.androidx.material3.android，其目录声明material3-android=1.5.0-beta01；Compose BOM=2026.09.00，animation-core=1.12.1，Media3=1.11.1，reorderable=3.1.0。
这里只确认声明和alias。Gradle实际解析版本、运行时classpath、二进制工件与源码匹配、目标项目API兼容和编译仍未确认，不能把这组版本当作迁移升级要求。
app build文件只归档必要依赖映射和blob；不复制配置/令牌/签名相关内容。

## 源码记录变化提案

三份pending→complete，native_validation仍not_run。已有MiniPlayer选择壳RUI-026不重复新增。
新增RUI-054容器机制、RUI-055音量绘图、RUI-056封面视频机制，共65项事实。53→56条机制、240→305项事实是应用增量后的预期，不是当前远端库已变化。
source文件仍97条；96条inventory中文件读取记录预计32完整/3部分/61待完成，另外feature定点1条。原资产12条和正文保持原样。

## 仍不能下的结论

PlayerScreen.kt仅核对到目录元数据：48,413字节，blob af4fb30cb9656af1f94a29e32e3ddeb63e61f4bc；ExpressivePlayerScreen.kt 169,047字节，28c25341c6d8ef788583c6a8edbcf7c6a9ba28d9；MaterialPlayerScreen.kt 242,584字节，3d3f6ff32fed5acf023e49a9f676ccd6507fe2fa。
未通读三者，不能把Sheet参数转发误作VolumeSlider真实调用位置已核验，更不宣布大型MiniPlayer/PlayerScreen链已闭环。
回调抛错后drag清零、TextureView重绑和fallback更新均保留条件性风险，不写成产品故障复现。

## 产品试点

新增列表状态/重排候选路线图；它只是规格和前置问题，不是App开发已启动。详见pilot-list-state.md。
下一步应先取得当前App固定实现基线和列表/写入入口，再确定最小迁移任务。不要把Rhythm的全部播放器/专有功能搬入轻量笔记项目。

## 发布与验证

本批没有新HTML、没有运行Rhythm、没有App/设备/浏览器/XML测试。现有模型与原生证据边界不变。
源读取、版本声明和待入库增量是实际产物；只有执行方更新SQLite/JSON、push并远端回读后才可称数据库已更新。
