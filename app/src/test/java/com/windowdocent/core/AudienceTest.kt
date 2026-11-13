package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 좌석 착석 · 승객 언어 → 운전자 · 가족 · 외국어 승객 모드 (Audience.kt) */
class AudienceTest {
    @Test fun aloneIsDriverMode() =
        assertEquals(Audience.DRIVER, AudienceSelector.select(Seats()).audience)

    @Test fun frontPassengerOnlyIsDriverMode() =
        assertEquals(Audience.DRIVER, AudienceSelector.select(Seats(frontPassenger = true)).audience)

    @Test fun rearSeatIsFamilyMode() =
        assertEquals(Audience.FAMILY, AudienceSelector.select(Seats(rearCount = 1)).audience)

    @Test fun guestLanguageWinsOverFamily() {
        val r = AudienceSelector.select(Seats(rearCount = 2), guestLanguage = "es")
        assertEquals(AudienceResult(Audience.FOREIGN_GUEST, "es"), r)
    }

    @Test fun koreanGuestStaysFamily() =
        assertEquals(Audience.FAMILY, AudienceSelector.select(Seats(rearCount = 1), "ko").audience)
}
