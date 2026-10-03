import glob

files = [
    "update/UpdateActions.kt",
    "update/ElqUpdateChecker.kt",
    "update/ElqUpdateInstaller.kt",
    "ui/SettingsActivity.kt",
    "utils/CrashCodeDefender.kt",
]

root = glob.glob("src/com/xw/*/")[0]
for rel in files:
    p = root + rel
    s = open(p, encoding="utf-8").read()
    ob = s.count("(");  cb = s.count(")")
    obr = s.count("{");   cbr = s.count("}")
    sq = s.count("[");   csq = s.count("]")
    ok = ob == cb and obr == cbr and sq == csq
    print(("OK " if ok else "BAD ") + rel + "  (: %d/%d  {: %d/%d  [: %d/%d" %
          (ob, cb, obr, cbr, sq, csq))