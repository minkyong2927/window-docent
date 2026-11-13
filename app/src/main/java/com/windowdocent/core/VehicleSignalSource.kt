package com.windowdocent.core

/**
 * 차량 신호 공급원. 엔진(DocentEngine)은 이 인터페이스만 봄.
 * - PrototypeSignalSource: 웹 프로토타입의 시연용 신호 판 값 (앱의 기본)
 * - pleos/PleosSignalSource: Pleos Connect SDK(Vehicle · NaviHelper · FusedLocation) 실제 신호 (화면의 「차량 신호」 를 켰을 때 · LiveSignalSource)
 */
interface VehicleSignalSource {
    fun start(listener: (VehicleSignals, Seats) -> Unit)
    fun stop()
}
