package com.windowdocent

import android.webkit.JavascriptInterface
import com.windowdocent.core.DocentEngine
import com.windowdocent.core.LatLng
import com.windowdocent.core.Poi
import com.windowdocent.core.PrototypeSignalSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * 프로토타입 화면(WebView) ↔ Kotlin 엔진 다리. 화면은 JSON 문자열만 주고받고, 판단은 전부 DocentEngine 이 함.
 * 화면에서는 window.Docent 로 보임: Docent.evaluate(...) · Docent.plan(...) · Docent.narrate(...)
 */
class DocentBridge(private val engine: DocentEngine = DocentEngine()) {
    private val source = PrototypeSignalSource()

    init {
        engine.attach(source)
    }

    /**
     * 신호 판 상태 S → 지금 어떻게 · 누구에게 말할지.
     * 입력 {"turn","steer","speed","brake","nav","navDist","door","rear","front","lang"}
     * 출력 {"level":0~3,"mode","reason","audience","language"}
     */
    @JavascriptInterface
    fun evaluate(stateJson: String): String {
        val state = toMap(JSONObject(stateJson))
        source.push(state)
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
