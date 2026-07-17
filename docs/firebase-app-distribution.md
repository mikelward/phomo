# Firebase App Distribution

CI distributes internal builds to testers through Firebase App Distribution. The upload is wired into the `deploy` job in `.github/workflows/android-ci.yml` using `wzieba/Firebase-Distribution-Github-Action@v1`. There is no Gradle plugin involved.

## What gets uploaded

The action uploads the **debug** APK that `assembleDebug` already produced (`app/build/outputs/apk/debug/app-debug.apk`). Distributing the debug APK avoids configuring a release signing keystore at this stage; debug-signed builds remain installable for internal testers.

## Stable debug signing key

Android refuses to install an APK as an update of an existing one if the signing key has changed. By default, every CI runner generates a fresh `~/.android/debug.keystore`, so successive FAD builds would force testers to uninstall before re-installing. To keep the signature stable, CI materializes a debug keystore from a repository secret and points `app/build.gradle.kts`'s `signingConfigs.debug` at it via the `DEBUG_KEYSTORE_FILE` environment variable. The keystore is **not** checked in (this is a public repo), and local builds without the env vars set fall through to AGP's auto-generated debug keystore so day-to-day development keeps working.

To generate the keystore once:

```sh
KEYSTORE_PASSWORD=$(openssl rand -hex 24)
keytool -genkeypair \
  -keystore debug.keystore \
  -alias androiddebugkey \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEYSTORE_PASSWORD" \
  -dname "CN=Phomo Debug, O=Phomo, C=US" \
  -validity 36500 \
  -keyalg RSA \
  -keysize 2048
base64 -w0 debug.keystore > debug.keystore.b64
echo "Password: $KEYSTORE_PASSWORD"
```

Then save the base64 contents and password into the secrets below and **delete the local keystore file** (`*.keystore` is gitignored, but don't tempt fate). PKCS12 stores share a single password between the store and the key, so `DEBUG_KEYSTORE_PASSWORD` and `DEBUG_KEY_PASSWORD` should both be set to the same value.

## When the upload runs

The upload step is gated so it only runs when all of the following are true:

- The triggering event is a `push` to `refs/heads/main`. PRs and feature pushes don't ship to testers.
- `FIREBASE_APP_ID` is non-empty.
- `FIREBASE_SERVICE_ACCOUNT_JSON` is non-empty.

Forks and fresh clones without Firebase secrets configured still get a green CI run — the step is silently skipped.

## Required GitHub secrets

| Secret | Description |
| --- | --- |
| `FIREBASE_APP_ID` | Firebase App ID for the Android app, e.g. `1:1234567890:android:abcdef`. |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Full JSON content of a service account that has the `Firebase App Distribution Admin` role. |
| `DEBUG_KEYSTORE_BASE64` | Base64-encoded PKCS12 keystore bytes (`base64 -w0 debug.keystore`). |
| `DEBUG_KEYSTORE_PASSWORD` | Random hex string set when the keystore was generated. |
| `DEBUG_KEY_PASSWORD` | Same value as `DEBUG_KEYSTORE_PASSWORD` (PKCS12 convention). |
| `DEBUG_KEY_ALIAS` | Key alias inside the keystore. Use `androiddebugkey` to match AGP's default. |

## Tester group

The tester group is hard-coded as `testers` in the workflow. The group with this name must exist in the Firebase App Distribution console. To rename it, update both the console and the `groups:` value in `.github/workflows/android-ci.yml` together.

## Release notes

Release notes are built by the `deploy` job's "Build release notes" step from the release-worthy commit subjects in the push (oldest-first, `• `-bulleted, non-user-facing `ci:`/`docs:`/`internal:`/`refactor:`/`test:` prefixes and pure docs/dotfile commits skipped — see `AGENTS.md` "Commit messages"), followed by the run number and full SHA:

```
• <oldest release-worthy commit subject>
• <next commit subject>
---
Build: <run-number> · <sha>
```

The first ~60 characters of these notes appear in the tester device's push notification (for a multi-commit push that's the oldest qualifying subject), so keep commit subjects informative. If every commit in the push is prefixed or housekeeping, the distribution is skipped entirely.

## Manual upload from a workstation

Install the [Firebase CLI](https://firebase.google.com/docs/cli) and run:

```sh
./gradlew assembleDebug
firebase appdistribution:distribute app/build/outputs/apk/debug/app-debug.apk \
  --app "$FIREBASE_APP_ID" \
  --groups testers \
  --release-notes "Local upload from $(git rev-parse --short HEAD)"
```
