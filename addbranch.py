import glob

p = glob.glob("src/com/xw/*/update/UpdateActions.kt")[0]
lines = open(p, encoding="utf-8").read().split("\n")

# Locate the Failed branch: its opening, body, closing at indent 24
for i, ln in enumerate(lines):
    if "is ElqUpdateInstaller.Result.Failed -> {" in ln and ln.strip().startswith("is "):
        fail_open = i
        break
else:
    raise SystemExit("Failed branch not found")

# The closing brace of the Failed branch is the next line at indent 24 after its body
j = fail_open + 1
while not (lines[j].strip() == "}" and len(lines[j]) - len(lines[j].lstrip()) == 24):
    j += 1
    assert j < len(lines), "ran off end"

# Insert the UserActionRequired no-op branch before the when-closing brace
branch = "                        is ElqUpdateInstaller.Result.UserActionRequired -> {}"
assert lines[j + 1].strip() == "}", "expected when close"
assert branch not in lines, "branch already present"
lines.insert(j + 1, branch)

open(p, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
print("inserted at line", j + 2)