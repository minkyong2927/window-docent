package com.windowdocent.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.windowdocent.core.Hazard
import com.windowdocent.core.LatLng
import com.windowdocent.core.LiveSignalSource
import com.windowdocent.core.Place
import com.windowdocent.core.SeatSpot
import com.windowdocent.core.Seats
import com.windowdocent.core.SignalBoard
import com.windowdocent.core.TurnSignal
import com.windowdocent.core.VehicleSignals

/**
 * Pleos Connect SDK 에서 실제 차량 신호를 받아 엔진에 넘기는 공급원.
 *
 * | 엔진 신호            | 받는 곳                                                           |
 * |---------------------|------------------------------------------------------------------|
 * | 방향지시등           | Vehicle TurnSignal.registerTurnSignalState                        |
 * | 조향각               | Vehicle SteeringWheel.registerSteeringWheelAngle                  |
 * | 브레이크             | Vehicle Brake.registerBrakePedalStatus                            |
 * | 도어 열림            | Vehicle Door.registerDoorPosition (사람이 타는 문 여섯)            |
 * | 좌석 착석            | Vehicle Seat.registerSeatOccupancy (운전석 빼고)                   |
 * | 다음 안내까지 거리    | NaviHelper onTBTInfo                                              |
 * | 위험 구간            | NaviHelper onCautionPointInfo · onCameraAlert (남은 거리 → CautionZone) |
 * | 도착                 | NaviHelper onDestinationArrived · onWayPointArrived               |
 * | 경로 안내 시작        | NaviHelper onRouteStarted (거리 · 시간 → 해설 예정 다시 계산)          |
 * | 차 위치              | FusedLocation (경로에 투영해 해설 시작 지점을 지났는지)                 |
 * | 속도 · 정차          | FusedLocation registerFusedLocationCallback 의 위치 변화로 계산     |
 * | 내비 안내 음성 중     | SDK 에 값이 없어 앱이 넘겨 준 감시(voiceWatch · 앱의 NaviVoiceWatcher = AudioManager)     |
 *
 * 차량 제어(set) API 는 쓰지 않음. 모듈(차량 · 내비 · 위치 · 안내 음성)마다 따로 붙여서, 하나가 없는 기기에서도 나머지는 동작함.
 * Pleos 서비스가 없는 기기(휴대폰 등)에서는 차량 · 내비 · 위치가 붙지 않고 status() 에 까닭이 남음. 앱은 그때 시연 신호를 그대로 씀.
 * 컴파일은 SDK 로 확인했고, Pleos Connect 에뮬레이터에서의 실행 확인은 2차 준비 기간에 함.
 */
