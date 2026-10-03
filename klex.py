import glob, sys

files = [
    "update/UpdateActions.kt",
    "update/ElqUpdateChecker.kt",
    "update/ElqUpdateInstaller.kt",
    "ui/SettingsActivity.kt",
    "utils/CrashCodeDefender.kt",
]

root = glob.glob("src/com/xw/*/")[0]

def lex_balance(text):
    po = pc = bo = bc = 0
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == '\\':
                    i +=  2
                else:
                    i +=  1
            i +=  1
        elif c == "'":
            i +=  1
            if i < n and text[i] == '\\':
                i +=  2
            else:
                i +=  1
            while i < n and text[i] != "'":
                i +=  1
            i +=  1
        elif c == '/' and i + 1 < n and text[i+1] == '/':
            while i < n and text[i] != '\n':
                i +=  1
        elif c == '/'and i + 1 < n and text[i+1] == '*':
            i +=  2
            while i + 1 < n and not (text[i] == '*' and text[i+1] == '/'):
                i +=  1
            i +=  2
        else:
            if c == '(':  po +=  1
            elif c == ')':  pc +=  1
            elif c == '{':  bo +=  1
            elif c == '}':  bc +=  1
            i +=  1
    return po, pc, bo, bc

all_ok = True
for rel in files:
    p = root + rel
    s = open(p, encoding="utf-8").read()
    po, pc, bo, bc = lex_balance(s)
    ok = (po == pc) and (bo == bc)
    all_ok = all_ok and ok
    print(("OK  " if ok else "BAD ") + rel, "(: %d/%d  {: %d/%d" % (po, pc, bo, bc))

print("ALL_BALANCED" if all_ok else "STILL_UNBALANCED")