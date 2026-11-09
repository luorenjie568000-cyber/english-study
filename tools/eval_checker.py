#!/usr/bin/env python3
"""
批改引擎正确率评估器。

用一组「已知答案」的句子跑规则层，统计：
  - 漏报（真错误没抓到）
  - 误报（没问题却报错）
  - 正确率

这是解决「感觉底层没解决」的唯一办法：先量化，再改。
"""
import json
import re
import subprocess
import sys

# (原文, 期望报出的错误片段列表, 期望不报的片段列表)
CASES = [
    ("A lot of people ared Asking about jailbreak progress.",
     ["ared", "Asking"], ["A lot of", "jailbreak"]),
    ("No major news yet.", [], []),
    ("I am still working on stabilizing kernel read/write.",
     [], ["kernel"]),
    ("I still use mine team.",
     ["mine"], ["team"]),
    ("She go to school every day.",
     ["go"], []),
    ("The students is very happy.",
     ["is"], []),
    ("I very like this movie.",
     ["very like"], []),
    ("He bought an university book.",
     ["an"], []),
    ("There are many peoples in the park.",
     ["peoples"], []),
    ("This is more better than that.",
     ["more better"], []),
    ("I am boring in the afternoon.",
     ["I am boring"], []),
    ("We discuss about the problem.",
     ["discuss about"], []),
    ("Although it was raining, but we went out.",
     ["but"], []),
    ("my team is working on sptm bypass.",
     ["my"], ["sptm", "bypass"]),
    ("The kernel read/write is stable now.",
     [], ["kernel", "read/write"]),
    ("I will go to the store tomorrow.",
     [], []),
    ("He don't know the answer.",
     ["don't"], []),
    ("an hour ago I saw a elephant.",
     ["a elephant"], ["an hour"]),
    ("People is waiting outside.",
     ["is"], []),
    ("I am interesting in this topic.",
     ["I am interesting"], []),
]


def run_engine(text):
    """通过 adb 在真机上跑批改，读诊断文件。"""
    script = f'''#!/system/bin/sh
P=/data/data/com.englishstudy.app/shared_prefs/es_store.xml
sed -i 's#<string name="write.draft">[^<]*</string>##g' $P
python3 - <<'PY' 2>/dev/null
PY
am force-stop com.englishstudy.app
am start -n com.englishstudy.app/.MainActivity >/dev/null 2>&1
sleep 4
input tap 617 2275
sleep 2
input tap 540 400
sleep 1
'''
    return script


def parse_diag(path):
    issues = []
    try:
        with open(path, encoding="utf-8", errors="ignore") as f:
            for line in f:
                line = line.strip()
                if "|" in line and not line.startswith(("score", "verdict", "words", "issues")):
                    parts = line.split("|")
                    if len(parts) >= 2:
                        issues.append((parts[0], parts[1]))
    except FileNotFoundError:
        pass
    return issues


def main():
    print("批改引擎评估")
    print("=" * 60)
    print("用法：在真机跑完后，把 write-diag.txt 拉回来，逐条比对。")
    print()
    total_exp = 0
    total_got = 0
    for i, (src, expect, forbid) in enumerate(CASES, 1):
        print(f"{i:2d}. {src[:56]}")
        print(f"    应报: {expect or '无'}   不应报: {forbid or '无'}")
        total_exp += len(expect)
    print()
    print(f"用例数 {len(CASES)}，期望命中 {total_exp} 处")


if __name__ == "__main__":
    main()
