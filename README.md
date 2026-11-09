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

### v4.2（2026-11-07 晚）
- 修复写作批改必闪退：`a.getColor(scoreColor(...))` 把已算好的 ARGB 当资源 id 再查一次
  → `Resources$NotFoundException: Resource ID #0xfff59e0b`
- 44 个音素全部换成**真人录音**（toipa.org / ipachart 的 Wikimedia Commons 录音），
  不再用 espeak 合成；塞擦音 tʃ/dʒ 由真实爆破段 + 真实擦音拼合
- 音色设置真正生效：`voice.tts` 决定加载 Amy 还是 Lessac（之前永远优先 Amy）
- 新增 `tools/build_ipa.py` 与 `assets/ipa/SOURCE.md`（来源与处理流程）

### v4.3（2026-11-07 夜）
- 新增「对话」标签：学伴 Agent
  - 未配置接口时走 `OfflineTutor` 纯离线（查词 / 翻译 / 批改 / 看复习）
  - 配置任意 OpenAI 兼容接口后，模型可调用 4 个本地工具：
    `lookup_word`、`translate_sentence`、`add_to_vocab`、`due_words`
  - 工具全部在手机上执行，只有对话文本发给模型
  - 设置入口在页面右上角「设置」，Key 只存本机 SharedPreferences
- **修复音色切换串音**：`OfflineTtsEngine` 增加 `generation` 代际号，
  `shutdown()` 会作废所有排队的合成任务并重置 `loading`；
  `say()` 在引擎加载中不再退回系统 TTS（那正是「切了男声却听到女声」的来源）；
  切换后界面会等 `loadedVoice()` 真的变成目标音色再念示例句
- 修复 `Agent` 的 `tool_call_id` 协议：tool 消息必须回带对应 id，assistant 的
  `tool_calls` 必须原样保留，否则接口直接 400 或返回空
- Manifest 加 `usesCleartextTraffic=true`（本地/Ollama 常是 http）

### v4.4（2026-11-07 深夜）
- **端侧本地模型**（llama.cpp / GGUF），完全离线、零 API 费用：
  - 原生库来自 `ffmpegkit-maintained/llama-android` 的 AAR（arm64-v8a），
    只取 `LlamaJNI` 这一层同步 JNI，**不引入 kotlinx-coroutines**
  - 支持 Qwen2.5-0.5B（380MB，43 tok/s）与 Qwen2.5-1.5B（940MB，16 tok/s）
  - 下载器带 `.part` 断点续传 + 字节数校验（同语音模型的写法）
- 本地模式的回答策略（实测得出）：
  - 词典命中 → **直接用本地数据拼答案**，不让小模型转述（转述会跑偏）
  - 复习 → 直接列真实到期词
  - 其余问题才交给模型自由回答
- 三种模式：本地模型 / 云端（OpenAI 兼容 + 4 个工具）/ 离线规则

#### 端侧模型踩的坑
1. `nativeComplete` **内部已经用模型自带 chat template 拼 prompt**（读 JNI 源码确认），
    Java 侧再套 `<|im_start|>` 会双套模板
2. 返回值是 JSON 字符串，`{"text":..,"tokensPerSecond":..}`，不是裸文本
3. 把整段词典条目塞进 user 会让 1.5B **在第 1 个 token 就停**；
   干净短 prompt 下同一模型能稳定输出 60 token
4. `-source 8` 编译，正则里的 `\s` 在 Java 字符串里要写 `\\s`，否则被当 text block 报错

### v4.5（2026-11-07 深夜）
补齐竞品分析 §14 里漏掉的 P0/P1 项：

| 项 | 来源 | 实现 |
|---|---|---|
| 打卡日历 + 连续天数 | P0-5 | `Streak` + `HeatmapView`，首页 8 周热力图，大字号连续天数 |
| 每日新词/复习上限 | P0-4 | 我的页可调（新词 10–100，复习 50–300） |
| 克漏字（Cloze） | P0-7 | 释义下方把例句里的目标词挖成 `____`，点句子看答案 |
| 统计图表 | P1-5 | 未来 30 天到期柱状图 + 稳定度分布折线图（纯自绘） |
| 导出 CSV / JSON | P1-11 | 我的页两个按钮，CSV 带 BOM 可直接用 Excel 打开 |
| 首启引导 | P0-8 | 定目标 → 选词书 → 立刻开始第一组（照 Duolingo 的顺序） |

顺带：`Streak.mark()` 挂在 `StudyActivity.applyGrade()` 里，复习一次记一次。

### v4.6（2026-11-08 上午）
**界面视觉升级**（用户要求「液态玻璃 + 可调节」）：

- **液态玻璃**（`Glass.java`）：4 档强度（关闭/轻/标准/强），参数化控制
  - 底色半透明 + 顶部高光渐变 + 边缘描边三层叠加
  - 玻璃感来自「边缘光 + 高光」，不是模糊（Android 上 `setRenderEffect`
    模糊的是 View 自己内容，会把文字糊掉，不能用）
  - 关闭档完全退回纯色卡片，0 成本
- **全屏光晕背景**（`Glass.Backdrop`）：两团径向渐变，玻璃要有东西可透才有质感
- **5 套主题**（`Theme.java`）：深蓝 / 森林 / 日落 / 纸墨（浅色）/ 纯黑（OLED）
  - 颜色表是静态的，切主题后 `recreate()` 生效
  - 182 处 `getColor(R.color.x)` 统一改走 `Theme.c(ctx, R.color.x)`
  - 按钮/药丸/卡片背景从 XML 资源改成代码绘制，才能跟主题
  - 热力图色阶由主题主色派生（`0.35 + i*0.16` 四档）
- **首页重排**：打卡热力图并入进度卡、词库状态压成一行，一屏看得下

#### 这轮踩的坑
1. **`View.setRenderEffect` 模糊的是自己** —— 不是背后。给卡片加它 = 把卡片文字糊掉。
2. **垂直线性布局里 `WRAP_CONTENT` 拿不到全宽** —— 热力图必须显式 `MATCH_PARENT`。
3. **热力图格子尺寸不能用 `getWidth()` 在 onDraw 里算** —— 那时可能还是 0，
   要把 `onMeasure` 的结果存成字段。
