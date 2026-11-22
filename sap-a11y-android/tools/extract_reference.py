#!/usr/bin/env python3
"""Extract the mod's IL2CPP surface from the decompiled game dump.

The bridge (native/src/sapa11y.cpp) calls il2cpp members by name. Nothing in
the build can tell a correct name from a plausible one, so the names are
checked against the decompiled game instead, offline:

  tools/extract_reference.py [dump.cs]  ->  tests/reference/members.json

Only the classes the bridge looks up (every find_class in the source, plus the
framework classes the shop/board paths walk through) are stored in full. The
JSON records the dump's line numbers and a digest so a reader can tell which
reference the artifact came from.

Usage: python3 tools/extract_reference.py /path/to/dump.cs
"""
import hashlib
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CPP = os.path.join(ROOT, "native", "src", "sapa11y.cpp")
OUT = os.path.join(ROOT, "tests", "reference", "members.json")

# Classes the shop/board paths reach through framework calls rather than a
# find_class: container shapes, the enum base and the string type.
# Names are spelled the way the dumper prints them: generics as List<T>.
EXTRA = [
    ("System", "String"),
    ("System", "Nullable<T>"),
    ("System.Collections.Generic", "List<T>"),
    ("Spacewood.Core.System", "Grid<T>"),
    ("Spacewood.Core.System", "Point"),
    ("Spacewood.Core.Models.Item", "ItemModel"),
    ("UnityEngine", "MonoBehaviour"),
    ("", "MonoSingleton<T>"),
    # Reached through a property rather than a cached find_class, so they are
    # named here: a slot's spell card, the overlay's timer, the roll/freeze
    # buttons, the shop containers and the stat wrapper.
    ("Spacewood.Unity.Views", "SpellView"),
    ("Spacewood.Unity.MonoBehaviours.Build", "HangarTimer"),
    ("Spacewood.Unity.MonoBehaviours.Build", "HangarRoll"),
    ("Spacewood.Unity.MonoBehaviours.Build", "HangarFreeze"),
    ("Spacewood.Unity.MonoBehaviours.Build", "MinionShop"),
    ("Spacewood.Unity.MonoBehaviours.Build", "SpellShop"),
    ("Spacewood.Unity.Views", "MinionArmy"),
    ("Spacewood.Core.Models", "IntegerStat"),
    # Reached through the state machine rather than a find_class: a food spell
    # with no Aim target covers the whole team, so the bridge asks the aiming
    # state whether the spell has one and then clicks the SpellZone.
    ("Spacewood.Unity", "SpellZone"),
    ("Spacewood.Unity.MonoBehaviours.Build.Hangar.States", "HangarStateMachine"),
    ("Spacewood.Unity.MonoBehaviours.Build.Hangar.States", "HangarStateShopSpellFocus"),
    ("Spacewood.Core.Models", "SpellModel"),
    ("Spacewood.Core.Models.Spells", "Spell"),
]

CLASS_DECL = re.compile(
    r"^(?P<mods>(?:(?:public|internal|private|protected|sealed|abstract|static|partial)\s+)*)"
    r"(?P<kind>class|struct|enum|interface)\s+(?P<name>[A-Za-z0-9_`<>,. ]+?)"
    r"(?:\s*:\s*(?P<bases>.+?))?\s*//\s*TypeDefIndex:\s*(?P<tdi>\d+)\s*$"
)
FIELD = re.compile(r"^\t(?P<mods>(?:(?:public|internal|private|protected|static|readonly|const)\s+)*)"
                   r"(?P<type>[^;{}()=]+?)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*;\s*//\s*0x[0-9A-Fa-f]+")
PROP = re.compile(r"^\t(?P<mods>(?:(?:public|internal|private|protected|static|virtual|override|abstract)\s+)*)"
                  r"(?P<type>[^;{}()=]+?)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*\{\s*(?P<acc>[^}]*)\}")
METHOD = re.compile(r"^\t(?P<mods>(?:(?:public|internal|private|protected|static|virtual|override|abstract|"
                    r"extern|unsafe|new)\s+)*)(?P<ret>[^;{}()=]+?)\s+(?P<name>[A-Za-z_][A-Za-z0-9_.<>]*)\s*"
                    r"\((?P<params>[^()]*)\)\s*\{\s*\}")


def source_classes(path):
    """(namespace, name) pairs the bridge looks up, in source order."""
    text = open(path, encoding="utf-8", errors="replace").read()
    seen, out = set(), []
    for ns, name in re.findall(r'find_class\(\s*"([^"]*)"\s*,\s*"([^"]+)"\s*\)', text):
        if (ns, name) not in seen:
            seen.add((ns, name))
            out.append((ns, name))
    return out


def parse(path, wanted):
    lines = open(path, encoding="utf-8", errors="replace").read().split("\n")
    ns = ""
    classes = {}
    current = None
    for i, line in enumerate(lines, 1):
        m = re.match(r"^// Namespace: (.*)$", line)
        if m:
            ns = m.group(1).strip()
            continue
        m = CLASS_DECL.match(line)
        if m:
            name = m.group("name").strip()
            current = None
            key = (ns, name)
            if key in wanted:
                current = {
                    "namespace": ns,
                    "name": name,
                    "line": i,
                    "typedef_index": int(m.group("tdi")),
                    "kind": m.group("kind"),
                    "bases": [b.strip() for b in (m.group("bases") or "").split(",") if b.strip()],
                    "fields": {},
                    "properties": {},
                    "methods": {},
                }
                classes["%s.%s" % (ns, name) if ns else name] = current
            continue
        if current is None:
            continue
        if line.startswith("\t// ") or line.startswith("\t["):
            continue
        m = FIELD.match(line)
        if m:
            mods = m.group("mods").split()
            current["fields"][m.group("name")] = {
                "type": m.group("type").strip(),
                "static": "static" in mods,
                "decl": line.strip(),
            }
            continue
        m = PROP.match(line)
        if m:
            mods = m.group("mods").split()
            acc = m.group("acc")
            current["properties"][m.group("name")] = {
                "type": m.group("type").strip(),
                "get": "get;" in acc,
                "set": "set;" in acc,
                "static": "static" in mods,
                "decl": line.strip(),
            }
            continue
        m = METHOD.match(line)
        if m:
            mods = m.group("mods").split()
            params = [p.strip() for p in m.group("params").split(",") if p.strip()]
            current["methods"].setdefault(m.group("name"), []).append({
                "returns": m.group("ret").strip(),
                "params": params,
                "static": "static" in mods,
                "decl": line.strip(),
            })
    return classes


def main():
    dump = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("SAP_DUMP", "/tmp/sapmeta/dumper/dump.cs")
    if not os.path.isfile(dump):
        sys.exit("no decompiled dump at %s (pass the path as the first argument)" % dump)
    wanted = set(source_classes(CPP)) | set(EXTRA)
    classes = parse(dump, wanted)
    missing = sorted("%s.%s" % (ns, name) for ns, name in wanted
                     if ("%s.%s" % (ns, name) if ns else name) not in classes)
    if missing:
        sys.exit("reference is missing classes the source looks up: %s" % ", ".join(missing))
    blob = open(dump, "rb").read()
    data = {
        "generator": "tools/extract_reference.py",
        "source": dump,
        "source_lines": blob.count(b"\n") + 1,
        "source_sha256": hashlib.sha256(blob).hexdigest(),
        "classes": classes,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1, sort_keys=True)
        f.write("\n")
    print("wrote %s: %d classes from %s (%d lines)"
          % (os.path.relpath(OUT, ROOT), len(classes), dump, data["source_lines"]))


if __name__ == "__main__":
    main()
