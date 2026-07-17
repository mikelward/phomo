# Phomo — roadmap

The order below is the intended implementation sequence. Each milestone is a
self-contained PR (or small stack) that keeps CI green and `main` shippable.
Items are checked off as they land. See `SPEC.md` for the design each milestone
implements.

## v0 — Foundation (this PR)

- [x] Project scaffolding: Gradle wrapper, version catalog, `:app` module.
- [x] `AGENTS.md` (+ `CLAUDE.md` / `GEMINI.md` symlinks), `SPEC.md`,
      `DEVELOPMENT.md`, `PRIVACY.md`, `docs/*`.
- [x] CI: build + unit test + lint, Roborazzi screenshot job, secret-gated
      Firebase / Play deploy — all adapted from typelauncher.
- [x] `.claude/` SessionStart hook + settings for web sessions.
- [x] Minimal buildable Compose app: `MainActivity`, `PhomoTheme`, placeholder
      home screen, build-provenance label, unit + screenshot tests.

## Milestone 1 — SIP stack integration

- [ ] Add the Linphone SDK dependency (confirm the current Maven coordinate and
      repository; wire it into `settings.gradle.kts` / `libs.versions.toml`) and
      verify it resolves and links across target ABIs.
- [ ] Confirm the debug APK / release AAB build with the native libs, and check
      the download-size impact (App Bundle per-ABI splits).
- [ ] Thin Kotlin wrapper around the SDK driven in on-demand mode (no background
      service, no persistent registration) — bring-up, register, place INVITE,
      teardown. Unit-test the state machine; real signaling is on-device only.

## Milestone 2 — Account setup

- [ ] Account setup screen: domain/registrar, username, password, outbound
      proxy. Reference copy targets a Twilio SIP trunk.
- [ ] Encrypted, backup-excluded credential store (`phomo_account.xml`).
- [ ] Validate credentials by attempting a registration on demand; surface
      clear success/failure.

## Milestone 3 — Manual dialer + place a call

- [ ] In-app dialer that places a call through a self-managed `ConnectionService`
      / `PhoneAccount`.
- [ ] In-call surface: connecting / ringing / active / ended, mute, speaker,
      DTMF, hang up.
- [ ] In-call foreground service (`phoneCall` / `microphone`), scoped to the
      call's lifetime.
- [ ] Contextual permission requests (microphone, manage-calls). **First real
      end-to-end call on a physical device with a real trunk.**

## Milestone 4 — Number classification + routing

- [ ] Pure-Kotlin domestic-vs-international classifier (E.164 normalization,
      home-country resolution, emergency/short-code exclusion). Exhaustive unit
      tests — this is the app's highest-stakes correctness surface.
- [ ] Home-country resolution from SIM/network with a settings override.

## Milestone 5 — Automatic diversion

- [ ] `CallRedirectionService` + `ROLE_CALL_REDIRECTION` request flow.
- [ ] International dials divert to Phomo's SIP account; domestic dials pass
      through to the SIM unchanged; every failure falls back to the native path.
- [ ] Settings toggle to enable/disable automatic diversion.
- [ ] Verify on both a Pixel and a Samsung device (Telecom behavior differs).

## Milestone 6 — Polish

- [ ] Settings/About screen (build provenance, privacy link, theme).
- [ ] Empty/error states, connectivity checks before dialing.
- [ ] Screenshot coverage for every user-facing screen.

## Post-v1 (explicitly out of scope for v1)

- [ ] Inbound calling (requires a different, more power-hungry registration
      model — a deliberate product decision, not a small addition).
- [ ] Presenting the user's existing mobile number as outbound caller ID
      (depends on trunk-side verified-caller-ID configuration).
- [ ] Call history within the app.
- [ ] Translations (English copy ships first and is approved in chat before any
      `values-*/` locale is touched — see `AGENTS.md` "Translations").
