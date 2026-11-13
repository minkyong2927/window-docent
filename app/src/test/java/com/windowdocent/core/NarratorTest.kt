package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 바로 멈추고 이어 말하기: 문장 단위로 말하고, 멈춤이면 바로 끊고, 풀리면 한마디 뒤 끊긴 문장부터 */
class NarratorTest {

    /** 말이 끝나는 때를 시험이 정하는 장치 */
    private class FakeSpeaker : Speaker {
        val said = ArrayList<String>()
        var stops = 0
        private var done: (() -> Unit)? = null
        private var failed: ((String) -> Unit)? = null

        override fun speak(text: String, language: String, done: () -> Unit, failed: (String) -> Unit) {
            said += text
            this.done = done
            this.failed = failed
        }

        override fun stop() {
            stops++
            done = null
            failed = null
        }

        fun finish() {
            val d = done
            done = null
            d?.invoke()
        }

        fun fail(why: String) {
            val f = failed
            failed = null
            f?.invoke(why)
        }
    }

    private var now = 0L
    private val speaker = FakeSpeaker()
    private val narrator = Narrator(speaker, clock = { now })
    private val story = Narrator.Story("gyeryong", "계룡산", listOf("첫 문장이에요.", "둘째 문장이에요.", "셋째 문장이에요."))

    @Test fun speaksSentenceBySentence() {
        narrator.play(story)
        assertEquals(listOf("첫 문장이에요."), speaker.said)
        speaker.finish()
        speaker.finish()
        assertEquals(3, speaker.said.size)
        speaker.finish()
        val s = narrator.snapshot()
        assertEquals(Narrator.State.DONE, s.state)
        assertEquals(3, s.index)
        assertNull(s.speaking)
    }

    @Test fun holdCutsImmediatelyAndResumesTheCutSentence() {
        narrator.play(story)
        speaker.finish()                                   // 첫 문장 끝 · 둘째 문장 말하는 중
        narrator.onMode(NarrationMode.HOLD)                // 방향지시등: 바로 끊음
        assertEquals(1, speaker.stops)
        assertEquals(Narrator.State.PAUSED, narrator.snapshot().state)
        assertTrue(narrator.snapshot().lines.last().cut)

        narrator.onMode(NarrationMode.HOLD)                // 멈춤 중에는 아무 말 안 함
        assertEquals(2, speaker.said.size)

        narrator.onMode(NarrationMode.NARRATE)             // 풀리면 한마디 뒤 끊긴 문장을 처음부터
        assertEquals(Narrator.RESUME_LINE, speaker.said[2])
        speaker.finish()
        assertEquals("둘째 문장이에요.", speaker.said[3])
        speaker.finish()
        assertEquals("셋째 문장이에요.", speaker.said[4])
        speaker.finish()
        assertEquals(Narrator.State.DONE, narrator.snapshot().state)
        assertEquals(
            listOf("첫 문장이에요.", "둘째 문장이에요. —", Narrator.RESUME_LINE, "둘째 문장이에요.", "셋째 문장이에요."),
            narrator.snapshot().lines.map { it.text + if (it.cut) " —" else "" },
        )
    }

    @Test fun holdBetweenSentencesNeedsNoResumeLine() {
        narrator.play(story)
        narrator.onMode(NarrationMode.HOLD)
        // 말하던 중이었으므로 끊고, 풀리면 한마디
        narrator.onMode(NarrationMode.NARRATE)
        assertEquals(Narrator.RESUME_LINE, speaker.said.last())

        val quiet = FakeSpeaker()
        val n = Narrator(quiet)
        n.onMode(NarrationMode.HOLD)
        n.play(story)                                      // 멈춤 중에 받은 해설은 기다림
        assertTrue(quiet.said.isEmpty())
        n.onMode(NarrationMode.NARRATE)                    // 끊긴 것이 없으니 한마디 없이 처음부터
        assertEquals(listOf("첫 문장이에요."), quiet.said)
    }

