package com.windowdocent.core

/** 누구에게 말할지 */
enum class Audience {
    /** 동승자 없음: 짧고 드문드문 */
    DRIVER,
    /** 뒷좌석 착석: 짧은 이야기 + 음성 퀴즈 */
    FAMILY,
    /** 승객이 언어를 고름: 승객 언어 해설 + 한국어 한마디 */
    FOREIGN_GUEST,
}

data class AudienceResult(val audience: Audience, val language: String)

/**
 * 좌석 정보와 승객 언어로 모드를 자동 전환.
 * 승객이 언어를 말하면(STT) 그 언어가 가족 모드보다 우선.
 */
object AudienceSelector {
    const val KOREAN = "ko"

    fun select(seats: Seats, guestLanguage: String? = null): AudienceResult = when {
        guestLanguage != null && guestLanguage != KOREAN ->
            AudienceResult(Audience.FOREIGN_GUEST, guestLanguage)
        seats.rearCount > 0 -> AudienceResult(Audience.FAMILY, KOREAN)
        else -> AudienceResult(Audience.DRIVER, KOREAN)
    }
}
