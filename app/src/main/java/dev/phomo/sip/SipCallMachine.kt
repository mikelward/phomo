package dev.phomo.sip

/**
 * Pure, framework-independent state machine for a single **outbound** SIP call,
 * driven register-on-demand and torn all the way down when the call ends.
 *
 * This is the correctness core of Phomo's calling path (see `SPEC.md` →
 * "Registration lifecycle"). It owns *what should happen* — when to register,
 * when to place the INVITE, and, crucially, that every ending (success,
 * failure, remote hangup, user hangup) routes back to [CallPhase.Idle] through
 * a full [SipEffect.Teardown], so the app never sits holding a live SIP stack
 * (and its wakelock) while dormant. The concrete liblinphone binding is a thin
 * executor of the [SipEffect]s this machine emits and a translator of the
 * SDK's callbacks into [SipEvent]s; it holds no lifecycle logic of its own, so
 * the logic here can be unit-tested exhaustively with no device, radio, mic, or
 * SIP peer.
 *
 * The machine is a pure function of (state, event); [SipCallMachine] is a thin
 * stateful wrapper for callers that prefer to feed events one at a time.
 */

/** Observable phase of the outbound call. [Idle] is dormant — no SIP resources held. */
enum class CallPhase {
    /** No call in progress; the SIP stack is not running. Resting/battery state. */
    Idle,

    /** A call was requested; the stack is up and REGISTER is in flight. */
    Registering,

    /** Registered; the INVITE has been sent and we're awaiting a response. */
    Dialing,

    /** The far end is ringing (or sending early media). */
    Ringing,

    /** The call is answered and media is running. */
    Connected,

    /** We asked to hang up (CANCEL/BYE) and are waiting for the call to end. */
    TerminatingCall,

    /** The call has ended; the SIP stack is being unregistered and destroyed. */
    TearingDown,
}

/** Inputs to the machine: user actions plus the SDK's registration/call callbacks. */
sealed interface SipEvent {
    /** User initiated an outbound call to [target] (only meaningful from [CallPhase.Idle]). */
    data class PlaceCall(val target: String) : SipEvent

    /** Registration completed successfully (SDK RegistrationState.Ok). */
    data object RegistrationSucceeded : SipEvent

    /** Registration failed or timed out (SDK RegistrationState.Failed). */
    data class RegistrationFailed(val reason: String) : SipEvent

    /** The far end is ringing / early media (SDK OutgoingRinging / OutgoingEarlyMedia). */
    data object RemoteRinging : SipEvent

    /** The call was answered and media is flowing (SDK Connected / StreamsRunning). */
    data object CallConnected : SipEvent

    /** The call ended normally — remote hangup, decline, or our CANCEL/BYE landing (SDK End). */
    data object CallEnded : SipEvent

    /** The call failed — busy, unreachable, no answer, media error (SDK Error). */
    data class CallFailed(val reason: String) : SipEvent

    /** The user asked to hang up / cancel. */
    data object HangUp : SipEvent

    /** The SIP stack finished unregistering and shutting down; we are dormant again. */
    data object TeardownComplete : SipEvent
}

/** Commands the machine asks the binding layer to perform. The binding never decides these itself. */
sealed interface SipEffect {
    /** Bring the SIP stack up and REGISTER, so a call to [target] can follow once registered. */
    data class StartCoreAndRegister(val target: String) : SipEffect

    /** Send the INVITE to [target]. */
    data class PlaceCall(val target: String) : SipEffect

    /** Hang up the in-progress call (CANCEL if not yet answered, BYE if answered). */
    data object TerminateCall : SipEffect

    /** Unregister and destroy the SIP stack, returning to the dormant state. */
    data object Teardown : SipEffect

    /** Report the final outcome of the call attempt to the UI. */
    data class ReportOutcome(val outcome: CallOutcome) : SipEffect
}

/** How a call attempt finished, for surfacing to the user. */
sealed interface CallOutcome {
    /** The call reached its natural end (it may or may not have been answered). */
    data object Ended : CallOutcome

    /** The user aborted before the call was placed (e.g. during registration). */
    data object Canceled : CallOutcome

    /** The attempt failed; [reason] is a short human-readable cause. */
    data class Failed(val reason: String) : CallOutcome
}

