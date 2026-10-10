#!/usr/bin/env bash
set -euo pipefail
workspace="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$workspace/env.sh"
flavor="${1:-offline}"
suite="${2:-core}"
build_option="${3:-}"
test_filter="${4:-}"
# POSEGUARD_BUILD_TYPE=release (set by `pg release-test`) runs the same suites against the R8-minified release APK.
build_type="${POSEGUARD_BUILD_TYPE:-debug}"
[[ "$flavor" == offline || "$flavor" == online ]] || { echo 'Choose offline or online.' >&2; exit 2; }
[[ "$build_type" == debug || "$build_type" == release ]] || { echo 'POSEGUARD_BUILD_TYPE must be debug or release.' >&2; exit 2; }
[[ -z "$build_option" || "$build_option" == --no-build ]] || { echo 'Optional flag: --no-build' >&2; exit 2; }
[[ "$(adb get-state)" == device ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]] || { echo 'Select a physical device.' >&2; exit 1; }
export ANDROID_SERIAL="$(adb get-serialno | tr -d '\r')"
safe_serial="${ANDROID_SERIAL//[^A-Za-z0-9_.-]/_}"
exec {device_lock_fd}> "$workspace/.tools/device-$safe_serial.lock"
flock -n "$device_lock_fd" || { echo 'Another PoseGuard test is using this device.' >&2; exit 1; }
output_tag="$flavor-$suite"
[[ "$build_type" == debug ]] || output_tag="$flavor-$suite-$build_type"
output="$workspace/artifacts/device-$output_tag-$(date +%Y%m%d-%H%M%S)"
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

uses_lab=false
[[ "$flavor" == online && "$suite" != camera && "$suite" != ui ]] && uses_lab=true
if [[ "$uses_lab" == true ]]; then
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
capital_build_type="${build_type^}"
apk_dir="$workspace/poseGuard/app/build/outputs/apk"
app_apk="$apk_dir/$flavor/$build_type/app-$flavor-$build_type.apk"
test_apk="$apk_dir/androidTest/$flavor/$build_type/app-$flavor-$build_type-androidTest.apk"
if [[ "$build_type" == release ]]; then
  source "$workspace/tools/release-env.sh"
  export POSEGUARD_TEST_KEEP_RULES="$workspace/tools/proguard/instrumentation-keep.pro"
  gradle_args=(-I "$workspace/tools/gradle/release-tests.init.gradle")
else
  gradle_args=()
fi
if [[ "$build_option" != --no-build ]]; then
  gradle -p "$workspace/poseGuard" "${gradle_args[@]}" ":app:assemble${capital_flavor}${capital_build_type}" ":app:assemble${capital_flavor}${capital_build_type}AndroidTest" --console=plain > "$output/build.log" 2>&1 || { tail -n 60 "$output/build.log" >&2; exit 1; }
fi
# Make sure the APK about to run really is the configuration under test.
debuggable_flag="$(aapt2 dump badging "$app_apk" | grep -c '^application-debuggable' || true)"
if [[ "$build_type" == release && "$debuggable_flag" != 0 ]]; then echo 'Release APK is debuggable; refusing to treat it as a release test.' >&2; exit 1; fi
if [[ "$build_type" == debug && "$debuggable_flag" == 0 ]]; then echo 'Debug APK is not debuggable; stale build output?' >&2; exit 1; fi
printf 'Build type under test: %s (%s)\n' "$build_type" "$app_apk" | tee "$output/build-type.txt"
adb install -r "$app_apk"
adb install -r "$test_apk"
camera_class=com.incident201.poseguard.scenario.CameraSmokeTest
ui_class=com.incident201.poseguard.scenario.AppFlowScenarioTest
runner_args=()
case "$suite" in
  core|all) runner_args+=(-e notAnnotation androidx.test.filters.LargeTest) ;;
  camera) runner_args+=(-e class "$camera_class") ;;
  ui) runner_args+=(-e class "$ui_class") ;;
  intiface) runner_args+=(-e class com.incident201.poseguard.scenario.IntifaceControllerScenarioTest,com.incident201.poseguard.scenario.IntifaceSessionScenarioTest) ;;
  restart) runner_args+=(-e class "$restart_class") ;;
  scenario) [[ -n "$test_filter" ]] || { echo 'Test class is required.' >&2; exit 2; }; runner_args+=(-e class "$test_filter") ;;
  *) echo 'Unknown suite.' >&2; exit 2 ;;
esac
if [[ "$uses_lab" == true ]]; then runner_args+=(-e poseguardIntiface true); fi
run_started="$(adb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
run_instrumentation() {
  local log="$1"
  shift
  adb shell am instrument -w -r "$@" com.incident201.poseguard.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
  # The crash buffer is shared by every app on the phone; keep only what happened since this run began.
  local crash_log="$output/crashes-$(basename "$log" .log).log" status=0
  adb logcat -b crash -d -v threadtime -T "$run_started" > "$crash_log" || true
  if grep -qE 'Process: com\.incident201\.poseguard|>>> com\.incident201\.poseguard|pid [0-9]+ \([^)]*poseguard[^)]*\)' "$crash_log"; then
    echo "PoseGuard crashed during this run; see $crash_log" >&2
    status=1
  else
    python3 "$workspace/tools/check-instrumentation.py" "$log" || status=$?
  fi
  if [[ "$status" != 0 ]]; then
    # Everything that was logged since the run began, for diagnosing the failure afterwards.
    adb logcat -d -v threadtime -T "$run_started" > "$output/logcat-$(basename "$log" .log).log" || true
  fi
  return "$status"
}
if [[ "$suite" == all ]]; then
  run_instrumentation "$output/instrumentation.log" "${runner_args[@]}"
  run_instrumentation "$output/camera.log" -e class "$camera_class"
  run_instrumentation "$output/ui.log" -e class "$ui_class"
elif [[ "$suite" != restart ]]; then
  run_instrumentation "$output/instrumentation.log" "${runner_args[@]}"
fi
if [[ "$flavor" == online && ( "$suite" == core || "$suite" == all || "$suite" == intiface || "$suite" == restart ) ]]; then
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
