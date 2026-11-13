package com.windowdocent

import com.windowdocent.core.LiveSignalSource
import com.windowdocent.core.Narrator
import com.windowdocent.core.NavGuidance
import com.windowdocent.core.Seats
import com.windowdocent.core.Speaker
import com.windowdocent.core.TurnSignal
import com.windowdocent.core.VehicleSignals
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면이 실제로 주고받는 JSON 으로 브리지 → 엔진을 끝까지 돌려 봄 */
class DocentBridgeTest {
    // ── 판단 · 명소 선정 · 사실 검증 ──────────────────────────────────────────
    private val bridge = DocentBridge()

    private fun evaluate(state: String) = JSONObject(bridge.evaluate(state))

    @Test fun evaluateReturnsDecisionForPanelState() {
        val r = evaluate(
            """{"turn":"off","steer":0,"speed":98,"brake":false,"nav":null,"navDist":null,
               "rear":true,"front":false,"door":false,"lang":"ko"}"""
        )
        assertEquals(1, r.getInt("level"))
        assertEquals("NARRATE", r.getString("mode"))
        assertEquals("CRUISING", r.getString("reason"))
        assertEquals("FAMILY", r.getString("audience"))
        assertEquals("ko", r.getString("language"))
    }

    @Test fun evaluateHoldsWhileNaviIsSpeaking() {
        val r = evaluate("""{"speed":98,"nav":"voice","navDist":1000}""")
        assertEquals(3, r.getInt("level"))
        assertEquals("HOLD", r.getString("mode"))
        assertEquals("NAV_VOICE", r.getString("reason"))
    }

    @Test fun evaluateSwitchesToForeignGuestByLanguage() {
        val r = evaluate("""{"speed":40,"rear":true,"lang":"es"}""")
        assertEquals("FOREIGN_GUEST", r.getString("audience"))
        assertEquals("es", r.getString("language"))
    }

    @Test fun jsonNullMeansSignalNotReceived() {
        // 속도를 못 받았으면(null) 브레이크를 밟고 있어도 정차로 보지 않음
        val r = evaluate("""{"speed":null,"steer":null,"brake":true,"nav":null,"lang":null}""")
        assertEquals("CRUISING", r.getString("reason"))
        assertEquals("DRIVER", r.getString("audience"))
    }

    /** 브라우저에서 쓰는 '미리 계산한 목록'이 Kotlin RoutePoiPlanner 결과와 같아야 함 */
    @Test fun planMatchesThePrototypeFallbackList() {
        val demo = prototypeDemoData()
        for ((planKey, routeKey) in listOf("base" to "route", "via" to "routeVia")) {
            val candidates = JSONArray()
            val all = demo.getJSONArray("pois")
            for (i in 0 until all.length()) {
                val poi = all.getJSONObject(i)
                if (planKey == "via" || !poi.optBoolean("viaOnly")) candidates.put(poi)
            }
            val request = JSONObject()
                .put("route", demo.getJSONArray(routeKey))
                .put("pois", candidates)
                .put("avgSpeedKmh", demo.getDouble("avgSpeedKmh"))

            val planned = JSONArray(bridge.plan(request.toString()))
            val expected = demo.getJSONObject("plan").getJSONArray(planKey)
            assertEquals("$planKey 명소 수", expected.length(), planned.length())
            for (i in 0 until expected.length()) {
                val want = expected.getJSONObject(i)
                val got = planned.getJSONObject(i)
                assertEquals("$planKey[$i] 순서", want.getString("id"), got.getString("id"))
                assertEquals("$planKey[$i] 해설 길이", want.getInt("sec"), got.getInt("sec"))
                assertEquals("$planKey[$i] 경로상 위치(해설 예정에 씀)", want.getLong("alongM"), got.getLong("alongM"))
            }
        }
    }

