package com.englishstudy.app.data;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;

import com.englishstudy.app.engine.AudioExtract;
import com.englishstudy.app.voice.OfflineAsrEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频素材（音频跟读用）。
 *
 * v6.0 起：导入后自动切句 + 逐句识别，句子带文本和时间区间，
 * 存进 audio.json —— 下次打开不用重算（这就是「缓存到本地」）。
 */
public final class AudioClip {

    private static final String TAG = "AudioClip";

    /** 一句（时间 + 文本）。 */
    public static final class Sent {
        public int startMs;
        public int endMs;
        public String text = "";

        public Sent(int s, int e, String t) {
            startMs = s;
            endMs = e;
            text = t == null ? "" : t;
        }
    }

    public static final class Item {
        public String id = "";
        public String title = "";
        public String fileName = "";
        public long durationMs;
        public long createdAt;
        /** 切出来的句子（带文本）。空表示还没切。 */
        public List<Sent> sents = new ArrayList<>();
        /** 句子是否已经识别过（避免每次打开重跑 ASR）。 */
        public boolean recognized;
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
                it.recognized = o.optBoolean("recognized");

                // 新格式：sents = [[start,end,"text"], ...]
                org.json.JSONArray ss = o.optJSONArray("sents");
                if (ss != null) {
                    for (int k = 0; k < ss.length(); k++) {
                        org.json.JSONArray s = ss.getJSONArray(k);
                        it.sents.add(new Sent(s.getInt(0), s.getInt(1),
                                s.length() > 2 ? s.optString(2) : ""));
                    }
                }
                // 旧格式兼容：segments = [[start,end], ...]
                org.json.JSONArray segs = o.optJSONArray("segments");
                if (segs != null && it.sents.isEmpty()) {
                    for (int k = 0; k < segs.length(); k++) {
                        org.json.JSONArray s = segs.getJSONArray(k);
                        it.sents.add(new Sent(s.getInt(0), s.getInt(1), ""));
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
                org.json.JSONArray ss = new org.json.JSONArray();
                for (Sent s : it.sents) {
                    ss.put(new org.json.JSONArray()
                            .put(s.startMs).put(s.endMs).put(s.text));
                }
                arr.put(new org.json.JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("fileName", it.fileName)
                        .put("durationMs", it.durationMs)
                        .put("createdAt", it.createdAt)
                        .put("recognized", it.recognized)
                        .put("sents", ss));
            }
            File f = metaFile(c);
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            if (!tmp.renameTo(f)) {
                // 跨文件系统时 renameTo 会失败，退回复制
                InputStream in = new java.io.FileInputStream(tmp);
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

    /** 导入一个音频文件。阻塞，放后台线程。 */
    public static Item importAudio(Context c, Uri uri, String displayName) {
        load(c);
        Item it = new Item();
        it.id = "au" + System.currentTimeMillis();
        String name = displayName == null ? "音频" : displayName;
        String ext = "mp3";
        String path = uri.getLastPathSegment();
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot > 0) ext = path.substring(dot + 1).toLowerCase(java.util.Locale.US);
        }
        // 只接受常见音频/容器扩展名，其他一律按 mp3 存（MediaPlayer 靠内容嗅探）
        if (!ext.matches("mp3|wav|m4a|aac|ogg|flac|opus|mp4|mkv|webm")) ext = "mp3";
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

    /**
     * 切句 + 识别整段。阻塞，放后台线程。
     *
     * 结果写回 Item.sents（含文本）并落盘 —— 下次直接读，不重跑。
     */
    public static List<Sent> segment(Context c, Item it) {
        if (it == null) return new ArrayList<>();
        // 已有缓存：直接返回
        if (it.recognized && !it.sents.isEmpty()) return it.sents;

        AudioExtract.Pcm pcm = AudioExtract.extract(c, Uri.fromFile(file(c, it)));
        if (!pcm.ok()) {
            Log.w(TAG, "extract failed: " + pcm.error);
            return new ArrayList<>();
        }
        if (it.durationMs <= 0) it.durationMs = (long) (pcm.seconds() * 1000);

        List<AudioExtract.Seg> segs = AudioExtract.segment(pcm);
        if (segs.isEmpty()) return new ArrayList<>();

        // 独立页/弹窗不会自动加载引擎，这里确保就绪（最多等 40 秒）
        if (!OfflineAsrEngine.ready()) {
            OfflineAsrEngine.init(c);
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 40000) {
                if (OfflineAsrEngine.ready() || OfflineAsrEngine.unavailable()) break;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
        }

        List<Sent> out = new ArrayList<>();
        boolean asrOk = OfflineAsrEngine.ready();
        for (AudioExtract.Seg s : segs) {
            Sent one = new Sent(s.startMs, s.endMs, "");
            if (asrOk) {
                try {
                    float[] slice = AudioExtract.slice(pcm, s.startMs, s.endMs);
                    String t = OfflineAsrEngine.recognize(slice, AudioExtract.SR);
                    if (t != null) one.text = t.trim();
                } catch (Throwable ignored) {
                }
            }
            out.add(one);
        }

        it.sents = out;
        it.recognized = asrOk;
        save(c);
        return out;
    }

    public static synchronized void remove(Context c, Item it) {
        load(c);
        //noinspection ResultOfMethodCallIgnored
        file(c, it).delete();
        LIST.remove(it);
        save(c);
    }
}
