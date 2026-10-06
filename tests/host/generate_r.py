"""Generate resource ID declarations for host-only Kotlin source compilation."""
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET
names = {}
for file in Path('res').rglob('*.xml'):
    if file.parent.name.startswith('values'):
        for element in ET.parse(file).getroot():
            name = element.get('name')
            if name:
                names.setdefault(element.tag.replace('-', '_'), set()).add(name.replace('.', '_'))
    else:
        names.setdefault(file.parent.name.split('-')[0], set()).add(file.stem)
for file in Path('src').rglob('*.kt'):
    for kind, name in re.findall(r'(?<!android\.)\bR\.(\w+)\.(\w+)', file.read_text()):
        if name not in names.get(kind, set()):
            raise ValueError(f'Undefined resource R.{kind}.{name}')
output = ['package com.xw.vvtts; public class R {']
for kind, items in names.items():
    output.append(f'public static class {kind} {{')
    output.extend(f'public static final int {name} = {index+1};' for index, name in enumerate(sorted(items)))
    output.append('}')
output.append('}')
Path(sys.argv[1]).write_text('\n'.join(output))
