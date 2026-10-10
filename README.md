# Chimera Terminal

A modern, sandboxed terminal emulator for Android. It does what Termux does —
real PTYs, a real shell, real escape sequences — and adds the things Termux
does not do by default:

* **Your keyboard, your choice.** The app never hijacks the IME. Keep Gboard,
  SwiftKey, a physical keyboard over USB/Bluetooth, or enable the bundled
  *Chimera Terminal Keyboard* when you want dedicated shell keys (Ctrl, Alt,
  Esc, Tab, arrows, function keys). Switching is one tap away.
* **Isolated sandboxes.** Every session is a separate process on its own
  pseudo-terminal with its own `HOME`, `PREFIX` and `TMPDIR`. Nothing is
  shared, so a heavy job in one sandbox cannot slow down or corrupt another.
* **A root toggle that tells the truth.** On a rooted device it uses your real
  `su` (Magisk / KernelSU / engineering ROM). On a device without root —
  including a **2021 Amazon Fire tablet (11th generation)** — it installs a
  `proot` userspace Linux rootfs so you still get a root shell and a package
  manager, without pretending the kernel handed you uid 0.
* **A kitty-style interface.** A horizontal tab strip for sandboxes, a compact
  status line, and an icy dark theme. Rendering is Canvas-based and stays
  smooth on low-end tablets.
* **Real Termux commands.** Install the official Termux userland and run
  `pkg`, `apt`, `bash`, `git`, `python`, `ssh` and everything else from the
  Termux repository inside a sandbox.
* **Debloating without a PC.** With Shizuku (ADB-level, startable on-device on
  Android 11+) or root, disable or remove preinstalled apps exactly like
  `adb shell pm uninstall --user 0`, with a safety policy that blocks anything
  that would stop the device booting, and a one-tap restore for everything you
  changed.

---

## What is implemented

### Terminal engine
| Area | Status |
| --- | --- |
| PTY via `forkpty(3)` in native C++ | yes — `app/src/main/cpp/pty_bridge.cpp` |
| VT100/xterm escape parser | yes — `TerminalEmulator` |
| 256-colour and 24-bit true colour (`38;2;r;g;b`) | yes |
| SGR: bold, dim, italic, underline, blink, reverse, hidden, strike | yes |
| Cursor movement, erase, insert/delete line and char | yes |
| Scrolling region (`DECSTBM`) | yes |
| Alternate screen (`?1049`, `?47`, `?1047`) | yes |
| Scrollback buffer (5000 lines) | yes |
| Bracketed paste (`?2004`) | yes |
| OSC 0/1/2 window title | yes |
| UTF-8 reassembly and East-Asian wide characters | yes |
| `DECCKM` application cursor keys, cursor shape `DECSCUSR` | yes |
| Window resize propagates `SIGWINCH` to the child | yes |

### Input
* Hardware, USB and Bluetooth keyboards via `onKeyDown` and `InputConnection`.
* On-screen **extra keys row** (Esc, Ctrl, Alt, Tab, arrows) always available.
* Optional **Chimera Terminal Keyboard** IME with a full qwerty layout, a
  symbol page, and latched Ctrl/Alt/Shift.
* Gestures: drag to scroll back, long-press to select, tap-away to dismiss,
  menu to copy or paste (bracketed-paste aware).

### Sandboxes
* Create, switch and close sandboxes from the drawer.
* Each sandbox has its own filesystem root under `files/sandboxes/<id>/`
  (`home`, `usr`, `tmp`, `workspace`).
* `motd`, `.mkshrc` and `.profile` are generated per sandbox.
* A foreground service keeps sessions alive while the app is in the background.

### Root
* `RootManager` probes Magisk, KernelSU, `/system/bin/su`, `/sbin/su` and the
  usual engineering paths, then verifies with `id`.
* `ProotInstaller` downloads a static `proot` and an Alpine minirootfs and
  extracts them with a pure-Java tar/gzip extractor (no `tar` binary is needed
  on the device). The shell then runs as `proot -0`, so the sandbox is uid 0.
* Amazon Fire devices are detected explicitly and routed to the userspace path,
  the only one that can work without unlocking the bootloader.

---

## Building

Requirements: JDK 17+ (JDK 21 works), Android SDK platform 34, build-tools
34.0.0, NDK `27.0.12077973`, CMake `3.22.1`.

