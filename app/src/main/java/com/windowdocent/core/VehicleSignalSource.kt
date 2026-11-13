package com.windowdocent.core

/**
 * 차량 신호 공급원. 엔진(DocentEngine)은 이 인터페이스만 봄.
 * - PrototypeSignalSource: 웹 프로토타입의 시연용 신호 판 값 (1차 · 지금 앱이 쓰는 것)
 * - 2차 준비 기간: Pleos Connect SDK(Vehicle · NaviHelper · FusedLocation) 구현체. 받을 API 는 VehicleSignals 주석 참고
 */
interface VehicleSignalSource {
    fun start(listener: (VehicleSignals, Seats) -> Unit)
    fun stop()
}
