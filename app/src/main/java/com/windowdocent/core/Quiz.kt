package com.windowdocent.core

/**
 * 가족 퀴즈 한 문제 (기능 3).
 * @param must 답에 들어 있어야 하는 낱말 모두 (예: 계룡산 = 닭, 용). 낱말 하나에 「autumn|fall」 처럼 쓰면 그중 하나
 * @param right 맞혔을 때 한마디 · @param wrong 틀렸을 때 한마디(정답을 알려 줌)
 */
data class QuizItem(
    val id: String,
    val question: String,
    val must: List<String>,
    val right: String,
    val wrong: String,
)

/** 퀴즈 채점과 점수 (기능 3). 답은 들은 말(Pleos SpeechToText) 또는 흉내 단추의 글 */
class QuizBook {
    /** 지금 답을 기다리는 문제 */
    var current: QuizItem? = null
        private set
    var asked = 0
        private set
    var right = 0
        private set

    /** 문제별 결과 (여행 기록용 · 맞힘 true) */
    val results = LinkedHashMap<String, Boolean>()

    fun ask(item: QuizItem) {
        current = item
        asked++
    }

    /** 답을 기다리던 문제를 버림 (다음 곳 해설이 시작되면 · 답 없이 지난 문제는 셈하지 않음) */
    fun cancel() {
        current = null
    }

    /** 들은 답을 채점하고 한마디를 돌려줌. 기다리는 문제가 없으면 null */
    fun answer(heard: String): Result? {
        val item = current ?: return null
        current = null
        val ok = judge(item, heard)
        if (ok) right++
        results[item.id] = ok
        return Result(item.id, ok, if (ok) item.right else item.wrong)
    }

    data class Result(val id: String, val ok: Boolean, val line: String)

    companion object {
        /** 띄어쓰기 · 문장부호 · 대소문자를 빼고, 답에 꼭 들어야 하는 낱말이 모두 있으면 정답 */
        fun judge(item: QuizItem, heard: String): Boolean {
            val t = normalize(heard)
            return item.must.isNotEmpty() && item.must.all { w -> w.split('|').any { t.contains(normalize(it)) } }
        }

        fun normalize(s: String): String = s.lowercase().replace(Regex("[\\s\\p{Punct}·~!?.,]"), "")
    }
}
