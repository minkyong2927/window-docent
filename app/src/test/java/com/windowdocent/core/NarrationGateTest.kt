package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NarrationGateTest {
    private val gate = NarrationGate()
    private val highway = VehicleSignals(speedKmh = 98.0)

    private fun check(s: VehicleSignals, mode: NarrationMode, reason: GateReason) {
        assertEquals(GateResult(mode, reason), gate.evaluate(s))
    }

    @Test fun cruisingNarrates() = check(highway, NarrationMode.NARRATE, GateReason.CRUISING)

    @Test fun stoppedAllowsLongStory() =
        check(highway.copy(speedKmh = 0.0), NarrationMode.LONG_STORY, GateReason.STOPPED)

    @Test fun doorOpenShowsTripRecord() =
        check(highway.copy(speedKmh = 0.0, doorOpen = true), NarrationMode.TRIP_RECORD, GateReason.DOOR_OPEN)

    @Test fun turnSignalHolds() =
        check(highway.copy(turnSignal = TurnSignal.RIGHT), NarrationMode.HOLD, GateReason.TURN_SIGNAL)

    @Test fun largeSteeringHolds() =
        check(highway.copy(steeringAngleDeg = -35.0), NarrationMode.HOLD, GateReason.LARGE_STEERING)

    @Test fun navVoiceHolds() =
        check(highway.copy(navGuidance = NavGuidance.VOICE), NarrationMode.HOLD, GateReason.NAV_VOICE)

    @Test fun junctionWithin1kmIsOneLine() =
        check(highway.copy(navGuidance = NavGuidance.TURN_AHEAD, navDistanceM = 1000.0),
            NarrationMode.ONE_LINE, GateReason.NAV_NEAR)

    @Test fun junctionFarAwayNarrates() =
        check(highway.copy(navGuidance = NavGuidance.TURN_AHEAD, navDistanceM = 4000.0),
            NarrationMode.NARRATE, GateReason.CRUISING)

    @Test fun holdIsLevel3AndTripRecordIsLevel0() {
        assertEquals(3, NarrationMode.HOLD.level)
        assertEquals(0, NarrationMode.TRIP_RECORD.level)
    }
}
