#!/usr/bin/env python3
"""
为高考/中考词表构建多例句库。

输入：/tmp/eng.tsv.bz2（Tatoeba 英语句）
输出：/tmp/es_assets/sents4.tsv.gz
      word \\t 句1 \\x1f 句2 \\x1f 句3 \\x1f 句4

匹配规则：
  - 句子长度 8-90 字符
  - 必须包含目标词或其合法词形（查 SQLite lemma 表 + 规则兜底）
  - 每词最多 4 句，按句长适中优先（8-60 更易读）
"""
import bz2
import gzip
import os
import re
import sqlite3

ENG = "/tmp/eng.tsv.bz2"
DB = "/tmp/es_assets/dict_core.db"
OUT = "/tmp/es_assets/sents4.tsv.gz"
MAX_PER_WORD = 4
MIN_LEN = 8
MAX_LEN = 90

os.makedirs("/tmp/es_assets", exist_ok=True)

# ---------- 1) 目标词 ----------
targets = set()
db = sqlite3.connect(DB)
for (w,) in db.execute("SELECT word FROM w WHERE tag LIKE '%gk%' OR tag LIKE '%zk%'"):
    targets.add(w.lower())
print("目标词: %d" % len(targets))

# ---------- 2) 词形 -> 原形 ----------
inflect = {}
for inf, base in db.execute("SELECT inflected, base FROM lemma"):
    inflect[inf] = base
db.close()
print("词形映射: %d" % len(inflect))


def variants(word):
    """目标词可能出现的所有形式（原形 + 常见变形）。"""
    v = {word}
    v.add(word + "s")
    v.add(word + "es")
    v.add(word + "ed")
    v.add(word + "ing")
    if word.endswith("e"):
        v.add(word[:-1] + "ing")
        v.add(word + "d")
    if word.endswith("y"):
        v.add(word[:-1] + "ies")
        v.add(word[:-1] + "ied")
    return v


# 反向索引：变形 -> 目标词集合
rev = {}
for t in targets:
    for f in variants(t):
        rev.setdefault(f, set()).add(t)

WORD_RE = re.compile(r"[A-Za-z][A-Za-z'-]*")
picked = {t: [] for t in targets}

# ---------- 3) 扫句 ----------
print("扫描句子…")
total = 0
with bz2.open(ENG, "rt", encoding="utf-8", errors="replace") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) < 3:
            continue
        s = p[2].strip()
        n = len(s)
        if n < MIN_LEN or n > MAX_LEN:
            continue
        total += 1

        hit = set()
        for m in WORD_RE.finditer(s):
            w = m.group(0).lower()
            if w in rev:
                hit |= rev[w]
            elif w in inflect and inflect[w] in targets:
                hit.add(inflect[w])

        for t in hit:
            bucket = picked[t]
            if len(bucket) >= MAX_PER_WORD:
                continue
            # 同模板去重：句首 4 词相同视为重复
            head = " ".join(s.split()[:4]).lower()
            if any(head == " ".join(x.split()[:4]).lower() for x in bucket):
                continue
            # 8-60 字符优先，其余降级
            if n <= 60 or len(bucket) < MAX_PER_WORD - 1:
                bucket.append(s)

print("扫描 %d 句" % total)

# ---------- 4) 写盘 ----------
dist = {}
lines = []
for t in sorted(targets):
    b = picked[t][:MAX_PER_WORD]
    if not b:
        continue
    dist[len(b)] = dist.get(len(b), 0) + 1
    lines.append(t + "\t" + "\x1f".join(b))

with gzip.open(OUT, "wt", encoding="utf-8") as g:
    g.write("\n".join(lines))

covered = len(lines)
print("有例句的词: %d / %d (%.1f%%)" % (covered, len(targets), 100.0 * covered / len(targets)))
print("句数分布:", dict(sorted(dist.items())))
print("输出: %s  %.2f MB" % (OUT, os.path.getsize(OUT) / 1048576))
