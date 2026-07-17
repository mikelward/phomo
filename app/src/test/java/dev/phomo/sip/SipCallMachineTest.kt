package dev.phomo.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive coverage of the outbound-call state machine. These are the
 * correctness guarantees the calling path leans on — routing every ending back
 * to a fully torn-down [CallPhase.Idle] and never acting on stray callbacks —
 * and they run with no device, radio, mic, or SIP peer.
 */
class SipCallMachineTest {

    private fun machine() = SipCallMachine()

    @Test
    fun idle_placeCall_registersForThatTarget() {
        val m = machine()
        val effects = m.dispatch(SipEvent.PlaceCall("+441234"))
        assertEquals(CallPhase.Registering, m.phase)
        assertEquals("+441234", m.state.target)
        assertEquals(listOf(SipEffect.StartCoreAndRegister("+441234")), effects)
    }

    @Test
    fun happyPath_ringThenAnswerThenUserHangUp_endsDormant() {
        val m = machine()
        m.dispatch(SipEvent.PlaceCall("+441234"))

        // Registration completes → INVITE is placed.
        assertEquals(listOf(SipEffect.PlaceCall("+441234")), m.dispatch(SipEvent.RegistrationSucceeded))
        assertEquals(CallPhase.Dialing, m.phase)

        // Ringback, then answer.
        assertEquals(emptyList<SipEffect>(), m.dispatch(SipEvent.RemoteRinging))
        assertEquals(CallPhase.Ringing, m.phase)
        assertEquals(emptyList<SipEffect>(), m.dispatch(SipEvent.CallConnected))
        assertEquals(CallPhase.Connected, m.phase)

        // User hangs up → CANCEL/BYE, then wait for the call to actually end.
        assertEquals(listOf(SipEffect.TerminateCall), m.dispatch(SipEvent.HangUp))
        assertEquals(CallPhase.TerminatingCall, m.phase)

        // Call ends → tear the stack down and report the outcome.
        assertEquals(
            listOf(SipEffect.Teardown, SipEffect.ReportOutcome(CallOutcome.Ended)),
            m.dispatch(SipEvent.CallEnded),
        )
        assertEquals(CallPhase.TearingDown, m.phase)

        // Stack destroyed → dormant, target cleared.
        assertEquals(emptyList<SipEffect>(), m.dispatch(SipEvent.TeardownComplete))
        assertEquals(CallPhase.Idle, m.phase)
        assertEquals(null, m.state.target)
    }

    @Test
    fun fastAnswer_dialingStraightToConnected() {
        val m = machine()
        m.dispatch(SipEvent.PlaceCall("x"))
        m.dispatch(SipEvent.RegistrationSucceeded)
        m.dispatch(SipEvent.CallConnected)
        assertEquals(CallPhase.Connected, m.phase)
    }

    @Test
    fun registrationFailure_tearsDownAndReportsFailed() {
        val m = machine()
        m.dispatch(SipEvent.PlaceCall("x"))
        val effects = m.dispatch(SipEvent.RegistrationFailed("timeout"))
        assertEquals(CallPhase.TearingDown, m.phase)
        assertEquals(
            listOf(SipEffect.Teardown, SipEffect.ReportOutcome(CallOutcome.Failed("timeout"))),
            effects,
        )
        m.dispatch(SipEvent.TeardownComplete)
        assertEquals(CallPhase.Idle, m.phase)
    }

    @Test
    fun hangUpDuringRegistration_cancelsAndTearsDown() {
        val m = machine()
        m.dispatch(SipEvent.PlaceCall("x"))
        val effects = m.dispatch(SipEvent.HangUp)
        assertEquals(CallPhase.TearingDown, m.phase)
        assertEquals(
            listOf(SipEffect.Teardown, SipEffect.ReportOutcome(CallOutcome.Canceled)),
            effects,
        )
    }

    @Test
    fun remoteDeclineWhileRinging_reportsEnded() {
        val m = machine()
        m.dispatch(SipEvent.PlaceCall("x"))
        m.dispatch(SipEvent.RegistrationSucceeded)
        m.dispatch(SipEvent.RemoteRinging)
        val effects = m.dispatch(SipEvent.CallEnded)
        assertEquals(CallPhase.TearingDown, m.phase)
        assertEquals(
            listOf(SipEffect.Teardown, SipEffect.ReportOutcome(CallOutcome.Ended)),
            effects,
        )
    }

    @Test
    fun callFailureWhileDialing_reportsFailed() {
        val m = machine()
        m.dispatch(SipEvent.PlaceCall("x"))
        m.dispatch(SipEvent.RegistrationSucceeded)
        val effects = m.dispatch(SipEvent.CallFailed("busy"))
        assertEquals(
            listOf(SipEffect.Teardown, SipEffect.ReportOutcome(CallOutcome.Failed("busy"))),
            effects,
        )
    }

    @Test
    fun remoteHangUpWhileConnected_reportsEnded() {
        val m = machine()
        m.dispatch(SipEvent.PlaceCall("x"))
        m.dispatch(SipEvent.RegistrationSucceeded)
        m.dispatch(SipEvent.CallConnected)
        val effects = m.dispatch(SipEvent.CallEnded)
        assertEquals(
            listOf(SipEffect.Teardown, SipEffect.ReportOutcome(CallOutcome.Ended)),
            effects,
        )
    }

