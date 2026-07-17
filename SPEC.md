# Phomo Design Spec

Phomo is a minimalist Android SIP client for placing outbound calls cheaply
over a SIP trunk (the reference provider is Twilio Elastic SIP Trunking), while
letting the native dialer and SIM keep handling everything else. It targets
Android 16+ (`minSdk 34`, `targetSdk 36`) and is built to feel like part of the
platform, not a separate calling app you have to remember to open.

> **Status.** This spec describes the intended v1 product and architecture. The
> repository currently contains the project scaffolding and a placeholder home
> screen; the calling stack described below lands across the implementation
> milestones in `TODO.md`. Sections that describe not-yet-built behavior say so.

## Product shape

- **Outbound-only in v1.** Phomo places calls; it does not receive them. This is
  a deliberate scope cut that makes the app dramatically more battery-efficient
  (see "Registration lifecycle"): an inbound-capable SIP client must stay
  registered and reachable around the clock, holding a network keep-alive and
  waking for every re-REGISTER. An outbound-only client can stay completely
  dormant until the moment the user places a call. Inbound calling is a
  post-v1 consideration, not a v1 feature.
- **Cheap overseas calling is the job.** The motivating use case is calling
  international numbers at SIP-trunk rates instead of carrier international
  rates. Domestic calls have no cost advantage over the SIM and should stay on
  the SIM.
- **Stay out of the way.** The user should be able to keep dialing from the
  stock dialer, a `tel:` link, or a contact, and have international calls
  transparently go out over SIP without opening Phomo. The app's own UI is a
  thin surface for account setup, permissions/role granting, a manual dialer
  fallback, and call status — not a place the user is expected to live.
- **Bring-your-own trunk.** Phomo is a client, not a calling plan. The user
  supplies SIP credentials (domain/registrar, username, password, and the
  outbound proxy / termination URI) for their own provider. The reference
  configuration is a Twilio SIP trunk, but nothing is Twilio-specific in the
  protocol.
- **Caller ID (post-v1).** Presenting the user's existing mobile number as the
  outbound caller ID is desirable but explicitly out of scope for v1; it depends
  on trunk-side configuration (verified caller IDs / `From` handling) rather
  than client behavior.

## Devices and compatibility

- First-class targets: recent **Pixel** and **Samsung** devices on **Android
  16+**. These two OEMs differ in Telecom and background-execution behavior, so
  both are part of "done" for any call-path change.
- `minSdk 34` covers Android 14–15 devices as a courtesy but the product is
  designed and tested against 16+.
- The app installs on devices without a telephony radio (tablets), but its
  reason to exist is calling; non-phone hardware is not a support target.

## SIP + media stack

- **Phomo builds on the Linphone SDK (liblinphone).** Android's built-in SIP
  API (`android.net.sip` / `SipManager`) was deprecated in Android 12 and is
  unusable on 16+, so the app brings its own stack. Linphone was chosen because
  it is a mature, actively maintained, Maven-distributed native stack that
  handles the parts that are genuinely hard and unsafe to hand-roll: SIP
  signaling, NAT traversal (ICE/STUN/TURN), codec negotiation (Opus and the
  PCMU/PCMA a Twilio trunk expects), SRTP/TLS, DTMF, and echo cancellation.
  Owning that machinery ourselves would put call quality at risk for no product
  benefit.
- The alternatives considered and rejected for v1: **PJSIP/pjsua2** (comparable
  capability but no Maven distribution and hand-written JNI glue — more
  integration risk for the same result) and a **lightweight/custom stack**
  (JAIN-SIP/mjSIP signaling plus custom RTP) — smallest footprint but it makes
  us responsible for media quality, NAT traversal, and echo cancellation, which
  is exactly where a DIY SIP client sounds bad on a real mobile network.
- Linphone's own background service, contact integration, and always-on
  registration features are **not** adopted. Phomo drives the SDK in a
  deliberately minimal, on-demand mode (see "Registration lifecycle") so the
  battery posture is Phomo's, not the SDK's defaults'.
