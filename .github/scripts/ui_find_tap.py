# Find a uiautomator dump node by visible text and print its center point.
import re
import sys
import xml.etree.ElementTree as ET


def main():
    if len(sys.argv) < 3:
        print("")
        return
    path, needle = sys.argv[1], sys.argv[2]
    try:
        root = ET.parse(path).getroot()
    except Exception:
        print("")
        return
    for n in root.iter():
        label = " ".join([(n.get("text") or ""), (n.get("content-desc") or "")])
        if needle.lower() in label.lower():
            b = n.get("bounds") or ""
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
            if m:
                x = (int(m.group(1)) + int(m.group(3))) // 2
                y = (int(m.group(2)) + int(m.group(4))) // 2
                print(f"{x} {y}")
                return
    print("")


if __name__ == "__main__":
    main()