4. **XML `shape` 资源无法运行时换色** —— 必须改成 `GradientDrawable` 代码构造。
5. **`Resources.getColor(int, Theme)` 参数是主题不是默认值** —— 编译期就报错。
6. **测试时设备会自己熄屏** —— `svc power stayon true` + `settings put system screen_off_timeout 1800000`。

### v4.6 补：导入能力
| 功能 | 实现 |
|---|---|
| 导入 Anki apkg | `AnkiImport`：zip 抽出 `collection.anki2/anki21` → SQLite 读 `notes.flds` → 取第一字段当单词 |
| 导入自定义词表 | `WordListImport`：CSV / TSV / TXT，自动识别分隔符，跳表头，只收英文词 |

真实测试（造了最小 apkg + csv）：
```
导入完成：新增 2 个词
· diligent  —  勤勉的
· eloquent  —  雄辩的

导入完成：新增 2 个词，跳过 1 个   （跳过的是表头 word,meaning）
```
生词本从 123 → 127，5 个词全在。

**未做**：mdx/mdd 导入（需要实现 writemdict 的 key block / record block 解析，
6–10 人日，且 wmdx 格式边缘情况多；CSV 导入已覆盖绝大多数换词表需求）。

#### 玻璃实现按规范重构（v4.6.1）
调研产物 `docs/liquid-glass-spec.md` 落地后，重写 `Glass.java`：

| 改进 | 之前 | 现在 |
|---|---|---|
| 边缘光 | `GradientDrawable` 单色描边（看起来是"白框"） | `LinearGradient` TL→BR，上亮下暗（模拟光从左上来） |
| 镜面高光 | 简化的 TL_BR 渐变 | `RadialGradient`，峰值在宽度 24% / 高度 10% |
| 内阴影 | 无 | 底部 `LinearGradient`（62% 处起黑 α 0x14），做厚度 |
| 外发光 | 黑色投影（深色底上看不见） | **主色低 alpha** `0x26–0x33`（黑投影在深色下只是脏） |
| 板级参数 | 3 个档位混在一起 | `Spec` 结构体，4 档 12 个参数分离 |

**规范给出的关键依据**（都已核对官方文档）：
- `View.setRenderEffect` 模糊的是**自己画出来的内容**，不是背后 → 整套方案不用它
- `Window.setBackgroundBlurRadius` 要求 window **translucent + floating**，
  常规 Activity 用了**无任何效果也不报错**
- `BlurMaskFilter` 在硬件加速下**完全不支持**（静默失效）
- 深色下白色低 alpha 叠加做玻璃 → 发灰去饱和（"90% 的人做错的地方"）
- 对比度实测：主文字 13–14:1 安全；但 `#4F7CFF` 在强档仅 4.18:1 < 4.5:1

**padding 坑**：外发光要给 View 留边距，但留多了会把卡片内容挤窄
（第一次给了 16dp×0.6，卡片直接缩了一圈）。改成只留 `glowDyDp`。

### v4.7（2026-11-08 上午，用户反馈）
1. **悬浮胶囊导航**（`FloatingBar`）：仿 LSPosed 17 的底部悬浮条
   - 左右 18dp / 下方 20dp 留白，圆角 = 高度一半，看起来浮在内容上
   - 胶囊本体：比卡片亮一档的底色 + 边缘光 + 顶部高光 + **20dp 柔影**
   - 选中项：`onDraw` 里画一颗更亮的实心药丸（`dispatchDraw` 会被 child 盖住）
   - 投影是「浮起来」的关键 —— 贴底通栏怎么调都只是换了个背景色
2. **分类收窄：9 标签 → 7 标签**
   - 翻译并进 **词汇**（顶部分段：词汇 / 翻译）
   - 对话（学伴 Agent）并进 **口语**（三段：跟读 / 场景 / 对话）
   - 分段状态存 `Store("vocab.seg")` / `Store("speak.tab")`，refresh 重建后能恢复
3. **修复语速 bug（重要）**
   - `generate(text, speakerId, speed)` 的第三参是 **speed**，之前传的是 `1.0/rate`
   - 所以 rate=0.7 时传进去是 **1.43**，变成「0.7 倍速反而更快」
   - 同时 `setLengthScale` 在初始化时又乘了一次，双重叠加
   - 修法：`generate(t, 0, r)`，`setLengthScale(1.0f)` 固定
   - 自检实测：`speed[0.7]=49664 / speed[1.0]=38912 / speed[1.3]=33536` 采样，比例正确

#### 悬浮条踩的坑
- `dispatchDraw` 里画药丸会被 child 挡住（因为 super 之后才轮到 child，但药丸已在 super 之前画完）→
  必须用 `onDraw`（时机在背景之后、children 之前），并 `setWillNotDraw(false)`
- 药丸和胶囊底的亮度差太小会看不见，深色下要拉到 `#46536B` vs `#2A3446`

### v4.8（2026-11-08 上午）
修复三个用户反馈 + 新功能：

1. **口语页闪退**
   `ChatView chat = new ChatView(a)` 写在字段初始化里，那时构造器的 `a` 还没赋值
   → `NullPointerException` in `U.col(Context)`。改成懒加载 `if (chat == null) chat = new ChatView(a)`。

2. **词汇/翻译分段栏字看不清**
   未选中项原来用 `text_dim2` 文字 + 深色底，对比度太低。
   改成 `text` 色文字 + 卡片色底 + 1px 描边。

3. **阅读页支持导入自己的文章**（对应网页版的「粘贴/导入文章」）
   - `data/Articles.java`：JSON 存在 `filesDir/articles.json`
   - 阅读页右上「＋ 导入文章」：粘贴正文或从 txt/md 选文件
   - 导入的文章排在内置短文之前，显示词数 / 预估阅读时长
   - 打开后复用 `ReaderActivity`，点词查义、点句朗读、通读一遍全部可用
   - 长按卡片可删除

#### 参考 1000h.org（Enjoy App）的设计
它的核心是「1000 小时可理解输入」：
- 导入音频/视频/文章 → 逐句可视化跟读
- 每个句子单独评分，错误的音标标红
- 生词一键加入记忆系统

