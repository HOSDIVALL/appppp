# 组件清单（按包分组）

> 来源：`data/functions-by-file.csv`。函数名从 lambda 内部类（`FooKt$Bar$1$1`）反推得到，
> 被 R8 完全内联的函数不会出现在这里，所以**列表是"至少存在这些"，不是全集**。
> 参数与实现见 `REPORT.md`，源码路径见 `FILE-INDEX.md`。

**通用组件 components/common**

| 文件 | 可还原的公开函数 |
|---|---|
| ArcProgressSliderKt | ArcProgressSlider |
| CollapsibleHeaderKt | ArtistCollapsibleHeaderScreen, CollapsibleHeaderScreen, FixedHeaderScreen |
| ComposableSingletons | BackdropShapesKt, CollapsibleHeaderKt, ExpressiveComponentsKt, ExpressiveNavigationKt, NavigationComponentsKt, RhythmControlButtonKt, RhythmWavyProgressLoaderKt |
| CookieHorizontalSliderKt | CookieHorizontalSlider |
| DragDropListKt | DragDropLazyColumn |
| ExpressiveComponentsKt | AutoScrollingText, ExpressiveGroupButton, ExpressiveMorphingPlayPauseButton, ExpressiveMorphingToggleButton, ExpressivePlayerControlGroup |
| ExpressiveScrollBarKt | ExpressiveScrollBar, buildVisibleGridLines |
| ExpressiveShapeProviderKt | toComposeShape |
| MarqueeTextKt | AutoScrollingText |
| NavigationComponentsKt | AlphabetBar, AlphabetBarItem |
| ProgressBarStylesKt | StyledProgressBar, WavyCircularProgress |
| RhythmButtonGroupKt | RhythmButtonWeighted, RhythmDetailActionButton, RhythmDetailActionButtonFullWidth, RhythmToggleButton |
| RhythmControlButtonKt | RhythmCompactButton, RhythmControlButton, RhythmPillButton, RhythmPlayButton, RhythmTapTonalButton |
| RhythmGuardCardKt | RhythmGuardCard |
| SmallTabAnimationKt | SmallTabAnimation |
| TabAnimationKt | TabAnimation |
| WaveSliderKt | WaveSlider |

**播放器 components/player**

| 文件 | 可还原的公开函数 |
|---|---|
| AnimatedPlaybackControlsKt | AnimatedPlaybackControls |
| CanvasArtworkPlayerKt | CanvasArtworkPlayer |
| ComposableSingletons | ExpressiveBottomButtonsOrderBottomSheetKt, ExpressiveMiniPlayerKt, MaterialMiniPlayerKt, PlayerChipOrderBottomSheetKt, SleepTimerBottomSheetNewKt |
| ExpressiveBottomButtonsOrderBottomSheetKt | ExpressiveBottomButtonsOrderBottomSheet |
| ExpressiveMiniPlayerKt | ExpressiveMiniPlayer |
| MaterialMiniPlayerKt | MaterialMiniPlayer |
| MorphingPlayPauseButtonKt | MorphingPlayPauseButton |
| PlayerChipOrderBottomSheetKt | PlayerChipOrderBottomSheet |
| RhythmPlayerSheetKt | RhythmPlayerSheet |
| SleepTimerBottomSheetNewKt | SleepTimerBottomSheetNew |

**歌词 components/lyrics**

| 文件 | 可还原的公开函数 |
|---|---|
| ComposableSingletons | FullScreenLyricsViewKt, LyricsEditorBottomSheetKt |
| FullScreenLyricsViewKt | FullScreenLyricsView |
| LyricsEditorBottomSheetKt | LyricsEditorBottomSheet |
| LyricsItem | Gap, LyricLine |
| SyncedLyricsItem | Gap, Line |
| SyncedLyricsViewKt | SyncedLyricsView, SyncedLyricsView_ORh_Wlk, animateToSyncedItemWithCatchUp |
| WordByWordLyricsViewKt | WordByWordLyricLineItem, WordByWordLyricsView, WordByWordLyricsView_8P5MkaM, animateToLyricItemWithCatchUp |

**图标 components/icons**

| 文件 | 可还原的公开函数 |
|---|---|
| RhythmIcons | Actions, Connectivity, Content, Devices, Music, Navigation, Player, System … |

**主题 ui/theme + festive**

| 文件 | 可还原的公开函数 |
|---|---|
| ChristmasDecorationsKt | SideDecorations |
| ComposableSingletons | FestiveThemeExamplesKt |
| FestiveSplashGreetingKt | FestiveSplashGreeting |
| SnowfallEffectKt | SnowfallEffect, SnowfallWithSparkle |

**设置页 screens/settings**

| 文件 | 可还原的公开函数 |
|---|---|
| ArtistSeparatorsSettingsScreenKt | ArtistSeparatorsSettingsScreen |
| BackupRestoreSettingsScreenKt | BackupRestoreSettingsScreen |
| CacheManagementSettingsScreenKt | CacheManagementSettingsScreen, trimArtworkCacheNow |
| ComposableSingletons | AboutScreenKt, ApiManagementSettingsScreenKt, ArtistSeparatorsSettingsScreenKt, AudioRoutingDialogKt, BackupRestoreResultBottomSheetKt, BackupRestoreSettingsScreenKt, CacheManagementSettingsScreenKt, CanvasNetworkModeDialogKt … |
| DecoderExtensionsSettingsScreenKt | DecoderExtensionsSettingsScreen |
| ExperimentalFeaturesScreenKt | LabsSettingsScreen |
| ExpressiveShapesSettingsScreenKt | ExpressiveShapesSettingsScreen |
| ExternalCapabilitiesSettingsScreenKt | ExternalCapabilitiesSettingsScreen |
| FontSelectionDialogKt | FontSelectionBottomSheet |
| LibrarySettingsScreenKt | LibrarySettingsScreen |
| LyricsSettingsScreenKt | LyricsSettingsScreen |
| MediaScanSettingsScreenKt | MediaScanSettingsScreen |
| NotificationsSettingsScreenKt | NotificationsSettingsScreen |
| PlaybackSettingsScreenKt | PlaybackSettingsScreen |
| PlayerCustomizationSettingsScreenKt | ProgressStyleBottomSheet, ThumbStyleBottomSheet |
| PlaylistsSettingsScreenKt | PlaylistsSettingsScreen |
| QueueSettingsScreenKt | QueueSettingsScreen |
| RhythmGuardSettingsScreenKt | RhythmGuardSettingsScreen, rememberSystemMusicVolumeFraction, rhythmGuardWeeklyAverageSessions |
| SettingsScreenKt | SettingsScreen, SettingsScreenWrapper, SettingsTipsCarousel, SettingsTipsRow |
| SettingsSearchKt | searchSettings |
| ThemeCustomizationSettingsScreenKt | ColorSchemeCircle, ColorSchemePaletteRow, ThemeCustomizationSettingsScreen |
| UpdatesSettingsScreenKt | UpdatesSettingsScreen |