```bash
# one-time: point Gradle at your SDK (git-ignored; CI uses ANDROID_HOME)
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 64-bit APK (arm64-v8a, plus x86_64 so it also runs on emulators)
./gradlew :app:assembleArm64Release
# 32-bit APK (armeabi-v7a)
./gradlew :app:assembleArm32Release
```

Outputs:

```
app/build/outputs/apk/arm64/release/app-arm64-release.apk   # 64-bit
app/build/outputs/apk/arm32/release/app-arm32-release.apk   # 32-bit
```

Two separate APKs are produced on purpose: each one bundles only the native
libraries for its own architecture, so neither carries dead code for the other.

### CI (GitHub Actions)

`.github/workflows/build.yml` builds both APKs on every push to `main`, on any
`v*` tag, on pull requests and on manual dispatch. It then:

* uploads them as a workflow artifact, and
* attaches them to a **GitHub Release as raw `.apk` files** — `ChimeraTerminal-<version>-arm64-v8a.apk`
  and `ChimeraTerminal-<version>-armeabi-v7a.apk`. Release assets download as
  plain APKs; GitHub's own workflow artifacts are always repackaged as `.zip`,
  so the Release is the place to grab an installable file.

On an untagged push the version is derived from the commit as
`v1.0.0-<short-sha>`. CI signs with a throwaway key unless you add these
repository secrets, which keep update signatures stable:

| Secret | Purpose |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

### Optional release signing
Create `keystore.properties` in the repository root (git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Installing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Using root

1. Open the drawer, then **Root**.
2. Tap **Check device root**. If `su` is present you will be asked to grant it.
3. On an unrooted device tap **Install / repair userspace root**. This fetches
   `proot` and Alpine into the app's private storage.
4. Turn on **Allow root in new sandboxes**, then create a sandbox and enable
   *Start as root* or *Use proot Linux rootfs*.

## Project layout

```
app/src/main/cpp/             native PTY bridge (forkpty, read/write, SIGWINCH)
app/src/main/java/.../core/   PTY wrapper + escape-sequence emulator
app/src/main/java/.../view/   Canvas renderer, input connection, keyboard IME
app/src/main/java/.../session/ sandbox manager, environment builder, service
app/src/main/java/.../root/   device-root detection + proot installer
app/src/main/java/.../ui/     activities (main, settings, root)
```

## Termux userland

Termux binaries are Android-native, but they hardcode their prefix as
`/data/data/com.termux/files/usr`. That path only exists for the Termux
package, so Chimera Terminal installs the bootstrap into its own storage and
launches it through `proot`, binding the real prefix onto the path the binaries
expect. The result is that `pkg` and `apt` genuinely work and install real
Termux packages.

Drawer → **Termux userland** → *Install Termux userland*, then *Open a Termux
sandbox*.

## Debloater

Drawer → **Debloater**.

| Backend | What it can do |
| --- | --- |
| Root (`su`) | Disable / uninstall / restore for any package |
| Shizuku (ADB-level, uid 2000) | Same, no root and no PC on Android 11+ |
| App only (no elevation) | Listing only |

Every action goes through `pm uninstall -k --user 0` semantics: the system
partition is untouched, the device still boots, OTAs still work, and anything
removed can be brought back with **Restore**.

A safety policy classifies every package as Safe, Caution or Protected.
Protected entries (system UI, settings, launcher, package installer, Fire OS
OTA and webview, and so on) are blocked outright, because removing them is how
people brick devices.

## What is not possible

Some things cannot be done from an app on a locked device, and this project
does not pretend otherwise:

* **Changing kernel driver settings without root.** Drivers live in kernel
  space; SELinux and the Android permission model block unprivileged access.
  There is no API for it. With real root you can write to `/sys`, and the
  terminal supports that path, but on a locked unrooted device it is not
  achievable by any app.
* **Unlocking a locked bootloader from userspace.** That is a firmware-level
  operation.
* **True system-partition removal without root.** `--user 0` uninstall removes
  the app for the user, which is what the ADB workflow does; the APK remains in
  the system image until a factory reset.

## Security notes

* Sandboxes are app-private directories and are not shared with other apps.
* The root toggle is opt-in per sandbox and is never enabled silently.
* `proot` grants a *fake* uid 0 inside a chroot. It does not and cannot give
  real kernel privileges; that requires a genuinely rooted device.
* Backups deliberately exclude sandbox data and the proot rootfs.

## Licence

Provided as-is for personal use.
