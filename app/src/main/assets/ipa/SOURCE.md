# 音素音频来源

44 个音素样本（`<sym>.raw`，16 kHz 单声道 PCM16 小端）全部来自**真人录音**，非 TTS 合成。

| 组 | 来源 | 许可 |
|---|---|---|
| 20 个元音 / 双元音 | [VV-yuna/english-ipa-learning](https://github.com/VV-yuna/english-ipa-learning) 的 `assets/audio/human/*.wav`，派生自 [ipachart.com](https://www.ipachart.com/) 的 Wikimedia Commons 录音（Peter Isotalo、Denelson83、UCLA Phonetics Lab Archive 2003、Halibutt、Pmx、Octane 等） | 自由 / copyleft（Wikimedia Commons） |
| 22 个辅音 | [toipa.org](https://toipa.org/) 的 `/ipa-animations/*.mp3` 真人发音 | 参考站点公开示例音频 |
| `tʃ` / `dʒ` | 上述真实塞音爆破段 + 真实后齿龈擦音拼合（不含任何合成音） | 同上 |

## 处理流程

`tools/build_ipa.py`：

1. ffmpeg 转 16 kHz 单声道 s16le
2. 10 ms 帧能量包络切分，取最长连续发声段（去掉首尾静音）
3. 元音上限 1.10 s、辅音上限 0.75 s，超长尾部带 60 ms 淡出
4. RMS 归一化到 5000，峰值上限 30000（避免不同来源音量落差）
5. 首尾 15 ms 淡入淡出，防爆音

## 不再使用 TTS 的原因

早期版本用 `espeak-ng` 合成单音素，听起来机械且浊塞音 b/d/g 几乎无声
（物理上只有一段爆破，没有元音载波就听不见）。换成真人录音后音质正常。