- Trade-offs accepted: Linphone adds native libraries (tens of MB across ABIs;
  App Bundle per-ABI splitting keeps the user's download smaller) and is
  GPLv3-licensed (or commercial). Those are acceptable for this app.

## Call routing — how a dialed number reaches Phomo

Phomo integrates with the Android **Telecom** framework rather than trying to
intercept dialing itself. Two complementary Telecom primitives divide the work:

1. **A self-managed calling account (`PhoneAccount` + `ConnectionService`).**
   Phomo registers a `PhoneAccount` so it appears to the system as a calling
   account that can carry a call, and a `ConnectionService` that actually
   places and manages the SIP call (audio, hold, mute, DTMF, disconnect) as a
   Telecom `Connection`. This is what lets a call "be a phone call" to the rest
   of the system — proper audio focus, in-call UI, Bluetooth/earpiece routing,
   and coexistence with a cellular call.
2. **Automatic diversion (`CallRedirectionService`).** To achieve the
   "stays out of the way" goal, Phomo registers a `CallRedirectionService`
   (the app requests the `ROLE_CALL_REDIRECTION` role). When the user dials from
   the stock dialer / a `tel:` link / a contact, the system hands the outgoing
   number to Phomo's redirection service, which decides:
   - **International number → route via Phomo's SIP calling account.** The call
     is redirected onto Phomo's `PhoneAccount`, so it goes out over the SIP
     trunk without the user opening the app.
   - **Domestic number → leave it alone.** The redirection service returns the
     call unmodified so it proceeds on the SIM exactly as it would without
     Phomo installed.

- **Manual selection still works.** Independently of redirection, because Phomo
  registers a calling account, the user can pick "Phomo" as the calling account
  in the system dialer (or set it as a per-call / default choice) to force a
  call over SIP. The two mechanisms are layered: redirection handles the common
  case automatically; the calling account is the explicit override.
- **The manual dialer inside Phomo** is a fallback for cases where the user
  wants to dial explicitly within the app (e.g. before the redirection role is
  granted, or to test). It places the call through the same `ConnectionService`.

### Domestic vs. international classification

- The classification that drives redirection is a pure function of the dialed
  number and the user's home country, and is unit-tested exhaustively — it is
  the single most important correctness surface in the app, because getting it
  wrong either sends a domestic call over SIP (unexpected, possibly failing) or,
  worse, swallows a call that should have gone to the SIM.
- The home country is determined from the device (SIM/network country;
  user-overridable in settings). A number is "international" when, normalized to
  E.164, its country code differs from the home country's; `+`-prefixed and
  IDD-prefixed (e.g. `00`) numbers are parsed accordingly. Emergency numbers,
  short codes, and non-dialable strings are **always** left to the SIM and never
  redirected.
- **Fail safe, always toward the SIM.** Any ambiguity, parse failure, missing
  configuration, unmet permission, or SIP setup error results in the call
  proceeding on the native path. Phomo never causes a call to fail that would
  have succeeded on the SIM.

## Registration lifecycle (battery model)

Battery efficiency is a first-class product constraint, and the registration
model is where it is won or lost.

- **Register on demand, tear down after the call.** Phomo does **not** maintain
  a persistent SIP registration, a background service, a periodic re-REGISTER
  alarm, or an idle network keep-alive. While no call is in progress, the app
  holds no wakelock and runs no background work. When a call is initiated, Phomo
  brings the SIP stack up, registers (or, where the trunk allows, sends the
  INVITE without a standing registration), places the call, and on hangup
  unregisters and shuts the stack down.
- This is only possible because v1 is outbound-only. It is the direct reason the
  app can be dormant in the user's pocket. Any future inbound support would
  require a fundamentally different, and more power-hungry, registration model,
  and that trade-off is a product decision, not an implementation detail.
- **During an active call**, a foreground service (type `phoneCall` /
  `microphone`) keeps the call alive and audible when Phomo isn't in the
  foreground — scoped strictly to the call's lifetime and stopped on teardown.
  This is the one sanctioned piece of "background" execution, and it exists only
  while a call is up.
- Any change that adds work outside a call's lifetime (a `WorkManager` job, a
  standing `ConnectivityManager` callback, a retained wakelock) must justify why
  it cannot be on-demand and must be called out explicitly in review.

## Permissions and roles

Phomo requests permissions and roles **contextually**, at the point of first
use, never in a wall of prompts at first launch. Each is tied to the feature
that needs it:

- `INTERNET`, `ACCESS_NETWORK_STATE` — SIP signaling and media; checking
  connectivity before attempting a call. (Install-time; already declared.)
