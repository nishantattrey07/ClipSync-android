# AndroidJUnitRunner starts in the target process and requires this test-only dependency.
-keep class androidx.tracing.Trace { *; }

# AndroidX Test uses Kotlin runtime classes from the target process after instrumentation starts.
-keep class kotlin.** { *; }

# The placeholder app does not reference protocol code yet; retain it for the external smoke test.
-keep class com.nishantattrey.clipsync.core.protocol.** { *; }
