#!/usr/bin/env bash
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: bash SCRIPT ABSOLUTE_SCOPE_SCRIPT NEW_EVIDENCE_DIR' >&2; exit 2; }
script=$1 out=$2
[[ $script == /* && -s $script ]]
[[ $(git rev-parse HEAD) == 5c688af4d30f01f8939319cad47b2393a8ebaa99 ]]
if git symbolic-ref -q HEAD >/dev/null; then exit 2; fi
mkdir -- "$out"
stage=apply_scope
trap 'rc=$?; printf "%s: exit %s\n" "$stage" "$rc" > "$out/status.txt"' EXIT
ui=app/src/main/java/com/example/myapplicationfirstapp/ui
files=("$ui/NotesListScreen.kt" "$ui/NotesListScrollbar.kt")
git diff HEAD -- "${files[@]}" > "$out/before.diff"
python3 "$script" 2>&1 | tee "$out/apply.log"
git diff HEAD -- "${files[@]}" > "$out/after.diff"
if cmp -s "$out/before.diff" "$out/after.diff"; then echo 'No scope change' >&2; exit 2; fi
git diff --check HEAD -- "${files[@]}"
for entry in unit:testDebugUnitTest app:assembleDebug androidTest:assembleDebugAndroidTest; do
  stage=${entry%%:*}
  bash ./gradlew --no-daemon --console=plain ":app:${entry#*:}" 2>&1 | tee "$out/$stage.log"
done
stage=manifest
python3 - "$out" <<'PY'
import hashlib,json,sys
from pathlib import Path
out=Path(sys.argv[1]); app=Path('app/build/outputs/apk/debug/app-debug.apk').resolve(strict=True); test=Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk').resolve(strict=True)
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
r={'variant':'note-scrollbar-scope','build_passed':True,'scope_diff_sha256':digest(out/'after.diff'),'app_sha256':digest(app),'test_sha256':digest(test),'app_apk':str(app),'android_test_apk':str(test),'base':'5c688af4d30f01f8939319cad47b2393a8ebaa99','scope_evidence':'after.diff (baseline-relative diff of the two scrollbar files)'}
if r['app_sha256']=='354fedc000904b9345c21a5d69aa0198decc1027d785d68b4f574bcc6711d526': sys.exit('Old application APK; no manifest emitted')
(out/'build-manifest.json').write_text(json.dumps(r,indent=2)+'\n',encoding='utf-8')
PY
stage=build_passed_no_install