我们已具备的对应能力：导入文章 ✅ / 逐句朗读 ✅ / 点词查义 ✅ /
跟读评分 ✅ / 生词本 ✅。还缺「音频/视频跟读」和「句子语法分析」。

### v4.9（2026-11-08 上午）
1. **底部胶囊改椭圆端**：高度 58→50dp（更扁），左右留白 18→22dp，
   圆角保持半高 → 视觉上是椭圆端，不是圆头方块
2. **句子语法分析**（对应 1000h 的「句子分析」）
   - `engine/Syntax.java`：规则引擎，拆出主语/谓语/宾语/表语/状语
   - 同时给出句型（主谓宾/主系表/主谓（被动））、时态、语态
   - 阅读页**长按任意句子**弹出分析
   - 用词典 `pos` 字段判词性，词典没收录的不规则动词（bought/wrote/gave…）用表兜底
3. **分类再收窄：7 → 6 标签**
   - **音标**并进 **口语**（4 段：跟读 / 场景 / 对话 / 音标）
     理由：跟读本来就需要音素知识，放一起更顺
   - 最终：学习 / 词汇（词汇·翻译） / 口语（跟读·场景·对话·音标） / 写作 / 阅读（短文·我的文章） / 我的

#### 语法分析的实测（8 句样本）
```
The old man walked into the bakery.
  主语=The old man  谓语=walked  状语=into the bakery   主谓 / 一般过去时
He bought two loaves of bread and a cup of coffee.
  主语=He  谓语=bought  宾语=two loaves  状语=of bread   主谓宾
The weather is really nice today.
  主语=The weather  谓语=is really  表语=nice           主系表
She has been learning English for about three years.
  主语=She  谓语=has been learning  宾语=English        主谓宾 / 现在完成时
The book was written by a young teacher.
  主语=The book  谓语=was written  状语=by a young teacher  主谓（被动）/ 被动
```

#### 踩的坑
- 不能靠后缀判词性：`old` 会因 `-ed` 被当动词 → 必须查词典 `pos`
- 数词（two/three）不能当主语或谓语
- `be + ing` 是进行（主动），`be + 过去分词` 才是被动 —— 之前一律判被动
- 状语不能吃句末标点

### v5.1（2026-11-08 中午）：液态玻璃重做

之前的问题：底栏只是一个「半透明贴图」，内容滚动时底下什么都透不出来，
看起来像贴纸；而且描边太重，一眼假。

按 Apple HIG《Materials》+ WWDC25 的核心要求重做：

1. **玻璃必须真的透出并模糊背后的内容**
   `Glass.blurBehind(source, region, w, h, radius)`：
   - 把内容层画到 1/4 分辨率 Bitmap
   - 从中裁出「玻璃所在的那一块」区域
   - 三遍盒式模糊（≈高斯，O(n)）
   - 玻璃面板把这张图当底图绘制

   **踩的坑**：第一版没传 region，整页 draw 后缩放到玻璃尺寸 →
   胶囊里显示的是整页缩略图，完全对不上位置，看起来像一块灰糊。
   必须用 `getLocationOnScreen` 算绝对坐标，按区域裁剪。

2. **描边从「重描边」改成 0.8px 渐变**
   上亮（α 0x3C–0x5A）下暗（α 0x08），模拟光沿玻璃边缘折射。
   粗的均匀白描边看起来就是"白框"。

3. **填充透明度大幅提高**
   标准档 α 从 0x80 降到 0x68（强档到 0x42），让内容真的能透出来。

4. **投影改为「大半径低 alpha」**
   26dp 半径 / 10dp 下移 / α 0x66 —— 之前 0xCC 太重，像一条硬边界。

5. **药丸自己带外发光**
   6dp 半径 / 2dp 下移 / α 0x33，才有"凸起"感。

6. **玻璃底图提亮而不是压暗**
   之前用黑色蒙版压暗，结果是灰玻璃。改成顶部白色渐变提亮（α 0x1E→0x04），
   深色下再补 0x1A 黑底保对比度。

#### 参考的官方原文
> "Liquid Glass allows content to scroll and peek through from beneath these
> elements to give the interface a sense of dynamism and depth"
> —— Apple HIG《Materials》

这句就是「贴图 vs 真玻璃」的分界线：**内容必须能透出来**。

### v5.2（2026-11-08 中午）：多语言阅读辅助（按调研结论）

## 结论：不做「全语言学习」，只做「多语言阅读辅助」

调研核实的三个决定性事实：

1. **市场没有一个产品做到「一个离线 App 把 10 种语言学到能用」**
   Duolingo 每门课是独立团队独立内容（40+ 门课 = 40 个团队）；
   LingQ（53 语）/ Readlang（100+）只做「阅读器 + 生词表」，不做语法、不做课程；
   唯一「多语言词书并列」的百词斩也只是**词书**，无语法分析、无每语言 TTS。

2. **体积会翻 4-6 倍，换来的还是半残**
   中日韩法德西词典 ≈ 360-400MB，TTS +422MB，分词 +21MB → 总包 1.2-1.5GB。
   而日/韩 TTS 在 sherpa-onnx 官方生态里**根本没有生产级模型**。

3. **技术栈复用率 ≈ 0**
   语法引擎只适用英语；分词三套互不相通（jieba / kuromoji / 后缀表）；
   韩文 Tatoeba 只有 10,745 条例句，连「每词 4 句」都凑不出。

## 做法：只做中文 / 日文 / 韩文，且只做「查词」

| 语言 | 数据源 | 条目 | 实测体积 | 许可 |
|---|---|---|---|---|
| 中文 | CC-CEDICT | 203,379 | SQLite 21.5MB → gz 7.8MB | CC BY-SA 4.0 |
| 日文 | JMdict_e | 233,794 | SQLite 25.7MB → gz 10.2MB | EDRDG CC BY-SA |
| 韩文 | kengdic | 172,228 | SQLite 6.5MB → gz 2.9MB | MPL/LGPL |

合计 20.9MB，APK 从 18.6MB → 38.8MB。