    @Test fun cutDuringResumeLineSaysItAgain() {
        narrator.play(story)
        narrator.onMode(NarrationMode.HOLD)
        narrator.onMode(NarrationMode.NARRATE)
        narrator.onMode(NarrationMode.HOLD)                // 한마디 하다 또 멈춤
        narrator.onMode(NarrationMode.NARRATE)
        assertEquals(listOf("첫 문장이에요.", Narrator.RESUME_LINE, Narrator.RESUME_LINE), speaker.said)
        speaker.finish()
        assertEquals("첫 문장이에요.", speaker.said.last())
    }

    @Test fun oneLineFinishesTheCurrentSentenceThenWaits() {
        narrator.play(story)
        narrator.onMode(NarrationMode.ONE_LINE)            // 분기점 접근: 끊지 않음
        assertEquals(0, speaker.stops)
        speaker.finish()                                   // 지금 문장이 그 한 문장 · 다음은 기다림
        assertEquals(1, speaker.said.size)
        assertEquals(Narrator.State.WAITING, narrator.snapshot().state)
        narrator.onMode(NarrationMode.NARRATE)             // 지나가면 다음 문장부터 (한마디 없음)
        assertEquals("둘째 문장이에요.", speaker.said.last())
    }

    @Test fun oneLineAllowsOneNewSentence() {
        narrator.onMode(NarrationMode.ONE_LINE)
        narrator.play(story)
        assertEquals(listOf("첫 문장이에요."), speaker.said)
        speaker.finish()
        assertEquals(1, speaker.said.size)
    }

    @Test fun nearThenNaviVoiceThenCruisingLikeTheDemo() {
        // 시연 장면: 분기점 접근 중 해설 시작 → 내비 음성으로 끊김 → 지나간 뒤 한마디와 끊긴 문장부터
        narrator.onMode(NarrationMode.ONE_LINE)
        narrator.play(story)
        narrator.onMode(NarrationMode.HOLD)
        narrator.onMode(NarrationMode.HOLD)
        narrator.onMode(NarrationMode.NARRATE)
        speaker.finish()
        speaker.finish()
        speaker.finish()
        assertEquals(
            listOf("첫 문장이에요.", Narrator.RESUME_LINE, "첫 문장이에요.", "둘째 문장이에요.", "셋째 문장이에요."),
            speaker.said,
        )
    }

    @Test fun doorOpenEndsTheStory() {
        narrator.play(story)
        narrator.onMode(NarrationMode.TRIP_RECORD)
        assertEquals(1, speaker.stops)
        assertEquals(Narrator.State.DONE, narrator.snapshot().state)
        narrator.onMode(NarrationMode.NARRATE)
        assertEquals(1, speaker.said.size)
    }

    @Test fun newStoryReplacesTheOldOne() {
        narrator.play(story)
        narrator.play(Narrator.Story("quiz", "퀴즈", listOf("문제예요?")))
        assertEquals(1, speaker.stops)
        assertEquals("문제예요?", speaker.said.last())
        assertEquals(listOf("문제예요?"), narrator.snapshot().lines.map { it.text })
        speaker.finish()
        assertEquals(Narrator.State.DONE, narrator.snapshot().state)
    }

    @Test fun lateCallbackFromAStoppedSentenceIsIgnored() {
        val late = ArrayList<() -> Unit>()
        val n = Narrator(object : Speaker {
            override fun speak(text: String, language: String, done: () -> Unit, failed: (String) -> Unit) { late += done }
            override fun stop() {}
        })
        n.play(story)
        n.onMode(NarrationMode.HOLD)
        late[0]()                                          // 끊은 뒤 늦게 온 '다 말함'
        assertEquals(Narrator.State.PAUSED, n.snapshot().state)
        assertEquals(0, n.snapshot().index)
    }

    @Test fun failureMovesOnAndIsReported() {
        narrator.play(story)
        speaker.fail("UNAVAILABLE_LANGUAGE")
        val s = narrator.snapshot()
        assertEquals(1, s.index)
        assertEquals("UNAVAILABLE_LANGUAGE", s.error)
        assertEquals("둘째 문장이에요.", speaker.said.last())
    }

    @Test fun splitsIntoSentences() {
        assertEquals(
            listOf("정답이에요!", "닭 계(鷄), 용 룡(龍).", "1점!"),
            Narrator.split("정답이에요! 닭 계(鷄), 용 룡(龍). 1점!"),
        )
        assertEquals(listOf("계룡산 이름에 숨어 있는 동물 두 마리는 뭘까요?"), Narrator.split(" 계룡산 이름에 숨어 있는 동물 두 마리는 뭘까요? "))
        assertTrue(Narrator.split("  ").isEmpty())
    }

