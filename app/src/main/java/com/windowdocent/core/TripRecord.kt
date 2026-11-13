package com.windowdocent.core

/**
 * 오늘의 여행 기록 (기능 3 도착 기록 · 차량 특화 5 내릴 때 · 추가 3 못 들은 이야기).
 * 해설을 시작한 곳, 들른 곳, 운전 상황으로 멈춘 횟수를 모으고, 퀴즈 결과와 못 한 이야기를 받아 한 벌로 냄.
 */
class TripRecord {
    private val heard = LinkedHashMap<String, String>()
    private val via = LinkedHashMap<String, String>()
    private var lastMode: NarrationMode? = null

    /** 해설 방식이 멈춤으로 바뀐 횟수 (방향지시등 · 큰 조향 · 내비 음성 · 위험 구간) */
    var holds = 0
        private set

    /** 해설을 시작한 곳 (이야기 id 에 꼬리(:)가 붙은 한 줄 · 퀴즈 · 제안은 빼고) */
    fun heard(id: String, name: String) {
        if (id.isNotBlank() && !id.contains(':')) heard.putIfAbsent(id, name)
    }

    /** 들른 곳 (「가자」 로 넣은 경유지) */
    fun via(id: String, name: String) {
        via[id] = name
    }

    fun mode(m: NarrationMode) {
        if (m == NarrationMode.HOLD && lastMode != NarrationMode.HOLD) holds++
        lastMode = m
    }

    data class PlaceLine(val id: String, val name: String, val quiz: Boolean?)

    data class Record(
        val places: List<PlaceLine>,
        val quizAsked: Int,
        val quizRight: Int,
        val via: List<String>,
        val holds: Int,
        val missed: List<String>,
    )

    /** @param quiz 퀴즈 결과(곳 id → 맞힘) · @param missed 못 한 이야기 제목 */
    fun snapshot(quiz: Map<String, Boolean>, asked: Int, right: Int, missed: List<String>): Record {
        val places = LinkedHashMap<String, String>(heard)
        via.forEach { (id, name) -> places.putIfAbsent(id, name) }
        return Record(places.map { (id, name) -> PlaceLine(id, name, quiz[id]) }, asked, right, via.values.toList(), holds, missed)
    }
}
