package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 사실 필드에 없는 숫자가 든 초안은 막고 사실 원문으로 (FactGuard.kt) */
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
