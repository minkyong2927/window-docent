package com.windowdocent.core

/**
 * 위치 두 점의 거리와 시간으로 차속(km/h)을 구함.
 * Pleos Vehicle SDK 에는 차속 값이 없고 FusedLocation 의 위치(LocationData)에도 속도 칸이 없어 위치 변화로 계산함.
 * 못 구하는 동안은 null(= 모름). NarrationGate 는 속도를 모르면 달리는 중으로 봄.
 */
class SpeedEstimator(
    /** 이보다 촘촘하게 온 위치는 다음 위치와 묶어 계산 (위치 떨림으로 속도가 튀는 것 방지) */
    private val minIntervalMs: Long = 400,
    /** 이 시간 넘게 새 위치가 없으면 속도를 모르는 것으로 봄 (터널 등) */
    private val staleMs: Long = 5_000,
    /** 이보다 빠르게 나온 값은 위치가 튄 것으로 보고 버림 */
    private val maxSpeedKmh: Double = 250.0,
    /** 새 값을 얼마나 반영할지 (0~1 · 1 이면 평활 없음) */
    private val smoothing: Double = 0.5,
) {
    private var lastPosition: LatLng? = null
    private var lastTimeMs = 0L
    private var speedKmh: Double? = null
    private var updatedMs = 0L

    /** 새 위치를 넣고 지금 속도를 돌려줌. 아직 두 점이 안 모였으면 null. */
    @Synchronized
    fun update(position: LatLng, timeMs: Long): Double? {
        val previous = lastPosition
        val elapsed = timeMs - lastTimeMs
        if (previous == null || elapsed < 0 || elapsed > staleMs) {
            // 첫 위치이거나, 시간이 거꾸로 갔거나, 너무 오래 끊겼으면 여기서부터 다시 셈
            lastPosition = position
            lastTimeMs = timeMs
            speedKmh = null
            return null
        }
        if (elapsed < minIntervalMs) return speedKmh

        val raw = RoutePoiPlanner.distanceM(previous, position) / (elapsed / 1000.0) * 3.6
        lastPosition = position
        lastTimeMs = timeMs
        if (raw > maxSpeedKmh) return speedKmh   // 위치가 튐: 기준점만 옮기고 속도는 그대로

        val before = speedKmh
        speedKmh = when {
            before == null -> raw
            raw < STOP_SNAP_KMH -> raw            // 멈춘 것은 바로 반영 (정차 판단이 늦지 않게)
            else -> before + smoothing * (raw - before)
        }
        updatedMs = timeMs
        return speedKmh
    }

    /** 지금 속도. 마지막 계산이 오래됐으면 null. */
    @Synchronized
    fun current(nowMs: Long): Double? {
        val speed = speedKmh ?: return null
        return if (nowMs - updatedMs > staleMs) null else speed
    }

    @Synchronized
    fun reset() {
        lastPosition = null
        speedKmh = null
    }

    private companion object {
        const val STOP_SNAP_KMH = 1.0
    }
}