功能范围（克制）：
- ✅ 点词查义（最长匹配 4→1 字，解决中文无空格）
- ✅ 读音（拼音 / 假名 / 汉字）+ 英文释义
- ✅ 一键存生词本（进 FSRS）
- ❌ 不做多语言语法分析（等于重写一套引擎）
- ❌ 不做多语言 TTS（日/韩无官方模型）

## 关键实现
- `MultiDict.detect()`：按 Unicode script 占比判断语言
  （假名 > 0 → 日文；谚文 > 20% → 韩文；汉字 > 20% → 中文）
- `MultiDict.longestAt()`：从 4 字往 1 字试，命中就返回
- 词典首次查询时懒解压到 `filesDir`（20MB 约几百毫秒），加载完 `rerender()`

## 踩的坑
1. **`TextPaint` 没有 `setUnderlineColor()`** → 要用字段 `ds.underlineColor`
2. **`recreate()` 会导致循环** → 改成局部 `rerender()` 重建句子容器
3. **kengdic 的列序是 `id|surface|hanja|gloss|...`**（不是我猜的 表面形|词性|读音|释义）
4. **下划线必须开**：中文没有空格，全关掉用户根本不知道哪里能点

### v5.3（2026-11-08 中午）：底栏材质重做 + 剩余问题清理

用户反馈「底部好像还没解决」——确实，之前的玻璃只在**背后有内容时**才好看，
在空白背景上就是一块平灰。重做材质本体：

1. **材质自带垂直渐变**（深色 `#39445A → #232C3B`）
   即使背后是空的，也能从渐变读出"这是一块玻璃"，不再是一块平灰
2. **顶部内高光**（下半部渐隐到透明）+ **底部内阴影**（55% 处起黑 α0x2A）
   这两层做出"凸面厚度"，是玻璃感的真正来源
3. **边缘光 0.9dp 渐变**（上 α0x46 / 下 α0x0A），不再是重描边
4. **双层投影**：10dp/4dp/α0x73（近处紧实）+ 28dp/12dp/α0x40（远处扩散）
   —— 之前只有单层淡影，浮不起来
5. **选中药丸**：明暗渐变底 + 顶部高光 + 0.8dp 细边 + 7dp 外发光
6. `setClipToPadding(false)` / `setClipChildren(false)` —— 否则投影被 View 边界裁掉

#### 同时修的问题
- **中文字数统计**：原来按空格分词，中文整段算 1 词。
  改成：CJK 字符按字计，拉丁按词计；阅读时长中文 300 字/分、英文 150 词/分
- **多语言词典完成后重渲染**：`recreate()` 会走完整生命周期、可能循环，
  改成 `rerender()` 只重建句子容器
- **中文下划线**：`TextPaint` 没有 `setUnderlineColor()`，要用字段 `ds.underlineColor`

### v5.4（2026-11-08 下午）：真正的悬浮 + 全文翻译 + 语言二审

## 1. 底栏「贴图感」的真因找到了

之前底栏是加在**垂直 LinearLayout** 里的 —— 它是布局的一部分，高度会把内容顶上去，
底部永远是它自己的区域。所以无论材质做得多像玻璃，**结构上它就不是浮层**。

修法：把底栏从 `root`（LinearLayout）移到 `shell`（FrameLayout），用
`gravity=BOTTOM` + margin 定位，`elevation` 抬到 8dp。内容改为占满整屏，
可以真正滚到胶囊背后。同时给内容底部补 padding，避免最后一行被盖住。

**验证**：阅读页滚动时，卡片文字会透到胶囊背后并被模糊 —— 这才是 Liquid Glass。
（Apple HIG 原话："content to scroll and peek through from beneath these elements"）

同时把透明度从 α0xE6/0xDC 提到 α0x96/0x88（强档 0x6E/0x62），
底色压暗到 `#2B3446 → #171E2B` —— 太重就变实心板。

## 2. 全文翻译（`engine/ArticleTool.java`）

两级策略：
1. **本地句库优先**：逐句查 71,642 对英中句对，命中直接用（零成本、零延迟）
2. **模型补全**：句库没有的交给大模型（本地 Qwen 或云端）

界面：文章页「全文翻译」按钮 → 逐句对照列表（英文 + 中文），显示「句库 N 句 / 共 M 句」。

## 3. 语言合理性二审（用户核心要求）

导入的文章可能是机器翻的、抄的、或自己写的，表达未必地道。
所以除了翻译，还让模型判断「这段英文是不是人话」。

点击「模型二审」→ 一次调用返回 JSON：
```json
{"lines":[{"en":"原句","zh":"译文","issue":"问题说明或空"}],
 "summary":"整篇语言质量总评"}
```

真机实测（故意写的病句）：
```
语言审校 · 5 处问题
整篇文章存在多处时态、拼写和用词错误，语言不地道，需要修改。

Yesterday I go to a coffee shop.
⚠ 时态错误，应为过去时：Yesterday I went to a coffee shop.
I very like the quite place.
⚠ "very like"不地道，应为"like ... very much"；"quite" 拼写...
Although it was crowded, but I found a seat.
⚠ 英语中 although 和 but 不能连用，应去掉 but
The waiter was very frienly.
⚠ 拼写错误："frienly" 应为 "friendly"
I am boring in the afternoon.
⚠ 用词错误："boring" 表示"令人无聊的"，应改为 "bored"
```

干净的文章返回「0 处问题 · 全文语法正确、表达清晰自然」。

## 实现要点
- `Agent.askRaw(prompt, maxTokens)`：不带工具、不写历史的原始问答，
  三种模式（本地/云端/离线）都支持
- `ArticleTool.splitSentences()`：按 `.!?` 切，短碎片（"Mr."）并回上一句
- 模型返回的 JSON 用前缀 80% 匹配对位（模型常改标点）

### v5.6（2026-11-08 下午）：底栏玻璃再修（浅色主题 + 坐标 bug）

用户反馈「底部栏是不透明白色贴图，挡住了页面底部的文字」。
查下来是两个叠加问题：

1. **当前主题是「纸墨」（浅色）**，底栏用了 `0xF2FFFFFF`（95% 不透明纯白）
   → 就是一块白板。改成米灰渐变 `#F0EDE6 → #DDD8CE` + α0x6E/0x60
