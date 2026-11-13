package com.windowdocent.core

import kotlin.math.abs

/** 지금 해설을 어떻게 할지. level은 운전 부담 0~3 (클수록 바쁨). */
enum class NarrationMode(val level: Int) {
    /** 정차: 긴 이야기 가능 */
    LONG_STORY(0),
    /** 직진·안내 없음: 해설과 퀴즈 */
    NARRATE(1),
    /** 분기점 접근: 한 문장으로 짧게 */
    ONE_LINE(2),
    /** 회전·차선 변경·내비 음성: 해설 보류, 끝나면 이어서 */
    HOLD(3),
    /** 도어 열림: 여행 기록 표시 (해설 끝) */
    TRIP_RECORD(0),
}

enum class GateReason { DOOR_OPEN, TURN_SIGNAL, LARGE_STEERING, NAV_VOICE, NAV_NEAR, STOPPED, CRUISING }

data class GateResult(val mode: NarrationMode, val reason: GateReason)

/**
 * 언제 말할지는 차량 신호가 정함. 위에서부터 먼저 맞는 규칙이 우선.
 * 못 받은 신호(null)는 그 규칙을 건너뛰고, 속도를 모르면 달리는 중으로 봄.
 */
class NarrationGate(private val config: DocentConfig = DocentConfig()) {

    fun evaluate(s: VehicleSignals): GateResult {
        val steering = s.steeringAngleDeg
        return when {
            s.doorOpen -> GateResult(NarrationMode.TRIP_RECORD, GateReason.DOOR_OPEN)
            s.turnSignal != TurnSignal.OFF -> GateResult(NarrationMode.HOLD, GateReason.TURN_SIGNAL)
            steering != null && abs(steering) >= config.largeSteeringDeg ->
                GateResult(NarrationMode.HOLD, GateReason.LARGE_STEERING)
            s.navGuidance == NavGuidance.VOICE -> GateResult(NarrationMode.HOLD, GateReason.NAV_VOICE)
            s.navGuidance == NavGuidance.TURN_AHEAD &&
                (s.navDistanceM ?: 0.0) <= config.navNearDistanceM ->
                GateResult(NarrationMode.ONE_LINE, GateReason.NAV_NEAR)
            isStopped(s) -> GateResult(NarrationMode.LONG_STORY, GateReason.STOPPED)
            else -> GateResult(NarrationMode.NARRATE, GateReason.CRUISING)
        }
    }

    /** 정차: 속도가 거의 0 이거나, 브레이크를 밟은 채 기어가는 속도. 긴 이야기는 정차가 확인될 때만. */
    private fun isStopped(s: VehicleSignals): Boolean {
        val speed = s.speedKmh ?: return false
        return speed < config.stoppedSpeedKmh || (s.brakePressed && speed < config.creepSpeedKmh)
    }
}
