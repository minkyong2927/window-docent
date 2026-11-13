package com.windowdocent.core

/** 방향지시등. Pleos Vehicle SDK 의 TurnSignalValue(OFF · LEFT · RIGHT)와 같은 값. */
enum class TurnSignal { OFF, LEFT, RIGHT }

enum class NavGuidance {
    NONE,
    /** 분기점·회전 안내 예정 (거리는 navDistanceM) */
    TURN_AHEAD,
    /** 내비 안내 음성 출력 중 */
    VOICE
}

/**
 * 한 시점의 차량 신호. 못 받은 값은 null (미지원 차종, 터널에서 위치가 끊긴 때 등).
 *
 * Pleos Connect SDK 에서 받는 곳 (pleos/PleosSignalSource · Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3 기준):
 * - speedKmh          Vehicle SDK 에 차속 API 가 없어 FusedLocation 위치 변화(LocationData)로 계산
 * - turnSignal        Vehicle TurnSignal.registerTurnSignalState
 * - steeringAngleDeg  Vehicle SteeringWheel.registerSteeringWheelAngle
 * - brakePressed      Vehicle Brake.registerBrakePedalStatus
 * - navGuidance       NaviHelper onTBTInfo (다음 안내가 있으면 TURN_AHEAD). 안내 음성 출력 중(VOICE)은 SDK 에 없어 AudioManager 재생 정보로 판단(NaviVoiceWatcher)
 * - navDistanceM      NaviHelper onTBTInfo 의 TBTInfo.distance
 * - doorOpen          Vehicle Door.registerDoorPosition
 * - cautionZone       NaviHelper onCautionPointInfo · onCameraAlert → CautionZone (위험 구간 안이면 그 종류 이름)
 * - arrived           NaviHelper onDestinationArrived · onWayPointArrived (도착한 뒤 잠깐 참)
 */
data class VehicleSignals(
    val speedKmh: Double? = null,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val steeringAngleDeg: Double? = null,
    val brakePressed: Boolean = false,
    val navGuidance: NavGuidance = NavGuidance.NONE,
    val navDistanceM: Double? = null,
    val doorOpen: Boolean = false,
    /** 위험 구간 안이면 그 종류 이름(SDK CautionPointType · CameraType 이름), 아니면 null */
    val cautionZone: String? = null,
    /** 목적지 · 경유지에 막 도착함 */
    val arrived: Boolean = false,
)

/** 좌석 착석 정보 (Vehicle Seat.registerSeatOccupancy · 조수석 ROW_1_RIGHT, 뒷좌석 ROW_2_*) */
data class Seats(
    val frontPassenger: Boolean = false,
    val rearCount: Int = 0,
)

/** 임계값은 설정값으로 분리 (실차 데이터로 조정) */
data class DocentConfig(
    val largeSteeringDeg: Double = 30.0,
    val navNearDistanceM: Double = 1000.0,
    val stoppedSpeedKmh: Double = 1.0,
    /** 브레이크를 밟은 채 이 속도 아래면 정차로 봄 (위치로 계산한 속도의 흔들림 보정) */
    val creepSpeedKmh: Double = 5.0,
    /** 경로에서 이만큼 안쪽에 있는 명소만 해설 대상 */
    val corridorM: Double = 3000.0,
    /** 해설 1건 최소·최대 길이(초) */
    val minNarrationSec: Int = 15,
    val maxNarrationSec: Int = 60,
)
