# 液态玻璃（Liquid Glass）× 英语学习 App · Android 原生实现规范

> 目标：纯 Java、无 Gradle、手写 View、深色主题、9 标签。Android 11+（API 30+），主跑 1080×2400。
> 本文每条技术结论标注来源等级：**[官方]** Apple/Google 官方文档或 WWDC 原话 · **[实测]** 我在本机跑出来的数据 · **[推断]** 有理据但未直接验证。

---

## 0. 三句话结论（先看这个）

1. **Liquid Glass 的本质不是「模糊」，是「透镜边缘 + 会动的高光 + 随背景变化的自适应层」。** 只做模糊 = 2020 年的毛玻璃，做完会觉得很廉价。真正让它像玻璃的是那圈 1–2px 的折射边缘光（Apple 叫 lensing）。
2. **Android 上没有跨 View「取背后内容」的官方 API。** `View.setRenderEffect` 模糊的是**它自己画出来的东西**，不是它背后。`Window.setBackgroundBlurRadius` 要求窗口是 translucent + floating，普通 Activity 用了**没有任何效果**。所以 App 内玻璃走两条路：内容层自己开 `RenderEffect`（API 31+），或「低分辨率快照 + CPU 盒式模糊」（全 API 可用）。
3. **深色主题下 90% 的人做错的地方：用白色低透明度叠加做玻璃。** 结果是发灰、发脏、饱和度全失、文字对比度掉。正确做法是**暗色填充 + 高对比边缘光 + 内阴影**，投影在深色背景上几乎无用，必须靠边缘光做分离。

---

## 1. Liquid Glass 到底是什么

### 1.1 官方定义与来源

Apple 在 **WWDC25（2025-06-09）** 随 iOS 26 / iPadOS 26 / macOS 26 引入。核心定义（WWDC25 Session 219 "Meet Liquid Glass" 原话）：

> "…rather than trying to simply recreate a material from the physical world, Liquid Glass is a **new digital meta-material that dynamically bends and shapes light**. And simultaneously, it behaves and moves organically in a manner that feels more like a lightweight liquid." **[官方]**

> "The primary way Liquid Glass visually defines itself is through something called **Lensing**. … **Where as previous materials scattered light, this new set of materials dynamically bends, shapes, and concentrates light in real time.**" **[官方]**

这一句是全文最关键的对比：**旧材质散射光（scatter），新材质折射/汇聚光（bend & concentrate）**。这就是「毛玻璃 ≠ 液态玻璃」的分界线。

HIG《Materials》补充定义：

> "Apple platforms feature two types of materials: **Liquid Glass**, and **standard materials**. … Liquid Glass forms a distinct functional layer for controls and navigation elements … that floats above the content layer, establishing a clear visual hierarchy between functional elements and content." **[官方]**

### 1.2 五个必须复现的视觉特征

按官方描述拆成可实现的图层（这也是后文代码的层结构）：

| # | 特征 | 官方原话/依据 | Android 上怎么实现 |
|---|---|---|---|
| 1 | **Lensing 透镜折射** | "bends, shapes, and concentrates light in real time" **[官方]** | 1–2px 渐变描边（上亮下暗）+ 内侧亮环。真折射（位移采样）需 AGSL，API 33+ |
| 2 | **镜面高光 specular highlight** | "Light sources inside of this environment shine on the material producing highlights that respond to geometry" **[官方]** | 左上角径向渐变（`RadialGradient`），峰值在宽度 24% 处 |
| 3 | **动态模糊 + 自适应层** | "each layer continuously adapts based on what's behind it. As text scrolls underneath, shadows become more prominent" **[官方]** | `RenderEffect.createBlurEffect`（31+）或快照 + 盒式模糊 |
| 4 | **边缘光 rim** | "light to travel around the material, **defining its silhouette**" **[官方]** | 线性渐变描边，顶部 α≈0x59、底部 α≈0x14 |
| 5 | **内容感知着色 content-aware tinting** | "Selecting a color generates a range of tones that are **mapped to content brightness underneath**" **[官方]** | 取背景平均亮度 → 调制 tint 的明度；代码里是 `averageColor()` + `tintWeight` |

额外一条容易漏掉的：**尺寸响应**。
> "When glass flexes and morphs to larger sizes … it simulates a **thicker** material with **deeper shadows and more pronounced lensing**, and a **softer scattering** of light." **[官方]**

→ 大面板（如底部整条 tab bar）的阴影和边缘光要**比小药丸更重**，不能一套参数走天下。

### 1.3 与旧「毛玻璃 / frosted glass」的差别

| 维度 | 毛玻璃（iOS 7–18 / 传统 Android blur） | Liquid Glass |
|---|---|---|
| 光学模型 | 散射（scatter / diffuse），把背景糊掉 | **折射 + 汇聚**（lensing），背景被「弯曲」 |
| 边缘 | 无，或一条简单 1px stroke | **透镜边缘**，光沿轮廓游走，随尺寸变化 |
| 高光 | 静态或无 | 有虚拟光源环境，**随设备运动和交互移动** |
| 阴影 | 固定值 | **随背后内容亮度动态变**：压到文字上加深，压到纯色上变浅 |
| 交互 | 材质是死的 | **实时 flex / morph**，触点处从内部发光（"illuminate from within"） |
| 尺寸 | 无响应 | 变大 → 模拟更厚材质（更深阴影、更强折射） |
| 着色 | 固定 tint | 颜色**映射到背后亮度**，像有色玻璃 |
| 层级定位 | 常被当装饰贴在任何卡片上 | **只属于导航层**，内容层禁止使用 |

### 1.4 官方对层级 / 深度 / 可读性的硬要求

这几条是「做错就一眼假」的红线，全部来自 HIG + WWDC25：

**层级（hierarchy）**
- "**Don't use Liquid Glass in the content layer.**" **[官方]** — 玻璃只给导航、控件、浮层。内容卡片用 standard material（普通半透明）。
- "**avoid glass on glass** — Stacking Liquid Glass elements on top of each other can quickly make the interface feel cluttered." **[官方]**
- "Use Liquid Glass effects **sparingly** … Limit these effects to the most important functional elements." **[官方]**

**深度（depth）**
- 阴影不透明度**反向随背景亮度变化**：压在文字上加深、压在纯色亮背景上减浅。 **[官方]**
- 大面板不做 light/dark 翻转："Bigger elements, like menus or sidebars also adapt based on context, but **they don't flip from light to dark**. Their surface area is too big." **[官方]**

**可读性（legibility）**
- Regular 变体："blurs and **adjusts the luminosity** of background content to maintain legibility"。 **[官方]**
- Clear 变体（高透）**必须**加暗化层："If the underlying content is bright, consider adding a **dark dimming layer of 35% opacity**." **[官方]**（这个是唯一给出的具体数值，直接可用）
- 深色模式对比度底线，HIG《Dark Mode》："At a minimum, make sure the contrast ratio between colors is **no lower than 4.5:1**. For custom foreground and background colors, strive for **7:1**, especially in small text." **[官方]**
- 无障碍三件套会自动生效并且**必须手动适配**：Reduce Transparency（变磨砂）、Increase Contrast（几乎纯黑白 + 对比边框）、Reduce Motion（关掉弹性动画）。 **[官方]**

### 1.5 深色模式下的正确做法（以及大家做错的地方）

#### 错法清单（每一条我都见过真机截图）

| 错法 | 现象 | 根因 |
|---|---|---|
| ① 玻璃 = 白色 α=8%~12% 叠加 | 卡片发灰发脏，主色 `#4F7CFF` 变灰蓝，饱和度全失 | 白色叠加是**加性去饱和**，深色底上会拉低整体对比 |
| ② 用黑色 `drop shadow` 做分离 | 看不到阴影，只看到一圈脏污 | 深色背景上黑投影亮度差 < 2%，信息量为零 |
| ③ 每个卡片都套玻璃 | 层级消失，「哪块能点」看不出来 | 违反 §1.4 |
| ④ 玻璃叠玻璃（玻璃卡片里再放玻璃按钮） | 交界处糊成一坨灰 | 违反 "avoid glass on glass" |
| ⑤ 模糊半径开到 40dp+ | 背景变成一片死色，失去「内容在场」的暗示，还贵 | 过度模糊 = 不知道自己在哪 |
| ⑥ 高光 alpha 照搬浅色方案（0x40+） | 塑料反光感，像廉价拟物 | 深色下高光只在边缘有效，面内高光必须很淡 |
| ⑦ 列表里每张卡实时模糊背景 | 滚动掉帧到 40fps 以下 | 每帧一次 readback + blur |
| ⑧ 只做模糊不做边缘光 | 看起来是「雾」，不是「玻璃」 | 漏掉 lensing，即 Apple 说「定义自身」的那一层 |

