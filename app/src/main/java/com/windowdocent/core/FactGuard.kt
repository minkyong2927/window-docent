package com.windowdocent.core

/**
 * 환각 차단: LLM은 말투·길이·언어만 담당하고, 사실은 TourAPI 필드에서만.
 * LLM 출력에 입력 사실 필드에 없는 숫자(연도·거리·높이 등)가 있으면 거부 → 사실 필드 원문으로 대체.
 */
object FactGuard {
    private val NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")

    data class Check(val ok: Boolean, val unknownNumbers: List<String>)

    fun check(output: String, facts: List<String>): Check {
        val known = facts.flatMap { numbersIn(it) }.toSet()
        val unknown = numbersIn(output).filter { it !in known }.distinct()
        return Check(unknown.isEmpty(), unknown)
    }

    /** 통과하면 LLM 문장, 아니면 사실 필드 첫 문장 */
    fun safeText(output: String, facts: List<String>): String =
        if (check(output, facts).ok) output else facts.firstOrNull().orEmpty()

    /** "1,392년" 과 "1392" 를 같은 숫자로 봄 */
    private fun numbersIn(text: String): List<String> =
        NUMBER.findAll(text).map { it.value.replace(",", "") }.toList()
}
