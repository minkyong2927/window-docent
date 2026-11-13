package com.windowdocent.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 들은 말 → 명령 (VoiceCommand.kt) */
class VoiceCommandTest {
    private fun check(command: Command, vararg said: String) {
        for (s in said) assertEquals(s, command, VoiceCommand.parse(s))
    }

    @Test fun repeatWords() = check(Command.REPEAT, "다시", "다시 말해 줘", "뭐라고?", "한 번 더", "못 들었어", "Again please", "다시 들려줘")

    @Test fun yesWords() = check(Command.YES, "응", "네", "들려줘", "좋아", "그래 해줘", "yes", "응 응")

    @Test fun noWords() = check(Command.NO, "아니", "나중에", "나중에 들려줘", "됐어", "no", "later")

    @Test fun goWords() = check(Command.GO, "가자", "거기 들르자", "응 가자", "let's go")

    @Test fun nothingKnown() = check(Command.NONE, "", "  ", "오늘 날씨 좋네", "내비")

    @Test fun shortWordsOnlyAlone() {
        // 「네」 가 다른 낱말 속에 있어도 예로 보지 않음
        assertEquals(Command.NONE, VoiceCommand.parse("그네 타고 싶어"))
        assertEquals(Command.NONE, VoiceCommand.parse("응급실"))
    }
}
