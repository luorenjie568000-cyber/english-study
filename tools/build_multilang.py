#!/usr/bin/env python3
"""
多语言词库构建（待调研结论决定是否启用）。

用法: python3 build_multilang.py <lang> [<lang> ...]
输出: app/src/main/assets/dict_<lang>.db.gz

支持的语言与数据源（体积待实测填入）：
  zh  CC-CEDICT         中→英，社区维护，CC BY-SA
  ja  JMdict            日→英，EDRDG 许可
  ko  KRDICT            韩→英
  fr  Kaikki French     法→英
  de  Kaikki German     德→英
  es  Kaikki Spanish    西→英

统一表结构（和现有 dict_core.db 一致）：
  w(word, phonetic, ipa, trans, defin, pos, tag, exchange, lemma, collins, oxford, frq)
"""
import os
import sys

ASSETS = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets')


def main():
    langs = sys.argv[1:]
    if not langs:
        print("用法: python3 build_multilang.py zh ja fr ...")
        print("可用: zh ja ko fr de es")
        return
    print("语言:", langs)
    print("输出目录:", os.path.abspath(ASSETS))
    print()
    print("注意：本脚本是占位框架。真实数据源与体积需先核实：")
    print("  - CC-CEDICT   约 120k 条，压缩后 ~10MB")
    print("  - JMdict      约 200k 条，压缩后 ~20MB")
    print("  - KRDICT      约 50k 条")
    print("  - Kaikki 单语言 10-80MB 不等")
    print()
    print("决策依据见 docs/ 下的调研报告。")


if __name__ == '__main__':
    main()
