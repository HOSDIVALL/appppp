#!/usr/bin/env bash
set -euo pipefail

die() { printf '%s\n' "$*" >&2; exit 1; }

[[ $# -eq 2 ]] || die \
  'Usage: bash workbuddy_install_scrollbar_v2.sh ABSOLUTE_ADB_PATH BUILD_MANIFEST'

adb=$1
manifest=$2
serial=emulator-5558
expected_avd=NotesGO_V2_Dedicated

case "$adb" in
  /*|[A-Za-z]:/*|[A-Za-z]:\\*) ;;
  *) die 'Pass an absolute adb path.' ;;
esac
[[ -f "$adb" && -x "$adb" ]] || die 'adb is missing or not executable.'
[[ -s "$manifest" ]] || die 'Build manifest is missing or empty.'

verify_apks() {
  python3 - "$manifest" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path

old_app = "354fedc000904b9345c21a5d69aa0198decc1027d785d68b4f574bcc6711d526"
base = "5c688af4d30f01f8939319cad47b2393a8ebaa99"

def require(condition, message):
    if not condition:
        raise SystemExit(message)

manifest = Path(sys.argv[1]).resolve(strict=True)
m = json.loads(manifest.read_text(encoding="utf-8"))
require(
    m.get("variant") == "note-scrollbar-scope"
    and m.get("build_passed") is True
    and m.get("base") == base,
    "Manifest does not identify a successful scope build from the expected base.",
)

def verified(path, field):
    expected = m.get(field)
    require(
        isinstance(expected, str) and re.fullmatch(r"[0-9a-f]{64}", expected),
        f"Missing or invalid full SHA-256: {field}",
    )
    require(path.is_absolute() and path.is_file(), f"Missing file: {path}")
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    require(h.hexdigest() == expected, f"SHA-256 mismatch: {path}")
    return path.resolve(strict=True)

verified(manifest.parent / "after.diff", "scope_diff_sha256")
require(m.get("app_sha256") != old_app, "Known old app APK is forbidden.")
app = verified(Path(m["app_apk"]), "app_sha256")
test = verified(Path(m["android_test_apk"]), "test_sha256")
require(app != test and m["app_sha256"] != m["test_sha256"],
        "App and test APKs must be different.")
for path in (app, test):
    require("\n" not in str(path) and "\r" not in str(path),
            "Invalid APK path.")
    print(path)
PY
}

check_avd() {
  local state avd_name boot
  state=$("$adb" -s "$serial" get-state | tr -d '\r') || die 'AVD unavailable.'
  [[ "$state" == device ]] || die 'AVD is not online.'
  avd_name=$("$adb" -s "$serial" emu avd name | tr -d '\r') ||
    die 'Cannot verify AVD identity.'
  [[ "$avd_name" == "$expected_avd"$'\n'OK ]] ||
    die "Unexpected AVD identity for $serial; refusing installation."
  boot=$("$adb" -s "$serial" shell getprop sys.boot_completed | tr -d '\r')
  [[ "$boot" == 1 ]] || die 'AVD has not finished booting.'
}

install_apk() {
  local result
  result=$("$adb" -s "$serial" install -r -t "$1" 2>&1) ||
    die "Install failed: $result"
  printf '%s\n' "$result"
  printf '%s\n' "$result" | tr -d '\r' | grep -qx Success ||
    die 'adb did not report Success.'
}

paths=$(verify_apks) || die 'Build evidence verification failed.'
mapfile -t apks <<< "$paths"
[[ ${#apks[@]} -eq 2 ]] || die 'Expected exactly two verified APK paths.'

check_avd
install_apk "${apks[0]}"
check_avd
install_apk "${apks[1]}"

printf 'Both APKs installed on %s (%s). No tests were run.\n' \
  "$expected_avd" "$serial"
