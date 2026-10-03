import glob, re

files = [
    "update/UpdateActions.kt",
    "update/ElqUpdateChecker.kt",
    "update/ElqUpdateInstaller.kt",
    "ui/SettingsActivity.kt",
    "utils/CrashCodeDefender.kt",
]

root = glob.glob("src/com/xw/*/")[0]
all_ok = True
for rel in files:
    p = root + rel
    s = open(p, encoding="utf-8").read()
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.DOTALL)   # block comments
    s = re.sub(r'"[^"\n]*"', '', s)                    # double-quoted strings
    s = re.sub(r"'[^'\n]'", '', s)                     # char literals
    s = re.sub(r"//[^\n]*", '', s)                     # line comments
    ob, cb = s.count("("), s.count(")")
    obr, cbr = s.count("{"), s.count("}")
    ok = (ob == cb) and (obr == cbr)
    all_ok = all_ok and ok
    print(("OK  " if ok else "BAD ") + rel, "(: %d/%d  {: %d/%d" % (ob, cb, obr, cbr))

print("ALL_BALANCED" if all_ok else "STILL_UNBALANCED")