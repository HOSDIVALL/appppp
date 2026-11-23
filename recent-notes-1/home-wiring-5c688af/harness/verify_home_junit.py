import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

app = Path(sys.argv[1] if len(sys.argv) > 1 else 'app')
expected = Counter()
classes = set()
for package, name, count in [('ui', 'NotesHomeListModelTest', 24), ('data', 'RecentNoteUsageTest', 11), ('ui', 'NotesListScrollbarMathTest', 11)]:
    source = app / 'src/test/java/com/example/myapplicationfirstapp' / package / (name + '.kt')
    names = re.findall(r'@Test(?:\([^\n]*\))?\s+fun\s+(`[^`]+`|\w+)', source.read_text(encoding='utf-8'))
    if len(names) != count or len(set(names)) != count:
        sys.exit(f'{source}: expected {count} distinct cases; got {len(names)}')
    cls = f'com.example.myapplicationfirstapp.{package}.{name}'
    classes.add(cls)
    expected.update((cls, method.strip('`')) for method in names)
seen = Counter()
for report in sorted((app / 'build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
    for case in ET.parse(report).iter('testcase'):
        key = (case.get('classname'), case.get('name'))
        if key[0] not in classes:
            continue
        if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
            sys.exit(f'Not passed: {key}')
        seen[key] += 1
if seen != expected:
    sys.exit('\n'.join(f'{key}: expected {expected[key]}, got {seen[key]}' for key in sorted(seen.keys() | expected.keys()) if seen[key] != expected[key]))
print('Verified 24 home model, 11 recent usage, 11 scrollbar cases.')
