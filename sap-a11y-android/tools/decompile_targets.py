#!/usr/bin/env python3
"""List the game methods worth decompiling natively, as a TSV for Ghidra.

Il2CppDumper's dump.cs records a metadata RVA for every method. That is enough
to disassemble exactly the functions the mod talks to, instead of spending hours
on a whole-binary analysis of a 100 MB libil2cpp.so.

  tools/decompile_targets.py [dump.cs] [--all] > targets.tsv

Each line is: <rva hex>\t<Class$$Method>\t<declaration>

With no class list the tool uses the classes the bridge resolves plus the
shop/board/battle types it walks through (see CLASSES below). --all emits every
method in the dump, which is only useful for a full sweep.
"""
import os
import re
import sys

DEFAULT_DUMP = "/tmp/sapmeta/dumper/dump.cs"

# The bridge's own lookups plus the model/UI types its shop and board paths
# walk through. Order is decompile priority: the shop is what breaks.
CLASSES = [
    # Shop actions live here.
    "HangarMain", "MinionShop", "SpellShop", "HangarOverlay", "HangarRoll",
    "HangarFreeze", "HangarGold", "HangarLives", "HangarTurns", "HangarTimer",
    "HangarStateBase", "HangarStateMachine", "HangarStateShopMinionFocus",
    "HangarStateShopMinionAim", "HangarStateShopSpellFocus",
    "HangarStateShopSpellAim", "HangarStateDefault", "HangarStateChoose",
    "HangarStateArmyMinionFocus", "HangarStateArmyMinionAim",
    "MinionDragger", "Chooser", "ChoiceZone", "PointerCapture", "DeckViewer",
    "MinionArmy", "RelicArmy", "BullyArmy", "SpellZone", "Arrow",
    # Board and models.
    "BoardModel", "BuildModel", "MinionModel", "ItemModel", "SpellModel",
    "ChoiceOptionModel", "IntegerStat", "Rules", "GameRules",
    "MinionModelExtensions", "BoardController",
    # Views the accessibility tree reads.
    "BoardView", "MinionView", "MinionCanvasView", "SpellView", "Space",
    "HangarScoreboard", "HangarScoreboardEntry", "Label",
    # Battle.
    "BattleController", "UIBattle",
    # Framework pieces the tree depends on.
    "PageManager", "Page", "SelectableBase", "ButtonBase", "Component",
]

CLASS_DECL = re.compile(
    r"^(?:(?:public|internal|private|protected|sealed|abstract|static|partial)\s+)*"
    r"(?P<kind>class|struct|enum|interface)\s+(?P<name>[A-Za-z0-9_`<>,. ]+?)\s*"
    r"(?::\s*(?P<bases>.+?))?\s*//\s*TypeDefIndex:\s*\d+\s*$"
)
RVA = re.compile(r"//\s*RVA:\s*(0x[0-9A-Fa-f]+)")
METHOD = re.compile(r"^\t(?:(?:public|private|protected|internal|static|virtual|override|"
                    r"abstract|sealed|extern|unsafe|new|async|readonly|volatile)\s+)*"
                    r"(?P<ret>[^=;{}]+?)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*\(")


def main(argv):
    dump = DEFAULT_DUMP
    every = "--all" in argv
    for a in argv[1:]:
        if not a.startswith("--"):
            dump = a
    wanted = set(CLASSES)
    out = []
    current = None
    seen_names = {}
    pending_rva = None
    with open(dump, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = CLASS_DECL.match(line)
            if m:
                name = m.group("name").strip().split("`")[0]
                current = name if (every or name in wanted) else None
                continue
            if current is None:
                continue
            rva = RVA.search(line)
            if rva:
                pending_rva = rva.group(1)
                continue
            m = METHOD.match(line)
            if m and pending_rva:
                decl = line.strip()
                label = "%s$$%s" % (current, m.group("name"))
                # Overloads share a name; number them so the output paths differ.
                seen_names[label] = seen_names.get(label, 0) + 1
                if seen_names[label] > 1:
                    label = "%s#%d" % (label, seen_names[label])
                out.append("%s\t%s\t%s" % (pending_rva, label, decl))
                pending_rva = None
    for row in out:
        print(row)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
