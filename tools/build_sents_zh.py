#!/usr/bin/env python3
"""
重建例句库：每条例句都优先带中文译文。

输入：
  /tmp/eng.tsv.bz2      Tatoeba 英语句
  /tmp/cmn.tsv.bz2      Tatoeba 中文句
  /tmp/links.tar.bz2    Tatoeba 句对链接
输出：
  /tmp/es_assets/sents4.tsv.gz
      word \\t en1 \\x1f zh1 \\x1e en2 \\x1f zh2 \\x1e ...

规则：
  - 句子长度 8-90 字符
  - 优先选有中文译文的句子；不足 4 句时用无译文的补齐
  - 每词最多 4 句，短句优先（8-60 更易读）
"""
import bz2
import gzip
import os
import re
import sqlite3
import tarfile
import io

ENG = "/tmp/eng.tsv.bz2"
CMN = "/tmp/cmn.tsv.bz2"
LINKS = "/tmp/links.tar.bz2"
DB = "/tmp/es_assets/dict_core.db"
OUT = "/tmp/es_assets/sents4.tsv.gz"

MAX_PER_WORD = 4
MIN_LEN, MAX_LEN = 8, 90

os.makedirs("/tmp/es_assets", exist_ok=True)


def norm(s):
    s = s.lower().replace("\u2019", "'")
    s = re.sub(r"[^a-z0-9' ]", " ", s)
    return re.sub(r"\s+", " ", s).strip()


print("读英文句…")
eng = {}
with bz2.open(ENG, "rt", encoding="utf-8") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3:
            eng[p[0]] = p[2]
print("  %d 句" % len(eng))

print("读中文句…")
cmn = {}
with bz2.open(CMN, "rt", encoding="utf-8") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3:
            cmn[p[0]] = p[2]
print("  %d 句" % len(cmn))

print("读句对链接…")
# links.csv: sentence_id \t translation_id
en2zh = {}
with tarfile.open(LINKS, "r:bz2") as tf:
    for member in tf:
        if not member.name.endswith("links.csv"):
            continue
        fh = tf.extractfile(member)
        for raw in io.TextIOWrapper(fh, encoding="utf-8"):
            p = raw.rstrip("\n").split("\t")
            if len(p) < 2:
                continue
            a, b = p[0], p[1]
            if a in eng and b in cmn:
                en2zh.setdefault(a, []).append(b)
            elif b in eng and a in cmn:
                en2zh.setdefault(b, []).append(a)
print("  %d 条英文句有中文译文" % len(en2zh))

print("读目标词与词形…")
db = sqlite3.connect(DB)
targets = set()
for (w,) in db.execute("SELECT word FROM w WHERE tag LIKE '%gk%' OR tag LIKE '%zk%'"):
    targets.add(w.lower())
inflect = {}
for inf, base in db.execute("SELECT inflected, base FROM lemma"):
    inflect[inf.lower()] = base.lower()
db.close()
print("  目标词 %d / 词形 %d" % (len(targets), len(inflect)))

# 每个词 -> [(len, en, zh)]，译文优先
cand = {w: [] for w in targets}
TOKEN = re.compile(r"[a-zA-Z']+")


def forms_of(word):
    out = {word}
    for inf, base in inflect.items():
        if base == word:
            out.add(inf)
    return out


print("扫句子…")
# 反向索引：把目标词和它的词形映射到一个集合，扫句子时逐 token 命中
form2targets = {}
for w in targets:
    for f in forms_of(w):
        form2targets.setdefault(f, []).append(w)

scanned = 0
for sid, text in eng.items():
    n = len(text)
    if n < MIN_LEN or n > MAX_LEN:
        continue
    scanned += 1
    toks = {t.lower().strip("'") for t in TOKEN.findall(text)}
    hits = set()
    for t in toks:
        for w in form2targets.get(t, ()):
            hits.add(w)
        if t in targets:
            hits.add(t)
    if not hits:
        continue
    zh = ""
    if sid in en2zh:
        zh = cmn[en2zh[sid][0]].strip()
        zh = zh.replace("\t", " ").replace("\x1f", " ").replace("\x1e", " ")
        if len(zh) < 2 or len(zh) > 120:
            zh = ""
    rec = (n, text.strip(), zh)
    for w in hits:
        cand[w].append(rec)

print("  扫描 %d 句" % scanned)

with gzip.open(OUT, "wt", encoding="utf-8") as out:
    total = 0
    withzh = 0
    for w in sorted(cand):
        items = cand[w]
        if not items:
            continue
        # 有译文的排前面，各自按句长（8-60 优先）排序
        items.sort(key=lambda r: (0 if r[2] else 1, 0 if 8 <= r[0] <= 60 else 1, r[0]))
        picked = []
        seen = set()
        for n, en, zh in items:
            key = norm(en)
            if key in seen:
                continue
            seen.add(key)
            picked.append((en, zh))
            if len(picked) >= MAX_PER_WORD:
                break
        if not picked:
            continue
        seg = "\x1e".join(e + "\x1f" + z for e, z in picked)
        out.write(w + "\t" + seg + "\n")
        total += 1
        withzh += sum(1 for _, z in picked if z)
print("写出 %d 词，%d 条例句（其中带译文）" % (total, withzh))
print("大小 %.2f MB" % (os.path.getsize(OUT) / 1048576))
