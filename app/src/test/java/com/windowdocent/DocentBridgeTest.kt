package com.windowdocent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 화면이 실제로 주고받는 JSON 으로 브리지 → 엔진을 끝까지 돌려 봄 */
class DocentBridgeTest {
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

    /** prototype/index.html 안의 시연 데이터 블록(<script id="demo-data">) */
    private fun prototypeDemoData(): JSONObject {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val html = File(dir, "prototype/index.html")
            if (html.isFile) {
                val block = Regex("""<script id="demo-data" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                    .find(html.readText(Charsets.UTF_8)) ?: error("demo-data 블록이 없음")
                return JSONObject(block.groupValues[1])
            }
            dir = dir.parentFile
        }
        error("prototype/index.html 을 찾지 못함")
    }
}
