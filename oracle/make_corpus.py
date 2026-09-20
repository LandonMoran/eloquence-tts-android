#!/usr/bin/env python3
"""Generate oracle corpora: hanzi sweep + sample sentences per dialect.
Outputs go to oracle/corpus/. Run: python3 make_corpus.py
"""
import os
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "corpus")
os.makedirs(OUT, exist_ok=True)


def hanzi_sweep():
    path = os.path.join(OUT, "hanzi-sweep.txt")
    with open(path, "w", encoding="utf-8") as f:
        for cp in range(0x3400, 0x9FFF + 1):
            ch = chr(cp)
            name = unicodedata.name(ch, "")
            if not name.startswith("CJK UNIFIED IDEOGRAPH"):
                continue
            f.write(ch + "\n")
    print("wrote", path)


def cjk_sweep():
    """Common CJK Unified (U+4E00-U+9FFF): the hanzi chsrom actually maps.
    The full sweep starts at U+3400 (CJK Ext A), which the engine barely
    resolves; the trace/dump sweeps for zh-cn/zh-tw must use this file."""
    path = os.path.join(OUT, "hanzi-cjk-sweep.txt")
    with open(path, "w", encoding="utf-8") as f:
        for cp in range(0x4E00, 0x9FFF + 1):
            ch = chr(cp)
            name = unicodedata.name(ch, "")
            if not name.startswith("CJK UNIFIED IDEOGRAPH"):
                continue
            f.write(ch + "\n")
    print("wrote", path)


def samples():
    data = [
        ("zh-cn", ["你好，世界。",
                   "这是一个测试。",
                   "今天天气很好，我们去公园散步。。",
                   "一，二，三。。"]),
        ("zh-tw", ["你好，世界。。",
                   "這是一個測試。。",
                   "今天天氣很好，我們去公園散步。。",
                   "一，二，三。。"]),
        ("ko", ["안녕하세요, 세계.",
                "이것은 테스트입니다.",
                "오늘 날씨가 좋아서 우리는 공원에서 산책합니다.",
                "값이 싸다. 저는 한국어를 공부합니다."]),
        ("ja", ["こんにちは、世界。。",
                "これはテストです。。",
                "今日は天気がいいので、公園を散歩します。。"]),
    ]
    for name, lines in data:
        path = os.path.join(OUT, name + "-sample.txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        print("wrote", path)


if __name__ == "__main__":
    hanzi_sweep()
    cjk_sweep()
    samples()