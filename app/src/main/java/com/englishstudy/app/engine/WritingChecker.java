package com.englishstudy.app.engine;

import com.englishstudy.app.data.Dict;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 写作批改 · 规则层（v7.0 重写）。
 *
 * v6.x 的核心毛病：**误报太多**。用户写 "jailbreak"、"sptm"、"calif's"，
 * 词典里没有 → 全被判「拼写错误」；"A lot of" 是标准英语 → 被判「搭配不当」。
 * 4 条问题全错，真正的错误（ared / Asking / mine team）一个没抓到。
 *
 * 重写原则：
 *   1. 拼写只报「有明确候选」的词（编辑距离 ≤1 且能给出正确形式）。
 *      查不到又没有近似词的，一律放过 —— 宁可漏报，不可误报。
 *   2. 技术词 / 专名 / 缩写 / 复合词（jail+break）直接白名单。
 *   3. 「风格建议」和「错误」分开：a lot of、with the development of 这类
 *      只进 style 列表，不扣分、不标红。
 *   4. 补上真正高频的语法错误：物主代词（mine team）、主谓一致、
 *      句中大写、a + 复数。
 *
 * 语义层面的深度批改交给 {@link WriteReview}（本地 LLM）。
 */
public final class WritingChecker {

    /** 严重度：error 扣分标红；style 只提示，不扣分。 */
    public static final class Issue {
        public final String type;
        public final int offset;
        public final int length;
        public final String snippet;
        public final String message;
        public final String hint;
        public final boolean style;

        Issue(String type, int offset, int length, String snippet,
              String message, String hint, boolean style) {
            this.type = type;
            this.offset = offset;
            this.length = length;
            this.snippet = snippet;
            this.message = message;
            this.hint = hint == null ? "" : hint;
            this.style = style;
        }
    }

    public static final class Result {
        public final List<Issue> issues = new ArrayList<>();
        public int score;
        public int words;
        public int sentences;
        public int avgLen;
        public String verdict = "";

        public int errorCount() {
            int n = 0;
            for (Issue i : issues) if (!i.style) n++;
            return n;
        }

        public int styleCount() {
            int n = 0;
            for (Issue i : issues) if (i.style) n++;
            return n;
        }
    }

    // ---------------------------------------------------------------- 规则表

    /**
     * 明确的错误（扣分）。key 必须足够具体，避免像 "a lot of" 那样误伤。
     */
    private static final String[][] ERRORS = {
            {"very like", "very 不能修饰动词", "really like / like … very much"},
            {"I very", "very 不能直接修饰动词", "I really …"},
            {"how to say", "中式问法", "how do you say"},
            {"open the light", "开灯不用 open", "turn on the light"},
            {"close the light", "关灯不用 close", "turn off the light"},
            {"play phone", "玩手机不说 play phone", "use my phone"},
            {"eat medicine", "吃药是 take", "take medicine"},
            {"I am boring", "boring = 让人无聊", "I am bored"},
            {"I am interesting", "interesting = 让人感兴趣", "I am interested"},
            {"I am exciting", "exciting = 让人兴奋", "I am excited"},
            {"I am tiring", "tiring = 让人累", "I am tired"},
            {"discuss about", "discuss 及物，不加 about", "discuss the topic"},
            {"marry with", "marry 及物，不加 with", "marry someone"},
            {"enter into the", "enter 及物", "enter the room"},
            {"according to me", "不地道", "in my opinion"},
            {"in my opinion, I think", "语义重复", "in my opinion 或 I think"},
            {"because so", "because 与 so 不能连用", "只留一个"},
            {"the reason is because", "reason 后接 that", "the reason is that"},
            {"more better", "双重比较级", "better"},
            {"most easiest", "双重最高级", "easiest"},
            {"can able to", "重复情态", "can 或 be able to"},
            {"return back", "重复", "return"},
            {"repeat again", "重复", "repeat"},
    };

