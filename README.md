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
