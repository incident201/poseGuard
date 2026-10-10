#!/usr/bin/env bash
# Builds the production release APK (the project's R8 configuration, no test additions), audits it statically and,
# when a phone is connected, starts that exact file on the device and checks that it keeps running.
set -euo pipefail
workspace="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$workspace/env.sh"
source "$workspace/tools/release-env.sh"
package=com.incident201.poseguard
selection="${1:-both}"
case "$selection" in
  offline|online) flavors=("$selection") ;;
  both) flavors=(offline online) ;;
  *) echo 'Flavor must be offline, online or both.' >&2; exit 2 ;;
esac
output="$workspace/artifacts/release-audit-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$output"
device_ready=false
if [[ "$(adb get-state 2>/dev/null || true)" == device && "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  device_ready=true
else
  echo 'No physical device is ready: the on-device launch check is skipped.' >&2
fi

failed=false
for flavor in "${flavors[@]}"; do
  capital="${flavor^}"
  echo "== $flavor: building the production release APK"
  gradle -p "$workspace/poseGuard" ":app:assemble${capital}Release" --console=plain > "$output/build-$flavor.log" 2>&1 \
    || { tail -n 60 "$output/build-$flavor.log" >&2; exit 1; }
  apk="$output/app-$flavor-release.apk"
  mapping="$output/mapping-$flavor.txt"
  cp "$workspace/poseGuard/app/build/outputs/apk/$flavor/release/app-$flavor-release.apk" "$apk"
  cp "$workspace/poseGuard/app/build/outputs/mapping/${flavor}Release/mapping.txt" "$mapping"
  python3 "$workspace/tools/release-audit.py" "$flavor" "$apk" "$mapping" | tee "$output/audit-$flavor.txt" || failed=true

  if [[ "$device_ready" == true ]]; then
    echo "== $flavor: starting the production APK on the device"
    adb install -r "$apk" > "$output/install-$flavor.log"
    adb shell pm grant "$package" android.permission.CAMERA
    [[ "$flavor" == offline ]] || adb shell pm grant "$package" android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null || true
    adb shell am force-stop "$package"
    started="$(adb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
    adb shell am start -W -n "$package/.MainActivity" > "$output/launch-$flavor.log"
    sleep 10
    pid="$(adb shell pidof -s "$package" | tr -d '\r' || true)"
    adb logcat -b crash -d -v threadtime -T "$started" > "$output/launch-crashes-$flavor.log" || true
    # Someone may be using the phone, so being moved to the background is fine; dying is not.
    if [[ "$pid" =~ ^[0-9]+$ ]] && ! grep -q "$package" "$output/launch-crashes-$flavor.log"; then
      frames="$(adb logcat -d --pid="$pid" -v threadtime | grep -c 'MediaPipe frame ts=' || true)"
      echo "  PASS  production $flavor APK starts, is still running after 10 s and did not crash  [MediaPipe frames logged: $frames]"
    else
      echo "  FAIL  production $flavor APK did not stay running; see $output/launch-crashes-$flavor.log"
      failed=true
    fi
    adb shell am force-stop "$package"
  fi
done
printf 'Reports: %s\n' "$output"
[[ "$failed" == false ]]
