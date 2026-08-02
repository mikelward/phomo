# Push-woken SIP — options for inbound calling

**Question.** Can Phomo use an urgent FCM push to wake up, connect to the SIP
server, and take an incoming call — and does RFC 8599 help?

**Short answer.** Yes, push-woken SIP is how this is done, and RFC 8599 is
exactly the standard for it — but RFC 8599 standardizes only the
*client-to-server handshake*. It does not give you a server that sends the push.
Twilio does not implement it, and Twilio's Elastic SIP Trunking does not even
accept `REGISTER`, so with Twilio as it stands today the push has to come from
somewhere else: a proxy in front of it, a rented gateway, a cloud function of
ours, or a vendor SDK that replaces SIP for the inbound leg.

**Is there a way to get everything** — no infrastructure, no drain, instant and
reliable ringing, still a SIP app, with the numbers we want? **Yes, in principle:
a PSTN provider whose own registrar speaks RFC 8599.** On the client side that is
a liblinphone flag; on the provider side it needs one piece of onboarding, since
a registrar cannot push to our device from `pn-param` alone — it has to hold FCM
credentials for our Firebase project, or we have to use a project of theirs.
Whether such a provider exists, and will do that, is unknown and is the single
most valuable thing to find out — §12 makes that case, and everything else in
this document is what to do if the answer is no.

**Where to look.** §4 answers the practical follow-up — how often the client
actually has to register, and which events should trigger it. §6 surveys the
options. §7 walks the "just a cloud function"
idea through an actual call flow and timing budget. §8 covers who could run the
always-on part instead of us — CPaaS SDKs, rented push gateways, providers that
might implement RFC 8599 natively, a self-hosted push gateway, and a hosted
Asterisk PBX. §9 covers the numbers: what each country asks for, and whether
becoming a carrier ourselves is ever worth it. §10 compares Twilio and Telnyx
dimension by dimension, since the provider choice constrains most of the rest.
§12 lays out the trade-off frontier — what each option spends, and where the
dials are worth turning.

**Nothing here is a hard constraint.** Battery, latency, delivery reliability,
infrastructure, cost, and number availability are all quantities to spend, not
tests to pass, and the right amount to spend on each depends on whether this is
a secondary number backed by voicemail or a replacement for the SIM. Where this
document sounds decisive, read it as an argument, not a rule.

**SIP is not the goal — the functionality is.** This is a **v2 exploration**,
and in v2 the SIP premise is itself up for grabs. Most of this document asks
"how do we wake a *SIP* client," but that question only exists because of a
route, not because of the product. If Phomo instead builds on a vendor WebRTC
SDK, the provider owns inbound push, there is no registration lifecycle at all,
and §§2, 4, 7 and most of §8 stop applying. **Option 6 in §6 treats that route as
a first-class option rather than a concession**, and §12 says what survives
either way. Read the SIP analysis as "if we stay on SIP," not as a premise.

This document is exploration, not a decision, and it does not change v1. `SPEC.md`
describes the shipping v1 product — outbound-only over SIP — and nothing here
amends it.

---

## 1. Why this question matters

Phomo's whole battery model rests on being outbound-only: no persistent
registration, no background service, no keep-alive, nothing running while the
phone is in a pocket (`SPEC.md` → "Registration lifecycle"). Inbound calling
normally destroys that, because an inbound-capable SIP client has to be
*reachable*, which classically means staying registered.

Concretely, on Twilio: a SIP endpoint may not ask a Twilio SIP Domain for a
binding shorter than **600 seconds**. That is a floor, not a target — longer
expiries are accepted and the refresh policy is the client's to choose, so the
`REGISTER` cadence does not follow from the 600s minimum on its own.

What sets the real cadence is the network underneath the registration, not the
SIP expiry. A binding is only useful for as long as the path it was made over
stays open, and NAT mappings on mobile networks and home routers are measured in
tens of seconds to a couple of minutes — far shorter than any expiry we would
negotiate. So an always-reachable client keeps waking to hold that path open
(keep-alives on the signaling transport, plus the periodic re-`REGISTER`)
regardless of how long an expiry it asked for. Stretching the SIP expiry moves
the *SIP* cost down and leaves the *network* cost, which is the dominant one,
where it was. Either way it is a wake every few minutes, forever, each costing
radio and CPU — precisely the drain the current design exists to avoid.

Push exists to break that link: keep the *binding* alive on the server without
keeping the *device* awake. The device's only always-on cost becomes Google
Play services' FCM socket — which is already open on every Pixel and Samsung for
every other app on the phone, and which Phomo pays nothing incremental for.
That is the single strongest argument for this whole direction: it is the one
way to add inbound calling without adding any always-on work to Phomo's own
process.

---

## 2. What RFC 8599 actually specifies

