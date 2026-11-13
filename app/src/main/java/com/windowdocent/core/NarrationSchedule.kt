package com.windowdocent.core

/** 해설 예정 한 줄 (경로를 정한 순간 화면에 보여 줌) */
data class ScheduleEntry(
    val id: String,
    val name: String,
    /** 명소 옆에 오는 지점 (출발점부터 경로를 따라 m) */
    val alongM: Double,
    /** 해설을 시작할 지점 (startPoint) */
    val startM: Double,
    /** 해설 길이(초) */
    val sec: Int,
    /** 출발 뒤 몇 초에 시작할지 (평균 속도로 계산) */
    val startAfterSec: Double,
) {
    /** 방식: 30초 이상이면 이야기, 아니면 한 줄 */
    val story: Boolean get() = sec >= NarrationSchedule.STORY_SEC
}

/** 예정 줄 상태: 예정 · 시작할 때 · 시작함 · 시작도 못 하고 지나침 */
enum class ScheduleStatus { PLANNED, DUE, STARTED, PASSED }

/**
 * 해설 예정과 시작 판단 (구현 3번 「명소에 가까워지면 해설 시작」 + 추가 1 「해설 예정 보여 주기」).
 * 예정 시각(startAfterSec)과 실제 시작 판단(tick)이 같은 시작 지점(startPoint)을 씀 → 예정표와 실제가 어긋나지 않음.
 * - 시작 지점: 해설이 명소 옆을 지날 때 끝나도록 해설 길이만큼 앞, 앞 해설이 끝나기 전이면 그 뒤, 출발점보다 앞이면 출발점
 * - 시작: 차가 경로를 따라 시작 지점을 지나면 (위치를 경로에 투영한 거리 · 시연은 주행 흉내)
 * - 못 함: 시작하지 못한 채 명소를 passM 넘게 지나침 → 못 한 이야기 (추가 3)
 * - 남은 시간: 속도를 알면 지금 속도(5km/h 이상), 모르면 평균 속도
 * 경로 좌표열은 NaviHelper 가 주지 않아 1차는 앱이 가진 시연 경로로 계산 (README).
 */
class NarrationSchedule(
    stops: List<PlannedStop>,
    private val avgSpeedKmh: Double,
    private val passM: Double = 300.0,
) {
    val entries: List<ScheduleEntry>
    private val started = HashSet<String>()
    private val reported = HashSet<String>()

    init {
        var prevEndM = 0.0
        entries = stops.map { s ->
            val start = startPoint(s.alongRouteM, s.narrationSec, prevEndM, avgSpeedKmh)
            prevEndM = start + avgSpeedKmh / 3.6 * s.narrationSec
            ScheduleEntry(s.poi.id, s.poi.name, s.alongRouteM, start, s.narrationSec, start / (avgSpeedKmh / 3.6))
        }
    }

    /** 이 줄의 지금 상태 */
    @Synchronized
    fun status(e: ScheduleEntry, travelledM: Double): ScheduleStatus = when {
        e.id in started -> ScheduleStatus.STARTED
        e.id in reported -> ScheduleStatus.PASSED
        travelledM > e.alongM + passM -> ScheduleStatus.PASSED
        travelledM >= e.startM -> ScheduleStatus.DUE
        else -> ScheduleStatus.PLANNED
    }

    /** 시작까지 남은 초 (지났으면 0) */
    fun secondsUntil(e: ScheduleEntry, travelledM: Double, speedKmh: Double?): Double {
        val v = (speedKmh?.takeIf { it >= MOVING_KMH } ?: avgSpeedKmh) / 3.6
        return if (v <= 0) 0.0 else ((e.startM - travelledM) / v).coerceAtLeast(0.0)
    }

    /** 다음에 시작할 줄 (없으면 null) */
    fun next(travelledM: Double): ScheduleEntry? =
        entries.firstOrNull { status(it, travelledM) == ScheduleStatus.PLANNED || status(it, travelledM) == ScheduleStatus.DUE }

    /**
     * 차가 travelledM 까지 왔을 때 할 일. 지금 시작할 해설(가장 최근에 시작 지점을 지난 것 하나)과
     * 시작하지 못하고 넘어간 줄(지나쳤거나, 한꺼번에 여러 시작 지점을 지나 앞의 것을 놓친 것)을 한 번씩 알려 줌.
     */
    @Synchronized
    fun tick(travelledM: Double): Tick {
        val due = entries.filter { status(it, travelledM) == ScheduleStatus.DUE }
        val start = due.lastOrNull()
        val missed = ArrayList<ScheduleEntry>()
        for (e in entries) {
            if (e.id in started || e.id in reported) continue
            val skipped = e in due && e != start
            if (skipped || status(e, travelledM) == ScheduleStatus.PASSED) {
                reported += e.id
                missed += e
            }
        }
        start?.let { started += it.id }
        return Tick(start, missed)
    }

    data class Tick(val start: ScheduleEntry?, val missed: List<ScheduleEntry>)

    companion object {
        const val STORY_SEC = 30
        private const val MOVING_KMH = 5.0

        /**
         * 해설 시작 지점 (예정 시각과 실제 시작 판단이 같이 씀).
         * 해설이 명소 옆에서 끝나도록 해설 길이만큼 앞 · 앞 해설이 끝나는 지점보다 뒤 · 출발점보다 뒤.
         */
        fun startPoint(alongM: Double, sec: Int, prevEndM: Double, speedKmh: Double): Double =
            maxOf(0.0, prevEndM, alongM - speedKmh / 3.6 * sec)
    }
}
