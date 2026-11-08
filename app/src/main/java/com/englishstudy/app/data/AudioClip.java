package com.englishstudy.app.data;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频素材（本地音频跟读用）。
 *
 * 用户导入一个 mp3/wav/m4a，我们用 MediaPlayer 播放。
 * 逐句切分需要「音频 → 波形 → 找静音段」，这部分交给 AudioSegments 处理。
 *
 * 存储：filesDir/audio/<id>.<ext>，元数据存 audio.json。
 */
public final class AudioClip {

    private static final String TAG = "AudioClip";

    public static final class Item {
        public String id = "";
        public String title = "";
        public String fileName = "";
        public long durationMs;
        public long createdAt;
        /** 切出来的句子区间（毫秒），空表示还没切。 */
        public List<int[]> segments = new ArrayList<>();
    }

    private static final List<Item> LIST = new ArrayList<>();
    private static boolean loaded;

    private AudioClip() {
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "audio");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File metaFile(Context c) {
        return new File(c.getFilesDir(), "audio.json");
    }

    public static File file(Context c, Item it) {
        return new File(dir(c), it.fileName);
    }

    public static List<Item> all(Context c) {
        load(c);
        return LIST;
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
            org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id = o.optString("id");
                it.title = o.optString("title");
                it.fileName = o.optString("fileName");
                it.durationMs = o.optLong("durationMs");
                it.createdAt = o.optLong("createdAt");
                org.json.JSONArray segs = o.optJSONArray("segments");
                if (segs != null) {
                    for (int k = 0; k < segs.length(); k++) {
                        org.json.JSONArray s = segs.getJSONArray(k);
                        it.segments.add(new int[]{s.getInt(0), s.getInt(1)});
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
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Item it : LIST) {
                org.json.JSONArray segs = new org.json.JSONArray();
                for (int[] s : it.segments) {
                    segs.put(new org.json.JSONArray().put(s[0]).put(s[1]));
                }
                arr.put(new org.json.JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("fileName", it.fileName)
                        .put("durationMs", it.durationMs)
                        .put("createdAt", it.createdAt)
                        .put("segments", segs));
            }
            File f = metaFile(c);
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            tmp.renameTo(f);
        } catch (Throwable e) {
            Log.w(TAG, "save failed", e);
        }
    }

    /** 导入一个音频文件。阻塞，放后台线程。 */
    public static Item importAudio(Context c, Uri uri, String displayName) {
        load(c);
        Item it = new Item();
        it.id = "au" + System.currentTimeMillis();
        String name = displayName == null ? "audio" : displayName;
        String ext = "mp3";
        String path = uri.getLastPathSegment();
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot > 0) ext = path.substring(dot + 1).toLowerCase(java.util.Locale.US);
        }
        it.fileName = it.id + "." + ext;
        it.title = name;
        it.createdAt = System.currentTimeMillis();

        try {
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            File dst = file(c, it);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();

            // 读时长
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(dst.getAbsolutePath());
            mp.prepare();
            it.durationMs = mp.getDuration();
            mp.release();
        } catch (Throwable e) {
            Log.e(TAG, "import failed", e);
            return null;
        }

        LIST.add(0, it);
        save(c);
        return it;
    }

    public static synchronized void remove(Context c, Item it) {
        load(c);
        //noinspection ResultOfMethodCallIgnored
        file(c, it).delete();
        LIST.remove(it);
        save(c);
    }
}
