package com.englishstudy.app.data;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户自己导入的文章（对应网页版的「粘贴/导入文章」）。
 *
 * 存成 JSON 放在 filesDir/articles.json，标题 + 正文 + 创建时间。
 * 阅读器里和内置短文一起列出来，可点词查义、点句朗读。
 */
public final class Articles {

    private static final String TAG = "Articles";

    public static final class Item {
        public String id = "";
        public String title = "";
        public String text = "";
        public long createdAt;

        public int words() {
            String t = text == null ? "" : text.trim();
            return t.isEmpty() ? 0 : t.split("\\s+").length;
        }

        public String[] sentences() {
            if (text == null) return new String[0];
            return text.split("(?<=[.!?])\\s+");
        }

        /** 预估阅读时长（分钟，按 150 词/分）。 */
        public int minutes() {
            return Math.max(1, words() / 150);
        }
    }

    private static final List<Item> LIST = new ArrayList<>();
    private static boolean loaded;

    private Articles() {
    }

    public static List<Item> all() {
        load();
        return LIST;
    }

    public static Item byId(String id) {
        load();
        for (Item it : LIST) if (it.id.equals(id)) return it;
        return null;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "articles.json");
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Context c = com.englishstudy.app.App.ctx();
        if (c == null) return;
        File f = file(c);
        if (!f.exists()) return;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f),
                            java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id = o.optString("id");
                it.title = o.optString("title");
                it.text = o.optString("text");
                it.createdAt = o.optLong("createdAt");
                LIST.add(it);
            }
        } catch (Throwable e) {
            Log.w(TAG, "load failed", e);
        }
    }

    public static synchronized void save() {
        Context c = com.englishstudy.app.App.ctx();
        if (c == null) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Item it : LIST) {
                arr.put(new org.json.JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("text", it.text)
                        .put("createdAt", it.createdAt));
            }
            File f = file(c);
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            out.write(arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            if (!tmp.renameTo(f)) {
                java.io.FileInputStream in = new java.io.FileInputStream(tmp);
                OutputStream o2 = new FileOutputStream(f);
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) o2.write(b, 0, n);
                o2.close();
                in.close();
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable e) {
            Log.w(TAG, "save failed", e);
        }
    }

    /** 新增一篇文章，返回 id。 */
    public static synchronized String add(String title, String text) {
        load();
        Item it = new Item();
        it.id = "a" + System.currentTimeMillis();
        it.title = (title == null || title.trim().isEmpty()) ? "未命名" : title.trim();
        it.text = normalize(text);
        it.createdAt = System.currentTimeMillis();
        LIST.add(0, it);
        save();
        return it.id;
    }

    public static synchronized void remove(String id) {
        load();
        for (int i = LIST.size() - 1; i >= 0; i--) {
            if (LIST.get(i).id.equals(id)) LIST.remove(i);
        }
        save();
    }

    /** 从 txt/md 文件读入文本。 */
    public static String readFile(Context c, android.net.Uri uri) {
        try {
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) return "";
            byte[] buf = new byte[65536];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            String s = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            return normalize(s);
        } catch (Throwable e) {
            return "";
        }
    }

    /** 清理：去 BOM、统一换行、压掉多余空行。 */
    private static String normalize(String s) {
        if (s == null) return "";
        String t = s.replace("\ufeff", "").replace("\r\n", "\n").replace("\r", "\n");
        t = t.replaceAll("\n{3,}", "\n\n");
        return t.trim();
    }

    public static int count() {
        load();
        return LIST.size();
    }
}
