<p align="center">
  <img src="docs/icon.svg" alt="Chimera Terminal icon: a frosted prompt with icicles" width="128" height="128">
</p>

<h1 align="center">Chimera Terminal</h1>

<p align="center">
  <a href="../../releases/latest"><img src="https://img.shields.io/github/v/release/ChimeraAnt-DEV/Android-Terminal?label=release&color=7FD4FF" alt="Latest release"></a>
  <a href="../../releases"><img src="https://img.shields.io/github/downloads/ChimeraAnt-DEV/Android-Terminal/total?label=downloads&color=7FD4FF" alt="Total downloads"></a>
  <a href="../../commits/main"><img src="https://img.shields.io/github/commit-activity/t/ChimeraAnt-DEV/Android-Terminal?label=commits&color=7FD4FF" alt="Total commits"></a>
  <a href="../../commits/main"><img src="https://img.shields.io/github/last-commit/ChimeraAnt-DEV/Android-Terminal?label=last%20commit&color=7FD4FF" alt="Last commit"></a>
  <a href="../../stargazers"><img src="https://img.shields.io/github/stars/ChimeraAnt-DEV/Android-Terminal?label=stars&color=7FD4FF" alt="Stars"></a>
  <a href="../../actions/workflows/build.yml"><img src="https://img.shields.io/badge/build-passing-6BE3A0" alt="Build status"></a>
  <img src="https://img.shields.io/badge/android-8.0%2B-7FD4FF" alt="Android 8.0 or newer">
  <img src="https://img.shields.io/badge/licence-personal%20use-8FA3B8" alt="Licence">
</p>

A terminal for Android that you can actually use without a manual. It runs a
real shell, keeps each job in its own sandbox, tells you what a command does
before you run it, and can remove preinstalled apps without a computer.

## Get it

Download the build that matches your device from the
[latest release](../../releases/latest):

| File | Use this for |
| --- | --- |
| `ChimeraTerminal-*-arm64-v8a.apk` | Most phones and tablets made since about 2017, and emulators |
| `ChimeraTerminal-*-armeabi-v7a.apk` | Older 32-bit devices |

If you are unsure, install the arm64 build first. Android will refuse it on a
32-bit device and tell you, and you can then install the other one.

## What you get

A real terminal. Every session is a separate process with its own
pseudo-terminal, its own home folder and its own working directory. A long job
in one tab cannot slow down another, and closing a tab does not disturb the
rest.

Any keyboard you like. The app never takes over your keyboard. Keep Gboard or
SwiftType, or use a physical keyboard over USB or Bluetooth. If you want
dedicated keys for a shell, turn on the built-in Chimera keyboard from the
drawer.

Help while you type. Start typing and a list appears above the keyboard showing
commands that begin with those letters. Tap one to insert it. Tap the info
button beside it and a small helper in the corner explains, in plain words,
what that command does.

The full Termux package set. Install the Termux userland from the drawer and
`pkg`, `apt`, `bash`, `git`, `python`, `ssh` and thousands of other packages
work inside a sandbox.

Remove preinstalled apps without a PC. The Debloater lists what is installed,
marks each app as safe, caution or protected, and can disable or remove apps
you do not want. Protected system apps are blocked, and you can restore
anything you change.

A ice-cold look. A frosted loading animation, a kitty-style tab strip, and an
icy blue theme with a bundled JetBrains Mono font.

## First run

The app shows a short guide the first time you open it. It covers what a
terminal is, what to type, and where to find the extra features. You can skip
it and read it later from the drawer.

Your first command:

```bash
help
```

That lists every command the sandbox can run. Nothing you type in a normal
sandbox can harm your device.

## Add real Linux packages

1. Open the drawer and choose Termux userland.
2. Tap Install Termux userland. The app downloads the official Termux
   bootstrap, about 23 MB.
3. Tap Open a Termux sandbox.
4. Run `pkg update`, then install what you want, for example
   `pkg install git python openssh`.

Termux programs are built for Android, so they run directly on the device. They
expect to live at `/data/data/com.termux/files/usr`, a path that belongs to the
Termux app. Chimera Terminal runs them through proot and maps the sandbox
folder onto that path, which is why `pkg` and `apt` work.

## Remove preinstalled apps

Open the drawer and choose Debloater. The app tells you which access it has,
and what that lets it do.

| What you have | What the app can do |
| --- | --- |
| Root | Disable, remove and restore any app |
| Shizuku | The same, with no root and no computer on Android 11 or newer |
| Neither | List apps, and open the system screen where you can act by hand |

Every removal uses the same rule as `adb shell pm uninstall --user 0`. The
system files stay untouched, the device still starts, updates still work, and
everything can be put back with Restore.

Apps that would break the device are marked protected and cannot be removed.
That list covers the system interface, settings, the launcher, the package
installer, and the Fire OS launcher and updater.

### Getting Shizuku running without a computer

Shizuku needs an ADB-identity process. On Android 11 or newer you can start it
on the device itself:

1. Install the Shizuku app.
2. Turn on Developer options, then Wireless debugging.
3. Open Shizuku and start it. Choose the wireless debugging pairing option.
4. Come back to Chimera Terminal, open Debloater and tap Grant Shizuku
   permission.

## Build it yourself

You need JDK 17 or newer, Android SDK platform 34, build-tools 34.0.0, NDK
`27.0.12077973` and CMake `3.22.1`.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew :app:assembleArm64Release    # 64-bit
./gradlew :app:assembleArm32Release    # 32-bit
./gradlew :app:testArm64ReleaseUnitTest
```

The APKs land in `app/build/outputs/apk/arm64/release/` and
`app/build/outputs/apk/arm32/release/`. Two separate APKs are built on purpose:
each carries only its own native libraries.

Every push to `main` runs the workflow in `.github/workflows/build.yml`, which
builds both APKs, runs the tests and publishes a release.

## What this app cannot do

Some things are impossible from an app on a locked device. It is better to say
so than to ship a button that does nothing.

Change kernel driver settings. Drivers run inside the kernel. Selinux and the
Android permission model block ordinary apps from writing to them, and no API
exists for it. With real root you can write to `/sys` yourself, and the
terminal allows that. On a locked, unrooted device, no app can do it.

Unlock a locked bootloader. That is a firmware operation, not something an app
can perform.

Remove an app from the system partition. `--user 0` removal hides the app for
your user. The original file stays in the system image until a factory reset.
Real removal needs root.

## Project layout

```
app/src/main/cpp/                 native pseudo-terminal bridge
app/src/main/java/.../core/       terminal emulator and PTY wrapper
app/src/main/java/.../view/       renderer, tabs, keyboard, bot helper
app/src/main/java/.../session/    sandboxes and the environment builder
app/src/main/java/.../termux/     Termux userland installer
app/src/main/java/.../debloat/    debloating engine and safety rules
app/src/main/java/.../suggest/    command list and input tracking
app/src/main/java/.../ui/         screens
app/src/main/assets/fonts/        JetBrains Mono, under the OFL licence
```

## Tests

25 unit tests cover the emulator, the debloating safety rules, the Termux path
contract and the typing mirror. Run them with:

```bash
./gradlew :app:testArm64ReleaseUnitTest
```

## Credits

JetBrains Mono is included under the SIL Open Font Licence. See
`app/src/main/assets/licenses/JetBrainsMono-OFL.txt`.

Termux packages are downloaded from the official
[termux-packages](https://github.com/termux/termux-packages) releases.

Shizuku is by Rikka, at [shizuku.rikka.app](https://shizuku.rikka.app/).

## Licence

Provided as-is for personal use.