    @Test
    fun terminating_errorInsteadOfEnd_stillTearsDown() {
        val m = machine()
        m.dispatch(SipEvent.PlaceCall("x"))
        m.dispatch(SipEvent.RegistrationSucceeded)
        m.dispatch(SipEvent.CallConnected)
        m.dispatch(SipEvent.HangUp)
        assertEquals(CallPhase.TerminatingCall, m.phase)
        val effects = m.dispatch(SipEvent.CallFailed("media error"))
        assertEquals(CallPhase.TearingDown, m.phase)
        assertEquals(
            listOf(SipEffect.Teardown, SipEffect.ReportOutcome(CallOutcome.Ended)),
            effects,
        )
    }

    @Test
    fun idle_ignoresStrayCallbacks_staysDormant() {
        val m = machine()
        for (event in listOf(
            SipEvent.RegistrationSucceeded,
            SipEvent.RegistrationFailed("x"),
            SipEvent.RemoteRinging,
            SipEvent.CallConnected,
            SipEvent.CallEnded,
            SipEvent.CallFailed("x"),
            SipEvent.HangUp,
            SipEvent.TeardownComplete,
        )) {
            val effects = m.dispatch(event)
            assertEquals("event $event must not wake a dormant machine", CallPhase.Idle, m.phase)
            assertTrue("event $event must produce no effects when dormant", effects.isEmpty())
        }
    }

    @Test
    fun secondPlaceCallMidCall_isIgnored() {
        val m = machine()
        m.dispatch(SipEvent.PlaceCall("first"))
        m.dispatch(SipEvent.RegistrationSucceeded)
        m.dispatch(SipEvent.CallConnected)
        val effects = m.dispatch(SipEvent.PlaceCall("second"))
        assertEquals(CallPhase.Connected, m.phase)
        assertEquals("first", m.state.target)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun tearingDown_ignoresEverythingButTeardownComplete() {
        val m = machine()
        m.dispatch(SipEvent.PlaceCall("x"))
        m.dispatch(SipEvent.RegistrationFailed("x")) // now TearingDown
        for (event in listOf(
            SipEvent.RegistrationSucceeded,
            SipEvent.RemoteRinging,
            SipEvent.CallConnected,
            SipEvent.CallEnded,
            SipEvent.HangUp,
        )) {
            val effects = m.dispatch(event)
            assertEquals(CallPhase.TearingDown, m.phase)
            assertTrue(effects.isEmpty())
        }
        m.dispatch(SipEvent.TeardownComplete)
        assertEquals(CallPhase.Idle, m.phase)
    }

    /**
     * The battery-safety invariant: from any active phase, every path that
     * returns to [CallPhase.Idle] emits exactly one [SipEffect.Teardown] on the
     * way, so the SIP stack is never left running while dormant.
     */
    @Test
    fun everyEndingTearsTheStackDownExactlyOnce() {
        // (setup events, ending event) pairs covering each active phase's endings.
        val scenarios: List<Pair<List<SipEvent>, SipEvent>> = listOf(
            listOf(SipEvent.PlaceCall("x")) to SipEvent.RegistrationFailed("r"),
            listOf(SipEvent.PlaceCall("x")) to SipEvent.HangUp,
            listOf(SipEvent.PlaceCall("x"), SipEvent.RegistrationSucceeded) to SipEvent.CallFailed("r"),
            listOf(SipEvent.PlaceCall("x"), SipEvent.RegistrationSucceeded) to SipEvent.CallEnded,
            listOf(SipEvent.PlaceCall("x"), SipEvent.RegistrationSucceeded, SipEvent.RemoteRinging) to SipEvent.CallEnded,
            listOf(SipEvent.PlaceCall("x"), SipEvent.RegistrationSucceeded, SipEvent.CallConnected) to SipEvent.CallEnded,
            listOf(SipEvent.PlaceCall("x"), SipEvent.RegistrationSucceeded, SipEvent.CallConnected) to SipEvent.CallFailed("r"),
        )
        for ((setup, ending) in scenarios) {
            val m = machine()
            val emitted = mutableListOf<SipEffect>()
            setup.forEach { emitted += m.dispatch(it) }
            emitted += m.dispatch(ending)
            // ...and drive any HangUp path through its CallEnded → teardown too.
            if (m.phase == CallPhase.TerminatingCall) emitted += m.dispatch(SipEvent.CallEnded)
            emitted += m.dispatch(SipEvent.TeardownComplete)

            assertEquals("scenario ending in $ending must reach Idle", CallPhase.Idle, m.phase)
            assertEquals(
                "scenario ending in $ending must tear the stack down exactly once",
                1,
                emitted.count { it == SipEffect.Teardown },
            )
            assertEquals(
                "scenario ending in $ending must report exactly one outcome",
                1,
                emitted.count { it is SipEffect.ReportOutcome },
            )
        }
    }
}
