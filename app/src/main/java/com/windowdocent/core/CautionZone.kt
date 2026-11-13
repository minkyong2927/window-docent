package com.windowdocent.core

/** 내비가 알려 준 앞쪽 위험 지점 하나 (NaviHelper onCautionPointInfo 의 CautionPoint · onCameraAlert 의 CameraAlert) */
data class Hazard(val id: String, val type: String, val remainM: Double)

/**
 * 위험 구간에 있는지 판단 (돌발 상황 멈춤: 위험 구간을 지날 때는 멈추었다가 이어 감).
 * - 위험으로 보는 종류: CautionPointType 23 가운데 13 + CameraType 의 어린이 보호구역 둘 (DANGER)
 * - 위험 지점이 aheadM(300m) 안이면 위험 구간 → 그 지점을 지난 뒤 afterM(300m) 더 (속도를 모르면 afterSec 20초) → 풀림.
 *   구간 길이는 SDK 가 주지 않아 지난 뒤 거리로 셈
 * - 지났다 = 그 지점이 목록에서 빠지거나 남은 거리가 0 이하
 * - 위험 지점 신호가 staleSec(15초) 넘게 안 오면 풀림 (멈춘 채 굳지 않게)
 * 남은 거리 단위는 미터로 봄 (Pleos 에뮬레이터 확인은 2차).
 */
class CautionZone(
    private val aheadM: Double = 300.0,
    private val afterM: Double = 300.0,
    private val afterSec: Double = 20.0,
    private val staleSec: Double = 15.0,
) {
    private var activeId: String? = null
    private var activeType: String? = null
    private var passed = false
    private var passedAtMs = 0L
    private var leftM = 0.0
    private var lastSignalMs = 0L
    private var lastTickMs = 0L

    /** 내비가 새 위험 지점 목록을 줄 때마다 */
    @Synchronized
    fun update(hazards: List<Hazard>, nowMs: Long) {
        lastSignalMs = nowMs
        val danger = hazards.filter { it.type in DANGER }
        val id = activeId
        if (id != null && !passed) {
            val cur = danger.firstOrNull { it.id == id }
            if (cur == null || cur.remainM <= 0.0) {
                passed = true
                passedAtMs = nowMs
                leftM = afterM
                lastTickMs = nowMs
            }
        }
        val near = danger.filter { it.remainM > 0.0 && it.remainM <= aheadM }.minByOrNull { it.remainM }
        if (near != null && (activeId == null || passed)) {
            activeId = near.id
            activeType = near.type
            passed = false
        }
    }

    /**
     * 지금 위험 구간이면 그 종류 이름, 아니면 null. 판단할 때마다 부름.
     * @param speedKmh 지난 뒤 거리를 셀 속도 (모르면 null → 시간으로)
     */
    @Synchronized
    fun active(nowMs: Long, speedKmh: Double?): String? {
        if (activeId == null) return null
        if (nowMs - lastSignalMs > staleSec * 1000) return clear()
        if (passed) {
            if (speedKmh != null) {
                leftM -= speedKmh / 3.6 * ((nowMs - lastTickMs).coerceAtLeast(0) / 1000.0)
                lastTickMs = nowMs
                if (leftM <= 0.0) return clear()
            } else {
                lastTickMs = nowMs
                if (nowMs - passedAtMs > afterSec * 1000) return clear()
            }
        }
        return activeType
    }

    private fun clear(): String? {
        activeId = null
        activeType = null
        passed = false
        return null
    }

    companion object {
        /** 위험으로 보는 종류 (SDK 이름 그대로 · SIGN_MALFUNCION 도 SDK 표기) */
        val DANGER = setOf(
            "SCHOOL_ZONE", "ACCIDENT_PRONE_AREA", "RAILROAD_CROSSING", "FOG_AREA", "FALLING_ROCK_AREA",
            "DOWNHILL_CAUTION_AREA", "WILDLIFE_CROSSING", "NARROW_ROAD", "HIGHWAY_CONSTRUCTION",
            "SLIPPERY_ROAD_ALERT", "ROAD_DAMAGE_ALERT", "WRONG_WAY_DRIVING_ALERT", "SIGN_MALFUNCION",
            "SPEED_CAMERA_SCHOOL_ZONE", "SPEED_CAMERA_SCHOOL_ZONE_TRAFFIC_LIGHT",
        )
    }
}
