package com.englishstudy.app.data;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 导入自定义词表：CSV / TSV / 纯文本（一行一个词）。
 *
 * 分隔符自动识别：逗号 > 制表符 > 分号。
 * 第一列当单词；如果第一行像表头（含 "word"/"单词"）就跳过。
 */
public final class WordListImport {

    private static final String TAG = "WordListImport";

    public static final class Result {
        public int added;
        public int skipped;
        public String error = "";

        public boolean ok() {
            return error.isEmpty();
        }

        public String summary() {
            if (!ok()) return "导入失败：" + error;
            return "导入完成：新增 " + added + " 个词"
                    + (skipped > 0 ? "，跳过 " + skipped + " 个" : "");
        }
    }

    private WordListImport() {
    }

    public static Result importFile(Context c, Uri uri, int limit) {
        Result r = new Result();
        BufferedReader reader = null;
        try {
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("无法读取文件");
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 15);

            String first = reader.readLine();
            if (first == null) {
                r.error = "文件是空的";
                return r;
            }
            first = stripBom(first);
            char sep = detectSeparator(first);

            // 表头判断
            String head = first.toLowerCase();
            boolean header = head.contains("word") || head.contains("单词")
                    || head.contains("词汇") || head.contains("英文");
            if (!header) handle(first, sep, r);

            String line;
            while ((line = reader.readLine()) != null) {
                if (limit > 0 && r.added >= limit) break;
                handle(line, sep, r);
            }
            Vocab.flush();
        } catch (Throwable e) {
            Log.e(TAG, "import failed", e);
            r.error = String.valueOf(e.getMessage());
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    private static void handle(String line, char sep, Result r) {
        if (line == null) return;
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#")) return;

        String word = s;
        int idx = sep == 0 ? -1 : s.indexOf(sep);
        if (idx > 0) word = s.substring(0, idx);

        word = word.trim()
                .replace("\"", "")
                .replace("'", "'")
                .toLowerCase();

        if (word.isEmpty() || word.length() > 32) {
            r.skipped++;
            return;
        }
        // 只收英文
        if (!word.matches("[a-z][a-z'\\- ]{0,31}")) {
            r.skipped++;
            return;
        }
        if (Vocab.get(word) != null) {
            r.skipped++;
            return;
        }
        Vocab.getOrCreate(word);
        r.added++;
    }

    /** 逗号 / 制表符 / 分号，看哪个出现得早。 */
    private static char detectSeparator(String line) {
        int comma = line.indexOf(',');
        int tab = line.indexOf('\t');
        int semi = line.indexOf(';');
        if (tab > 0 && (comma < 0 || tab < comma)) return '\t';
        if (comma > 0 && (semi < 0 || comma < semi)) return ',';
        if (semi > 0) return ';';
        return 0;
    }

    private static String stripBom(String s) {
        return s.startsWith("\ufeff") ? s.substring(1) : s;
    }
}
