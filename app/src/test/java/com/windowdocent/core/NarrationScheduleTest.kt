package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 해설 예정과 시작 판단이 같은 시작 지점을 쓰는지 (NarrationSchedule.kt) */
class NarrationScheduleTest {
    private fun stop(id: String, alongM: Double, sec: Int) = PlannedStop(Poi(id, id, LatLng(0.0, 0.0)), alongM, 0.0, sec)

    // 90km/h = 25m/s · 60초 해설은 1,500m 앞에서 시작
    private val schedule = NarrationSchedule(
        listOf(stop("a", 10_000.0, 60), stop("b", 10_500.0, 30), stop("c", 40_000.0, 15)),
        avgSpeedKmh = 90.0,
    )

    @Test fun startPointEndsTheNarrationBesideThePoi() {
        val a = schedule.entries[0]
        assertEquals(8_500.0, a.startM, 0.001)
        assertEquals(340.0, a.startAfterSec, 0.001)          // 8,500m ÷ 25m/s
        assertTrue(a.story)
    }

    @Test fun nextStartWaitsForThePreviousToEnd() {
        val b = schedule.entries[1]
        assertEquals(10_000.0, b.startM, 0.001)              // 9,750 이 아니라 a 가 끝나는 10,000 에서
        assertTrue(b.story)
        assertFalse(schedule.entries[2].story)               // 15초 = 한 줄
    }

    @Test fun startAtTheBeginningWhenThePoiIsClose() =
        assertEquals(0.0, NarrationSchedule.startPoint(500.0, 60, 0.0, 90.0), 0.001)

    @Test fun statusFollowsTheSameStartPoint() {
        val a = schedule.entries[0]
        assertEquals(ScheduleStatus.PLANNED, schedule.status(a, 8_499.0))
        assertEquals(ScheduleStatus.DUE, schedule.status(a, 8_500.0))
        assertEquals(ScheduleStatus.PASSED, schedule.status(a, 10_301.0))
    }

    @Test fun tickStartsOnceWhenReachingTheStartPoint() {
        assertNull(schedule.tick(8_000.0).start)
        assertEquals("a", schedule.tick(8_600.0).start?.id)
        assertNull(schedule.tick(8_700.0).start)              // 한 번만
        assertEquals(ScheduleStatus.STARTED, schedule.status(schedule.entries[0], 8_700.0))
    }

    @Test fun jumpingPastSeveralStartsPlaysTheLatestAndMissesTheRest() {
        val t = schedule.tick(10_100.0)                     // a, b 시작 지점을 한꺼번에 지남
        assertEquals("b", t.start?.id)
        assertEquals(listOf("a"), t.missed.map { it.id })
        assertEquals(ScheduleStatus.PASSED, schedule.status(schedule.entries[0], 10_100.0))
    }

    @Test fun passingWithoutStartingIsMissedOnce() {
        val s = NarrationSchedule(listOf(stop("x", 5_000.0, 60)), 90.0)
        s.tick(1_000.0)
        assertEquals(listOf("x"), s.tick(6_000.0).missed.map { it.id })   // 시작 지점 · 명소를 다 지나침
        assertTrue(s.tick(7_000.0).missed.isEmpty())
    }

    @Test fun remainingTimeUsesSpeedWhenKnown() {
        val c = schedule.entries[2]                          // 40,000 - 375 = 39,625m 에서 시작
        assertEquals(39_625.0, c.startM, 0.001)
        assertEquals((39_625.0 - 25_000.0) / 25.0, schedule.secondsUntil(c, 25_000.0, null), 0.01)
        assertEquals((39_625.0 - 25_000.0) / (120.0 / 3.6), schedule.secondsUntil(c, 25_000.0, 120.0), 0.01)
        assertEquals((39_625.0 - 25_000.0) / 25.0, schedule.secondsUntil(c, 25_000.0, 2.0), 0.01)   // 거의 정차면 평균 속도
        assertEquals("c", schedule.next(25_000.0)?.id)
    }
}
