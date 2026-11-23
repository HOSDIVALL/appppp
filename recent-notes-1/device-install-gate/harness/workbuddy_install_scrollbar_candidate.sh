#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 7 || ${7:-} != --dedicated-device ]]; then
echo 'Usage: bash workbuddy_install_scrollbar_candidate.sh SERIAL APP_APK TEST_APK APP_SHA256 TEST_SHA256 NEW_EVIDENCE_DIR --dedicated-device' >&2
exit 2
fi
serial=$1 app=$2 testapk=$3 appsha=${4,,} testsha=${5,,} out=$6
[[ -n $serial && $serial != -* && $appsha =~ ^[0-9a-f]{64}$ && $testsha =~ ^[0-9a-f]{64}$ ]]
case "$appsha" in 354fedc0*71d526|d85ae6f7*686719d) echo 'STOP: old candidate APK' >&2; exit 2;; esac
mkdir -- "$out"
stage=preflight
trap 'rc=$?; printf "%s: exit %s\n" "$stage" "$rc" > "$out/status.txt"' EXIT
printf '%s  %s\n' "$appsha" "$app" "$testsha" "$testapk" | tee "$out/apk.sha256" | sha256sum -c -
ad=(adb -s "$serial")
[[ $("${ad[@]}" get-state | tr -d '\r') == device ]]
user=$("${ad[@]}" shell am get-current-user | tr -d '\r')
[[ $user =~ ^[0-9]+$ ]]
pkg=com.example.myapplicationfirstapp
testpkg=$pkg.test
packages=$("${ad[@]}" shell pm list packages -u --user "$user" | tr -d '\r')
for name in "$pkg" "$testpkg"; do
if grep -Fxq "package:$name" <<< "$packages"; then
echo "STOP: $name already exists for Android user $user; no overwrite or cleanup" >&2
exit 3
fi
done
{
"${ad[@]}" shell getprop ro.product.model
"${ad[@]}" shell getprop ro.build.version.release
"${ad[@]}" shell wm size
"${ad[@]}" shell wm density
} > "$out/device.txt"
stage=install_app
"${ad[@]}" install --user "$user" "$app" | tee "$out/install-app.txt"
stage=install_test
"${ad[@]}" install --user "$user" -t "$testapk" | tee "$out/install-test.txt"
stage=runner
runners=$("${ad[@]}" shell pm list instrumentation "$pkg" | tr -d '\r')
runner=$(awk -v p="$testpkg/" -v t="(target=$pkg)" '$2==t && index($1,"instrumentation:"p)==1 {sub(/^instrumentation:/,"",$1);print $1}' <<< "$runners")
[[ -n $runner && $runner != *$'\n'* ]]
stage=instrumentation
"${ad[@]}" shell am instrument --user "$user" -w "$runner" | tee "$out/instrumentation.txt"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "$out/instrumentation.txt" || ! grep -Eq '^OK \([1-9][0-9]* tests?\)[[:space:]]*$' "$out/instrumentation.txt"; then
echo 'No conclusive nonzero passing test summary.' >&2
exit 4
fi
stage=automated_reported_pass_manual_pending
printf '%s\n' 'Manual: carousel right-edge swipe; note-only track; filtered first and last rows; continuous drag stability.' > "$out/manual-pending.txt"
