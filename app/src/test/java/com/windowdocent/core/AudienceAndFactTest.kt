package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudienceSelectorTest {
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

class FactGuardTest {
    private val facts = listOf("경복궁은 1395년에 지어진 조선의 법궁이다.", "면적 432,703㎡")

    @Test fun numbersFromFactsPass() =
        assertTrue(FactGuard.check("1395년에 지어진 궁궐이에요.", facts).ok)

    @Test fun inventedYearIsRejected() {
        val c = FactGuard.check("1392년에 지어졌어요.", facts)
        assertFalse(c.ok)
        assertEquals(listOf("1392"), c.unknownNumbers)
    }

    @Test fun commaFormattingIsIgnored() =
        assertTrue(FactGuard.check("넓이는 432703㎡예요.", facts).ok)

    @Test fun noNumbersPass() =
        assertTrue(FactGuard.check("오른쪽에 보이는 곳이 경복궁이에요.", facts).ok)

    @Test fun rejectedTextFallsBackToFact() =
        assertEquals(facts[0], FactGuard.safeText("600년 넘은 궁이에요.", facts))
}
