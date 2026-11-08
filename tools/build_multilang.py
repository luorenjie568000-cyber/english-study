#!/usr/bin/env python3
"""
多语言词典构建（按需下载包，不进主包）。

设计依据（见 docs/multilang-report.md）：
  - 只做「中文 / 日文 / 韩文」三门，这是绝大多数中国用户的真实需求
  - 法/德/西 的 Kaikki 数据 90-120MB 且释义是英文，投入产出比不成立，暂不做
  - 每门语言的词典做成独立 .db.gz，用户在「词库」里自己下载

支持的数据源（体积为实测）：
  zh  CC-CEDICT    cedict_1_0_ts_utf-8_mdbg.txt.gz   3.98MB → SQLite ~10.3MB
  ja  JMdict_e     JMdict_e.gz                      10.59MB → SQLite ~22MB
  ko  kengdic      kengdic.tsv                      11.65MB → SQLite ~9MB

输出：app/src/main/assets/multilang/dict_<lang>.db.gz
统一表结构（与 dict_core.db 一致）：
  w(word, phonetic, ipa, trans, defin, pos, tag, exchange, lemma, collins, oxford, frq)
  多语言表只需要 w + trans + pinyin(存 phonetic) 三列，但保留完整结构以便复用查询代码。

用法：
  python3 build_multilang.py zh         # 只做中文
  python3 build_multilang.py zh ja ko   # 全做
"""
import gzip
import os
import re
import sqlite3
import sys
import urllib.request

OUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'multilang')
CACHE = '/tmp/multilang'

SOURCES = {
    'zh': {
        'url': 'https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz',
        'name': 'CC-CEDICT',
        'license': 'CC BY-SA 4.0',
    },
    'ja': {
        'url': 'http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz',
        'name': 'JMdict',
        'license': 'EDRDG CC BY-SA',
    },
    'ko': {
        'url': 'https://raw.githubusercontent.com/garfieldnate/kengdic/master/kengdic.tsv',
        'name': 'kengdic',
        'license': 'MPL 2.0 / LGPL 2.0+',
    },
}

SCHEMA = '''
CREATE TABLE w (
  word TEXT PRIMARY KEY,
  phonetic TEXT, ipa TEXT, trans TEXT, defin TEXT, pos TEXT,
  tag TEXT, exchange TEXT, lemma TEXT,
  collins INTEGER, oxford INTEGER, frq INTEGER
);
CREATE INDEX idx_frq ON w(frq);
'''


def fetch(lang):
    src = SOURCES[lang]
    os.makedirs(CACHE, exist_ok=True)
    fn = os.path.join(CACHE, os.path.basename(src['url']))
    if os.path.exists(fn) and os.path.getsize(fn) > 1000:
        print('  缓存命中', fn)
        return fn
    print('  下载', src['url'])
    urllib.request.urlretrieve(src['url'], fn)
    print('  完成', os.path.getsize(fn) // 1024, 'KB')
    return fn


def build_zh(path, db):
    """CC-CEDICT: 傳統 简体 [pin1 yin1] /def1/def2/"""
    pat = re.compile(r'^(\S+)\s+(\S+)\s+\[([^\]]+)\]\s+/(.+)/$')
    n = 0
    with gzip.open(path, 'rt', encoding='utf-8') as f:
        for line in f:
            if line.startswith('#'):
                continue
            m = pat.match(line.strip())
            if not m:
                continue
            trad, simp, pinyin, defs = m.groups()
            trans = '；'.join(defs.split('/'))
            # 简繁都建一条，繁体作为别名
            for w in {trad, simp}:
                if not w:
                    continue
                db.execute(
                    'INSERT OR REPLACE INTO w(word,phonetic,trans,pos,tag,exchange,lemma,'
                    'collins,oxford,frq) VALUES(?,?,?,?,?,?,?,?,?,?)',
                    (w, pinyin, trans, '', 'zh', '', '', 0, 0, 0))
                n += 1
    return n


def build_ja(path, db):
    """JMdict_e XML: <entry><keb>日</keb><reb>かな</reb><sense><gloss>eng</gloss>"""
    n = 0
    cur = None
    kana = []
    gloss = []
    with gzip.open(path, 'rt', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if '<entry>' in line:
                cur, kana, gloss = [], [], []
            elif '<keb>' in line or '<reb>' in line:
                m = re.search(r'<(keb|reb)>(.*?)</\1>', line)
                if m:
                    (kana if m.group(1) == 'reb' else cur).append(m.group(2))
            elif '<gloss' in line:
                m = re.search(r'<gloss[^>]*>(.*?)</gloss>', line)
                if m and len(gloss) < 3:
                    gloss.append(m.group(1))
            elif '</entry>' in line:
                if cur and gloss:
                    trans = '；'.join(gloss)
                    k = kana[0] if kana else ''
                    for w in cur:
                        db.execute(
                            'INSERT OR REPLACE INTO w(word,phonetic,trans,pos,tag,exchange,'
                            'lemma,collins,oxford,frq) VALUES(?,?,?,?,?,?,?,?,?,?)',
                            (w, k, trans, '', 'ja', '', '', 0, 0, 0))
                        n += 1
    return n


def build_ko(path, db):
    """kengdic.tsv 实际列序: id | surface | hanja | gloss | level | created | source"""
    n = 0
    with open(path, encoding='utf-8', errors='replace') as f:
        for i, line in enumerate(f):
            if i == 0:
                continue
            p = line.rstrip('\n').split('\t')
            if len(p) < 4:
                continue
            surface = p[1].strip()
            hanja = p[2].strip()
            gloss = p[3].strip()
            if not surface or not gloss:
                continue
            # surface 里可能有多个词用空格分隔（"전원의 행진"），拆开各建一条
            for w in surface.split():
                db.execute(
                    'INSERT OR REPLACE INTO w(word,phonetic,trans,pos,tag,exchange,'
                    'lemma,collins,oxford,frq) VALUES(?,?,?,?,?,?,?,?,?,?)',
                    (w, hanja, gloss.replace('; ', '；'), '', 'ko', '', '', 0, 0, 0))
                n += 1
    return n


def main():
    langs = sys.argv[1:] or ['zh']
    os.makedirs(OUT_DIR, exist_ok=True)
    for lang in langs:
        if lang not in SOURCES:
            print('跳过未知语言:', lang)
            continue
        src = SOURCES[lang]
        print('==', lang, src['name'], '(' + src['license'] + ')')
        raw = fetch(lang)
        tmp = os.path.join(CACHE, 'dict_%s.db' % lang)
        if os.path.exists(tmp):
            os.remove(tmp)
        db = sqlite3.connect(tmp)
        db.executescript(SCHEMA)
        if lang == 'zh':
            n = build_zh(raw, db)
        elif lang == 'ja':
            n = build_ja(raw, db)
        else:
            n = build_ko(raw, db)
        db.commit()
        db.execute('VACUUM')
        db.close()
        size = os.path.getsize(tmp)
        print('  条目 %d, SQLite %.1f MB' % (n, size / 1048576))

        out = os.path.join(OUT_DIR, 'dict_%s.db.gz' % lang)
        with open(tmp, 'rb') as fi, gzip.open(out, 'wb', 9) as fo:
            fo.write(fi.read())
        print('  ->', out, '%.1f MB' % (os.path.getsize(out) / 1048576))


if __name__ == '__main__':
    main()
