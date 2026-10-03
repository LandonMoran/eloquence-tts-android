import glob

p = glob.glob("src/com/xw/*/update/UpdateActions.kt")[0]
lines = open(p, encoding="utf-8").read().split("\n")

depth = 0
for i, raw in enumerate(lines, 1):
    net = raw.count("{") -  raw.count("}")
    if net != 0:
        depth += net
        print(f"L{i:3} net={net:+d} depth={depth:+d} | {raw.strip()[:80]}")
print("final depth:", depth)