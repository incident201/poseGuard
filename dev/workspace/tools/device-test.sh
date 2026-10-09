#!/usr/bin/env bash
set -euo pipefail
workspace="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$workspace/env.sh"
flavor="${1:-offline}"
suite="${2:-core}"
build_option="${3:-}"
test_filter="${4:-}"
[[ "$flavor" == offline || "$flavor" == online ]] || { echo 'Choose offline or online.' >&2; exit 2; }
[[ -z "$build_option" || "$build_option" == --no-build ]] || { echo 'Optional flag: --no-build' >&2; exit 2; }
[[ "$(adb get-state)" == device ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]] || { echo 'Select a physical device.' >&2; exit 1; }
export ANDROID_SERIAL="$(adb get-serialno | tr -d '\r')"
safe_serial="${ANDROID_SERIAL//[^A-Za-z0-9_.-]/_}"
exec {device_lock_fd}> "$workspace/.tools/device-$safe_serial.lock"
flock -n "$device_lock_fd" || { echo 'Another PoseGuard test is using this device.' >&2; exit 1; }
output="$workspace/artifacts/device-$flavor-$suite-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$output"
lab_pid=''
lab_started=false
ports_forwarded=false
restart_pending=false
restart_class=com.incident201.poseguard.scenario.IntifaceRestartScenarioTest
declare -A previous_reverse
cleanup() {
  if [[ "$restart_pending" == true ]]; then
    adb shell am instrument -w -r -e class "$restart_class" -e poseguardRestartPhase restore \
      com.incident201.poseguard.test/androidx.test.runner.AndroidJUnitRunner > "$output/restart-restore.log" 2>&1 || true
    if ! python3 "$workspace/tools/check-instrumentation.py" "$output/restart-restore.log"; then
      echo 'Could not restore preferences after interrupted restart test. See restart-restore.log.' >&2
    fi
  fi
  if [[ "$ports_forwarded" == true ]]; then
    for port in 12345 8787; do
      if [[ -n "${previous_reverse[$port]}" ]]; then
        adb reverse "tcp:$port" "${previous_reverse[$port]}" >/dev/null 2>&1 || true
      else
        adb reverse --remove "tcp:$port" >/dev/null 2>&1 || true
      fi
    done
  fi
  if [[ "$lab_started" == true ]]; then kill -TERM "$lab_pid" 2>/dev/null || true; wait "$lab_pid" || true; fi
}
trap cleanup EXIT

if [[ "$flavor" == online && "$suite" != camera ]]; then
  if curl -fsS --max-time 1 http://127.0.0.1:8787/health > "$output/health.json" 2>/dev/null; then
    python3 - "$output/health.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1])).get('lab') == 'poseguard-intiface'
PY
  else
    "$workspace/.tools/python/bin/python" "$workspace/tools/intiface/lab.py" serve --output "$output/intiface" > "$output/lab.log" 2>&1 &
    lab_pid=$!
    lab_started=true
    lab_ready=false
    for attempt in {1..100}; do
      if curl -fsS --max-time 1 http://127.0.0.1:8787/health > "$output/health.json" 2>/dev/null; then lab_ready=true; break; fi
      if ! kill -0 "$lab_pid" 2>/dev/null; then cat "$output/lab.log" >&2; exit 1; fi
      sleep 0.1
    done
    [[ "$lab_ready" == true ]] || { cat "$output/lab.log" >&2; exit 1; }
    "$workspace/.tools/python/bin/python" "$workspace/tools/intiface/lab.py" smoke > "$output/smoke.log" 2>&1 || { cat "$output/smoke.log" >&2; exit 1; }
  fi
  for port in 12345 8787; do
    previous_reverse[$port]="$(adb reverse --list | awk -v wanted="tcp:$port" '$2 == wanted {print $3}')"
  done
  ports_forwarded=true
  for port in 12345 8787; do adb reverse "tcp:$port" "tcp:$port"; done
fi

capital_flavor="${flavor^}"
if [[ "$build_option" != --no-build ]]; then
  gradle -p "$workspace/poseGuard" ":app:assemble${capital_flavor}Debug" ":app:assemble${capital_flavor}DebugAndroidTest" --console=plain > "$output/build.log" 2>&1 || { tail -n 60 "$output/build.log" >&2; exit 1; }
fi
adb install -r "$workspace/poseGuard/app/build/outputs/apk/$flavor/debug/app-$flavor-debug.apk"
adb install -r "$workspace/poseGuard/app/build/outputs/apk/androidTest/$flavor/debug/app-$flavor-debug-androidTest.apk"
runner_args=()
case "$suite" in
  core) runner_args+=(-e notAnnotation androidx.test.filters.LargeTest) ;;
  camera) runner_args+=(-e class com.incident201.poseguard.scenario.CameraSmokeTest) ;;
  intiface) runner_args+=(-e class com.incident201.poseguard.scenario.IntifaceControllerScenarioTest,com.incident201.poseguard.scenario.IntifaceSessionScenarioTest) ;;
  restart) runner_args+=(-e class "$restart_class") ;;
  scenario) [[ -n "$test_filter" ]] || { echo 'Test class is required.' >&2; exit 2; }; runner_args+=(-e class "$test_filter") ;;
  *) echo 'Unknown suite.' >&2; exit 2 ;;
esac
if [[ "$flavor" == online && "$suite" != camera ]]; then runner_args+=(-e poseguardIntiface true); fi
run_instrumentation() {
  local log="$1"
  shift
  adb shell am instrument -w -r "$@" com.incident201.poseguard.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
  adb logcat -b crash -d -v threadtime > "$output/crashes.log"
  python3 "$workspace/tools/check-instrumentation.py" "$log"
}
if [[ "$suite" != restart ]]; then
  run_instrumentation "$output/instrumentation.log" "${runner_args[@]}"
fi
if [[ "$flavor" == online && ( "$suite" == core || "$suite" == intiface || "$suite" == restart ) ]]; then
  restart_pending=true
  for phase in prepare verify; do
    curl -fsS -X POST http://127.0.0.1:8787/reset >/dev/null
    if [[ "$phase" == verify ]]; then
      adb shell am force-stop com.incident201.poseguard
      [[ -z "$(adb shell pidof com.incident201.poseguard | tr -d '\r')" ]]
    fi
    run_instrumentation "$output/restart-$phase.log" -e class "$restart_class" -e poseguardIntiface true -e poseguardRestartPhase "$phase"
  done
  run_instrumentation "$output/restart-restore.log" -e class "$restart_class" -e poseguardRestartPhase restore
  restart_pending=false
fi
printf 'Reports: %s\n' "$output"
