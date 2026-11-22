#!/usr/bin/env python3
"""Check the bridge against the decompiled game.

Two kinds of check run here, both offline:

1. The shop/board surface the mod uses is asserted member by member against
   tests/reference/members.json (generated from the game's dump by
   tools/extract_reference.py). A renamed, removed, re-arity'd or newly static
   member fails here instead of failing silently on a phone.

2. Every class the source looks up with find_class() and every member name it
   passes to the il2cpp helpers is looked for in that reference. This catches a
   typo in a name literal, which no compiler can.

What this cannot decide: whether a member behaves as its signature suggests,
and whether the object handed to a call is of the right runtime type. Those
need the game running, and are listed in TESTING.md as device checks.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REFERENCE = os.path.join(ROOT, "tests", "reference", "members.json")
SOURCE = os.path.join(ROOT, "native", "src", "sapa11y.cpp")

# Literals passed to the helpers that are not member names: UI labels used as
# keys, log words, and names assembled at runtime. Anything else missing from
# the reference is a bug.
NON_MEMBERS = {
    ".ctor": "constructor, printed as .ctor in the dump and invoked by name",
    "Done": "label text used to find the button by its caption",
    "Sell": "label text used to find the button by its caption",
    "FOUND": "log word",
    "missing": "log word",
    "get_": "prefix for a member name built at runtime from a parameter",
    "TextMesh": "field name on other UI classes; checked per class, not globally",
}

FAILS = []
CHECKS = 0


def check(ok, what):
    global CHECKS
    CHECKS += 1
    if not ok:
        FAILS.append(what)


def member(classes, cls, name, kind, **want):
    """Assert one member with its shape. kind: field|property|method."""
    key = "%s.%s" % (cls, name) if kind != "method" else None
    c = classes.get(cls)
    if c is None:
        check(False, "class %s missing from the reference" % cls)
        return
    table = {"field": c["fields"], "property": c["properties"], "method": c["methods"]}[kind]
    if name not in table:
        check(False, "%s: no %s %r (reference line %d)" % (cls, kind, name, c["line"]))
        return
    entry = table[name]
    if kind == "method":
        if "arity" in want:
            entry = [o for o in entry if len(o["params"]) == want["arity"]]
            if not entry:
                check(False, "%s.%s: no overload with %d parameter(s)"
                      % (cls, name, want["arity"]))
                return
            entry = entry[0]
        else:
            entry = entry[0]
        if "static" in want:
            check(entry["static"] == want["static"],
                  "%s.%s: static is %r, expected %r" % (cls, name, entry["static"], want["static"]))
        if "returns" in want:
            check(entry["returns"] == want["returns"],
                  "%s.%s: returns %r, expected %r" % (cls, name, entry["returns"], want["returns"]))
        if "param_types" in want:
            got = [p.split("=")[0].strip().rsplit(" ", 1)[0] for p in entry["params"]]
            check(got == want["param_types"],
                  "%s.%s: parameters are %r, expected %r" % (cls, name, got, want["param_types"]))
        check(True, "")
        return
    if "type" in want:
        check(entry["type"] == want["type"],
              "%s.%s: type is %r, expected %r" % (cls, name, entry["type"], want["type"]))
    if "static" in want:
        check(entry["static"] == want["static"],
              "%s.%s: static is %r, expected %r" % (cls, name, entry["static"], want["static"]))
    if kind == "property" and "get" in want:
        check(entry["get"] == want["get"],
              "%s.%s: readable is %r, expected %r" % (cls, name, entry["get"], want["get"]))
    check(True, "")


def surface(classes):
    """The members the shop and board paths read and call, by reference line."""
    C = "Spacewood."
    # --- board state -------------------------------------------------------
    for name in ["Gold", "Lives", "LivesMax", "Turn", "Tier", "Victories", "Losses",
                 "FreeRolls", "MoustachesCollected", "Minions"]:
        member(classes, C + "Core.Models.BoardModel", "get_" + name, "method",
               arity=0, static=False)
    for name, typ in [("Level", "int"), ("Exp", "int"), ("Attack", "IntegerStat"),
                      ("Health", "IntegerStat"), ("SellValue", "Nullable<int>"),
                      ("Tribe", "Nullable<TribeEnum>"), ("Enum", "MinionEnum")]:
        member(classes, C + "Core.Models.MinionModel", name, "field", type=typ)
    for name in ["Tier", "Type", "Template"]:
        member(classes, C + "Core.Models.MinionModel", "get_" + name, "method", arity=0)
    member(classes, C + "Core.Models.IntegerStat", "get_Total", "method", arity=0, returns="int")
    member(classes, C + "Core.Models.MinionModel", "Level", "field", type="int")
    # The tribe is optional, which is what the bridge unwraps.
    member(classes, C + "Core.Models.MinionModel", "Tribe", "field", type="Nullable<TribeEnum>")

    # --- item numbers the shop speaks --------------------------------------
    item_model = C + "Core.Models.Item.ItemModel"
    member(classes, item_model, "Price", "property", type="int")
    member(classes, item_model, "get_Price", "method", arity=0, returns="int")
    member(classes, item_model, "Frozen", "property", type="bool")
    member(classes, item_model, "get_Frozen", "method", arity=0, returns="bool")
    # SellValue is a Nullable<int> field, not a property and not a plain int:
    # the bridge has to unwrap it the way it unwraps Tribe.
    member(classes, C + "Core.Models.MinionModel", "SellValue", "field", type="Nullable<int>")
    check("SellValue" not in classes[C + "Core.Models.MinionModel"]["properties"],
          "MinionModel.SellValue became a property; the bridge reads it as a field")
    member(classes, "System.Nullable<T>", "value", "field")
    member(classes, "System.Nullable<T>", "hasValue", "field")

    # --- card text ---------------------------------------------------------
    member(classes, C + "Unity.Extensions.MinionModelExtensions", "GetNameLocalized",
           "method", arity=1, static=True, returns="string",
           param_types=["MinionModel"])
    member(classes, C + "Unity.Extensions.MinionModelExtensions", "GetAbilityLocalized",
           "method", arity=1, static=True, returns="string",
           param_types=["MinionModel"])

    # --- views -------------------------------------------------------------
    member(classes, C + "Unity.Views.MinionView", "get_Minion", "method", arity=0,
           returns="MinionModel")
    member(classes, C + "Unity.Views.SpellView", "get_Spell", "method", arity=0,
           returns="SpellModel")
    member(classes, C + "Unity.Views.MinionCanvasView", "get_Model", "method", arity=0,
           returns="MinionModel")

    # --- slots -------------------------------------------------------------
    space = C + "Unity.Views.Space"
    member(classes, space, "PointerCapture", "field", type="PointerCapture")
    member(classes, space, "ItemContainer", "field", type="Transform")
    member(classes, space, "PreviewContainer", "field", type="Transform")
    for name, typ in [("MinionModel", "MinionModel"), ("MinionView", "MinionView"),
                      ("SpellModel", "SpellModel"), ("SpellView", "SpellView")]:
        member(classes, space, "get_" + name, "method", arity=0, returns=typ)
    # Freezing and selling read the slot's own model first (the PC mod's
    # space.MinionModel / space.SpellModel) and only fall back to the card view
    # when there is no Space. A lost getter has to fail here, not on a phone.
    check(space in classes and "get_MinionModel" in classes[space]["methods"],
          "Space.get_MinionModel is what shop_item_of reads first")
    member(classes, space, "HandleClick", "method", arity=1, static=False,
           returns="void", param_types=["PointerCapture"])

    # --- shop containers ---------------------------------------------------
    hangar = C + "Unity.MonoBehaviours.Build.HangarMain"
    for name, typ in [("MinionShop", "MinionShop"), ("SpellShop", "SpellShop"),
                      ("MinionArmy", "MinionArmy"), ("Overlay", "HangarOverlay")]:
        member(classes, hangar, name, "field", type=typ)
    member(classes, hangar, "FreezeItem", "method", arity=1, returns="void",
           param_types=["ItemModel"])
    member(classes, hangar, "HandleMinionShopClick", "method", arity=2, returns="void",
           param_types=["MinionShop", "Space"])
    member(classes, hangar, "HandleSpellShopClick", "method", arity=2, returns="void",
           param_types=["SpellShop", "Space"])
    member(classes, hangar, "HandleMinionArmyClick", "method", arity=2, returns="void",
           param_types=["MinionArmy", "Space"])
    member(classes, C + "Unity.MonoBehaviours.Build.MinionShop", "get_Spaces", "method",
           arity=0, returns="List<Space>")
    member(classes, C + "Unity.MonoBehaviours.Build.MinionShop", "get_ShopMinions", "method",
           arity=0, returns="List<MinionModel>")
    member(classes, C + "Unity.MonoBehaviours.Build.SpellShop", "get_Spaces", "method",
           arity=0, returns="List<Space>")
    member(classes, C + "Unity.Views.MinionArmy", "get_Spaces", "method", arity=0,
           returns="Grid<Space>")
    member(classes, C + "Core.System.Grid<T>", "get_Items", "method", arity=0,
           returns="List<T>")
    member(classes, C + "Core.System.Grid<T>", "Item", "property", type="T")
    member(classes, C + "Core.System.Grid<T>", "get_Item", "method")
    member(classes, "System.Collections.Generic.List<T>", "get_Count", "method", arity=0,
           returns="int")
    member(classes, "System.Collections.Generic.List<T>", "get_Item", "method", arity=1)

    # --- overlay buttons and counters -------------------------------------
    overlay = C + "Unity.MonoBehaviours.Build.HangarOverlay"
    member(classes, overlay, "get_BoardModel", "method", arity=0, returns="BoardModel")
    for name, typ in [("Roll", "HangarRoll"), ("Freeze", "HangarFreeze"),
                      ("DoneButton", "ButtonBase"), ("SellButton", "ButtonBase"),
                      ("Timer", "HangarTimer")]:
        member(classes, overlay, name, "field", type=typ)
    member(classes, C + "Unity.MonoBehaviours.Build.HangarRoll", "Button", "field",
           type="ButtonBase")
    member(classes, C + "Unity.MonoBehaviours.Build.HangarTimer", "get_IsCounting", "method",
           arity=0, returns="bool")
    member(classes, C + "Unity.MonoBehaviours.Build.HangarTimer", "get_SecondsDelta",
           "method", arity=0, returns="int")

    # --- whole-team food ---------------------------------------------------
    # A food spell whose template has no Aim target covers the whole team: the
    # game ignores pet clicks and waits for its SpellZone click, which is what
    # the bridge performs. The PC mod reads the same chain
    # (spellFocusState.Spell -> Template.Aim == null) and clicks
    # spellZone.PointerCapture.
    states = C + "Unity.MonoBehaviours.Build.Hangar.States."
    member(classes, hangar, "SpellZone", "field", type="SpellZone")
    member(classes, hangar, "HandleSpellZoneClick", "method", arity=1, returns="void",
           param_types=["SpellZone"])
    member(classes, C + "Unity.SpellZone", "PointerCapture", "field",
           type="PointerCapture")
    member(classes, states + "HangarStateMachine", "get_State", "method", arity=0,
           returns="HangarStateBase")
    member(classes, states + "HangarStateShopSpellFocus", "get_Spell", "method", arity=0,
           returns="SpellModel")
    member(classes, C + "Core.Models.SpellModel", "get_Template", "method", arity=0,
           returns="Spell")
    member(classes, C + "Core.Models.Spells.Spell", "Aim", "field", type="TargetsBase")

    # --- buttons -----------------------------------------------------------
    # ButtonBase exposes GetInteractable(), not a get_Interactable property.
    member(classes, C + "Unity.UI.ButtonBase", "GetInteractable", "method", arity=0)
    # Roll, Done and Sell are driven by the holder's own Click(), which is the
    # call the PC mod's PerformRoll/PerformEndTurn/PerformSell make. click_component
    # finds it by the "Click" suffix, so it must be a real zero-argument method
    # on both button bases the overlay fields are typed with.
    member(classes, C + "Unity.UI.SelectableBase", "Click", "method", arity=0, returns="void")
    # ButtonBase has no Click of its own; the overlay's Button fields are typed
    # ButtonBase, so the inherited base has to stay reachable by the parent walk
    # click_component does.
    check("SelectableBase" in classes[C + "Unity.UI.ButtonBase"]["bases"],
          "ButtonBase no longer derives from SelectableBase; its Click() is gone")
    check("IsInteractable" not in classes[C + "Unity.UI.ButtonBase"]["methods"],
          "ButtonBase.IsInteractable appeared: the removed dead probe can come back")
    # Stock controls go through uGUI's own property.
    member(classes, "UnityEngine.UI.Selectable", "get_interactable", "method", arity=0,
           returns="bool")

    # --- control liveness --------------------------------------------------
    member(classes, "UnityEngine.Behaviour", "get_enabled", "method", arity=0, returns="bool")
    member(classes, "UnityEngine.GameObject", "get_activeInHierarchy", "method", arity=0,
           returns="bool")

    # --- battle ------------------------------------------------------------
    battle = C + "Unity.MonoBehaviours.Battle.BattleController"
    member(classes, battle, "get_PlayerBoardModel", "method", arity=0, returns="BoardModel")
    member(classes, battle, "get_OpponentBoardModel", "method", arity=0, returns="BoardModel")
    # Two flags, both defaulting to false; there is no zero-argument Pause here.
    member(classes, battle, "Pause", "method", arity=2, returns="void",
           param_types=["bool", "bool"])
    check(all(len(o["params"]) != 0 for o in classes[battle]["methods"]["Pause"]),
          "BattleController.Pause gained a zero-argument overload; the bridge calls the two-argument one")
    member(classes, battle, "Play", "method", arity=0, returns="void")
    member(classes, battle, "Skip", "method", arity=0)
    member(classes, battle, "ToggleFastforward", "method", arity=0, returns="void")
    member(classes, battle, "ToggleAutoplay", "method", arity=0, returns="void")
    member(classes, battle, "get_IsFastfowarding", "method", arity=0, returns="bool",
           static=True)

    # --- page title --------------------------------------------------------
    member(classes, C + "Unity.PageManager", "CurrentPage", "field", type="Page")

    # --- negative: what a wrong member looked like -------------------------
    canvas = classes.get("UnityEngine.Canvas", {})
    check("get_planeDistance" not in canvas.get("methods", {}),
          "Canvas.get_planeDistance appeared: the removed log read can come back")
    check("planeDistance" not in canvas.get("properties", {}),
          "Canvas.planeDistance appeared as a property")


def source_scan(classes):
    src = open(SOURCE, encoding="utf-8", errors="replace").read()

    known_classes = set((c["namespace"], c["name"]) for c in classes.values())
    for ns, name in sorted(set(re.findall(r'find_class\(\s*"([^"]*)"\s*,\s*"([^"]+)"', src))):
        check((ns, name) in known_classes,
              'find_class("%s", "%s"): no such class in the reference' % (ns, name))

    names = set()
    for c in classes.values():
        names.update(c["fields"])
        names.update(c["properties"])
        names.update(c["methods"])
        # The dump spells a generic method GetComponentsInChildren<T>; il2cpp's
        # own method name drops the arity suffix, which is what the bridge has
        # to pass, so both spellings count.
        names.update(re.sub(r"<.*>$", "", n) for n in c["methods"])
    literals = set()
    for call in re.finditer(
            r'(?:method_on|prop_or_field|find_field|call_[a-z_]+)\([^;\n]*?"([A-Za-z_.0-9]+)"', src):
        literals.add(call.group(1))
    for name in sorted(literals):
        if name in NON_MEMBERS:
            continue
        check(name in names, "member name %r is not in the reference" % name)


def main():
    for path in (REFERENCE, SOURCE):
        if not os.path.isfile(path):
            sys.exit("missing %s" % os.path.relpath(path, ROOT))
    with open(REFERENCE, encoding="utf-8") as f:
        ref = json.load(f)
    for field in ("generator", "source", "source_lines", "source_sha256", "classes"):
        check(field in ref, "reference is missing %r" % field)
    check(ref.get("generator") == "tools/extract_reference.py",
          "reference was generated by %r, not this repo's extractor" % ref.get("generator"))
    classes = ref["classes"]
    surface(classes)
    source_scan(classes)
    print("reference: %d classes from %s (%d lines, %s)"
          % (len(classes), ref["source"], ref["source_lines"], ref["source_sha256"][:12]))
    print("surface: %d checks, %d failed" % (CHECKS, len(FAILS)))
    for failure in FAILS:
        print("  FAIL: %s" % failure)
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