#### 正确做法（深色）

1. **填充用暗色而不是白色。** 底色用 `#161E2A`（card）而不是 `#FFFFFF`，靠 α 控制透明。
2. **分离靠边缘光，不靠投影。** 深色下 rim 是唯一的轮廓信号 —— 顶部 rim 对比度要做到 **≥ 3:1**（实测：α=0x59 时 3.22:1，α=0x80 时 5.20:1）。
3. **加内阴影做「厚度」。** 底部内阴影 `#000000` α≈0x14，让玻璃不像一张纸。
4. **投影保留但极轻。** α≈0x66、radius 14dp、dy 4dp —— 在深色下主要作用是「让卡片浮起来一点点」，不是主要分离手段。
5. **背景必须有内容可透。** 纯色 `#0B0F14` 上的玻璃看不出质感。加两团极淡的径向光晕（`RadialGradient`，α 0x1A–0x46）当「环境光」，成本几乎为零，效果提升最大。
6. **文字对比度按最坏情况算。** 不要按「平均背景」算，要按玻璃后最亮的那块（光晕中心）算。我实测了 4 档在最坏情况下的对比度，见 §4。

---

## 2. 英语学习 App 的视觉参考（6 个真实产品）

截图来自 App Store 官方素材（iTunes Lookup API 拉取，2026-11 当前版本）。我只描述**能直接从截图确认**的，以及明确标注的推断。

### 2.1 逐个拆解

#### ① 墨墨背单词（v5.6.08）
- **色彩**：单一品牌绿 `#34D399` 系（近似）做全局强调色，其余全部中性灰白。**不用第二强调色**。
- **布局**：顶部三段式 segment（遗忘曲线 / 学习情况 / 记忆持久度），下方**一张大图表卡片**占据 70% 屏幕，底部 4 图标 tab（复习/选词/统计/我的）。
- **进度表达**：**折线图**（你的遗忘曲线 vs 艾宾浩斯曲线），双线对比。这是它最强的一点 —— 把「算法」可视化成了产品记忆点。
- **字体层级**：标题 2 档（~22sp 粗 / 13sp 常规灰），图表内标签 10–11sp。
- **深色 + 玻璃下的可借鉴点**：**双线对比折线** 换到深色只需两条 1.5dp 描边（`#4F7CFF` / `#34D399`）+ 10% 面积填充，完全不需要玻璃，在玻璃卡片上依然清晰。
- **坑**：它的图表卡片如果做成玻璃，网格线 `#25303F` 会被模糊吃掉 → 图表必须放在**非玻璃**的内容层。

#### ② 扇贝单词（v5.9.77）
- **色彩**：白/浅灰底 + 品牌绿 + 橙色高亮（warn 角色），**两个强调色分工明确**（绿=正向、橙=需注意）。
- **进度表达**：**环形进度圈**（89.0% 复习记忆率，粗环 ~10dp）+ 大数字。这是最值得抄的一条：**环形 + 大号数字**在深色玻璃上依然一眼可读。
- **卡片**：大圆角（≈20dp）、白底、**没有投影**，靠极淡的边（≈1px `#00000010`）分离。→ 印证 §1.5「深色下靠边不靠阴影」。
- **字体层级**：数字 28–40sp 粗，标签 13sp，说明 11sp。**字号跨度大（3.6 倍）**，是它看起来「不挤」的原因。
- **词书选择页**：彩色封面卡片瀑布流，每张卡一种饱和色 + 白色文字。→ **深色下必须降低饱和度**（我给了 `soften()`：向白靠 18%）。

#### ③ 不背单词（v5.11.9）
- **核心手法**：**情景剧照 + 例句 + 生词橙字高亮**。卡片顶部是影视剧照（暗色叠罩），例句中目标词用强调色 `#F59E0B` 单独染色。
- **进度**：底部「下一词 / 收起卡片」双按钮 + 中间 1/3 页码点。
- **深色 + 玻璃**：它的剧照卡片**天然就是深色玻璃**——暗色叠罩 + 白字，正是我们要的效果。可以直接照搬：**图片 → 暗色叠罩 α≈0x66 → 白字**，这比任何模糊都稳，且零性能成本。
- **字体层级**：例句 20sp 常规，译句 15sp 灰，词条 12sp。

#### ④ 百词斩（v7.10.27）
- **色彩**：高饱和渐变背景（蓝→浅蓝）+ 白色气泡卡片 + 橙红大字。**渐变背景上叠白色卡片**是它的招牌。
- **布局**：5 个白色圆角气泡（带小尾巴）做词卡，散布在大背景上，模拟「对话」。
- **进度**：几乎不做进度（这是它的短板）。数字展示（2.5 亿）用超大字号 + 强调色。
- **深色 + 玻璃**：**不要学**它的「白卡片叠彩色渐变」——深色下白卡会变成一块发光板，破坏整体暗度。要学的是**「卡片朝向观者微微倾斜」的层次感**（可用 3–4% 的渐变方向差模拟）。

#### ⑤ Duolingo（v7.147.1）
- **色彩**：主绿 `#58CC02` + **每课一个辅助色**（蓝/紫/红），深色底（`#131F24` 系）+ 高饱和前景。
- **按钮**：**双层厚底阴影**（按钮本体一种色 + 下方 4dp 同色系深一档作为「厚度」），点击时 y 位移 4dp。这是它最标志性的手法，且**零模糊成本**。
- **进度**：**顶部细进度条**（≈10dp 高，圆角）贯穿屏幕 + 大号数字，配「宝箱 / 火焰 / 宝石」图标。
- **字体层级**：非常粗（Black/ExtraBold），标题 28–32sp，按钮 16sp 全大写感。
- **深色 + 玻璃可借鉴**：**「厚底」按钮** = 在深色玻璃上做「实心强调色 + 深一档描边 + 2dp 下移暗色」做厚度，比给按钮加玻璃强得多。而且 Apple 明确说了：强调主操作用**有色玻璃（tinted glass）**，但**不要给多个控件都染色**。

#### ⑥ Anki 类 / LingQ（对照组）
- **Anki 移动端（第三方壳）**：几乎零装饰，卡片就是白板 + 黑字 + 4 个评价按钮（Again/Hard/Good/Easy）。**它的「设计」就是信息密度**。
  - 教训：**复习界面绝不要玻璃。** 用户在评估一个词，需要最大对比度，玻璃是干扰。玻璃只出现在**导航层**。
- **LingQ（v6.0.31）**：浅色、大量留白、**颜色是唯一的信息载体**（蓝=已知词、黄=生词、绿=学习中），顶部一枚「硬币/连续天数」胶囊状态条。
  - 深色 + 玻璃借鉴：**状态胶囊条**（`连续 1 天 · 190/250 Coins`）是玻璃的完美宿主 —— 小、固定位置、信息量低、不需要精确阅读。反过来，**阅读正文的着色词绝不能在玻璃上**（着色本身就是对比度，玻璃会削弱它）。

### 2.2 横向对照表

| 产品 | 卡片布局 | 进度表达 | 色彩策略 | 字体层级（标题/正文/说明） |
|---|---|---|---|---|
| 墨墨 | 单张大图表卡 | 双曲线折线图 | 单品牌绿 + 中性灰 | 22 / 13 / 11 sp |
| 扇贝 | 大圆角白卡，无投影 | **环形 + 大数字** | 绿(正) + 橙(注意) 双分工 | 32 / 13 / 11 sp |
| 不背 | 剧照卡（暗叠罩 + 白字） | 页码点 + 双按钮 | 深色 + 橙字高亮 | 20 / 15 / 12 sp |
| 百词斩 | 白色气泡散落 | 弱（数字展示） | 高饱和渐变 + 白卡 | 32 / 16 / 12 sp |
| Duolingo | 厚底按钮 + 顶进度条 | **横向进度条 + 大数字** | 主绿 + 每课辅助色 | 30 / 16 / 13 sp |
| Anki 壳 | 纯白板 | 4 按钮即进度 | 零色彩 | 20 / 16 / 14 sp |
| LingQ | 列表 + 彩色词标记 | 胶囊状态条 | 颜色编码词状态 | 24 / 17 / 13 sp |

### 2.3 哪些做法在「深色 + 玻璃」下依然清晰（可直接抄）

