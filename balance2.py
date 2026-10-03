import glob, re

files = [
    "update/UpdateActions.kt",
    "update/ElqUpdateChecker.kt",
    "utils/CrashCodeDefender.kt",
]

root = glob.glob("src/com/xw/*/")[0]
for rel in files:
    p = root + rel
    s = open(p, encoding="utf-8").read()
    s = re.sub(r'"[^"\n]*"', '', s)          # double-quoted strings
    s = re.sub(r"'[^'\n]'", '', s)           # char literals
    s = re.sub(r"//[^\n]*", '', s)           # line comments
    ob = s.count("("); cb = s.count(")")
    obr = s.count("{"); cbr = s.count("}")
    sq = s.count("["); csq = s.count("]")
    ok = ob == cb and obr == cbr
  
    print(("OK " if ok else "BAD ") + rel + "  (: %d/%d  {: %d/%d" % (ob, cb, obr, cbr))