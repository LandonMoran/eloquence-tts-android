import glob

p = glob.glob("src/com/xw/*/update/UpdateActions.kt")[0]
text = open(p, encoding="utf-8").read()
lines = text.split("\n")

def code_of(line):
    out = []
    i = 0
    n = len(line)
    while i < n:
        c = line[i]
        if c == '"':
            i +=  1
            while i < n and line[i] != '"':
                if line[i] == '\\':
                    i +=  2
                else:
                    i +=  1
            i +=  1
        elif c == "'":
            i +=  1
            if i < n and line[i] == '\\':
                i +=  2
            else:
                i +=  1
            while i < n and line[i] != "'":
                i +=  1
            i +=  1
        elif c == '/'and i + 1 < n and line[i+1] == '/':
            break
        elif c == '/'and i + 1 < n and line[i+1] == '*':
            i +=  2
            while i +  2 < n and not (line[i] == '*' and line[i+1] == '/'):
                i +=  1
            if i +  1 < n:
                i +=  2
        else:
            out.append(c)
            i +=           1
    return "".join(out)

depth = 0
for idx, raw in enumerate(lines, 1):
    code = code_of(raw)
    net = code.count("{") - code.count("}")
    if net !=  0:
        depth += net
        print(f"L{idx:3} net={net:+d} depth={depth:+d} | {raw.strip()[:95]}")
print("final:", depth)