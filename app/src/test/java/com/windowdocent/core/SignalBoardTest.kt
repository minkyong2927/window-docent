package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalBoardTest {
    private var now = 0L
    private val board = SignalBoard(clock = { now })

    private fun north(meters: Double) = LatLng(36.0 + meters / 111_194.93, 127.0)

    @Test fun nothingReceivedMeansUnknown() {
        assertEquals(VehicleSignals(), board.signals())
        assertEquals(Seats(), board.seats())
    }

    @Test fun collectsVehicleSignals() {
        board.turnSignal(TurnSignal.LEFT)
        board.steeringAngle(-42.0)
        board.brakePedal(true)
        val s = board.signals()
        assertEquals(TurnSignal.LEFT, s.turnSignal)
        assertEquals(-42.0, s.steeringAngleDeg!!, 0.0)
        assertTrue(s.brakePressed)
    }

    @Test fun brakeValueNotReceivedCountsAsReleased() {
        board.brakePedal(true)
        board.brakePedal(null)
        assertFalse(board.signals().brakePressed)
    }

    @Test fun anyOpenDoorMeansDoorOpen() {
        board.door("ROW_1_LEFT", true)
        board.door("ROW_2_RIGHT", true)
        assertTrue(board.signals().doorOpen)
        board.door("ROW_1_LEFT", false)
        assertTrue(board.signals().doorOpen)
        board.door("ROW_2_RIGHT", false)
        assertFalse(board.signals().doorOpen)
    }

    @Test fun countsPassengersButNotTheDriver() {
        board.seat("ROW_1_LEFT", SeatSpot.DRIVER, true)
        board.seat("ROW_1_RIGHT", SeatSpot.FRONT_PASSENGER, true)
        board.seat("ROW_2_LEFT", SeatSpot.REAR, true)
        board.seat("ROW_2_RIGHT", SeatSpot.REAR, true)
        assertEquals(Seats(frontPassenger = true, rearCount = 2), board.seats())
        board.seat("ROW_2_LEFT", SeatSpot.REAR, false)
        board.seat("ROW_1_RIGHT", SeatSpot.FRONT_PASSENGER, false)
        assertEquals(Seats(frontPassenger = false, rearCount = 1), board.seats())
    }

    @Test fun nextTurnBecomesTurnAheadWithDistance() {
        board.nextGuidance(attention = true, distanceM = 800.0)
        assertEquals(NavGuidance.TURN_AHEAD, board.signals().navGuidance)
        assertEquals(800.0, board.signals().navDistanceM!!, 0.0)
        board.clearGuidance()
        assertEquals(NavGuidance.NONE, board.signals().navGuidance)
        assertNull(board.signals().navDistanceM)
    }

    @Test fun naviVoiceWinsOverTurnAhead() {
        board.nextGuidance(attention = true, distanceM = 800.0)
        board.naviVoice(true)
        assertEquals(NavGuidance.VOICE, board.signals().navGuidance)
        board.naviVoice(false)
        assertEquals(NavGuidance.TURN_AHEAD, board.signals().navGuidance)
    }

    @Test fun straightAndTunnelGuidanceNeedNoAttention() {
        assertFalse(SignalBoard.needsAttention("TURN_STRAIGHT"))
        assertFalse(SignalBoard.needsAttention("TURN_TUNNEL"))
        assertFalse(SignalBoard.needsAttention("TURN_UNKNOWN"))
        assertTrue(SignalBoard.needsAttention("TURN_RIGHT"))
        assertTrue(SignalBoard.needsAttention("TURN_EXIT_IC_R"))
        assertTrue(SignalBoard.needsAttention("TURN_DEVIDE_MERGE"))
    }

    @Test fun picksTheNearestGuidanceThatNeedsAttention() {
        val upcoming = listOf("TURN_STRAIGHT" to 300.0, "TURN_EXIT_IC_R" to 2400.0, "TURN_LEFT" to 900.0)
        assertEquals(900.0, SignalBoard.nearestAttentionM(upcoming)!!, 0.0)
        assertNull(SignalBoard.nearestAttentionM(listOf("TURN_STRAIGHT" to 300.0)))
        assertNull(SignalBoard.nearestAttentionM(emptyList()))
    }

    @Test fun speedComesFromLocationChanges() {
        assertNull(board.signals().speedKmh)
        for (s in 0..3) {
            now = s * 1000L
            board.location(north(25.0 * s), now)       // 초속 25m = 시속 90km
        }
        assertEquals(90.0, board.signals().speedKmh!!, 0.5)
        now = 20_000                                    // 위치가 한참 안 옴
        assertNull(board.signals().speedKmh)
    }

    @Test fun listenerGetsTheWholeSetOnEveryChange() {
        val received = mutableListOf<Pair<VehicleSignals, Seats>>()
        board.onChange { vehicle, seated -> received += vehicle to seated }
        board.turnSignal(TurnSignal.RIGHT)
        board.seat("ROW_2_LEFT", SeatSpot.REAR, true)
        assertEquals(2, received.size)
        assertEquals(TurnSignal.RIGHT, received[1].first.turnSignal)
        assertEquals(1, received[1].second.rearCount)
        board.onChange(null)
        board.brakePedal(true)
        assertEquals(2, received.size)
    }

    @Test fun republishSendsCurrentValuesAgain() {
        var count = 0
        board.onChange { _, _ -> count++ }
        board.republish()
        assertEquals(1, count)
    }

    @Test fun collectedSignalsDriveTheGate() {
        val gate = NarrationGate()
        // 달리는 중
        for (s in 0..3) {
            now = s * 1000L
            board.location(north(25.0 * s), now)
        }
        assertEquals(NarrationMode.NARRATE, gate.evaluate(board.signals()).mode)
        // 방향지시등
        board.turnSignal(TurnSignal.LEFT)
        assertEquals(GateReason.TURN_SIGNAL, gate.evaluate(board.signals()).reason)
        board.turnSignal(TurnSignal.OFF)
        // 분기점 700m 앞
        board.nextGuidance(attention = true, distanceM = 700.0)
        assertEquals(NarrationMode.ONE_LINE, gate.evaluate(board.signals()).mode)
        board.clearGuidance()
        // 같은 자리에 멈춤
        now = 4000
        board.location(north(75.0), now)
        assertEquals(NarrationMode.LONG_STORY, gate.evaluate(board.signals()).mode)
        // 문이 열림
        board.door("ROW_2_LEFT", true)
        assertEquals(NarrationMode.TRIP_RECORD, gate.evaluate(board.signals()).mode)
    }

    @Test fun hazardAheadBecomesCautionZone() {
        board.hazards(listOf(Hazard("s", "SCHOOL_ZONE", 900.0)))
        assertNull(board.signals().cautionZone)
        board.hazards(listOf(Hazard("s", "SCHOOL_ZONE", 250.0)))
        assertEquals("SCHOOL_ZONE", board.signals().cautionZone)
        now += 16_000                                     // 위험 지점 신호가 15초 넘게 안 옴
        assertNull(board.signals().cautionZone)
    }

    @Test fun arrivalIsTrueForAMinute() {
        assertFalse(board.signals().arrived)
        board.arrived()
        assertTrue(board.signals().arrived)
        now += 61_000
        assertFalse(board.signals().arrived)
    }
}
