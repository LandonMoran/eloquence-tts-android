import glob; D = glob.glob("src/com/xw/*/")[0]
status = []

pa = D + "update/UpdateActions.kt"
lines = open(pa, encoding="utf-8").read().split("\n")
assert "UserActionRequired" in lines[99]
assert ".start()" in lines[121]
inds = {100:16, 101:20,  102:20,  103:24,
          104:20,  105:16,  106:16,
          107:20,  108:20,  109:20,  110:20,
          111:24,  112:28,  113:24,
          114:24,  115:28,  116:24,
          117:20,  118:16,  119:12,  120:16,
          121:12,  122:8}
for n, pad in inds.items():
    body = lines[n-1].lstrip()
    assert body,"line %d empty" % n
    lines[n-1] = " " * pad + body
open(pa,"w", encoding="utf-8", newline="\n").write("\n".join(lines))
status.append("1 UpdateActions indented")

pc = D + "utils/CrashCodeDefender.kt"
lines = open(pc, encoding="utf-8").read().split("\n")
assert lines[33].startswith("    private var initFailed"), repr(lines[33])
assert lines[34] == lines[33], "dup mismatch"
del lines[34]
open(pc,"w", encoding="utf-8", newline="\n").write("\n".join(lines))
status.append("2 CrashCodeDefender deduped")

ps = D + "ui/SettingsActivity.kt"
lines = open(ps, encoding="utf-8").read().split("\n")
assert "uri..split" in lines[787], "L788"
lines[787] = lines[787].replace("uri..split", "uri).split", 1)
open(ps,"w", encoding="utf-8", newline="\n").write("\n".join(lines))
status.append("3 SettingsActivity uri split fixed")

pk = D + "update/ElqUpdateChecker.kt"
lines = open(pk, encoding="utf-8").read().split("\n")
assert "apiUrl?.openConnection" in lines[59], "L60"
lines[59] = lines[59].replace("apiUrl?.openConnection", "apiUrl).openConnection", 1)
open(pk,"w", encoding="utf-8", newline="\n").write("\n".join(lines))
status.append("4 ElqUpdateChecker URL fixed")

pi = D + "update/ElqUpdateInstaller.kt"
lines = open(pi, encoding="utf-8").read().split("\n")
assert "sessionId.intentSender))" in lines[106], "L107"
lines[106] = lines[106].replace("sessionId.intentSender))", "sessionId).intentSender)", 1)
open(pi,"w", encoding="utf-8", newline="\n").write("\n".join(lines))
status.append("5 ElqUpdateInstaller commit paren fixed")

print("\n".join(status))