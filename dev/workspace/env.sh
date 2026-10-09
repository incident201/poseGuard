# Source this file from Bash to use the workspace-local Android toolchain.
export POSEGUARD_WORKSPACE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="$POSEGUARD_WORKSPACE/.tools/jdk-17"
export ANDROID_HOME="$POSEGUARD_WORKSPACE/.tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$POSEGUARD_WORKSPACE/.tools/gradle-home"
export ADB="$ANDROID_HOME/platform-tools/adb"
export PATH="$POSEGUARD_WORKSPACE/bin:$JAVA_HOME/bin:$POSEGUARD_WORKSPACE/.tools/gradle-9.6.0/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/build-tools/37.0.0:$POSEGUARD_WORKSPACE/.tools/scrcpy:$POSEGUARD_WORKSPACE/.tools/intiface-engine:$PATH"
