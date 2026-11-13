package com.windowdocent.core

/** 화면에 넘길 판단 한 벌: 지금 어떻게 말할지(차량 신호) + 누구에게 말할지(좌석·언어) */
data class Decision(
    val mode: NarrationMode,
    val reason: GateReason,
    val audience: Audience,
    val language: String,
)

/** 사실 검증을 거친 해설문. ok 가 아니면 text 는 사실 필드 원문. */
data class GuardedText(val text: String, val ok: Boolean, val unknownNumbers: List<String>)

/**
 * 엔진 네 개(RoutePoiPlanner · NarrationGate · AudienceSelector · FactGuard)를 한 곳에서 부르는 창구.
 * 화면(1차 WebView 브리지 · 2차 네이티브 화면)은 이것만 보고, 차량 신호는 VehicleSignalSource 로 들어옴.
 */
class DocentEngine(config: DocentConfig = DocentConfig()) {
    private val gate = NarrationGate(config)
    private val planner = RoutePoiPlanner(config)
    private var source: VehicleSignalSource? = null

    @Volatile
    var signals: VehicleSignals = VehicleSignals()
        private set

    @Volatile
    var seats: Seats = Seats()
        private set

    /** 승객이 말로 고른 언어 (SpeechToText). 없으면 null */
    @Volatile
    var guestLanguage: String? = null

    /** 신호 공급원을 붙임. 이미 붙어 있던 공급원은 멈춤. */
    fun attach(source: VehicleSignalSource) {
        this.source?.stop()
        this.source = source
        source.start { vehicle, seated ->
            signals = vehicle
            seats = seated
        }
    }

    fun detach() {
        source?.stop()
        source = null
    }

    /** 마지막으로 받은 신호로 판단 */
    fun decide(): Decision {
        val gated = gate.evaluate(signals)
        val audience = AudienceSelector.select(seats, guestLanguage)
        return Decision(gated.mode, gated.reason, audience.audience, audience.language)
    }

    /** 경로를 읽어 해설할 명소 · 순서 · 해설 길이를 정함 */
    fun plan(route: List<LatLng>, pois: List<Poi>, avgSpeedKmh: Double): List<PlannedStop> =
        planner.plan(route, pois, avgSpeedKmh)

    /** LLM 이 쓴 초안을 사실 필드와 대조. 사실 필드에 없는 숫자가 있으면 사실 원문으로 바꿈. */
    fun narrate(draft: String, facts: List<String>): GuardedText {
        val check = FactGuard.check(draft, facts)
        return GuardedText(FactGuard.safeText(draft, facts), check.ok, check.unknownNumbers)
    }
}
