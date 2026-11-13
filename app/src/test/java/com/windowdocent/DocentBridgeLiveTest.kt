package com.windowdocent

import com.windowdocent.core.LiveSignalSource
import com.windowdocent.core.NavGuidance
import com.windowdocent.core.Seats
import com.windowdocent.core.TurnSignal
import com.windowdocent.core.VehicleSignals
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면의 「차량 신호」 켜기 · 끄기: 실제 신호 공급원으로 바꾸고, 판단과 신호 판 값이 실제 신호를 따르는지 */
class DocentBridgeLiveTest {

    /** 실제 차량 신호 공급원 흉내 (Pleos SDK 대신) */
    private class FakeLive(var ok: Boolean = true) : LiveSignalSource {
        var listener: ((VehicleSignals, Seats) -> Unit)? = null
        var signals = VehicleSignals()
        var seats = Seats()
        var started = 0
        var stopped = 0
        var refreshed = 0

        override fun start(listener: (VehicleSignals, Seats) -> Unit) {
            started++
            this.listener = listener
            listener(signals, seats)
        }

        override fun stop() {
            stopped++
            listener = null
        }

        override fun status() = if (ok) mapOf("vehicle" to "ok", "navi" to "ok (아직 받은 값 없음)")
        else mapOf("vehicle" to "이 기기에 Pleos 서비스가 없음")

        override fun connected() = ok

        override fun refresh() {
            refreshed++
            listener?.invoke(signals, seats)
        }

        fun send(vehicle: VehicleSignals, seated: Seats = seats) {
            signals = vehicle
            seats = seated
            listener?.invoke(vehicle, seated)
        }
    }

    private val demoCruising = """{"turn":"off","steer":0,"speed":98,"brake":false,"nav":null,"navDist":null,"door":false,"rear":false,"front":false,"lang":"ko"}"""

    private fun reason(bridge: DocentBridge, state: String = demoCruising) = JSONObject(bridge.evaluate(state)).getString("reason")

    @Test fun withoutFactoryLiveIsUnavailable() {
        val bridge = DocentBridge()
        val r = JSONObject(bridge.live("""{"on":true}"""))
        assertFalse(r.getBoolean("available"))
        assertFalse(r.getBoolean("on"))
        assertEquals("CRUISING", reason(bridge))
    }

    @Test fun liveSignalsDecideInsteadOfDemoBoard() {
        val fake = FakeLive()
        val bridge = DocentBridge(liveFactory = { fake })
        val r = JSONObject(bridge.live("""{"on":true}"""))
        assertTrue(r.getBoolean("on"))
        assertTrue(r.getBoolean("connected"))
        assertEquals(1, fake.started)

        // 차량에서 방향지시등이 켜지면, 신호 판(시연 값)이 직진이어도 해설을 멈춤
        fake.send(VehicleSignals(speedKmh = 70.0, turnSignal = TurnSignal.LEFT))
        assertEquals("TURN_SIGNAL", reason(bridge))
        assertTrue(fake.refreshed > 0)

        // 정차(속도 0)면 긴 이야기
        fake.send(VehicleSignals(speedKmh = 0.0))
        assertEquals("STOPPED", reason(bridge))
    }

    @Test fun liveStateMirrorsSignalsForTheBoard() {
        val fake = FakeLive()
        val bridge = DocentBridge(liveFactory = { fake })
        bridge.live("""{"on":true}""")
        fake.send(
            VehicleSignals(
                speedKmh = 63.4, turnSignal = TurnSignal.RIGHT, steeringAngleDeg = -12.6, brakePressed = true,
                navGuidance = NavGuidance.TURN_AHEAD, navDistanceM = 742.0, doorOpen = false,
            ),
            Seats(frontPassenger = true, rearCount = 2),
        )
        val r = JSONObject(bridge.liveState("{}"))
        val s = r.getJSONObject("state")
        assertEquals("right", s.getString("turn"))
        assertEquals(-13, s.getInt("steer"))
        assertEquals(63, s.getInt("speed"))
        assertTrue(s.getBoolean("brake"))
        assertEquals("near", s.getString("nav"))
        assertEquals(742, s.getInt("navDist"))
        assertEquals(2, s.getInt("rear"))
        assertTrue(s.getBoolean("front"))
        assertEquals("ok", r.getJSONObject("status").getString("vehicle"))
    }

    @Test fun unknownSpeedIsNullOnTheBoard() {
        val fake = FakeLive()
        val bridge = DocentBridge(liveFactory = { fake })
        bridge.live("""{"on":true}""")
        fake.send(VehicleSignals(speedKmh = null, steeringAngleDeg = null))
        val s = JSONObject(bridge.liveState("{}")).getJSONObject("state")
        assertTrue(s.isNull("speed"))
        assertTrue(s.isNull("steer"))
        assertTrue(s.isNull("nav"))
    }

    @Test fun passengerLanguageStillComesFromTheScreen() {
        val fake = FakeLive()
        val bridge = DocentBridge(liveFactory = { fake })
        bridge.live("""{"on":true}""")
        fake.send(VehicleSignals(speedKmh = 80.0), Seats(rearCount = 1))
        val d = JSONObject(bridge.evaluate(demoCruising.replace("\"lang\":\"ko\"", "\"lang\":\"en\"")))
        assertEquals("FOREIGN_GUEST", d.getString("audience"))
        assertEquals("en", d.getString("language"))
    }

    @Test fun turningOffGoesBackToDemoBoard() {
        val fake = FakeLive()
        val bridge = DocentBridge(liveFactory = { fake })
        bridge.live("""{"on":true}""")
        fake.send(VehicleSignals(speedKmh = 70.0, doorOpen = true))
        assertEquals("DOOR_OPEN", reason(bridge))

        val r = JSONObject(bridge.live("""{"on":false}"""))
        assertFalse(r.getBoolean("on"))
        assertEquals(1, fake.stopped)
        assertEquals("CRUISING", reason(bridge))

        // 다시 켜면 같은 공급원을 다시 붙임
        bridge.live("""{"on":true}""")
        assertEquals(2, fake.started)
    }

    @Test fun notConnectedIsReported() {
        val bridge = DocentBridge(liveFactory = { FakeLive(ok = false) })
        val r = JSONObject(bridge.live("""{"on":true}"""))
        assertTrue(r.getBoolean("on"))
        assertFalse(r.getBoolean("connected"))
        assertEquals("이 기기에 Pleos 서비스가 없음", r.getJSONObject("status").getString("vehicle"))
    }

    @Test fun liveStartHookRunsOnce() {
        var hooked = 0
        val bridge = DocentBridge(liveFactory = { FakeLive() }, onLiveStart = { hooked++ })
        bridge.live("""{"on":true}""")
        bridge.live("""{"on":true}""")
        assertEquals(1, hooked)
    }
}