[RFC 8599](https://www.rfc-editor.org/rfc/rfc8599.html) ("Push Notification
with the Session Initiation Protocol") defines how a suspended UA tells a SIP
network how to wake it, and how the network then does so.

**The client side.** The UA puts new SIP URI parameters in the `Contact` header
of its `REGISTER`:

- `pn-provider` — which push service to use (`fcm`, `apns`, …).
- `pn-prid` — the push resource ID, i.e. the FCM registration token.
- `pn-param` — provider-specific routing detail (for FCM, the project
  identifier).
- `pn-purr` — the "push resource URI" the server hands back, which the UA then
  echoes in non-`REGISTER` requests so mid-dialog traffic can also trigger a
  wake. Outside of `pn-purr`, a UA must *not* put these parameters on
  non-`REGISTER` requests.

**The server side.** The registrar/proxy stores those parameters against the
binding. When an `INVITE` arrives for that address of record, the proxy asks the
push service to wake the device, **holds the request** while the device comes
up, and delivers it once the UA is reachable again. A server that supports the
mechanism advertises it with a `sip.pns` feature-capability indicator in
`Feature-Caps`; a server asked for a push provider it does not support answers
**555 (Push Notification Service Not Supported)**.

**The part that is the actual battery win.** RFC 8599 also inverts binding
refresh: rather than the device running its own timer, the *server* sends a push
when it wants the binding refreshed, and the device re-`REGISTER`s in response.
The device therefore holds no timer, no alarm, and no keep-alive of its own.
This is the property that makes push-woken SIP compatible with Phomo's "nothing
in the background" posture, and it is the reason to prefer the standard
mechanism over a hand-rolled one.

**But note the tension with §3.** A refresh push surfaces nothing to the user —
it is exactly the "silent push" that Android's priority-downgrade heuristic
penalizes, and spending high priority on it risks getting the *call* pushes
demoted, which would be a self-inflicted wound. The resolution is to split them:
**refresh pushes at normal priority, call pushes at high**. Refreshes are not
time-critical, so the server can send them with lead time and the binding expiry
can be long enough to absorb Doze deferring a normal-priority message by minutes.
That is a real design constraint on the "no timer, best reliability" rating, not
a free property — and it is worth asking a candidate provider how their server
schedules refreshes, because a naive implementation that fires high-priority
silent pushes on a short binding would quietly degrade the thing we actually
care about.

**What RFC 8599 explicitly leaves out.** How the UA obtains its PRID from the
push service, and the push service's own protocol, are out of scope. Critically,
so is everything about *operating* the push sender: whoever runs the SIP server
must hold credentials for our Firebase project (an FCM HTTP v1 service-account
key, since legacy server keys are gone) and must send the pushes. **RFC 8599
does not remove the need for a server. It standardizes the conversation with a
server we do not currently have.**

---

## 3. What Android gives us

- **There is no PushKit equivalent on Android.** The mechanism is a
  **high-priority FCM data message**. FCM attempts to deliver high-priority
  messages immediately and will wake a device that is in Doze, with limited
  network access granted to the receiving app. This is the documented,
  supported way to signal an incoming call.
- **High priority is not unconditional.** The system downgrades an app's
  high-priority messages if it observes the app using them for things that do
  not surface time-sensitive content to the user. A downgraded message cannot
  start a foreground service. Practically: every push must result in a visible
  incoming-call notification, and the handler should check
  `RemoteMessage.getPriority() == PRIORITY_HIGH` before doing call work. A
  design that pushes speculatively (to pre-warm, to poll, to keep a binding
  fresh silently) risks getting the *call* pushes throttled. This bites RFC 8599
  directly, since its binding-refresh push surfaces nothing to the user — see §2
  for why refreshes want normal priority and calls want high.
- **Receiving a high-priority FCM message is an explicit exemption** from the
  Android 12+ restriction on starting a foreground service from the background.
  So the wake path is legal.
- **But the microphone is a separate trap.** `RECORD_AUDIO` is a while-in-use
  permission, and on Android 14+ starting a `microphone`-type foreground service
  while the app is in the background throws `SecurityException`. The exemptions
  are things like "a system component starts the service" and "the service
  starts by interacting with a notification" — i.e. Telecom binding us, or the
  user tapping Answer. The right shape is therefore: push → hand the call to
  Telecom → post the call notification → capture audio only once Telecom/the
  user has brought us up. Rolling our own `microphone` foreground service
  straight off the push is the version that fails on a real device.
- **`androidx.core-telecom` (`CallsManager`) is the modern path** and handles
  this for us: `addCall` plus a call notification posted within 5 seconds gives
  the app foreground execution priority, with the platform managing the
  foreground-service side. It also owns audio endpoint routing (and warns
  against touching `AudioManager.setCommunicationDevice` /
  `startBluetoothSco` yourself). If we do inbound, this is what we should build
  on rather than extending the self-managed `ConnectionService` by hand.
- **Full-screen intents are gated.** Since Android 14, `USE_FULL_SCREEN_INTENT`
  is granted by default only to apps that provide calling or alarm
  functionality; Play revokes it at install for apps that do not. Phomo
  qualifies, but the incoming-call UI has to check
  `NotificationManager.canUseFullScreenIntent()` and degrade to a heads-up
  notification rather than assuming a full-screen ring.
- **FCM requires Google Play services.** That is fine for the stated targets
  (Pixel, Samsung) but means inbound would simply not work on a de-Googled
  device, where outbound still would.
- **Token rotation is a correctness surface.** When FCM rotates the
  registration token, whatever the server stored is stale and inbound calls
  silently stop arriving. The app has to act on `onNewToken` — but *what* it
  does depends on the model, and getting this wrong breaks one design or the
  other: under RFC 8599 the token travels in a `REGISTER` contact, so a fresh
  registration is the only way to deliver it; under register-on-demand it goes
  to the token store over an authenticated endpoint, and registering here would
  hold a binding that model exists to avoid. §4 splits this out properly.
  "Silently stops receiving calls" is the worst possible failure mode for a
  phone app, so this needs a test, not just a code path.

---

## 4. When does the client actually need to register?

§2 says the device holds no timer of its own. That raises the obvious follow-up:
what *does* trigger a `REGISTER`, and can it simply be reboot, app start, and
network change? The answer is better than it looks, and one of those three
triggers is a trap.

### With push, the device owns no timer — and may need no cadence at all

The reason an always-registered client re-`REGISTER`s every few minutes is not
really the SIP expiry (§1). It is that a binding is only useful while **the NAT
mapping it was made over is still open**, and those close in tens of seconds to
a couple of minutes on mobile networks. That is what forces the treadmill.

**Push dissolves that, because the server no longer needs to reach us over that
path.** It reaches us out of band via FCM; we then register, which opens a fresh
mapping; and the `INVITE` arrives down the path we just opened. There is nothing
to hold open in between, so there is nothing to keep alive.

Concretely, by model:

- **Register-on-demand** (§7's webhook, and the PBX in §8 E) — no binding is
  held at all, so there is genuinely **no cadence**. The device registers at call
  time, exactly as the outbound path already does, and tears down after. Zero
  registrations between calls.
- **RFC 8599** (§8 C and D) — a binding *is* held, so there **is** a cadence; it
  is simply not ours. The registrar has to push for a refresh before the
  binding expires, and the device answers with a `REGISTER`. What push removes
  here is the client-owned timer and the need to hold a live transport open —
  not the periodic wake itself. Because the refresh interval is bounded by the
  binding lease rather than by a NAT mapping, it can be minutes-to-hours rather
  than tens of seconds, but it is not free, and §11 rates its idle cost as low
  and unmeasured for exactly this reason.

### What actually needs to stay fresh is the push token, not the binding

This is the reframing that matters. In every push model the server's route to
the device is the FCM registration token, so **token freshness is the
correctness surface, and binding freshness is either the server's job or
irrelevant**. That changes which events are worth acting on:

**The right action differs by model, and conflating them breaks something
either way** — so the table is split. In register-on-demand the token lives in
our token store and a SIP `REGISTER` before a call would violate the
zero-registration-between-calls invariant; in RFC 8599 the token *is* carried in
the `REGISTER` contact, so there is nowhere else to put it.

| Trigger | Register-on-demand (§7, §8 E) | RFC 8599 (§8 C, D) |
|---|---|---|
| **App start** | **Yes** — publish the current token to the token store, revalidate credentials. No SIP registration. | **Yes** — `REGISTER`, which both refreshes the binding and carries the current token. The recovery point after a lapse. |
| **`onNewToken`** | **Yes, to the token store only** — via the authenticated endpoint (§7). Registering here would hold a binding we do not want. | **Yes, via `REGISTER`** — the token travels in the contact, so a registration is the only way to deliver it. |
| **Credentials changed** | **Yes** — the account changed; re-publish and revalidate. | **Yes** — the old binding is wrong. |
| **Reboot** | **No.** Tokens survive reboots and no binding is held, so there is nothing to do. | **Worth considering.** A binding may have expired while the phone was off, leaving the registrar nothing to push at — see the failure modes below. Note the Android 15+ `BOOT_COMPLETED` restriction applies to launching a `phoneCall` foreground service, *not* to performing a plain `REGISTER`, so it is not the obstacle it first appears. Weigh a boot-time `REGISTER` against a wake on every boot. |
| **Network change** | **No — a trap** | **No — a trap** |

### Why network change is the wrong trigger

The one answer that is the same in both models, and the most tempting of the
three triggers:

- It needs a `ConnectivityManager` callback that stays registered while the app
  is idle — precisely the standing background work the battery rule in
  `AGENTS.md` tells us not to add.
- Network changes are *frequent*. Every Wi-Fi/cellular handover, every change
  that moves the device's IP. On a train journey it would fire more often than
  the fixed timer it was meant to replace, which inverts the whole point.
- It buys nothing, because **Play services already handles network changes for
  the FCM socket**, once, on behalf of every app on the device. Re-doing that
  per-app is the exact work push exists to let us skip.

### Without push, event-driven registration does not work

Worth stating plainly, because it is the version that looks fine in testing. A
binding is a lease with a server-side expiry — Twilio SIP Domains will not grant
one shorter than 600s, but it is still finite — and the NAT path dies well
before the lease does. An app that registers only on reboot, app start and
network change would therefore be reachable for a minute or two after each of
those events and unreachable the rest of the time. It would pass a
place-a-test-call check every time and drop real calls all day.

### The two failure modes this leaves

Both end in "it silently stopped ringing," and both recover only at app start:

- **A force-stopped app receives no FCM messages at all** until the user opens
  it again. If the user swipes the app away from the task switcher on a device
  whose OEM treats that as a force-stop, inbound dies with no signal.
- **A binding dropped after a long power-off** — phone flat over a weekend, or
  the account unused for longer than the server's retention — leaves nothing for
  the server to push at.

**Neither is detectable from inside the app while it is happening**, and this
is worth being blunt about because it limits the mitigation §12 asks for. A
force-stopped app cannot run, so it cannot receive a push, update a
registration-health indicator, or raise a notification; and opening the app to
*look* at the indicator is the same action that clears the force-stop and
repairs the binding. The indicator therefore reports health at the moment the
user is already fixing it, and stays silent through the entire window in which
inbound was broken. The expired-binding case has the same shape.

So the indicator is still worth building — it turns "is this thing working?"
into a question the user can answer, and a "last successful registration / last
push received" timestamp lets someone who suspects a problem confirm it — but
it **cannot** warn during an outage, and the document should not pretend
otherwise. Actually detecting these cases needs a signal from outside the app:
a server-side watchdog noticing the device has not checked in for longer than
expected, and reaching the user by some other channel. That is more
infrastructure, and it is the kind of thing worth deciding deliberately rather
than discovering after a missed call.

---

## 5. What Twilio gives us (and does not)

- **Elastic SIP Trunking — what Phomo uses today for outbound — does not
  support `REGISTER` at all.** A trunk delivers inbound traffic to a fixed
  origination URI, which a mobile handset cannot be. So inbound over the
  current product is a non-starter regardless of push.
- **Programmable Voice SIP Domains do support registration.** Enabling SIP
  Registration on a SIP Domain lets an endpoint bind its AOR, minimum expiry
  600 seconds, and inbound calls reach it via TwiML `<Dial><Sip>` to
  `user@yourdomain.sip.twilio.com`. Dialing an endpoint that is not currently
  registered fails immediately (error 32009, "the user you tried to dial is not
  registered").
- **Twilio does not implement RFC 8599.** There is no evidence of `pn-*`
  handling or `sip.pns` support in the SIP Domain registrar, and Twilio's own
  answer to mobile push is a different product — the Voice SDK.
- **Twilio's Voice SDK does the push for you.** You upload an FCM HTTP v1
  service-account key to Twilio as a Push Credential, the app calls
  `Voice.register(accessToken, fcmToken, …)`, and Twilio itself sends the FCM
  message when a call arrives for that identity. There is no SIP registration in
  this model at all — the identity + Push Credential SID + FCM token *is* the
  address.
- **Either inbound route needs an HTTPS endpoint of ours.** A Twilio number
  that rings a registered SIP endpoint needs a Voice webhook returning TwiML,
  and the Voice SDK needs an access-token minting endpoint. Twilio Functions can
  host either. This matters because **Phomo currently has no backend at all**,
  and every inbound option ends that property.

Two consequences worth stating plainly: inbound is not a client-side feature we
can add in the app alone, and inbound would move us off Elastic SIP Trunking
onto a second Twilio product.

---

## 6. The options

### Option 0 — Stay outbound-only (status quo)

Do nothing. Inbound calls arrive on the SIM as they do today.

- **Battery:** unchanged; the app stays dormant.
- **Infrastructure:** none.
- **Cost to the user:** none, given the product's premise. Phomo exists to make
  *outgoing* international calls cheap; there is no matching cost argument for
  receiving them, because incoming calls to the user's mobile number already
  land on the SIM for free.
- **Verdict:** still the right default. Everything below should be judged
  against "what does the user actually get that the SIM does not already give
  them?"

### Option 1 — Persistent registration, no push

Keep a SIP registration alive against a Twilio SIP Domain with a foreground
service and a ~5-minute re-`REGISTER`.

- **Battery:** the expensive one, and expensive in the way the current spec was
  written to avoid: a wake every few minutes indefinitely, NAT keep-alives on
  top, and a permanent ongoing notification. Worth being honest that "expensive"
  here is not quantified — a keep-alive every 30s on a modern radio is not the
  drain it was a decade ago, and the true cost is measurable on a real device in
  a day. It is plausible this lands anywhere between "unnoticeable" and "kills
  the phone by mid-afternoon," and we do not currently know which.
- **Reliability:** the bigger objection, and the one that does not improve with
  measurement. Doze and OEM battery management (Samsung especially) will defer
  or kill the refresh, and a missed refresh means an inbound call fails outright
  with 32009 rather than degrading. An always-on design that the OS is actively
  working against is less reliable than a push design the OS is designed to
  support — which is the real argument against it, more than the battery.
- **The interesting middle ground:** availability does not have to be
  all-or-nothing. Registering only while charging, only on Wi-Fi, only during
  waking hours, or only while the screen has been on recently would each buy
  most of the practical value at a fraction of the cost — "reachable at home and
  at my desk, voicemail otherwise" is a perfectly good product. This also
  composes with push rather than competing with it: registered when it is cheap
  to be, push-woken when it is not. If the push paths below turn out to be
  expensive or unreliable, this is the fallback worth costing properly rather
  than dismissing.
- **Verdict:** the weakest option as stated, but not disqualified — and its
  scoped variants are genuinely worth exploring, especially since they need no
  infrastructure at all.

### Option 2 — RFC 8599 via a push-capable SIP proxy in front of Twilio

Run a push-capable SIP server (Flexisip supports RFC 8599 and has an explicit
**"push gateway" mode** for fronting a SIP infrastructure that does not do push;
OpenSIPS 3.1+ and Kamailio have equivalents). Phomo registers to *that* server
with `pn-provider=fcm` and its FCM token; the server holds the `INVITE`, pushes,
waits for the device to re-`REGISTER`, then delivers the call. Twilio sits
behind it as the PSTN gateway.

- **How well it fits Phomo:** technically the best fit by a wide margin. It is
  the only option where the device holds no timer at all (server-driven binding
  refresh), where the `INVITE` is *held* rather than raced, and where the client
  side is a standard, well-trodden path — liblinphone already implements
  RFC 8599 client-side, discovers the FCM token itself, and populates the
  `Contact` parameters when the account allows push. We would be enabling a flag
  the SDK already has, not writing signaling. Token rotation also falls out for
  free: the FCM token travels in the `Contact` of the next `REGISTER`, so
  storing and updating it is the registrar's existing job and needs no endpoint
  or datastore of ours — unlike Option 3, which has to build that separately.
- **Cost:** we have to run and secure a SIP proxy — a VPS, TLS certificates,
  upgrades, monitoring, and a service whose downtime means missed calls. It also
  holds our Firebase service-account key and terminates our SIP credentials.
  At one user that is a small VM and a container rather than "infrastructure" in
  the usual sense; §8 D prices it more carefully, because the reflex to dismiss
  it may be stronger than the actual burden deserves.
- **Privacy:** a new always-listening server that sees all of the user's call
  metadata, plus Google seeing a push per inbound call — and, depending on what
  the payload carries, *who* is calling and when (§7). `PRIVACY.md` would need
  rewriting.
- **Verdict:** technically the best of the self-directed options, and the only
  one that is both standards-based and compatible with our existing client. It
  is bought with ongoing ownership of a server, which is a preference question
  rather than a technical one.

### Option 3 — TwiML webhook fires the push ("poor man's RFC 8599")

No SIP proxy. The Twilio number's Voice webhook points at a small function of
ours. On an inbound call it sends a high-priority FCM data message to the device
and then **holds its HTTP response open** while the app wakes, registers, and
pings a `/ready` endpoint — at which point it returns a single `<Dial><Sip>` to
the SIP Domain endpoint. If the ping never comes, it returns voicemail TwiML
instead. (The obvious alternative — dial immediately and retry with ringback
between attempts — is a trap that answers the caller's leg early; §7 explains
why.)

- **Battery:** as good as Option 2. Idle cost is zero; the device registers only
  when a call is actually inbound, and tears down after — the same on-demand
  lifecycle the outbound path already has, just triggered by a push instead of a
  tap. The existing `SipCallMachine` shape carries over almost unchanged; it
  gains an inbound entry point alongside `PlaceCall`.
- **Infrastructure:** an HTTPS function (Twilio Functions or similar) holding an
  FCM service-account key — *plus* durable storage for the device's current FCM
  registration token and an authenticated endpoint the app can publish to from
  `onNewToken`. That second half is easy to overlook and is not optional: the
  function has to push to one specific installation, the token rotates (§3), and
  a token the server cannot update is a phone that silently stops ringing. So
  this is not the stateless one-function deployment it first looks like — it is
  a small service with a datastore and an authentication story, and the auth
  matters, because an endpoint that lets anyone rewrite the push token is an
  endpoint that lets anyone steal the user's incoming calls. There is still no
  SIP server and nothing holding a socket, but the gap to Option 2's burden is
  narrower than it first appears.
- **The weakness:** nobody holds the `INVITE`. Twilio will not wait
  indefinitely, so the whole wake — push delivery, stack bring-up, `REGISTER` —
  has to fit inside the webhook's HTTP timeout, and a cold start on a Dozing
  phone on mobile data is not a predictable number. Overrun it and the caller
  goes to voicemail while the user is holding an unlocked phone. That ceiling is
  fixed and outside our control, which is exactly the kind of race that shows up
  as "sometimes it just doesn't ring."
- **Non-standard:** we would be reimplementing the idea of RFC 8599 badly,
  without the held request or the server-driven refresh that make the RFC work.
- **Walked through in full in §7** — this is the option that looks like "just a
  cloud function," so it is worth seeing the actual call flow and timing budget
  before judging it.
- **Verdict:** the cheapest thing that could actually ship against Twilio as it
  is today, and worth prototyping if inbound is wanted. Its reliability ceiling
  is lower than Option 2's, and reliability is a stated hard acceptance
  criterion.

### Option 4 — Twilio Voice SDK for inbound (drop SIP for the inbound leg)

Use `com.twilio:voice-android` for receiving. Upload an FCM service-account key
to Twilio as a Push Credential; the app registers with `Voice.register(...)`;
Twilio sends the push and delivers the call over its own signaling.

- **Infrastructure:** the least of our own, but not zero. Two endpoints:
  an **access-token endpoint** to mint the JWTs the SDK logs in with, and — for
  a PSTN number to reach the app at all — an **inbound routing webhook** (a
  TwiML App returning `<Dial><Client>` aimed at the registered identity).
  `Voice.register(...)` only tells Twilio where to *push*; it does not route the
  call. Both are small and stateless, and Twilio Functions hosts either.
  What we still do not need: a SIP server, a webhook that stalls waiting for a
  wake, a registration to keep valid, or a token store — as in Option 2, a fresh
  `Voice.register(...)` after `onNewToken` updates the address Twilio pushes to,
  so nothing of ours has to remember it.
- **Battery:** equivalent to the others — push-driven, nothing idle.
- **Cost:** a second, entirely separate calling stack in the app alongside
  liblinphone, with its own media engine, its own audio focus behavior, and its
  own Telecom integration to reconcile with ours. It is also a hard lock-in to
  Twilio for inbound, which contradicts `SPEC.md`'s "bring-your-own trunk,
  nothing is Twilio-specific in the protocol" — an unusually large architectural
  concession. Two native media stacks in one app is also a real APK-size and
  audio-conflict problem.
- **Verdict:** lowest operational burden, highest architectural cost. Only
  attractive if we decide Phomo is a Twilio app rather than a SIP app.

### Option 5 — Push for something other than inbound

Worth naming to dismiss: push has no use on the outbound path. Outbound already
registers on demand at the moment the user taps Call, and pre-warming
registration with a speculative push would both burn the high-priority budget
(§3) and reintroduce idle work. There is no version of "urgent push" that
improves outbound calling.

### Option 6 — Drop SIP entirely and build on a vendor SDK

Listed last because it is the largest, but it is not a concession — on the
functionality Phomo actually wants, it is arguably the shortest path. Use one
vendor's WebRTC SDK (Twilio's or Telnyx's) for **both** legs: outbound *and*
inbound, replacing liblinphone rather than sitting beside it.

**What this dissolves.** Nearly all of this document. There is no `REGISTER`, no
binding, no expiry, no NAT keep-alive, no push gateway, no RFC 8599, no wake
race, no `/ready` endpoint, no registration-health indicator. The provider sends
the push, holds the call while the device wakes, and hands the app a ringing
call object. §§2, 4, 7 and most of §8 are answers to questions this route does
not ask.

**What it costs.**

- **Portability becomes a rewrite, not a credentials edit.** With SIP, changing
  provider is a settings change; with an SDK it is the calling layer. Whether
  that matters depends on how likely a switch really is for a single user with
  one account — but it is a genuine, permanent cost, and it should be accepted
  deliberately rather than discovered later.
- **One vendor's Android audio stack owns call quality** — echo cancellation,
  routing, Bluetooth, Telecom integration. That is the hardest thing to evaluate
  before placing real calls and the least affordable to get wrong.
- **It still is not backend-free.** An access-token endpoint and an inbound
  routing webhook are both required (§6 Option 4), and SMS would still need the
  webhook of §9.

**What it does not cost.** Media quality or PSTN reach — §8 A establishes that
WebRTC is full-duplex by design, routinely bridged to the PSTN, and carries
Opus, the 3A chain, NetEQ, FEC and mandatory ICE, under a BSD license rather
than GPLv3. On the things `AGENTS.md` actually cares about — a call that
connects, sounds clean both ways, and ends cleanly — this route is not a
compromise.

**The timing argument.** The `sip/` package is a few hundred lines of pure
Kotlin with **no liblinphone binding written yet** — the dependency is declared
but nothing calls it. So the sunk cost in SIP today is close to zero, and this
is the cheapest moment this decision will ever be. Once the binding, audio
routing and Telecom integration exist against SIP, it stops being cheap.

**Verdict:** the right route if provider portability turns out to be worth less
than it sounds, which for one user with one provider it may well be. The way to
decide is not more analysis — it is to place one real call on each SDK and
listen.

---

## 7. The TwiML webhook path, in detail

This is the "we just need a cloud function that turns an incoming call into an
FCM push" idea. It is the most attractive option on paper because it needs no
always-on server of ours, so it deserves a concrete walkthrough rather than a
one-line verdict.

### The call flow

1. A PSTN call arrives at the Twilio number. Twilio POSTs its Voice webhook to
   our function.
2. The function verifies the `X-Twilio-Signature` header (without this, anyone
   who learns the URL can make the user's phone ring), looks up the device's
   current FCM registration token, and sends a **high-priority FCM data
   message** carrying at least the `CallSid` — and the caller's number only if
   we accept disclosing it to Google (see "the trick that hides most of the
   latency" below).
3. **The function does not answer yet.** It holds the HTTP response open,
   waiting for the app to call a `/ready` endpoint (authenticated, keyed by
   `CallSid`) once it has woken and registered. Twilio gives a webhook on the
   order of ten to fifteen seconds before it times out — verify the current
   figure — and that window is the budget for the whole wake.
4. Meanwhile the app has woken on the push, brought the SIP stack up, and
   registered against the SIP Domain. It pings `/ready`.
5. The function returns a single `<Dial>`:

   ```xml
   <Response>
     <Dial timeout="30" answerOnBridge="true" ringTone="uk" action="/after">
       <Sip>sip:phomo@ourdomain.sip.twilio.com</Sip>
     </Dial>
   </Response>
   ```

   `answerOnBridge="true"` keeps Twilio from answering the PSTN leg early, so
   the caller hears genuine ringback and billing starts when the call is
   actually answered. `ringTone` supplies a country-appropriate ringback as
   early media *during* the dial, without answering. Note the value is Twilio's
   own enum, not ISO 3166 — the United Kingdom is `uk`, and `gb` is rejected
   (error 13220, "invalid ringTone value").
6. If the app never pings — push undelivered, no credentials, user's phone off —
   the function returns voicemail TwiML instead. **This fallback is mandatory**;
   without it a caller whose push never arrived listens to nothing until Twilio
   gives up.

### Why not a TwiML retry loop (a trap worth recording)

The obvious first design is to `<Dial>` immediately, let it fail fast with error
32009 ("the user you tried to dial is not registered"), and retry from the
`action` URL, playing a couple of seconds of ringback between attempts. It looks
better than a fixed `<Pause>` because it adapts to the real wake time.

**It quietly breaks the thing it is trying to protect.** Producing audio in
TwiML — `<Play>`, `<Say>` — requires Twilio to answer the inbound PSTN leg
first. So the moment the first retry plays ringback, the call is *answered*:
billing starts, `answerOnBridge` becomes moot, and the caller's carrier stops
signaling "ringing." Worse, if the device then never registers, the caller
experiences a call that connected and then dropped, rather than one that rang
out to voicemail — a materially worse outcome, and one that would be easy to
ship without noticing, because it looks correct in a happy-path test.

Hence holding the webhook response instead: nothing is answered, the caller
hears their own carrier's ringing, and there is exactly one `<Dial>`. The cost
is that it needs the app to report readiness, which means one more authenticated
endpoint on top of the token store — more evidence that this option is a small
*service*, not a lone function. It also caps the whole wake budget at Twilio's
webhook timeout, which is a harder ceiling than a retry loop would have been.

### The trick that hides most of the latency

If the push carries the caller's number, the app can raise the incoming-call UI
via `CallsManager.addCall` **the moment the push lands** — before registration
has finished, before the `INVITE` exists. The user's phone starts ringing at
push-delivery time rather than at `INVITE` time, which is where most of the
perceived latency lives.

**That "if" is a privacy decision, not a free optimization.** FCM payloads are
encrypted in transit but not end-to-end, so a payload carrying the caller's
number discloses *who is calling the user, and when*, to Google — materially
more than the bare fact that a push occurred. Three ways to handle it, in
descending order of how much latency they buy back:

1. **Send the number.** Fastest and simplest; accept and document the
   disclosure.
2. **Send an opaque `CallSid` only, and have the app fetch the caller detail
   over an authenticated channel** as it wakes. Google learns only that a call
   arrived. Costs one extra round trip before the UI can show a name or number,
   though the phone can start ringing as "unknown caller" immediately, so most
   of the latency win survives.
3. **Encrypt the payload** with a key established at registration. This is the
   only option that gets both: the caller detail arrives in the push, so the UI
   can be complete on the first frame with no extra round trip, *and* Google
   sees ciphertext rather than a number. What it costs is key management —
   establishing the key, rotating it, and handling the device that has lost
   it — in a design whose whole appeal is being small.

Option 2 is the simplest thing that protects the metadata, and option 3 is the
better one if the extra round trip turns out to matter once wake latency is
measured. Either way it is worth deciding deliberately rather than inheriting
whatever the first prototype did.

That is a genuine win, and it introduces a genuine new failure mode: a "ghost
ring." If registration then fails, or the caller hangs up while we are still
waiting, we have a ringing Telecom call with no SIP call behind it and must cancel it
cleanly with an honest disconnect cause. A phone that rings and then shows a
missed call from someone who never got through is worse than a phone that rang
half a second later.

### Timing budget

| Step | Cost |
|---|---|
| Twilio → our function → FCM accepted | ~200–500 ms |
| FCM delivery to a Dozing device | **the unknown** — the thing to measure |
| App wake, SIP stack bring-up, `REGISTER` round trip | **the unknown** — the thing to measure |
| App pings `/ready`, function returns `<Dial>` | ~100 ms |
| Hard ceiling — Twilio's webhook HTTP timeout | ~10–15 s, **not tunable by us** |

Everything hinges on the two unknowns, which are the same two unknowns as
§12's recommendation. If they total ~2–3 seconds the caller hears normal
ringback and never knows. If they occasionally total 20 seconds, calls go to
voicemail while the user is holding an unlocked phone, and no amount of
webhook tuning fixes it — that is the point at which only a held `INVITE`
(§6 Option 2, or a rented gateway from §8) will do.

### What has to be built

- The Voice webhook that fires the push and holds its response, plus the
  authenticated `/ready` endpoint the app pings once it has registered (Twilio
  Functions, Cloud Run, Workers — all fine, though "hold the response" needs a
  runtime that tolerates a request open for ten-odd seconds).
- **A token store.** The function must push to one specific installation, and
  the FCM token rotates, so there has to be somewhere durable to keep it
  (Twilio Sync, Firestore, a KV store).
- **An authenticated endpoint** for the app to publish a new token from
  `onNewToken`. The authentication is not optional: an endpoint that lets anyone
  overwrite the push token is an endpoint that lets anyone redirect the user's
  incoming calls to their own device.
- Dedupe on `CallSid` — FCM can deliver more than once.
- A teardown timer in the app, so a registration triggered by a push that never
  turns into an `INVITE` does not sit there holding the SIP stack up. This is
  the battery-critical one: the whole model collapses if a failed inbound
  attempt can leave the stack running.

Call that 200–300 lines of server code plus a datastore. Real, but small — and
critically, **nothing of ours is listening or running between calls**, which is
what distinguishes it from Option 2.

### Honest assessment

It works, and it is the cheapest thing that works against Twilio as it exists
today. Its ceiling is set by the fact that nobody holds the `INVITE`: we are
racing a device wake with a caller's patience, and we lose that race
occasionally in ways we cannot control (a downgraded push, an OEM battery
optimizer, a bad network moment). For a personal app where an occasional missed
inbound call falls through to voicemail, that may be entirely acceptable. As a
general product promise it is not.

---

## 8. Who can run the always-on part for us

Something, somewhere, has to stay reachable and hold the call while the phone
wakes up. The only real question is who runs it. There are four families, and
the market splits along them cleanly.

### A — CPaaS providers with their own mobile SDK

The provider keeps the connection, sends the push itself, and hands you an
Android SDK. You hold no push credentials logic, no registration, no gateway.

- **Twilio Voice SDK** — upload an FCM service-account key as a Push Credential;
  `Voice.register(...)` binds identity + token; Twilio pushes on an inbound
  call. Infrastructure of ours is an access-token endpoint **plus an inbound
  routing webhook** — a TwiML App returning `<Dial><Client>` — since
  `Voice.register(...)` says where to push but does not route a PSTN call.
- **Telnyx** — the closest thing to a drop-in for Phomo's situation, because the
  same account gives both cheap international termination *and* a push-capable
  Android SDK, and the SDK authenticates with ordinary **SIP credentials**. It
  registers the FCM token at login and supports up to **5 push tokens per user**
  (least-recently-used evicted beyond that), so a phone and a tablet can ring
  together.
- Same shape, different vendors: **Vonage, Plivo, SignalWire, Sinch,
  Voximplant**.

**Speed and reliability: the best available.** The provider owns the entire path
— it knows when the push was sent, it holds the call while the device wakes, and
it reconnects the socket on the far side. There is no race for us to lose. This
is what every mainstream mobile VoIP app actually does.

**The catch:** these SDKs are not SIP on the wire. Telnyx's and Twilio's are
WebRTC over their own WebSocket signaling. Adopting one *alongside* liblinphone
means a second native calling stack — two media engines, two audio-focus
behaviors, two Telecom integrations, a bigger APK. Adopting one *instead of*
liblinphone avoids all that but makes Phomo a client of one vendor, which is
what `SPEC.md`'s "bring-your-own trunk, nothing is Twilio-specific in the
protocol" was written to prevent.

#### Does WebRTC actually do the job? (Yes)

Since the second path — replacing liblinphone outright — is only on the table if
WebRTC is genuinely capable, it is worth answering directly.

- **Bidirectional audio: yes, natively.** WebRTC is full-duplex real-time media
  by design. It is the same RTP/SRTP media plane a SIP client uses; nothing
  about it is one-way or browser-only.
- **Reaching the PSTN: yes, routinely.** This is exactly what these products
  do — Telnyx describes its WebRTC as enabling calls between PSTN numbers,
  mobile clients, SIP endpoints, and browsers, and its Android SDK supports
  outbound calls to phone numbers and SIP addresses, with DTMF. The device
  speaks WebRTC to the provider's media server, which bridges to normal carrier
  interconnect. The far end is an ordinary phone that knows nothing about it.
- **Media quality is a strength, not a compromise.** The WebRTC stack brings
  Opus, the 3A chain (acoustic echo cancellation, automatic gain control, noise
  suppression), the NetEQ jitter buffer, FEC and packet-loss concealment, and
  mandatory ICE/STUN/TURN for NAT traversal — the same lineage that runs in
  Chrome and Meet. These are precisely the things `SPEC.md` chose liblinphone to
  avoid hand-rolling, and WebRTC's implementations are at least as good;
  liblinphone can even be built to use WebRTC's echo canceller. **On media,
  this is not a downgrade.**
- **Licensing is arguably better.** libwebrtc is BSD-licensed, against
  liblinphone's GPLv3-or-commercial. For an app that has to ship on Play, BSD is
  the less complicated of the two.

**So the real trade-off is not media quality or PSTN reach — both are fine — it
is signaling and portability.** WebRTC deliberately does not specify signaling,
which is why each vendor supplies its own WebSocket protocol and its own SDK.
That is the lock-in: changing provider stops being a settings change and becomes
a rewrite of the calling layer. Set against that, the vendor SDK hands us push,
inbound, NAT traversal, echo cancellation, registration, and the wake race, all
solved — which is a great deal of machinery we would otherwise be responsible
for.

There is a middle path worth knowing about: **SIP signaling over WebSocket
(RFC 7118) with WebRTC media**, which some servers (Kamailio, Asterisk,
Flexisip) support. It keeps standard signaling and standard portability while
using the WebRTC media stack. It is not what Telnyx's or Twilio's SDKs speak,
though, so choosing it means assembling the client ourselves — which is the
opposite of the "don't implement everything ourselves" goal.

**Where this leaves the option:** if liblinphone is not sacred, a vendor SDK
becomes a *single-stack* choice rather than a second-stack one, and most of the
objection above dissolves. What remains is portability, and that is a genuine
product question — how much is "bring your own trunk" worth, given the user will
in practice have exactly one provider? If the answer is "not much," family A
becomes the least-work option on the board by a wide margin, and it is the
option that implements the least ourselves.

### B — Hosted gateways that stay registered on your behalf

A third party holds a permanent SIP registration to *your* provider and pushes
your device when a call arrives. You keep SIP, you keep your trunk, and you run
nothing.

- **Acrobits SIPIS** ("SIP Instance Server") is the reference example: it
  registers your SIP account on the app's behalf while the app sleeps, then
  pushes the device, waits for the app to report readiness, and places its own
  SIP call to it. It is explicitly designed to work with any standards-compliant
  SIP proxy, with **no provider-side changes required** — so it would sit in
  front of Twilio, Telnyx, or anything else unchanged.
- **Mizu VoIP Push Gateway** is a comparable product, offered both as software
  and hosted.

**Speed and reliability: very good** — architecturally the same as RFC 8599,
because the gateway holds the call while the device wakes. It adds a hop and a
third party to the media/signaling path.

**The catch:** SIPIS is provided to Cloud Softphone and Acrobits SDK customers,
and the SDK is a commercial product priced by contacting sales. This family is
built for operators bundling a branded softphone, not for a personal
open-source app, and using it likely means adopting their SDK rather than
liblinphone — which lands us back in the two-stacks problem, just with a
different vendor. **Whether SIPIS can be bought on its own, for use with our own
client, is the single highest-value question to ask in this whole document**,
because a yes would give us the RFC 8599 architecture with none of the
operations.

### C — SIP providers that natively implement RFC 8599

This would be close to perfect: liblinphone already speaks the client side, so
on our side it is a flag, with no gateway and no vendor SDK.

**One prerequisite is easy to miss, and it is not a flag.** The registrar has to
be able to *send* to our device, and `pn-param` only carries the FCM project
identifier — not authorization. So either the provider securely onboards an FCM
HTTP v1 service-account key for **our** Firebase project, or the app ships with
**their** Firebase project and the token belongs to them. Neither is
technically hard, but both are a business and security process rather than a
configuration change: the first means handing a third party a credential that
can push to our users, the second means our push identity is theirs, which
undercuts the portability that made this option attractive in the first place.
Whichever a provider offers should be part of the question we ask them, not an
afterthought — and it applies equally to the rented gateways in family B.

**No mainstream PSTN trunk provider documents support for it.** Twilio does not.
Telnyx's push is tied to its SDK, not to plain SIP registration. The RFC 8599
implementations that exist in the wild — Flexisip, OpenSIPS 3.1+, Kamailio — are
server software that platforms deploy for their *own* apps, not a feature
exposed to third-party clients on a retail trunk. Linphone's own
`sip.linphone.org` supports push but is not a PSTN trunk.

This is worth a direct email to two or three providers rather than more
searching — it is exactly the kind of capability that exists but is undocumented,
and a yes collapses the whole problem.

### D — Run it ourselves

Flexisip in push-gateway mode (the mode that exists precisely for fronting a SIP
service that lacks push), OpenSIPS, or Kamailio, on a VPS. Best technical fit,
standards-based, and the client side is a liblinphone flag.

**Speed and reliability: as good as it gets** — the proxy holds the `INVITE`, so
there is no race, and we control every timer in the path.

The cost is a server whose downtime is missed calls, and the honest question is
how big that really is at this scale. A single-user push gateway is a small VM
(a few euros a month at Hetzner or similar), a TLS certificate on a renewal
timer, and a container that either runs or does not. That is meaningfully less
than "operating infrastructure" usually implies — closer to maintaining a
personal server than to running a service. Set against it: it is a thing that
can silently break while you are asleep, it holds our SIP credentials and our
Firebase key, and it makes the app's inbound path depend on our own uptime
rather than a provider's. Worth pricing honestly rather than ruling out by
reflex, particularly as it is the only route that gets us standards-based
RFC 8599 with our existing liblinphone client and no vendor SDK.

### E — A hosted Asterisk / FreePBX in the middle

A full PBX between the app and the trunks: Phomo registers to the PBX, and one
or more provider trunks terminate behind it. Worth its own entry because it
solves a problem none of the others do, and creates one none of the others have.

**What it fixes, and it is the big one.** Unlike Twilio, *our own* PBX can hold
an inbound call without answering it. An Asterisk dialplan can emit early media
with `Progress()`, play real ringback, wait while polling for the device to
register, and only then `Dial()` the endpoint. **That removes the wake race and
the early-answer trap of §7 entirely** — the two things that cap the webhook
option's reliability. It is the same property that makes RFC 8599 work, obtained
by owning the box rather than by the protocol.

**What it does not give you: push.** Asterisk has **no native RFC 8599
support** — it has been discussed in the community for years without landing, so
`res_pjsip` will accept a `REGISTER` carrying `pn-*` contact parameters but does
nothing with them. The push has to be fired from the dialplan ourselves, via
AGI/ARI or a shell-out to a script that talks to FCM, with a hand-rolled wait
loop around it. Well-trodden among FreePBX users, but it is our code, in a
dialplan, on a box we own — which is precisely the "implement it ourselves" cost
we are trying to avoid. **If we are running a server anyway, Flexisip or
Kamailio give RFC 8599 out of the box and liblinphone already speaks it**;
Asterisk gives a PBX and asks us to write the push. The documented best-of-both
is Flexisip in push-gateway mode *in front of* Asterisk.

**Where it genuinely shines: multi-provider aggregation.** §9 makes it likely
that numbers come from different providers — a UK number from one, a German from
another, US and Australian from a third, depending on who will sell to us. A PBX
makes that invisible to the app: **one registration, many trunks**, and
least-cost routing for outbound as a bonus. No other option on this list does
that; with a vendor SDK or a direct trunk, every provider is a separate
integration. Given that the numbers question may well force multiple providers,
this is a structural advantage, not a nicety. Voicemail, IVR, call recording and
blocklists also come for free — including the voicemail fallback §7 would
otherwise have to build.

**The trade-offs, and one is sharper than it first looks:**

- **"Hosted" usually still means ours.** A managed FreePBX offering or a
  one-click VPS image removes the install, not the responsibility: our config,
  our upgrades, our TLS, our monitoring, our downtime. A genuinely managed PBX
  where the vendor owns security is closer in cost to family B.
- **Toll fraud is the sharp edge.** An internet-facing Asterisk with live trunks
  behind it is a standing target for SIP brute-force and call-fraud bots, and a
  misconfiguration does not merely leak data — it places expensive international
  calls **on our account**. That is a materially worse failure mode than
  anything else here, and it is a well-known one precisely because it happens
  often. It argues for strict IP allow-listing, strong credentials, fail2ban,
  and outbound spend limits at the provider as non-optional.
- **Media flows through our box.** Unless direct media is negotiated, every call
  is relayed, which costs bandwidth and adds a hop that can hurt latency and
  audio quality — against a product whose bar for call quality is explicit.
- It is a bigger and more complex thing to operate than a push gateway. Flexisip
  does one job; Asterisk does everything, and everything is more surface.

**Verdict:** the most capable option on the board and the right answer if
multi-provider aggregation matters, since it also removes the wake race. It is
also the one that asks the most of us operationally, and the only one with a
failure mode that costs money rather than calls. If the appeal is "hold the
INVITE without running much," Flexisip (D) is the smaller version of the same
idea; if the appeal is "one place that owns all my numbers and features,"
Asterisk is the honest choice.

### Where this leaves us

Ranked by "least of our own to write and run," with the trade-off each one asks
for in exchange:

| Family | We run | Speed / reliability | Asks us to give up |
|---|---|---|---|
| **A** — CPaaS SDK (Telnyx, Twilio) | token endpoint + routing webhook | best | SIP itself; vendor lock-in (but one stack, not two) |
| **B** — rented gateway (Acrobits, Mizu) | nothing | very good | money, a third party in the path, probably their SDK too |
| **§7** — TwiML webhook + FCM | a function, token store, `/ready` endpoint | fair — loses the wake race sometimes | nothing architecturally |
| **C** — native RFC 8599 provider | nothing | best | nothing — if one exists |
| **D** — own push gateway (Flexisip) | a SIP proxy | best | the "no infrastructure" goal |
| **E** — hosted Asterisk / FreePBX | a PBX | best — it can hold the call | more ops than D, plus toll-fraud exposure; push is hand-rolled |

The honest summary: **the only options that need nothing of ours all require
giving up something architectural**, and the only option that preserves the
architecture completely (§7) is the one that needs a small service and races the
device wake. There is no free square on this board — except possibly C, which is
why it is worth an email. E buys its way out of the wake race with operational
ownership, and is the only one that also solves multi-provider numbers.

---

## 9. Getting the numbers

The push architecture assumes numbers exist to push about. Sourcing them is a
separate problem with its own answer, and since the motivating use is UK and
German numbers — with Australia and the US plausibly following — it is worth
treating properly rather than as a footnote.

### Buy from a provider — yes, and this is the right default

The assumption in the question is correct: **a provider like Twilio, Telnyx, or
Bandwidth sells numbers in many countries and absorbs the reliability and
regulatory work we would otherwise have to build.** What we are actually buying
is not the number — it is interconnection with every carrier that might call it,
per-country regulatory compliance maintained as rules change, emergency-calling
obligations, number portability, redundancy across POPs, and someone to call at
3am. None of that is replicable at our scale, and all of it is priced into a
number that costs about a dollar a month.

Coverage across the four countries in question is not the constraint. Telnyx
advertises local numbers in 140+ countries on its own carrier infrastructure,
Bandwidth (which absorbed Voxbone) covers 65+, and specialist DID wholesalers
like DIDWW publish per-country availability and regulatory requirements openly.
UK, Germany, Australia, and the US are core markets for all of them — every one
of these providers sells all four. So the question is never "who has the
country," it is **"who will sell it to *me*, given who I am and where I live."**

### What each country actually asks for

That last question is where they differ, and the spread is wide. Germany was the
original ask, but it is worth seeing it next to the alternatives, because it is
an outlier rather than typical:

| Country | Individual can buy? | What it takes | Ease |
|---|---|---|---|
| **US** | yes | Generally no address requirement to purchase; E911 registration is the real obligation | easiest |
| **Czech Republic** | yes | DIDWW lists it among the countries needing **no service registration** at all | easiest |
| **UK** | yes | Since 2024 an approved regulatory bundle per long code: a **UK** address (no PO boxes), documents issued within the last year. DIDWW also lists the UK as not requiring service registration, so requirements vary by provider — worth shopping. `056` VoIP non-geographic numbers are the traditional low-friction route | easy–moderate |
| **Australia** | yes | Personal ID plus an **Australian** address — government ID, utility bill, tax notice, rent receipt or title deed. Telnyx quotes ~72 hours to validate | easy–moderate |
| **Netherlands** | yes | Local **NL** address required; PO Box not accepted | moderate |
| **Ireland** | yes | Local **IE** address required; PO Box not accepted | moderate |
| **Belgium** | yes | Local **BE** address required (Twilio specifies address requirements for both individual and business use, across local, mobile, national and toll-free) | moderate |
| **France** | yes | Local **FR** address required; PO Box not accepted | moderate |
| **Switzerland** | yes | Local **CH** address required; PO Box not accepted. Swiss numbering rules generally expect a Swiss domicile or registered office — verify before committing | moderate–hard |
| **Germany** | **no — business entity only at Twilio** | See below | hardest |

Two things stand out. **Germany is the outlier**: everywhere else on this list an
individual can buy with a local address, while Germany (at Twilio at least) asks
for a company. And **the common requirement is simply an address in the
destination country** — which means the practical question is not "which country
is easiest" but "in which of these countries do I have an address I can
document."

If the goal is a European presence rather than specifically `+49`, the
Netherlands, Ireland, Belgium, and France are all materially easier, and the
Czech Republic and UK appear to be the lightest of all. If the goal really is
Germany, the workarounds below are the path.

These are reads of provider documentation, not legal advice, and requirements
change and are assessed case by case — treat the table as a shortlist for asking
rather than a settled answer. Note also that they vary *by provider* for the same
country, so a "no" from one is worth testing against another.

- **Germany is genuinely hard, and hard at every provider,** because German
  regulation ties a number to a subscriber established in the number's own local
  area. At Twilio, German local and mobile numbers are **not available to
  individuals at all** — they require a business entity, with a
  `Handelsregisterauszug` or `Gewerbeanmeldung` as proof, and an address that
  matches the number's area code (a Munich address will not get you a Berlin
  number). Telnyx and DIDWW publish their own German requirements in the same
  spirit; Telnyx requires proof of identity issued by the country of purchase,
  though EU customers may use any EU member state's passport or national ID.
- **German workarounds worth checking, in order of promise:** `032`
  *non-geographic* national numbers, which are not tied to a region and so
  remove the area-code-matching problem (though not the in-country requirement);
  German consumer providers like **sipgate**, **easybell**, or **Placetel**,
  which are straightforward *if* there is a German address available; and
  toll-free, which carries lighter documentation requirements than geographic
  numbers.
The pattern across all four: **the barrier is almost never the provider, it is
having a credible address in the destination country.** Where one exists,
everything is straightforward; where it does not, no amount of provider-shopping
fixes it, because they are all enforcing the same national rules.

**Practical consequence: it is cheap to check eligibility early**, since if a
German number requires a German business entity, that fact narrows the list
regardless of how good anyone's push story is. It is also worth asking how much
of the goal is specifically `+49` — a European number that is easy to buy may
serve the same purpose, and the four numbers need not come from one provider or
ship at the same time. A US and Australian number could ship while the German
one is still being negotiated.

### Could we be the carrier ourselves?

Worth asking, and the answer is no — but the reasoning is more interesting than
a flat no, because the regimes differ a lot and one of them is genuinely
approachable.

- **The UK is the most accessible.** It is a notification regime rather than a
  licensing one: a provider of a public electronic communications network or
  service can apply to Ofcom for number allocations through its Number
  Management System, using the S1–S10 application forms, and Ofcom does not
  charge for the numbers themselves. That is the closest thing to an "easy"
  country on this list.
- **Australia is next.** No carrier license is needed to be a carriage service
  provider — that license is for owning network infrastructure. But VoIP
  services that interconnect with the PSTN are carriage services, which makes
  the operator a registerable CSP with the ACMA, obliged to join the
  Telecommunications Industry Ombudsman scheme (exemptions exist but are
  case-by-case), and subject to a regime whose enforcement powers were
  deliberately sharpened in 2025.
- **Germany and the US are heavier.** Germany requires notification to the
  Bundesnetzagentur and a German establishment. The US layers FCC obligations —
  Form 499-A filing, Universal Service Fund contributions, an application for
  direct access to numbers, 911 and CPNI rules — on top of each other.

**Why it still is not worth it, even for the UK.** Getting numbers allocated is
the easy half. The obligations attached are the real cost: emergency-call access
(999/112/000/911) with caller location, number portability both directions,
regulator reporting, consumer-protection conditions, data retention, ombudsman
membership, and — the part no paperwork solves — **interconnection agreements
with the carriers whose subscribers would call the number.** A wholesaler's
actual product is that interconnect web plus the compliance staff who keep it
current in every country. Reproducing it for one person's two or four numbers
would be an enormous amount of work to arrive at a worse version of a
one-dollar-a-month DID, and it would make Phomo a regulated entity with
statutory duties — a fundamentally different thing than an app.

**One genuine exception worth noting:** if the German number turns out to be
unobtainable through normal channels and matters enough, the lightest legitimate
route is usually a German entity or a German-resident arrangement — a
regulatory problem to solve with paperwork, not a technical one to solve with
architecture. Becoming a carrier is not the workaround for a number you cannot
buy.

### SMS, while we are here

If SMS on these numbers is ever wanted, it interacts with everything above in a
way worth recording now.

SMS does not travel over SIP in any practical sense — providers deliver inbound
messages by **webhook to an HTTPS endpoint**, not to a registered SIP client.
So inbound SMS *requires* the small server that §7 describes, unconditionally
and regardless of which call architecture is chosen. That cuts two ways:

- It **strengthens the webhook option** (§7). If we need a function with a token
  store and an authenticated update endpoint for SMS anyway, then the marginal
  cost of also firing the call push from it is close to zero, and the "it needs
  a backend" objection to §7 largely evaporates.
- It **weakens the pure-provider options** for the same reason: a native
  RFC 8599 provider or a rented gateway would give us calls with no
  infrastructure, but SMS would still need the webhook — so the no-backend
  property would not survive the first SMS anyway.

There is also a happy consequence for battery: an SMS push is the same
high-priority FCM data message as a call push, arriving through the same path
and waking the same handler, so SMS costs nothing extra in the idle state. It
would, though, count against the same high-priority budget (§3), which is
another reason to make every push produce something the user actually sees.

Tracked as a post-v1 item in `TODO.md`; no decision here.

---

## 10. Twilio and Telnyx, dimension by dimension

Both providers appear throughout the sections above — Twilio for what its SIP
products do and do not do (§5), Telnyx as the most interesting of the CPaaS SDKs
(§8 A), and both for numbers (§9). This section pulls the comparison together,
because if inbound gets built the choice of provider constrains almost
everything else.

**Read the figures with suspicion.** Almost all published Twilio-vs-Telnyx
material is either written by Telnyx or is affiliate SEO content, and neither
vendor's pricing page was reachable from the sandbox, so the numbers below come
from mid-2026 secondary sources. They are good enough to shape a shortlist and
not good enough to sign a contract on. Verify against the vendors' own rate
sheets before committing.

**And read the percentages as absolute money.** Vendor comparisons quote ratios
because ratios flatter: "60% cheaper" is transformative at 100,000 minutes a
month and close to meaningless at personal-use volume. A few hundred
international minutes a month puts the gap between these two providers at
roughly the price of a coffee — less than the Play Console fee amortized over a
year. **Whatever else decides this, price probably should not**, unless usage
turns out to be far higher than a single user's.

**A second pricing trap, specific to the SDK route.** The rows below compare
*trunk* rates. SDK-originated calls are frequently priced on a different card
(Twilio Client-to-PSTN and Elastic SIP Trunking are separate products with
separate rates), so if Phomo goes the way of §6 Option 6, none of the per-minute
figures here are the ones that would actually be billed. Re-price against the
SDK rate card before drawing any conclusion from them.

| Dimension | Twilio | Telnyx | Edge |
|---|---|---|---|
| **US outbound / min** | ~$0.014 Programmable Voice; ~$0.013 elastic trunk | ~$0.005–0.007 blended | **Telnyx**, roughly half |
| **US inbound / min** | ~$0.0085; ~$0.0015 trunk | ~$0.001–0.0035 | Telnyx |
| **Network** | Resells carrier capacity (largely Bandwidth in the US) with an API layer on top | Owns a private global IP network; licensed carrier in many markets | **Telnyx**, structurally |
| **Latency** | Public-internet hops between components; more variance | Claims sub-100 ms p95 SIP latency on its own backbone | Telnyx — *vendor-sourced claim* |
| **Call quality** | Opus / G.711 / G.722; `Call.getStats()` exposes MOS, jitter, packet loss and RTT, plus live quality-warning callbacks, plus **Voice Insights** server-side analytics | Same codecs, plus codec-preference ordering; SDK exposes call-quality metrics | **Twilio** — both give per-call telemetry, Twilio adds warnings and a whole analytics product |
| **Operating history** | Enormous scale, long public status record | Fewer public incidents but far less history at scale | **Twilio** |
| **Number coverage** | 100+ countries; better for the exotic (Brazil, UAE, Singapore toll-free) | 140+ claimed; strongest in US/CA/UK/AU/DE/FR/NL | Twilio for breadth; a tie for the four countries in §9 |
| **Android SDK** | Voice SDK: identity + Push Credential, FCM | WebRTC SDK: **SIP credentials**, FCM, 5 push tokens/user, hold/mute, ringback, trickle ICE, codec preference, call-quality metrics | Telnyx |
| **Markup** | TwiML | **TeXML — deliberately TwiML-compatible** | Telnyx |
| **Voicemail** | No built-in product; `<Record>` + TwiML Bins | No built-in product; `<Record>` + TeXML Bins, with a documented voicemail recipe | Tie |
| **Support** | Paid tiers | Free 24/7, in-house telecom specialists | **Telnyx**, and it matters here |
| **Ecosystem / docs** | Largest by a wide margin | Smaller community, thinner third-party material | **Twilio** |

### The four that actually decide it for Phomo

**1. The US per-minute comparison is nearly irrelevant to us.** Every published
benchmark quotes US rates, but Phomo exists to call *overseas*. The bill is set
by the per-destination termination rate to the specific countries the user
calls, and those vary enormously by destination and do not track the US headline
at all. "Telnyx is 60% cheaper on US outbound" says almost nothing about the
rate to a UK mobile. **Pull the actual rate sheets for the top five
destinations** and compare those; treat everything else in the pricing rows
above as noise.

**2. TeXML changes the lock-in calculus — but only halfway.** Telnyx's TeXML is
deliberately TwiML-compatible, to the point that existing TwiML is claimed to
run unmodified. That matters directly for §7: the cloud function we would write
is close to portable between the two, so at the *markup* layer the provider
choice is nearly reversible. It does **not** rescue the SDK layer — Twilio's
Voice SDK and Telnyx's WebRTC SDK are entirely different clients, and that is
where the lock-in in §8 A actually lives.

**3. Telnyx's SDK fits a *hybrid* architecture better — but only a hybrid
one.** It authenticates with **ordinary SIP credentials**, so a single account
and one credential set could cover both the trunk (outbound, liblinphone today)
and the push-woken SDK path (inbound). Twilio's Voice SDK uses a separate
identity + Push Credential model that does not line up with a SIP domain at all.
**This argument evaporates if SIP is dropped entirely** (§6 Option 6): with no
trunk to unify with, "SIP credentials" is just a login form, and the two vendors
are equivalent on this dimension. It is the single most conditional point in
this section, and it was previously stated as though it applied unconditionally.
Its SDK also exposes call-quality metrics, which matter against `AGENTS.md`'s
call-quality bar — though **this is not a Telnyx advantage**, and an earlier
draft of this section wrongly implied it was. Twilio's Voice SDK offers the same
telemetry through `Call.getStats()` (MOS, jitter, packet loss, RTT), adds live
quality-warning callbacks for high jitter, high packet loss and low MOS, and
backs it with Voice Insights server-side. On measuring audio quality, Twilio is
ahead, not behind.

**4. Free 24/7 support is worth more to this project than to a company.** Phomo
has one developer and no ops. When a call fails at 11pm on a real device against
a real trunk — the exact class of problem this whole document defers to
on-device testing — Twilio's answer is a support tier you pay for. That
asymmetry favors Telnyx more than the pricing does.

### Where Twilio still wins

Operating history, ecosystem depth, and — as the table above now records —
call-quality telemetry. When something breaks at 2am, the odds
that someone has already written up the exact symptom are much higher with
Twilio, and for a project whose hardest problems will be device- and
carrier-specific that is not a small thing. Twilio is also the safer choice if
the number catalog ever needs somewhere unusual.

### Verdict

**The answer depends on whether Phomo stays on SIP.**

- **If it does** (a SIP trunk for outbound, a push path for inbound), Telnyx
  looks like the better fit: cheaper, SIP credentials that unify both legs, free
  support, TwiML-compatible markup — against Twilio's ecosystem and call-quality
  telemetry.
- **If it does not** (§6 Option 6, one vendor SDK for everything), the case
  narrows sharply. The SIP-credentials argument disappears, the price advantage
  is small in absolute terms at one user's volume, and what remains is Telnyx's
  owned network and free support against Twilio's deeper ecosystem, better
  telemetry and longer operating history. **On that reading it is close to a
  toss-up, and arguably leans Twilio.**

Either way, treat it as a hypothesis rather than a conclusion. Three things
could overturn it, and none of them is more analysis:

1. **The per-destination rates** for the countries actually called — on the
   *right* rate card, trunk or SDK, per the trap noted above.
2. **Whether either will sell the numbers** in §9, given the address situation.
   Number eligibility could decide this before any technical dimension does.
3. **How each SDK actually sounds** on a real device. Both quickstarts are small;
   one real call on each would settle more than this section can, and audio
   quality is the dimension least visible from documentation.

How binding the choice is depends on the route. On the SIP route it is barely
binding at all — outbound is plain SIP against an elastic trunk that either
provider serves, and TeXML compatibility makes the webhook portable too. On the
Option 6 route it is the most binding decision in the project, because the
calling layer is written against one vendor's SDK.

---

## 11. Comparison

| | Idle battery | Our infrastructure | Reliability | Standards | Fit with `SPEC.md` |
|---|---|---|---|---|---|
| **0. Outbound-only** | none | none | n/a | n/a | as designed |
| **1. Persistent registration** | costly, unmeasured | none | poor (Doze/OEM) | plain SIP | contradicts today's model; scoped variants soften it |
| **2. RFC 8599 + own proxy** | low, unmeasured — refresh pushes | SIP proxy (VPS, ops) | best — request is held | RFC 8599 | fits, but adds a backend |
| **3. Webhook fires push** | none | function + token store + auth'd update endpoint | fair — wake race | non-standard | fits, adds a small backend |
| **4. Twilio Voice SDK** | none | token endpoint + inbound routing webhook | good — Twilio owns it | proprietary | breaks "no Twilio-specific protocol" |

---

## 12. Is there a way to get everything?

Everything, for this feature, means all seven of: nothing of ours to run, no
meaningful cost, no idle battery drain, a phone that rings promptly, a phone
that rings *every* time, still a standard SIP app with one media stack, and UK
and German numbers we can actually buy.

**The sixth item is a want, not a requirement** — and that changes the answer
more than anything else in this document. If "still a SIP app" is dropped, as
§6 Option 6 argues it reasonably can be, then a vendor SDK already delivers the
other six *today*, from vendors known to exist, with less to build than any
other route here. The hunt below for a provider that gives all seven is only
worth running if portability is worth something. If it is not, the answer is
already available and the rest of this section is moot.

**Yes — one shape gets all seven, and it is family C: a PSTN provider whose own
registrar implements RFC 8599.** Then liblinphone's existing push support does
the client side (a flag, not a feature), the provider's proxy holds the `INVITE`
while the phone wakes so there is no race, nothing of ours runs anywhere, the
device holds no timer because the server drives binding refresh, and we stay a
plain SIP app with our existing stack. It is not a compromise; it is simply the
feature working as designed.

Two asterisks on "nothing of ours." The provider has to be able to push to our
device, which means onboarding FCM credentials for our Firebase project or
lending us theirs (§8 C) — a business process, not a config field. And their
server has to schedule binding-refresh pushes sensibly, since a silent
high-priority refresh is exactly what Android's downgrade heuristic punishes
(§2). Both are questions to ask, not reasons to discount the option.

The only thing standing between us and it is that **we do not know whether such
a provider exists that also sells the numbers we want.** No mainstream trunk
provider documents RFC 8599 support, but "undocumented" is weak evidence — the
implementations exist (Flexisip, OpenSIPS, Kamailio) and plenty of providers run
them. Two or three emails settle it, and the answer is worth far more than more
searching. **A close second is family B if Acrobits will license SIPIS
standalone**: everything on the list except "no meaningful cost," since the
gateway holds the call and works with any standards-compliant SIP proxy.

Two caveats on "everything," both worth knowing before chasing it:

- **It assumes we want to stay a SIP app.** If portability across providers
  turns out not to be worth much — and with one user and one provider it may not
  be — then family A already delivers six of the seven today, from vendors we
  know exist, with the least implementation work of anything on the board. The
  "everything" answer is only strictly better if the seventh item matters.
- **It assumes calls only.** Add SMS and no option keeps the
  no-infrastructure property (§9), because inbound messages arrive by webhook
  rather than to a SIP client.

So the first move is not to choose a trade-off — it is to find out whether one
is required at all, and to decide how much the portability item is actually
worth, since that single judgment reorders everything below.

### If a trade-off is required, here is the frontier

None of the dials below is pass/fail. They are all things to spend, and the
right amount to spend on each depends on what this number is *for*. That matters
because **a secondary UK or German number is a much gentler brief than replacing
the SIM.** If the fallback for a missed inbound call is voicemail, then a
four-second ring delay is invisible and a 97%-of-the-time delivery rate is
genuinely fine. The same numbers would be unacceptable for a primary line. Worth
deciding which one this is before optimizing anything, because it changes which
options are even distinguishable.

The dials, and what each option spends:

| | We run | We implement | Money | Idle battery | Ring latency | Delivery reliability | Portable off the provider | Numbers |
|---|---|---|---|---|---|---|---|---|
| **C** — native RFC 8599 provider | nothing | a flag, **plus onboarding FCM credentials with them** | trunk only | low, unmeasured | best | best | yes | depends who |
| **B** — rented gateway (SIPIS, Mizu) | nothing | their SDK, probably, **plus the same credential onboarding** | subscription | low, unmeasured | best | best | yes | free choice |
| **A** — CPaaS SDK (Telnyx, Twilio) | token endpoint + inbound routing webhook | their SDK — but **one stack, not two** | trunk only | none | best | best | **no** | their catalog |
| **D** — own push gateway | a small VM | a liblinphone flag | a few €/mo | low, unmeasured | best | best, but our uptime | yes | free choice |
| **E** — hosted Asterisk / FreePBX | a PBX | the push, in a dialplan | VM + our time | none — no binding held | best — holds the call | best, but our uptime | yes | **many providers at once** |
| **§7** — TwiML webhook + FCM | a function, token store, `/ready` endpoint | the inbound call path ourselves | ~free | none | fair — wake race | fair — loses some | yes | free choice |
| **1-scoped** — register while charging / on Wi-Fi | nothing | a registration policy | trunk only | moderate, bounded | best when up | reachable only sometimes | yes | free choice |
| **0** — outbound only | nothing | nothing | none | none | n/a | n/a | yes | n/a |

Reading it across: **A, B, C, and D all give the same excellent latency and
reliability**, because in every one of them something stays reachable and holds
the call. They differ only in what they charge for it — money, infrastructure,
or portability. §7 is the one that is cheap in all three currencies and pays in
latency and reliability instead.

**On idle battery, note that "none" is not quite free for the options that hold
a binding.** B, C and D keep a server-side registration alive, and even with
refresh pushes at normal priority (§2) the device still wakes to handle each one
and completes a `REGISTER` round trip — real CPU and radio, just far less than a
30-second keep-alive, and unmeasured until we know a provider's refresh cadence.

The genuinely zero-idle options are the ones that hold **no binding at all**
between calls, and there are three: §7's webhook, which registers on demand
exactly as the outbound path already does; the vendor SDK, whose registration is
a long-lived address rather than a refreshed lease; and **E as described in
§8** — the Asterisk dialplan pushes only once an inbound call has arrived and
then waits for the device to register, so nothing is bound between calls. E
could of course be built the other way, with a standing registration to the PBX,
but that would be a different design with a different battery cost, and it is
not the one §8 describes.

That is a small point in favor of the register-on-demand shapes that the rest of
this document does not otherwise make. The scoped-registration variant pays in
*availability* instead — reachable at home and at your desk, voicemail
elsewhere — while costing nothing at all, which is an under-rated trade if the
number is secondary.

**Two findings have moved the balance since this table was first drawn.**

- **If liblinphone is not sacred, family A stops being a second stack.** The
  "two media engines" objection was the main cost of a vendor SDK, and it only
  applies if we keep liblinphone alongside it. WebRTC does bidirectional audio
  and reaches the PSTN perfectly well (§8 A), its media processing is at least
  as good, and its license is friendlier — so replacing liblinphone outright is
  a real single-stack option. What remains is portability, and that is a product
  question rather than a technical one: how much is "bring your own trunk" worth
  when the user will in practice have exactly one provider? **Family A is the
  option that implements the least ourselves by a wide margin.**
- **If SMS is ever wanted, everyone needs a backend** (§9). Inbound SMS arrives
  by webhook, never to a SIP client, so the no-infrastructure property of B and
  C does not survive the first text message. That narrows the gap between §7 and
  the paid options considerably — if a small service exists anyway, §7's
  marginal cost is close to zero.

- **A third factor: how many providers the numbers force on us.** If the UK,
  German, Australian and US numbers end up at different vendors — quite likely,
  given §9 — then every option except a PBX (E) means a separate integration per
  provider, while E hides all of them behind one registration. That is a
  structural argument that only appears once the numbers question is answered,
  which is another reason to answer it early.

Put together: the ranking depends on how much portability is worth, whether SMS
is in scope, and how many providers the numbers force. High portability and no
SMS favors C, then B. Low portability favors A outright. SMS in scope favors §7,
because the server it needs is a server we would be running regardless. Multiple
providers favors E, which is also the only self-run option that removes the wake
race — at the price of being the only one whose failure mode is a fraudulent
phone bill rather than a missed call.

### Things worth exploring that this document has not costed

- **What the persistent-registration battery cost actually is.** It is assumed
  to be bad and never measured. A day on a real device with a scoped variant
  would tell us, and if it turns out to be small, the simplest design on the
  board becomes viable.
- **Whether latency even matters at the values we would see.** If the webhook
  path (§7) rings in 3–4 seconds, that is within normal PSTN setup time and no
  one notices. The measurement may dissolve the concern rather than confirm it.
- **Combining rather than choosing.** Scoped registration when it is cheap to be
  registered, push-woken when it is not, is strictly better than either alone
  and costs one more state in the machine.
- **Which European countries are easy.** Germany looks like the hardest number
  in Europe to buy as an individual (§9). If the goal is "a European presence"
  rather than specifically `+49`, it is worth checking what a Netherlands,
  Irish, or Austrian number costs in paperwork before accepting Germany's.
- **Whether the UK and German numbers even need the same architecture.** They do
  not have to be the same provider, and a UK number that is easy to buy could
  ship first while the German one is still being negotiated.

### Sequencing, if this gets built

Inbound is the milestone after outbound works — none of it is worth
destabilizing the calling path before a real call has been placed on a real
device. When it does start:

1. Ask the family-C and family-B questions (a few emails). A yes to either
   collapses the whole problem.
2. Establish UK and German number eligibility in parallel, since it constrains
   the provider list independently.
3. Measure the two unknowns — FCM delivery latency to a Dozing device and
   wake-to-registered time — on a real Pixel and a real Samsung, on mobile data.
   That tells us whether §7 is indistinguishable from the paid options or
   meaningfully worse.
4. Whatever is chosen, note that latency and delivery are separate measurements.
   Wake-to-registered only measures the pushes that *arrive*; priority
   downgrade (§3), token rotation (§3), and OEM deferral (§14) each fail as "the
   phone never rang" and need their own observation over weeks. A visible
   registration-health indicator is worth building either way, so a silently
   dead push path is something the user can see rather than discover by missing
   a call.

## 13. What would have to change if we adopt any of these

- `SPEC.md` — "Product shape" (outbound-only is a load-bearing claim),
  "Registration lifecycle" (the battery model gains a push-woken path), and
  "Permissions and roles" (full-screen intent joins the list, gated on
  `canUseFullScreenIntent()`).
  **`POST_NOTIFICATIONS` is *not* a gate on the incoming-call UI**, which is
  worth stating explicitly so nobody adds one: an app that declares
  `MANAGE_OWN_CALLS`, implements `ConnectionService`, and calls
  `registerPhoneAccount()` — all three of which Phomo does — is exempt from the
  notification permission for `Notification.CallStyle` notifications. Phomo
  should therefore never make inbound calling appear unavailable because the
  permission was denied. It is still needed for anything *outside* that
  exemption: missed-call notifications, and the registration-health indicator
  §12 asks for.
- `PRIVACY.md` — Google receives a push per inbound call; Options 2 and 3 add a
  server of ours that holds a Firebase service-account key.
- The manifest gains a `FirebaseMessagingService`, and the app gains a Firebase
  dependency that is currently inert (`SPEC.md` → "Distribution and
  versioning": telemetry is gated on a `google-services.json` that is not
  checked in). Inbound would make a real Firebase project mandatory rather than
  optional.
- The call state machine gains an inbound entry point, and the new races —
  push arriving with no credentials configured, push arriving while a cellular
  call is active, push arriving after the caller hung up, a stale `pn-prid` —
  all need to be unit-testable in the same pure-logic style as
  `SipCallMachine`.

## 14. Open questions

Ordered by how much each one would change the plan.

- **Would a provider hold FCM credentials for our Firebase project, or make us
  use theirs?** (§8 C) This decides whether families B and C are actually
  reachable, and the second answer costs us the portability that made them
  attractive.
- **How does a candidate provider schedule binding-refresh pushes?** (§2) A
  naive high-priority silent refresh would degrade the call pushes we care
  about.
- **What are the per-destination termination rates** to the countries actually
  called, at each candidate provider? (§10) Every published comparison quotes US
  rates, which are close to irrelevant for an app that exists to call overseas.
- **Can we actually get the numbers, and from whom?** (§9) The
  legal answer decides the provider, which constrains everything else. German
  local numbers appear to be closed to individuals at the major CPaaS providers.
- **Does any PSTN trunk provider implement RFC 8599 for third-party clients?**
  Undocumented is not the same as unsupported; worth asking directly.
- **Can Acrobits SIPIS be licensed on its own**, for use with our own
  liblinphone-based client rather than their SDK? A yes gives us the RFC 8599
  architecture with zero operations.
- **Wake-to-registered latency**, and FCM delivery latency to a Dozing device,
  on Pixel and Samsung, on mobile data and Wi-Fi. Everything in §7 hinges on
  this and it cannot be measured in the sandbox.
- How aggressively does the FCM priority-downgrade heuristic bite for an app
  with very low call volume — does a phone that receives two calls a week keep
  its high-priority standing?
- Does an OEM battery optimizer (Samsung's especially) defer FCM delivery for an
  app the user rarely opens, and does a battery-exemption prompt become
  necessary? If it does, that is a real cost: asking the user to disable battery
  optimization sits awkwardly against a product whose pitch is battery
  efficiency.
- Does Twilio's SIP Domain registrar preserve or reject unknown `Contact` URI
  parameters? (Relevant only for a hybrid.)
- What does a rented gateway actually cost per month at one user? Neither
  Acrobits nor Belledonne publishes pricing.

## 15. Verification status

Nothing in this document has been verified against a live call, and no code
changed. It is a literature and API review: RFC 8599, the Android FCM /
foreground-service / Telecom documentation, Twilio's SIP and Voice SDK
documentation, Telnyx's Android SDK and DID-requirement documentation,
Acrobits' SIPIS documentation, and Flexisip's push-gateway documentation.

The sandbox has no radio, no microphone, and no SIP peer, so the two
load-bearing numbers — FCM delivery latency and wake-to-registered time — are
unmeasured, and every reliability claim above is an inference from
documentation rather than an observation. The regulatory summaries in §9 are
read from provider documentation and are a starting point for asking, not legal
advice; number eligibility is exactly the kind of thing that changes and that
providers assess case by case.

## Sources

- [RFC 8599: Push Notification with the Session Initiation Protocol (SIP)](https://www.rfc-editor.org/rfc/rfc8599.html)
- [Restrictions on starting a foreground service from the background — Android Developers](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Foreground service types are required — Android Developers](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Build a calling app with core-telecom — Android Developers](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom)
- [Set and manage Android message priority — Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging/android-message-priority)
- [Full-screen intent limits — Android Open Source Project](https://source.android.com/docs/core/permissions/fsi-limits)
- [Notification runtime permission — Android Developers](https://developer.android.com/develop/ui/views/notifications/notification-permission) (the `CallStyle` exemption for self-managed calling apps)
- [SIP registration — Twilio](https://www.twilio.com/docs/voice/api/sip-registration)
- [Elastic SIP Trunking — Twilio](https://www.twilio.com/docs/sip-trunking)
- [32009: The user you tried to dial is not registered with the corresponding SIP Domain — Twilio](https://www.twilio.com/docs/api/errors/32009)
- [Managing Push Credentials — twilio/voice-quickstart-android](https://github.com/twilio/voice-quickstart-android/blob/master/Docs/manage-push-credentials.md)
- [Voice Android SDK FAQ — Twilio](https://www.twilio.com/docs/voice/sdks/android/faq)
- [Flexisip — Linphone](https://www.linphone.org/en/flexisip-sip-server/)
- [Interest in implementing SIP push notification — Asterisk Community](https://community.asterisk.org/t/interest-in-implementing-sip-push-notification/75563)
- [res_pjsip — Asterisk documentation](https://docs.asterisk.org/Latest_API/API_Documentation/Module_Configuration/res_pjsip/)
- [Ofcom telecoms numbering](https://www.ofcom.org.uk/phones-and-broadband/phone-numbers/numbering)
- [Carrier licensing guide — ACMA](https://www.acma.gov.au/sites/default/files/2025-10/Carrier%20licensing%20guide_October%202025.pdf)
- [Join the TIO scheme](https://www.tio.com.au/joining-scheme)
- [telnyx-webrtc-android — WebRTC to PSTN, DTMF, push](https://github.com/team-telnyx/telnyx-webrtc-android)
- [Australia DID requirements — Telnyx](https://support.telnyx.com/en/articles/3505912-australia-did-requirements)
- [Regulatory requirements — DIDWW](https://www.didww.com/resources/regulatory-requirements)
- [Push notifications — Linphone SDK wiki](https://wiki.linphone.org/xwiki/wiki/public/view/Lib/Features/Push%20notifications/)
- [SIP Push Notification with OpenSIPS 3.1 LTS (RFC 8599 support)](https://blog.opensips.org/2020/06/03/sip-push-notification-with-opensips-3-1-lts-rfc-8599-supportpart-ii/)

Provider comparison (§10) — note that much of the published Twilio-vs-Telnyx
material is vendor-authored or affiliate SEO, and figures are mid-2026
secondary sources rather than the vendors' own rate sheets:

- [Telnyx vs Twilio Voice API: pricing, latency, and global coverage — Telnyx](https://telnyx.com/resources/telnyx-vs-twilio-which-voice-api-is-better) (vendor)
- [Telnyx vs Twilio for elastic SIP trunking — Telnyx](https://telnyx.com/resources/telnyx-vs-twilio-sip-trunking) (vendor)
- [TeXML / TwiML compatibility — Telnyx developers](https://developers.telnyx.com/docs/voice/programmable-voice/texml-twiml-compatibility)
- [TeXML Bin: simple voicemail and call forwarding — Telnyx](https://support.telnyx.com/en/articles/13386198-texml-bin-simple-voicemail-and-call-forwarding)
- [Telnyx vs Twilio: features, pricing, and support — Plivo](https://www.plivo.com/blog/telnyx-vs-twilio/) (a third vendor, so biased differently)
- [Voice coverage — Twilio](https://www.twilio.com/en-us/voice/coverage)
- [Voice Android SDK 5.3 — network and audio warnings API, MOS — Twilio](https://www.twilio.com/en-us/changelog/voice-android-sdk-5-3---network-and-audio-warnings-api--mos--and)
- [Voice Insights call summary — Twilio](https://www.twilio.com/docs/voice/voice-insights/call-summary)
- [TwiML Voice: `<Dial>` — Twilio](https://www.twilio.com/docs/voice/twiml/dial)

Providers and hosted infrastructure (§8):

- [Notification quickstart for Android — Telnyx](https://developers.telnyx.com/docs/voice/webrtc/android-sdk/push-notification/quickstart)
- [telnyx-webrtc-android — GitHub](https://github.com/team-telnyx/telnyx-webrtc-android)
- [SIP Instance Server (SIPIS) — Acrobits](https://doc.acrobits.net/server-components/sipis/overview/)
- [About Acrobits push notifications](https://faq.acrobits.net/about-push-notifications)
- [Acrobits SIP Mobile SDK](https://doc.acrobits.net/sdk/)
- [VoIP Push Notifications Gateway — Mizu](https://www.mizu-voip.com/Software/VoIPPushGateway.aspx)

Numbers and regulatory (§9):

- [Germany: regulatory guidelines — Twilio](https://www.twilio.com/en-us/guidelines/de/regulatory)
- [United Kingdom: regulatory guidelines — Twilio](https://www.twilio.com/en-us/guidelines/gb/regulatory)
- [New KYC regulation for UK long codes — Twilio](https://www.twilio.com/en-us/changelog/new-kyc-regulation-for-the-uk-long-codes)
- [Germany DID requirements — Telnyx](https://support.telnyx.com/en/articles/1311450-germany-did-requirements)
- [United Kingdom DID requirements — Telnyx](https://support.telnyx.com/en/articles/1311457-united-kingdom-uk-did-requirements)
- [Regulatory requirements for phone numbers in Germany — DIDWW](https://www.didww.com/resources/regulatory-requirements/Germany)
