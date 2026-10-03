import glob, re

files = [
    "update/UpdateActions.kt",
    "update/ElqUpdateChecker.kt",
    "utils/CrashCodeDefender.kt",
]

root = glob.glob("src/com/xw/*/")[0]
for rel in files:
    p = root + rel
    s = open(p, encoding="utf-8").read().split("\n")
    d = 0
    print("===== " + rel)
    for i, raw in enumerate(s, 1):
        ln = re.sub(r'"[^"\n]*"', '', raw)
        ln = re.sub(r"'[^'\n]'", '', ln)
        ln = re.sub(r"//[^\n]*", '', ln)
        # split on the line into paren/brace-bearing tokens to spot per-line asymmetry
        ob = ln.count("(") -  ln.count(")")
        obr = ln.count("{") -  ln.count("}")
        if ob != 0 or obr != 0:
            d2 = d + ob + obr
            flag = " <--" if (d2 < 0 or (ob != 0 and obr != 0 and d2 < d)) else ""
            print(f"L{i:3} dpre={d:3} +({ob:+d}{obr:+d}) -> {d2:+d} | {raw.strip()[:72]}{flag}")
            d = d2
        else:
            d = d + ob + obr
    print("final depth=%d" % d)