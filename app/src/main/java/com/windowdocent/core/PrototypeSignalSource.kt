package com.windowdocent.core

/**
 * 웹 프로토타입의 시연용 신호 판을 차량 신호로 바꿔 넘기는 공급원.
 * 신호 판 상태 S 의 칸: turn("off"|"left"|"right"), steer, speed, brake, nav(null|"near"|"voice"),
 * navDist, door, rear(참/거짓 또는 인원 수), front. 없는 칸은 '못 받은 값'으로 둠.
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
        )

        fun seatsOf(s: Map<String, Any?>) = Seats(
            frontPassenger = s["front"] == true,
            rearCount = when (val rear = s["rear"]) {
                is Number -> rear.toInt().coerceAtLeast(0)
                true -> 1
                else -> 0
            },
        )

        private fun number(v: Any?): Double? = (v as? Number)?.toDouble()
    }
}
