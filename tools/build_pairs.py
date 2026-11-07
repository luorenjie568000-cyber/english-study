#!/usr/bin/env python3
"""
构建英中句对库（整句翻译用）。

输入：/tmp/eng.tsv.bz2, /tmp/cmn.tsv.bz2, /tmp/links.tar.bz2
输出：/tmp/es_assets/sent_zh.tsv.gz   英文句 \\t 简体中文译文

中文用 OpenCC t2s 转简体（Tatoeba 中文多为繁体）。
"""
import bz2
import gzip
import io
import os
import subprocess
import tarfile

ENG = "/tmp/eng.tsv.bz2"
CMN = "/tmp/cmn.tsv.bz2"
LINKS = "/tmp/links.tar.bz2"
OUT = "/tmp/es_assets/sent_zh.tsv.gz"
TMP_ZH = "/tmp/_zh_raw.txt"
TMP_ZH_S = "/tmp/_zh_sim.txt"

MIN_EN, MAX_EN = 6, 120
MIN_ZH, MAX_ZH = 2, 120

os.makedirs("/tmp/es_assets", exist_ok=True)

print("读英文…")
eng = {}
with bz2.open(ENG, "rt", encoding="utf-8", errors="replace") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) < 3:
            continue
        s = p[2].strip()
        if MIN_EN <= len(s) <= MAX_EN:
            eng[p[0]] = s
print("  %d 句" % len(eng))

print("读中文…")
cmn = {}
with bz2.open(CMN, "rt", encoding="utf-8", errors="replace") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) < 3:
            continue
        s = p[2].strip()
        if MIN_ZH <= len(s) <= MAX_ZH:
            cmn[p[0]] = s
print("  %d 句" % len(cmn))

print("扫描 links…")
pairs = {}
n = 0
with tarfile.open(LINKS, "r:bz2") as tar:
    for m in tar:
        if not m.isfile():
            continue
        fh = tar.extractfile(m)
        if fh is None:
            continue
        for raw in io.TextIOWrapper(fh, encoding="utf-8", errors="replace"):
            parts = raw.rstrip("\n").split("\t")
            if len(parts) != 2:
                continue
            a, b = parts
            n += 1
            if a in eng and b in cmn:
                pairs.setdefault(eng[a], cmn[b])
            elif b in eng and a in cmn:
                pairs.setdefault(eng[b], cmn[a])
print("  扫描 %d 条，得到 %d 对" % (n, len(pairs)))

# 繁转简
print("繁转简…")
items = list(pairs.items())
with open(TMP_ZH, "w", encoding="utf-8") as f:
    f.write("\n".join(zh for _, zh in items))
subprocess.run(["opencc", "-c", "t2s", "-i", TMP_ZH, "-o", TMP_ZH_S], check=True)
with open(TMP_ZH_S, encoding="utf-8") as f:
    simp = f.read().split("\n")

with gzip.open(OUT, "wt", encoding="utf-8") as g:
    for i, (en, zh) in enumerate(items):
        z = simp[i].strip() if i < len(simp) and simp[i].strip() else zh
        g.write(en.replace("\t", " ") + "\t" + z.replace("\t", " ") + "\n")

os.remove(TMP_ZH)
os.remove(TMP_ZH_S)
print("输出: %s  %.2f MB" % (OUT, os.path.getsize(OUT) / 1048576))
