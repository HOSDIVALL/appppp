# 补证素材（全部本轮生成，无隐私）

| 文件 | 说明 | 是否入库 |
|---|---|---|
| `FIXTURE_IMG_01.png` | 900×600 虚构色块图，Pillow 生成；`sha256 = baf121559078ec0bcb9c59793f33513cc6197dd9bd5c46d1ed63c4444933fc1a` | 是 |
| `FIXTURE_AUDIO_01.json` | 录音元数据（标题 `FIXTURE-AUDIO-01`、8 秒、波形包络），供应用录音列表识别 | 是 |
| `FIXTURE_AUDIO_01.m4a` | 8 秒 440Hz 正弦，ffmpeg 生成；`sha256 = 75615df84c4ba93eadedd2799fa05d48c7fe7b48bcbe93e3658bc5d976d65188` | **否** |

`*.m4a` 被仓库 `.gitignore` 排除（避免误提交用户真实录音）。为不绕过这条规则，音频本体**不入库**，用下面的命令可逐字节复现：

```bash
ffmpeg -f lavfi -i "sine=frequency=440:duration=8" -af volume=0.25 -c:a aac -b:a 96k FIXTURE_AUDIO_01.m4a
```

复现后核对：`sha256sum FIXTURE_AUDIO_01.m4a` 应为 `75615df8…76d65188`（ffmpeg 版本不同可能不同，音频内容仍是同一段 8 秒正弦，不影响复跑结论）。

图片生成命令见 `../logs/adb-commands.md` §0。