class PleosSignalSource(
    context: Context,
    private val board: SignalBoard = SignalBoard(),
    /** 운전석이 왼쪽인 차인지. 오른쪽 운전석 차는 false */
    private val driverOnLeft: Boolean = true,
    /**
     * 내비 안내 음성이 나오는지 감시를 시작하고, 멈추는 함수를 돌려줌 (SDK 에 없는 값이라 앱이 넘겨 줌 · 없으면 그 모듈은 안 붙임).
     * 이 파일은 Pleos SDK 호출만 둠.
     */
    private val voiceWatch: (((Boolean) -> Unit) -> (() -> Unit))? = null,
) : LiveSignalSource {
    private val app: Context = context.applicationContext
    private val status = LinkedHashMap<String, String>()
    private val closers = HashMap<String, List<() -> Unit>>()
    private val problems = HashMap<String, MutableSet<String>>()
    private val received = HashSet<String>()
    private var started = false
    /** 붙은 내비 (경유지 · 경로 요청용) */
    @Volatile
    private var navi: NaviHelper? = null

    override fun start(listener: (VehicleSignals, Seats) -> Unit) {
        board.onChange(listener)
        started = true
        connectAll()
        board.republish()
    }

    override fun stop() {
        started = false
        board.onChange(null)
        MODULES.forEach(::disconnect)
    }

    override fun status(): Map<String, String> = synchronized(status) {
        status.mapValues { (module, state) ->
            if (state != OK) return@mapValues state
            val notes = ArrayList<String>()
            if (module !in received) notes += "아직 받은 값 없음"
            problems[module]?.takeIf { it.isNotEmpty() }?.let { notes += "못 받는 신호: ${it.sorted().joinToString()}" }
            if (notes.isEmpty()) OK else "$OK (${notes.joinToString(" · ")})"
        }
    }

    /** 차량 · 내비 · 위치 가운데 하나라도 붙었는지 (안내 음성은 어느 기기에나 있어 셈하지 않음) */
    override fun connected(): Boolean = synchronized(status) { PLEOS_MODULES.any { status[it] == OK } }

    override fun refresh() = board.republish()

    /** 「가자」: 내비에 첫째 경유지로 넣음 (NaviHelper addWaypoint · 차량 제어가 아닌 내비 요청) */
    override fun addWaypoint(place: Place): String {
        val helper = navi ?: return "내비가 안 붙음"
        return try {
            helper.addWaypoint(
                RequestWaypointInfo(place.location.lat, place.location.lng, place.name, WaypointIndex.FIRST, place.id, "", "", ""),
            )
            OK
        } catch (t: Throwable) {
            Log.w(TAG, "경유지를 못 넣음", t)
            describe(t)
        }
    }

    /** 목적지 경로 요청 (NaviHelper requestRoute · 추천 경로) */
    override fun requestRoute(place: Place): String {
        val helper = navi ?: return "내비가 안 붙음"
        return try {
            helper.requestRoute(
                RouteInfo(place.location.lat, place.location.lng, null, null, place.name, place.id, "", "", RouteOption.RECOMMENDED, "", false),
            )
            OK
        } catch (t: Throwable) {
            Log.w(TAG, "경로를 못 요청함", t)
            describe(t)
        }
    }

    /** 권한을 받은 뒤 등, 아직 못 붙은 모듈만 다시 붙여 봄 */
    override fun retry() {
        if (started) connectAll()
    }

    /** 위치 권한이 없어서 위치 모듈이 못 붙었는지 (화면이 권한을 요청하도록) */
    fun needsLocationPermission(): Boolean = synchronized(status) { status[LOCATION] == NEED_PERMISSION }

    private fun connectAll() {
        connect(VEHICLE, ::connectVehicle)
        connect(NAVI, ::connectNavi)
        connect(LOCATION, ::connectLocation)
        connect(VOICE, ::connectVoice)
    }

    private fun connect(module: String, block: (MutableList<() -> Unit>) -> Unit) {
        if (synchronized(status) { status[module] } == OK) return
        disconnect(module)
        val opened = ArrayList<() -> Unit>()
        val state = try {
            block(opened)
            OK
        } catch (t: Throwable) {
            // SDK 서비스가 없는 기기에서는 여기로 옴. 연 것만 닫고 까닭을 남김
            Log.w(TAG, "$module 모듈을 붙이지 못함", t)
            close(opened)
            opened.clear()
            describe(t)
        }
        synchronized(status) {
            // 비동기 연결(내비)이 그 사이 실패를 적었으면 그대로 둠
            if (status[module] == null) status[module] = state
            closers[module] = opened
        }
    }

    private fun disconnect(module: String) {
        val opened = synchronized(status) {
            status.remove(module)
            problems.remove(module)
            received.remove(module)
            closers.remove(module)
        }
        close(opened.orEmpty())
    }

    private fun close(opened: List<() -> Unit>) {
        for (closer in opened.asReversed()) {
            try {
                closer()
            } catch (t: Throwable) {
                Log.w(TAG, "정리 중 오류", t)
            }
        }
    }

    /** 신호 하나를 못 받아도 모듈 전체는 살려 둠 (3열이 없는 차 등) */
    private fun optional(module: String, signal: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            problem(module, signal, t)
        }
    }

    private fun problem(module: String, signal: String, t: Throwable) {
        Log.w(TAG, "$module: $signal 신호를 못 받음 (${describe(t)})")
        synchronized(status) { problems.getOrPut(module) { HashSet() }.add(signal) }
    }

    /** 모듈에서 값이 한 번이라도 왔다고 적어 둠 (화면의 연결 상태 표시용) */
    private fun got(module: String) {
        synchronized(status) { received.add(module) }
    }

    /**
     * Pleos 서비스가 이 기기에 있는지. SDK 매니페스트가 이 주소를 queries 로 선언해 두어 안드로이드 11 이상에서도 보임.
     * 휴대폰처럼 서비스가 없는 기기에서는 SDK 를 붙이지 않고 까닭만 남김.
     */
    private fun hasService(action: String): Boolean {
        val intent = Intent(action)
        val pm = app.packageManager
        return pm.queryIntentServices(intent, 0).isNotEmpty() || pm.queryIntentActivities(intent, 0).isNotEmpty()
    }

    // ── 차량: 방향지시등 · 조향각 · 브레이크 · 도어 · 좌석 ─────────────────────────
    private fun connectVehicle(opened: MutableList<() -> Unit>) {
        check(hasService(VEHICLE_ACTION)) { NO_SERVICE }
        val vehicle = Vehicle(app)
        vehicle.initialize()
        opened += { vehicle.release() }

        optional(VEHICLE, "방향지시등") {
            val api = vehicle.getTurnSignal()
            val listener = object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = vehicleValue { board.turnSignal(turnOf(value)) }
                override fun onFailed(e: Exception) = problem(VEHICLE, "방향지시등", e)
            }
            api.registerTurnSignalState(listener)
            opened += { api.unregisterTurnSignalState(listener) }
            api.getCurrentTurnSignalState({ vehicleValue { board.turnSignal(turnOf(it)) } }, { })
        }

        optional(VEHICLE, "조향각") {
            val api = vehicle.getSteeringWheel()
            val listener = object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(value: Float?) = vehicleValue { board.steeringAngle(value?.toDouble()) }
                override fun onFailed(e: Exception) = problem(VEHICLE, "조향각", e)
            }
            api.registerSteeringWheelAngle(listener)
            opened += { api.unregisterSteeringWheelAngle(listener) }
            api.getCurrentSteeringWheelAngle({ vehicleValue { board.steeringAngle(it?.toDouble()) } }, { })
        }

        optional(VEHICLE, "브레이크") {
            val api = vehicle.getBrake()
            val listener = object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(value: Boolean?) = vehicleValue { board.brakePedal(value) }
                override fun onFailed(e: Exception) = problem(VEHICLE, "브레이크", e)
            }
            api.registerBrakePedalStatus(listener)
            opened += { api.unregisterBrakePedalStatus(listener) }
            api.getCurrentBrakePedalStatus({ vehicleValue { board.brakePedal(it) } }, { })
        }

        val doors = vehicle.getDoor()
        for (door in PASSENGER_DOORS) {
            optional(VEHICLE, "도어 ${door.name}") {
                val area = DoorArea(door)
                val listener = object : DoorPositionListener {
                    override fun onPositionUpdated(value: Int?) = vehicleValue { board.door(door.name, isOpen(value)) }
                    override fun onFailed(e: Exception) = problem(VEHICLE, "도어 ${door.name}", e)
                }
                doors.registerDoorPosition(area, listener)
                opened += { doors.unregisterDoorPosition(area, listener) }
                doors.getCurrentDoorPosition(area, { vehicleValue { board.door(door.name, isOpen(it)) } }, { })
            }
        }

        val seats = vehicle.getSeat()
        for (seat in SeatValue.values()) {
            val spot = spotOf(seat)
            if (spot == SeatSpot.DRIVER) continue
            optional(VEHICLE, "좌석 ${seat.name}") {
                val area = SeatArea(seat)
                val listener = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(value: Boolean?) = vehicleValue { board.seat(seat.name, spot, value == true) }
                    override fun onFailed(e: Exception) = problem(VEHICLE, "좌석 ${seat.name}", e)
                }
                seats.registerSeatOccupancy(area, listener)
                opened += { seats.unregisterSeatOccupancy(area, listener) }
                seats.getCurrentSeatOccupancy(area, { vehicleValue { board.seat(seat.name, spot, it == true) } }, { })
            }
        }
    }

    private inline fun vehicleValue(block: () -> Unit) {
        got(VEHICLE)
        block()
    }

    private fun turnOf(value: TurnSignalValue?) = when (value) {
        TurnSignalValue.LEFT -> TurnSignal.LEFT
        TurnSignalValue.RIGHT -> TurnSignal.RIGHT
        else -> TurnSignal.OFF
    }

    /** 도어 위치값: 0 이 닫힘, 그보다 크면 열림 */
    private fun isOpen(position: Int?) = (position ?: 0) > 0

    private fun spotOf(seat: SeatValue): SeatSpot = when (seat) {
        SeatValue.ROW_1_LEFT -> if (driverOnLeft) SeatSpot.DRIVER else SeatSpot.FRONT_PASSENGER
        SeatValue.ROW_1_RIGHT -> if (driverOnLeft) SeatSpot.FRONT_PASSENGER else SeatSpot.DRIVER
        SeatValue.ROW_1_CENTER -> SeatSpot.FRONT_PASSENGER
        else -> SeatSpot.REAR
    }

    // ── 내비: 다음 안내 종류와 거리 ───────────────────────────────────────────────
    private fun connectNavi(opened: MutableList<() -> Unit>) {
        check(hasService(NAVI_ACTION)) { NO_SERVICE }
        val helper = NaviHelper(app)
        val events = object : NaviHelperEventListener {
            override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
                got(NAVI)
                // 거리 단위는 미터로 봄 (에뮬레이터에서 확인할 것)
                val nearest = SignalBoard.nearestAttentionM(tbtInfoList.map { it.type.name to it.distance.toDouble() })
                board.nextGuidance(nearest != null, nearest)
            }

            override fun onNavigationStatus(status: NavigationStatus) {
                got(NAVI)
                if (status != NavigationStatus.ROUTE_DRIVE) board.clearGuidance()
            }

            override fun onCautionPointInfo(cautionPoints: List<CautionPoint>) {
                got(NAVI)
                hazards(caution = cautionPoints.map { Hazard(it.id, it.type.name, it.remainDistance.toDouble()) })
            }

            override fun onCameraAlert(cameraAlerts: List<CameraAlert>) {
                got(NAVI)
                hazards(camera = cameraAlerts.map { Hazard(it.id, it.type.name, it.remainDistance.toDouble()) })
            }

            override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
                got(NAVI)
                board.routeStarted(routeStartInfo.distance.toDouble(), routeStartInfo.duration.toDouble())
            }

            override fun onRouteEnded() = board.clearGuidance()
            override fun onRouteCancelled() = board.clearGuidance()

            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
                board.clearGuidance()
                board.arrived()
            }

            override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = board.arrived()
        }
        // 연결이 끝나면 지금 안내를 한 번 물어봄. 연결 실패는 나중에 와서 그때 상태를 고침
        helper.initialize(
            { optional(NAVI, "지금 안내") { helper.getTBTInfo() } },
            { e ->
                Log.w(TAG, "$NAVI 연결 실패", e)
                synchronized(status) { status[NAVI] = describe(e) }
            },
        )
        opened += { helper.release() }
        navi = helper
        opened += { if (navi === helper) navi = null }
        helper.addListener(events)
        opened += { helper.removeListener(events) }
    }

    /** 위험 지점과 단속 카메라는 따로 오므로 둘을 합쳐 넘김 (한쪽만 오면 다른 쪽은 지난 목록 그대로) */
    private var lastCaution: List<Hazard> = emptyList()
    private var lastCamera: List<Hazard> = emptyList()

    private fun hazards(caution: List<Hazard>? = null, camera: List<Hazard>? = null) {
        val all = synchronized(status) {
            caution?.let { lastCaution = it }
            camera?.let { lastCamera = it }
            lastCaution + lastCamera
        }
        board.hazards(all)
    }

    // ── 위치: 속도 계산용 ────────────────────────────────────────────────────────
    private fun connectLocation(opened: MutableList<() -> Unit>) {
        val fused = FusedLocation(app)
        check(fused.isSupported()) { NO_SERVICE }
        if (app.checkSelfPermission(LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException(NEED_PERMISSION)
        }
        fused.initialize()
        opened += { fused.release() }

        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                if (location == null) return
                got(LOCATION)
                // 받은 시각으로 계산 (SDK 시각이 어떤 시계 기준인지와 상관없게)
                board.location(LatLng(location.latitude, location.longitude), System.currentTimeMillis())
            }

            override fun onError(exception: Exception) = problem(LOCATION, "위치", exception)
        }
        fused.registerFusedLocationCallback(listener)
        opened += { fused.unregisterFusedLocationCallback(listener) }
    }

    // ── 내비 안내 음성: 기기에서 길 안내 음성이 재생 중인지 ───────────────────────────
    private fun connectVoice(opened: MutableList<() -> Unit>) {
        val watch = checkNotNull(voiceWatch) { "안내 음성 감시 없음" }
        opened += watch { speaking ->
            got(VOICE)
            board.naviVoice(speaking)
        }
    }

    private fun describe(t: Throwable): String {
        if (t is SecurityException && t.message == NEED_PERMISSION) return NEED_PERMISSION
        if (t is IllegalStateException && t.message == NO_SERVICE) return NO_SERVICE
        val text = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
        return text.take(MAX_REASON)
    }

    companion object {
        const val VEHICLE = "vehicle"
        const val NAVI = "navi"
        const val LOCATION = "location"
        const val VOICE = "naviVoice"
        const val OK = "ok"
        const val NEED_PERMISSION = "권한 필요"
        const val NO_SERVICE = "이 기기에 Pleos 서비스가 없음"

        /** FusedLocation 은 실행 중 권한 요청이 필요함 (Vehicle · NaviHelper 권한은 선언만으로 받음) */
        const val LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"

        /** Pleos 차량 · 내비 서비스 주소 (Vehicle · NaviHelper 묶음 매니페스트의 queries 와 같음) */
        private const val VEHICLE_ACTION = "ai.pleos.playground.vehicle.VEHICLE_START"
        private const val NAVI_ACTION = "ai.pleos.playground.intent.action.NAVI_START"

        private const val TAG = "PleosSignalSource"
        private const val MAX_REASON = 120
        private val MODULES = listOf(VEHICLE, NAVI, LOCATION, VOICE)
        private val PLEOS_MODULES = listOf(VEHICLE, NAVI, LOCATION)
        private val PASSENGER_DOORS = listOf(
            DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT,
            DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT,
            DoorValue.ROW_3_LEFT, DoorValue.ROW_3_RIGHT,
        )
    }
}
