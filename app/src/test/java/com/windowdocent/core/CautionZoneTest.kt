package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 위험 구간: 300m 앞부터 멈춤 · 지난 뒤 300m(속도 모르면 20초) 더 · 신호가 15초 안 오면 풀림 (CautionZone.kt) */
class CautionZoneTest {
    private val zone = CautionZone()
    private fun school(remain: Double, id: String = "s1") = Hazard(id, "SCHOOL_ZONE", remain)

    @Test fun farAheadIsNotYet() {
        zone.update(listOf(school(800.0)), 0)
        assertNull(zone.active(0, 60.0))
    }

    @Test fun within300mStartsTheZone() {
        zone.update(listOf(school(800.0)), 0)
        zone.update(listOf(school(290.0)), 1_000)
        assertEquals("SCHOOL_ZONE", zone.active(1_000, 60.0))
    }

    @Test fun ignoredTypesNeverHold() {
        zone.update(listOf(Hazard("b", "SPEED_BUMP", 50.0), Hazard("c", "SPEED_CAMERA_FIX", 80.0)), 0)
        assertNull(zone.active(0, 60.0))
    }

    @Test fun schoolZoneCameraHolds() {
        zone.update(listOf(Hazard("c", "SPEED_CAMERA_SCHOOL_ZONE", 120.0)), 0)
        assertEquals("SPEED_CAMERA_SCHOOL_ZONE", zone.active(0, 30.0))
    }

    @Test fun keepsHolding300mAfterPassingThenReleases() {
        zone.update(listOf(school(200.0)), 0)
        zone.update(emptyList(), 5_000)                       // 지남 (목록에서 빠짐)
        assertEquals("SCHOOL_ZONE", zone.active(5_000, 36.0))  // 36km/h = 10m/s
        assertEquals("SCHOOL_ZONE", zone.active(20_000, 36.0)) // 150m 더 감
        zone.update(emptyList(), 30_000)
        assertNull(zone.active(36_000, 36.0))                  // 310m 더 감 → 풀림
    }

    @Test fun zeroRemainCountsAsPassed() {
        zone.update(listOf(school(100.0)), 0)
        zone.update(listOf(school(0.0)), 3_000)
        zone.update(listOf(school(0.0)), 13_000)
        assertNull(zone.active(14_000, 120.0))                 // 33m/s × 11초 = 366m
    }

    @Test fun unknownSpeedReleases20SecondsAfterPassing() {
        zone.update(listOf(school(100.0)), 0)
        zone.update(emptyList(), 2_000)
        zone.update(emptyList(), 12_000)
        assertEquals("SCHOOL_ZONE", zone.active(21_000, null))
        zone.update(emptyList(), 21_500)
        assertNull(zone.active(22_500, null))
    }

    @Test fun noSignalFor15SecondsReleases() {
        zone.update(listOf(school(250.0)), 0)
        assertEquals("SCHOOL_ZONE", zone.active(14_000, 0.0))
        assertNull(zone.active(15_500, 0.0))
    }

    @Test fun nextHazardAfterPassingTakesOver() {
        zone.update(listOf(school(100.0)), 0)
        zone.update(listOf(Hazard("r", "RAILROAD_CROSSING", 250.0)), 4_000)
        assertEquals("RAILROAD_CROSSING", zone.active(4_000, 50.0))
    }
}
