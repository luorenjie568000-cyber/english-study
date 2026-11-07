#!/usr/bin/env python3
"""
生成 44 个音素的真实人声样本（assets/ipa/<sym>.raw，16k 单声道 PCM16）。

来源：
  元音 / 双元音 —— VV-yuna/english-ipa-learning 的 assets/audio/human/*.wav
                   （派生自 ipachart.com 的 Wikimedia Commons 真人录音）
  辅音       —— toipa.org/ipa-animations/*.mp3 的真人发音
  塞擦音 tʃ dʒ —— 上述真实塞音爆破段 + 真实后齿龈擦音拼合

处理：转 16k 单声道 → 能量分段取最长段 → RMS 归一化到 5000 → 首尾淡入淡出。

用法：python3 tools/build_ipa.py <work_dir>
  需要预先准备 work_dir 里的原始音频（见下方 SRC），产物写 work_dir/final/*.raw
"""
import math
import os
import struct
import subprocess
import sys

WORK = sys.argv[1] if len(sys.argv) > 1 else "/tmp/ipa_h"
OUT = os.path.join(WORK, "final")

# 音素 -> 源文件名
SRC = {
    "ɪ": "v_i-short.wav", "e": "v_e.wav", "æ": "v_ae.wav", "ʌ": "v_caret.wav",
    "ɒ": "v_o-short.wav", "ʊ": "v_u-short.wav", "ə": "v_schwa.wav",
    "iː": "v_i-long.wav", "ɑː": "v_a-long.wav", "ɔː": "v_aw-long.wav",
    "uː": "v_u-long.wav", "ɜː": "v_er-long.wav", "eɪ": "v_ei.wav",
    "aɪ": "v_ai.wav", "ɔɪ": "v_oi.wav", "aʊ": "v_au.wav", "əʊ": "v_oh.wav",
    "ɪə": "v_ia.wav", "eə": "v_ea.wav", "ʊə": "v_ua.wav",
    "p": "c_p.mp3", "b": "voiced-bilabial-plosive-de0d75d2cad9.mp3",
    "t": "c_voiceless-alveolar-plosive.mp3", "d": "c_voiced-alveolar-plosive.mp3",
    "k": "c_voiceless-velar-plosive.mp3", "ɡ": "c_voiced-velar-plosive.mp3",
    "f": "c_voiceless-labiodental-fricative.mp3",
    "v": "c_voiced-labiodental-fricative.mp3",
    "θ": "c_voiceless-dental-fricative.mp3", "ð": "c_voiced-dental-fricative.mp3",
    "s": "c_voiceless-alveolar-fricative.mp3", "z": "c_voiced-alveolar-fricative.mp3",
    "ʃ": "c_voiceless-postalveolar-fricative.mp3",
    "ʒ": "c_voiced-postalveolar-fricative.mp3",
    "h": "c_voiceless-glottal-fricative.mp3", "m": "c_voiced-bilabial-nasal.mp3",
    "n": "c_voiced-alveolar-nasal.mp3", "ŋ": "c_voiced-velar-nasal.mp3",
    "l": "c_voiced-alveolar-lateral-approximant.mp3", "r": "c_r.mp3",
    "j": "c_voiced-palatal-approximant.mp3",
    "w": "c_voiced-labiodental-approximant.mp3",
}

VOWELS = {"ɪ", "e", "æ", "ʌ", "ɒ", "ʊ", "ə", "iː", "ɑː", "ɔː", "uː", "ɜː",
          "eɪ", "aɪ", "ɔɪ", "aʊ", "əʊ", "ɪə", "eə", "ʊə"}

RMS_TARGET = 5000
PEAK_CAP = 30000
FADE = 0.015


def load(path):
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", path,
                    "-ac", "1", "-ar", "16000", "-f", "s16le", "/tmp/_ipa_tmp.raw"],
                   check=True)
    data = open("/tmp/_ipa_tmp.raw", "rb").read()
    return list(struct.unpack("<%dh" % (len(data) // 2), data))


def segments(s, th_ratio=0.12, min_ms=30, max_gap_ms=120):
    """按能量包络切出连续发声段（10 ms 一帧）。"""
    frame = 160
    env = []
    for i in range(0, len(s) - frame, frame):
        env.append(math.sqrt(sum(v * v for v in s[i:i + frame]) / frame))
    if not env:
        return [(0, len(s))]
    peak = max(env) or 1
    th = peak * th_ratio
    idx = [i for i, v in enumerate(env) if v > th]
    if not idx:
        return [(0, len(s))]
    segs = []
    a = idx[0]
    for prev, cur in zip(idx, idx[1:]):
        if (cur - prev) * 10 > max_gap_ms:
            segs.append((a * frame, (prev + 1) * frame))
            a = cur
    segs.append((a * frame, (idx[-1] + 1) * frame))
    return [(x, y) for x, y in segs if (y - x) / 16 >= min_ms]


def normalize(seg):
    rms = math.sqrt(sum(v * v for v in seg) / len(seg)) or 1
    gain = RMS_TARGET / rms
    out = [int(max(-PEAK_CAP, min(PEAK_CAP, v * gain))) for v in seg]
    fade = int(FADE * 16000)
    for i in range(min(fade, len(out))):
        k = i / fade
        out[i] = int(out[i] * k)
        out[-1 - i] = int(out[-1 - i] * k)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    for sym, name in SRC.items():
        path = os.path.join(WORK, name)
        if not os.path.exists(path):
            print("MISSING", sym, name)
            continue
        s = load(path)
        segs = segments(s)
        a, b = max(segs, key=lambda x: x[1] - x[0])
        seg = s[a:b]
        # 元音最多留 1.10s，辅音 0.75s（擦音/近音本来就长）
        limit = int((1.10 if sym in VOWELS else 0.75) * 16000)
        if len(seg) > limit:
            seg = seg[:limit]
            fade = int(0.06 * 16000)
            for i in range(fade):
                seg[len(seg) - fade + i] = int(seg[len(seg) - fade + i] * (1 - i / fade))
        seg = normalize(seg)
        with open(os.path.join(OUT, sym.replace("ː", "_") + ".raw"), "wb") as f:
            f.write(struct.pack("<%dh" % len(seg), *seg))
        print("%-4s %.2fs" % (sym, len(seg) / 16000))

    # 塞擦音：真实塞音爆破段 + 真实后齿龈擦音，拼合后同样归一化
    for sym, stop, fric in (("tʃ", "c_voiceless-alveolar-plosive.mp3",
                             "c_voiceless-postalveolar-fricative.mp3"),
                            ("dʒ", "c_voiced-alveolar-plosive.mp3",
                             "c_voiced-postalveolar-fricative.mp3")):
        s1 = normalize(load(os.path.join(WORK, stop)))[:int(0.09 * 16000)]
        s2 = normalize(load(os.path.join(WORK, fric)))[:int(0.22 * 16000)]
        seg = normalize(s1 + s2)
        with open(os.path.join(OUT, sym + ".raw"), "wb") as f:
            f.write(struct.pack("<%dh" % len(seg), *seg))
        print("%-4s %.2fs (拼接)" % (sym, len(seg) / 16000))

    print("输出 ->", OUT)


if __name__ == "__main__":
    main()
