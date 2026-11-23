# scope-build-v2 专用模拟器验收（scope-accept-v2）

时间：2026-11-23 19:50–20:10 CST
设备：`emulator-5558` / AVD `NotesGO_V2_Dedicated` / Android 17 / API 37 / x86_64
被测包：`com.example.myapplicationfirstapp` versionName 2.0 / versionCode 2

## 0. 身份复核（01-device-identity.txt）

```text
get-state                     device
emu avd name                  NotesGO_V2_Dedicated + OK
versionName / versionCode     2.0 / 2
firstInstallTime = lastUpdateTime = 2026-11-23 11:38:42 (UTC)
installed base.apk pulled     57,485,832 B
  sha256 440e6c48bc1c3c20b7219bf283f3c3a4d334b66bc267141cd88b2a1207c3ae29
  == scope-build-v2 的 app_sha256（manifest 同值）
```

安装时间与哈希都指向上一轮那只 `scope-build-v2` 包，本轮未重装、未替换。

## 1. 仪表测试（02-instrument.txt / 02b-instrument-summary.txt）

`am instrument -w -r` 直接跑，未走 Gradle：

```text
RecentNotesStoreTest        11 通过
NotesStateRecentNotesTest   11 通过
合计                        22 通过 / 0 失败 / 0 错误 / 0 跳过
Time: 5.207    OK (22 tests)    INSTRUMENTATION_CODE: -1
```

## 2. 顶部带轮播时的限位（14-top-carousel-scope.png）

轮播、摘要、搜索、筛选行可见时（uiautomator 实测边界：轮播 331–1271、摘要 1308–1360、
搜索 1423–1570、筛选 1570–1696、首条笔记 1806–2046、底部导航 2153–2289）：

```text
滚动条手柄 1795–1920，轨道 1931–2095
```

手柄上端 1795 比首条笔记上沿 1806 高 11px（圆角端帽），比轮播下沿 1271 低 495px；
轨道只存在于笔记行区间，未压轮播、搜索、筛选，也未进入底部导航。**通过。**

## 3. 拖动防跳动与首末端落点（15…18，19-scrollbar-geometry.txt）

连续拖动（一次 4s 手势，四帧采样，同一次按压内）：

```text
帧   e1      e2      e3      e4      hold
手柄 1889    1553    1215    1025    790
Δ          -336    -338    -190    -235
```

严格单调、无回跳、无空档；轮播在 e2–e3 之间滚出视口，手柄没有跳变。**通过。**

- 拖到最顶：手柄停 1795，恰好停在第一条实际笔记上沿（1806），列表回到顶部，未跳成覆盖轮播或分区标题。**通过。**
- 拖到最底：最后一条实际笔记为 `Dune 沙丘 04`（卡片底约 1800），轨道与手柄共同止于 1769，
  下方 120dp 留白与导航栏（2153）都没有被伸进去。**通过。**

## 4. 气泡首字：与手柄正对行不一致（16-drag-bubble.png）

按住手柄出现圆形气泡，字符 `R`。该帧视口第一条可见笔记是 `River 河流 18`（R），
而手柄正对的一行是 `Prairie 草原 16`（P）。也就是说气泡取的是**当前视口第一条笔记的首字**，
不是手柄指向的那一条。**待确认口径**：若约定是"手柄正对行"，此项不通过；若约定是"滚动锚点行"，则通过。

## 5. 无可滚动时隐藏（09/10/11）

| 状态 | 结果 |
|---|---|
| 搜索 `Tundra` → 匹配 1 条 | 滚动条不出现（x=1055–1070 无轨道/手柄像素） |
| 再输入 `XYZ` → 匹配 0 条 | 空态，滚动条不出现 |
| 点 × 清空 → 共 20 条 | 列表恢复；滚动时滚动条回归 |

**通过。**

## 6. 崩溃检查（13-crash.txt）

`logcat -b crash -d` 输出 0 行（本轮开始前已 `-c` 清空）。无本应用崩溃。**通过。**

## 7. 观察项：冷启动轮播仍在视口上方（05-relaunch-carousel.png）

杀掉进程后重新启动，首屏直接显示「共 20 条」与列表，轮播不在视口内；
手动下滑一次后轮播（`Cedar 雪松 03`）才出现。与上一轮模拟器人工验收记录的缺陷一致，
本轮未修（只做验收，未改 Kotlin）。这一项不影响上面第 2 条的限位判定，但会影响"首屏就能看到轮播"的预期。

## 造数说明

20 条笔记由 `files/notes.json` 直接写入（标题 `Aurora…Tundra` + 中英数字，置顶前 3 条），
随后在界面上点开 4 条（Cedar/Bamboo/Aurora/Tundra）生成最近使用；
`databases/recent_notes.db` 仅记录打开时间，未手工写入。造数前后 `notes.json` 7033 B，
sha256 `4c479d39…86ef20`。
