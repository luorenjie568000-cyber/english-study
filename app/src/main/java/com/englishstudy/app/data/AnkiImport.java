package com.englishstudy.app.data;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 导入 Anki apkg。
 *
 * apkg 就是个 zip，里面通常有：
 *   collection.anki2 / collection.anki21        SQLite 库（notes + cards 两张表）
 *   collection.anki21b                          新版 zstd 压缩库（这里只识别，不解）
 *   0, 1, 2 …                                   媒体文件
 *
 * 我们只做「把正面当单词、背面当释义」这一件事：
 * 用户导入的绝大多数是单词卡组，多余的字段忽略。
 * 不建卡片调度（导入的词先进生词本，按 APP 自己的 FSRS 重新排）。
 */
public final class AnkiImport {

    private static final String TAG = "AnkiImport";

    public static final class Result {
        public int added;
        public int skipped;
        public String error = "";
        public final List<String> samples = new ArrayList<>();

        public boolean ok() {
            return error.isEmpty();
        }

        public String summary() {
            if (!ok()) return "导入失败：" + error;
            StringBuilder sb = new StringBuilder("导入完成：新增 " + added + " 个词");
            if (skipped > 0) sb.append("，跳过 " + skipped + " 个重复");
            if (!samples.isEmpty()) {
                sb.append("\n\n例如：");
                for (String s : samples) sb.append("\n· ").append(s);
            }
            return sb.toString();
        }
    }

    private AnkiImport() {
    }

    /**
     * 从 apkg 导入。
     *
     * @param limit 最多导入多少个（0 = 不限）
     */
    public static Result importApkg(Context c, Uri uri, int limit) {
        Result r = new Result();
        File tmp = new File(c.getCacheDir(), "anki-import.db");
        try {
            // 1) 从 zip 里抽出 collection.anki2（或 .anki21）
            String dbName = extractCollection(c, uri, tmp);
            if (dbName == null) {
                r.error = "压缩包里没找到 collection.anki2 / .anki21";
                return r;
            }
            if (dbName.equals("collection.anki21b")) {
                r.error = "这是新版 apkg（anki21b，zstd 压缩），请先在 Anki 里"
                        + "导出为「ankki2 兼容格式」再导入";
                return r;
            }

            // 2) 读 notes 表
            parseNotes(tmp, r, limit);
        } catch (Throwable e) {
            Log.e(TAG, "import failed", e);
            r.error = String.valueOf(e.getMessage());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return r;
    }

    /** 返回选中的库文件名，null 表示没找到。 */
    private static String extractCollection(Context c, Uri uri, File out) throws Exception {
        InputStream raw = c.getContentResolver().openInputStream(uri);
        if (raw == null) throw new Exception("无法读取文件");
        ZipInputStream zin = new ZipInputStream(raw);
        ZipEntry e;
        String found = null;
        byte[] buf = new byte[65536];
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            boolean isDb = "collection.anki2".equals(name)
                    || "collection.anki21".equals(name)
                    || "collection.anki21b".equals(name);
            if (!isDb) continue;
            // 优先级：anki21 > anki2 > anki21b
            if (found != null && !better(name, found)) continue;
            FileOutputStream fos = new FileOutputStream(out);
            int n;
            while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            found = name;
        }
        zin.close();
        return found;
    }

    private static boolean better(String a, String b) {
        return rank(a) > rank(b);
    }

    private static int rank(String name) {
        if (name.endsWith("anki21")) return 3;
        if (name.endsWith("anki2")) return 2;
        return 1;
    }

    /** 读 SQLite 的 notes 表，flds 用 \u001f 分隔字段。 */
    private static void parseNotes(File db, Result r, int limit) throws Exception {
        android.database.sqlite.SQLiteDatabase sql = null;
        android.database.Cursor cur = null;
        try {
            sql = android.database.sqlite.SQLiteDatabase.openDatabase(
                    db.getAbsolutePath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            cur = sql.rawQuery("SELECT flds FROM notes", null);
            if (cur == null) {
                r.error = "notes 表读取失败";
                return;
            }
            while (cur.moveToNext()) {
                if (limit > 0 && r.added >= limit) break;
                String flds = cur.getString(0);
                if (flds == null || flds.isEmpty()) continue;
                String[] parts = flds.split("\u001f");
                if (parts.length == 0) continue;
                String front = clean(parts[0]);
                if (front.isEmpty()) continue;

                // 只收英文单词（1-3 个词，允许连字符），太长的短语跳过
                String word = front.toLowerCase().trim();
                if (!word.matches("[a-z][a-z'\\- ]{0,30}")) {
                    r.skipped++;
                    continue;
                }
                if (word.contains(" ")) {
                    // 短语：只有在词典里查得到才收
                    if (Dict.lookup(word) == null) {
                        r.skipped++;
                        continue;
                    }
                }
                if (Vocab.get(word) != null) {
                    r.skipped++;
                    continue;
                }
                Vocab.getOrCreate(word);
                r.added++;
                if (r.samples.size() < 5) {
                    r.samples.add(word + (parts.length > 1
                            ? "  —  " + clip(clean(parts[1]), 30) : ""));
                }
            }
            Vocab.flush();
        } finally {
            if (cur != null) cur.close();
            if (sql != null) sql.close();
        }
    }

    /** 去掉 HTML 标签和多余空白（Anki 字段里常带 <div> 和 style）。 */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
        return t;
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
