# PoseGuard environment without Android Studio

All tools are installed locally in `.tools/`; the project source is in `poseGuard/`. The system PATH and shell settings are not changed. Android Studio, the Android Emulator and virtual device images are not installed in this environment. Access to an already connected USB device works without sudo.

## Usage

From this folder:

```bash
source ./env.sh
pg doctor
pg build                 # debug APKs: offline and online
pg test                  # JVM/Robolectric, both flavors
pg lint                  # Android lint, both flavors
pg test-apk              # instrumentation test APKs
pg verify                # all of the above + release R8 check without a release key
pg quick-test offline    # fast session and codec scenarios on the phone
pg quick-test online     # the same scenarios + a real Intiface server and test device
pg camera-smoke          # camera, MediaPipe, Activity recreation and session timelapse
pg ui-test               # user flows through the real MainActivity
pg release-audit         # production release APK (R8): static audit and launch on the phone
pg release-test online   # the same scenarios on the minified release APK
pg release-check         # the full release pipeline for both flavors
pg restart-test          # saved device after a real force-stop of the app
pg scenario offline 'SessionScenarioTest#slowDisplacementIsClassifiedAsDriftAndEmitsDriftCue' --no-build
```

`./bin/pg` can be used without activating the environment. For a single flavor use `pg build offline`, `pg test online` and similar commands. Any Gradle task runs through `pg gradle :app:tasks --all`; after activation, plain `gradle` also works from the project folder. The repository has no Gradle Wrapper, so the environment provides the same Gradle version as GitHub Actions.

Gradle reports are in `poseGuard/app/build/reports/`, APKs in `poseGuard/app/build/outputs/apk/`. The screenshot test uses Robolectric with API 34 inside the JVM and needs neither an emulator nor a separate Android SDK 34.

## Physical device

```bash
pg devices
pg install offline       # build and install the debug APK, keeping app data
pg launch
pg device-test offline   # instrumentation tests; install the app and the test APK
pg mirror                # phone screen and control via scrcpy
pg logs                  # logcat of the current PoseGuard process only
pg crashes               # Android crash buffer
pg diagnostics           # dumpsys: app, memory, camera, sensors, temperature
pg screenshot
pg bugreport
pg profile 10            # Perfetto system trace, 10 seconds
pg debug                 # restart waiting for JDB, port 8700
```

`pg debug` attaches JDB to Android JDWP. Example JDB commands: `stop in com.incident201.poseguard.MainActivity.onCreate`, `cont`, `threads`, `where`, `locals`, `exit`. For Kotlin, line and local-variable mapping depends on the bytecode; Compose and inline functions may map less conveniently to the sources. When JDB exits, the script clears the wait-for-debugger flag and the port forwarding.

`.perfetto-trace` files can be opened in [Perfetto UI](https://ui.perfetto.dev/). Logs, screenshots, traces and bugreports are saved to `artifacts/`; these files may contain device data. `pg stop` stops the app.

If several devices are connected, select one:

```bash
export ANDROID_SERIAL=serial_number_from_pg_devices
```

On a new phone, enable USB debugging in Developer options and allow the computer's RSA key. For Wi-Fi, use `pg adb pair IP:PORT` and `pg adb connect IP:PORT`; the phone shows the addresses and pairing code. If USB is unavailable because of permissions, on CachyOS/Arch the system package `android-udev` installs the matching udev rules; the current tested configuration does not need it.

For Intiface checks, the Intiface Engine 5.0.4 server is installed, which is the backend of Intiface Central. `pg intiface` starts the server, two virtual devices (A: two motors, B: one motor) and a recorder of each device's commands. In another terminal use `pg adb reverse tcp:12345 tcp:12345`, and `ws://127.0.0.1:12345` in the online app. `pg intiface-test` runs the connect, disconnect, switch, selection-restore and signal checks automatically. The Intiface Central GUI is not installed. Scenarios and coverage limits are described in [TESTING.md](TESTING.md).

## Components and setup

| Component | Version / purpose |
| --- | --- |
| Eclipse Temurin JDK | 17.0.20.1, including javac, JDB, keytool, jstack, jcmd |
| Gradle | 9.6.0, as in the project's CI |
| Android Command-line Tools | 23.0: sdkmanager, Android CLI, apkanalyzer, R8/retrace |
| Android SDK Platform | 37.0 revision 2; `android-37` alias for the project |
| Android Build Tools | 37.0.0: AAPT2, D8, apksigner, zipalign |
| Android Platform Tools | 37.0.1: ADB and related utilities |
| Android Sources | 37.0 revision 2 |
| scrcpy | 5.0.1, prebuilt for Linux x86_64 |
| Intiface Engine | 5.0.4, a real Buttplug server with a virtual WebSocket device |
| Python venv | local environment with websockets 16.0 for the bench and command recording |

```bash
./tools/setup.sh
```

Setup is repeatable: existing tools are reused, and archives are verified against published checksums. The script accepts the SDK licenses, installs only the listed SDK packages, writes a local `sdk.dir` and restores the debug keystore from the repository file, as CI does. The Gradle cache and Java toolchain live inside `.tools/`; existing user ADB keys are used unchanged. The debug keystore and `local.properties` are ignored by the project's Git.

Release signing requires your own `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`; the environment does not create real release keys and does not change the project's signing settings. For local checks, `pg release-*` sign builds with the repository's public debug key under the alias `upload` (keystore `.tools/release-test.p12`, variables set only inside these commands), so the release build installs over the debug build without losing data.

Versions match the [AGP 9.4 requirements](https://developer.android.com/build/releases/agp-9-4-0-release-notes). Reference for [ADB](https://developer.android.com/tools/adb) and [scrcpy on Linux](https://github.com/Genymobile/scrcpy/blob/master/doc/linux.md).