    /** 风格建议（不扣分，只在清单里提一句）。 */
    private static final String[][] STYLE = {
            {"a lot of", "口语化，正式写作可换", "many / much / a great deal of"},
            {"more and more people", "略套话", "a growing number of people"},
            {"with the development of", "套话开头", "as … develops / thanks to"},
            {"in a word", "套话", "in short / overall"},
            {"last but not least", "套话", "finally"},
            {"as we all know", "套话", "（直接说事实）"},
    };

    /**
     * 技术词 / 常见专名白名单 —— 词典里没有，但绝不能报拼写错误。
     * 用 Set 查，O(1)。
     */
    private static final Set<String> WHITELIST = new HashSet<>(Arrays.asList(
            // 编程 / 系统
            "jailbreak", "jailbroken", "root", "rooted", "kernel", "userspace", "syscall",
            "syscalls", "exploit", "exploits", "exploited", "payload", "payloads",
            "bypass", "bypassing", "bypassed", "hook", "hooking", "hooked", "inject",
            "injection", "injecting", "patcher", "patching", "patched", "bootloader",
            "unlock", "unlocking", "unlocked", "firmware", "sideload", "sideloading",
            "adb", "fastboot", "recovery", "twrp", "magisk", "xposed", "substrate",
            "selinux", "seccomp", "sandbox", "sandboxed", "emulator", "hypervisor",
            "vm", "vms", "qemu", "android", "ios", "linux", "unix", "macos", "windows",
            "github", "gitlab", "repo", "repos", "commit", "commits", "merge", "rebase",
            "pull", "push", "branch", "branches", "fork", "forks", "issue", "issues",
            "api", "apis", "sdk", "ndk", "jni", "abi", "abis", "arm", "arm64", "x86",
            "gpu", "cpu", "ram", "rom", "ssd", "usb", "uart", "i2c", "spi", "gpio",
            "mmio", "dma", "irq", "ioctl", "mmap", "munmap", "fork", "execve",
            "buffer", "buffers", "overflow", "underflow", "stack", "heap", "malloc",
            "free", "alloc", "allocator", "pointer", "pointers", "struct", "structs",
            "typedef", "enum", "namespace", "lambda", "async", "await", "callback",
            "callbacks", "thread", "threads", "mutex", "semaphore", "lock", "locks",
            "deadlock", "race", "racecondition", "regression", "refactor", "refactoring",
            "deploy", "deployed", "deployment", "docker", "container", "containers",
            "kubernetes", "k8s", "nginx", "redis", "mysql", "postgres", "sqlite",
            "json", "yaml", "xml", "http", "https", "tcp", "udp", "dns", "tls", "ssl",
            "websocket", "grpc", "rest", "graphql", "oauth", "jwt", "cors", "csrf",
            "llm", "llms", "gpt", "bert", "transformer", "transformers", "token",
            "tokens", "tokenizer", "embedding", "embeddings", "inference", "finetune",
            "quantization", "quantized", "gguf", "onnx", "pytorch", "tensorflow",
            "numpy", "pandas", "python", "javascript", "typescript", "kotlin", "swift",
            "rust", "golang", "java", "gradle", "maven", "cmake", "makefile",
            // 通用缩写
            "ok", "okay", "etc", "vs", "eg", "ie", "aka", "asap", "fyi", "imo",
            "btw", "idk", "tbh", "imho", "diy", "pdf", "png", "jpg", "gif", "svg",
            "url", "urls", "uri", "uuid", "id", "ids", "ip", "ips", "mac", "dns",
            // 网络 / 日常
            "wifi", "bluetooth", "nfc", "gps", "sms", "mms", "vpn", "cdn", "ddos",
            "selfie", "podcast", "podcasts", "blog", "blogs", "blogger", "vlog",
            "smartphone", "smartphones", "laptop", "laptops", "tablet", "tablets",
            "app", "apps", "emoji", "emojis", "hashtag", "hashtags", "streaming",
            "online", "offline", "email", "emails", "website", "websites", "login",
            "logout", "signup", "username", "password", "passwords", "database",
            "databases", "server", "servers", "client", "clients", "backend",
            "frontend", "framework", "frameworks", "plugin", "plugins", "module",
            "modules", "package", "packages", "library", "libraries", "runtime",
            "compiler", "compilers", "debugger", "syntax", "semantic", "bytecode"
    ));

