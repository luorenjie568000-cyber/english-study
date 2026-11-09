#!/usr/bin/env python3
"""批改引擎正确率评估：20 条已知答案，统计漏报/误报。"""
import re
import subprocess

# (原文, 应报出的片段, 绝不能报的片段)
CASES = [
    ("A lot of people ared Asking about jailbreak progress.",
     ["ared", "Asking"], ["A lot of", "jailbreak"]),
    ("No major news yet.", [], []),
    ("I am still working on stabilizing kernel read/write.",
     [], ["kernel"]),
    ("I still use mine team.", ["mine"], ["team"]),
    ("The students is very happy.", ["is"], []),
    ("I very like this movie.", ["very like"], []),
    ("He bought an university book.", ["an"], []),
    ("This is more better than that.", ["more better"], []),
    ("I am boring in the afternoon.", ["boring"], []),
    ("We discuss about the problem.", ["about"], []),
    ("my team is working on sptm bypass.", [], ["sptm", "bypass", "my"]),
    ("The kernel read/write is stable now.", [], ["kernel"]),
    ("I will go to the store tomorrow.", [], []),
    ("an hour ago I saw a elephant.", ["elephant"], ["hour"]),
    ("People is waiting outside.", ["is"], []),
    ("I am interesting in this topic.", ["interesting"], []),
    ("calif's primitives are not public yet.", [], ["calif"]),
    ("They was very tired.", ["was"], []),
    ("She go to school every day.", [], []),
    ("He don't know the answer.", [], []),
]


def push_text(dev_text):
    """把文本写进 App 草稿，启动并点批改，返回诊断里的问题片段。"""
    script = f'''P=/data/data/com.englishstudy.app/shared_prefs/es_store.xml
sed -i 's#<string name="write.draft">[^<]*</string>##g' $P
sed -i 's#</map>#<string name="write.draft">{dev_text}</string>\\n</map>#' $P
chown u0_a506:u0_a506 $P
am force-stop com.englishstudy.app
am start -n com.englishstudy.app/.MainActivity >/dev/null 2>&1
sleep 4
input tap 617 2275
sleep 2
input tap 229 883
sleep 3
cat /data/data/com.englishstudy.app/files/write-diag.txt'''
    open('/var/minis/mounts/work_space/es/eval_run.sh', 'w').write(script)
    out = subprocess.run(
        ["android-shizuku-cli", "exec",
         'su -c "sh /sdcard/work_space/es/eval_run.sh"'],
        capture_output=True, text=True, timeout=180).stdout
    hits = re.findall(r'(拼写|语法|搭配|冠词|大小写|标点|重复|句长)\|([^|\\]+)\|', out)
    return [h[1] for h in hits]


def main():
    tp = fp = fn = 0
    print(f"{'#':<3}{'句子':<50}{'应报':<24}{'实报'}")
    print("-" * 112)
    for i, (src, expect, forbid) in enumerate(CASES, 1):
        try:
            got = push_text(src)
        except Exception as e:
            got = []
            print(f"   ! {e}")
        got_l = [g.lower() for g in got]
        missed = [e for e in expect if not any(e.lower() in g for g in got_l)]
        wrong = [g for g in got_l if any(f.lower() in g for f in forbid)]
        tp += len(expect) - len(missed)
        fn += len(missed)
        fp += len(wrong)
        print(f"{i:<3}{src[:48]:<50}{str(expect):<24}{got}")
        if missed:
            print(f"     >>> 漏报: {missed}")
        if wrong:
            print(f"     >>> 误报: {wrong}")
    print("-" * 112)
    prec = tp / (tp + fp) * 100 if tp + fp else 100
    rec = tp / (tp + fn) * 100 if tp + fn else 100
    print(f"命中 {tp} · 漏报 {fn} · 误报 {fp}")
    print(f"精确率 {prec:.0f}%   召回率 {rec:.0f}%")


if __name__ == "__main__":
    main()
