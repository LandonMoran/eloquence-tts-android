# Judge the TTS engine probe results from a filtered logcat capture.
# Input: a file of logcat -v time lines filtered to TTSProbe / seg dialect / crashes.
import re
import sys


def main():
    if len(sys.argv) < 2:
        print("CHECK_RESULT=FAIL no log file")
        sys.exit(1)
    lines = open(sys.argv[1]).read().splitlines()
    seg_re = re.compile(r'seg dialect=0x([0-9a-fA-F]+)')
    dial = [(m.group(1), i) for i, l in enumerate(lines) if (m := seg_re.search(l))]
    first = dial[0][0] if dial else ""
    has_zh = any(h.startswith("6") for h, _ in dial)
    locked_i = next((i for i, l in enumerate(lines) if "CHECKPOINT_LOCKED" in l), None)
    unlocked_i = next((i for i, l in enumerate(lines) if "CHECKPOINT_UNLOCKED" in l), None)
    speak2_i = next((i for i, l in enumerate(lines) if "PROBE_SPEAK2_FIRED" in l), None)
    bad_re = re.compile(r'(SecurityException|FATAL EXCEPTION|IllegalStateException|StorageNotReady|Process.*com\\.xw.*died|UNEXPECTED TOP-LEVEL)')
    post = lines[speak2_i:] if speak2_i is not None else []
    bad = [l for l in post if bad_re.search(l)]
    err = []
    ok = True
    if not dial:
        ok = False
        err.append("no seg dialect markers")
    else:
        if first.startswith("6"):
            ok = False
            err.append("first latin segment resolved to zh dialect 0x" + first)
        if not has_zh:
            ok = False
            err.append("no zh sanity segment (0x6..( found")
    if locked_i is None:
        err.append("no CHECKPOINT_LOCKED marker")
    if speak2_i is None:
        err.append("speak2 never fired")
    else:
        if locked_i is not None and speak2_i < locked_i:
            err.append("speak2 fired before device locked")
        lockwin = [h for h, i in dial if locked_i is not None and i > locked_i and (unlocked_i is None or i < unlocked_i)]
        if not lockwin:
            err.append("no synthesis segments in the locked window")
        if bad:
            ok = False
            err.append("post-speak2 errors: " + " | ".join(bad[:3]))
    if ok:
        print("CHECK_RESULT=PASS")
    else:
        print("CHECK_RESULT=FAIL " + "; ".join(err))
        sys.exit(1)


if __name__ == "__main__":
    main()