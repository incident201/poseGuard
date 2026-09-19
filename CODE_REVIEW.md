# Independent code review — 2026-09-18

Reviewed baseline: `4144e18` (including the six-language localization).
Working branch: `codex/independent-review-refactor`.

## Scope and approach

Reviewed the application boundaries and their interactions: CameraX input and Compose lifecycle,
MediaPipe CPU/GPU switching and model selection, bitmap ownership, pose tracking/smoothing/identity/
occlusion, the session state machine and penalties, settings/localization, TTS/MP3/PCM, timelapse
encoding/export, flavor separation, Intiface reconnection/device selection, manifests, R8 rules,
dependencies and CI/release workflows. This is a source/automated-test review, not a claim that every
device/driver combination has been exercised.

The review concentrated on observable correctness, failure handling and resource lifetime. It did
not change movement thresholds, model files, MediaPipe options, dependency versions, preference keys
or the offline/online architecture. Large UI files were not split merely for style.

## Findings and corrections

### 1. P1 — Camera bitmaps could be recycled while a delegate still used them

**Baseline:** `GameViewModel.registerCameraFrame/clearCameraFrameCache`,
`PoseLandmarkerService.LandmarkerBackend.detect`.

The cache recycled an evicted bitmap directly, although the same instance could be queued/running on
another executor. Checking `isRecycled` before a native call was not synchronization: eviction or
screen disposal could recycle it immediately afterward. A stalled submission worker could also
accumulate an unbounded submission queue.

**Correction:** `CameraFrame` explicitly shares ownership between the submitter, cache/result
processor and native-submission worker. The final owner closes the bitmap-backed MPImage; cache
eviction alone cannot recycle a frame still in use. Each backend accepts at most one pending native
submission. MediaPipe retains its own flow-limiting behavior.

