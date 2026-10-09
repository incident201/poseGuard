#!/usr/bin/env bash
set -euo pipefail
workspace="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$workspace/env.sh"
[[ "$(uname -s)/$(uname -m)" == Linux/x86_64 ]] || { echo 'This toolchain requires Linux x86_64.' >&2; exit 1; }
for tool in curl unzip tar sha256sum sha1sum python3 flock; do command -v "$tool" >/dev/null; done
mkdir -p "$workspace/.tools/downloads" "$ANDROID_HOME/cmdline-tools" "$GRADLE_USER_HOME" "$workspace/artifacts"

download() {
  local url="$1" name="$2" algorithm="$3" checksum="$4"
  local archive="$workspace/.tools/downloads/$name"
  if [[ ! -f "$archive" ]]; then
    curl --fail --location --retry 3 --connect-timeout 30 --output "$archive.part" "$url"
    mv -- "$archive.part" "$archive"
  fi
  printf '%s  %s\n' "$checksum" "$archive" | "${algorithm}sum" --check --status
}

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  download 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz' jdk-17.tar.gz sha256 3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e
  mkdir -p "$JAVA_HOME"
  tar -xzf "$workspace/.tools/downloads/jdk-17.tar.gz" -C "$JAVA_HOME" --strip-components=1
fi
if [[ ! -x "$workspace/.tools/gradle-9.6.0/bin/gradle" ]]; then
  download 'https://services.gradle.org/distributions/gradle-9.6.0-bin.zip' gradle-9.6.0-bin.zip sha256 bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
  unzip -q "$workspace/.tools/downloads/gradle-9.6.0-bin.zip" -d "$workspace/.tools"
fi
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  download 'https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip' commandline-tools-23.zip sha1 e025545c62a8e64c7559119566a569fb1dec5f60
  unzip -q "$workspace/.tools/downloads/commandline-tools-23.zip" -d "$ANDROID_HOME/cmdline-tools"
  mv -- "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
if [[ ! -x "$workspace/.tools/scrcpy/scrcpy" ]]; then
  download 'https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-linux-x86_64-v5.0.1.tar.gz' scrcpy-5.0.1.tar.gz sha256 9f969d30cc574816077edecda65719c1c70b0aee1df1ba5fa00779ac07b8fd6e
  mkdir -p "$workspace/.tools/scrcpy"
  tar -xzf "$workspace/.tools/downloads/scrcpy-5.0.1.tar.gz" -C "$workspace/.tools/scrcpy" --strip-components=1
fi
if [[ ! -x "$workspace/.tools/intiface-engine/intiface-engine" ]]; then
  download 'https://github.com/buttplugio/buttplug/releases/download/intiface-engine-5.0.4/intiface-engine-v5.0.4-linux-x64.zip' intiface-engine-5.0.4.zip sha256 ea2b40eca8369e01edcaa00076315bde0d7352108d38c226b77f1818395d2e66
  mkdir -p "$workspace/.tools/intiface-engine"
  unzip -q "$workspace/.tools/downloads/intiface-engine-5.0.4.zip" -d "$workspace/.tools/intiface-engine"
  chmod +x "$workspace/.tools/intiface-engine/intiface-engine"
fi
if [[ ! -x "$workspace/.tools/python/bin/python" ]]; then
  python3 -m venv "$workspace/.tools/python"
fi
"$workspace/.tools/python/bin/python" -m pip install -r "$workspace/tools/intiface/requirements.txt"

# Accept SDK licenses as part of the requested SDK installation. Ignore only
# yes's expected SIGPIPE; preserve sdkmanager's actual exit code.
set +o pipefail
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > "$workspace/artifacts/sdk-licenses.log" 2>&1
license_status="${PIPESTATUS[1]}"
set -o pipefail
[[ "$license_status" == 0 ]] || { cat "$workspace/artifacts/sdk-licenses.log" >&2; exit "$license_status"; }
sdkmanager --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-37.0' 'build-tools;37.0.0' 'sources;android-37.0'
if [[ ! -e "$ANDROID_HOME/platforms/android-37" ]]; then
  ln -s android-37.0 "$ANDROID_HOME/platforms/android-37"
fi

python3 - "$workspace/poseGuard/local.properties" "$ANDROID_HOME" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
lines = path.read_text().splitlines() if path.exists() else []
lines = [line for line in lines if not line.lstrip().startswith('sdk.dir=')]
sdk = sys.argv[2].replace('\\', '\\\\').replace(':', '\\:').replace(' ', '\\ ')
path.write_text('\n'.join(lines + ['sdk.dir=' + sdk]) + '\n')
PY
if [[ ! -f "$workspace/poseGuard/debug.keystore" ]]; then
  if [[ -f "$workspace/poseGuard/debug.keystore.base64" ]]; then
    base64 --decode "$workspace/poseGuard/debug.keystore.base64" > "$workspace/poseGuard/debug.keystore"
  else
    keytool -genkeypair -keystore "$workspace/poseGuard/debug.keystore" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
  fi
  chmod 600 "$workspace/poseGuard/debug.keystore"
fi
cat > "$GRADLE_USER_HOME/gradle.properties" <<EOF
org.gradle.java.installations.paths=$JAVA_HOME
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
EOF
java -version
gradle --version
adb version
scrcpy --version
echo "Toolchain installed in $workspace/.tools"
