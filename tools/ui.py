#!/usr/bin/env python3
"""设备 UI 自动化小工具：dump 界面、按文本点击、截图。

用法：
  python3 ui.py dump                 # 打印所有文本+坐标
  python3 ui.py tap "记得"            # 按文本包含匹配点击
  python3 ui.py tap "记得" exact      # 精确匹配
  python3 ui.py shot name.png        # 截图到挂载目录
"""
import re
import subprocess
import sys

MOUNT = "/var/minis/mounts/work_space/es"
DEV_XML = "/sdcard/work_space/es/ui_auto.xml"
LOCAL_XML = MOUNT + "/ui_auto.xml"


def sh(cmd):
    return subprocess.run(["android-shizuku-cli"] + cmd, capture_output=True,
                          text=True).stdout


def dump():
    sh(["exec", "uiautomator dump %s >/dev/null 2>&1" % DEV_XML])
    try:
        with open(LOCAL_XML, encoding="utf-8") as f:
            out = f.read()
    except OSError:
        return []
    items = []
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', out):
        t = m.group(1).strip()
        if not t:
            continue
        x1, y1, x2, y2 = (int(m.group(i)) for i in range(2, 6))
        items.append((t, (x1 + x2) // 2, (y1 + y2) // 2))
    return items


def tap_text(needle, exact=False):
    for t, x, y in dump():
        hit = (t == needle) if exact else (needle in t)
        if hit:
            sh(["input", "tap", str(x), str(y)])
            print("tap", t, x, y)
            return True
    print("not found:", needle)
    return False


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "dump"
    if cmd == "dump":
        for t, x, y in dump():
            print("%-42s %4d,%4d" % (t[:42], x, y))
    elif cmd == "tap":
        tap_text(sys.argv[2], exact=(len(sys.argv) > 3 and sys.argv[3] == "exact"))
    elif cmd == "shot":
        name = sys.argv[2]
        sh(["exec", "screencap -p /sdcard/work_space/es/%s" % name])
        print("%s/%s" % (MOUNT, name))