    // ── 다시 말하기 · 못 한 이야기 (추가 3) ─────────────────────────────────────

    @Test fun secondCutOfTheSameSentenceSkipsToTheNext() {
        narrator.play(story)
        speaker.finish()                                   // 둘째 문장 말하는 중
        narrator.onMode(NarrationMode.HOLD)                // 한 번 끊김
        narrator.onMode(NarrationMode.NARRATE)
        speaker.finish()                                   // 한마디 끝 · 둘째 문장 다시
        narrator.onMode(NarrationMode.HOLD)                // 같은 문장이 또 끊김
        narrator.onMode(NarrationMode.NARRATE)
        assertEquals(Narrator.RESUME_LINE, speaker.said.last())
        speaker.finish()
        assertEquals("셋째 문장이에요.", speaker.said.last())   // 둘째는 또 말하지 않음
        assertEquals(listOf("둘째 문장이에요."), narrator.snapshot().missed.single().sentences)
    }

    @Test fun lastSentenceCutTwiceEndsWithoutResumeLine() {
        val n = Narrator(speaker)
        n.play(Narrator.Story("one", "한 문장", listOf("하나뿐이에요.")))
        n.onMode(NarrationMode.HOLD); n.onMode(NarrationMode.NARRATE); speaker.finish()
        n.onMode(NarrationMode.HOLD); n.onMode(NarrationMode.NARRATE)
        assertEquals(Narrator.State.DONE, n.snapshot().state)
        assertEquals(listOf("하나뿐이에요.", Narrator.RESUME_LINE, "하나뿐이에요."), speaker.said)
    }

    @Test fun storyReplacedWhileHeldKeepsTheRestAsMissed() {
        narrator.play(story)
        speaker.finish()
        narrator.onMode(NarrationMode.HOLD)                // 둘째 문장에서 멈춘 채 다음 명소로
        narrator.play(Narrator.Story("next", "다음 명소", listOf("다음 이야기예요.")))
        val m = narrator.snapshot().missed.single()
        assertEquals("gyeryong", m.storyId)
        assertEquals(listOf("둘째 문장이에요.", "셋째 문장이에요."), m.sentences)
    }

    @Test fun nothingMissedNeverOffers() {
        narrator.play(story)
        repeat(3) { speaker.finish() }
        narrator.onMode(NarrationMode.LONG_STORY)
        now = 10_000
        narrator.tick()
        narrator.arrived()
        assertEquals(3, speaker.said.size)
        assertEquals(false, narrator.snapshot().awaitingAnswer)
    }

    @Test fun stoppedFiveSecondsOffersOnceAndYesPlaysTheMissed() {
        missOneSentence()
        val before = speaker.said.size
        narrator.onMode(NarrationMode.LONG_STORY)          // 정차
        now += 4_000; narrator.tick()
        assertEquals(before, speaker.said.size)            // 5초 전에는 묻지 않음
        now += 1_500; narrator.tick()
        assertEquals(Narrator.offerLine(1), speaker.said.last())
        speaker.finish()
        assertTrue(narrator.snapshot().awaitingAnswer)
        now += 30_000; narrator.tick()                     // 같은 정차에서 또 묻지 않음
        assertEquals(before + 1, speaker.said.size)
        narrator.answer(true)                              // 응 · 들려줘
        assertEquals("둘째 문장이에요.", speaker.said.last())
        assertTrue(narrator.snapshot().missed.isEmpty())
    }

    @Test fun laterThenArrivalAsksOnceMore() {
        missOneSentence()
        narrator.onMode(NarrationMode.LONG_STORY)
        now += 6_000; narrator.tick()
        speaker.finish()
        narrator.answer(false)                             // 나중에
        assertEquals(1, narrator.snapshot().missed.size)
        narrator.onMode(NarrationMode.NARRATE)
        narrator.onMode(NarrationMode.LONG_STORY)
        now += 10_000; narrator.tick()                     // 다른 정차: 정차 제안은 한 번뿐
        assertEquals(1, speaker.said.count { it == Narrator.offerLine(1) })
        narrator.onMode(NarrationMode.TRIP_RECORD)         // 도어 열림이어도 도착 제안은 말함
        narrator.arrived()
        assertEquals(2, speaker.said.count { it == Narrator.offerLine(1) })
        narrator.arrived()                                 // 도착 제안도 한 번
        assertEquals(2, speaker.said.count { it == Narrator.offerLine(1) })
    }

