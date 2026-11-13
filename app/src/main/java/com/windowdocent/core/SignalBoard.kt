package com.windowdocent.core

/** 좌석 종류. 운전석은 승객으로 세지 않음. */
enum class SeatSpot { DRIVER, FRONT_PASSENGER, REAR }

/**
 * 실제 차량 신호를 내는 공급원(예: Pleos SDK 구현). 화면에 연결 상태를 알려 줄 수 있음.
 */
interface LiveSignalSource : VehicleSignalSource {
    /** 모듈별 연결 상태. 값이 "ok" 로 시작하지 않으면 못 붙은 까닭 */
    fun status(): Map<String, String>

    /** 차량 신호를 내는 모듈이 하나라도 붙었는지. 없으면 화면은 시연 신호로 돌아감 */
    fun connected(): Boolean

    /** 지금 값으로 다시 알림 (오래된 속도를 '모름'으로 돌리기 위해 판단 직전에 부름) */
    fun refresh()

    /** 권한을 받은 뒤 등, 못 붙은 모듈을 다시 붙여 봄 */
    fun retry() {}
}

/**
 * 따로따로 들어오는 차량 신호(방향지시등 · 조향각 · 브레이크 · 도어 · 좌석 · 내비 안내 · 위치)를 모아
 * 엔진이 쓰는 한 벌(VehicleSignals · Seats)로 만듦.
 * - 아직 한 번도 못 받은 신호는 '모름'으로 둠 (속도 · 조향각 = null)
 * - SDK 콜백이 여러 스레드에서 와도 되게 잠금. 바뀔 때마다 듣는 쪽에 새 한 벌을 넘김
 */
class SignalBoard(
    private val speed: SpeedEstimator = SpeedEstimator(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val caution: CautionZone = CautionZone(),
) {
    private val lock = Any()
    private var turn = TurnSignal.OFF
    private var steeringDeg: Double? = null
    private var brake = false
    private val openDoors = HashSet<String>()
    private val occupied = HashMap<String, SeatSpot>()
    private var attentionAhead = false
    private var guidanceDistanceM: Double? = null
    private var naviSpeaking = false
    private var arrivedAtMs: Long? = null
    private var lastPosition: LatLng? = null
    private var routeStart: RouteStart? = null
    private var listener: ((VehicleSignals, Seats) -> Unit)? = null

    fun onChange(listener: ((VehicleSignals, Seats) -> Unit)?) {
        synchronized(lock) { this.listener = listener }
    }

    fun turnSignal(value: TurnSignal) = change { turn = value }

    fun steeringAngle(deg: Double?) = change { steeringDeg = deg }

    fun brakePedal(pressed: Boolean?) = change { brake = pressed == true }

    /** 사람이 타고 내리는 문 하나의 상태. 하나라도 열려 있으면 doorOpen */
    fun door(id: String, open: Boolean) = change {
        if (open) openDoors.add(id) else openDoors.remove(id)
    }

    fun seat(id: String, spot: SeatSpot, isOccupied: Boolean) = change {
        if (isOccupied) occupied[id] = spot else occupied.remove(id)
    }

    /**
     * 내비의 다음 안내.
     * @param attention 회전 · 분기 · 진출입처럼 운전자가 신경 써야 하는 안내인지 (직진 · 터널 통과는 아님)
     * @param distanceM 그 안내까지 남은 거리. 모르면 null
     */
    fun nextGuidance(attention: Boolean, distanceM: Double?) = change {
        attentionAhead = attention
        guidanceDistanceM = if (attention) distanceM else null
    }

    /** 경로 안내가 끝났거나 취소됨 */
    fun clearGuidance() = nextGuidance(false, null)

    /** 내비 안내 음성이 나오는 중인지. NaviHelper 에는 이 값이 없어 오디오 포커스로 알아냄 */
    fun naviVoice(speaking: Boolean) = change { naviSpeaking = speaking }

    /** 내비가 준 앞쪽 위험 지점 목록 (위험 지점 · 단속 카메라를 합친 것) */
    fun hazards(list: List<Hazard>) = change { caution.update(list, clock()) }

    /** 목적지 · 경유지 도착 */
    fun arrived() = change { arrivedAtMs = clock() }

    /** 새 위치. 위치 변화로 속도를 계산 */
    fun location(position: LatLng, timeMs: Long) = change {
        speed.update(position, timeMs)
        lastPosition = position
    }

    /** 경로 안내 시작 (거리 m · 시간 초) */
    fun routeStarted(distanceM: Double, durationSec: Double) = change { routeStart = RouteStart(clock(), distanceM, durationSec) }

    fun signals(): VehicleSignals = synchronized(lock) { signalsLocked() }

    fun seats(): Seats = synchronized(lock) { seatsLocked() }

    /** 지금 값으로 다시 알림 */
    fun republish() = change { }

    private fun signalsLocked(): VehicleSignals {
        val now = clock()
        val kmh = speed.current(now)
        return VehicleSignals(
            speedKmh = kmh,
            turnSignal = turn,
            steeringAngleDeg = steeringDeg,
            brakePressed = brake,
            navGuidance = when {
                naviSpeaking -> NavGuidance.VOICE
                attentionAhead -> NavGuidance.TURN_AHEAD
                else -> NavGuidance.NONE
            },
            navDistanceM = guidanceDistanceM,
            doorOpen = openDoors.isNotEmpty(),
            cautionZone = caution.active(now, kmh),
            arrived = arrivedAtMs?.let { now - it < ARRIVED_MS } == true,
            position = lastPosition,
            routeStart = routeStart,
        )
    }

    private fun seatsLocked() = Seats(
        frontPassenger = occupied.values.any { it == SeatSpot.FRONT_PASSENGER },
        rearCount = occupied.values.count { it == SeatSpot.REAR },
    )

    private inline fun change(block: () -> Unit) {
        val vehicle: VehicleSignals
        val seated: Seats
        val notify: ((VehicleSignals, Seats) -> Unit)?
        synchronized(lock) {
            block()
            vehicle = signalsLocked()
            seated = seatsLocked()
            notify = listener
        }
        notify?.invoke(vehicle, seated)
    }

    companion object {
        /** 도착한 뒤 이만큼 arrived 를 참으로 둠 */
        private const val ARRIVED_MS = 60_000L

        /** 운전자가 신경 쓸 필요가 없는 내비 안내 종류 (NaviHelper TurnType 이름) */
        private val PASSIVE_TURNS = setOf("TURN_UNKNOWN", "TURN_STRAIGHT", "TURN_TUNNEL", "TURN_RESTAREA")

        /** 내비 안내 종류 이름(TurnType.name)이 회전 · 분기처럼 신경 써야 하는 안내인지 */
        fun needsAttention(turnTypeName: String): Boolean = turnTypeName !in PASSIVE_TURNS

        /**
         * 내비가 준 다음 안내 목록에서 가장 가까운 '신경 써야 하는 안내'의 거리.
         * @param upcoming (안내 종류 이름, 남은 거리 m) 목록
         * @return 그런 안내가 없으면 null
         */
        fun nearestAttentionM(upcoming: List<Pair<String, Double>>): Double? =
            upcoming.filter { needsAttention(it.first) }.minOfOrNull { it.second }
    }
}
