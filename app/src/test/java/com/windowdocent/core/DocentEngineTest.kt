package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DocentEngineTest {
    private val engine = DocentEngine()
    private val source = PrototypeSignalSource().also { engine.attach(it) }
    private val facts = listOf("계룡산은 1968년에 국립공원으로 지정됐다.")

    @Test fun decidesFromLatestSignalsOfTheSource() {
        source.push(mapOf("speed" to 98, "rear" to true))
        assertEquals(Decision(NarrationMode.NARRATE, GateReason.CRUISING, Audience.FAMILY, "ko"), engine.decide())

        source.push(mapOf("speed" to 98, "turn" to "right"))
        assertEquals(Decision(NarrationMode.HOLD, GateReason.TURN_SIGNAL, Audience.DRIVER, "ko"), engine.decide())
    }

    @Test fun guestLanguageSwitchesToForeignGuestMode() {
        source.push(mapOf("speed" to 40, "rear" to true))
        engine.guestLanguage = "es"
        assertEquals(Decision(NarrationMode.NARRATE, GateReason.CRUISING, Audience.FOREIGN_GUEST, "es"), engine.decide())
    }

    @Test fun attachingNewSourceStopsTheOldOne() {
        val next = PrototypeSignalSource()
        engine.attach(next)
        source.push(mapOf("door" to true))
        assertEquals(GateReason.CRUISING, engine.decide().reason)

        next.push(mapOf("door" to true))
        assertEquals(GateReason.DOOR_OPEN, engine.decide().reason)
    }

    @Test fun narrateKeepsDraftWhenNumbersAreInFacts() {
        val draft = "계룡산은 1968년에 국립공원이 된 산이에요."
        assertEquals(GuardedText(draft, true, emptyList()), engine.narrate(draft, facts))
    }

    @Test fun narrateFallsBackToFactWhenNumberIsInvented() =
        assertEquals(
            GuardedText(facts[0], false, listOf("1392")),
            engine.narrate("계룡산은 1392년에 국립공원이 됐어요.", facts),
        )

    @Test fun planUsesTheEngineConfig() {
        val route = listOf(LatLng(36.0, 127.0), LatLng(36.0, 127.1))
        val poi = Poi("near", "near", LatLng(36.005, 127.05))
        assertEquals(listOf("near"), engine.plan(route, listOf(poi), 90.0).map { it.poi.id })
        val narrow = DocentEngine(DocentConfig(corridorM = 300.0))
        assertEquals(emptyList<String>(), narrow.plan(route, listOf(poi), 90.0).map { it.poi.id })
    }
}
