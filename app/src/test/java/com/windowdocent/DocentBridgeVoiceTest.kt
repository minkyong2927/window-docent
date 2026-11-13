package com.windowdocent

import com.windowdocent.core.Narrator
import com.windowdocent.core.Speaker
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면 → Docent.speak · voiceState: 해설을 문장마다 말하고, 신호 판이 멈춤이면 끊고, 풀리면 이어 말하기 */
class DocentBridgeVoiceTest {

    private class FakeSpeaker : Speaker {
        val said = ArrayList<String>()
        var stops = 0
        private var done: (() -> Unit)? = null
        override fun speak(text: String, language: String, done: () -> Unit, failed: (String) -> Unit) {
            said += "$language:$text"
            this.done = done
        }
        override fun stop() { stops++; done = null }
        fun finish() { val d = done; done = null; d?.invoke() }
    }

    private fun state(turn: String = "off", nav: String? = null) =
        """{"turn":"$turn","steer":0,"speed":98,"brake":false,"nav":${nav?.let { "\"$it\"" } ?: "null"},"navDist":${if (nav != null) 1000 else "null"},"door":false,"rear":false,"front":false,"lang":"ko"}"""

    private fun lines(bridge: DocentBridge): List<String> {
        val arr = JSONObject(bridge.voiceState("{}")).getJSONArray("lines")
        return (0 until arr.length()).map { arr.getJSONObject(it).let { l -> l.getString("t") + if (l.getBoolean("cut")) " —" else "" } }
    }

    @Test fun withoutSpeakerTheScreenSpeaksByItself() {
        val bridge = DocentBridge()
        val r = JSONObject(bridge.speak("""{"id":"a","text":"안녕하세요."}"""))
        assertFalse(r.getBoolean("available"))
    }

    @Test fun speaksSentencesFromText() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker, speakerName = { "시험 장치" })
        bridge.evaluate(state())
        val r = JSONObject(bridge.speak("""{"id":"gyeryong","title":"계룡산","text":"오른쪽에 보이는 큰 산이 계룡산이에요. 산줄기가 용처럼 생겼대요."}"""))
        assertTrue(r.getBoolean("available"))
        assertEquals("시험 장치", r.getString("device"))
        assertEquals("SPEAKING", r.getString("state"))
        assertEquals(2, r.getInt("total"))
        assertEquals(listOf("ko:오른쪽에 보이는 큰 산이 계룡산이에요."), speaker.said)
        speaker.finish()
        speaker.finish()
        assertEquals("DONE", JSONObject(bridge.voiceState("{}")).getString("state"))
    }

    @Test fun signalBoardHoldCutsAndCruisingResumes() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker)
        bridge.evaluate(state(nav = "near"))                 // 분기점 접근 중 해설 시작
        bridge.speak("""{"id":"story","text":"옛날 이야기가 아주 많아요. 도착해서 기록으로도 볼 수 있어요."}""")
        bridge.evaluate(state(nav = "voice"))                // 내비 안내 음성: 바로 끊음
        assertEquals(1, speaker.stops)
        assertEquals("PAUSED", JSONObject(bridge.voiceState("{}")).getString("state"))
        bridge.evaluate(state(turn = "right"))               // 방향지시등: 계속 멈춤
        bridge.evaluate(state())                             // 직진: 한마디 뒤 끊긴 문장부터
        speaker.finish()
        speaker.finish()
        speaker.finish()
        assertEquals(
            listOf("옛날 이야기가 아주 많아요. —", Narrator.RESUME_LINE, "옛날 이야기가 아주 많아요.", "도착해서 기록으로도 볼 수 있어요."),
            lines(bridge),
        )
    }

    @Test fun languageAndStop() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker)
        bridge.speak("""{"id":"en","sentences":["Gyeryongsan is on your right.","It looks like a dragon."],"lang":"en"}""")
        assertEquals("en:Gyeryongsan is on your right.", speaker.said.last())
        val r = JSONObject(bridge.voiceStop("{}"))
        assertEquals("IDLE", r.getString("state"))
        assertEquals(1, speaker.stops)
    }
}