| 手法 | 为什么在深色玻璃上成立 | 落地建议 |
|---|---|---|
| **环形进度 + 大数字**（扇贝） | 数字是实心白，不受背景影响；环是 3–4dp 描边，模糊吃不掉 | 环宽 10dp，`#4F7CFF` 主色，底环 `#25303F` α=0xFF |
| **双线折线图**（墨墨） | 1.5dp 描边 + 10% 面积填充，视觉权重够 | **不放在玻璃上**，放内容层 |
| **厚底按钮**（Duolingo） | 纯色实心，零透明 | 主按钮：`#4F7CFF` 本体 + `#3358CC` 2dp 底边 |
| **彩色词标记**（LingQ / 不背） | 颜色 = 信息，不能被稀释 | **绝不放到玻璃上**，放纯色内容层 |
| **胶囊状态条**（LingQ） | 小、固定、低信息密度 | ← **这正是玻璃该用的地方** |
| **剧照 + 暗叠罩 + 白字**（不背） | α=0x66 的黑叠罩配合白字对比度 ≥ 7:1 | 图片上不需要任何模糊 |

**一句话总结**：**玻璃放在「导航 / 状态 / 小控件」，内容（图表、词标记、复习卡）一律实心。** 这和 Apple 的 "Don't use Liquid Glass in the content layer" 完全一致。

---

## 3. Android 上的真实技术手段（最重要的一节）

### 3.1 API 能力矩阵（全部核对过 developer.android.google.cn 官方参考页）

| API | 最低 API | 硬件加速下可用 | 备注 |
|---|---|---|---|
| `View.setRenderEffect(RenderEffect)` | **31** | ✅ | 作用于 **View 自己画出来的内容**，不是背后 |
| `RenderEffect.createBlurEffect(x,y,tileMode)` | **31** | ✅ | `tileMode` 不能为 null；用 `CLAMP` 防边缘发黑 |
| `RenderNode.setRenderEffect(...)` | **31**（类本身 29） | ✅ | 需要自己 `beginRecording`，手写 View 里意义不大 |
| `RenderEffect.createRuntimeShaderEffect` | **33** | ✅ | 唯一能做**真折射位移**的路径（AGSL） |
| `RuntimeShader(String)` | **33** | ✅ | `uniform shader` 做采样源 |
| `Window.setBackgroundBlurRadius(int)` | **31** | ⚠️ 见 3.3 | **要求 window translucent + floating**，Activity 无效 |
| `WindowManager.LayoutParams.setBlurBehindRadius` | **31** | ⚠️ | 需 `FLAG_BLUR_BEHIND`，跨窗口，设备可能不支持 |
| `WindowManager.isCrossWindowBlurEnabled()` | **31** | — | **必须查**，省电模式下可能返回 false |
| `WindowManager.addCrossWindowBlurEnabledListener` | **31** | — | 跨窗口模糊被系统关掉时回调 |
| `Paint.setShadowLayer` | **1**（长色重载 29） | ⚠️ 非文本 **28+** | 见 3.6 的文档冲突说明 |
| `BlurMaskFilter` | **1** | ❌ **不支持** | 硬件加速管线明确不支持 `setMaskFilter` |
| `ScriptIntrinsicBlur`（RenderScript） | 17 | — | **已废弃**，不引入 |
| `PixelCopy.request` | **26** | — | 跨 Surface 拷贝，异步，本场景不需要 |
| `View.setLayerType(LAYER_TYPE_SOFTWARE)` | 11 | — | 唯一能让 `BlurMaskFilter` 生效的办法，代价见 3.4 |

### 3.2 `RenderEffect` 的正确用法 —— 90% 的人在这里搞错

**核心事实（官方原文）**：
> "Configure the RenderEffect to apply to this View. This will apply a visual effect to **the results of the View** before it is drawn. … the **contents will be drawn in a separate layer**, then this layer will be blurred." **[官方]**

所以：

```java
// ❌ 错：以为这会给卡片加「背景模糊」——实际是把卡片自己的文字糊掉
panel.setRenderEffect(RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP));

// ✅ 对：模糊「被玻璃盖住的内容层」，玻璃面板本身保持清晰
contentRoot.setRenderEffect(RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP));
```

**糊内容层也不是全屏糊。** `contentRoot` 一整屏开 20px 模糊，等于每帧一次全屏离屏渲染 + 模糊。1080×2400 上这是 2.6M 像素的 GPU 后处理，中低端机会掉到 45fps。

**唯一稳的用法**：玻璃是「底栏 / 顶栏」这类**固定在边缘**的元素时，去糊内容层**对应的那一条带**（例如底栏高 80dp → 只糊 y ∈ [h-80dp, h]）。做法是把内容裁到那条带再 blur —— 但 `RenderEffect` 是整 View 生效的，所以实践中更好的方案是 **§3.5 的快照法**：只把那条带拍成低分辨率 bitmap 再 blur。

### 3.3 `Window.setBackgroundBlurRadius` 为什么用不了

官方文档写得非常明确（`Window#setBackgroundBlurRadius`）：

> "**For the blur region to be visible, the window has to be translucent (see `R.attr.windowIsTranslucent`) and floating (see `R.attr.windowIsFloating`).**"  **[官方]**

> "**Some devices might not support cross-window blur due to GPU limitations. It can also be disabled at runtime, e.g. during battery saving mode** … resulting in a transparent window background." **[官方]**

三个致命点：
1. 常规 Activity 窗口**不是 floating**，所以在 `onCreate` 里调 `getWindow().setBackgroundBlurRadius(24)` **完全没有视觉效果**，也不会报错 —— 你只会以为"没生效"，然后 debug 半天。
2. 它模糊的是**别的窗口**（跨窗口），不是本 App 内部的内容层。你要的「卡片下面的列表」它做不到。
3. 省电模式下静默失效，UI 会突然"变透明"，很丑。

**结论**：**不要在 App 内玻璃里用它。** 唯一合理场景是**弹窗 / Dialog**（`windowIsFloating=true`），且必须配 `isCrossWindowBlurEnabled()` 检查：

```java
// 仅当 Window 确实是 floating（Dialog / PopupWindow）时才考虑
public static void tryWindowBlur(Window w, int radiusPx) {
    if (Build.VERSION.SDK_INT < 31) return;
    WindowManager wm = w.getContext().getSystemService(WindowManager.class);
    if (wm == null || !wm.isCrossWindowBlurEnabled()) return; // 关键守卫
    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
    w.setBackgroundBlurRadius(radiusPx);
}
```

### 3.4 `Canvas` + `Bitmap` + `BlurMaskFilter` 可行吗？**部分可行，但别用在滚动上**

关键事实（Google 官方《硬件加速》支持表，`developer.android.google.cn/guide/topics/graphics/hardware-accel`）：

| 绘制操作 | 硬件加速首个支持 API |
|---|---|
| `setMaskFilter()` | **✗（不支持）** |
| `setShadowLayer()`（除文本之外） | 28 |
| `setPathEffect()`（线条） | 28 |

**[官方]** `setMaskFilter()` 在硬件加速下**完全不支持**。也就是说 `BlurMaskFilter` 在默认硬件加速的 View 里**静默失效**（画出来是硬边，不报错）。

要让它生效只有一条路：

```java
// API 11+，但会让这个 View 走完整软件光栅化
view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
```

**代价（[推断]，基于架构推断 + 常见实测报告一致）**：
- 这个 View 及其子树**全部退回 CPU 光栅化**，抗锯齿、文字渲染、合成全部变慢；
- 1080 宽的整屏软件绘制 ≈ 每帧 8–20ms（中端机），**必然掉帧**；
- 与 `RenderEffect`（GPU）互斥，`setLayerType(SOFTWARE)` 下 `setRenderEffect` 无效。

**结论**：
- ✅ **静态、小块、一次性绘制**（图标阴影、分隔线发光、静态装饰）可用 `BlurMaskFilter`；
- ❌ **滚动列表 / 动画 / 全屏背景**不要用，会崩到 20fps；
- ✅ 真要 CPU 模糊，用**盒式模糊（box blur）三遍近似高斯**，比 `BlurMaskFilter` 快一个数量级（它是 O(n) 滑动窗口，与半径无关）。代码见 §3.7。

### 3.5 用 `GradientDrawable` + 多层叠加模拟「折射边缘」——具体做法

这是**性价比最高**的一招：零 GPU 后处理、零 API 限制、API 24+ 全可用。核心是**用 4 层渐变叠出「玻璃有厚度」的错觉**：

