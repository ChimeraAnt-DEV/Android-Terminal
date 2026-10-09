# Keep JNI entry points for the PTY bridge.
-keepclasseswithmembernames class com.chimeraant.terminal.core.** {
    native <methods>;
}
-keep class com.chimeraant.terminal.core.PtyProcess { *; }