2. **模糊快照的坐标系搞错了**：`region` 是屏幕绝对坐标，
   但 `source.draw()` 需要本地坐标 → 抓出来的图是空白的，
   所以玻璃背后什么都没有，只剩半透明色块

修法：`cv.translate(loc[0] - region.left, loc[1] - region.top)`，
先把 region 从屏幕坐标转成 source 本地坐标再绘制。

3. **底部文字被遮挡**：之前给根 View 加 padding 对 ScrollView 无效
   （padding 会被算进滚动区，滚到底时最后一行仍在胶囊后面）。
   改成判断类型：ScrollView 就给直接子 View 加底部 padding（滚动区变长）。

#### 关键教训
**Android 上没有「读取背后像素」的 API。**
`View.setRenderEffect` 模糊的是自己画的内容；`Window.setBackgroundBlurRadius`
要求 floating 窗口。所以玻璃只能靠「快照 + CPU 模糊」近似 —— 这意味着：
- 快照的**坐标系**必须精确（屏幕坐标 vs 本地坐标）
- 快照的**抓取时机**必须对（布局完成后）
- 内容滚动后必须**重抓**（否则看到的是旧画面）

做不到 iOS 那种逐帧实时的真折射，这是平台限制，不是实现问题。

### v5.7（2026-11-08 下午）：真·液态玻璃（RenderNode + RenderEffect）

## 调研结论（子代理 26 分钟，196 次工具调用，实机验证）

之前的三个方案全部失败的**源码级原因**：

| 方案 | 为什么失败 |
|---|---|
| `View.setRenderEffect(blur)` | `RenderEffect` 作用对象是**该 View 自己的绘制结果**。给 TabBar 加 = 糊自己的文字 |
| `Window.setBackgroundBlurRadius()` | AOSP `DecorView.java:1345`：`mBackgroundBlurRadius = mCrossWindowBlurEnabled && mWindow.isTranslucent() ? ... : 0` —— 非 translucent 窗口直接归零，**静默失效** |
| Bitmap 快照 + CPU 盒式模糊 | 方向对，但用错了介质：Bitmap 有坐标对齐、重抓、性能三座大山 |

## 正解：RenderNode 重录背景 + RenderEffect GPU 模糊

```java
// 1. 把背景层录进 RenderNode（显示列表，不是位图）
RecordingCanvas rc = backdropNode.beginRecording(w, h);
drawTree(rc, source, this);        // 跳过玻璃自己那一支
backdropNode.endRecording();

// 2. 给这个 RenderNode 挂 RenderEffect（GPU 高斯模糊）
backdropNode.setRenderEffect(RenderEffect.createBlurEffect(r, r, CLAMP));

// 3. 画到自己身上
canvas.drawRenderNode(backdropNode);
```

**关键点**：
- 被模糊的是背景层，玻璃自己的文字原样清晰
- 全程 GPU，零 Bitmap、零回读、零 CPU 模糊
- `RenderNode` 的显示列表会自动跟随 View 树变化，**不需要手动重抓**

实机跑分：**p99 = 9ms，462 帧 1 掉帧（0.22%）**，6 次连续 fling 零掉帧。

## 布局约束（成败关键）
1. **背景源必须指向有背景色的父容器**（这里是 `shell`），
   不能指向 `ScrollView` —— 它 padding 处是透明的，模糊后出现深色空洞
2. 玻璃必须是背景源的**直接子级** → 遍历时 `child == glass` 直接跳过，
   不会有重入（`IllegalStateException`）和成环（无限递归）
3. **不要**在 onDraw 里无条件 `invalidate()` —— 会形成 invalidate→draw 死循环

## AGSL 折射（API 33+）
用 `RuntimeShader` 做边缘折射 + 色散 + 高光，这是唯一能做出 iOS 那种"透镜边"的路径。
三个坑：
- `refract` 是 AGSL 内建函数名 → uniform 要加前缀 `gRefract`
- `half4` 颜色 uniform 必须用 `setColorUniform(String, long)`
- **AGSL 编译失败是运行时抛异常** → 必须 try/catch + 永久降级标志，否则用户设备直接崩

## 参数（照规范）
| 参数 | 值 |
|---|---|
| blurRadius | 24dp（超过 40dp 收益递减） |
| cornerRadius | 高度一半（胶囊） |
| refraction | 12dp（超过 20dp 像塑料） |
| dispersion | 0.10（>0.25 出彩虹边） |
| tintColor | 浅色 0x22FFFFFF / 深色 0x1F000000 |
| saturation | 1.25（让模糊后不发灰） |

## API 30 降级
`RenderEffect` 是 API 31。API 30 走不透明兜底（仍然圆角，视觉上像玻璃卡片）。
iOS 的 `UIGlassEffect` 在低端设备上也是这么降级的。

## 平台真相
Android **有**能力做逐帧实时真模糊，但 `RenderNode.setBackdropRenderEffect()`
（一行代码的 behind-blur）是 **`@hide`**。hwui 层早已具备（`setBackdropImageFilter`），
只是没给 App 用。这是 **API 暴露问题，不是硬件问题**。

### v5.7（2026-11-08 下午）：三项 P1 功能

## 1. 词根词缀（`data/WordRoot.java`）
ECDICT 的 `wordroot.txt`（362KB，**611 个词根**）打进 assets。

- **正查**：这个词出现在哪些词根的例词里（精确）
- **反查**：按词根字符串做包含匹配 —— 前缀要求词首、后缀要求词尾、词根只要包含
- 单词详情页新增「词根词缀」卡片

真机验证：
```
international → 前缀 inter-  （同根：interact · interactive · intercalate · intercede）
hopeless      → 后缀 -less   without（同根：aimless · airless · baseless · blameless）
```

## 2. 逐词发音评分（`engine/Pronunciation.java`）
用 ASR 结果做**逐词对齐**，比原来的整句字符串相似度准得多：
- 能指出**具体哪个词**念错了（红色标记）
- 能算**语速**（词/分），判断念太快还是太慢
- 能看出**停顿**在哪（间隔 > 0.5s）

闭环验证（TTS 合成 → ASR → 评分）：
```
loop: "But weather is really nice today."
  timed.words=7 pron.score=83 wpm=152 advice=大部分对，再顺一遍就更好了。
    The miss(but)   weather ok  is ok  really ok  nice ok  today ok
loop: "Can I have a cup of coffee, please?"
  pron.score=100 wpm=166 advice=发音很准，节奏也对。
```

