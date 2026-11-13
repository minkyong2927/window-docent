package com.windowdocent

import android.webkit.JavascriptInterface
import com.windowdocent.core.DocentEngine
import com.windowdocent.core.LatLng
import com.windowdocent.core.Command
import com.windowdocent.core.LiveSignalSource
import com.windowdocent.core.NavGuidance
import com.windowdocent.core.Narrator
import com.windowdocent.core.NarrationSchedule
import com.windowdocent.core.NarrationMode
import com.windowdocent.core.ScheduleEntry
import com.windowdocent.core.Place
import com.windowdocent.core.Poi
import com.windowdocent.core.PrototypeSignalSource
import com.windowdocent.core.QuizBook
import com.windowdocent.core.QuizItem
import com.windowdocent.core.RoutePoiPlanner
import com.windowdocent.core.RouteStart
import com.windowdocent.core.TripRecord
import com.windowdocent.core.ScheduleStatus
import com.windowdocent.core.Speaker
import com.windowdocent.core.TurnSignal
import com.windowdocent.core.VoiceCommand
import com.windowdocent.core.VoiceInput
import org.json.JSONArray
import org.json.JSONObject

/**
 * 프로토타입 화면(WebView) ↔ Kotlin 엔진 다리. 화면은 JSON 문자열만 주고받고, 판단은 전부 DocentEngine 이 함.
 * 화면에서는 window.Docent 로 보임: Docent.evaluate(...) · Docent.plan(...) · Docent.narrate(...) · Docent.live(...) · Docent.liveState(...)
 *   · Docent.speak(...) · Docent.voiceState(...) · Docent.voiceStop(...) · Docent.voiceAnswer(...) · Docent.schedule(...) · Docent.progress(...)
 *   · Docent.listen(...) · Docent.command(...)
 *
 * 신호는 기본이 화면의 시연용 신호 판(PrototypeSignalSource). 화면에서 「차량 신호」 를 켜면 실제 차량 신호(LiveSignalSource ·
 * 앱에서는 Pleos SDK 의 PleosSignalSource)로 바꾸고, 끄면 시연 신호로 돌아감.
 *
 * @param liveFactory 실제 차량 신호 공급원을 만드는 곳. 없으면 「차량 신호」 를 켤 수 없음
 * @param onLiveStart 실제 신호를 붙인 직후 (화면 쪽에서 위치 권한을 요청하는 등)
 * @param runner 공급원 붙이기 · 떼기를 어느 스레드에서 할지 (앱은 메인 스레드 · 테스트는 바로)
 * @param speaker 해설을 소리로 내는 장치. 없으면 화면이 자기 방식(브라우저 음성)으로 말함
 * @param speakerName 화면에 보일 말하는 장치 이름
 * @param voiceInput 음성 명령을 듣는 장치 (Pleos SpeechToText). 없으면 화면의 흉내 단추로만
 */
