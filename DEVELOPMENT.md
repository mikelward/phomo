# Development

Phomo is a Kotlin Android SIP client. The repository contains a single Android
application module, `:app`, built with the Gradle wrapper.

## Prerequisites

- Android Studio, with the Android SDK and Android SDK Platform 36 installed.
- JDK 17 or newer. The remote build environments use JDK 21.
- Android Gradle Plugin 9.2.0 and Gradle 9.4.1, both resolved through the
  checked-in Gradle wrapper and version catalog.
- For command-line work, set `ANDROID_HOME` to your Android SDK path and make
  sure `adb` is on `PATH`.

The app targets SDK 36, compiles against Android SDK Platform 36.1, and supports
Android 14/API 34 and newer (the product is designed and tested against Android
16+).

## Build and run

From the repository root:

```sh
./gradlew assembleDebug     # build the debug APK
./gradlew installDebug      # install on the connected device/emulator
./gradlew test              # JVM unit tests (no device needed)
./gradlew lint              # Android lint
./gradlew clean             # clean build outputs
```

If multiple devices are attached, set `ANDROID_SERIAL=<device-id>` first;
`adb devices` lists them.

### A note on device testing

Phomo places real SIP calls, so the parts that matter most cannot be exercised
on an emulator or in a cloud sandbox — there is no cellular radio, no
microphone peer, and no SIP server. Verifying signaling, media, audio routing,
the Telecom integration, and the registration lifecycle requires:

1. A physical Android device (a recent Pixel or Samsung on Android 16+).
2. A real SIP trunk. The reference setup is a Twilio Elastic SIP Trunk; you
   supply the domain/registrar, username, password, and outbound termination
   URI in the app's account setup.
3. Granting Phomo the microphone permission and, for automatic diversion, the
   call-redirection role when the app prompts.

Unit tests and screenshot tests cover the logic and UI that *can* be verified
without a device; everything call-related is owed a real-device test.

## Testing

### Local JVM unit tests

Unit tests live under `app/src/test`. They run on the development machine and do
not require an emulator:

```sh
./gradlew test
./gradlew :app:testDebugUnitTest   # app module only
```

Use these for the pure logic that carries the app's correctness — number
classification (domestic vs. international), the routing decision, and the
registration state machine.

### Screenshot tests

UI is covered by Roborazzi screenshot tests under Robolectric, recorded in CI.
Record locally with:

```sh
./gradlew :app:testDebugUnitTest --tests "dev.phomo.HomeScreenshotTest" -Proborazzi.test.record=true
```

Snapshots live under `app/src/test/snapshots/images/`. After adding a new
`*ScreenshotTest` class, wire it into `.github/workflows/android-ci.yml` with
its own record step (see `AGENTS.md` "Testing expectations") — a class not on
that allow-list never records in CI.

### Instrumented tests

Instrumented tests live under `app/src/androidTest` and run on a device/emulator
(`./gradlew connectedDebugAndroidTest`). KVM is unavailable in the cloud
environments, so run these on a local KVM-capable host or a physical device.

### Lint

```sh
./gradlew lint
```

The debug lint HTML report is written to
`app/build/reports/lint-results-debug.html`.

## Remote build environments

See `AGENTS.md` → "Remote build environments" for the JDK / Android SDK layout
on Cursor Cloud and Claude Code on the web, and for the `SessionStart` hook that
provisions the SDK in web sessions.

## Versioning

`versionCode` and `versionName` are derived from git at configure time:

- `versionCode` = `git rev-list --count HEAD`.
- `versionName` = `"1.0.<commitCount>+<shortSha>"`, e.g. `1.0.20+5e6eb54`.

CI checks the repo out with full history (`fetch-depth: 0`); a shallow clone
collapses `versionCode` to `1`. To bump the base name, edit `baseVersionName` in
`app/build.gradle.kts`.

## CI and distribution

`.github/workflows/android-ci.yml` runs three jobs:

- **build** — `assembleDebug`, `test`, `lint`; posts failing test details as a
  PR comment.
- **screenshot-tests** — records Roborazzi snapshots, commits any drift back to
  the PR branch, and posts a visual-diff PR comment.
- **deploy** (main only) — distributes a debug build via Firebase App
  Distribution and uploads a signed AAB to the Play Store internal track.

All distribution steps are secret-gated and skip cleanly without secrets, so
forks and the sandbox build green. See `docs/` for the Firebase and Play Store
setup.

## Common Gradle commands

| Task | Command |
| --- | --- |
| List Gradle tasks | `./gradlew tasks` |
| Build debug APK | `./gradlew assembleDebug` |
| Install debug APK | `./gradlew installDebug` |
| Run local unit tests | `./gradlew test` |
| Run app unit tests | `./gradlew :app:testDebugUnitTest` |
| Record screenshots | `./gradlew :app:testDebugUnitTest --tests "<Class>" -Proborazzi.test.record=true` |
| Run instrumented tests | `./gradlew connectedDebugAndroidTest` |
| Run lint | `./gradlew lint` |
| Clean build outputs | `./gradlew clean` |
