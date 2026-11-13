package com.windowdocent

import android.webkit.JavascriptInterface
import com.windowdocent.core.DocentEngine
import com.windowdocent.core.LatLng
import com.windowdocent.core.LiveSignalSource
import com.windowdocent.core.NavGuidance
import com.windowdocent.core.Poi
import com.windowdocent.core.PrototypeSignalSource
import com.windowdocent.core.TurnSignal
import org.json.JSONArray
import org.json.JSONObject

/**
 * 프로토타입 화면(WebView) ↔ Kotlin 엔진 다리. 화면은 JSON 문자열만 주고받고, 판단은 전부 DocentEngine 이 함.
 * 화면에서는 window.Docent 로 보임: Docent.evaluate(...) · Docent.plan(...) · Docent.narrate(...) · Docent.live(...) · Docent.liveState(...)
 *
 * 신호는 기본이 화면의 시연용 신호 판(PrototypeSignalSource). 화면에서 「차량 신호」 를 켜면 실제 차량 신호(LiveSignalSource ·
 * 앱에서는 Pleos SDK 의 PleosSignalSource)로 바꾸고, 끄면 시연 신호로 돌아감.
 *
 * @param liveFactory 실제 차량 신호 공급원을 만드는 곳. 없으면 「차량 신호」 를 켤 수 없음
 * @param onLiveStart 실제 신호를 붙인 직후 (화면 쪽에서 위치 권한을 요청하는 등)
 * @param runner 공급원 붙이기 · 떼기를 어느 스레드에서 할지 (앱은 메인 스레드 · 테스트는 바로)
 */
class DocentBridge(
    private val engine: DocentEngine = DocentEngine(),
    private val liveFactory: (() -> LiveSignalSource)? = null,
    private val onLiveStart: (LiveSignalSource) -> Unit = {},
    private val runner: (() -> Unit) -> Unit = { it() },
) {
    private val source = PrototypeSignalSource()
    private var live: LiveSignalSource? = null

    @Volatile
    private var liveOn = false

    init {
        engine.attach(source)
    }

    /**
     * 신호 판 상태 S → 지금 어떻게 · 누구에게 말할지.
     * 입력 {"turn","steer","speed","brake","nav","navDist","door","rear","front","lang"}
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
                    engine.attach(src)
                    onLiveStart(src)
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

    /** 화면을 닫을 때 신호 공급원을 멈춤 */
    fun close() {
        liveOn = false
        engine.detach()
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
        val pois = list(req["pois"]).mapNotNull { p ->
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
