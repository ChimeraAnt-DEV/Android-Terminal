# AGENTS.md — repository notes for automated contributors

## What this project is
`Chimera Terminal` is a sandboxed Android terminal emulator. It provides real
pseudo-terminals (native `forkpty`), a VT/xterm emulator, multiple isolated
sandboxes, a choice of keyboards, and a root story that works on both rooted and
unrooted devices (including 2021 Amazon Fire tablets).

## Build
The build environment is not preinstalled; provision it with the Android SDK
command-line tools, then:

```bash
source ~/.android-env.sh            # ANDROID_HOME/JAVA_HOME convenience
./gradlew :app:assembleArm64Release   # 64-bit APK (arm64-v8a + x86_64)
./gradlew :app:assembleArm32Release   # 32-bit APK (armeabi-v7a)
```

The `abi` flavor dimension (`arm64`, `arm32`) is how the two APKs are produced;
keep `defaultConfig` free of `abiFilters` and set filters on the flavors only.
CI (`.github/workflows/build.yml`) builds both and publishes them as raw `.apk`
Release assets.

Toolchain versions are pinned in `app/build.gradle.kts`:
* AGP 8.5.2, Gradle 8.10.2, JDK 17+ (JDK 21 verified)
* compileSdk/targetSdk 34, minSdk 26
* NDK `27.0.12077973`, CMake `3.22.1`
* ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`

`local.properties` holds `sdk.dir` and is git-ignored. Release signing reads
`keystore.properties` (git-ignored); without it, release builds are unsigned.

## Source map
```
app/src/main/cpp/pty_bridge.cpp     native forkpty + read/write/winsize/signals
app/src/main/java/.../core/         PtyProcess (JNI) and TerminalEmulator (VT parser)
app/src/main/java/.../view/         TerminalView (Canvas), TabStrip, TerminalFonts,
                                    TerminalInputConnection, ChimeraKeyboard (IME)
app/src/main/java/.../session/      TerminalSession, SessionManager, EnvironmentBuilder, TerminalService
app/src/main/java/.../root/         RootManager (su detection), ProotInstaller (userspace root)
app/src/main/java/.../termux/       TermuxBootstrapInstaller (official Termux userland)
app/src/main/java/.../debloat/      DebloatEngine, DebloatSafety, DebloatHistory,
                                    CommandRunner + Root/Local/Shizuku runners, ShizukuBridge
app/src/main/java/.../ui/           MainActivity, SettingsActivity, RootActivity,
                                    DebloatActivity, TermuxActivity
app/src/main/assets/fonts/          JetBrains Mono (OFL) faces used by the terminal
```

## Entry points
The launcher activity is `LauncherActivity`, which shows the frosty splash and
then routes to `OnboardingActivity` on first run or `MainActivity` afterwards.
Do not put the launcher intent filter back on `MainActivity`.

## Hard constraints
* The terminal font is bundled in `assets/fonts`. Keep the OFL licence file
  alongside it if the font is ever changed.
* `DebloatSafety` is a safety boundary. Never add a package to the allow path
  that would stop a device booting. Protected packages must stay blocked.
* Privilege backends must report real capability. Do not make an unavailable
  backend appear to succeed; `isAvailable()` and `unavailableReason()` exist so
  the UI can tell the truth.
* Termux binaries hardcode `/data/data/com.termux/files/usr`. Any change to the
  Termux launch path must keep the proot bind of that prefix intact.
* Changing kernel driver settings on a locked device is not implementable from
  an app. Do not add fake controls for it.
* The terminal grid must never be resized to a tiny value. `computeCols` and
  `computeRows` return -1 until the view is measured, and `updatePtySize`
  ignores that. Resizing to 2 columns wraps every character onto its own line
  and was the cause of a launch-time garbling bug.
* Session output must repaint through `TerminalView.requestRender`, which
  coalesces redraws. Repainting per write makes typing feel slow.
* `TerminalView.onCreateInputConnection` must return a real text input type.
  `TYPE_NULL` makes most keyboards refuse to appear.

## Conventions
* Java 17, four-space indent, 100-column soft limit.
* Keep native code C++17, no exceptions/RTTI (`-fno-exceptions -fno-rtti`).
* All JNI methods live on `PtyProcess` under package `com.chimeraant.terminal.core`.
* Never rename the native method names without updating `pty_bridge.cpp`.
* Terminal state is owned by `TerminalEmulator`; views must not mutate it.
* Sessions never share file descriptors. Isolation is a hard requirement.

## Testing notes
There is no unit-test harness yet. Verify changes by building both APKs and,
when an emulator is available, installing with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
