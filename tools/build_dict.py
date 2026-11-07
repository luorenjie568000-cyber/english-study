#!/usr/bin/env python3
"""
从 ECDICT 生成 Android 用的 SQLite 词库。

产出 /tmp/es_assets/dict_core.db：
  w(word, phonetic, trans, pos, collins, oxford, tag, frq, exchange, lemma, defin, ipa)
  lemma(inflected, base)

只保留 frq<=30000 的核心词（约 3 万），外加所有带 gk/zk 标签的词，
保证高考/中考词表完整。标准 IPA 从 ipa-dict 合并。
"""
import csv
import os
import re
import sqlite3
import subprocess
import sys

CSV = "/tmp/ecdict.csv"
IPA = "/tmp/ipa_en.txt"
OUT = "/tmp/es_assets/dict_core.db"

os.makedirs("/tmp/es_assets", exist_ok=True)
if os.path.exists(OUT):
    os.remove(OUT)

csv.field_size_limit(10 * 1024 * 1024)


def norm_phonetic(p):
    """ECDICT 音标不是标准 IPA，这里只做展示兜底；优先用 ipa-dict。"""
    return (p or "").strip()


def clean(s):
    """把换行/制表压成空格。"""
    return " ".join((s or "").replace("\t", " ").split())


# ---------- 1) 读 ECDICT，挑核心词 ----------
rows = []
lemma_rows = []
with open(CSV, encoding="utf-8", errors="replace", newline="") as f:
    rd = csv.DictReader(f)
    for rec in rd:
        word = (rec.get("word") or "").strip()
        if not word:
            continue
        tag = (rec.get("tag") or "").strip()
        frq_s = (rec.get("frq") or "0").strip()
        bnc_s = (rec.get("bnc") or "0").strip()
        try:
            frq = int(frq_s)
        except ValueError:
            frq = 0
        try:
            bnc = int(bnc_s)
        except ValueError:
            bnc = 0

        is_exam = ("gk" in tag.split()) or ("zk" in tag.split())
        is_core = (frq > 0 and frq <= 30000) or (bnc > 0 and bnc <= 30000)
        if not (is_exam or is_core):
            continue

        ex = (rec.get("exchange") or "").strip()
        base = ""
        for seg in ex.split("/"):
            if seg.startswith("0:"):
                base = seg[2:].strip().lower()

        rows.append((
            word,
            norm_phonetic(rec.get("phonetic")),
            clean(rec.get("translation")),
            clean(rec.get("pos")),
            (rec.get("collins") or "").strip(),
            (rec.get("oxford") or "").strip(),
            tag,
            frq if frq > 0 else bnc,
            ex,
            base if base and base != word.lower() else "",
            clean(rec.get("definition")),
            "",  # ipa 稍后填
        ))
        if base and base != word.lower():
            lemma_rows.append((word.lower(), base))

print("核心词条: %d" % len(rows))
lemma_rows = list(dict.fromkeys(lemma_rows))
print("词形映射: %d" % len(lemma_rows))

# ---------- 2) 标准 IPA ----------
ipa_map = {}
if os.path.exists(IPA):
    with open(IPA, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) != 2:
                continue
            w, ph = p[0].strip().lower(), p[1].strip()
            if w and ph.startswith("/"):
                ipa_map.setdefault(w, ph.strip("/"))
    print("IPA 词条: %d" % len(ipa_map))

# ---------- 3) 建库 ----------
db = sqlite3.connect(OUT)
db.execute("PRAGMA journal_mode=OFF")
db.execute("PRAGMA synchronous=OFF")
db.execute("""CREATE TABLE w(
    word TEXT PRIMARY KEY,
    phonetic TEXT, trans TEXT, pos TEXT, collins TEXT, oxford TEXT,
    tag TEXT, frq INTEGER, exchange TEXT, lemma TEXT, defin TEXT, ipa TEXT)""")
db.execute("CREATE TABLE lemma(inflected TEXT PRIMARY KEY, base TEXT)")

final = []
for r in rows:
    key = r[0].lower()
    ipa = ipa_map.get(key, "")
    final.append(r[:11] + (ipa,))

db.executemany("INSERT OR REPLACE INTO w VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", final)
db.executemany("INSERT OR REPLACE INTO lemma VALUES (?,?)", lemma_rows)
db.execute("CREATE INDEX idx_frq ON w(frq)")
db.execute("CREATE INDEX idx_tag ON w(tag)")
db.commit()
db.execute("VACUUM")
db.close()

size = os.path.getsize(OUT)
print("dict_core.db: %.2f MB" % (size / 1048576))
n_ipa = sum(1 for r in final if r[11])
print("带标准 IPA 的词: %d / %d (%.1f%%)" % (n_ipa, len(final), 100.0 * n_ipa / max(1, len(final))))
