package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 가족 퀴즈 채점 (기능 3) */
class QuizTest {
    private val gyeryong = QuizItem("gyeryong", "계룡산 이름에 숨어 있는 동물 두 마리는 뭘까요?", listOf("닭", "용"), "정답이에요!", "아쉬워요. 정답은 닭과 용이에요.")

    @Test fun allMustWordsMakeItRight() {
        assertTrue(QuizBook.judge(gyeryong, "닭이랑 용!"))
        assertTrue(QuizBook.judge(gyeryong, "용 그리고 닭"))
        assertFalse(QuizBook.judge(gyeryong, "용이요"))       // 하나만 맞으면 오답
        assertFalse(QuizBook.judge(gyeryong, ""))
    }

    @Test fun spacesAndMarksAreIgnored() {
        val q = QuizItem("cheonan", "나무는?", listOf("능수버들"), "", "")
        assertTrue(QuizBook.judge(q, "능수 버들!"))
    }

    @Test fun scoreCountsAskedAndRight() {
        val book = QuizBook()
        assertNull(book.answer("닭"))                          // 묻지 않았으면 채점 안 함
        book.ask(gyeryong)
        val r = book.answer("닭이랑 용")!!
        assertTrue(r.ok)
        assertEquals("정답이에요!", r.line)
        book.ask(gyeryong)
        assertFalse(book.answer("호랑이")!!.ok)
        assertEquals(2, book.asked)
        assertEquals(1, book.right)
        assertEquals(mapOf("gyeryong" to false), book.results.toMap())
    }
}
