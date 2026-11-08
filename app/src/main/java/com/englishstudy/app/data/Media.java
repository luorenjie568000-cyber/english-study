package com.englishstudy.app.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 媒体素材 + 自动切出的字幕句（带缓存与历史）。
 *
 * 设计目标（用户要求）：
 *   - 导入视频/音频后自动识别整段，输出所有句子
 *   - 句子可以手动修改（识别错误）
 *   - 每句可单独播放对应片段的音视频，或用本地 TTS 朗读
 *   - 每句有译文
 *   - 结果**缓存到本地**，下次打开不用重跑
 *   - 有历史记录，可以点回来
 */
public final class Media {

    private static final String TAG = "Media";

    /** 一句字幕。 */
    public static final class Line {
        public int startMs;
        public int endMs;
        public String text = "";
        public String zh = "";
        /** 用户手动改过（就不再被自动识别覆盖）。 */
        public boolean edited;
    }

    /** 一个媒体素材。 */
    public static final class Item {
        public String id = "";
        public String title = "";
        public String fileName = "";
        public boolean isVideo;
        public long durationMs;
        public long createdAt;
        /** 识别状态：0=未识别 1=识别中 2=完成 3=失败 */
        public int state;
        public String error = "";
        public final List<Line> lines = new ArrayList<>();

        public int wordCount() {
            int n = 0;
            for (Line l : lines) {
                String t = l.text == null ? "" : l.text.trim();
                if (!t.isEmpty()) n += t.split("\\s+").length;
            }
            return n;
        }

        public int translatedCount() {
            int n = 0;
            for (Line l : lines) if (l.zh != null && !l.zh.isEmpty()) n++;
            return n;
        }
    }

    private static final List<Item> LIST = new ArrayList<>();
    private static boolean loaded;

    private Media() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "media");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File file(Context c, Item it) {
        return new File(dir(c), it.fileName);
    }

    private static File metaFile(Context c) {
        return new File(c.getFilesDir(), "media.json");
    }

    public static synchronized List<Item> all(Context c) {
        load(c);
        return LIST;
    }

    public static synchronized Item byId(Context c, String id) {
        load(c);
        for (Item it : LIST) if (it.id.equals(id)) return it;
        return null;
    }

    public static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        File f = metaFile(c);
        if (!f.exists()) return;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f),
                            java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id = o.optString("id");
                it.title = o.optString("title");
                it.fileName = o.optString("fileName");
                it.isVideo = o.optBoolean("isVideo");
                it.durationMs = o.optLong("durationMs");
                it.createdAt = o.optLong("createdAt");
                it.state = o.optInt("state");
                it.error = o.optString("error");
                JSONArray ls = o.optJSONArray("lines");
                if (ls != null) {
                    for (int k = 0; k < ls.length(); k++) {
                        JSONObject lo = ls.getJSONObject(k);
                        Line l = new Line();
                        l.startMs = lo.optInt("s");
                        l.endMs = lo.optInt("e");
                        l.text = lo.optString("t");
                        l.zh = lo.optString("z");
                        l.edited = lo.optBoolean("ed");
                        it.lines.add(l);
                    }
                }
                LIST.add(it);
            }
        } catch (Throwable e) {
            Log.w(TAG, "load failed", e);
        }
    }

    public static synchronized void save(Context c) {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : LIST) {
                JSONArray ls = new JSONArray();
                for (Line l : it.lines) {
                    ls.put(new JSONObject()
                            .put("s", l.startMs)
                            .put("e", l.endMs)
                            .put("t", l.text)
                            .put("z", l.zh)
                            .put("ed", l.edited));
                }
                arr.put(new JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("fileName", it.fileName)
                        .put("isVideo", it.isVideo)
                        .put("durationMs", it.durationMs)
                        .put("createdAt", it.createdAt)
                        .put("state", it.state)
                        .put("error", it.error)
                        .put("lines", ls));
            }
            File f = metaFile(c);
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            if (!tmp.renameTo(f)) {
                java.io.FileInputStream in = new java.io.FileInputStream(tmp);
                FileOutputStream o2 = new FileOutputStream(f);
                byte[] b = new byte[1 << 16];
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

    public static synchronized void remove(Context c, Item it) {
        load(c);
        //noinspection ResultOfMethodCallIgnored
        file(c, it).delete();
        LIST.remove(it);
        save(c);
    }
}
