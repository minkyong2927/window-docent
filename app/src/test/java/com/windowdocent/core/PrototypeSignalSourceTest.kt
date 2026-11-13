package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PrototypeSignalSourceTest {

    @Test fun mapsPanelStateToSignalsAndSeats() {
        val state = mapOf(
            "turn" to "right", "steer" to 18, "speed" to 98.5, "brake" to true,
            "nav" to "near", "navDist" to 1000, "door" to false, "rear" to true, "front" to true,
        )
        assertEquals(
            VehicleSignals(
                speedKmh = 98.5,
                turnSignal = TurnSignal.RIGHT,
                steeringAngleDeg = 18.0,
                brakePressed = true,
                navGuidance = NavGuidance.TURN_AHEAD,
                navDistanceM = 1000.0,
            ),
            PrototypeSignalSource.signalsOf(state),
        )
        assertEquals(Seats(frontPassenger = true, rearCount = 1), PrototypeSignalSource.seatsOf(state))
    }

    @Test fun missingOrNullFieldsStayUnknown() {
        assertEquals(VehicleSignals(), PrototypeSignalSource.signalsOf(mapOf("speed" to null, "nav" to null)))
        assertEquals(Seats(), PrototypeSignalSource.seatsOf(emptyMap()))
    }

    @Test fun rearSeatAcceptsHeadCount() =
        assertEquals(2, PrototypeSignalSource.seatsOf(mapOf("rear" to 2)).rearCount)

    @Test fun deliversToListenerOnlyBetweenStartAndStop() {
        val source = PrototypeSignalSource()
        val received = mutableListOf<VehicleSignals>()
        source.push(mapOf("turn" to "left"))
        source.start { vehicle, _ -> received += vehicle }
        source.push(mapOf("turn" to "left"))
        source.stop()
        source.push(mapOf("turn" to "right"))
        assertEquals(listOf(VehicleSignals(turnSignal = TurnSignal.LEFT)), received)
    }

    @Test fun cautionWithin300mOnly() {
        assertEquals("SCHOOL_ZONE", PrototypeSignalSource.signalsOf(mapOf("caution" to "SCHOOL_ZONE", "cautionDist" to 200)).cautionZone)
        assertEquals(null, PrototypeSignalSource.signalsOf(mapOf("caution" to "SCHOOL_ZONE", "cautionDist" to 600)).cautionZone)
        assertEquals(null, PrototypeSignalSource.signalsOf(mapOf("caution" to "SPEED_BUMP", "cautionDist" to 50)).cautionZone)
        assertEquals(true, PrototypeSignalSource.signalsOf(mapOf("arrived" to true)).arrived)
    }
}
