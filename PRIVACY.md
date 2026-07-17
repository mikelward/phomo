# Phomo Privacy Policy

_Last updated: 2026-07-17_

Phomo is a minimalist Android SIP client for placing outbound calls over a SIP
trunk you configure. This document describes what data the app handles and where
it goes. It backs the privacy policy linked from the app's About screen.

## The short version

- Phomo has **no backend of its own.** There is no Phomo account, no Phomo
  server, and no Phomo-operated database. The developer does not receive your
  calls, your call metadata, your contacts, or your SIP credentials.
- Your calls go directly from your device to **your SIP provider** (for example,
  a Twilio SIP trunk you set up). Their handling of your calls is governed by
  **their** privacy policy and terms, not this one.

## What Phomo stores on your device

- **SIP account credentials** — the domain/registrar, username, password, and
  outbound proxy you enter during setup. These are stored **only on your
  device**, encrypted at rest, and are **excluded from cloud backup and
  device-to-device transfer** so they are never uploaded off the device by the
  Android backup system.
- **App settings** — your home-country override, theme choice, and whether
  automatic call diversion is enabled. These are ordinary preferences and may be
  included in Android's system backup.

Phomo does not maintain its own call history beyond what Android's own call log
records for any call placed through the system (the same call log the stock
dialer uses).

## What Phomo transmits, and to whom

- **To your SIP provider only:** when you place a call, Phomo sends SIP signaling
  and call audio to the SIP server **you** configured. This is the call itself.
  Phomo does not route this through any Phomo-operated server.
- **No advertising identifier is collected.** Phomo explicitly disables the
  Android advertising ID and collects no advertising data.

## Permissions and why they're used

- **Microphone** — to capture your voice during a call. Requested the first time
  you place a call.
- **Network / connectivity** — to carry SIP signaling and call audio, and to
  check you're online before dialing.
- **Manage calls / call redirection role** — to place calls through Android's
  Telecom framework and, if you opt in, to automatically route international
  calls over SIP while leaving domestic calls on your SIM. You can decline or
  revoke the redirection role at any time; the app still works, you just choose
  Phomo manually per call.
- **Notifications** — to show the ongoing-call notification while a call is
  active.

## Crash and performance reporting

Builds distributed by the developer may include Firebase Crashlytics and
Performance Monitoring to diagnose crashes and performance problems. This is
configured by a Firebase project file that is **not** part of the open-source
code, so builds you or others compile from source without it send **no**
telemetry at all. When present, this reporting collects diagnostic data (crash
stack traces, device model, OS version, performance traces) and never the
contents of your calls or your SIP credentials.

## Children

Phomo is not directed at children and collects no personal data centrally.

## Changes to this policy

Material changes will be reflected in this document and its published copy. The
"Last updated" date at the top tracks the latest revision.

## Contact

Questions about this policy can be raised via the project's GitHub repository.