**Coverage:** `CameraFrameTest`, `FramePipelineTest`,
`PoseLandmarkerLifecycleTest.submissionIsBoundedAndKeepsBitmapAliveUntilNativeSubmissionReturns`.
The installed MediaPipe 1.0.0 bytecode and the
[native bitmap-to-image implementation](https://raw.githubusercontent.com/google-ai-edge/mediapipe/master/mediapipe/java/com/google/mediapipe/framework/jni/android_packet_creator_jni.cc)
were checked for ownership semantics.

### 2. P1 — Stale GPU failures could override a newer mode and poison the cache

**Baseline:** `PoseLandmarkerService.onBackendInitializationFailed`.

A GPU initializer may finish after its watchdog, a manual CPU selection or service disposal. Its
exception path did not verify that it was still the current backend, and could persist GPU failure or
publish CPU fallback into a newer session.

**Correction:** error handling checks backend identity and closed state while holding the state lock.
GPU runtime fallback rechecks identity inside the same lock. UI callbacks from replaced service
instances are generation-guarded. Repeated CPU status cannot turn a manual CPU preference into an
automatically resolved preference.

**Coverage:** controlled fake-native lifecycle tests for CPU-first probing, GPU runtime fallback,
late initialization failure after a manual switch, failure after close, and manual preference
preservation. `PoseLandmarkerEngine` is a narrow native-API boundary, not a second acceleration policy.

### 3. P2 — An app update reset Auto, but could still reuse an old GPU failure

The ViewModel's version-based migration and the delegate's GPU-failure cache used different
invalidation rules. An unchanged fingerprint/model/probe identifier could suppress the intended new
probe after an app update.

**Correction:** the GPU compatibility signature now includes `BuildConfig.VERSION_CODE`, in addition
to fingerprint, probe version and model asset. Existing manual CPU preferences remain manual.

### 4. P1 — Unmatched results bypassed generation invalidation

**Baseline:** `GameViewModel.processMediaPipeResultsInternal`.

When the corresponding bitmap had already been removed, the callback was treated as belonging to
the current processing generation. A delayed result could therefore restore obsolete landmarks or
affect a new session after input reset.

**Correction:** unmatched results are discarded; input-continuity reset invalidates both pending
frames and the latest analyzed frame. Out-of-order callbacks cannot roll the pipeline back to an
older timestamp when CPU/GPU streams hand over.

**Coverage:** matching, unregistered, cleared and evicted result scenarios in `FramePipelineTest`.

### 5. P1 — Exact-mode timing could lose penalties and drift with delayed dispatch

**Baseline:** `GameViewModel.startTimerLoop/applyPenalty`.

The main coroutine performed a read-modify-write decrement of `_timerSeconds`, while the processing
worker added penalties under a different synchronization boundary. A decrement could overwrite a
penalty; the zero check/reservation could race with a just-added penalty. Repeated `delay(1000)`
also counted callbacks rather than actual elapsed time.

**Correction:** both timer modes use `SessionClock` and monotonic elapsed time. Penalty updates,
timer snapshots and success reservation share the session lock. Target extension saturates instead
of overflowing. Terminal success and manual reset/start use the processing lock consistently.
Duplicated final-screen cleanup now calls the existing stop path. Penalty cooldown also uses a
monotonic clock.

**Coverage:** delayed ticks, penalty at the deadline, reset and overflow in `SessionClockTest`.

### 6. P1 — A session could succeed after camera processing stopped

**Baseline:** the UI's ON_STOP handler stopped only Intiface output; the timer continued after
CameraX stopped supplying frames. A stalled delegate similarly produced no disappearance callbacks.

**Correction:** loss of the foreground camera ends an active countdown/holding session with the
existing camera-unavailable reason; stabilization is cancelled. A five-second analyzed-frame
watchdog prevents a stalled pipeline from silently awarding success.

**Coverage:** backgrounding during stabilization and holding in `FramePipelineTest`.
This is an intentional behavior correction: background time no longer counts as validated holding.

### 7. P1 — Timelapse shutdown could block the UI, leak partial setup or exhaust memory

**Baseline:** `TimelapseRecorder.offerFrame/ensureEncoder/drainEncoder/release`.

- The frame executor accepted unlimited full-size copies when encoding fell behind.
- End-of-stream draining could loop forever on repeated codec timeouts.
- UI disposal synchronously waited for the encoder executor.
- Resources were assigned to owned fields only after all setup succeeded, so an intermediate
  configure/create-surface/start exception left partial native resources outside cleanup.
- MP4 finalization failures were swallowed and could expose an invalid file as ready.

**Correction:** at most two encoding frames are retained, admission is rechecked against stop/discard,
EOS draining and stop waiting are bounded, release queues cleanup without blocking the UI, partial
setup is owned immediately, and failed container finalization invalidates the output.

**Coverage:** blocked-worker release and bounded queue test in `TimelapseLifecycleTest`.
Actual codec output/duration and gallery export still require a device smoke test.

### 8. P1 — Remembered Intiface selection could target the wrong device

**Baseline:** `OnlineIntifaceController.runConnectToRememberedLocked`.

The first matching model name won before a custom device label, and a reused device index was a
fallback even when the name differed. Two same-model devices, or another server reusing an index,
could receive output meant for a different device.

**Correction:** match the remembered name and custom label; do not use a bare index as identity.
Ambiguous matches require manual selection. No commands or connection protocol were changed.

**Coverage:** five device-matching cases in `RememberedDeviceMatcherTest`. This is consistent with
the [Buttplug device-index lifetime rules](https://buttplug.io/docs/spec/device_information/).
Only the online implementation uses the matcher; offline has no network dependency.

### 9. P2 — Disabled face checking still ran CPU inference

`buildOverlayState` always cropped and ran the face detector, and the detector initialized eagerly.
This consumed CPU/native memory even with the face rule disabled and debugging off.

**Correction:** face inference runs only when its rule or debug display needs it; native detector
creation is lazy. Temporary crops no longer recycle the caller's bitmap if Android returns the
original instance for a full-size crop. Detector shutdown is queued on its processing executor;
ViewModel disposal no longer waits on the UI thread for an in-progress native face call.
Enabled face-rule thresholds are unchanged.

### 10. P2 — TTS service discovery declaration was missing

The app targets Android versions with package visibility filtering, but did not declare the TTS
service query required by the [Android TextToSpeech documentation](https://developer.android.com/reference/android/speech/tts/TextToSpeech).
Working on one device does not establish portability across installed engines.

**Correction:** added only the TTS service intent query, not broad package visibility.

### 11. P2 — Asynchronous camera binding outlived its preview

A pending provider listener could bind after preview release/disposal. The release callback could
also synchronously wait for provider initialization on the UI thread.

**Correction:** camera binding requests have a generation guard; release invalidates pending work
and unbinds only an already available provider, without a blocking `get()`.

### 12. P2 — Static analysis and release shrinking were absent from PR checks

Baseline lint failed with four errors: two non-observable configuration reads in localization
helpers, ignored Scaffold padding, and construction of a ViewModel inside a composable screenshot
test. These were not caught by debug compilation.

**Correction:** use Compose's observable configuration, pass the zero-inset Scaffold padding, and
use the lifecycle ViewModel factory in the test. CI now runs both flavor lint tasks and both release
R8 tasks without requiring signing secrets. Dependency versions are unchanged.

### 13. P2 — Workflow-dispatch inputs were interpolated into shell source

`release.yml` inserted version strings directly into executable shell text in a job with release
signing secrets. Access is limited to dispatch-capable users, but input text should not become code.

**Correction:** pass inputs through environment variables and validate version name/code before
checkout or secret materialization. This edits the local workflow only; no workflow was dispatched.

### 14. P1 — Persistent face-only detection failures left an enabled rule unchecked

**Follow-up approved by the user:** stop the session after a sustained technical outage without
penalizing the user's pose.

**Correction:** during HoldingPose with face checking enabled, Error and NotProcessed share one
five-second monotonic timeout. FaceVisible and FaceNotVisible reset it; neither is a technical error.
Both the result-processing path and the session timer check the deadline, so another detector
callback is not required to stop. A session whose timer expires during a brief outage waits for
recovery or the technical-stop deadline rather than awarding unchecked success.

Preparation time is excluded so taking position does not trigger a false outage. Stop/new-session,
rule changes and terminal cleanup reset the outage state. The technical-stop summary displays
“Session stopped” and a clear explanation in all six languages, preserving existing violation
counts without adding a penalty, defeat audio or punitive Intiface vibration. Ordinary face-rule
violations remain unchanged. The existing Failed terminal state is reused for cleanup/video saving;
an explicit summary flag distinguishes a technical interruption in the UI.

**Coverage:** `FaceCheckAvailabilityTest` covers transient/sustained/mixed failures, recovery,
disabled checking, restart, preparation, timer-end races, ordinary missing-face violations, and
non-punitive Intiface/audio handling.

## Deliberately unchanged / remaining risks

- Native GPU/codec calls can hang inside vendor code. Java/Kotlin cannot safely terminate such a
  call; watchdogs, bounded queues and asynchronous cleanup limit its impact, but an indefinitely
  hung native worker may retain resources until process exit.
- Intiface still has complex cross-thread callback/state handling. Matching tests do not replace
  an integration test against a server with disconnect/reconnect, two identical devices and
  timeout/cancellation scenarios. A cancelled blocking SDK connection is not necessarily interrupted.
- TTS unavailable-language behavior and queued speech after delayed initialization need dedicated
  real-engine tests. MP3/PCM playback was reviewed but not listened to during this audit.
- Numeric tuning, full-body crop geometry, camera overlay alignment and long-session thermal
  behavior remain hardware/visual verification areas. Existing tracker tests were retained.
- Lint warnings are not globally suppressed. Existing warnings include optional modernization,
  unused resources, dependency update notices and plural/backup/App Bundle considerations.
  APK distribution is currently used; an AAB distribution needs explicit language-split handling.
- Large CameraScreen, SettingsScreen and GameViewModel files remain maintenance debt. Further
  architectural extraction should be driven by tests and a separate scope, not bundled with
  behavior fixes. The settings store and all migration keys remain compatible.

## Verification

- Environment: Ubuntu24Dev WSL, JDK 17, Android SDK/build-tools 37, Gradle 9.6.0,
  unchanged dependency versions from the repository workflows.
- Offline Debug and Online Debug: assembled successfully.
- Offline Release and Online Release: assembled successfully with R8 and resource shrinking enabled,
  signed only with the existing local test key (not a production release).
- Review verification before the face-check follow-up: both flavor suites had 74 tests each,
  73 passed and one pre-existing skipped tracker test, zero failures/errors (25 new regression tests).
  The face-check follow-up adds 13 more tests: both flavor suites now have 87 tests each,
  86 passed and the same one pre-existing skipped test, zero failures/errors.
- Both flavor lint tasks: zero errors; existing warning-level debt remains (98 warnings, seven hints).
- Release APK inspection: offline has no INTERNET/ACCESS_NETWORK_STATE/ACCESS_LOCAL_NETWORK
  permissions; online retains its network permissions. Heavy, Full and the face model are bundled.
- `git diff --check`: clean. Models, R8 keep rules and dependency versions are unchanged.

No connected Android device was visible to Windows ADB during this review; no device installation,
on-device inference, audible playback, physical Intiface output or native MP4 playback is claimed.

No release workflow has been run as part of this review.
