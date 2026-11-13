package com.windowdocent.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

/** 명소 사실 필드 (원천: 한국관광공사 TourAPI) */
data class Poi(
    val id: String,
    val name: String,
    val location: LatLng,
    /** 해설 우선순위. 클수록 먼저. */
    val priority: Int = 0,
    /** 산처럼 멀리서도 보이는 명소는 경로에서 이 거리(m)까지 해설 대상. null 이면 설정값 corridorM. */
    val visibleM: Double? = null,
)

/** 경로 위에서 해설할 명소 하나 */
data class PlannedStop(
    val poi: Poi,
    /** 출발점부터 경로를 따라 이 명소 옆에 오기까지 거리(m) */
    val alongRouteM: Double,
    /** 경로에서 명소까지 옆으로 떨어진 거리(m) */
    val offsetM: Double,
    /** 다음 해설 전까지 쓸 수 있는 해설 길이(초) */
    val narrationSec: Int,
)

/**
 * 무엇을 말할지는 경로가 정함.
 * 출발 전에 내비 경로를 읽어 지나갈 명소를 고르고, 순서와 해설 길이를 미리 정함.
 * → 해설을 미리 만들어 캐시해 두면 터널·산간에서 통신이 끊겨도 재생 가능.
 */
class RoutePoiPlanner(private val config: DocentConfig = DocentConfig()) {

    /**
     * @param route 경로 좌표열 (1차: 프로토타입 시연 경로 · 2차: NaviHelper 경로점과 현재 위치로 구성)
     * @param pois 후보 명소
     * @param avgSpeedKmh 평균 속도 추정 (남은 거리 / 남은 시간 · NaviHelper onDrivingInfo)
     */
    fun plan(route: List<LatLng>, pois: List<Poi>, avgSpeedKmh: Double): List<PlannedStop> {
        require(route.size >= 2) { "경로 좌표는 2개 이상" }
        val cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) cumulative[i] = cumulative[i - 1] + distanceM(route[i - 1], route[i])

        val near = pois.mapNotNull { poi ->
            val (along, offset) = project(route, cumulative, poi.location)
            if (offset <= (poi.visibleM ?: config.corridorM)) Triple(poi, along, offset) else null
        }.sortedBy { it.second }

        // 너무 가까이 붙은 명소는 우선순위 높은 쪽만 남김 (최소 해설 길이만큼 간격)
        val mps = avgSpeedKmh / 3.6
        val minGapM = config.minNarrationSec * mps
        val kept = mutableListOf<Triple<Poi, Double, Double>>()
        for (c in near) {
            val last = kept.lastOrNull()
            if (last != null && c.second - last.second < minGapM) {
                if (c.first.priority > last.first.priority) kept[kept.lastIndex] = c
            } else {
                kept += c
            }
        }

        val total = cumulative.last()
        return kept.mapIndexed { i, (poi, along, offset) ->
            val nextAlong = if (i + 1 < kept.size) kept[i + 1].second else total
            val gapSec = if (mps > 0) ((nextAlong - along) / mps).toInt() else config.maxNarrationSec
            PlannedStop(poi, along, offset, gapSec.coerceIn(config.minNarrationSec, config.maxNarrationSec))
        }
    }

    /** 점을 경로에 투영 → (경로를 따라 간 거리, 옆으로 떨어진 거리) */
    private fun project(route: List<LatLng>, cumulative: DoubleArray, p: LatLng): Pair<Double, Double> {
        var bestAlong = 0.0
        var bestOffset = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            // 구간 시작점 기준 평면 좌표(m)
            val k = cos(a.lat * PI / 180)
            val bx = (b.lng - a.lng) * k * M_PER_DEG
            val by = (b.lat - a.lat) * M_PER_DEG
            val px = (p.lng - a.lng) * k * M_PER_DEG
            val py = (p.lat - a.lat) * M_PER_DEG
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            val offset = sqrt(dx * dx + dy * dy)
            if (offset < bestOffset) {
                bestOffset = offset
                bestAlong = cumulative[i] + t * sqrt(len2)
            }
        }
        return bestAlong to bestOffset
    }

    companion object {
        private const val EARTH_R = 6_371_000.0
        private const val M_PER_DEG = EARTH_R * PI / 180

        fun distanceM(a: LatLng, b: LatLng): Double {
            val dLat = (b.lat - a.lat) * PI / 180
            val dLng = (b.lng - a.lng) * PI / 180
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * EARTH_R * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}
