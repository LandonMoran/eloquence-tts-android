# Judge the TTS engine probe results from a filtered logcat capture.
# Input: a file of logcat -v time lines filtered to TTSProbe / seg dialect / crashes.
#
# Every check below is required: a log that is missing a marker is a failed run,
# not a pass with a note (a probe that never locked the device or never fired the
# second utterance proves nothing about the locked-screen path).
import re
import sys

SEG_RE = re.compile(r'seg dialect=0x([0-9a-fA-F]+)')
# "com\.xw" (a real dot), not "com\\.xw": the double escape in a raw string
# matched a literal backslash and never matched an ActivityManager line.
BAD_RE = re.compile(
    r'(SecurityException|FATAL EXCEPTION|IllegalStateException|StorageNotReady'
    r'|Process .*com\.xw.*died|UNEXPECTED TOP-LEVEL)')


def main():
    if len(sys.argv) < 2:
        print("CHECK_RESULT=FAIL no log file")
        return 1
    try:
        with open(sys.argv[1], encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except OSError as exc:
        print(f"CHECK_RESULT=FAIL cannot read log: {exc}")
        return 1

    dialect = [(m.group(1), i) for i, line in enumerate(lines)
               if (m := SEG_RE.search(line))]
    locked_i = next((i for i, line in enumerate(lines) if "CHECKPOINT_LOCKED" in line), None)
    unlocked_i = next((i for i, line in enumerate(lines) if "CHECKPOINT_UNLOCKED" in line), None)
    speak2_i = next((i for i, line in enumerate(lines) if "PROBE_SPEAK2_FIRED" in line), None)

    errors = []

    if not dialect:
        errors.append("no seg dialect markers")
    else:
        first = dialect[0][0]
        if first.startswith("6"):
            errors.append("first latin segment resolved to zh dialect 0x" + first)
        if not any(d.startswith("6") for d, _ in dialect):
            errors.append("no zh sanity segment (0x6..) found")

    if locked_i is None:
        errors.append("no CHECKPOINT_LOCKED marker")

    if speak2_i is None:
        errors.append("speak2 never fired")
    else:
        if locked_i is not None and speak2_i < locked_i:
            errors.append("speak2 fired before device locked")
        lock_window = [d for d, i in dialect
                       if locked_i is not None and i > locked_i
                       and (unlocked_i is None or i < unlocked_i)]
        if not lock_window:
            errors.append("no synthesis segments in the locked window")
        bad = [line for line in lines[speak2_i:] if BAD_RE.search(line)]
        if bad:
            errors.append("post-speak2 errors: " + " | ".join(bad[:3]))

    if errors:
        print("CHECK_RESULT=FAIL " + "; ".join(errors))
        return 1
    print("CHECK_RESULT=PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