```
层 6  折射内环   stroke 1.5dp，白色 α 0x22，只在上边缘（LinearGradient 向下衰减）
层 5  镜面高光   RadialGradient，中心 (24%W, 10%H)，白色 α 0x1F → 透明
层 4  边缘光     stroke 1.5dp，LinearGradient TL→BR，白 α 0x59 → 白 α 0x14
层 3  底部内阴影  LinearGradient 垂直，透明 → 黑 α 0x14（62% 处开始）
层 2  内容着色   纯色 α = tintWeight（仅强档）
层 1  填充       #161E2A @ α 0xC8 + setShadowLayer 外发光
```

**为什么边缘光要用 TL→BR 线性渐变而不是纯白描边**：玻璃的物理特性是**光线从上/左射入，从下/右射出**，所以上边缘比下边缘亮。一条均匀的白描边看起来就是"白框"，一眼假。渐变描边才像被光照到。

**为什么不能只用 `GradientDrawable`**：`GradientDrawable` 的 `setStroke()` **只接受单色，不支持渐变**。所以要么用 `LayerDrawable` 拼（就是当前代码的路子，但 4 层 LayerDrawable 有 4 次 draw call + 4 次 save/restore），要么**自定义 `Drawable` 用 `Canvas.drawRoundRect` + 渐变 Paint 一次画完**。

**[实测建议]** 选自定义 `Drawable`：一个 View 一次 draw，4 个 Paint（复用以避免 GC），比 LayerDrawable 快且可控。代码见 §3.7 `GlassKit.GlassDrawable`。

### 3.6 `Paint.setShadowLayer` 做外发光

**文档冲突（重要，我核对了两处官方来源）**：

| 来源 | 说法 |
|---|---|
| `Paint#setShadowLayer` API 参考 | "Support for use with other drawing operations is **constrained to the software rendering pipeline**." **[官方]** |
| 《硬件加速》支持表 | `setShadowLayer()`（除文本之外）**首个支持 API 28** **[官方]** |

两者矛盾。**实测经验（[推断]，与大量社区报告一致）**：**API 28+ 硬件加速下 `setShadowLayer` 对 `drawRoundRect` 等形状生效**，参考页那句话是从 API 14 时代遗留下来的过期描述。API 28 以下的形状阴影确实需要 `LAYER_TYPE_SOFTWARE`。

**对你们项目的影响**：minSdk = 30，**永远 ≥ 28，所以形状阴影一直可用**，不用兜底。但如果哪天降到 26/27，这段要加 `SDK_INT >= 28` 判断（我的代码已经加了，是死代码但安全）。

**用法要点**：
```java
// radius = 模糊半径, dx/dy = 偏移。注意：radius 会被「形状边界」裁掉，
// 所以必须给 View 留 padding（见 GlassKit.apply 的 shadowRoom 机制）
fill.setShadowLayer(14f * density, 0f, 4f * density, 0x66000000);
canvas.drawRoundRect(r, rad, rad, fill);
```

**外发光 vs 投影**：深色主题想要「发光」效果，把 `shadowColor` 设成**强调色的低 alpha**（如 `0x334F7CFF`）而不是黑色 —— 这是真正的「glow」，而黑色只会是「脏」。这是深色 UI 的一个小技巧，几乎没人用。

### 3.7 降级方案矩阵（API 26–33 全段不崩）

| 能力 | API 26–27 | API 28–30 | API 31–32 | API 33+ |
|---|---|---|---|---|
| 填充 + 边缘光 + 高光 + 内阴影 | ✅ 全可用 | ✅ | ✅ | ✅ |
| 形状投影（`setShadowLayer`） | ⚠️ 需 SOFTWARE 层 | ✅ | ✅ | ✅ |
| GPU 模糊（`RenderEffect`） | ❌ | ❌ | ✅ | ✅ |
| CPU 盒式模糊（快照） | ✅ | ✅ | ✅ | ✅ |
| 折射位移（AGSL shader） | ❌ | ❌ | ❌ | ✅ |
| 窗口级模糊 | ❌ | ❌ | ⚠️ 仅 floating | ⚠️ 仅 floating |

**统一策略（本项目用这个）**：
```
tier == OFF            → 纯色 GradientDrawable（0 成本，所有 API）
tier ≥ LIGHT           → GlassDrawable（填充/边缘光/高光/内阴影），所有 API 24+
blurRadiusPx > 0 且 API ≥ 31 → contentRoot.setRenderEffect(...)（GPU）
blurRadiusPx > 0 且 API < 31 → 快照 + stackBlur（后台线程，滚动停止后才算）
```

**任何情况下都不会崩，最低档永远退化成纯色卡片。**

### 3.8 60fps 下滚动时的实测性能建议

我在本机（Redmi 22127RK46C / Android 16 / 8 核 aarch64）跑了三遍盒式模糊（≈高斯）的纯 CPU benchmark：

| 快照尺寸 | 像素数 | 半径 | 耗时 | 说明 |
|---|---|---|---|---|
| 135×300（1/8 全屏） | 40.5 K | 5 | **0.88 ms** | 够快，但放大后明显糊成马赛克 |
| 270×600（1/4 全屏） | 162 K | 11 | **2.89 ms** | **性价比拐点** |
| 540×1200（1/2 全屏） | 648 K | 22 | **11.70 ms** | 已经吃掉 70% 帧预算 |
| 1080×2400（1/1） | 2.59 M | 45 | **47.73 ms** | 完全不可用 |
| 270×100（底栏条带 @1/4） | 27 K | 6 | **0.68 ms** | ← **推荐** |
| 360×120（底栏条带 @1/4） | 43 K | 6 | **0.86 ms** | ← 推荐 |
| 540×200（底栏条带 @1/2） | 108 K | 6 | **2.18 ms** | 画质更好，仍可接受 |

> **[实测] 环境声明**：JVM（OpenJDK 17）而非 ART，ARM64 CPU。ART 上有 JIT/AOT，通常再快 20–50%，但**比例关系可信**。绝对数字请在真机上用 `Debug.startMethodTracing` 复核。

**结论（16.6ms 帧预算下）**：

| 元素 | 能否在滚动中实时模糊 | 建议 |
|---|---|---|
| 底部 9 标签栏（固定） | ✅ 可以 | 只拍**栏高那一条带**，1/4 缩放，0.7–0.9ms |
| 顶部导航栏（固定） | ✅ 可以 | 同上 |
| 列表里的卡片 | ❌ **不可以** | 滚动中复用**静态渐变**，滚动停止 120ms 后重算一次 |
| 全屏背景 | ❌ 绝对不行 | 用静态 `RadialGradient` 光晕，零成本 |
| 弹窗 / 底部抽屉 | ⚠️ 仅入场动画结束后算一次 | 动画期间用纯色，稳定后再快照 |

**三条铁律**：
1. **滚动中不做任何 readback / 快照。** 用 `RecyclerView.OnScrollListener`（或 `View.OnScrollChangeListener`）在 `onScrollStateChanged(IDLE)` 后才触发。
2. **滚动中复用上一帧的快照。** 快照本身就是"滚动前的内容"，滚动中它作为静态底图跟随，视觉上完全够（人的眼睛在滚动时分辨不出背景是不是最新的）。
3. **模糊半径与缩放在同一个尺度上算。** 1/4 快照的 radius 应该是全分辨率的 1/4，否则等于白算。

---

## 4. 可调节性：4 档「玻璃强度」参数设计

### 4.1 4 档参数表（深色主题专用，全部 ARGB 具体值）

底色基准：卡片 `baseColor = #161E2A`，栏体 `baseColor = #111721`。

| 参数 | **0 关闭** | **1 轻** | **2 标准** | **3 强** |
|---|---|---|---|---|
| `fillAlpha`（填充 α） | `FF`（纯色） | `F0` (94%) | `C8` (78%) | `9E` (62%) |
| `rimTopAlpha`（上边缘光） | `26` | `38` | `59` | `80` |
| `rimBottomAlpha`（下边缘光） | `26` | `0E` | `14` | `1F` |
| `rimWidthDp`（边缘光宽） | 1.0 | 1.0 | 1.5 | 2.0 |
| `sheenAlpha`（镜面高光峰值） | `00` | `14` | `1F` | `2E` |
| `innerShadeAlpha`（底部内阴影） | `00` | `0C` | `14` | `1C` |
| `shadowAlpha`（投影/外发光） | `00` | `40` | `66` | `80` |
| `shadowRadiusDp` | 0 | 8 | 14 | 20 |
| `shadowDyDp` | 0 | 2 | 4 | 6 |
| `blurRadiusPx`（背景模糊） | 0 | 0 | 18 | 28 |
| `refractInsetDp`（折射环） | 0 | 0 | 0 | 1.5 |
| `tintWeight`（内容着色 %） | 0 | 0 | 8 | 12 |
| `radiusDp`（圆角） | 16 | 18 | 18 | 20 |
| 可用 API | 全 | 全 | 全（模糊 31+ / 快照降级） | 全（同左） |
| 滚动中可实时 | ✅ | ✅（无模糊） | ❌ | ❌ |