### 关于「音素级」的诚实说明
**这不是音素级 GOP 对齐。** sherpa 的 `OfflineRecognizerResult.getTimestamps()`
在 Whisper 模型上**全部返回 0**（实测），所以时间戳按词数均分时长估算。

真正的音素级评分需要 wav2vec2 + 强制对齐（模型 300MB+），不在离线 App 的预算内。
现在能做到的是「词级」：词对不对、快慢、停顿 —— 这已经覆盖了 80% 的跟读需求。
**不能**判断「把 /θ/ 念成 /s/ 但识别成同一个词」这类音素级错误。

## 3. 音频跟读（`data/AudioClip.java` + `ui/AudioPanel.java`）
入口：口语 → 音标 → 底部「打开音频跟读」

- 导入 mp3 / wav / m4a（MediaPlayer 读时长）
- **A/B 循环**：手动设两个点，反复听那一段 —— 这是跟读最实用的形态
- 分段拖动条 + 播放/暂停/回 A 点
- **录音对比**：录一句，ASR 识别出你念的内容，和音频对照

### 为什么不做自动逐句切分
需要「音频解码 → PCM → 静音检测 → 切分」，纯 Java 里要做完整解码器（Android 只给
MediaPlayer/MediaCodec，前者不给 PCM，后者要自己处理容器格式）。而且对音乐类素材
静音检测根本不准。**手动 A/B 反而更实用** —— 你知道要练哪一句。

也不做波形可视化：那需要解码全部 PCM 进内存，一首 5 分钟的歌 = 50MB。

### v5.8（2026-11-08 下午）：多语言全文翻译

之前「全文翻译」只支持英文（走本地 71,642 对英中句库）。中文/日文/韩文文章
只有查词，没有整篇翻译 —— 这是用户明确要求的「翻译要支持多语言」。

## 分级策略（按语言分流）

| 文章语言 | 翻译流程 |
|---|---|
| 英文 | 本地句库优先 → 缺的交给模型 → 仍缺的逐词对照 |
| 中文 | **模型把中文译成英文**（给学习者的对照译文）；无模型时用 CC-CEDICT 逐词对照 |
| 日文/韩文 | 模型译成中文；无模型时用对应词典逐词对照 |

**为什么中文要译成英文**：这是一款英语学习 App，中文文章的价值是
「读中文 → 看地道英文怎么说」。所以任务不是「中译中」，而是「中译英」。

## 真机验证（中文文章）
```
他昨天在书店买了一本很有意思的书。
He bought a very interesting book at the bookstore yesterday.

这本书讲的是中国古代的历史故事。
This book is about historical stories from ancient China.

读完之后，他打算再去买一本。
After finishing it, he plans to go buy another one.

语言质量总评：原文三句语法正确、表达自然，无语病；
译文分别使用一般过去时、一般现在时和现在时态搭配…
```

## 踩的坑
1. **模型返回的译文被丢弃**：`parse()` 里写了「zh 为空才填」，
   而非英文文章的 `zh` 已经被逐词对照占了 → 改成模型优先覆盖
2. **按文本匹配会串句**：`sameSentence` 用「前缀 80% 匹配」，
   三句中文互相串位（第一句对上了第三句的译文）→ 改成**按索引对位**
   （模型本来就是按原文顺序返回的，只有数量不符时才退回文本匹配）
3. **二审窗口把翻译窗口顶掉**：改成二审结束后直接显示「译文 + 问题」合并视图
4. 中文文章被二审批「这不是外语」→ 提示词按语言分三种任务描述

### v5.9（2026-11-08 下午）：浅色主题重做 + 视频跟读

## 1. 白底白字（用户反馈「白色背景的字体不要也是白色的」）
两个根因：
1. `Theme.c()` 里 `R.color.white` 直接返回 `0xFFFFFFFF`，浅色主题下就是白底白字
   → 改成：浅色主题返回 `0xFF1A1F26`
2. `U.primaryButton()` 硬编码 `0xFFFFFFFF` 绕过主题 → 改成走 `Theme.c`

## 2. 五套配色按类原生（Material 3）重做

| 主题 | 底色 | 卡片 | 主色 |
|---|---|---|---|
| 深色 | `#0E1116` | `#1C2026` | `#8AB4F8`（Material 蓝） |
| 森林 | `#0F1512` | `#1A231E` | `#7BD9A5` |
| 暖橙 | `#17110E` | `#2A211C` | `#FFB59E` |
| 浅色 | `#F6F7F9` | **`#FFFFFF`** | `#1A73E8`（Google 蓝） |
| 纯黑 | `#000000` | `#101316` | `#8AB4F8` |

关键改动：
- 浅色主题从「米黄纸感」改成 **纯白卡 + 中性灰底**（这才是类原生的样子）
- 主色改用 Material 3 的标准蓝 `#1A73E8` / `#8AB4F8`
- 次要文字全部重算对比度（`dim` 6:1 左右，`dim2` 4.5:1 以上）

## 3. 热力图色阶修浅色主题
原来浅色下给色阶 `+40` 导致变脏变棕 → 改成「向白色靠拢 55%」，得到干净的浅色梯度。

## 4. 视频跟读（`ui/VideoPanel.java`）
入口：口语 → 音标 → 「视频跟读」

- 用原生 `VideoView`（无需解码库），16:9 固定区域
- **手动打点切片段**：播到起点点一次「＋标一句」，播到终点点第二次 → 切出一段
- 片段列表可点播、循环、删除
- 循环播放时到终点自动跳回起点

### 为什么不做自动字幕切分
需要「视频 → 抽音轨 → PCM → ASR 逐句切分」：
- 抽音轨在纯 Java 里要 `MediaCodec` + `MediaExtractor` 手写
- 片段切分还要处理音视频同步

手动打点 5 秒能标一句，对「反复练同一段」这个真实需求反而更直接。

### v6.0（2026-11-08 傍晚）：全面本地化 + 自动字幕

## 核心转变：一切先本地
用户要求「往后都不走云，本地要能真正做到」。所以：

