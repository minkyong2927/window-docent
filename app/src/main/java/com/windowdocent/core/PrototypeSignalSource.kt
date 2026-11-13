package com.windowdocent.core

/**
 * 웹 프로토타입의 시연용 신호 판을 차량 신호로 바꿔 넘기는 공급원.
 * 신호 판 상태 S 의 칸: turn("off"|"left"|"right"), steer, speed, brake, nav(null|"near"|"voice"),
 * navDist, door, rear(참/거짓 또는 인원 수), front, caution(위험 지점 종류 · SDK 이름), cautionDist(그 지점까지 m), arrived.
 * 없는 칸은 '못 받은 값'으로 둠. 위험 지점은 cautionDist 가 300m 안이면 위험 구간 (지난 뒤 300m 더는 실제 신호 쪽 CautionZone).
 */
class PrototypeSignalSource : VehicleSignalSource {
    private var listener: ((VehicleSignals, Seats) -> Unit)? = null

    override fun start(listener: (VehicleSignals, Seats) -> Unit) {
        this.listener = listener
    }

    override fun stop() {
        listener = null
    }

    /** 신호 판 상태가 바뀔 때마다 화면이 부름 */
    fun push(state: Map<String, Any?>) {
        listener?.invoke(signalsOf(state), seatsOf(state))
    }

    companion object {
        fun signalsOf(s: Map<String, Any?>) = VehicleSignals(
            speedKmh = number(s["speed"]),
            turnSignal = when (s["turn"]) {
                "left" -> TurnSignal.LEFT
                "right" -> TurnSignal.RIGHT
                else -> TurnSignal.OFF
            },
            steeringAngleDeg = number(s["steer"]),
            brakePressed = s["brake"] == true,
            navGuidance = when (s["nav"]) {
                "voice" -> NavGuidance.VOICE
                "near" -> NavGuidance.TURN_AHEAD
                else -> NavGuidance.NONE
            },
            navDistanceM = number(s["navDist"]),
            doorOpen = s["door"] == true,
            cautionZone = (s["caution"] as? String)?.takeIf { it in CautionZone.DANGER && (number(s["cautionDist"]) ?: 0.0) <= CAUTION_AHEAD_M },
            arrived = s["arrived"] == true,
        )

        fun seatsOf(s: Map<String, Any?>) = Seats(
            frontPassenger = s["front"] == true,
            rearCount = when (val rear = s["rear"]) {
                is Number -> rear.toInt().coerceAtLeast(0)
                true -> 1
                else -> 0
            },
        )

        private const val CAUTION_AHEAD_M = 300.0

        private fun number(v: Any?): Double? = (v as? Number)?.toDouble()
    }
}
