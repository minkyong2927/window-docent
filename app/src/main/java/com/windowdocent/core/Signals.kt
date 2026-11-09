package com.windowdocent.core

enum class TurnSignal { OFF, LEFT, RIGHT, HAZARD }

enum class NavGuidance {
    NONE,
    /** 분기점·회전 안내 예정 (거리는 navDistanceM) */
    TURN_AHEAD,
    /** 내비 안내 음성 출력 중 */
    VOICE
}

/**
 * 한 시점의 차량 신호. Pleos Connect SDK의 Vehicle·NaviHelper·FusedLocation 값을 채움.
 */
data class VehicleSignals(
    val speedKmh: Double = 0.0,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val steeringAngleDeg: Double = 0.0,
    val navGuidance: NavGuidance = NavGuidance.NONE,
    val navDistanceM: Double? = null,
    val doorOpen: Boolean = false,
)

/** 좌석 착석 정보 (Vehicle - Seat) */
data class Seats(
    val frontPassenger: Boolean = false,
    val rearCount: Int = 0,
)

/** 임계값은 설정값으로 분리 (실차 데이터로 조정) */
data class DocentConfig(
    val largeSteeringDeg: Double = 30.0,
    val navNearDistanceM: Double = 1000.0,
    val stoppedSpeedKmh: Double = 1.0,
    /** 경로에서 이만큼 안쪽에 있는 명소만 해설 대상 */
    val corridorM: Double = 3000.0,
    /** 해설 1건 최소·최대 길이(초) */
    val minNarrationSec: Int = 15,
    val maxNarrationSec: Int = 60,
)