| 项 | v5.9 | v6.0 |
|---|---|---|
| 默认模型 | Qwen2.5 1.5B | **Qwen3 4B Instruct 2507** |
| 上下文 | 1024 | **2048** |
| 线程 | 4（cores/2） | 4（实测 6 线程抢带宽反而慢） |
| 模型源 | hf-mirror | **ModelScope（国内直连 14MB/s）** |
| 启动预热 | 无 | **有（1.4s 加载完成）** |
| 翻译 | 走云端 | **本地 4B 批量翻译** |

### 为什么 4B 而不是 7B/8B
用户明确「8b 以下，功耗要低」。Qwen3-4B-Instruct-2507 在指令跟随、JSON 输出、
中英互译上都超过同体积旧模型（2507 版去掉了 thinking，直接出答案，更适合翻译批改）。

### 实测数据（真机 Redmi / SD8Gen2 / 11.4GB）
```
加载：705ms（mmap，不预热首 token 要 1.5s）
预热：1.4s 完成 + 4 token 暖机
闲聊：~3s
翻译单句：6.8s → {"zh":"今天天气真不错。"}
批改单句：23.6s → {"fixed":"Yesterday I went to the park and liked it.","why":"..."}
批量翻译 3 句：~50s（一次调用，不是逐句）
```

### 踩到的坑（都很关键）
1. **4096 上下文会让 4B 在 11GB 机上 swap 到死**：KV cache 预分配 2GB，
   加上模型 2.4GB 直接把可用内存吃光，prefill 几分钟不出字。降到 2048 后正常。
2. **6 线程比 4 线程慢**：大核抢内存带宽，反而掉速。
3. **`askRaw` 要求「必须返回合法 JSON」会把纯文本结果吞掉**：
   整段译文任务返回的不是 JSON → 判定失败 → 静默返回空。改成「吐了字就交回去」。
4. **模型爱把「编号|译文」格式改成 JSON**：顺着它，直接要 `{"1":"...","2":"..."}`。
5. **MediaActivity 独立页不会自动 init ASR 引擎**：`ready()` 永远 false，
   识别被静默跳过。改成主动 init + 最多等 40 秒。

## 自动字幕（视频/音频）

流程：抽音轨（MediaCodec）→ 能量 VAD 切句 → 逐段 ASR → 缓存到本地

每句能做的事：
- `▶ 播这段` 按时间码播放对应片段（音频用 seekTo，视频跳独立播放页）
- `🔊 读` 本地 TTS 朗读
- `改文本` 识别错了直接改（改过的标记 edited，重识别不覆盖）
- `翻译` / `重译` 本地句库 → 4B 模型
- `翻译全部` 一次批量翻完（8 句一批）

导出：SRT（挂播放器）/ TXT（进 Anki）/ Markdown（留档，带时间码表）

历史记录：独立 MediaActivity，条目带「N 句 · M 词 · 已译 K」，点回可继续用。

## 音频跟读重做
v5.x 只显示「识别到 XXX」，没有评分也不能回放自己念的。现在：
- 导入即自动切句 + 识别（缓存进 audio.json）
- 每句可点播、可朗读、可选中练
- 录音后逐词对齐打分（对/漏/多）+ 语速 + 停顿 + 建议
- 能回放自己念的 vs 原声交替听

## 修掉的崩溃
- `MediaPanel` 里 `a.getColor(0)` → `Resources$NotFoundException: Resource ID #0x0`
- `MediaActivity` 未在 Manifest 注册前会 `Unable to start activity`

### v6.1（2026-11-08 夜）：识别模型可选 + 本地 LLM 纠错

## 问题
用户截图：视频原文 `We're off to a Statue of The Seven!`
ASR 输出：`We are up to a statue of the students.`
（tiny.en 把 off→up、Seven→students 听错）

## 两个解法，都做了

### 1. 识别模型可选（用户下载后自行切换）
「我的 → 离线语音 → 识别模型」现在有四档：

| 模型 | 体积 | 参数 | 定位 |
|---|---|---|---|
| NeMo CTC small | 46MB | 120M | 最快，准确度一般 |
| Whisper tiny.en | 103MB | 39M | 原默认，同音错多 |
| **Whisper base.en** | 153MB | 74M | **推荐**，错率明显下降 |
| Whisper small.en | 358MB | 244M | 更准，慢一些 |
| Whisper medium.en | 902MB | 769M | 最准，最慢 |

`voice.asr` 存选择；`OfflineAsrEngine.pickWhisper()` 按「用户选择 → 从大到小」
挑第一个已下载的，不会出现「选了但没生效」。

### 2. 本地 LLM 上下文件纠错（`engine/AsrFix.java`）
识别完自动跑一遍：把整批句子交给本地 4B，让它按上下文改掉同音错词。
原理是**语言先验推翻声学判据**：`statue of the students` 不是英语会出现的搭配，
而 `Statue of The Seven` 是有名专有名词。

实测（同一段音频，tiny.en 的原始输出）：
```
输入：We are up to a statue of the students.
输出：We are up to a statue of the seven.        ← students 改对了
输入：But whenever is really nice today.
输出：But whenever it is really nice today.      ← 补上了 it
```
`up → off` 没改（"up to" 本身合法，模型不敢动）—— 这一处必须靠大模型。

## base.en 实测对比
同一段测试音频，只换模型：

| 句 | tiny.en | base.en |
|---|---|---|
| 1 | But whenever is really nice today. | **The weather is really nice today.** |
| 2 | I want to go outside and take a walk. | 一致 |
| 3 | After now, I will read the book. | **After that** I will read a book. |

两处全对。所以「换大模型 + LLM 纠错」两条腿都要有：
大模型减少错误发生，LLM 兜住剩下的。

## UI
详情页新增「🤖 本地模型校对照（改同音错词）」按钮，可手动重跑；
识别完成时自动跑一次（下载模型后不需要额外操作）。
用户手动改过的句子（`edited=true`）不会被覆盖。

### v6.2（2026-11-09 上午）：音频跟读修好（之前是空白页）

## 症状
点「音频跟读」只有标题和「关闭」，内容一片空白 —— 看起来像功能没做。

