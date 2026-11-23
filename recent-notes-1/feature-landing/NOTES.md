# 验收通过的源码落盘：`eafd31e` → `ux/rhythm-recent-notes-1`

## 本轮做了什么

把 `D:/RECENT-NOTES-1-scope-v2` 里已通过验收的那份工作树（detached 在 `5c688af4`）只把
`app/src/` 提交成 `eafd31e`，并快进推送到特性分支 `ux/rhythm-recent-notes-1`。
入库内容与「73 单测全绿 + 22 仪表全通过 + 5558 安装验收」所用的工作树逐字一致
（提交后 `git diff HEAD -- app/src` 为 0 行）。

## 范围与排除

- 提交内 12 个文件，全部在 `app/src/` 下：M 9（`IsolatedNoteStorage.kt`、`MainActivity.kt`、
  `RecentNoteUsage.kt`、`RecentNotesStore.kt`、`NotesListScreen.kt`、`NotesListScrollbar.kt`、
  `NotesState.kt`、`RecentNoteUsageTest.kt`、`NotesListScrollbarMathTest.kt`）、
  A 3（`RecentNotesStoreTest.kt`、`NotesStateRecentNotesTest.kt`、`ScrollbarMath.kt`）。
- 未入库：`app/libs/sherpa-onnx-2.0.1.aar`（`git ls-tree -r eafd31e -- app/libs` 为 0 个文件）、
  任何 APK、三个证据目录（`manual-install-evidence/`、`scope-accept-v2-evidence/`、
  `scrollbar-scope-build-evidence/`）。暂存用了 `git add -- app/src/` 限定路径。

## 闸门与偏差

1. 基线：提交前 `HEAD` 与 `origin/ux/rhythm-recent-notes-1` 均为 `5c688af4`，`eafd31e^` 也是 `5c688af4`。
2. `git diff --cached --check` **退出 2**，报 18 条 `trailing whitespace`，全部落在
   `app/src/main/java/com/example/myapplicationfirstapp/MainActivity.kt`。
   成因：该文件在索引与工作区都是 CRLF（`git ls-files --eol` = `i/crlf  w/crlf  attr/`），
   仓库无 `.gitattributes`，`core.autocrlf=false`，于是每个新增行末的 `\r` 被判成行尾空白。
   该文件本次 diff 只有 26 行，**不是整篇行尾改写**；其余 11 个文件无此告警。
   未在本轮顺手做全文件行尾归一化——那会变成一次独立的、影响面大得多的改动。
3. 用户给的 `git status --short --cached` 不被 git 接受（`error: unknown option 'cached'`），
   改用等价清单 `git diff --cached --name-status` 核对，结果与上条文件列表一致。

## 推送与远端核对

`git push origin HEAD:refs/heads/ux/rhythm-recent-notes-1` 回显
`5c688af..eafd31e  HEAD -> ux/rhythm-recent-notes-1`（快进，无 `--force`）。
回读：`git rev-parse origin/ux/rhythm-recent-notes-1` = `eafd31e8…`，`git ls-remote` 同为该值；
`refs/heads/main` 仍为 `61dff28e4041aa5a8d9376ba1da872a87a009301`，本轮未触碰。
推送后命令尾部出现过一条安全策略提示，未重试，改用 `ls-remote` 与 `fetch` 只读回读确认已落远端。

## 未做（等指令）

1. 冷启动首屏显示轮播：需在 `NotesState` / `NotesListScreen` 增加「recentNotes 首次异步到位且用户
   未手动滚动时回列表顶部」的行为，属改源码，需单独授权与单独小提交。
2. `workbuddy_install_scrollbar_v2.sh` 的 `\r` 修复（`paths=$(verify_apks | tr -d '\r')`）：
   属脚本，未改，待指定改远端 `v2-request/` 原稿还是本地副本。

## 文件

| 文件 | 内容 |
|---|---|
| `commit-meta.txt` | `git log -1 --format=fuller eafd31e` |
| `commit-stat.txt` | `git show --stat --oneline eafd31e` |
| `commit-files.txt` | 12 个文件的 name-status |
| `feature-landing.diff` | `git diff 5c688af4 eafd31e`（104,244 B） |
| `landing-gates.txt` | 基线/暂存/check/一致性四项实测值 |
| `remote-readback.txt` | `ls-remote`、`rev-parse`、`log`、push 回显 |
