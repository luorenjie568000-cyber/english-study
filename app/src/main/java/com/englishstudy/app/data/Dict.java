package com.englishstudy.app.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 词典：assets/dict_core.db.gz 首次启动解压到 filesDir，之后用 SQLite 查询。
 *
 * 表结构
 *   w(word, phonetic, trans, pos, collins, oxford, tag, frq, exchange, lemma, defin, ipa)
 *   lemma(inflected, base)
 *
 * 数据来源 ECDICT(MIT) + ipa-dict(MIT)。全量 77 万词可放 dict_full.db 自动优先使用。
 */
public final class Dict {

    private static final String TAG = "Dict";
    private static final String DB_NAME = "dict_core.db";
    private static final String ASSET = "dict_core.db.gz";

    /** 一个词条。字段全部只读。 */
    public static final class Entry {
        public final String word;
        public final String phonetic;   // ECDICT 原始音标（非标准 IPA）
        public final String ipa;        // 标准 IPA，无斜杠
        public final String def;        // 中文释义
        public final String defin;      // 英文释义
        public final String pos;        // 词性串
        public final String tag;        // zk gk cet4 cet6 ky toefl ielts gre
        public final String exchange;   // 词形变化原串
        public final String lemma;      // 本词对应的原形（若自身是变形）
        public final int collins;       // 柯林斯星级 0-5
        public final boolean oxford;    // 是否牛津 3000
        public final int frq;           // 词频排名，0 = 未知

        Entry(String word, String phonetic, String ipa, String def, String defin,
              String pos, String tag, String exchange, String lemma,
              int collins, boolean oxford, int frq) {
            this.word = word;
            this.phonetic = phonetic;
            this.ipa = ipa;
            this.def = def;
            this.defin = defin;
            this.pos = pos;
            this.tag = tag;
            this.exchange = exchange;
            this.lemma = lemma;
            this.collins = collins;
            this.oxford = oxford;
            this.frq = frq;
        }

        /** 展示用 IPA：去掉首尾斜杠，避免 UI 再包一层出现 //…//。 */
        public String displayIpa() {
            String s = ipa != null && !ipa.isEmpty() ? ipa : phonetic;
            if (s == null) return "";
            s = s.trim();
            while (s.startsWith("/")) s = s.substring(1);
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            return s.trim();
        }

        public boolean inBook(String bookId) {
            if (bookId == null || bookId.isEmpty() || "all".equals(bookId)) return true;
            return tag != null && tag.contains(bookId);
        }

        public boolean rich() {
            return frq > 0 || collins > 0 || oxford;
        }
    }

    private static SQLiteDatabase db;
    private static volatile boolean ready;
    private static int count;

    private Dict() {
    }

    public static boolean ready() {
        return ready;
    }

    public static int size() {
        return count;
    }

