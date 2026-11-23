#!/usr/bin/env bash
set -euo pipefail
wiring=$(realpath "${1:?Pass apply_home_wiring.py}")
aar=$(realpath "${2:?Pass the official sherpa-onnx-2.0.1 AAR}")
out=$(realpath -m "${3:?Pass a new evidence directory}")
base=5c688af4d30f01f8939319cad47b2393a8ebaa99
evidence=a81885479378d615d7293467481f7b98c8ca1d4a
branch=ux/rhythm-recent-notes-1
mkdir "$out"
exec > >(tee "$out/run.log") 2>&1
step=baseline
trap 'rc=$?; printf "%s: exit %s\n" "$step" "$rc" > "$out/status.txt"' EXIT
git fetch origin "$branch"
test "$(git rev-parse FETCH_HEAD)" = "$base"
step=batch1
git cat-file -e "$evidence^{commit}" 2>/dev/null || git fetch origin "$evidence"
git show "$evidence:recent-notes-1/batch1/batch1.diff" > "$out/batch1.diff"
printf '%s  %s\n' 4f781f4aeade7a86f13df751f1323e8f0d28910671cf2622d22a4550bd280b41 "$out/batch1.diff" | sha256sum -c -
git worktree add --detach "$out/worktree" "$base"
cd "$out/worktree"
git apply --check --verbose "$out/batch1.diff"
git apply "$out/batch1.diff"
step=wiring
python3 "$wiring"
git add -N -- app/src
git diff --binary HEAD -- app/src > "$out/candidate.diff"
test -s "$out/candidate.diff"
git apply --reverse --check "$out/candidate.diff"
step=dependency
printf '%s  %s\n' a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7 "$aar" | sha256sum -c -
mkdir -p app/libs
cp "$aar" app/libs/sherpa-onnx-2.0.1.aar
step=android_build_and_tests
bash ./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
step=test_evidence
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
from collections import Counter
expected = {'com.example.myapplicationfirstapp.ui.NotesHomeListModelTest': 24, 'com.example.myapplicationfirstapp.data.RecentNoteUsageTest': 7}
seen = Counter()
keys = set()
for file in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    for case in ET.parse(file).iter('testcase'):
        cls, name = case.get('classname'), case.get('name')
        if cls not in expected:
            continue
        assert (cls, name) not in keys and not any(case.find(x) is not None for x in ('failure', 'error', 'skipped')), (cls, name)
        keys.add((cls, name))
        seen[cls] += 1
assert seen == expected, (seen, expected)
print(dict(seen))
PY
step=passed_without_device_testing