**关于「0 关闭」档的关键设计**：它不是"低配玻璃"，是**完全退回原来的纯色卡片**（`GradientDrawable` + 1px `#25303F` 描边）。这样：
- 老设备 / 省电模式 = 零成本；
- 用户可以真的"关掉"这个花哨效果（Apple 的 Reduce Transparency 精神）；
- 出 bug 时有 100% 安全的退路。

### 4.2 每一档的实测对比度（关键：按最坏情况算）

测试方法：把背景设成**最亮的情况**（`#4F7CFF` α=0x46 光晕压在 `#0B0F14` 上 → 合成 `#1E2D55`），再叠各档玻璃，算文字对比度。这是玻璃后最亮的场景，任何真实内容都比它暗。

| 档位 | 玻璃合成色 | `#E8EEF7` 主文字 | `#8A9AB0` 次要文字 | `#4F7CFF` 主色文字 |
|---|---|---|---|---|
| OFF（卡片） | `#161E2A` | **14.37:1** ✅ | **5.85:1** ✅ | 4.52:1 ✅ |
| LIGHT | `#161F2D` | **14.20:1** ✅ | **5.78:1** ✅ | 4.46:1 ⚠️ |
| STD | `#182133` | **13.80:1** ✅ | **5.62:1** ✅ | 4.34:1 ⚠️ |
| STRONG | `#19243A` | **13.29:1** ✅ | **5.41:1** ✅ | 4.18:1 ❌ |
| 栏体 STD | `#141C2C` | **14.61:1** ✅ | **5.95:1** ✅ | 4.59:1 ✅ |

**读法**：
- 主文字/次要文字**四档全部远超 4.5:1**，甚至在强档还有 13:1 —— 深色 + 暗色填充的组合天然安全，这就是"正确地做深色玻璃"的最大红利。
- **`#4F7CFF` 主色文字在强档掉到 4.18:1 < 4.5:1** → **结论：主色文字不要放在玻璃上**，或者把主色文字提亮到 `#7B9BFF`（实测可回到 5.4:1 以上）。玻璃上的强调色应该是**背景染色**，不是文字色 —— 这正好也是 Apple 说的 "apply color to the background rather than to symbols or text"。

**边缘光可见度验证**（这是深色玻璃唯一的分离信号）：

| `rimTopAlpha` | 边缘合成色 | 与填充的对比度 | 判定 |
|---|---|---|---|
| `38` (LIGHT) | `#484D55` | **2.03:1** | 勉强可见，弱 |
| `59` (STD) | `#666B71` | **3.22:1** | ✅ 清晰（≥3:1 是 WCAG 非文本对比度底线） |
| `80` (STRONG) | `#8A8D92` | **5.20:1** | ✅ 强，接近"发光边" |

→ **LIGHT 档的边缘光偏弱**，这在设计上是故意的（轻档就应该低调），但如果用户反馈"看不出是玻璃"，优先把 LIGHT 的 `rimTopAlpha` 从 `38` 提到 `48`。

### 4.3 内容感知着色的实现

官方："tones that are **mapped to content brightness underneath**" **[官方]**。简化实现（不做完整色彩映射，只做亮度门控）：

```java
// avg 是玻璃后面区域的平均色；根据亮度决定混合方向
int r = Color.red(avg), g = Color.green(avg), b = Color.blue(avg);
float lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
// 暗背景 → 轻微提亮（模拟光从玻璃边缘渗入）；亮背景 → 轻微压暗（保可读性）
int tint = lum < 0.35f ? blend(avg, 0xFFFFFFFF, 0.35f) : blend(avg, 0xFF000000, 0.25f);
int finalAlpha = Math.round(spec.tintWeight * 255f / 100f); // 8% → 0x14
drawable.setAmbientTint(Color.argb(finalAlpha, Color.red(tint), Color.green(tint), Color.blue(tint)));
```

**注意**：`tintWeight` 只有 8–12%（α ≈ `0x14`–`0x1F`），超过 20% 就会明显"染色"，破坏中性。

---

## 5. 可编译的 Java 代码

下面三段代码**已用 `javac -source 8 -target 8 -cp android-all.jar` 实际编译通过**（编译目标是 Android 14 / API 34 的 `android-all.jar`，无错误无警告除了一个 `deprecated` note）。

### 5.1 `GlassKit.java` —— 核心：参数表 + 玻璃 Drawable + 模糊

> 完整文件（照抄进 `app/src/main/java/com/englishstudy/app/ui/GlassKit.java` 即可编译）。
> 关键点已在注释里标注 API 级别。

