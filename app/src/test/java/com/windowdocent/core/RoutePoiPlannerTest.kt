package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

class RoutePoiPlannerTest {
    private val planner = RoutePoiPlanner()

    // 위도 36도에서 동쪽으로 0.1도 (약 9km) 직선 경로
    private val route = listOf(LatLng(36.0, 127.0), LatLng(36.0, 127.05), LatLng(36.0, 127.1))
    private val mPerLngDeg = 6_371_000.0 * PI / 180 * cos(36.0 * PI / 180)

    private fun poi(id: String, lng: Double, latOffset: Double = 0.005, priority: Int = 0) =
        Poi(id, id, LatLng(36.0 + latOffset, lng), priority)

    @Test fun keepsOnlyPoisInsideCorridor() {
        val stops = planner.plan(route, listOf(poi("near", 127.02), poi("far", 127.04, latOffset = 0.05)), 90.0)
        assertEquals(listOf("near"), stops.map { it.poi.id })
    }

    @Test fun ordersByDistanceAlongRoute() {
        val stops = planner.plan(route, listOf(poi("b", 127.08), poi("a", 127.02)), 90.0)
        assertEquals(listOf("a", "b"), stops.map { it.poi.id })
        assertEquals(0.02 * mPerLngDeg, stops[0].alongRouteM, 30.0)
        assertEquals(0.005 * 111_195.0, stops[0].offsetM, 10.0)
    }

    @Test fun crowdedPoisKeepHigherPriority() {
        // 약 90m 간격: 최소 해설 15초 × 25m/s = 375m 보다 가까움
        val stops = planner.plan(route, listOf(poi("low", 127.020, priority = 1), poi("high", 127.021, priority = 5)), 90.0)
        assertEquals(listOf("high"), stops.map { it.poi.id })
    }

    @Test fun narrationLengthFitsGapUntilNextPoi() {
        // 0.01도(약 900m) 간격, 90km/h(25m/s) → 약 36초
        val stops = planner.plan(route, listOf(poi("a", 127.02), poi("b", 127.03), poi("c", 127.09)), 90.0)
        assertEquals(listOf("a", "b", "c"), stops.map { it.poi.id })
        assertEquals((0.01 * mPerLngDeg / 25).toInt().toDouble(), stops[0].narrationSec.toDouble(), 1.0)
        assertEquals(60, stops[1].narrationSec) // 다음 명소까지 멀면 최대 60초
    }

    @Test fun narrationLengthHasMinimum() {
        val stops = planner.plan(route, listOf(poi("end", 127.0995)), 90.0)
        assertEquals(15, stops[0].narrationSec)
    }

    @Test fun landmarkVisibleFromAfarIsKeptBeyondCorridor() {
        // 경로에서 약 5.6km: 기본 폭 3km 밖이지만, 멀리서 보이는 산은 visibleM 만큼 넓게 봄
        val mountain = Poi("mountain", "mountain", LatLng(36.05, 127.04), visibleM = 8000.0)
        val museum = Poi("museum", "museum", LatLng(36.05, 127.06))
        val stops = planner.plan(route, listOf(mountain, museum), 90.0)
        assertEquals(listOf("mountain"), stops.map { it.poi.id })
    }

    @Test fun visibleRangeShorterThanOffsetIsDropped() {
        val hill = Poi("hill", "hill", LatLng(36.05, 127.04), visibleM = 4000.0)
        assertEquals(emptyList<String>(), planner.plan(route, listOf(hill), 90.0).map { it.poi.id })
    }

    @Test fun routeNeedsTwoPoints() {
        assertThrows(IllegalArgumentException::class.java) { planner.plan(listOf(route[0]), emptyList(), 90.0) }
    }

    @Test fun haversineMatchesKnownDistance() {
        // 서울역 → 광화문 직선 약 2.4km
        val d = RoutePoiPlanner.distanceM(LatLng(37.5547, 126.9707), LatLng(37.5759, 126.9768))
        assertEquals(2410.0, d, 150.0)
    }
}