- `RECORD_AUDIO` — call audio capture. Requested the first time the user places
  a call.
- `MANAGE_OWN_CALLS` — required to register a self-managed
  `ConnectionService` / place calls through Telecom.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` /
  `FOREGROUND_SERVICE_PHONE_CALL` — the in-call foreground service.
- `POST_NOTIFICATIONS` — the ongoing-call notification (Android 13+).
- **`ROLE_CALL_REDIRECTION`** (via `RoleManager`) — the automatic-diversion
  feature. Requested only when the user opts into automatic routing; declined or
  revoked, Phomo still works via the manual calling account, just without
  transparent diversion. The `CallRedirectionService` is bound by the system
  under `BIND_CALL_REDIRECTION_SERVICE`.

The permission/role surface is added to `AndroidManifest.xml` alongside the code
that uses it, so the app never ships holding a permission it doesn't yet
exercise. The manifest starts minimal (network only) and grows with the
implementation milestones.

## Persistence

- **SIP account credentials** (domain, username, password, outbound proxy) are
  the only sensitive data Phomo stores. They are written to a dedicated,
  backup-excluded store (`phomo_account.xml`; see
  `res/xml/data_extraction_rules.xml` and `backup_rules.xml`) so a password is
  never carried off-device by cloud backup or device-to-device transfer. The
  credential store is encrypted at rest.
- **Settings** (home-country override, theme, whether automatic diversion is
  enabled) are ordinary preferences and may be backed up.
- Phomo keeps no call history / CDR in v1 beyond what the platform's own call
  log records for a Telecom call. If a call history is added later it is a
  product decision recorded here.

## UI architecture

- Jetpack **Compose** with **Material 3** and dynamic color, matching platform
  conventions. Light and dark themes both first-class.
- The surface is intentionally small: a home/status screen, account setup, a
  manual dialer, and a settings/permissions screen. There is no bottom nav
  hierarchy to get lost in — this is a utility, not a destination app.
- Every user-facing screen has a Roborazzi screenshot test wired into CI (see
  `AGENTS.md` "Testing expectations").

## Distribution and versioning

- `versionCode` = `git rev-list --count HEAD`; `versionName` =
  `"1.0.<count>+<shortSha>"`, both derived at configure time in
  `app/build.gradle.kts`. The same commit always produces the same
  `versionCode`, and the short SHA is recoverable from an installed build.
- CI (`.github/workflows/android-ci.yml`) builds and unit-tests every PR,
  records screenshots, and on `main` distributes a debug build via Firebase App
  Distribution and uploads a signed AAB to the Play Store internal track. All
  distribution steps are secret-gated and no-op on forks / without secrets, so
  the project builds cleanly for anyone. Commit subjects become the release
  "What's new" — see `AGENTS.md` "Commit messages".
- **Telemetry** (Firebase Crashlytics + Performance) is wired but gated on a
  `google-services.json` that isn't checked in, so it is inert for forks and in
  the sandbox. No advertising ID is collected.

## Testing strategy

- **Pure logic is unit-tested exhaustively.** Number classification (domestic
  vs. international, E.164 normalization, emergency/short-code exclusion), the
  routing decision, and the registration state machine are all
  framework-independent Kotlin and carry the correctness weight of the app.
- **UI is screenshot-tested** with Roborazzi under Robolectric, recorded in CI.
- **Signaling, media, audio routing, and the live registration lifecycle can
  only be verified on a real device** with a real SIP trunk — the sandbox and
  emulator have no cellular radio, no microphone, and no SIP peer. Changes to
  those paths are flagged for on-device call testing every time and are never
  reported as verified on inspection alone.

## History-derived decisions

_(This section accumulates the "why" behind decisions as the product evolves, so
later readers don't re-litigate settled trade-offs. The first entries:)_

- **Outbound-only v1** — chosen to enable the dormant-until-called battery model;
  inbound would force always-on registration.
- **Linphone over PJSIP / custom** — Maven distribution and proven media/NAT/echo
  handling outweigh its size and GPL licensing; call quality is not a place to
  DIY.
- **Telecom `CallRedirectionService` + self-managed `ConnectionService`** —
  redirection gives the transparent "stays out of the way" diversion; the
  calling account gives a reliable manual override and proper in-call system
  integration. Domestic calls are always left to the SIM, failing safe toward
  the native path.