    /** 句中允许大写的词（月份/星期/语言/常见专名），避免误报大小写。 */
    private static final Set<String> PROPER = new HashSet<>(Arrays.asList(
            "i", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
            "sunday", "january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december", "english",
            "chinese", "japanese", "korean", "french", "german", "spanish", "russian",
            "american", "british", "european", "asian", "african", "christmas",
            "thanksgiving", "easter", "halloween", "google", "apple", "microsoft",
            "amazon", "facebook", "twitter", "youtube", "instagram", "tiktok",
            "android", "ios", "iphone", "ipad", "macbook", "windows", "linux",
            "github", "gitlab", "docker", "kubernetes", "python", "java", "kotlin",
            "swift", "rust", "golang", "javascript", "typescript", "react", "vue",
            "angular", "node", "npm", "yarn", "webpack", "vite", "mysql", "redis",
            "postgres", "mongodb", "sqlite", "nginx", "apache", "aws", "azure", "gcp",
            "qualcomm", "snapdragon", "mediatek", "samsung", "xiaomi", "huawei",
            "oppo", "vivo", "oneplus", "pixel", "nexus", "magisk", "supersu", "xposed",
            "selinux", "arm", "intel", "amd", "nvidia", "adreno", "mali", "vulkan",
            "opengl", "directx", "metal", "llvm", "gcc", "clang", "cmake", "gradle"
    ));

    /** 常见「多打 ed」手滑 → 正确形式。 */
    private static final java.util.Map<String, String> HAND_TYPOS =
            new java.util.HashMap<>();

    static {
        HAND_TYPOS.put("ared", "are");
        HAND_TYPOS.put("ised", "is");
        HAND_TYPOS.put("wered", "were");
        HAND_TYPOS.put("was'ed", "was");
        HAND_TYPOS.put("hased", "has");
        HAND_TYPOS.put("haved", "have");
        HAND_TYPOS.put("doed", "do");
        HAND_TYPOS.put("dosed", "does");
        HAND_TYPOS.put("caned", "can");
        HAND_TYPOS.put("willed", "will");
    }

    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z'-]*");
    private static final Pattern VOWEL_START =
            Pattern.compile("^[aeiou]", Pattern.CASE_INSENSITIVE);

    private WritingChecker() {
    }

    public static Result check(String text) {
        Result r = new Result();
        String src = text == null ? "" : text;
        if (src.trim().isEmpty()) {
            r.verdict = "先写点东西吧";
            return r;
        }

        matchRules(src, ERRORS, false, r);
        matchRules(src, STYLE, true, r);
        // 结构层：基于词性的语法分析（不是字符串匹配）
        if (Pos.ready()) {
            for (Grammar.Finding f : Grammar.check(src)) {
                r.issues.add(new Issue(f.type, f.offset, f.length, f.snippet,
                        f.message, f.hint, false));
            }
        } else {
            // 词性索引没加载时退回旧规则，保证仍能工作
            checkArticles(src, r);
            checkPossessive(src, r);
            checkAgreement(src, r);
        }
        checkLowercaseI(src, r);
        checkSentenceCase(src, r);
        checkMidSentenceCaps(src, r);
        checkRepeats(src, r);
        checkPunctuation(src, r);
        checkSpelling(src, r);
        checkLength(src, r);

        List<Issue> deduped = dedupe(r.issues);
        r.issues.clear();
        r.issues.addAll(deduped);

        countStats(r, src);
        score(r);
        return r;
    }

    // ---------------------------------------------------------------- 规则实现

