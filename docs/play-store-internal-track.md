# Play Store internal testing track

CI uploads a signed release AAB to the Google Play Store **internal** testing track on every push to `main`. The upload is wired into the `deploy` job in `.github/workflows/android-ci.yml` using [`r0adkll/upload-google-play@v1`](https://github.com/r0adkll/upload-google-play). It runs in addition to (not instead of) the Firebase App Distribution step — Firebase remains the faster, no-Play-review channel; the internal track is the route to alpha/beta/production once we want to go there.

## What gets uploaded

`./gradlew bundleRelease` produces `app/build/outputs/bundle/release/app-release.aab` and the action uploads that file to the `internal` track on the `dev.phomo` listing. Play App Signing re-signs the AAB with its managed app-signing key before delivery to devices, so the upload key generated below is only used to authenticate to Play, not to sign what testers actually run.

## When the upload runs

The upload steps are gated so they only run when **all** of the following are true:

- The triggering event is a `push` to `refs/heads/main` (PRs and feature pushes don't ship).
- `RELEASE_KEYSTORE_BASE64` is non-empty (so the upload key materialises).
- `PLAY_SERVICE_ACCOUNT_JSON` is non-empty (so we can authenticate to Play).

Forks and fresh clones without these secrets configured still get a green CI run — the AAB build and upload steps are silently skipped. The release `signingConfig` in `app/build.gradle.kts` is also only attached when `RELEASE_KEYSTORE_FILE` is set, so a local `./gradlew bundleRelease` without the env vars produces an unsigned AAB rather than a build failure.

## One-time Play Console setup

You only need to do these steps once per Play Console account / app.

### 1. Create the app on Play Console

Go to https://play.google.com/console → "Create app" and use:

- **App name**: `Phomo`
- **Default language**: English (United States)
- **App or game**: App
- **Free or paid**: Free
- **Package name**: `dev.phomo` (must match `applicationId` in `app/build.gradle.kts`)

Complete the required declarations under "App content" (privacy policy, target audience, etc.) — Play won't let you ship to the internal track without them.

### 2. Seed the internal track with a manual upload

The first AAB for any new app must be uploaded through the Play Console UI; the API can only create subsequent releases. There are two ways to get a signed AAB:

**Option A — let CI build it (recommended).** Add the four release-keystore secrets from the table below (everything except `PLAY_SERVICE_ACCOUNT_JSON`) and push to main. The workflow will build a signed AAB and skip the upload step (because the service-account secret isn't set yet), but the `Build release AAB` step is decoupled from the Play upload — it always uploads the result as a workflow artifact called `app-release-aab` you can download from the Actions UI. Grab that AAB and upload it through Play Console.

**Option B — build locally:**

```sh
RELEASE_KEYSTORE_FILE=/path/to/release.keystore \
RELEASE_KEYSTORE_PASSWORD=<password> \
RELEASE_KEY_PASSWORD=<password> \
RELEASE_KEY_ALIAS=phomo \
./gradlew bundleRelease
```

Either way, upload `app-release.aab` via Play Console → Internal testing → Create new release. Accept Play App Signing when prompted (this is the managed key Play uses to re-sign all subsequent uploads).

Once the seed upload is done, finish the rest of this doc to add `PLAY_SERVICE_ACCOUNT_JSON` and let the next push to main upload automatically.

### 3. Add internal testers

Play Console → Internal testing → Testers tab → "Create email list". Add the tester email addresses, save the list, and copy the opt-in URL. Send that URL to testers; they need to follow it once before the app appears in their Play Store.

### 4. Enable the Google Play Android Developer API

Go to https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com and enable the API on a Google Cloud project of your choice. Play Console will link itself to this project automatically the first time a service account from it is granted access.

### 5. Create the service account

In the **same** Cloud project:

1. https://console.cloud.google.com/iam-admin/serviceaccounts → "Create service account".
   - **Name**: `play-publisher` (anything works — this is just for your own bookkeeping).
   - No roles needed at the Cloud project level. Click "Done".
2. Click into the new service account → "Keys" tab → "Add key" → "Create new key" → JSON. Save the downloaded JSON; this becomes the `PLAY_SERVICE_ACCOUNT_JSON` secret.

### 6. Grant the service account access in Play Console

Play Console → Users and permissions → Invite new users → paste the service account email (`play-publisher@<project>.iam.gserviceaccount.com`). On the "App permissions" tab, add Phomo and grant:

- **Releases: Release to testing tracks**

That's the minimum scope for an internal-track upload. Don't grant production-track release without a separate decision. Save and confirm the invite.

It can take a few minutes for the permission to propagate before the API will accept uploads from the service account.

## Generating the upload keystore

Keep this keystore safe — losing it means using Play Console's key-reset flow before you can ship a new upload to the same listing. The keystore is **not** the app-signing key (Play manages that one); it is only the credential the upload action uses to prove the AAB came from us.

```sh
KEYSTORE_PASSWORD=$(openssl rand -hex 24)
keytool -genkeypair \
  -keystore release.keystore \
  -alias phomo \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEYSTORE_PASSWORD" \
  -dname "CN=Phomo Release, O=Phomo, C=US" \
  -validity 36500 \
  -keyalg RSA \
  -keysize 2048
base64 -w0 release.keystore > release.keystore.b64
echo "Password: $KEYSTORE_PASSWORD"
```

Save the base64 contents and the password into the secrets below, then **delete the local keystore file** (`*.keystore` is gitignored, but don't tempt fate). PKCS12 stores share a single password between the store and the key, so `RELEASE_KEYSTORE_PASSWORD` and `RELEASE_KEY_PASSWORD` should both be set to the same value.

If this is the very first AAB for the listing (step 2 above), keep the local keystore file around long enough to do the seed upload — only delete it after the upload key is enrolled in Play App Signing.

## Required GitHub secrets

| Secret | Description |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded PKCS12 keystore bytes (`base64 -w0 release.keystore`). |
| `RELEASE_KEYSTORE_PASSWORD` | Random hex string set when the keystore was generated. |
| `RELEASE_KEY_PASSWORD` | Same value as `RELEASE_KEYSTORE_PASSWORD` (PKCS12 convention). |
| `RELEASE_KEY_ALIAS` | Key alias inside the keystore. Use `phomo` to match the snippet above. |
| `PLAY_SERVICE_ACCOUNT_JSON` | Full JSON contents of the service account key downloaded in step 5. |

Add them at https://github.com/mikelward/phomo/settings/secrets/actions.

## Release notes

The workflow builds a `whatsnew-en-US` file from the release-worthy commit subjects in the push — oldest-first, `• `-bulleted, non-user-facing prefixes (`ci:`/`docs:`/`internal:`/`refactor:`/`test:`) and pure docs/dotfile commits skipped (see `AGENTS.md` "Commit messages") — capped at Play's 500-character per-locale limit (trailing subjects are dropped with a `…` marker if the push overflows):

```
• <oldest release-worthy commit subject>
• <next commit subject>
```

Internal-track release notes show up in Play Console under the release; testers don't see a push notification the way Firebase delivers one, so the audience is mostly future-you reading the release history.

## versionCode

Play rejects an AAB whose `versionCode` is `<=` the highest one already on any track for the listing. We derive `versionCode` from `git rev-list --count HEAD` (see `app/build.gradle.kts`), which monotonically increases as long as `main` only moves forward — fine for our workflow. CI checks out with `fetch-depth: 0` so the count isn't truncated.

## Troubleshooting

- **`The Android App Bundle was not signed.`** — the `release` `signingConfig` didn't attach. Confirm `RELEASE_KEYSTORE_BASE64` is set and the materialise step ran. The build only attaches the signing config when `RELEASE_KEYSTORE_FILE` is non-empty.
- **`APK specifies a version code that has already been used.`** — usually means CI ran on a shallow clone and `git rev-list --count HEAD` came up short. The workflow uses `fetch-depth: 0`; check that the checkout step ran with it.
- **`The caller does not have permission`** — service account doesn't have "Release to testing tracks" on the app yet, or the invite hasn't propagated. Re-check Play Console → Users and permissions.
- **`Package not found: dev.phomo`** — the listing doesn't exist yet on Play Console, or the very first AAB hasn't been uploaded manually. Do step 2 above.

## Manual upload from a workstation

For one-off pushes (e.g. testing the upload key without a CI round-trip), `bundletool` + `gcloud auth` works, but the simplest path is to install the [Fastlane supply](https://docs.fastlane.tools/actions/upload_to_play_store/) tool or just re-run the workflow via "Run workflow" on the Actions tab once secrets are in place.
