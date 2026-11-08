package com.englishstudy.app.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 多语言词典（中文 / 日文 / 韩文）。
 *
 * 设计依据见 docs/multilang-report.md：
 *   - 只做「阅读辅助」：点词查义 + 存生词本，不做语法分析（那等于重写一套引擎）
 *   - 三门语言共 20.9MB，打进 assets；首次查询时懒解压到 filesDir
 *   - 查词走最长匹配：中文没有空格，从 4 个字往 1 个字试
 *
 * 数据来源：
 *   zh  CC-CEDICT  CC BY-SA 4.0   203,379 条
 *   ja  JMdict     EDRDG CC BY-SA 233,794 条
 *   ko  kengdic    MPL/LGPL        172,228 条
 */
public final class MultiDict {

    private static final String TAG = "MultiDict";

    public static final String ZH = "zh";
    public static final String JA = "ja";
    public static final String KO = "ko";

    public static final class Entry {
        public String word = "";
        public String reading = "";   // 拼音 / 假名 / 汉字
        public String trans = "";     // 英文释义（CC-CEDICT/JMdict/kengdic 都是英释）
        public String lang = "";
    }

    private static final java.util.Map<String, SQLiteDatabase> DB = new java.util.HashMap<>();
    private static final java.util.Set<String> LOADING = new java.util.HashSet<>();

    private MultiDict() {
    }

    // ---------------------------------------------------------------- 语言检测

    /** 按 Unicode script 占比判断文本主体语言。 */
    public static String detect(String text) {
        if (text == null || text.isEmpty()) return "en";
        int han = 0, kana = 0, hangul = 0, latin = 0;
        int n = Math.min(text.length(), 2000);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c >= 0x3040 && c <= 0x30FF) kana++;
            else if (c >= 0xAC00 && c <= 0xD7AF) hangul++;
            else if (c >= 0x4E00 && c <= 0x9FFF) han++;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
        }
        if (kana > 0 && kana + han > latin * 0.1) return JA;   // 有假名基本是日文
        if (hangul > latin * 0.2) return KO;
        if (han > latin * 0.2) return ZH;
        return "en";
    }

    public static String langName(String lang) {
        switch (lang) {
            case ZH: return "中文";
            case JA: return "日文";
            case KO: return "韩文";
            default: return "英文";
        }
    }

    // ---------------------------------------------------------------- 加载

    public static boolean ready(String lang) {
        return DB.containsKey(lang);
    }

    /** 解压 + 打开（首次调用会慢几百毫秒，放到后台线程）。 */
    public static synchronized boolean open(Context c, String lang) {
        if (DB.containsKey(lang)) return true;
        if (!ZH.equals(lang) && !JA.equals(lang) && !KO.equals(lang)) return false;
        File db = new File(c.getFilesDir(), "dict_" + lang + ".db");
        try {
            if (!db.exists() || db.length() < 1024) {
                InputStream in = c.getAssets().open("multilang/dict_" + lang + ".db.gz");
                GZIPInputStream gz = new GZIPInputStream(in, 1 << 16);
                File tmp = new File(db.getAbsolutePath() + ".tmp");
                OutputStream out = new FileOutputStream(tmp);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = gz.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                gz.close();
                in.close();
                if (db.exists()) //noinspection ResultOfMethodCallIgnored
                    db.delete();
                if (!tmp.renameTo(db)) {
                    FileInputStream fi = new FileInputStream(tmp);
                    OutputStream o2 = new FileOutputStream(db);
                    byte[] b = new byte[1 << 16];
                    int m;
                    while ((m = fi.read(b)) > 0) o2.write(b, 0, m);
                    o2.close();
                    fi.close();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
            SQLiteDatabase s = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            DB.put(lang, s);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "open failed: " + lang, e);
            return false;
        }
    }

    public static void closeAll() {
        for (SQLiteDatabase s : DB.values()) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        DB.clear();
    }

    // ---------------------------------------------------------------- 查询

    /** 精确查（大小写不敏感）。 */
    public static Entry lookup(String lang, String word) {
        SQLiteDatabase s = DB.get(lang);
        if (s == null || word == null || word.isEmpty()) return null;
        Cursor cur = null;
        try {
            cur = s.rawQuery(
                    "SELECT word,phonetic,trans FROM w WHERE word=? LIMIT 1",
                    new String[]{word});
            return cur.moveToFirst() ? read(cur, lang) : null;
        } catch (Throwable e) {
            return null;
        } finally {
            if (cur != null) cur.close();
        }
    }

    /**
     * 最长匹配：从 text 的 start 位置开始，尝试 4→1 个字符，返回命中长度与条目。
     * 中文/日文没有空格，点选时用这个把"图书"这类词整体识别出来。
     */
    public static Entry longestAt(String lang, String text, int start, int[] hitLen) {
        if (hitLen != null) hitLen[0] = 0;
        if (text == null || start < 0 || start >= text.length()) return null;
        int max = Math.min(4, text.length() - start);
        for (int len = max; len >= 1; len--) {
            String cand = text.substring(start, start + len);
            Entry e = lookup(lang, cand);
            if (e != null) {
                if (hitLen != null) hitLen[0] = len;
                return e;
            }
        }
        return null;
    }

    /** 模糊搜（前缀）。 */
    public static java.util.List<Entry> search(String lang, String q, int max) {
        java.util.List<Entry> out = new java.util.ArrayList<>();
        SQLiteDatabase s = DB.get(lang);
        if (s == null || q == null || q.isEmpty()) return out;
        Cursor cur = null;
        try {
            cur = s.rawQuery(
                    "SELECT word,phonetic,trans FROM w WHERE word LIKE ? ORDER BY length(word) LIMIT ?",
                    new String[]{q + "%", String.valueOf(max)});
            while (cur.moveToNext()) out.add(read(cur, lang));
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }

    private static Entry read(Cursor cur, String lang) {
        Entry e = new Entry();
        e.word = nz(cur.getString(0));
        e.reading = nz(cur.getString(1));
        e.trans = nz(cur.getString(2));
        e.lang = lang;
        return e;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static int count(Context c, String lang) {
        // 从 assets 里读不到行数，用一个静态常量（构建脚本产出的实测值）
        switch (lang) {
            case ZH: return 203379;
            case JA: return 233794;
            case KO: return 172228;
            default: return 0;
        }
    }
}
