package com.windowdocent.core

/** 들은 말에서 알아낸 명령 */
enum class Command {
    /** 다시 · 다시 말해 줘 · 뭐라고 · 한 번 더 · 못 들었어 → 방금 말을 한 번 더 (추가 3 둘째) */
    REPEAT,

    /** 응 · 들려줘 · 좋아 → 「지금 들려드릴까요?」 에 예 */
    YES,

    /** 아니 · 나중에 → 「지금 들려드릴까요?」 에 아니오 */
    NO,

    /** 가자 · 들르자 → 들러볼 곳을 경유지로 (구현 5번) */
    GO,

    /** 알아들을 명령이 없음 */
    NONE,
}

/**
 * 음성 입력 장치 (Pleos SpeechToText). 한 번 부르면 한 마디를 듣고 끝남.
 * 차량 마이크 음성은 Pleos SpeechToText SDK 로만 인식하고 바깥 음성 인식 서비스로 보내지 않음 (공모전 공지).
 */
interface VoiceInput {
    /** 한 마디 듣기 시작. 다 들으면 heard(들은 글), 못 들으면 failed(까닭) */
    fun listen(language: String, heard: (String) -> Unit, failed: (String) -> Unit)

    fun stop()

    fun release() {}
}

/**
 * 음성 명령 듣기 (구현 4번): 들은 글을 명령으로 바꿈.
 * 띄어쓰기 · 문장부호 · 대소문자는 보지 않음. 한 글자 낱말(응 · 네 · 예)은 그 말만 했을 때만.
 * 여러 명령 낱말이 섞이면 다시 → 아니 → 가자 → 응 차례로 봄 (「다시 들려줘」 는 다시).
 */
object VoiceCommand {
    private val RULES: List<Pair<Command, List<String>>> = listOf(
        Command.REPEAT to listOf("다시", "뭐라고", "한번더", "못들었", "again", "repeat", "pardon", "otravez", "repite"),
        Command.NO to listOf("아니", "나중에", "됐어", "싫어", "later", "notnow", "nothanks", "despues", "después"),
        Command.GO to listOf("가자", "가줘", "들르자", "들러줘", "letsgo", "vamos"),
        Command.YES to listOf("들려줘", "좋아", "그래", "해줘", "yes", "sure", "please", "claro"),
    )
    private val SHORT = mapOf("응" to Command.YES, "네" to Command.YES, "예" to Command.YES, "ok" to Command.YES,
        "si" to Command.YES, "sí" to Command.YES, "no" to Command.NO)

    fun parse(text: String): Command {
        val t = normalize(text)
        if (t.isEmpty()) return Command.NONE
        SHORT[t]?.let { return it }
        for ((command, words) in RULES) if (words.any { t.contains(it) }) return command
        // 「응 응」 처럼 한 글자 낱말만 되풀이한 것
        val tokens = text.lowercase().split(Regex("[\\s,.!?~]+")).filter { it.isNotEmpty() }
        if (tokens.isNotEmpty() && tokens.all { SHORT[it] == Command.YES }) return Command.YES
        return Command.NONE
    }

    private fun normalize(text: String) = text.lowercase().replace(Regex("[\\s,.!?~'\"]+"), "")
}
