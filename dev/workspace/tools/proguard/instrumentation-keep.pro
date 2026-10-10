# Extra R8 input used ONLY when `pg release-test` builds the release APK that instrumentation runs against.
# The release rules of the project (proguard-rules.pro, consumer rules of MediaPipe, Jetty, Buttplug, protobuf ...)
# stay fully in force. The test APK shares classes with the app, so what it calls must survive shrinking:
#
# * Libraries of the test runner and Compose test rules (androidx.tracing.Trace, lifecycle, coroutines ...) are
#   not used by the product, so R8 would delete or rewrite them in the app APK, while the test APK's own copy of
#   the runner expects them unchanged. They are kept as they are; none of them is among the libraries that
#   needed project-specific rules (MediaPipe, protobuf, Flogger, Jetty, Buttplug), which stay fully processed.
-keep class androidx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
# * The scenarios call into the application directly (GameViewModel, TimelapseRecorder, Intiface controller ...).
#   Names may still be obfuscated and code optimized; only removal is prevented.
-keep,allowobfuscation,allowoptimization class com.incident201.poseguard.** { *; }
-dontwarn org.junit.**
-dontwarn junit.framework.**
