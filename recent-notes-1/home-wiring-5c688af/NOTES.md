# 首页接线候选：在 `5c688af` 上的验证记录

**性质**：只读验证 + 隔离工作树实验。未提交源码，未推送特性分支，未动 `main`，未安装 APK，未操作真机。
**被验证基线**：`ux/rhythm-recent-notes-1` @ `5c688af4d30f01f8939319cad47b2393a8ebaa99`
（`git fetch` 后 `FETCH_HEAD` 逐字符比对，等于该提交）
**验证脚本**：`harness/verify_home_candidate.sh`（原样执行，未改一行）
**接线脚本**：`harness/apply_home_wiring.py`（原样执行，未改一行）

## 1 输入与校验

| 输入 | 期望 | 实测 |
|---|---|---|
| `batch1.diff`（从证据分支 `a818854` 抽出） | `4f781f4aeade7a86f13df751f1323e8f0d28910671cf2622d22a4550bd280b41` | 一致，`sha256sum -c` → `OK` |
| `sherpa-onnx-2.0.1.aar`（真实依赖，非占位） | `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7` | 一致，`sha256sum -c` → `OK` |

## 2 阶段结果

| 阶段 | 结果 | 说明 |
|---|---|---|
| `baseline` | 通过 | 远端仍指向 `5c688af`，工作树 `git worktree add --detach` 于证据目录内 |
| `batch1` | 通过 | 8 文件 `git apply --check --verbose` 全 cleanly，随后应用成功 |
| `wiring` | 通过 | 脚本 exit 0；`MainActivity.kt +26`、`NotesListScreen.kt` 243 行变动（−154/+115） |
| `dependency` | 通过 | AAR 摘要 OK，装入 `app/libs/sherpa-onnx-2.0.1.aar` |
| `android_build_and_tests` | **失败 exit 1** | `:app:compileDebugUnitTestKotlin` 编译错误 |
| `test_evidence` | 未执行 | 被上一阶段阻断 |

`status.txt` 原文：`android_build_and_tests: exit 1`

## 3 首个错误

```text
> Task :app:compileDebugUnitTestKotlin FAILED

e: <evidence>/worktree/app/src/test/java/com/example/myapplicationfirstapp/ui/NotesListScrollbarMathTest.kt:19:48
   Unresolved reference 'ScrollbarEstimate'.
```

`run.log` 中 `e:` 共 **101 条，全部集中在该单一文件**（`ScrollbarEstimate`、`prefixDistancePx`、`targetFor`、`progressOf`、`estimatedTotalPx` 均未解析）。

**归因证据**：`candidate.diff` 覆盖 9 个文件，**不含** `NotesListScrollbarMathTest.kt`（`grep -c` = 0）。该文件在主源集里对应的 `ScrollbarEstimate` 引用数为 0，测试侧 62 处，属基线遗留：v4 滚动条重写删除该类后测试未同步。

## 4 候选补丁（`candidate.diff`）

- 57,616 B / 1,316 行，由 `git diff --binary HEAD -- app/src` 生成（`git add -N` 纳入新增文件）
- 覆盖：

```text
app/src/androidTest/.../data/IsolatedNoteStorage.kt        （第一批）
app/src/androidTest/.../data/RecentNotesStoreTest.kt       （第一批，新增）
app/src/androidTest/.../ui/NotesStateRecentNotesTest.kt    （第一批，新增）
app/src/main/.../MainActivity.kt                           （第一批 + 接线）
app/src/main/.../data/RecentNoteUsage.kt                   （第一批）
app/src/main/.../data/RecentNotesStore.kt                  （第一批）
app/src/main/.../ui/NotesListScreen.kt                     （接线，243 行）
app/src/main/.../ui/NotesState.kt                          （第一批）
app/src/test/.../data/RecentNoteUsageTest.kt               （第一批）
```

- 已自校验：`git apply --reverse --check candidate.diff` 通过（可逆、无空白损坏）

## 5 第二处阻塞（即使编译修好也会触发）

`test_evidence` 断言两组用例数为 24 与 7，实际：

| 类 | 脚本期望 | 源码中 `@Test` | 结论 |
|---|---|---|---|
| `NotesHomeListModelTest` | 24 | 24 | 吻合 |
| `RecentNoteUsageTest` | 7 | 11 | **不符** |

11 = 基线已有的 7 条 + `batch1.diff` 新增 4 条（`displayKeepsOpenOrderNotUpdatedAtOrder`、`displayExcludesTrashedAndUnknownNotes`、`displayAppliesLimitAfterFiltering`、`displayOfEmptyUsageIsEmpty`）。断言是集合全等，故会以 `11 ≠ 7` 失败。

## 6 本轮没有做的事

- 未改测试代码、未排除任务、未删文件、未临时移出源集、未改脚本参数与流程
- 未产生 APK：同一次调用里 `assembleDebug` 与 `testDebugUnitTest` 并行，编译失败使打包未完成，`app/build/outputs/apk/` 为空
- 未产生 `test-results/`：零条用例真实执行，因此**本轮没有任何测试计数可引用**
- 唯一补充的环境变量：`JAVA_HOME`、`ANDROID_HOME`（隔离工作树无 `local.properties`，脚本不负责生成）

## 7 如何复核

```bash
cd <evidence>/worktree            # detached 5c688af，脏，含两批改动
git status --short
git apply --reverse --check ../candidate.diff
grep -c ScrollbarEstimate app/src/test/java/com/example/myapplicationfirstapp/ui/NotesListScrollbarMathTest.kt   # 62
grep -rc ScrollbarEstimate app/src/main --include=*.kt | grep -v ':0' || echo "主源集 0 处"
```

## 8 请裁决

1. `NotesListScrollbarMathTest.kt`（21 个 `@Test`，引用已删除的 `ScrollbarEstimate`）如何处置：重写 / 删除 / 暂不纳入单测源集。
2. `RecentNoteUsageTest` 的门槛：改为 11，还是把 7 明确指向「基线那 7 条」。
3. 是否需要在本轮之后再补一次 `assembleDebug` 单跑，以证明接线后的主源集独立可打包（上一轮在 `5c688af` 单跑为 57,469,448 B / `sha256 d85ae6f7…686719d`，本轮因编译失败未复现）。