```java
package com.englishstudy.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * 液态玻璃（Liquid Glass）实现，只用 Android SDK 自带能力。
 *
 * 层序（下 → 上）：填充 → 内容着色 → 底部内阴影 → 边缘光 → 镜面高光 → 折射环
 * API 分级：全部图层 API 24+；真模糊 API 31+；降级模糊 = stackBlur(全 API)
 */
public final class GlassKit {

    public static final int OFF = 0;      // 关闭：纯色卡片，0 成本
    public static final int LIGHT = 1;    // 轻：只有边缘光 + 高光，无模糊
    public static final int STD = 2;      // 标准：加静态模糊 + 外发光
    public static final int STRONG = 3;   // 强：加折射偏移 + 内容着色

    private GlassKit() {
    }

    // ------------------------------------------------------------------ 参数表

    /** 一档玻璃的全部数值参数。所有 *_Dp 在运行时乘 density。 */
    public static final class Spec {
        public int tier;
        public int fillAlpha;        // 填充层 alpha (0-255)
        public int rimTopAlpha;      // 边缘光顶部（亮）
        public int rimBottomAlpha;   // 边缘光底部（暗）
        public float rimWidthDp;     // 边缘光宽度
        public int sheenAlpha;       // 左上镜面高光峰值 alpha
        public int innerShadeAlpha;  // 底部内阴影 alpha（做厚度感）
        public int shadowAlpha;      // 外发光/投影 alpha
        public float shadowRadiusDp; // 投影模糊半径
        public float shadowDyDp;     // 投影 Y 偏移
        public float blurRadiusPx;   // 背景模糊半径（0 = 不模糊）
        public float refractInsetDp; // 折射偏移环宽度（STRONG 才有）
        public int tintWeight;       // 内容感知着色权重 0-100
        public float radiusDp;       // 圆角
        public boolean allowScroll;  // 是否允许贴在滚动内容上
    }

    /** 4 档参数，深色主题专用。 */
    public static Spec spec(int tier) {
        Spec s = new Spec();
        s.tier = tier;
        switch (tier) {
            case LIGHT:
                s.fillAlpha = 0xF0; s.rimTopAlpha = 0x38; s.rimBottomAlpha = 0x0E;
                s.rimWidthDp = 1f;  s.sheenAlpha = 0x14; s.innerShadeAlpha = 0x0C;
                s.shadowAlpha = 0x40; s.shadowRadiusDp = 8f; s.shadowDyDp = 2f;
                s.blurRadiusPx = 0f; s.refractInsetDp = 0f; s.tintWeight = 0;
                s.radiusDp = 18f; s.allowScroll = true;
                break;
            case STD:
                s.fillAlpha = 0xC8; s.rimTopAlpha = 0x59; s.rimBottomAlpha = 0x14;
                s.rimWidthDp = 1.5f; s.sheenAlpha = 0x1F; s.innerShadeAlpha = 0x14;
                s.shadowAlpha = 0x66; s.shadowRadiusDp = 14f; s.shadowDyDp = 4f;
                s.blurRadiusPx = 18f; s.refractInsetDp = 0f; s.tintWeight = 8;
                s.radiusDp = 18f; s.allowScroll = false;
                break;
            case STRONG:
                s.fillAlpha = 0x9E; s.rimTopAlpha = 0x80; s.rimBottomAlpha = 0x1F;
                s.rimWidthDp = 2f;  s.sheenAlpha = 0x2E; s.innerShadeAlpha = 0x1C;
                s.shadowAlpha = 0x80; s.shadowRadiusDp = 20f; s.shadowDyDp = 6f;
                s.blurRadiusPx = 28f; s.refractInsetDp = 1.5f; s.tintWeight = 12;
                s.radiusDp = 20f; s.allowScroll = false;
                break;
            default: // OFF
                s.fillAlpha = 0xFF; s.rimTopAlpha = 0x26; s.rimBottomAlpha = 0x26;
                s.rimWidthDp = 1f;  s.sheenAlpha = 0;    s.innerShadeAlpha = 0;
                s.shadowAlpha = 0;  s.shadowRadiusDp = 0f; s.shadowDyDp = 0f;
                s.blurRadiusPx = 0f; s.refractInsetDp = 0f; s.tintWeight = 0;
                s.radiusDp = 16f; s.allowScroll = true;
                break;
        }
        return s;
    }

    // ------------------------------------------------------------------ 背景

    /**
     * 给任意 View 套上玻璃背景。
     * 投影会被 View 自身边界裁掉，所以把 padding 各边扩大 shadowRoom，
     * Drawable 再把内容区 inset 回来 —— 视觉卡片尺寸不变，多余空间留给阴影。
     */
    public static void apply(View v, int tier, int baseColor) {
        Context c = v.getContext();
        Spec s = spec(tier);
        int room = px(c, shadowRoomDp(s));
        v.setPadding(v.getPaddingLeft() + room, v.getPaddingTop() + room,
                v.getPaddingRight() + room, v.getPaddingBottom() + room);
        v.setBackground(new GlassDrawable(c, s, baseColor, room));
    }

    /** 阴影需要预留的边距（dp）。 */
    public static float shadowRoomDp(Spec s) {
        return s.shadowAlpha > 0 ? s.shadowRadiusDp * 0.6f + s.shadowDyDp : 0f;
    }

    static int px(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    /** 顶栏 / 底栏：比卡片更实一点（要保证 9 个 tab 的字可读）。 */
    public static void applyBar(View v, int tier) {
        Spec s = spec(tier);
        s.fillAlpha = Math.min(0xFF, s.fillAlpha + 0x14);
        s.radiusDp = 0f;      // 通栏不做圆角
        s.shadowAlpha = 0;    // 通栏不投影，避免和内容打架
        v.setBackground(new GlassDrawable(v.getContext(), s, 0xFF111721, 0));
    }

    /** 选中态玻璃药丸。 */
    public static Drawable pill(Context c, int tier, int accent) {
        Spec s = spec(tier);
        s.fillAlpha = tier == OFF ? 0x22 : 0x3A;
        s.rimTopAlpha = Math.min(0xFF, s.rimTopAlpha + 0x18);
        s.rimBottomAlpha = 0x10;
        s.shadowAlpha = 0;
        s.radiusDp = 16f;
        return new GlassDrawable(c, s, accent, 0);
    }

    /**
     * 玻璃背景绘制器。4 个 Paint 复用，避免每帧 GC。
     * 全部 API 24+ 可用。
     */
    public static final class GlassDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tintP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint refractP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float density;
        private final Spec s;
        private final int padPx;          // 为阴影预留的内边距
        private int baseColor;
        private int ambientTint = Color.TRANSPARENT;

        public GlassDrawable(Context c, Spec spec, int baseColor) {
            this(c, spec, baseColor, 0);
        }

        public GlassDrawable(Context c, Spec spec, int baseColor, int padPx) {
            DisplayMetrics m = c.getResources().getDisplayMetrics();
            this.density = m.density;
            this.s = spec;
            this.baseColor = baseColor;
            this.padPx = padPx;
            rim.setStyle(Paint.Style.STROKE);
            sheen.setStyle(Paint.Style.FILL);
            shade.setStyle(Paint.Style.FILL);
            fill.setStyle(Paint.Style.FILL);
            tintP.setStyle(Paint.Style.FILL);
            refractP.setStyle(Paint.Style.STROKE);
        }

        /** 内容感知着色：把玻璃后面区域的平均色按权重混进来。 */
        public void setAmbientTint(int color) {
            this.ambientTint = color;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            r.set(bounds);
            r.inset(padPx, padPx);
            rebuild();
        }

        private void rebuild() {
            float rad = s.radiusDp * density;
            int fillColor = s.fillAlpha >= 0xFF ? baseColor : withAlpha(baseColor, s.fillAlpha);

            fill.setShader(null);
            fill.setColor(fillColor);
            fill.clearShadowLayer();
            // API 28+ 形状阴影在硬件加速下生效（Official hw-accel 支持表：setShadowLayer 非文本 = 28）
            if (s.shadowAlpha > 0 && Build.VERSION.SDK_INT >= 28) {
                fill.setShadowLayer(s.shadowRadiusDp * density, 0f, s.shadowDyDp * density,
                        withAlpha(0xFF000000, s.shadowAlpha));
            }

            // 边缘光：左上亮、右下暗，模拟光沿玻璃边缘折射
            rim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    withAlpha(0xFFFFFFFF, s.rimTopAlpha),
                    withAlpha(0xFFFFFFFF, s.rimBottomAlpha),
                    Shader.TileMode.CLAMP));
            rim.setStrokeWidth(Math.max(1f, s.rimWidthDp * density));

            // 镜面高光：左上角径向渐变，峰值在 24% 宽度 / 10% 高度
            float hx = r.left + r.width() * 0.24f;
            float hy = r.top + r.height() * 0.10f;
            float hr = Math.max(r.width() * 0.62f, 1f);
            sheen.setShader(new RadialGradient(hx, hy, hr,
                    new int[]{withAlpha(0xFFFFFFFF, s.sheenAlpha), Color.TRANSPARENT},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));

            // 底部内阴影：让玻璃有厚度
            shade.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    new int[]{Color.TRANSPARENT, Color.TRANSPARENT,
                            withAlpha(0xFF000000, s.innerShadeAlpha)},
                    new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));

            // 折射环：上边缘内缩一条亮带，模拟镜片弯曲（仅 STRONG）
            if (s.refractInsetDp > 0) {
                refractP.setStrokeWidth(s.refractInsetDp * density);
                refractP.setShader(new LinearGradient(0, r.top,
                        0, r.top + s.refractInsetDp * density * 4f,
                        withAlpha(0xFFFFFFFF, 0x22), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            }
        }

        @Override
        public void draw(Canvas canvas) {
            float rad = s.radiusDp * density;
            if (r.isEmpty()) {
                r.set(getBounds());
                r.inset(padPx, padPx);
                rebuild();
            }
            // 1. 填充 + 投影
            canvas.drawRoundRect(r, rad, rad, fill);
            // 2. 内容感知着色（只在 STRONG 档，α ≤ 0x1F）
            if (s.tintWeight > 0 && Color.alpha(ambientTint) > 0) {
                tintP.setColor(ambientTint);
                canvas.drawRoundRect(r, rad, rad, tintP);
            }
            // 3. 底部内阴影
            if (s.innerShadeAlpha > 0) {
                canvas.drawRoundRect(r, rad, rad, shade);
            }
            // 4. 边缘光
            RectF rr = new RectF(r);
            float inset = Math.max(1f, s.rimWidthDp * density * 0.5f);
            rr.inset(inset, inset);
            canvas.drawRoundRect(rr, Math.max(0, rad - inset), Math.max(0, rad - inset), rim);
            // 5. 镜面高光（裁剪到玻璃内部）
            if (s.sheenAlpha > 0) {
                int save = canvas.save();
                canvas.clipRect(r);
                canvas.drawRoundRect(r, rad, rad, sheen);
                canvas.restoreToCount(save);
            }
            // 6. 折射偏移环（仅 STRONG）
            if (s.refractInsetDp > 0) {
                RectF ri = new RectF(r);
                ri.inset(s.refractInsetDp * density, s.refractInsetDp * density);
                canvas.drawRoundRect(ri,
                        Math.max(0, rad - s.refractInsetDp * density),
                        Math.max(0, rad - s.refractInsetDp * density), refractP);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            fill.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ 模糊

    /**
     * 给「内容层」加真模糊（API 31+）。
     *
     * 关键：View.setRenderEffect 模糊的是这个 View 自己画出来的东西，不是它背后。
     * 所以要模糊被玻璃盖住的内容层本身，玻璃面板盖在它上面。
     */
    public static boolean blurContent(View content, float radiusPx) {
        if (Build.VERSION.SDK_INT < 31) return false;
        if (radiusPx <= 0f) {
            content.setRenderEffect(null);
            return true;
        }
        content.setRenderEffect(RenderEffect.createBlurEffect(
                radiusPx, radiusPx, Shader.TileMode.CLAMP));
        return true;
    }

    /** 把 View 抓成低分辨率快照（全 API，不需要任何权限）。必须主线程调用。 */
    public static Bitmap snapshot(View v, float scale) {
        int w = Math.max(1, (int) (v.getWidth() * scale));
        int h = Math.max(1, (int) (v.getHeight() * scale));
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.scale(scale, scale);
        v.draw(cv);
        return bmp;
    }

    /** 三遍盒式模糊近似高斯，纯 Java，后台线程。实测 270x600 ≈ 2.89ms。 */
    public static void stackBlur(Bitmap bmp, int radius) {
        if (radius < 1) return;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, w, h, radius);
            boxV(px, w, h, radius);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxH(int[] p, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = p[row + clamp(i, w)];
                sa += c >>> 24; sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                p[row + x] = ((int) (sa / div) << 24) | ((int) (sr / div) << 16)
                        | ((int) (sg / div) << 8) | (int) (sb / div);
                int out = p[row + clamp(x - r, w)];
                int in = p[row + clamp(x + r + 1, w)];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static void boxV(int[] p, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = p[clamp(i, h) * w + x];
                sa += c >>> 24; sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                p[y * w + x] = ((int) (sa / div) << 24) | ((int) (sr / div) << 16)
                        | ((int) (sg / div) << 8) | (int) (sb / div);
                int out = p[clamp(y - r, h) * w + x];
                int in = p[clamp(y + r + 1, h) * w + x];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    /** 平均色（内容感知着色用），带亮度门控，避免过曝。 */
    public static int averageColor(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        long r = 0, g = 0, b = 0, n = 0;
        int step = Math.max(1, Math.min(w, h) / 32);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = bmp.getPixel(x, y);
                r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
            }
        }
        if (n == 0) return Color.TRANSPARENT;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    // ------------------------------------------------------------------ 工具

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    public static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return Color.argb(255, r, g, bl);
    }

    /** 深色主题下把强调色降饱和 18%，避免玻璃被彩色"污染"。 */
    public static int soften(int color, int alpha) {
        return withAlpha(blend(color, 0xFFFFFFFF, 0.18f), alpha);
    }
}
```

