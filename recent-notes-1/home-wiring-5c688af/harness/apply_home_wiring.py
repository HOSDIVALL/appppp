from pathlib import Path
import subprocess

BASE = "5c688af4d30f01f8939319cad47b2393a8ebaa99"
PKG = Path("app/src/main/java/com/example/myapplicationfirstapp")

def once(text, old, new):
    if text.count(old) != 1:
        raise SystemExit(f"Unexpected source anchor: {old[:100]}")
    return text.replace(old, new, 1)

def replace_section(text, start, end, new):
    left = text.index(start)
    right = text.index(end, left)
    return text[:left] + new + text[right:]

screen = PKG / "ui/NotesListScreen.kt"
activity = PKG / "MainActivity.kt"
if subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() != BASE:
    raise SystemExit("Wrong HEAD; use the pinned detached worktree")
if subprocess.check_output(["git", "hash-object", str(screen)], text=True).strip() != "57dd36c67811c2f800c710dbf93c62b6630aece6":
    raise SystemExit(f"Unexpected baseline: {screen}")
raw = screen.read_bytes()
s = raw.decode().replace("\r\n", "\n")
a_raw = activity.read_bytes()
a = a_raw.decode().replace("\r\n", "\n")
state = (PKG / "ui/NotesState.kt").read_text(encoding="utf-8")
if "val recentNotes: List<Note>" not in state or "state.openNote(id)" not in a:
    raise SystemExit("Apply the verified first-batch patch in the detached worktree first")
s = once(s, "import androidx.compose.runtime.getValue", "import androidx.compose.runtime.derivedStateOf\nimport androidx.compose.runtime.getValue")
s = once(s, "    onOpenSettings: () -> Unit,\n    modifier: Modifier = Modifier", "    onOpenSettings: () -> Unit,\n    modifier: Modifier = Modifier,\n    isHomeActive: Boolean = false")
s = once(s, "BackHandler(enabled = searchActive)", "BackHandler(enabled = searchActive && isHomeActive)")
summary_start = s.index("        Row(\n            modifier = Modifier")
search_start = s.index("        // ---------- 搜索 ----------", summary_start)
list_start = s.index("        // ---------- 筛选 chip", search_start)
summary = s[summary_start:search_start]
search = s[search_start:list_start]
s = s[:summary_start] + s[list_start:]
s = replace_section(s, "    val pinned = notes.filter", "    Column(", """    val recent = state.recentNotes
    val model = buildNotesHomeListModel(notes, isFiltering, recent.isNotEmpty(), { it.id }, { it.pinned })
    val displayNotes = model.displayNotes
    val listDataKey = listOf(model, recent.map { it.id }, state.query, state.sortMode, state.filter, state.selectedTag).hashCode()

""")
s = once(s, "        val bg = MaterialTheme.colorScheme.surface", """        val carouselVisible by remember(listState) {
            derivedStateOf {
                val info = listState.layoutInfo
                info.visibleItemsInfo.any {
                    it.key == "home:carousel" && it.offset < info.viewportEndOffset &&
                        it.offset + it.size > info.viewportStartOffset
                }
            }
        }
        val bg = MaterialTheme.colorScheme.surface""")
s = once(s, "        LargeTopAppBar(", "        Spacer(Modifier.height(10.dp))\n        LargeTopAppBar(")
s = once(s, "                            letterSpacing = (-1).sp", "                            fontSize = (32f - 8f * scrollBehavior.state.collapsedFraction).sp")
s = once(s, "                    start = AppSpacing.page,\n                    end = AppSpacing.page,", "                    start = 0.dp,\n                    end = 0.dp,")
filters_start = s.index("                item(key = \"__filters__\") {")
filters_end = s.index("                // 新建入口已统一", filters_start)
filters = s[filters_start:filters_end].split("\n", 1)[1].rstrip()
assert filters.endswith("}")
filters = filters[:-1].rstrip()
body = """                items(model.items, key = { it.key }, contentType = { it.kind }) { entry ->
                    when (entry.kind) {
                        NotesHomeItemKind.CAROUSEL -> RecentNotesCarousel(
                            notes = recent, imageFile = state::imageFile, onOpen = ::openNote,
                            active = isHomeActive && !searchActive && carouselVisible && !listState.isScrollInProgress
                        )
                        NotesHomeItemKind.SUMMARY -> {
@@SUMMARY@@
                        }
                        NotesHomeItemKind.SEARCH -> {
@@SEARCH@@
                        }
                        NotesHomeItemKind.FILTERS -> Box(Modifier.padding(horizontal = AppSpacing.page)) {
@@FILTERS@@
                        }
                        NotesHomeItemKind.EMPTY -> EmptyState(
                            isSearching = isFiltering, strings = s,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.page)
                                .padding(top = ListVisualTokens.listTopGap).height(300.dp)
                        )
                        NotesHomeItemKind.PINNED_HEADER, NotesHomeItemKind.RECENT_HEADER -> ListSectionHeader(
                            text = if (entry.kind == NotesHomeItemKind.PINNED_HEADER) s.pinnedSection else s.recentSection,
                            modifier = Modifier.padding(horizontal = AppSpacing.page).padding(top = ListVisualTokens.interGroupGap)
                        )
                        NotesHomeItemKind.NOTE -> {
                            val note = checkNotNull(entry.note)
                            Box(Modifier.padding(horizontal = AppSpacing.page)) {
                                NoteRowWithSwipe(
                                    note = note, thumbnail = note.images.firstOrNull()?.let(state::imageFile),
                                    strings = s, onClick = { openNote(note.id) }, onDelete = { onDelete(note) },
                                    onTogglePin = { state.togglePin(note.id) },
                                    topGap = if (entry.startsUnsectionedGroup) ListVisualTokens.listTopGap else ListVisualTokens.intraGroupGap,
                                    shape = ListGrouping.shapeOf(ListGrouping.slotOf(entry.indexInGroup, entry.groupSize))
                                )
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(24.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0.72f), bg.copy(alpha = 0.32f), Color.Transparent))))

""".replace("@@SUMMARY@@", summary.rstrip()).replace("@@SEARCH@@", search.rstrip()).replace("@@FILTERS@@", filters)
s = replace_section(s, "                // 类型筛选", "            // 底部渐变", body)
start = a.index("HomeTab.NOTES -> NotesListScreen(")
end = a.index("HomeTab.RECORD ->", start)
a = a[:start] + once(a[start:end], "modifier = contentModifier", """modifier = contentModifier,
                                isHomeActive = page == AppPage.List && homeTab == HomeTab.NOTES &&
                                    !recorder.isRecording && viewerFiles == null && pendingImportUri == null &&
                                    !showFontSettings && !showExactAlarmHint && sheetRecordingId == null &&
                                    renameTargetId == null && notePickerRecordingId == null""") + a[end:]
for path, old, new in [(screen, raw, s), (activity, a_raw, a)]:
    newline = "\r\n" if b"\r\n" in old else "\n"
    path.write_bytes(new.replace("\n", newline).encode())
subprocess.run(["git", "diff", "--stat", "--", str(screen), str(activity)], check=True)
