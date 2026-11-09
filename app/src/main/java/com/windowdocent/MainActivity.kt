package com.windowdocent

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.windowdocent.core.AudienceSelector
import com.windowdocent.core.NarrationGate
import com.windowdocent.core.NavGuidance
import com.windowdocent.core.Seats
import com.windowdocent.core.TurnSignal
import com.windowdocent.core.VehicleSignals
import org.json.JSONObject

/**
 * 1차 버전: 웹 프로토타입 화면을 띄우고, 해설 보류·모드 판단은 Kotlin 엔진이 맡음.
 * 프로토타입의 시뮬레이션 신호 -> Docent.evaluate() -> 해설 모드·근거·청중 -> 화면.
 * 2차에서 시뮬레이션 대신 Pleos Connect SDK 신호를 VehicleSignalSource로 연결.
 */
class MainActivity : Activity() {

    private val gate = NarrationGate()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(), "Docent")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(BRIDGE_JS, null)
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    inner class Bridge {
        /** 프로토타입 상태 S(JSON) -> {"level":n,"mode":"...","reason":"...","audience":"..."} */
        @JavascriptInterface
        fun evaluate(stateJson: String): String {
            val s = JSONObject(stateJson)
            val r = gate.evaluate(signalsFromPrototype(s))
            val a = AudienceSelector.select(seatsFromPrototype(s))
            return JSONObject()
                .put("level", r.mode.level)
                .put("mode", r.mode.name)
                .put("reason", r.reason.name)
                .put("audience", a.audience.name)
                .toString()
        }
    }

    companion object {
        fun signalsFromPrototype(s: JSONObject) = VehicleSignals(
            speedKmh = s.optDouble("speed", 0.0),
            turnSignal = when (s.optString("turn")) {
                "left" -> TurnSignal.LEFT
                "right" -> TurnSignal.RIGHT
                else -> TurnSignal.OFF
            },
            steeringAngleDeg = s.optDouble("steer", 0.0),
            navGuidance = when (s.optString("nav")) {
                "voice" -> NavGuidance.VOICE
                "near" -> NavGuidance.TURN_AHEAD
                else -> NavGuidance.NONE
            },
            doorOpen = s.optBoolean("door"),
        )

        fun seatsFromPrototype(s: JSONObject) = Seats(rearCount = if (s.optBoolean("rear")) 1 else 0)

        /** 프로토타입의 computeLoad()를 Kotlin 엔진 호출로 바꿔 끼움 */
        private val BRIDGE_JS = """
            (function(){
              if (!window.Docent) return;
              var WHY = {
                DOOR_OPEN:'<b>도착 · 도어 열림</b> → 여행 기록을 보여줍니다',
                TURN_SIGNAL:'<b>방향지시등 켜짐</b> → 해설을 멈춥니다',
                LARGE_STEERING:'<b>큰 조향</b> → 회전이 끝날 때까지 멈춤',
                NAV_VOICE:'<b>내비 안내 음성</b> → 내비가 먼저 말합니다',
                NAV_NEAR:'<b>분기점 접근</b> → 한 문장으로 짧게',
                STOPPED:'<b>정차 중</b> → 긴 이야기 가능',
                CRUISING:'<b>직진 · 안내 없음</b> → 해설과 퀴즈'
              };
              computeLoad = function(){
                var r = JSON.parse(Docent.evaluate(JSON.stringify(S)));
                return { l: r.level, why: (WHY[r.reason] || '') };
              };
              render();
            })();
        """.trimIndent()
    }
}
