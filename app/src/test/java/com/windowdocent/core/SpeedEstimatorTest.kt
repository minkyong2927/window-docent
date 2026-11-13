package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedEstimatorTest {
    private val start = LatLng(36.0, 127.0)

    /** 출발점에서 북쪽으로 meters 만큼 간 점 */
    private fun north(meters: Double) = LatLng(start.lat + meters / 111_194.93, start.lng)

    /** 초속 mps 로 seconds 동안 달렸을 때의 위치를 1초마다 넣고 마지막 속도를 돌려줌 */
    private fun drive(estimator: SpeedEstimator, mps: Double, seconds: Int, fromM: Double = 0.0, fromMs: Long = 0): Double? {
        var speed: Double? = null
        for (s in 0..seconds) speed = estimator.update(north(fromM + mps * s), fromMs + s * 1000L)
        return speed
    }

    @Test fun firstFixAloneGivesNoSpeed() =
        assertNull(SpeedEstimator().update(start, 0))

    @Test fun twoFixesGiveSpeedFromDistanceAndTime() {
        val estimator = SpeedEstimator()
        estimator.update(start, 0)
        // 1초에 27.78m = 시속 100km
        assertEquals(100.0, estimator.update(north(27.78), 1000)!!, 0.5)
    }

    @Test fun steadyDrivingStaysAtThatSpeed() =
        assertEquals(90.0, drive(SpeedEstimator(), mps = 25.0, seconds = 10)!!, 0.5)

    @Test fun speedChangeIsSmoothed() {
        val estimator = SpeedEstimator(smoothing = 0.5)
        drive(estimator, mps = 27.78, seconds = 3)                       // 시속 100km
        val next = estimator.update(north(27.78 * 3 + 16.67), 4000)!!    // 다음 1초는 시속 60km
        assertEquals(80.0, next, 1.0)                                    // 절반만 반영
    }

    @Test fun standingStillIsReportedAtOnce() {
        val estimator = SpeedEstimator()
        drive(estimator, mps = 27.78, seconds = 3)
        val stopped = estimator.update(north(27.78 * 3), 4000)!!         // 같은 자리
        assertEquals(0.0, stopped, 0.01)
    }

    @Test fun fixesTooCloseInTimeAreMergedWithTheNextOne() {
        val estimator = SpeedEstimator(minIntervalMs = 400)
        estimator.update(start, 0)
        assertNull(estimator.update(north(2.8), 100))                    // 0.1초 뒤: 아직 계산 안 함
        assertEquals(100.0, estimator.update(north(27.78), 1000)!!, 0.5) // 첫 점부터 1초로 계산
    }

    @Test fun positionJumpIsIgnored() {
        val estimator = SpeedEstimator()
        drive(estimator, mps = 25.0, seconds = 3)                        // 시속 90km
        val afterJump = estimator.update(north(25.0 * 3 + 5000.0), 4000) // 1초에 5km: 위치가 튄 것
        assertEquals(90.0, afterJump!!, 0.5)
    }

    @Test fun longGapStartsOver() {
        val estimator = SpeedEstimator(staleMs = 5000)
        drive(estimator, mps = 25.0, seconds = 3)
        assertNull(estimator.update(north(2000.0), 60_000))              // 터널을 나온 뒤 첫 위치
        assertEquals(90.0, estimator.update(north(2025.0), 61_000)!!, 0.5)
    }

    @Test fun speedBecomesUnknownWhenNoFixArrives() {
        val estimator = SpeedEstimator(staleMs = 5000)
        drive(estimator, mps = 25.0, seconds = 3)
        assertEquals(90.0, estimator.current(4000)!!, 0.5)
        assertNull(estimator.current(9000))
    }

    @Test fun timeGoingBackwardsStartsOver() {
        val estimator = SpeedEstimator()
        drive(estimator, mps = 25.0, seconds = 3)
        assertNull(estimator.update(north(100.0), 1000))
    }
}
