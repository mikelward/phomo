# Phomo

Minimalist Android SIP client for calling overseas cheaply.

Phomo places outbound calls over a SIP trunk you configure (the reference setup
is a Twilio SIP trunk), while letting your native dialer and SIM keep handling
everything else. Dial an international number from the stock dialer, a `tel:`
link, or a contact and Phomo routes it over SIP automatically; domestic calls
stay on your SIM. It's built to feel like part of the platform and stay out of
the way.

- **Outbound-only (v1)** — deliberately, so the app can stay dormant and
  battery-efficient until the moment you place a call.
- **Android 16+**, first-class on recent Pixel and Samsung devices.
- **Bring your own trunk** — Phomo is a client, not a calling plan. You supply
  your SIP credentials.

> **Status:** early scaffolding. The calling stack is implemented across the
> milestones in [`TODO.md`](TODO.md). See [`SPEC.md`](SPEC.md) for the design.

## Documentation

- [`SPEC.md`](SPEC.md) — product and architecture (the SIP/media stack, Telecom
  routing, the on-demand registration battery model, permissions).
- [`AGENTS.md`](AGENTS.md) — engineering conventions, quality bar, CI, git and
  PR workflow.
- [`DEVELOPMENT.md`](DEVELOPMENT.md) — build, test, and run instructions.
- [`PRIVACY.md`](PRIVACY.md) — what data the app handles and where it goes.
- [`docs/`](docs) — Firebase and Play Store distribution setup.

## Building

```sh
./gradlew assembleDebug   # build the debug APK
./gradlew test            # JVM unit tests
./gradlew lint            # Android lint
```

JDK 17+ (21 recommended) and the Android SDK (Platform 36) are required. See
[`DEVELOPMENT.md`](DEVELOPMENT.md) for details, and note that anything
call-related can only be verified on a real device with a real SIP trunk — the
emulator and CI sandbox have no cellular radio, microphone peer, or SIP server.

## License

To be determined. Note that the Linphone SDK the calling stack builds on is
GPLv3 (or available under a commercial license), which constrains the options.
