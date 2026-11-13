package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 오늘의 여행 기록 (마-7) */
class TripRecordTest {
    @Test fun collectsPlacesViaHoldsQuizAndMissed() {
        val t = TripRecord()
        t.heard("cheonan", "천안삼거리")
        t.heard("gyeryong:quiz", "퀴즈")              // 꼬리가 붙은 이야기는 곳이 아님
        t.heard("cheonan", "천안삼거리")              // 한 번만
        t.via("juknok", "죽녹원")
        listOf(NarrationMode.NARRATE, NarrationMode.HOLD, NarrationMode.HOLD, NarrationMode.NARRATE, NarrationMode.HOLD).forEach(t::mode)
        val r = t.snapshot(mapOf("cheonan" to true), 1, 1, listOf("계룡산"))
        assertEquals(listOf("cheonan", "juknok"), r.places.map { it.id })
        assertEquals(true, r.places[0].quiz)
        assertEquals(null, r.places[1].quiz)
        assertEquals(listOf("죽녹원"), r.via)
        assertEquals(2, r.holds)                         // 멈춤으로 바뀐 횟수
        assertEquals(listOf("계룡산"), r.missed)
    }
}