    @Test fun doorOpenWhileHeldKeepsTheRestButWhileSpeakingDoesNot() {
        narrator.play(story)
        narrator.onMode(NarrationMode.TRIP_RECORD)         // 말하던 중 도어 열림: 못 한 이야기 아님
        assertTrue(narrator.snapshot().missed.isEmpty())
        narrator.onMode(NarrationMode.NARRATE)
        narrator.play(story)
        speaker.finish()
        narrator.onMode(NarrationMode.HOLD)                // 멈춘 채 도착해 도어 열림: 남은 문장은 못 한 이야기
        narrator.onMode(NarrationMode.TRIP_RECORD)
        assertEquals(listOf("둘째 문장이에요.", "셋째 문장이에요."), narrator.snapshot().missed.single().sentences)
    }

    /** 둘째 문장을 두 번 끊어 못 한 이야기 하나를 만들고 해설을 끝냄 */
    private fun missOneSentence() {
        narrator.play(story)
        speaker.finish()
        narrator.onMode(NarrationMode.HOLD); narrator.onMode(NarrationMode.NARRATE); speaker.finish()
        narrator.onMode(NarrationMode.HOLD); narrator.onMode(NarrationMode.NARRATE); speaker.finish()
        speaker.finish()                                   // 셋째 문장 끝
        assertEquals(Narrator.State.DONE, narrator.snapshot().state)
    }

    // ── 다시라고 말하면 반복 (추가 3 둘째) ─────────────────────────────────────────

    @Test fun repeatWhileSpeakingRestartsThatSentence() {
        narrator.play(story)
        speaker.finish()                                   // 둘째 문장 말하는 중
        assertTrue(narrator.repeat())
        assertEquals(1, speaker.stops)
        assertEquals("둘째 문장이에요.", speaker.said.last())
        speaker.finish()
        assertEquals("셋째 문장이에요.", speaker.said.last()) // 그다음은 이어서
    }

    @Test fun repeatAfterTheEndWithin30SecondsSaysTheLastSentenceAgain() {
        narrator.play(story)
        repeat(3) { speaker.finish() }
        now += 20_000
        assertTrue(narrator.repeat())
        assertEquals("셋째 문장이에요.", speaker.said.last())
        speaker.finish()
        assertEquals(Narrator.State.DONE, narrator.snapshot().state)
        now += 31_000
        assertFalse(narrator.repeat())                     // 30초가 지나면 안 함
    }

    @Test fun repeatBetweenSentencesThenContinues() {
        narrator.play(story)
        narrator.onMode(NarrationMode.ONE_LINE)            // 지금 문장만 끝내고 기다림
        speaker.finish()
        assertEquals(Narrator.State.WAITING, narrator.snapshot().state)
        assertTrue(narrator.repeat())                      // 방금 끝난 첫 문장을 한 번 더
        assertEquals("첫 문장이에요.", speaker.said.last())
        speaker.finish()
        assertEquals(Narrator.State.WAITING, narrator.snapshot().state)
        narrator.onMode(NarrationMode.NARRATE)
        assertEquals("둘째 문장이에요.", speaker.said.last())
    }

    @Test fun repeatWhileHeldDoesNothing() {
        narrator.play(story)
        narrator.onMode(NarrationMode.HOLD)
        assertFalse(narrator.repeat())
        assertEquals(1, speaker.said.size)
    }

    @Test fun repeatTheOfferKeepsWaitingForTheAnswer() {
        missOneSentence()
        narrator.arrived()
        speaker.finish()
        assertTrue(narrator.repeat())
        assertEquals(Narrator.offerLine(1), speaker.said.last())
        speaker.finish()
        assertTrue(narrator.snapshot().awaitingAnswer)
    }
}
