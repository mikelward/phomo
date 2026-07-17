# Firebase Crashlytics + Performance Monitoring

Developer-distributed builds of Phomo can report crashes and performance traces
to Firebase. Both SDKs identify installs by an anonymous Firebase Installation
ID — there is no Google sign-in and no user-visible login. Devices without
Google Play Services skip telemetry silently. No advertising ID is collected
(the manifest strips the `AD_ID` permission and disables ad-id collection).

## What is captured

- **Crashlytics**: uncaught exceptions and ANRs (when the system reports them).
- **Performance Monitoring**: the SDK auto-instruments `app_start` and screen
  rendering. Phomo does not add custom traces yet; when call-path timing is
  worth measuring (e.g. time-to-first-audio), custom traces will be added and
  documented here.

Telemetry never captures the contents of a call or the SIP credentials.

## Build wiring

Firebase is gated on the presence of `app/google-services.json`:

- **File present** → `app/build.gradle.kts` applies the
  `com.google.gms.google-services` and `com.google.firebase.crashlytics`
  plugins, the SDKs auto-initialize via the manifest-merged
  `FirebaseInitProvider`, and telemetry flows.
- **File absent** → the plugins are skipped, the SDKs find no `FirebaseApp` at
  runtime and stay inert. Forks, the sandbox, and Robolectric tests build
  cleanly.

The `firebase-bom`, `firebase-crashlytics`, and `firebase-perf` dependencies are
always pulled so the app compiles either way; only the gradle plugins (which
inject the project config and upload symbols) are conditional. If
`google-services.json` names no release client (`dev.phomo`), the release
Google-services and Crashlytics-mapping-upload tasks are disabled so
`bundleRelease` still succeeds — see the guard in `app/build.gradle.kts`.

`app/google-services.json` is gitignored. The Firebase project ID it carries is
not strictly secret — Firebase apps are protected by the Android signing key,
not the contents of this file — but the maintainer's Firebase project is
private, and gating via existence keeps the conditional build behavior easy to
reason about.

## Populating it in CI

CI materializes the file from a GitHub Actions secret named
`GOOGLE_SERVICES_JSON`. Set the secret's value to the **raw JSON** downloaded
from the Firebase console (Project settings → General → Your apps →
`google-services.json`). The `Materialize google-services.json` step in
`.github/workflows/android-ci.yml` writes it to `app/google-services.json`
before the build. The step is gated on the secret being non-empty, so fork PRs
(which can't see secrets) still pass — they just produce a build without
telemetry.

## Local development

To enable telemetry locally, drop the same `google-services.json` into `app/`.
Day-to-day work does not need it; the build skips the Firebase plugins and the
app runs identically minus the trace/crash reports.

## Crashlytics symbol upload

R8 runs in shrink-only, `-dontobfuscate` mode (see `proguard-rules.pro`), so
stack traces stay readable without a mapping file. If obfuscation is later
enabled, the `firebase-crashlytics` gradle plugin will upload mapping files
automatically as part of `assembleRelease`.
