#!/usr/bin/env python3
"""Static audit of a release APK produced by the project's real R8 configuration.

usage: release-audit.py FLAVOR APK MAPPING

Everything here is a property that has to hold for the shipped file but that a debug build, the JVM tests and
`minifyReleaseWithR8` alone cannot show: what the APK declares, what R8 kept or removed, and how assets are packed.
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

flavor, apk, mapping = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
assert flavor in ("offline", "online")
failures = []


def check(ok: bool, description: str, detail: str = "") -> None:
    print(f"  {'PASS' if ok else 'FAIL'}  {description}" + (f"  [{detail}]" if detail else ""))
    if not ok:
        failures.append(description)


def run(*command: str) -> str:
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


print(f"{flavor}: {apk.name}, {apk.stat().st_size / 1e6:.1f} MB")

# --- what the APK declares -------------------------------------------------------------------------------
badging = run("aapt2", "dump", "badging", str(apk))
check("application-debuggable" not in badging, "not debuggable")
check("package: name='com.incident201.poseguard'" in badging, "package name is unchanged")
check("targetSdkVersion:'37'" in badging and "minSdkVersion:'30'" in badging, "minSdk 30 / targetSdk 37")
permissions = set(re.findall(r"uses-permission(?:-sdk-23)?: name='([^']+)'", badging))
network = {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.ACCESS_LOCAL_NETWORK"}
check({"android.permission.CAMERA", "android.permission.VIBRATE"} <= permissions, "camera and vibrate permissions declared")
if flavor == "offline":
    check(not (permissions & network), "offline declares no network permission", ", ".join(sorted(permissions & network)))
else:
    check({"android.permission.INTERNET", "android.permission.ACCESS_LOCAL_NETWORK"} <= permissions,
          "online declares INTERNET and ACCESS_LOCAL_NETWORK")
# Strings looked up at runtime for the language chosen in the app must exist for all six languages after shrinking.
resources = run("aapt2", "dump", "resources", str(apk)).splitlines()
translated = {}
current = None
for line in resources:
    header = re.match(r"\s+resource 0x[0-9a-f]+ string/(\w+)", line)
    if header:
        current = header.group(1)
        translated[current] = set()
    elif current:
        value = re.match(r"\s+\((\w*)\) ", line)
        if value:
            translated[current].add(value.group(1) or "en")
languages = {"en", "de", "es", "fr", "it", "ru"}
for key in ("status_initial", "camera_no_body", "place_device_still", "onboarding_next", "final_save_timelapse", "violation_recorded"):
    check(languages <= translated.get(key, set()), f"string/{key} exists in all six languages", " ".join(sorted(translated.get(key, set()))))
verify = subprocess.run(["apksigner", "verify", str(apk)], capture_output=True, text=True)
check(verify.returncode == 0, "signature verifies")
align = subprocess.run(["zipalign", "-c", "-P", "16", "4", str(apk)], capture_output=True, text=True)
check(align.returncode == 0, "zip alignment is valid for 4 KB and 16 KB page sizes")

# --- how assets and native code are packed -----------------------------------------------------------------
with zipfile.ZipFile(apk) as archive:
    entries = {info.filename: info for info in archive.infolist()}
    for asset in ("pose_landmarker_heavy.task", "pose_landmarker_full.task", "blaze_face_short_range.tflite"):
        info = entries.get(f"assets/{asset}")
        check(info is not None and info.compress_type == zipfile.ZIP_STORED, f"assets/{asset} present and stored uncompressed")
    jni = entries.get("lib/arm64-v8a/libmediapipe_tasks_jni.so")
    check(jni is not None and jni.compress_type == zipfile.ZIP_STORED, "MediaPipe JNI library packed uncompressed for arm64-v8a")
    dex_names = sorted(name for name in entries if re.fullmatch(r"classes\d*\.dex", name))
    dex = b"".join(archive.read(name) for name in dex_names)

# --- what R8 kept and removed -----------------------------------------------------------------------------
def has(descriptor: str) -> bool:
    return descriptor.encode() in dex


check(has("Lcom/incident201/poseguard/MainActivity;"), "MainActivity keeps its manifest name")
for descriptor, reason in (
    ("Lcom/google/mediapipe/framework/Graph;", "MediaPipe framework classes keep names for JNI"),
    ("Lcom/google/common/flogger/", "Flogger is kept"),
):
    check(has(descriptor), reason)
jetty_or_buttplug = [m for m in ("org/eclipse/jetty", "blackspherefollower", "buttplug4j") if has(m)]
if flavor == "offline":
    check(not jetty_or_buttplug, "offline contains no Jetty or Buttplug code", ", ".join(jetty_or_buttplug))
else:
    check(has("Lio/github/blackspherefollower/buttplug4j/protocol/"), "Buttplug protocol classes keep their names for JSON")
    check(has("Lorg/eclipse/jetty/websocket/api/annotations/WebSocket;"), "Jetty WebSocket annotations are kept")

check(mapping.is_file() and mapping.stat().st_size > 0, "R8 mapping file produced")
own = renamed = 0
renamed_names = {}
for line in mapping.read_text().splitlines():
    match = re.match(r"^(com\.incident201\.poseguard\.[\w.$]+) -> ([\w.$]+):$", line)
    if match:
        own += 1
        if match.group(1) != match.group(2):
            renamed += 1
            renamed_names[match.group(1)] = match.group(2)
share = renamed / own if own else 0
check(own > 0 and share >= 0.8, "application code is obfuscated by R8", f"{renamed}/{own} classes renamed")
check("com.incident201.poseguard.viewmodel.GameViewModel" in renamed_names, "GameViewModel is renamed (a real minified build)")

print(f"{flavor}: {'OK' if not failures else str(len(failures)) + ' check(s) failed'}")
sys.exit(1 if failures else 0)
