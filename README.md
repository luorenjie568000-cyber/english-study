# English Study · 原生离线版

一个**完全离线**的英语学习 App，纯 Java 原生实现（无 WebView、无网络请求）。
词库、例句、翻译语料、发音模型全部打包在 APK 内或首次下载后本地运行。

## 功能

| 标签 | 内容 |
|---|---|
| 学习 | 今日进度环、FSRS-6 间隔重复、一题一屏四档自评（忘了/勉强/记得/简单） |
| 口语 | **跟读打分**（12 句三档难度 + 自定义句）、**8 个场景对话**（点咖啡/面试/问路/酒店/看医生/餐厅/机场/闲聊），本地识别 + 关键词命中评分 |
| 词汇 | 内置 35251 词 ECDICT（标准 IPA、词频、柯林斯星级、牛津3000、词形变化）、生词本四档分组、教材词书（高考 7 册 36 单元） |
| 翻译 | 71642 句人工英中对照整句翻译 + 逐词对照兜底 + 本地历史 |
| 写作 | 本地批改（中式搭配/冠词/时态/拼写/标点），分数 + 标红原文 + 问题清单 |
| 音标 | 44 个国际音标，点按听发音 |
| 阅读 | 22 篇分级短文，点词查义、点句朗读、整篇通读 |
| 我的 | 学习统计、离线语音模型管理（下载/删除）、每日目标、语速、数据清空 |

## 离线语音

- **TTS**：sherpa-onnx + VITS(piper) Amy 女声 / Lessac 男声，16 kHz，纯本地合成。
  模型未下载时自动降级到系统 TTS（同样离线）。
- **ASR**：sherpa-onnx + NeMo CTC small / Whisper tiny.en，16 kHz 录音识别。
- **espeak-ng-data** 最小集（1.3 MB）打包在 assets，首次启动解包到 `filesDir/voice/`。

模型从 hf-mirror 下载，支持断点续传与单飞锁（同一包不允许并发写）。

## 数据来源

| 资源 | 来源 | 规模 |
|---|---|---|
| 词典 | [ECDICT](https://github.com/skywind3000/ECDICT) | 35251 词，含 56422 条词形还原 |
| 音标 | [ipa-dict](https://github.com/open-dict-data/ipa-dict) | 合并进词典 |
| 例句 | [Tatoeba](https://tatoeba.org/) eng_sentences | 3717 词 × 4 句 |
| 翻译 | Tatoeba eng↔cmn links | 71917 对，OpenCC 繁转简 |
| 发音模型 | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) releases | VITS / NeMo |

## 构建（无 Gradle）

沙箱里用 aarch64 静态 build-tools 手工打包：

```sh
sh tools/setup_env.sh          # 下载 android-all.jar / r8 / apksig / aapt2 / sherpa.aar
sh tools/build.sh debug        # 产物 /tmp/es_out/es-debug.apk
```

流程：`aapt2 compile → aapt2 link → javac → D8 → zip → zipalign → SignApk(v1+v2+v3)`。

### 构建期踩过的坑（改代码前先看）

1. sherpa 的 Java 类 + `kotlin-stdlib.jar` 必须作为 **D8 的输入文件**，只放 `--classpath` 不会进 dex
   → 运行时报 `NoClassDefFoundError: OfflineTtsVitsModelConfig`
2. `ModelRepo.ready()` 不能用 `length() < 1024` 判空：`tokens.txt` 只有 763 字节
3. 语音引擎要在 `MainActivity.onCreate` 预热，否则进「我的」页永远显示"加载中…"
4. `AudioTrack.write()` 返回只代表数据进了缓冲；必须等 `getPlaybackHeadPosition()` 走完再 `stop()`，
   否则音频被立刻截断（听不到声音）
5. 并发下载同一个 `.part` 会把模型写坏（同大小、MD5 不符）→ 必须加单飞锁
6. 语音卡片的状态刷新只能改 `TextView` 文本，不能 `refresh()` 整页（会重置 ScrollView 位置）
7. `MainActivity` 在 `com.englishstudy.app` 包，引用 `.ui` 子包类必须显式 import
8. 录音权限除 `pm grant` 外，小米还需 `appops set <pkg> RECORD_AUDIO allow`

## 自检

设备上放一个开关文件即可跑全量自检，结果落在 `filesDir`：

```sh
su -c "touch /data/data/com.englishstudy.app/files/selftest.on"
# 重启 App 后
su -c "cat /data/data/com.englishstudy.app/files/selftest.txt"
su -c "cat /data/data/com.englishstudy.app/files/voice-diag.txt"
```

`voice-diag.txt` 里含**闭环验证**：TTS 合成 → PCM 回灌 ASR → 打分，
三句测试句全部 100 分，证明发音与识别两端都真在工作。

## 许可

代码 MIT。词库/例句/翻译语料遵循各自上游许可（ECDICT MIT、Tatoeba CC-BY 2.0 FR）。

## 更新记录

### v4.0（2026-11-07）
- 离线语音全链路打通：VITS TTS（Amy/Lessac）+ NeMo CTC / Whisper ASR + espeak-ng 内置
- 新增「口语」页：跟读打分（12 句三档 + 自定义）、8 个场景对话（34 轮 NPC + 关键词评分）
- 单词详情页加跟读练习，阅读页每句可单独朗读 + 整篇通读
- 修复：播放被截断（等 playback head）、renameTo 静默失败、释义字面量 \n、评分栏被挤出屏幕、选句重置滚动、Whisper [BLANK_AUDIO] 占位、语速异步失效
- 新启动图标，版本号 400/4.0