/** Immutable machine state: the [phase] plus the [target] being called (retained across REGISTER). */
data class SipState(
    val phase: CallPhase = CallPhase.Idle,
    val target: String? = null,
)

/** The result of feeding one event: the next [state] and the [effects] to execute, in order. */
data class SipTransition(
    val state: SipState,
    val effects: List<SipEffect> = emptyList(),
)

/**
 * Pure transition function. Unknown/stray events for a given phase are no-ops
 * (state unchanged, no effects) — this is deliberate: late or duplicated SDK
 * callbacks must never move a dormant or already-tearing-down machine.
 */
fun sipTransition(state: SipState, event: SipEvent): SipTransition = when (state.phase) {
    CallPhase.Idle -> when (event) {
        is SipEvent.PlaceCall -> SipTransition(
            SipState(CallPhase.Registering, event.target),
            listOf(SipEffect.StartCoreAndRegister(event.target)),
        )
        else -> SipTransition(state)
    }

    CallPhase.Registering -> when (event) {
        is SipEvent.RegistrationSucceeded -> {
            val target = state.target
            if (target == null) {
                // Defensive: registered with no pending target — tear down rather than hang.
                tearDown(CallOutcome.Failed("no dialing target"))
            } else {
                SipTransition(state.copy(phase = CallPhase.Dialing), listOf(SipEffect.PlaceCall(target)))
            }
        }
        is SipEvent.RegistrationFailed -> tearDown(CallOutcome.Failed(event.reason))
        SipEvent.HangUp -> tearDown(CallOutcome.Canceled)
        else -> SipTransition(state)
    }

    CallPhase.Dialing, CallPhase.Ringing -> when (event) {
        SipEvent.RemoteRinging -> SipTransition(state.copy(phase = CallPhase.Ringing))
        SipEvent.CallConnected -> SipTransition(state.copy(phase = CallPhase.Connected))
        SipEvent.HangUp -> SipTransition(
            state.copy(phase = CallPhase.TerminatingCall),
            listOf(SipEffect.TerminateCall),
        )
        is SipEvent.CallFailed -> tearDown(CallOutcome.Failed(event.reason))
        SipEvent.CallEnded -> tearDown(CallOutcome.Ended)
        else -> SipTransition(state)
    }

    CallPhase.Connected -> when (event) {
        SipEvent.HangUp -> SipTransition(
            state.copy(phase = CallPhase.TerminatingCall),
            listOf(SipEffect.TerminateCall),
        )
        is SipEvent.CallFailed -> tearDown(CallOutcome.Failed(event.reason))
        SipEvent.CallEnded -> tearDown(CallOutcome.Ended)
        else -> SipTransition(state)
    }

    CallPhase.TerminatingCall -> when (event) {
        // Our CANCEL/BYE landed (or the call errored out while ending); now shut the stack down.
        SipEvent.CallEnded -> tearDown(CallOutcome.Ended)
        is SipEvent.CallFailed -> tearDown(CallOutcome.Ended)
        else -> SipTransition(state)
    }

    CallPhase.TearingDown -> when (event) {
        SipEvent.TeardownComplete -> SipTransition(SipState(CallPhase.Idle))
        else -> SipTransition(state)
    }
}

/**
 * Enter [CallPhase.TearingDown]: unregister + destroy the stack and report [outcome].
 * The target is cleared so the machine returns to a clean dormant state.
 */
private fun tearDown(outcome: CallOutcome): SipTransition = SipTransition(
    SipState(CallPhase.TearingDown, target = null),
    listOf(SipEffect.Teardown, SipEffect.ReportOutcome(outcome)),
)

/**
 * Thin stateful wrapper over [sipTransition]. Not thread-safe: the binding
 * layer is expected to funnel all events through a single dispatcher/thread.
 */
class SipCallMachine(initial: SipState = SipState()) {
    var state: SipState = initial
        private set

    /** Feed one [event]; applies the transition and returns the effects to execute. */
    fun dispatch(event: SipEvent): List<SipEffect> {
        val next = sipTransition(state, event)
        state = next.state
        return next.effects
    }

    val phase: CallPhase get() = state.phase
}