    private static void matchRules(String src, String[][] rules, boolean style, Result r) {
        for (String[] rule : rules) {
            String key = rule[0];
            Pattern p = Pattern.compile(
                    "(?<![A-Za-z])" + Pattern.quote(key) + "(?![A-Za-z])",
                    Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(src);
            while (m.find()) {
                r.issues.add(new Issue("搭配", m.start(), m.end() - m.start(),
                        src.substring(m.start(), m.end()), rule[1], rule[2], style));
            }
        }
    }

    /** a / an：按音素判断（university 用 a，hour 用 an）。 */
    private static void checkArticles(String src, Result r) {
        Matcher ma = Pattern.compile("\\b(a|an)\\s+([A-Za-z][A-Za-z'-]*)").matcher(src);
        while (ma.find()) {
            String art = ma.group(1).toLowerCase(Locale.US);
            String word = ma.group(2);
            boolean vowel = VOWEL_START.matcher(word).find();
            boolean silentH = word.matches("(?i)^(hour|honest|honor|honour|heir).*");
            boolean uni = word.matches("(?i)^(university|unit|unique|uniform|union"
                    + "|useful|user|usual|euro|one|once).*");
            boolean needAn = (vowel && !uni) || silentH;
            if (needAn && art.equals("a")) {
                r.issues.add(new Issue("冠词", ma.start(), ma.group().length(), ma.group(),
                        "元音音素开头，应用 an", "an " + word, false));
            } else if (!needAn && art.equals("an")) {
                r.issues.add(new Issue("冠词", ma.start(), ma.group().length(), ma.group(),
                        "辅音音素开头，应用 a", "a " + word, false));
            }
        }
    }

    private static void checkLowercaseI(String src, Result r) {
        Matcher mi = Pattern.compile("(^|[.!?]\\s+|\\s)i(\\s|'|$|[,.!?])").matcher(src);
        while (mi.find()) {
            int at = mi.start() + mi.group(1).length();
            r.issues.add(new Issue("大小写", at, 1, "i", "人称代词 I 永远大写", "I", false));
        }
    }

    private static void checkSentenceCase(String src, Result r) {
        Matcher ms = Pattern.compile("(^|[.!?]\\s+)([a-z][A-Za-z'-]*)").matcher(src);
        while (ms.find()) {
            int at = ms.start() + ms.group(1).length();
            String w = ms.group(2);
            r.issues.add(new Issue("大小写", at, w.length(), w, "句首字母应大写",
                    Character.toUpperCase(w.charAt(0)) + w.substring(1), false));
        }
    }

    /**
     * 句中不该大写的词。
     *
     * 这是 v6.x 完全漏掉的一类：用户写 "people ared Asking about"，
     * Asking 在句中却大写 —— 明显是输入失误，但规则没查。
     */
    private static void checkMidSentenceCaps(String src, Result r) {
        Matcher m = Pattern.compile("[A-Za-z]+").matcher(src);
        int prevEnd = -1;
        while (m.find()) {
            String w = m.group();
            int at = m.start();
            boolean atSentenceStart = at == 0;
            if (!atSentenceStart && prevEnd >= 0) {
                // 前一个非空字符是 . ! ? 或换行 → 句首
                // 往前找第一个非空白字符
                int k = prevEnd;
                boolean sawNewline = false;
                while (k < at) {
                    char c = src.charAt(k);
                    if (c == '\n') sawNewline = true;
                    if (!Character.isWhitespace(c)) break;
                    k++;
                }
                if (k < at) {
                    // 中间有标点：只有 . ! ? 才算句首
                    char c = src.charAt(k);
                    atSentenceStart = c == '.' || c == '!' || c == '?';
                } else {
                    // 中间只有空白（同一行）→ 不是句首；跨行 → 行首
                    atSentenceStart = sawNewline;
                }
            }
            prevEnd = m.end();

            if (atSentenceStart) continue;
            if (w.length() < 2) continue;
            if (!Character.isUpperCase(w.charAt(0))) continue;
            // 全大写（缩写）跳过
            if (w.equals(w.toUpperCase(Locale.US))) continue;
            String low = w.toLowerCase(Locale.US);
            if (PROPER.contains(low)) continue;
            if (WHITELIST.contains(low)) continue;
            // 后面紧跟数字/符号的技术标识（如 Adreno740）跳过
            if (m.end() < src.length() && Character.isDigit(src.charAt(m.end()))) continue;

            r.issues.add(new Issue("大小写", at, w.length(), w,
                    "句中不该大写（除非是专有名词）", low, false));
        }
    }

    /**
     * 物主代词误用：mine / yours / hers / theirs / ours 不能直接修饰名词。
     * 例："with mine team" → "with my team"
     */
    private static void checkPossessive(String src, Result r) {
        String[][] map = {
                {"mine", "my"}, {"yours", "your"}, {"hers", "her"},
                {"theirs", "their"}, {"ours", "our"},
        };
        for (String[] pair : map) {
            Matcher m = Pattern.compile("\\b" + pair[0] + "\\s+([A-Za-z]+)",
                    Pattern.CASE_INSENSITIVE).matcher(src);
            while (m.find()) {
                String next = m.group(1);
                // 后面跟的是名词才报（mine is / yours are 是对的）
                String lowNext = next.toLowerCase(Locale.US);
                if (isFunctionWord(lowNext)) continue;
                r.issues.add(new Issue("语法", m.start(), pair[0].length(), pair[0],
                        pair[0] + " 是名词性物主代词，不能修饰名词",
                        pair[1] + " " + next, false));
            }
        }
    }

    private static boolean isFunctionWord(String w) {
        switch (w) {
            case "is": case "are": case "was": case "were": case "has": case "have":
            case "had": case "will": case "would": case "can": case "could":
            case "should": case "must": case "may": case "might": case "do":
            case "does": case "did": case "to": case "of": case "in": case "on":
            case "at": case "for": case "with": case "and": case "or": case "but":
            case "not": case "also": case "too": case "very": case "so":
                return true;
            default:
                return false;
        }
    }

    /**
     * 主谓一致（只覆盖最确定的几种，宁缺毋滥）。
     */
    private static void checkAgreement(String src, Result r) {
        // 复数主语 + is/was/has/does
        Matcher m1 = Pattern.compile(
                "\\b(people|they|we|you|children|men|women|students|teachers)\\s+"
                        + "(is|was|has|does)\\b", Pattern.CASE_INSENSITIVE).matcher(src);
        while (m1.find()) {
            String subj = m1.group(1);
            String verb = m1.group(2);
            String fix = subj.toLowerCase(Locale.US).equals("people")
                    || subj.toLowerCase(Locale.US).equals("children")
                    || subj.toLowerCase(Locale.US).equals("men")
                    || subj.toLowerCase(Locale.US).equals("women")
                    || subj.toLowerCase(Locale.US).equals("students")
                    || subj.toLowerCase(Locale.US).equals("teachers")
                    ? pluralOf(verb) : pluralOf(verb);
            r.issues.add(new Issue("语法", m1.start(), m1.group().length(), m1.group(),
                    "主语是复数，谓语要用复数形式", subj + " " + fix, false));
        }

        // 第三人称单数 + are/were/have/do
        Matcher m2 = Pattern.compile(
                "\\b(he|she|it)\\s+(are|were|have|do)\\b",
                Pattern.CASE_INSENSITIVE).matcher(src);
        while (m2.find()) {
            String subj = m2.group(1);
            String verb = m2.group(2);
            r.issues.add(new Issue("语法", m2.start(), m2.group().length(), m2.group(),
                    "第三人称单数，谓语要用单数形式", subj + " " + singularOf(verb), false));
        }

        // 明显的手滑：动词 + ed 后接 -ing（ared Asking）
        Matcher m3 = Pattern.compile("\\b([a-z]{2,})ed\\s+[A-Z]").matcher(src);
        while (m3.find()) {
            String stem = m3.group(1);
            // 只有 stem+ed 不在词典、而 stem 是常见动词时才报
            if (Dict.get(stem + "ed") != null) continue;
            if (Dict.get(stem) == null) continue;
            // 常见系动词/助动词的手滑（ared → are），给准确建议
            String wrong = (stem + "ed").toLowerCase(Locale.US);
            // 系动词/助动词 + ed 的手滑：直接查表给出正确形式
            String fix = HAND_TYPOS.get(wrong);
            if (fix == null) fix = null;   // 给不出确定答案就不瞎猜
            if (fix != null) {
                r.issues.add(new Issue("语法", m3.start(), wrong.length(), wrong,
                        "这个词形不成立，多打了 ed", "→ " + fix, false));
            }
        }
    }

    private static String pluralOf(String v) {
        switch (v.toLowerCase(Locale.US)) {
            case "is": return "are";
            case "was": return "were";
            case "has": return "have";
            case "does": return "do";
            default: return v;
        }
    }

    private static String singularOf(String v) {
        switch (v.toLowerCase(Locale.US)) {
            case "are": return "is";
            case "were": return "was";
            case "have": return "has";
            case "do": return "does";
            default: return v;
        }
    }

    private static void checkRepeats(String src, Result r) {
        Matcher md = Pattern.compile("\\b([A-Za-z]+)\\s+\\1\\b",
                Pattern.CASE_INSENSITIVE).matcher(src);
        while (md.find()) {
            r.issues.add(new Issue("重复", md.start(), md.group().length(), md.group(),
                    "同一个词连续出现两次", md.group(1), false));
        }
    }

    private static void checkPunctuation(String src, Result r) {
        Matcher mp = Pattern.compile("\\s+[,.;:!?]").matcher(src);
        while (mp.find()) {
            r.issues.add(new Issue("标点", mp.start(), mp.group().length(),
                    mp.group().trim(), "标点前不应有空格", "把空格删掉", false));
        }
        // 逗号后没空格（排除数字 1,000）
        Matcher mc = Pattern.compile("[A-Za-z],[A-Za-z]").matcher(src);
        while (mc.find()) {
            r.issues.add(new Issue("标点", mc.start(), mc.group().length(), mc.group(),
                    "逗号后应有空格", mc.group().charAt(0) + ", " + mc.group().charAt(2), false));
        }
        // 句子之间没有结束标点（连续两个空格隔开的大写词，粗糙但有效）
        if (!src.matches("(?s).*[.!?]\\s*$") && src.trim().length() > 20) {
            r.issues.add(new Issue("标点", src.length() - 1, 1, "（末尾）",
                    "结尾缺句号", "补一个 .", false));
        }
    }

    /**
     * 拼写：**只在能给出明确候选时**才报。
     *
     * 这是修误报的关键。v6.x 的做法是「词典查不到 = 错」，
     * 于是 jailbreak / sptm / calif 全被标红。
     * 现在改成：查不到 → 先看白名单/复合词/专名 → 仍查不到就找编辑距离 ≤1 的
     * 词典词，只有找到了才报「可能是 X」。找不到近似词的一律放过。
     */
    private static void checkSpelling(String src, Result r) {
        if (!Dict.ready()) return;
        Matcher mw = WORD.matcher(src);
        while (mw.find()) {
            String w = mw.group();
            if (w.length() < 3) continue;
            if (Character.isUpperCase(w.charAt(0))) continue;   // 专名/句首
            if (w.equals(w.toUpperCase(Locale.US))) continue;   // 缩写
            if (w.contains("-") || w.contains("'")) continue;   // 复合/缩写形式

            String low = w.toLowerCase(Locale.US);
            if (WHITELIST.contains(low)) continue;
            if (Dict.lookup(w) != null) continue;
            if (isCompoundOfKnownWords(low)) continue;

            String cand = suggest(low);
            if (cand == null) continue;   // 找不到候选 → 不报（可能是新词/术语）
            // 候选必须同词性，避免把名词改成动词
            if (Pos.ready() && !Pos.tags(low).isEmpty()
                    && !Pos.tags(cand).isEmpty()
                    && Pos.tags(low).charAt(0) != Pos.tags(cand).charAt(0)) {
                continue;
            }

            r.issues.add(new Issue("拼写", mw.start(), w.length(), w,
                    "可能拼错了", "→ " + cand, false));
        }
    }

    /** jailbreak = jail + break，这类复合词不该报错。 */
    private static boolean isCompoundOfKnownWords(String w) {
        for (int i = 2; i <= w.length() - 2; i++) {
            String a = w.substring(0, i);
            String b = w.substring(i);
            if (Dict.get(a) != null && Dict.get(b) != null) return true;
        }
        return false;
    }

    private static void checkLength(String src, Result r) {
        String[] sents = src.split("[.!?]+");
        int at = 0;
        for (String s : sents) {
            int idx = src.indexOf(s, at);
            if (idx >= 0) at = idx + s.length();
            int n = s.trim().isEmpty() ? 0 : s.trim().split("\\s+").length;
            if (n > 40) {
                r.issues.add(new Issue("句长", Math.max(0, idx), Math.min(s.length(), 30),
                        s.trim().substring(0, Math.min(24, s.trim().length())) + "…",
                        "这句有 " + n + " 词，太长容易读不懂", "拆成两三句", true));
            }
        }
    }

    // ---------------------------------------------------------------- 统计

    private static List<Issue> dedupe(List<Issue> in) {
        List<Issue> out = new ArrayList<>();
        for (Issue cand : in) {
            boolean dup = false;
            for (int i = 0; i < out.size(); i++) {
                Issue keep = out.get(i);
                if (!keep.type.equals(cand.type)) continue;
                int aS = keep.offset, aE = keep.offset + keep.length;
                int bS = cand.offset, bE = cand.offset + cand.length;
                if (bS < aE && aS < bE) {
                    dup = true;
                    if (cand.length > keep.length) out.set(i, cand);
                    break;
                }
            }
            if (!dup) out.add(cand);
        }
        return out;
    }

    private static void countStats(Result r, String src) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = WORD.matcher(src);
        while (m.find()) seen.add(m.group().toLowerCase(Locale.US));
        r.words = seen.size();

        String[] sents = src.split("[.!?]+");
        int n = 0, total = 0;
        for (String s : sents) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            n++;
            total += t.split("\\s+").length;
        }
        r.sentences = Math.max(1, n);
        r.avgLen = total / r.sentences;
    }

    /**
     * 评分：只有 error 扣分，style 不扣。
     *
     * 之前 a lot of 这种「风格建议」也扣 6 分，用户明明写对了却掉到 70。
     */
    private static void score(Result r) {
        int s = 100;
        for (Issue it : r.issues) {
            if (it.style) continue;
            switch (it.type) {
                case "拼写": s -= 8; break;
                case "语法": s -= 7; break;
                case "搭配": s -= 6; break;
                case "冠词": s -= 4; break;
                case "大小写": s -= 3; break;
                case "标点": s -= 2; break;
                case "重复": s -= 3; break;
                default: s -= 2;
            }
        }
        if (r.words < 20) s -= 3;
        if (r.avgLen > 40) s -= 3;
        if (r.avgLen < 5) s -= 2;
        r.score = Math.max(0, Math.min(100, s));

        int err = r.errorCount();
        if (r.score >= 92) r.verdict = "很地道";
        else if (r.score >= 85) r.verdict = "表达清楚";
        else if (r.score >= 72) r.verdict = "能看懂，" + err + " 处要改";
        else if (r.score >= 60) r.verdict = "问题较多，看下面的清单";
        else r.verdict = "先从红色项改起";
    }

    /** 找编辑距离 ≤1 的词典词；找不到返回 null（调用方据此不报错）。 */
    private static String suggest(String w) {
        // 常见手滑先试
        String[] tries = {
                w.replaceAll("([a-z])\\1+", "$1"),
                w.replace("ie", "ei"),
                w.replace("ei", "ie"),
                w.replaceAll("([a-z])$", ""),
                w + "e",
                w + "s",
                w + "d",
        };
        for (String t : tries) {
            if (!t.equals(w) && t.length() >= 3 && Dict.get(t) != null) return t;
        }
        // 编辑距离 1：换一个字母 / 删一个 / 加一个
        if (w.length() <= 12) {
            for (int i = 0; i < w.length(); i++) {
                String del = w.substring(0, i) + w.substring(i + 1);
                if (del.length() >= 3 && Dict.get(del) != null) return del;
                for (char c = 'a'; c <= 'z'; c++) {
                    String rep = w.substring(0, i) + c + w.substring(i + 1);
                    if (!rep.equals(w) && Dict.get(rep) != null) return rep;
                }
            }
            for (int i = 0; i <= w.length(); i++) {
                for (char c = 'a'; c <= 'z'; c++) {
                    String ins = w.substring(0, i) + c + w.substring(i);
                    if (Dict.get(ins) != null) return ins;
                }
            }
        }
        return null;
    }
}