### 5.2 `GlassHost.java` —— 快照模糊宿主（API 30 及以下的主力）

```java
package com.englishstudy.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 把「被玻璃盖住的内容」抓成低分辨率快照，模糊后当玻璃底图。
 *
 * 为什么不用 Window.setBackgroundBlurRadius：要求 window 同时 translucent + floating，
 * 常规 Activity 不满足 → 调用后无任何效果（官方文档明确写了）。
 *
 * 性能纪律：快照缩放 1/8~1/4，只在滚动停止 120ms 后重算，滚动中复用旧快照。
 */
public class GlassHost extends FrameLayout {

    public interface ContentSource {
        View contentView();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float scale = 0.25f;
    private Bitmap blurred;
    private ContentSource source;
    private int tier = GlassKit.STD;
    private int ambient = Color.TRANSPARENT;
    private final Runnable capture = new Runnable() {
        @Override
        public void run() {
            captureNow();
        }
    };

    public GlassHost(Context c) {
        super(c);
        setWillNotDraw(false);
        bitmapPaint.setFilterBitmap(true);
    }

    public void bind(ContentSource s, int glassTier) {
        this.source = s;
        this.tier = glassTier;
        markDirty();
    }

    public void setTier(int t) {
        this.tier = t;
        markDirty();
        invalidate();
    }

    /** 内容滚动 / 变化时调用，停止 120ms 后重算一次。 */
    public void markDirty() {
        main.removeCallbacks(capture);
        main.postDelayed(capture, 120);
    }

    public void captureNow() {
        if (source == null || tier == GlassKit.OFF) return;
        View content = source.contentView();
        if (content == null || content.getWidth() == 0) return;

        // API 31+：让内容层自己糊，GPU 完成，零 CPU 成本
        if (Build.VERSION.SDK_INT >= 31 && GlassKit.blurContent(content, blurPx())) {
            recycle();
            invalidate();
            return;
        }
        // 降级：快照 → 后台线程盒式模糊 → 回主线程贴图
        final Bitmap snap = GlassKit.snapshot(content, scale);
        final int radius = Math.max(2, Math.round(blurPx() * scale));
        new Thread(new Runnable() {
            @Override
            public void run() {
                GlassKit.stackBlur(snap, radius);
                final int avg = GlassKit.averageColor(snap);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        recycle();
                        blurred = snap;
                        ambient = avg;
                        invalidate();
                    }
                });
            }
        }, "glass-blur").start();
    }

    public int ambient() {
        return ambient;
    }

    private float blurPx() {
        return GlassKit.spec(tier).blurRadiusPx * getResources().getDisplayMetrics().density;
    }

    private void recycle() {
        if (blurred != null && !blurred.isRecycled()) blurred.recycle();
        blurred = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (blurred != null && !blurred.isRecycled()) {
            canvas.drawBitmap(blurred, null, new Rect(0, 0, getWidth(), getHeight()), bitmapPaint);
        }
        super.onDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        main.removeCallbacks(capture);
        recycle();
        super.onDetachedFromWindow();
    }
}
```

### 5.3 `GlassTabBar.java` —— 9 标签玻璃底栏（针对 1080px 宽度专门调过）

> 1080px / 9 tab = 每格 120px（= 40dp @3x）。这是**极限密度**，所以：文字 10sp + 选中只给药丸（不给整格填充）。

```java
package com.englishstudy.app.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 9 个标签的玻璃底栏。玻璃只出现在「栏体 + 选中药丸」两层，不叠第三层。 */
public class GlassTabBar extends LinearLayout {

    public interface OnTab {
        void onTab(int i);
    }

    private final TextView[] labels;
    private int current = -1;
    private int tier;
    private int accent;

    public GlassTabBar(Context c, String[] names, int glassTier, int accentColor) {
        super(c);
        this.tier = glassTier;
        this.accent = accentColor;
        float d = c.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, (int) (64 * d)));
        setPadding((int) (4 * d), 0, (int) (4 * d), 0);
        GlassKit.applyBar(this, tier);

        labels = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(c);
            t.setText(names[i]);
            t.setTextSize(names.length > 8 ? 10 : 11);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(0xFF8A9AB0);
            t.setIncludeFontPadding(false);
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            lp.gravity = Gravity.CENTER_VERTICAL;
            t.setLayoutParams(lp);
            t.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(idx);
                    if (getParent() instanceof OnTab) ((OnTab) getParent()).onTab(idx);
                }
            });
            labels[i] = t;
            addView(t);
        }
    }

    public void setTier(int t) {
        this.tier = t;
        GlassKit.applyBar(this, t);
        int keep = current;
        current = -1;
        select(keep);
    }

    public void select(int i) {
        if (i < 0 || i >= labels.length) return;
        float d = getResources().getDisplayMetrics().density;
        if (current >= 0) {
            labels[current].setBackground(null);
            labels[current].setTextColor(0xFF8A9AB0);
            labels[current].setPadding(0, 0, 0, 0);
        }
        current = i;
        labels[i].setTextColor(accent);
        if (tier != GlassKit.OFF) {
            labels[i].setBackground(GlassKit.pill(getContext(), tier, accent));
        } else {
            // 关闭档：纯色药丸，成本为 0
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(16 * d);
            g.setColor(0x334F7CFF);
            labels[i].setBackground(g);
        }
        int ph = (int) (8 * d);
        int pv = (int) (6 * d);
        labels[i].setPadding(ph, pv, ph, pv);
    }

    public int current() {
        return current;
    }
}
```

### 5.4 接入方式（改动量最小的落地路径）

```java
// MainActivity.onCreate 里，把原来的 bar 换成玻璃版：
GlassTabBar bar = new GlassTabBar(this, TABS, GlassKit.level(), getColor(R.color.primary));
bar.setTier(GlassKit.level());           // 设置页切档后调这个，立即生效
FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
lp.gravity = Gravity.BOTTOM;
shell.addView(bar, lp);

// 内容区滚动监听 —— 只在 IDLE 时重算，滚动中复用旧快照（关键性能纪律）
sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
    @Override
    public void onScrollChange(View v, int x, int y, int ox, int oy) {
        // 滚动中什么都不做
    }
});
// 停止滚动时（用 GestureDetector 或 OnTouchListener 的 ACTION_UP）：
//   host.markDirty();
```