class DocentBridge(
    private val engine: DocentEngine = DocentEngine(),
    private val liveFactory: (() -> LiveSignalSource)? = null,
    private val onLiveStart: (LiveSignalSource) -> Unit = {},
    private val runner: (() -> Unit) -> Unit = { it() },
    speaker: Speaker? = null,
    private val speakerName: () -> String = { "" },
    private val voiceInput: VoiceInput? = null,
    /** 문이 열려 여행 기록이 만들어지면 받아서 저장하는 곳 (앱: 기기 파일) */
    private val recordSink: (String) -> Unit = {},
) {
    private val source = PrototypeSignalSource()
    private val narrator: Narrator? = speaker?.let { Narrator(it) }
    private var live: LiveSignalSource? = null

    @Volatile
    private var liveOn = false
    private var wasArrived = false

    /** 해설 예정 (경로를 정할 때 만들고, 차가 나아갈 때마다 progress 로 시작 판단) */
    private var schedule: NarrationSchedule? = null
    private var scheduleRoute: List<LatLng> = emptyList()
    private var scheduleTexts: Map<String, String> = emptyMap()
    /** 지나친 곳 한 줄 (기능 1 · 명소마다 passed) */
    private var schedulePassed: Map<String, String> = emptyMap()
    /** 아직 못 한 지나친 곳 한 줄 (말하는 중이라 미룸 · 지나서 PASSED_WITHIN_M 안에서만) */
    private var pendingPassed: ScheduleEntry? = null
    private var schedulePois: List<Poi> = emptyList()
    private var scheduleAvgKmh = DEFAULT_SPEED_KMH
    private var departMs = 0L
    private var travelledM = 0.0
    private var lastRouteStart: RouteStart? = null
    private val planner = RoutePoiPlanner()

    /** 음성 명령: 듣는 중인지 · 마지막으로 들은 말 · 못 한 이야기를 물은 뒤 한 번 들었는지 */
    @Volatile
    private var listening = false
    private var heard: JSONObject? = null
    /** 오늘의 여행 기록 (마-7) · 문이 열린 순간 한 번 저장 */
    private val trip = TripRecord()
    private var recordSaved = false
    /** 가족 퀴즈 (기능 3) · 문제를 다 물으면 답을 한 번 들음 */
    private val quiz = QuizBook()
    private var listenedForQuiz = false
    /** 지금 제안한 들러볼 곳 (기능 2 · 「가자」 를 기다림) */
    private var suggestion: Place? = null
    /** 마지막 경유지 요청 결과 {id, name, navi} */
    private var waypoint: JSONObject? = null
    private var listenedForOffer = false

    init {
        engine.attach(source)
    }

    /**
     * 신호 판 상태 S → 지금 어떻게 · 누구에게 말할지.
     * 입력 {"turn","steer","speed","brake","nav","navDist","door","rear","front","lang","caution","cautionDist","arrived"}
     * (차량 신호가 켜져 있으면 신호는 실제 값을 쓰고 S 에서는 승객 언어 lang 만 씀)
     * 출력 {"level":0~3,"mode","reason","audience","language"}
     */
    @JavascriptInterface
    fun evaluate(stateJson: String): String {
        val state = toMap(JSONObject(stateJson))
        val current = live
        if (liveOn && current != null) current.refresh() else source.push(state)
        engine.guestLanguage = state["lang"] as? String
        val d = engine.decide()
        // 해설 방식이 바뀌면 말하던 해설을 멈추거나 이어 감 · 막 도착했으면 못 한 이야기를 제안
        narrator?.onMode(d.mode)
        trip.mode(d.mode)
        if (d.mode == NarrationMode.TRIP_RECORD) {
            if (!recordSaved) {
                recordSaved = true
                try { recordSink(recordJson().toString()) } catch (_: Throwable) {}
            }
        } else {
            recordSaved = false
        }
        val arrived = engine.signals.arrived
        if (arrived && !wasArrived) narrator?.arrived()
        wasArrived = arrived
        return JSONObject()
            .put("level", d.mode.level)
            .put("mode", d.mode.name)
            .put("reason", d.reason.name)
            .put("audience", d.audience.name)
            .put("language", d.language)
            .toString()
    }

    /**
     * 실제 차량 신호 켜기 · 끄기. 입력 {"on":true|false} · 출력은 liveState 와 같음.
     */
    @JavascriptInterface
    fun live(requestJson: String): String {
        val on = JSONObject(requestJson).optBoolean("on", false)
        val factory = liveFactory
        synchronized(this) {
            if (on && !liveOn && factory != null) {
                val src = live ?: factory().also { live = it }
                liveOn = true
                runner {
                    try {
                        engine.attach(src)
                        onLiveStart(src)
                    } catch (t: Throwable) {
                        // 신호 공급원을 붙이다 예외가 나면 시연 신호로 돌아감 (앱은 꺼지지 않음)
                        liveOn = false
                        engine.attach(source)
                    }
                }
            } else if (!on && liveOn) {
                liveOn = false
                runner { engine.attach(source) }
            }
        }
        return liveState("{}")
    }

    /**
     * 지금 신호와 연결 상태.
     * 출력 {"available","on","connected","status":{모듈: 상태},"state":{신호 판 S 와 같은 칸}}
     */
    @JavascriptInterface
    fun liveState(@Suppress("UNUSED_PARAMETER") requestJson: String): String {
        val current = live
        val on = liveOn && current != null
        if (on) current!!.refresh()
        val status = JSONObject()
        if (on) for ((module, state) in current!!.status()) status.put(module, state)
        val s = engine.signals
        val seats = engine.seats
        val state = JSONObject()
            .put("turn", when (s.turnSignal) {
                TurnSignal.LEFT -> "left"
                TurnSignal.RIGHT -> "right"
                TurnSignal.OFF -> "off"
            })
            .put("steer", s.steeringAngleDeg?.let { Math.round(it) } ?: JSONObject.NULL)
            .put("speed", s.speedKmh?.let { Math.round(it) } ?: JSONObject.NULL)
            .put("brake", s.brakePressed)
            .put("nav", when (s.navGuidance) {
                NavGuidance.VOICE -> "voice"
                NavGuidance.TURN_AHEAD -> "near"
                NavGuidance.NONE -> JSONObject.NULL
            })
            .put("navDist", s.navDistanceM?.let { Math.round(it) } ?: JSONObject.NULL)
            .put("door", s.doorOpen)
            .put("caution", s.cautionZone ?: JSONObject.NULL)
            .put("cautionDist", if (s.cautionZone != null) 0 else JSONObject.NULL)
            .put("arrived", s.arrived)
            .put("rear", seats.rearCount)
            .put("front", seats.frontPassenger)
        return JSONObject()
            .put("available", liveFactory != null)
            .put("on", on)
            .put("connected", on && current!!.connected())
            .put("status", status)
            .put("state", state)
            .toString()
    }

    /**
     * 해설 한 편을 소리로 (문장마다 따로 말하고, 해설 방식에 따라 멈추고 이어 감).
     * 입력 {"id","title","text" 또는 "sentences":[…],"lang"} · 출력은 voiceState 와 같음 (말하는 장치가 없으면 available false)
     */
    @JavascriptInterface
    fun speak(requestJson: String): String {
        val n = narrator ?: return voiceState("{}")
        val req = toMap(JSONObject(requestJson))
        val sentences = list(req["sentences"]).filterIsInstance<String>().ifEmpty { Narrator.split(req["text"] as? String ?: "") }
        val id = req["id"] as? String ?: "story"
        trip.heard(id, req["title"] as? String ?: id)
        n.onMode(engine.decide().mode)
        n.play(Narrator.Story(id, req["title"] as? String ?: id, sentences, req["lang"] as? String ?: "ko"))
        return voiceState("{}")
    }

    /**
     * 지금 해설을 어디까지 말했는지.
     * 출력 {"available","device","id","title","state","index","total","speaking","error","awaiting",
     *   "lines":[{"t","cut","resume"}],"missed":[{"id","title","sentences":[…]}]}
     */
    @JavascriptInterface
    fun voiceState(@Suppress("UNUSED_PARAMETER") requestJson: String): String {
        val out = JSONObject().put("available", narrator != null).put("device", speakerName())
        val n = narrator
        if (n != null) {
            n.tick()
            val before = n.snapshot()
            // 「지금 들려드릴까요?」 를 다 물었으면 대답을 한 번 들음 (해설 중에 계속 듣지는 않음)
            if (before.awaitingAnswer && before.state == Narrator.State.DONE) {
                if (!listenedForOffer && startListening()) listenedForOffer = true
            } else if (!before.awaitingAnswer) {
                listenedForOffer = false
            }
            // 퀴즈 문제를 다 말했으면 답을 한 번 들음
            if (quiz.current != null && before.storyId == quiz.current?.id + QUIZ_SUFFIX && before.state == Narrator.State.DONE) {
                if (!listenedForQuiz && startListening()) listenedForQuiz = true
            }
        }
        out.put("listening", listening).put("canListen", voiceInput != null).put("heard", heard ?: JSONObject.NULL)
        out.put("suggesting", suggestion?.id ?: JSONObject.NULL).put("waypoint", waypoint ?: JSONObject.NULL)
        out.put("quiz", JSONObject().put("asking", quiz.current?.id ?: JSONObject.NULL).put("asked", quiz.asked).put("right", quiz.right))
        val s = n?.snapshot() ?: return out.toString()
        val lines = JSONArray()
        for (line in s.lines) lines.put(JSONObject().put("t", line.text).put("cut", line.cut).put("resume", line.resume))
        val missed = JSONArray()
        for (m in s.missed) missed.put(JSONObject().put("id", m.storyId).put("title", m.title).put("sentences", JSONArray(m.sentences)))
        return out
            .put("id", s.storyId ?: JSONObject.NULL)
            .put("title", s.title ?: JSONObject.NULL)
            .put("state", s.state.name)
            .put("index", s.index)
            .put("total", s.total)
            .put("speaking", s.speaking ?: JSONObject.NULL)
            .put("error", s.error ?: JSONObject.NULL)
            .put("awaiting", s.awaitingAnswer)
            .put("lines", lines)
            .put("missed", missed)
            .toString()
    }

    /** 「아까 못 한 이야기가 n개 있어요. 지금 들려드릴까요?」 에 대한 대답. 입력 {"yes":true|false} (음성 명령 · 시연은 흉내 단추) */
    @JavascriptInterface
    fun voiceAnswer(requestJson: String): String {
        narrator?.answer(JSONObject(requestJson).optBoolean("yes", false))
        return voiceState("{}")
    }

    /** 해설을 그만둠 */
    @JavascriptInterface
    fun voiceStop(@Suppress("UNUSED_PARAMETER") requestJson: String): String {
        narrator?.stopAll()
        return voiceState("{}")
    }

    /**
     * 경로를 정한 순간: 해설 예정표를 만듦 (추가 1). 명소 선정은 plan 과 같고, 명소마다 해설 글(text)을 같이 받아 둠.
     * 입력 {"route":[[위도,경도],…],"pois":[{"id","name","lat","lng","priority","visibleM","text"}],"avgSpeedKmh":n,"departMs":n}
     * 출력은 progress 와 같음
     */
    @JavascriptInterface
    fun schedule(requestJson: String): String {
        val req = toMap(JSONObject(requestJson))
        val route = list(req["route"]).mapNotNull { p -> list(p).let { latLng(it.getOrNull(0), it.getOrNull(1)) } }
        val pois = poisOf(req["pois"])
        scheduleTexts = list(req["pois"]).mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            id to (m["text"] as? String ?: "")
        }.toMap()
        schedulePassed = list(req["pois"]).mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            (m["passed"] as? String)?.takeIf { it.isNotBlank() }?.let { id to it }
        }.toMap()
        pendingPassed = null
        scheduleRoute = route
        schedulePois = pois
        scheduleAvgKmh = (req["avgSpeedKmh"] as? Number)?.toDouble() ?: DEFAULT_SPEED_KMH
        departMs = (req["departMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
        travelledM = 0.0
        schedule = NarrationSchedule(engine.plan(route, pois, scheduleAvgKmh), scheduleAvgKmh)
        return scheduleView().toString()
    }

    /**
     * 차가 나아갈 때마다 (명소에 가까워지면 해설 시작 · 구현 3번). 예정표와 같은 시작 지점으로 판단.
     * 입력 {"travelledM":n,"speedKmh":n} · 실제 차량 신호가 켜져 있으면 차 위치를 경로에 투영한 거리를 씀
     * (경로 안내 시작이 오면 그 거리 · 시간으로 예정을 다시 계산)
     * 출력 {"departMs","travelledM","count","firstAfterSec","next":{"id","name","remainSec"}|null,
     *   "start":{"id","name","text"}|null (말하는 장치가 없을 때 화면이 말할 것),
     *   "entries":[{"id","name","alongM","startM","sec","startAfterSec","story","status","remainSec"}]}
     *   status = PLANNED(예정) · SPEAKING(말하는 중) · CUT(끊김) · DONE(끝) · MISSED(못 함)
     */
    @JavascriptInterface
    fun progress(requestJson: String): String {
        val sch = schedule ?: return JSONObject().put("count", 0).put("entries", JSONArray()).toString()
        val req = toMap(JSONObject(requestJson))
        val signals = engine.signals
        val routeStart = signals.routeStart
        if (liveOn && routeStart != null && routeStart != lastRouteStart) {
            // 실제 경로 안내가 시작됨: 그 거리 · 시간으로 평균 속도를 잡고 예정을 다시 만듦
            lastRouteStart = routeStart
            if (routeStart.distanceM > 0 && routeStart.durationSec > 0) scheduleAvgKmh = routeStart.distanceM / routeStart.durationSec * 3.6
            departMs = routeStart.atMs
            schedule = NarrationSchedule(engine.plan(scheduleRoute, schedulePois, scheduleAvgKmh), scheduleAvgKmh)
            return progress(requestJson)
        }
        val position = signals.position
        travelledM = if (liveOn && position != null && scheduleRoute.size >= 2) {
            planner.alongRouteM(scheduleRoute, position)
        } else {
            (req["travelledM"] as? Number)?.toDouble() ?: travelledM
        }
        val speedKmh = if (liveOn) signals.speedKmh else (req["speedKmh"] as? Number)?.toDouble()
        val tick = sch.tick(travelledM)
        var start: JSONObject? = null
        tick.start?.let { e ->
            val story = Narrator.Story(e.id, e.name, Narrator.split(scheduleTexts[e.id].orEmpty()))
            trip.heard(e.id, e.name)
            val n = narrator
            if (n != null) n.play(story)
            else start = JSONObject().put("id", e.id).put("name", e.name).put("text", scheduleTexts[e.id].orEmpty())
        }
        for (e in tick.missed) narrator?.miss(Narrator.Story(e.id, e.name, Narrator.split(scheduleTexts[e.id].orEmpty())))
        // 기능 1 · 지나친 곳은 지난 뒤 짧게: 해설을 시작 못 하고 지나친 곳은 말하는 중이 아니면 한 줄 요약을 바로 말함.
        // 바쁘면(말하는 중 · 멈춤 · 물음 중) 지나서 3km 안에서 비는 때로 미룸. 자세한 이야기는 못 한 이야기에 그대로 남음
        tick.missed.lastOrNull { schedulePassed[it.id] != null }?.let { pendingPassed = it }
        var passed: JSONObject? = null
        val p = pendingPassed
        if (tick.start != null) {
            pendingPassed = null
        } else if (p != null && travelledM - p.alongM > PASSED_WITHIN_M) {
            pendingPassed = null
        } else if (p != null && passedFree()) {
            pendingPassed = null
            val line = schedulePassed.getValue(p.id)
            val n = narrator
            if (n != null) n.play(Narrator.Story(p.id + PASSED_SUFFIX, p.name, listOf(line)))
            else passed = JSONObject().put("id", p.id).put("name", p.name).put("text", line)
        }
        return scheduleView(speedKmh).put("start", start ?: JSONObject.NULL).put("passed", passed ?: JSONObject.NULL).toString()
    }

    /** 지나친 곳 한 줄을 지금 말해도 되는지: 말하는 장치가 쉬고(아무것도 안 하거나 다 말함 · 물음 중 아님) 멈춤 방식이 아님 */
    private fun passedFree(): Boolean {
        if (engine.decide().mode == NarrationMode.HOLD) return false
        val s = narrator?.snapshot() ?: return true
        return (s.state == Narrator.State.IDLE || s.state == Narrator.State.DONE) && !s.awaitingAnswer
    }

    private fun scheduleView(speedKmh: Double? = null): JSONObject {
        val sch = schedule ?: return JSONObject().put("count", 0).put("entries", JSONArray())
        val snap = narrator?.snapshot()
        val entries = JSONArray()
        for (e in sch.entries) {
            val st = sch.status(e, travelledM)
            val status = when {
                snap?.storyId == e.id && (snap.state == Narrator.State.SPEAKING || snap.state == Narrator.State.WAITING) -> "SPEAKING"
                snap?.storyId == e.id && snap.state == Narrator.State.PAUSED -> "CUT"
                snap?.missed?.any { it.storyId == e.id } == true -> "MISSED"
                st == ScheduleStatus.STARTED -> "DONE"
                st == ScheduleStatus.PASSED -> if (narrator != null) "DONE" else "MISSED"
                else -> "PLANNED"
            }
            entries.put(
                JSONObject()
                    .put("id", e.id).put("name", e.name)
                    .put("alongM", Math.round(e.alongM)).put("startM", Math.round(e.startM))
                    .put("sec", e.sec).put("startAfterSec", Math.round(e.startAfterSec)).put("story", e.story)
                    .put("status", status)
                    .put("remainSec", Math.round(sch.secondsUntil(e, travelledM, speedKmh)))
            )
        }
        val next = sch.next(travelledM)
        return JSONObject()
            .put("departMs", departMs)
            .put("travelledM", Math.round(travelledM))
            .put("count", sch.entries.size)
            .put("firstAfterSec", sch.entries.firstOrNull()?.let { Math.round(it.startAfterSec) } ?: JSONObject.NULL)
            .put("next", next?.let { JSONObject().put("id", it.id).put("name", it.name).put("remainSec", Math.round(sch.secondsUntil(it, travelledM, speedKmh))) } ?: JSONObject.NULL)
            .put("entries", entries)
    }

    /**
     * 음성 명령 한 마디 듣기 (구현 4번 · 화면의 말하기 단추). Pleos SpeechToText 로만 듣고, 들은 말은 command 와 같이 처리.
     * 출력은 voiceState 와 같음 (listening · canListen · heard)
     */
    @JavascriptInterface
    fun listen(@Suppress("UNUSED_PARAMETER") requestJson: String): String {
        startListening()
        return voiceState("{}")
    }

    /**
     * 들은 말(또는 시연 화면의 흉내 단추)을 명령으로. 입력 {"text":"다시"} · 출력은 voiceState 와 같음
     * 다시 = 방금 말을 한 번 더 · 응 / 아니 = 못 한 이야기 대답 · 가자 = 화면이 경유지 추가로 (heard.command)
     */
    @JavascriptInterface
    fun command(requestJson: String): String {
        handleCommand(JSONObject(requestJson).optString("text", ""))
        return voiceState("{}")
    }

    private fun startListening(): Boolean {
        val input = voiceInput ?: return false
        synchronized(this) {
            if (listening) return false
            listening = true
        }
        val language = engine.decide().language
        val failed: (String) -> Unit = { why ->
            listening = false
            heard = JSONObject().put("text", "").put("command", Command.NONE.name).put("error", why)
        }
        runner {
            try {
                input.listen(
                    language,
                    { text ->
                        listening = false
                        handleCommand(text)
                    },
                    failed,
                )
            } catch (t: Throwable) {
                // 듣는 장치가 던져도 앱은 그대로 · 화면에는 못 들은 것으로
                failed(t.javaClass.simpleName)
            }
        }
        return true
    }

    private fun handleCommand(text: String) {
        val command = VoiceCommand.parse(text)
        // 퀴즈 답을 기다리면 들은 말은 답 (「다시」 는 문제를 한 번 더)
        if (quiz.current != null && command != Command.REPEAT) {
            val r = quiz.answer(text) ?: return
            narrator?.play(Narrator.Story(r.id + QUIZ_SUFFIX + ":a", "퀴즈", Narrator.split(r.line)))
            heard = JSONObject().put("text", text).put("command", "ANSWER").put("done", true).put("atMs", System.currentTimeMillis())
                .put("quiz", JSONObject().put("id", r.id).put("ok", r.ok).put("line", r.line))
            return
        }
        val done = when (command) {
            Command.REPEAT -> narrator?.repeat() == true
            Command.YES, Command.NO -> {
                val n = narrator
                if (n != null && n.snapshot().awaitingAnswer) {
                    n.answer(command == Command.YES)
                    true
                } else {
                    false
                }
            }
            Command.GO -> go()
            Command.NONE -> false
        }
        heard = JSONObject().put("text", text).put("command", command.name).put("done", done).put("atMs", System.currentTimeMillis())
    }

    /**
     * 들러볼 곳 제안 (기능 2). 입력 {"id","name","lat","lng","text"} · 제안 글을 말하고 「가자」 를 기다림. 출력은 voiceState 와 같음
     */
    @JavascriptInterface
    fun suggest(requestJson: String): String {
        val o = JSONObject(requestJson)
        val at = latLng(o.opt("lat"), o.opt("lng"))
        suggestion = if (at != null) Place(o.optString("id"), o.optString("name"), at) else null
        waypoint = null
        val text = o.optString("text")
        if (text.isNotBlank()) narrator?.play(Narrator.Story(o.optString("id") + SUGGEST_SUFFIX, o.optString("name"), Narrator.split(text)))
        return voiceState("{}")
    }

    /** 「가자」: 제안한 곳을 경유지로. 차량 신호가 켜져 있으면 내비(NaviHelper addWaypoint)에 넣음 */
    private fun go(): Boolean {
        val place = suggestion ?: return false
        suggestion = null
        val src = live
        val navi = if (liveOn && src != null) src.addWaypoint(place) else "차량 신호를 켜면 내비에 넣음"
        waypoint = JSONObject().put("id", place.id).put("name", place.name).put("navi", navi)
        trip.via(place.id, place.name)
        return true
    }

    /**
     * 가족 퀴즈 한 문제 (기능 3). 입력 {"id","question","must":[낱말],"right","wrong"} · 문제를 말하고 다 물으면 답을 한 번 들음.
     * 답은 command(들은 말) 로 채점 · 출력은 voiceState 와 같음 (quiz {asking, asked, right})
     */
    @JavascriptInterface
    fun quiz(requestJson: String): String {
        val o = JSONObject(requestJson)
        val must = o.optJSONArray("must")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val item = QuizItem(o.optString("id"), o.optString("question"), must, o.optString("right"), o.optString("wrong"))
        quiz.ask(item)
        listenedForQuiz = false
        narrator?.play(Narrator.Story(item.id + QUIZ_SUFFIX, "퀴즈", Narrator.split(item.question)))
        return voiceState("{}")
    }

    /**
     * 오늘의 여행 기록 (마-7). 출력 {"places":[{"id","name","quiz":true|false|null}],"quiz":{"asked","right"},"via":[이름],"holds":n,"missed":[제목]}
     * 문이 열리면(기록 모드) 같은 값을 recordSink 로 한 번 저장함
     */
    @JavascriptInterface
    fun record(@Suppress("UNUSED_PARAMETER") requestJson: String): String = recordJson().toString()

    private fun recordJson(): JSONObject {
        val missed = narrator?.snapshot()?.missed?.map { it.title }.orEmpty()
        val r = trip.snapshot(quiz.results, quiz.asked, quiz.right, missed)
        val places = JSONArray()
        for (p in r.places) places.put(JSONObject().put("id", p.id).put("name", p.name).put("quiz", p.quiz ?: JSONObject.NULL))
        return JSONObject().put("places", places)
            .put("quiz", JSONObject().put("asked", r.quizAsked).put("right", r.quizRight))
            .put("via", JSONArray(r.via)).put("holds", r.holds).put("missed", JSONArray(r.missed))
    }

    /** 확인용: 내비에 목적지 경로를 요청 (에뮬레이터에서 안내 값을 받을 때). 입력 {"id","name","lat","lng"} · 출력 {"navi"} */
    @JavascriptInterface
    fun route(requestJson: String): String {
        val o = JSONObject(requestJson)
        val at = latLng(o.opt("lat"), o.opt("lng")) ?: return JSONObject().put("navi", "좌표 없음").toString()
        val src = live
        val navi = if (liveOn && src != null) src.requestRoute(Place(o.optString("id"), o.optString("name"), at)) else "차량 신호가 꺼져 있음"
        return JSONObject().put("navi", navi).toString()
    }

    /** 화면을 닫을 때 신호 공급원 · 말하는 장치를 멈춤 */
    fun close() {
        liveOn = false
        engine.detach()
        narrator?.release()
        runCatching { voiceInput?.release() }
    }

    /**
     * 경로와 후보 명소 → 해설할 명소 · 순서 · 해설 길이.
     * 입력 {"route":[[위도,경도],…],"pois":[{"id","name","lat","lng","priority","visibleM"}],"avgSpeedKmh":n}
     * 출력 [{"id","alongM","offsetM","sec"}]
     */
    @JavascriptInterface
    fun plan(requestJson: String): String {
        val req = toMap(JSONObject(requestJson))
        val route = list(req["route"]).mapNotNull { p -> list(p).let { latLng(it.getOrNull(0), it.getOrNull(1)) } }
        val pois = poisOf(req["pois"])
        val avgSpeedKmh = (req["avgSpeedKmh"] as? Number)?.toDouble() ?: DEFAULT_SPEED_KMH

        val out = JSONArray()
        for (stop in engine.plan(route, pois, avgSpeedKmh)) {
            out.put(
                JSONObject()
                    .put("id", stop.poi.id)
                    .put("alongM", Math.round(stop.alongRouteM))
                    .put("offsetM", Math.round(stop.offsetM))
                    .put("sec", stop.narrationSec)
            )
        }
        return out.toString()
    }

    /**
     * LLM 초안을 사실 필드와 대조.
     * 입력 {"draft":"…","facts":["…"]}  출력 {"text","ok","unknown":["사실 필드에 없는 숫자"]}
     */
    @JavascriptInterface
    fun narrate(requestJson: String): String {
        val req = toMap(JSONObject(requestJson))
        val draft = req["draft"] as? String ?: ""
        val facts = list(req["facts"]).filterIsInstance<String>()
        val guarded = engine.narrate(draft, facts)
        val unknown = JSONArray()
        for (n in guarded.unknownNumbers) unknown.put(n)
        return JSONObject()
            .put("text", guarded.text)
            .put("ok", guarded.ok)
            .put("unknown", unknown)
            .toString()
    }

    private companion object {
        const val DEFAULT_SPEED_KMH = 80.0
        /** 지나친 곳 한 줄은 그곳을 지나 이 거리 안에서만 (m) */
        const val PASSED_WITHIN_M = 3000.0
        /** 지나친 곳 한 줄의 이야기 id 꼬리 (자세한 이야기와 구분) */
        const val PASSED_SUFFIX = ":passed"
        /** 들러볼 곳 제안 글의 이야기 id 꼬리 */
        const val SUGGEST_SUFFIX = ":suggest"
        /** 퀴즈 문제의 이야기 id 꼬리 */
        const val QUIZ_SUFFIX = ":quiz"

        fun poisOf(v: Any?): List<Poi> = list(v).mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            val location = latLng(m["lat"], m["lng"]) ?: return@mapNotNull null
            Poi(
                id = id,
                name = m["name"] as? String ?: id,
                location = location,
                priority = (m["priority"] as? Number)?.toInt() ?: 0,
                visibleM = (m["visibleM"] as? Number)?.toDouble(),
            )
        }

        fun list(v: Any?): List<Any?> = v as? List<*> ?: emptyList()

        fun latLng(lat: Any?, lng: Any?): LatLng? {
            val a = (lat as? Number)?.toDouble() ?: return null
            val b = (lng as? Number)?.toDouble() ?: return null
            return LatLng(a, b)
        }

        /** org.json 값을 Kotlin 값으로 풀어 놓음 (JSON null → null). 엔진은 org.json 을 모름. */
        fun plain(v: Any?): Any? = when {
            v == null || v == JSONObject.NULL -> null
            v is JSONObject -> toMap(v)
            v is JSONArray -> (0 until v.length()).map { plain(v.opt(it)) }
            else -> v
        }

        fun toMap(o: JSONObject): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val key = keys.next().toString()
                map[key] = plain(o.opt(key))
            }
            return map
        }
    }
}
