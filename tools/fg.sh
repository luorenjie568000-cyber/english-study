#!/bin/sh
# 把 App 拉到前台，等稳定后截图/操作
android-shizuku-cli exec 'am start -n com.englishstudy.app/.MainActivity' >/dev/null 2>&1
sleep 1.5
"$@"