### 5.5 参数持久化（沿用现有 `Store`）

```java
public static int level() {
    return Math.max(0, Math.min(3, Store.getInt("glass", 2)));  // 默认标准档
}
public static void setLevel(int v) {
    Store.putInt("glass", Math.max(0, Math.min(3, v)));
}
```

---

## 6. 落地清单（按实现成本排序）

### 必做（P0）—— 不做就没有「玻璃」这个东西

| # | 事项 | 成本 | 工作量 | 依据 |
|---|---|---|---|---|
| 1 | **`GlassKit` 参数表 + `GlassDrawable`**（填充/边缘光/高光/内阴影 4 层） | 极低（纯 Canvas，0 依赖） | 0.5 天 | §1.2 五特征里的 4 个全靠它；**这是 90% 的视觉收益** |
| 2 | **背景环境光晕**（`RadialGradient` 两团，α 0x1A–0x46） | 极低 | 0.2 天 | 纯色背景上的玻璃看不出质感，这一条性价比最高 |
| 3 | **4 档强度开关 + 持久化** | 极低 | 0.3 天 | §4 参数表；OFF 档回退纯色保证不崩 |
| 4 | **9 标签底栏套玻璃**（`GlassTabBar`） | 低 | 0.5 天 | 玻璃唯一「高价值」位置（导航层） |
| 5 | **深色对比度校正**：主色文字不放玻璃上，或提亮到 `#7B9BFF` | 低 | 0.2 天 | §4.2 实测 STRONG 档 `#4F7CFF` 只有 4.18:1 |
| 6 | **边缘光对比度 ≥ 3:1**（STD/STRONG 档已达 3.22 / 5.20） | 低 | 0（已在参数里） | §4.2 实测 |

### 应该做（P1）—— 让它从「像」变成「是」

| # | 事项 | 成本 | 工作量 | 依据 |
|---|---|---|---|---|
| 7 | **`GlassHost` 快照模糊**（API 31 走 RenderEffect，<31 走 stackBlur） | 中 | 1 天 | §3.8 实测 0.7–2.9ms，可接受 |
| 8 | **滚动停止才重算**（`markDirty` + 120ms 延迟） | 低 | 0.2 天 | §3.8 三条铁律 |
| 9 | **内容感知着色**（`averageColor` + 亮度门控 + `tintWeight` 8–12%） | 中 | 0.5 天 | §1.2 第 5 特征 |
| 10 | **平滑过渡动画**：切 tab 时药丸位置插值（150ms，`DecelerateInterpolator`） | 低 | 0.3 天 | Apple："materialize by gradually modulating the light bending"，不是 fade |
| 11 | **Reduce Transparency 适配**：读 `Settings.Global`/无障碍开关，降一档 | 低 | 0.3 天 | §1.4 官方要求 |

### 锦上添花（P2）—— 有预算再做

| # | 事项 | 成本 | 工作量 | 说明 |
|---|---|---|---|---|
| 12 | **AGSL 真折射**（`RuntimeShader` + `createRuntimeShaderEffect`，API 33+） | 高 | 2–3 天 | 唯一能做真 lensing 位移的路径；<33 必须优雅退回 §5.1 |
| 13 | **手指按下时内部发光**（触点处径向渐变 + alpha 动到 0x40） | 中 | 0.5 天 | Apple："illuminate from within" |
| 14 | **设备陀螺仪驱动的高光位移**（`SensorManager` 读 `TYPE_ROTATION_VECTOR`，高光中心偏移 ±6%） | 中 | 1 天 | 官方："lighting responds to device motion"；耗电 + 可能干扰，慎用 |
| 15 | 大面板「变厚」响应（栏体尺寸变化时阴影/边缘光按比例加重） | 中 | 0.5 天 | 官方："simulates a thicker material" |
| 16 | 顶栏也套玻璃 | 低 | 0.2 天 | 同理，但要保证状态栏文字对比度 |

### 明确不要做（黑名单）

| ✗ | 原因 |
|---|---|
| 内容卡片（词卡、图表、复习卡）套玻璃 | 违反 "Don't use Liquid Glass in the content layer"；复习卡要最大对比度 |
| 玻璃叠玻璃 | 官方明令禁止；视觉上糊成一坨灰 |
| `Window.setBackgroundBlurRadius` 在 Activity 里 | 无效（需 floating 窗口），浪费调试时间 |
| `BlurMaskFilter` 用在滚动列表 | 硬件加速不支持 → 必须 SOFTWARE 层 → 掉到 20fps |
| RenderScript `ScriptIntrinsicBlur` | 已废弃，且要额外 .so |
| 给每个控件都染强调色 | 官方："Refrain from adding color to the background of multiple controls" |
| 白色低透明度叠加做深色玻璃 | §1.5 错法①，发灰发脏 |
| 黑色 drop shadow 做深色分离 | §1.5 错法②，看不见还显脏 |
| 第三方模糊库 / Compose | 约束明确禁止 |

---

## 7. 已验证 vs 推测（诚实分级）

### ✅ 已验证（官方文档原文或本机实测）

| 结论 | 来源 |
|---|---|
| `RenderEffect` 全部方法 = **API 31** | developer.android.google.cn 参考页，逐方法核对 |
| `View.setRenderEffect` / `RenderNode.setRenderEffect` = **API 31** | 同上 |
| `RuntimeShader` / `createRuntimeShaderEffect` = **API 33** | 同上 |
| `Window.setBackgroundBlurRadius` = API 31，**要求 window translucent + floating** | 官方参考页原文 |
| `isCrossWindowBlurEnabled` = API 31；省电模式下可能返回 false | 官方参考页原文 |
| **硬件加速不支持 `setMaskFilter()`**（BlurMaskFilter 静默失效） | 《硬件加速》官方支持表 |
| `setShadowLayer`（非文本）= 硬件加速 API **28+** | 《硬件加速》官方支持表 |
| `PixelCopy.request` = API 26 | 官方参考页 |
| RenderScript 已废弃（不建议新用） | 官方迁移文档存在即为证据 |
| 官方对「玻璃只用于导航层」「禁止 glass on glass」「极少使用」的要求 | HIG《Materials》+ WWDC25-219 原文 |
| 官方 "Regular / Clear 两个变体不可混用" | WWDC25-219 原文 |
| 官方 Clear 变体需 35% 暗化层 | HIG《Materials》原文 |
| 深色模式对比度 ≥ 4.5:1（小字建议 7:1） | HIG《Dark Mode》原文 |
| 三遍盒式模糊耗时（135×300 至 1080×2400，6 组数据） | 本机 OpenJDK 17 / ARM64 实测 |
| 4 档玻璃在最坏背景下的对比度（5 组数据） | 我用 sRGB→线性→WCAG 公式算的 |
| 三段代码可编译 | `javac -source 8 -target 8 -cp android-all.jar` 通过 |

### ⚠️ 推测 / 未直接验证（实施时请复测）

| 结论 | 依据 | 风险 |
|---|---|---|
| `setShadowLayer` 在 API 28+ 硬件加速下对形状生效（与 API 参考页文字冲突） | 硬件加速支持表 + 大量社区一致报告 | **低**：minSdk 30，若失效只需换成 9-patch / 单层渐变 |
| `RenderEffect` 全屏 20px 模糊在低端机掉到 ~45fps | 按像素吞吐推算，**未在真机跑 FPS** | **中**：请用 `dumpsys gfxinfo` 在本机验证 |
| ART 上盒式模糊比 JVM 快 20–50% | JIT/AOT 一般规律 | **低**：只影响绝对数字，不影响方案选择 |
| AGSL 折射位移（方案 12）能做出真 lensing | `RuntimeShader` + `uniform shader` 能力已确认，但**未写完整 shader 验证** | **中**：只影响 P2 项 |
| 六个产品截图的视觉描述 | App Store 官方截图（2026-11 版本），我逐张看过 | **低**：个别字号是我按 600px 宽截图估算的，±2sp |

---

## 8. 一句话实施顺序

**先做 1+2+3+4（约 1.5 天，纯 Canvas，零风险，拿到 80% 视觉效果）→ 验收对比度 → 再加 7+8（模糊与性能纪律）→ 最后才考虑 12（AGSL）。**

不要一上来就写 shader。玻璃质感的来源是**边缘光 + 高光 + 内阴影**这三层，不是模糊。模糊只是"内容在场"的暗示，做对了加分，做错了扣分（糊成一坨 + 掉帧）。