    @Test fun narrateBlocksInventedYearFromThePrototypeDemo() {
        val demo = prototypeDemoData().getJSONObject("factDemo")
        val facts = demo.getJSONArray("facts")
        val drafts = demo.getJSONArray("drafts")

        val blocked = JSONObject(bridge.narrate(JSONObject().put("draft", drafts.getString(0)).put("facts", facts).toString()))
        assertFalse(blocked.getBoolean("ok"))
        assertEquals(facts.getString(0), blocked.getString("text"))
        assertEquals("1392", blocked.getJSONArray("unknown").getString(0))

        val passed = JSONObject(bridge.narrate(JSONObject().put("draft", drafts.getString(1)).put("facts", facts).toString()))
        assertTrue(passed.getBoolean("ok"))
        assertEquals(drafts.getString(1), passed.getString("text"))
        assertEquals(0, passed.getJSONArray("unknown").length())
    }

    @Test fun planNeedsAtLeastTwoRoutePoints() {
        assertThrows(IllegalArgumentException::class.java) {
            bridge.plan("""{"route":[[36.0,127.0]],"pois":[],"avgSpeedKmh":90}""")
        }
    }

    /** sample-data/scenario-a.js 의 시연 자료(window.DEMO_DATA = {…}) */
    private fun prototypeDemoData(): JSONObject {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val file = File(dir, "sample-data/scenario-a.js")
            if (file.isFile) {
                val text = file.readText(Charsets.UTF_8)
                return JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            }
            dir = dir.parentFile
        }
        error("sample-data/scenario-a.js 를 찾지 못함")
    }

    // ── 실제 차량 신호 켜기 · 끄기 (Docent.live · liveState) ─────────────────────
    /** 실제 차량 신호 공급원 흉내 (Pleos SDK 대신) */
    private class FakeLive(var ok: Boolean = true) : LiveSignalSource {
        val waypoints = ArrayList<com.windowdocent.core.Place>()
        override fun addWaypoint(place: com.windowdocent.core.Place): String { waypoints += place; return "ok" }
        var listener: ((VehicleSignals, Seats) -> Unit)? = null
        var signals = VehicleSignals()
        var seats = Seats()
        var started = 0
        var stopped = 0
        var refreshed = 0

        override fun start(listener: (VehicleSignals, Seats) -> Unit) {
            started++
            this.listener = listener
            listener(signals, seats)
        }

        override fun stop() {
            stopped++
            listener = null
        }

        override fun status() = if (ok) mapOf("vehicle" to "ok", "navi" to "ok (아직 받은 값 없음)")
        else mapOf("vehicle" to "이 기기에 Pleos 서비스가 없음")

        override fun connected() = ok

        override fun refresh() {
            refreshed++
            listener?.invoke(signals, seats)
        }

        fun send(vehicle: VehicleSignals, seated: Seats = seats) {
            signals = vehicle
            seats = seated
            listener?.invoke(vehicle, seated)
        }
    }

    private val demoCruising = """{"turn":"off","steer":0,"speed":98,"brake":false,"nav":null,"navDist":null,"door":false,"rear":false,"front":false,"lang":"ko"}"""

    private fun reason(bridge: DocentBridge, state: String = demoCruising) = JSONObject(bridge.evaluate(state)).getString("reason")

    @Test fun withoutFactoryLiveIsUnavailable() {
        val bridge = DocentBridge()
        val r = JSONObject(bridge.live("""{"on":true}"""))
        assertFalse(r.getBoolean("available"))
        assertFalse(r.getBoolean("on"))
        assertEquals("CRUISING", reason(bridge))
    }

    @Test fun liveSignalsDecideInsteadOfDemoBoard() {
        val fake = FakeLive()
        val bridge = DocentBridge(liveFactory = { fake })
        val r = JSONObject(bridge.live("""{"on":true}"""))
        assertTrue(r.getBoolean("on"))
        assertTrue(r.getBoolean("connected"))
        assertEquals(1, fake.started)

        // 차량에서 방향지시등이 켜지면, 신호 판(시연 값)이 직진이어도 해설을 멈춤
        fake.send(VehicleSignals(speedKmh = 70.0, turnSignal = TurnSignal.LEFT))
        assertEquals("TURN_SIGNAL", reason(bridge))
        assertTrue(fake.refreshed > 0)

        // 정차(속도 0)면 긴 이야기
        fake.send(VehicleSignals(speedKmh = 0.0))
        assertEquals("STOPPED", reason(bridge))
    }

    @Test fun liveStateMirrorsSignalsForTheBoard() {
        val fake = FakeLive()
        val bridge = DocentBridge(liveFactory = { fake })
        bridge.live("""{"on":true}""")
        fake.send(
            VehicleSignals(
                speedKmh = 63.4, turnSignal = TurnSignal.RIGHT, steeringAngleDeg = -12.6, brakePressed = true,
                navGuidance = NavGuidance.TURN_AHEAD, navDistanceM = 742.0, doorOpen = false,
            ),
            Seats(frontPassenger = true, rearCount = 2),
        )
        val r = JSONObject(bridge.liveState("{}"))
        val s = r.getJSONObject("state")
        assertEquals("right", s.getString("turn"))
        assertEquals(-13, s.getInt("steer"))
        assertEquals(63, s.getInt("speed"))
        assertTrue(s.getBoolean("brake"))
        assertEquals("near", s.getString("nav"))
        assertEquals(742, s.getInt("navDist"))
        assertEquals(2, s.getInt("rear"))
        assertTrue(s.getBoolean("front"))
        assertEquals("ok", r.getJSONObject("status").getString("vehicle"))
    }

    @Test fun unknownSpeedIsNullOnTheBoard() {
        val fake = FakeLive()
        val bridge = DocentBridge(liveFactory = { fake })
        bridge.live("""{"on":true}""")
        fake.send(VehicleSignals(speedKmh = null, steeringAngleDeg = null))
        val s = JSONObject(bridge.liveState("{}")).getJSONObject("state")
        assertTrue(s.isNull("speed"))
        assertTrue(s.isNull("steer"))
        assertTrue(s.isNull("nav"))
    }

    @Test fun passengerLanguageStillComesFromTheScreen() {
        val fake = FakeLive()
        val bridge = DocentBridge(liveFactory = { fake })
        bridge.live("""{"on":true}""")
        fake.send(VehicleSignals(speedKmh = 80.0), Seats(rearCount = 1))
        val d = JSONObject(bridge.evaluate(demoCruising.replace("\"lang\":\"ko\"", "\"lang\":\"en\"")))
        assertEquals("FOREIGN_GUEST", d.getString("audience"))
        assertEquals("en", d.getString("language"))
    }

    @Test fun turningOffGoesBackToDemoBoard() {
        val fake = FakeLive()
        val bridge = DocentBridge(liveFactory = { fake })
        bridge.live("""{"on":true}""")
        fake.send(VehicleSignals(speedKmh = 70.0, doorOpen = true))
        assertEquals("DOOR_OPEN", reason(bridge))

        val r = JSONObject(bridge.live("""{"on":false}"""))
        assertFalse(r.getBoolean("on"))
        assertEquals(1, fake.stopped)
        assertEquals("CRUISING", reason(bridge))

        // 다시 켜면 같은 공급원을 다시 붙임
        bridge.live("""{"on":true}""")
        assertEquals(2, fake.started)
    }

    @Test fun notConnectedIsReported() {
        val bridge = DocentBridge(liveFactory = { FakeLive(ok = false) })
        val r = JSONObject(bridge.live("""{"on":true}"""))
        assertTrue(r.getBoolean("on"))
        assertFalse(r.getBoolean("connected"))
        assertEquals("이 기기에 Pleos 서비스가 없음", r.getJSONObject("status").getString("vehicle"))
    }

    @Test fun liveStartHookRunsOnce() {
        var hooked = 0
        val bridge = DocentBridge(liveFactory = { FakeLive() }, onLiveStart = { hooked++ })
        bridge.live("""{"on":true}""")
        bridge.live("""{"on":true}""")
        assertEquals(1, hooked)
    }

    // ── 음성 해설 (Docent.speak · voiceState · voiceStop) ───────────────────────
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

    @Test fun cautionButtonHoldsAndArrivalOffersMissedStory() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker)
        val near = state().replace("\"lang\":\"ko\"", "\"lang\":\"ko\",\"caution\":\"SCHOOL_ZONE\",\"cautionDist\":200")
        bridge.evaluate(state())
        bridge.speak("""{"id":"a","title":"계룡산","text":"첫 문장이에요. 둘째 문장이에요."}""")
        assertEquals("CAUTION_ZONE", JSONObject(bridge.evaluate(near)).getString("reason"))
        bridge.evaluate(state()); speaker.finish()            // 한마디 → 첫 문장 다시
        bridge.evaluate(near); bridge.evaluate(state())       // 같은 문장이 또 끊김 → 다음 문장으로
        speaker.finish(); speaker.finish()
        var v = JSONObject(bridge.voiceState("{}"))
        assertEquals("첫 문장이에요.", v.getJSONArray("missed").getJSONObject(0).getJSONArray("sentences").getString(0))
        val arrive = state().replace("\"lang\":\"ko\"", "\"lang\":\"ko\",\"arrived\":true")
        bridge.evaluate(arrive)                               // 도착 → 한 번 묻기
        assertEquals("ko:" + Narrator.offerLine(1), speaker.said.last())
        speaker.finish()
        assertTrue(JSONObject(bridge.voiceState("{}")).getBoolean("awaiting"))
        v = JSONObject(bridge.voiceAnswer("""{"yes":true}"""))  // 응
        assertEquals("ko:첫 문장이에요.", speaker.said.last())
        assertEquals(0, v.getJSONArray("missed").length())
    }

    // ── 해설 예정 · 명소에 가까워지면 시작 (Docent.schedule · progress) ─────────────────

    private fun demoScheduleRequest(): JSONObject {
        val demo = prototypeDemoData()
        val pois = JSONArray()
        val all = demo.getJSONArray("pois")
        for (i in 0 until all.length()) if (!all.getJSONObject(i).optBoolean("viaOnly")) pois.put(all.getJSONObject(i).put("text", "첫 문장이에요. 둘째 문장이에요."))
        return JSONObject().put("route", demo.getJSONArray("route")).put("pois", pois)
            .put("avgSpeedKmh", demo.getDouble("avgSpeedKmh")).put("departMs", 0)
    }

    @Test fun demoRouteScheduleIsFixed() {
        // 시연 경로(서울 → 담양 · 평균 90km/h) 예정표: 명소 · 시작 예상(출발 뒤 초) · 길이 · 방식 (구글 시트 추가 1 출력값과 같음)
        val r = JSONObject(DocentBridge().schedule(demoScheduleRequest().toString()))
        val e = r.getJSONArray("entries")
        val got = (0 until e.length()).map { e.getJSONObject(it).let { x -> listOf(x.getString("id"), x.getLong("startAfterSec"), x.getInt("sec"), x.getBoolean("story")) } }
        assertEquals(
            listOf(
                listOf("cheonan", 3006L, 60, true),
                listOf("gyeryong", 4901L, 60, true),
                listOf("naejang", 9018L, 60, true),
                listOf("meta", 10760L, 15, false),
            ),
            got,
        )
        assertEquals(4, r.getInt("count"))
        assertEquals(3006L, r.getLong("firstAfterSec"))
        assertEquals("cheonan", r.getJSONObject("next").getString("id"))
    }

    @Test fun progressStartsAtTheStartPointAndMissesPassedOnes() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker)
        bridge.evaluate(state())
        bridge.schedule(demoScheduleRequest().toString())
        var r = JSONObject(bridge.progress("""{"travelledM":75000,"speedKmh":90}"""))
        assertTrue(speaker.said.isEmpty())                         // 시작 지점(75,157m) 전
        assertEquals(6L, r.getJSONObject("next").getLong("remainSec"))   // 157m ÷ 25m/s
        r = JSONObject(bridge.progress("""{"travelledM":75200,"speedKmh":90}"""))
        assertEquals("ko:첫 문장이에요.", speaker.said.last())         // 천안삼거리 해설 시작
        assertEquals("SPEAKING", r.getJSONArray("entries").getJSONObject(0).getString("status"))
        r = JSONObject(bridge.progress("""{"travelledM":124500,"speedKmh":90}"""))   // 계룡산 시작 지점 · 명소를 다 지나침
        val entries = r.getJSONArray("entries")
        assertEquals("MISSED", entries.getJSONObject(1).getString("status"))
        assertEquals("gyeryong", JSONObject(bridge.voiceState("{}")).getJSONArray("missed").getJSONObject(0).getString("id"))
        assertEquals("naejang", r.getJSONObject("next").getString("id"))
    }

    @Test fun withoutSpeakerProgressHandsTheTextToTheScreen() {
        val bridge = DocentBridge()
        bridge.schedule(demoScheduleRequest().toString())
        val r = JSONObject(bridge.progress("""{"travelledM":75200}"""))
        assertEquals("cheonan", r.getJSONObject("start").getString("id"))
        assertEquals("첫 문장이에요. 둘째 문장이에요.", r.getJSONObject("start").getString("text"))
    }

    // ── 음성 명령 (Docent.listen · command) ───────────────────────────────────────

    /** 들을 말을 시험이 정하는 음성 입력 */
    private class FakeEars : com.windowdocent.core.VoiceInput {
        var next: String? = null
        var listens = 0
        override fun listen(language: String, heard: (String) -> Unit, failed: (String) -> Unit) {
            listens++
            next?.let(heard) ?: failed("들은 말 없음")
        }
        override fun stop() {}
    }

    @Test fun repeatCommandSaysTheSentenceAgain() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker)
        bridge.evaluate(state())
        bridge.speak("""{"id":"a","text":"첫 문장이에요. 둘째 문장이에요."}""")
        speaker.finish()                                         // 둘째 문장 말하는 중
        val r = JSONObject(bridge.command("""{"text":"뭐라고?"}"""))
        assertEquals("REPEAT", r.getJSONObject("heard").getString("command"))
        assertTrue(r.getJSONObject("heard").getBoolean("done"))
        assertEquals("ko:둘째 문장이에요.", speaker.said.last())
        assertFalse(r.getBoolean("canListen"))                   // 듣는 장치가 없으면 흉내 단추로만
    }

    @Test fun listenUsesTheVoiceInputAndAnswersTheOffer() {
        val speaker = FakeSpeaker()
        val ears = FakeEars()
        val bridge = DocentBridge(speaker = speaker, voiceInput = ears)
        bridge.evaluate(state())
        bridge.speak("""{"id":"a","text":"첫 문장이에요. 둘째 문장이에요."}""")
        val near = state().replace("\"lang\":\"ko\"", "\"lang\":\"ko\",\"caution\":\"SCHOOL_ZONE\",\"cautionDist\":200")
        bridge.evaluate(near); bridge.evaluate(state()); speaker.finish()
        bridge.evaluate(near); bridge.evaluate(state()); speaker.finish(); speaker.finish()   // 첫 문장은 못 한 이야기로
        val arrive = state().replace("\"lang\":\"ko\"", "\"lang\":\"ko\",\"arrived\":true")
        bridge.evaluate(arrive)                                  // 도착 → 물음
        ears.next = "응 들려줘"
        speaker.finish()                                         // 물음을 다 말함
        val v = JSONObject(bridge.voiceState("{}"))              // 물은 뒤 한 번 들음 → 응
        assertEquals(1, ears.listens)
        assertEquals("YES", v.getJSONObject("heard").getString("command"))
        assertEquals("ko:첫 문장이에요.", speaker.said.last())
        bridge.voiceState("{}")
        assertEquals(1, ears.listens)                            // 같은 물음에 두 번 듣지 않음
        ears.next = "다시"
        JSONObject(bridge.listen("{}"))                          // 말하기 단추
        assertEquals(2, ears.listens)
        assertEquals("ko:첫 문장이에요.", speaker.said.last())
    }

    // ── 장치가 예외를 던질 때 (11/14 에뮬레이터: 음성 권한이 없어 앱이 바로 꺼진 일) ─────────────

    /** 무엇을 불러도 예외를 던지는 말하는 장치 · 듣는 장치 */
    private class BrokenSpeaker : Speaker {
        override fun speak(text: String, language: String, done: () -> Unit, failed: (String) -> Unit) =
            throw SecurityException("Permission pleos.car.permission.TTS_SERVICE is required to perform this action.")
        override fun stop() = throw SecurityException("stop")
        override fun release() = throw SecurityException("release")
    }

    private class BrokenEars : com.windowdocent.core.VoiceInput {
        override fun listen(language: String, heard: (String) -> Unit, failed: (String) -> Unit) =
            throw SecurityException("Permission pleos.car.permission.STT_SERVICE is required to perform this action.")
        override fun stop() = throw SecurityException("stop")
        override fun release() = throw SecurityException("release")
    }

    @Test fun bridgeStaysAliveWhenTheSpeakerThrows() {
        val bridge = DocentBridge(speaker = BrokenSpeaker(), speakerName = { "시험 장치" })
        bridge.evaluate(state())
        val r = JSONObject(bridge.speak("""{"id":"a","text":"첫 문장이에요. 둘째 문장이에요."}"""))
        assertTrue(r.getBoolean("available"))
        assertEquals("DONE", r.getString("state"))
        assertEquals("SecurityException", r.getString("error"))
        bridge.evaluate(state(nav = "voice"))                    // 멈춤 · 다시 시작도 그대로
        JSONObject(bridge.voiceStop("{}"))
        assertTrue(JSONObject(bridge.evaluate(state())).has("mode"))     // 판단도 그대로
        bridge.close()
    }

    @Test fun bridgeFallsBackToTheNextSpeakerInTheChain() {
        val speaker = FakeSpeaker()
        val chain = com.windowdocent.core.SpeakerChain(
            listOf(
                com.windowdocent.core.SpeakerChain.Maker("Pleos TextToSpeech") { throw SecurityException("TTS_SERVICE") },
                com.windowdocent.core.SpeakerChain.Maker("안드로이드 TTS") { speaker },
            ),
        )
        val bridge = DocentBridge(speaker = chain, speakerName = { chain.name })
        bridge.evaluate(state())
        val r = JSONObject(bridge.speak("""{"id":"a","text":"첫 문장이에요."}"""))
        assertEquals("안드로이드 TTS", r.getString("device"))
        assertEquals(listOf("ko:첫 문장이에요."), speaker.said)
    }

    @Test fun bridgeStaysAliveWhenTheVoiceInputThrows() {
        val bridge = DocentBridge(speaker = FakeSpeaker(), voiceInput = BrokenEars())
        bridge.evaluate(state())
        val v = JSONObject(bridge.listen("{}"))
        assertFalse(v.getBoolean("listening"))
        assertEquals("NONE", v.getJSONObject("heard").getString("command"))
        assertEquals("SecurityException", v.getJSONObject("heard").getString("error"))
        bridge.close()
    }

    // ── 지나친 곳은 지난 뒤 짧게 (기능 1 · 마-1) ─────────────────────────────────────
    private fun scheduleWithPassed(): JSONObject {
        val req = demoScheduleRequest()
        val pois = req.getJSONArray("pois")
        for (i in 0 until pois.length()) pois.getJSONObject(i).let { it.put("passed", "방금 지난 " + it.getString("name") + "이에요.") }
        return req
    }

    @Test fun passedPlaceGetsOneLineRightAwayWhenFree() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker)
        bridge.evaluate(state())
        bridge.schedule(scheduleWithPassed().toString())
        bridge.progress("""{"travelledM":75200,"speedKmh":90}""")      // 천안삼거리 해설
        speaker.finish(); speaker.finish()                                  // 다 말함
        val r = JSONObject(bridge.progress("""{"travelledM":125000,"speedKmh":90}"""))   // 계룡산을 시작 못 하고 지나침
        assertEquals("ko:방금 지난 계룡산이에요.", speaker.said.last())
        assertEquals("MISSED", r.getJSONArray("entries").getJSONObject(1).getString("status"))   // 자세한 이야기는 못 한 이야기에
        assertEquals("gyeryong", JSONObject(bridge.voiceState("{}")).getJSONArray("missed").getJSONObject(0).getString("id"))
    }

    @Test fun passedLineWaitsWhileSpeakingThenSaysItWithin3km() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker)
        bridge.evaluate(state())
        bridge.schedule(scheduleWithPassed().toString())
        bridge.progress("""{"travelledM":75200,"speedKmh":90}""")      // 천안삼거리 말하는 중
        bridge.progress("""{"travelledM":124500,"speedKmh":90}""")     // 계룡산을 지나침 · 바빠서 미룸
        assertEquals("ko:첫 문장이에요.", speaker.said.last())
        speaker.finish(); speaker.finish()
        bridge.progress("""{"travelledM":126000,"speedKmh":90}""")     // 지나서 2km · 이제 한 줄
        assertEquals("ko:방금 지난 계룡산이에요.", speaker.said.last())
    }

    @Test fun passedLineIsDroppedMoreThan3kmLater() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker)
        bridge.evaluate(state())
        bridge.schedule(scheduleWithPassed().toString())
        bridge.progress("""{"travelledM":75200,"speedKmh":90}""")
        bridge.progress("""{"travelledM":124500,"speedKmh":90}""")
        speaker.finish(); speaker.finish()
        bridge.progress("""{"travelledM":127500,"speedKmh":90}""")     // 지나서 3km 넘음
        assertEquals("ko:둘째 문장이에요.", speaker.said.last())
    }

    // ── 들러볼 곳 제안과 「가자」 (기능 2 · 마-2) ─────────────────────────────────────
    private val juknok = """{"id":"juknok","name":"죽녹원","lat":35.327,"lng":126.986,"text":"죽녹원은 가는 길에서 3분이에요. 들러 갈까요?"}"""

    @Test fun suggestionIsSpokenAndGoAddsTheWaypointToTheNavi() {
        val speaker = FakeSpeaker()
        val live = FakeLive()
        val bridge = DocentBridge(liveFactory = { live }, speaker = speaker)
        bridge.live("""{"on":true}""")
        val v = JSONObject(bridge.suggest(juknok))
        assertEquals("ko:죽녹원은 가는 길에서 3분이에요.", speaker.said.last())
        assertEquals("juknok", v.getString("suggesting"))
        val r = JSONObject(bridge.command("""{"text":"가자!"}"""))
        assertEquals("GO", r.getJSONObject("heard").getString("command"))
        assertTrue(r.getJSONObject("heard").getBoolean("done"))
        assertEquals("ok", r.getJSONObject("waypoint").getString("navi"))
        assertEquals(listOf("juknok"), live.waypoints.map { it.id })
        assertEquals(35.327, live.waypoints[0].location.lat, 1e-9)
        assertTrue(r.isNull("suggesting"))
        assertFalse(JSONObject(bridge.command("""{"text":"가자"}""")).getJSONObject("heard").getBoolean("done"))   // 제안이 없으면 할 일 없음
    }

    @Test fun goWithoutVehicleSignalsStillSwitchesTheScreenOnly() {
        val bridge = DocentBridge()
        bridge.suggest(juknok)
        val r = JSONObject(bridge.command("""{"text":"들르자"}"""))
        assertTrue(r.getJSONObject("heard").getBoolean("done"))
        assertEquals("차량 신호를 켜면 내비에 넣음", r.getJSONObject("waypoint").getString("navi"))
        assertEquals("차량 신호가 꺼져 있음", JSONObject(bridge.route(juknok)).getString("navi"))
    }

    // ── 가족 퀴즈 (기능 3 · 마-3) ─────────────────────────────────────────────────────
    @Test fun quizIsAskedThenTheHeardAnswerIsScored() {
        val speaker = FakeSpeaker()
        val ears = FakeEars()
        val bridge = DocentBridge(speaker = speaker, voiceInput = ears)
        bridge.evaluate(state())
        bridge.quiz("""{"id":"gyeryong","question":"계룡산 이름에 숨어 있는 동물 두 마리는 뭘까요?","must":["닭","용"],"right":"정답이에요! 1점!","wrong":"아쉬워요."}""")
        assertEquals("ko:계룡산 이름에 숨어 있는 동물 두 마리는 뭘까요?", speaker.said.last())
        ears.next = "닭이랑 용!"
        speaker.finish()                                         // 문제를 다 물음
        val v = JSONObject(bridge.voiceState("{}"))              // 답을 한 번 들음 → 채점
        assertEquals(1, ears.listens)
        assertEquals("ANSWER", v.getJSONObject("heard").getString("command"))
        assertTrue(v.getJSONObject("heard").getJSONObject("quiz").getBoolean("ok"))
        assertEquals("ko:정답이에요!", speaker.said.last())
        val q = JSONObject(bridge.voiceState("{}")).getJSONObject("quiz")
        assertEquals(1, q.getInt("asked")); assertEquals(1, q.getInt("right")); assertTrue(q.isNull("asking"))
    }

    @Test fun repeatDuringQuizSaysTheQuestionAgain() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker)
        bridge.evaluate(state())
        bridge.quiz("""{"id":"naejang","question":"내장산은 어느 계절에 가장 유명할까요?","must":["가을"],"right":"정답!","wrong":"아쉬워요. 정답은 가을이에요."}""")
        bridge.command("""{"text":"다시"}""")
        assertEquals("ko:내장산은 어느 계절에 가장 유명할까요?", speaker.said.last())
        val r = JSONObject(bridge.command("""{"text":"여름"}"""))
        assertFalse(r.getJSONObject("heard").getJSONObject("quiz").getBoolean("ok"))
        assertEquals("ko:아쉬워요.", speaker.said.last())
    }

    // ── 여행 기록 (마-7) ─────────────────────────────────────────────────────────────
    @Test fun doorOpenSavesTheTripRecordOnce() {
        val saved = ArrayList<String>()
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker, recordSink = { saved += it })
        bridge.evaluate(state())
        bridge.speak("""{"id":"cheonan","title":"천안삼거리","text":"첫 문장이에요."}""")
        bridge.suggest(juknok)
        bridge.command("""{"text":"가자"}""")
        bridge.evaluate(state(turn = "left"))            // 멈춤 한 번
        bridge.evaluate(state())
        val door = state().replace("\"door\":false", "\"door\":true")
        bridge.evaluate(door)
        bridge.evaluate(door)                            // 문이 열려 있는 동안 한 번만
        assertEquals(1, saved.size)
        val r = JSONObject(saved[0])
        assertEquals(listOf("cheonan", "juknok"), (0 until r.getJSONArray("places").length()).map { r.getJSONArray("places").getJSONObject(it).getString("id") })
        assertEquals("죽녹원", r.getJSONArray("via").getString(0))
        assertEquals(1, r.getInt("holds"))
        assertEquals(r.toString(), JSONObject(bridge.record("{}")).toString())
    }

    // ── 외국어 승객 해설과 한국어 한마디 (기능 4 · 마-4) ─────────────────────────────────
    private fun scheduleWithForeign(): JSONObject {
        val req = demoScheduleRequest()
        val pois = req.getJSONArray("pois")
        for (i in 0 until pois.length()) if (pois.getJSONObject(i).getString("id") == "cheonan") {
            pois.getJSONObject(i).put("foreign", JSONObject("""{"en":"We are passing Cheonan Samgeori.","es":"Pasamos por Cheonan Samgeori.","phrase":{"ko":"안녕하세요","roman":"annyeonghaseyo","en":"hello","es":"hola"}}"""))
        }
        return req
    }

    @Test fun foreignGuestHearsTheStoryInTheirLanguageThenAKoreanWord() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker)
        bridge.evaluate(state().replace("\"lang\":\"ko\"", "\"lang\":\"es\""))
        bridge.schedule(scheduleWithForeign().toString())
        bridge.progress("""{"travelledM":75200,"speedKmh":90}""")
        assertEquals("es:Pasamos por Cheonan Samgeori.", speaker.said.last())
        speaker.finish()
        assertEquals("es:Palabra coreana: 안녕하세요, annyeonghaseyo, significa hola.", speaker.said.last())
    }

    @Test fun koreanPassengersStillHearTheKoreanStory() {
        val speaker = FakeSpeaker()
        val bridge = DocentBridge(speaker = speaker)
        bridge.evaluate(state())
        bridge.schedule(scheduleWithForeign().toString())
        bridge.progress("""{"travelledM":75200,"speedKmh":90}""")
        assertEquals("ko:첫 문장이에요.", speaker.said.last())
    }
}