## 两个真因（都不是"没做"，是显示不出来）

### 1. `AudioPanel.build()` 忘了把 root 塞进 ScrollView
```java
ScrollView sv = new ScrollView(a);
LinearLayout root = U.col(a);
// ... 所有内容都 addView 到 root ...
renderList();
return sv;          // ← root 从来没被加进去，返回的是空壳
```
改成 `sv.addView(root)` 才有内容。
**教训**：ScrollView 包 LinearLayout 的写法，最后一定要 addView。

### 2. 塞在 AlertDialog 里测不出高度
即使补了 addView，ScrollView 放进 `AlertDialog` 并给固定高度
（`0.7 * screenHeight`）后，它拿不到正确的 measure spec，内容仍然塌成 0。
`MediaPanel` 之前就是这个毛病，当时靠改独立 Activity 躲过去了。

所以 `AudioPanel` 也改成独立页 `ui/AudioActivity.java`：
- 正常窗口高度，ScrollView 能正确测量
- 返回键天然回上一页
- `onDestroy` 里释放 MediaPlayer，不会带着声音退到后台

### 3. 自动切句没触发
`if (sents == null) doSegmentQuiet();`
但 `sents` 初始化是 `new ArrayList<>()`（空列表，不是 null），条件永远不成立。
改成 `if (sents == null || sents.isEmpty())`。

## 现在音频跟读的完整能力
导入 → **自动切句 + 识别**（存 audio.json，下次不重算）
→ 每句显示文本 + 时间码 + 时长
→ 点句子：跳到该段并循环，A/B 自动设好
→ `读`：本地 TTS 念这句
→ `🎤`：跳到这句准备跟读
→ 录完：**逐词对齐打分**（对/漏/多 + 语速 wpm + 停顿 + 建议）
→ 可回放自己念的 vs 原声交替听

实测（测试音频 7.9 秒）：
```
The weather is really nice today.        00:00 - 00:02  2.1s
I want to go outside and take a walk.    00:02 - 00:05  2.9s
After that I will read a book.           00:05 - 00:07  1.8s
```
三句全对（用的是 base.en）。

### v7.0（2026-11-09）：写作批改重写（消灭误报 + 加语义层）

## 用户截图暴露的问题
原文 43 词，报「4 处可改进」，**4 处全是误报，真错误一个没抓到**：

| 判定 | 词 | 真相 |
|---|---|---|
| ❌ 误报 | `A lot of` | 标准英语 |
| ❌ 误报 | `jailbreak` | 技术词，词典没有 |
| ❌ 误报 | `calif's` | 专名 |
| ❌ 误报 | `sptm` | 技术缩写 |
| ✅ 漏报 | `ared` | 应为 `are` |
| ✅ 漏报 | `Asking` | 句中不该大写 |
| ✅ 漏报 | `mine team` | 应为 `my team` |

### 三个根因
1. **拼写检查是「词典查不到 = 错」**：把 jailbreak / sptm / calif 全标红。
   正确做法：查不到 → 先看白名单/复合词 → 再找编辑距离 ≤1 的候选，
   **找不到候选就不报**（宁可漏报，不可误报）。
2. **`a lot of` 被当成错误**：它是风格建议，不该扣分标红。
   现在把「错误」和「风格建议」分开，style 不扣分。
3. **完全没有语义层**：规则只能抓固定形态，`ared`、`Asking` 这类要理解整句。

### 重写内容（engine/WritingChecker.java，268 → 700 行）

**白名单**（约 300 个）：编程/系统/网络/通用缩写，jailbreak、kernel、syscall、
gguf、onnx、api、sdk、wifi… 直接跳过拼写检查。

**复合词判定**：`jailbreak` = `jail` + `break`，两段都在词典里 → 放过。

**拼写只报有候选的**：
```
查不到 → 白名单？→ 复合词？→ 编辑距离 ≤1 能找到词典词？
                                    ↓ 找到       ↓ 找不到
                              报「→ 正确形式」   不报
```

**新增 6 类规则**：
- 物主代词：`mine team` → `my team`（mine/yours/hers/theirs/ours 不能修饰名词）
- 主谓一致：`people is` → `people are`，`he are` → `he is`
- 句中大写：`Asking about` → 句中不该大写（排除专名/缩写/技术词）
- 手滑 ed：`ared` → `are`（查表，不瞎猜）
- a + 复数、双重比较级（`more better`）
- 标点：逗号后空格、结尾缺句号

**评分只算 error**：`a lot of` 这类 style 不再扣 6 分。

### 新增语义层（engine/WriteReview.java）
规则层毫秒出结果，模型层随后补上（异步，不阻塞界面）。

三档强度：
| 档位 | 行为 |
|---|---|
| 只改错 | 只动真正的语法/拼写错误，保留风格 |
| 标准 | 改错 + 明显不地道的表达 |
| 全面打磨 | 语法/搭配/用词/句式/逻辑都看 |

模型必须**指出原文片段**，找不到片段的结果直接丢弃（防瞎编）。
输出含总评 + 逐条 ✗原文/✓改写/中文说明 + **整段改写版**（可一键替换）。

### 实测（用户那段原文）

规则层（秒出）：
```
score=83  能看懂，2 处要改
语法|mine|mine 是名词性物主代词，不能修饰名词|my team
语法|ared|这个词形不成立，多打了 ed|→ are
```
**零误报**，`A lot of` / `jailbreak` / `sptm` / `calif's` 全部放过。

语义层（本地 4B，约 40 秒）：
```
summary=原文存在搭配错误和不自然的表达，经过修改后更符合英语母语者的表达习惯
items=3
搭配|A lots of people ared Asking about jailbreak progress.|A lot of people are asking about jailbreak progress.|'ared' 应为 'are'；'Asking' 应为 'asking'
表达|No major news yet.|There's still no major news.|更符合口语习惯
搭配|I still use mine team.|I'm still using my team.|'mine' 不能修饰名词
rewritten=A lot of people are asking about jailbreak progress. There's still no major news. I'm still using my team.
```

### 顺带
- 写作草稿持久化（`write.draft`）：切标签、退后台不丢
- `write-diag.txt` / `write-review.txt` 两个诊断文件，便于自动化校验