    /** 解压 assets 里的词库并打开。已在盘上则直接打开。 */
    public static synchronized void load(Context c) {
        if (ready) return;
        long t0 = System.currentTimeMillis();
        try {
            File dir = new File(c.getFilesDir(), "dict");
            if (!dir.exists()) dir.mkdirs();

            // 全量库优先（用户可选下载）
            File full = new File(dir, "dict_full.db");
            File core = new File(dir, DB_NAME);
            File target = full.exists() && full.length() > 100000 ? full : core;

            if (!target.exists() || target.length() < 100000) {
                unpack(c, target);
            }
            db = SQLiteDatabase.openDatabase(target.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            Cursor cur = db.rawQuery("SELECT COUNT(*) FROM w", null);
            if (cur.moveToFirst()) count = cur.getInt(0);
            cur.close();
            ready = count > 0;
            Log.i(TAG, "loaded " + count + " words in " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable e) {
            Log.e(TAG, "load failed", e);
            ready = false;
        }
    }

    private static void unpack(Context c, File target) throws Exception {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        InputStream in = new GZIPInputStream(c.getAssets().open(ASSET), 1 << 16);
        FileOutputStream out = new FileOutputStream(tmp);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) throw new Exception("rename failed");
    }

    // ---------------------------------------------------------------- 查询

    private static final String COLS =
            "word,phonetic,ipa,trans,defin,pos,tag,exchange,lemma,collins,oxford,frq";

    private static Entry read(Cursor cur) {
        int collins;
        try {
            collins = Integer.parseInt(cur.getString(9) == null ? "0" : cur.getString(9).trim());
        } catch (NumberFormatException e) {
            collins = 0;
        }
        String ox = cur.getString(10);
        boolean oxford = "1".equals(ox == null ? "" : ox.trim());
        int frq = cur.getInt(11);
        return new Entry(
                nz(cur.getString(0)), nz(cur.getString(1)), nz(cur.getString(2)),
                unesc(cur.getString(3)), unesc(cur.getString(4)), nz(cur.getString(5)),
                nz(cur.getString(6)), nz(cur.getString(7)), nz(cur.getString(8)),
                collins, oxford, frq);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** ECDICT 的 trans/definition 里是字面量 \n，还原成真换行。 */
    private static String unesc(String s) {
        if (s == null) return "";
        if (s.indexOf('\\') < 0) return s;
        return s.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n");
    }

    /** 精确查词（大小写不敏感）。 */
    public static Entry get(String word) {
        if (!ready || word == null) return null;
        Cursor cur = null;
        try {
            cur = db.rawQuery("SELECT " + COLS + " FROM w WHERE word=? COLLATE NOCASE LIMIT 1",
                    new String[]{word});
            return cur.moveToFirst() ? read(cur) : null;
        } catch (Throwable e) {
            return null;
        } finally {
            if (cur != null) cur.close();
        }
    }

    /** 带词形还原的查词：tries / gave / running → 原形。 */
    public static Entry lookup(String raw) {
        if (!ready || raw == null) return null;
        String w = raw.trim();
        if (w.isEmpty()) return null;

        Entry direct = get(w);
        if (direct != null) return direct;

        // 1) 用 lemma 表查原形（最准）
        String base = lemmaOf(w);
        if (base != null) {
            Entry e = get(base);
            if (e != null) return e;
        }
        // 2) 规则推导兜底
        for (String c : lemmas(w)) {
            Entry e = get(c);
            if (e != null) return e;
        }
        return null;
    }

    /** 查变形词对应的原形。 */
    public static String lemmaOf(String word) {
        if (!ready || word == null) return null;
        Cursor cur = null;
        try {
            cur = db.rawQuery("SELECT base FROM lemma WHERE inflected=? LIMIT 1",
                    new String[]{word.toLowerCase(Locale.US)});
            return cur.moveToFirst() ? cur.getString(0) : null;
        } catch (Throwable e) {
            return null;
        } finally {
            if (cur != null) cur.close();
        }
    }

    /** 模糊搜索：前缀优先，其次包含（词或释义）。 */
    public static List<Entry> search(String q, int max) {
        List<Entry> out = new ArrayList<>();
        if (!ready || q == null) return out;
        String s = q.trim();
        if (s.isEmpty()) return out;

        Cursor cur = null;
        try {
            cur = db.rawQuery(
                    "SELECT " + COLS + " FROM w WHERE word LIKE ? COLLATE NOCASE "
                            + "ORDER BY CASE WHEN word LIKE ? COLLATE NOCASE THEN 0 ELSE 1 END, frq "
                            + "LIMIT ?",
                    new String[]{s + "%", s + "%", String.valueOf(max)});
            while (cur.moveToNext()) out.add(read(cur));
            cur.close();

            if (out.size() < max && !isAscii(s)) {
                cur = db.rawQuery("SELECT " + COLS + " FROM w WHERE trans LIKE ? LIMIT ?",
                        new String[]{"%" + s + "%", String.valueOf(max - out.size())});
                while (cur.moveToNext()) out.add(read(cur));
            }
            return out;
        } catch (Throwable e) {
            return out;
        } finally {
            if (cur != null) cur.close();
        }
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    /** 取某词书（tag）的词，按词频排序。 */
    public static List<String> byTag(String tag, int limit) {
        List<String> out = new ArrayList<>();
        if (!ready) return out;
        Cursor cur = null;
        try {
            cur = db.rawQuery(
                    "SELECT word FROM w WHERE tag LIKE ? AND frq>0 ORDER BY frq LIMIT ?",
                    new String[]{"%" + tag + "%", String.valueOf(limit)});
            while (cur.moveToNext()) out.add(cur.getString(0));
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }

    /** 全部词条（慎用，35k 条）。 */
    public static List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        if (!ready) return out;
        Cursor cur = null;
        try {
            cur = db.rawQuery("SELECT " + COLS + " FROM w ORDER BY word", null);
            while (cur.moveToNext()) out.add(read(cur));
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }

    /** 规则词形还原（lemma 表未命中时兜底）。 */
    public static List<String> lemmas(String word) {
        String w = word.toLowerCase(Locale.US);
        List<String> out = new ArrayList<>(8);
        add(out, w);
        if (w.endsWith("ies") && w.length() > 4) add(out, w.substring(0, w.length() - 3) + "y");
        if (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes")
                || w.endsWith("zes") || w.endsWith("sses")) {
            add(out, w.substring(0, w.length() - 2));
        }
        if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 3) {
            add(out, w.substring(0, w.length() - 1));
        }
        if (w.endsWith("ied")) add(out, w.substring(0, w.length() - 3) + "y");
        if (w.endsWith("ed")) {
            add(out, w.substring(0, w.length() - 2));
            add(out, w.substring(0, w.length() - 1));
            if (w.length() > 4 && w.charAt(w.length() - 3) == w.charAt(w.length() - 4)) {
                add(out, w.substring(0, w.length() - 3));
            }
        }
        if (w.endsWith("ing")) {
            add(out, w.substring(0, w.length() - 3));
            add(out, w.substring(0, w.length() - 3) + "e");
            if (w.length() > 5 && w.charAt(w.length() - 4) == w.charAt(w.length() - 5)) {
                add(out, w.substring(0, w.length() - 4));
            }
        }
        if (w.endsWith("ier")) add(out, w.substring(0, w.length() - 3) + "y");
        if (w.endsWith("iest")) add(out, w.substring(0, w.length() - 4) + "y");
        if (w.endsWith("er")) {
            add(out, w.substring(0, w.length() - 2));
            add(out, w.substring(0, w.length() - 1));
        }
        if (w.endsWith("est")) {
            add(out, w.substring(0, w.length() - 3));
            add(out, w.substring(0, w.length() - 2));
        }
        if (w.endsWith("ily")) add(out, w.substring(0, w.length() - 3) + "y");
        if (w.endsWith("ly")) add(out, w.substring(0, w.length() - 2));
        return out;
    }

    private static void add(List<String> out, String s) {
        if (s != null && s.length() > 1 && !out.contains(s)) out.add(s);
    }
}
