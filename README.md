# 창밖 도슨트 (Window Docent)

내비 경로를 미리 읽고, 운전 상황과 탑승자에 맞춰 창밖 명소를 먼저 이야기해 주는 차량용 음성 도슨트. 2026 현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작.

[![android](https://github.com/minkyong2927/window-docent/actions/workflows/android.yml/badge.svg)](https://github.com/minkyong2927/window-docent/actions/workflows/android.yml)

## 개요

- 문제: 국내 여행 대부분이 자가용인데 창밖 풍경은 그냥 지나침. 운전 중엔 찾아볼 수 없고, 폰 오디오 가이드는 회전 중인지·내비가 안내 중인지·누가 탔는지 모름
- 해결: 무엇을 말할지는 **경로**가, 언제·누구에게 말할지는 **차량 신호**가 정함
- 기본 단위: 지나가는 장소 하나마다 '해설 → 들러볼 곳 제안 → 한마디' 30~60초
- 화면: 주행 중에는 해설 제목 한 줄, 하차 시 여행 기록

## 주요 기능

1. 지나가는 곳 해설: 경로상 명소에 가까워지면 연도·의미·볼거리 2~3문장. 명소가 드문 국도는 지명 유래·특산·축제
2. 들러볼 곳 제안: "죽녹원은 경로에서 3분" → "가자"라고 말하면 경유지 추가
3. 가족 퀴즈: 뒷좌석 착석 시 짧은 이야기·음성 퀴즈, 도착 시 여행 기록 저장
4. 외국어 승객 모드: 승객 언어로 해설 + 한국어 한마디, 하차 시 QR로 명소 목록
5. 학습 모드: 같은 해설을 공부할 언어로 듣고 퀴즈 복습

## 엔진 구성

| 모듈 | 하는 일 |
|---|---|
| `RoutePoiPlanner` | 출발 전 내비 경로를 읽어 경로 3km 안 명소 선정(산처럼 멀리서 보이는 명소는 더 넓게), 경로 순서 정렬, 붙은 명소는 우선순위로 정리, 다음 명소까지 시간으로 해설 길이(15~60초) 결정 |
| `NarrationGate` | 차량 신호로 해설 방식 결정: 정차 긴 이야기 / 직진 해설·퀴즈 / 분기점 1km 한 문장 / 회전·차선 변경·내비 음성 보류 / 도어 열림 여행 기록. 못 받은 신호는 건너뛰고, 속도를 모르면 달리는 중으로 봄 |
| `AudienceSelector` | 좌석 착석·승객 언어로 운전자·가족·외국어 모드 자동 전환 |
| `FactGuard` | 환각 차단: LLM 출력에 사실 필드에 없는 숫자(연도 등)가 있으면 거부하고 사실 원문으로 대체 |
| `Narrator` | 해설을 문장마다 소리로: 멈춤(방향지시등·큰 조향·내비 음성)이면 말하던 중이라도 바로 끊고, 풀리면 "아까 하던 이야기 이어서 할게요" 뒤 끊긴 문장을 처음부터. 분기점 접근은 한 문장까지, 도어 열림이면 끝 |

- 네 엔진은 `DocentEngine` 한 곳에서 부르고, 차량 신호는 `VehicleSignalSource`로 들어옴: 기본은 프로토타입 신호 판을 읽는 `PrototypeSignalSource`, 화면의 '차량 신호'를 켜면 Pleos Connect SDK에서 받는 `PleosSignalSource`
- 임계값(조향각, 분기점 거리, 정차 속도, 경로 폭, 해설 길이)은 `DocentConfig` 설정값으로 분리
- 경로가 정해지면 해설을 미리 생성·캐시 → 터널·산간 통신 끊김에도 재생 (2차)

## Pleos Connect SDK/API 사용 계획

차량 제어(set) API는 쓰지 않고 조회·음성 API만 사용. API 이름은 공개 SDK(Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3, TextToSpeech·SpeechToText 3.3.1)에서 확인. 차량 신호(Vehicle·NaviHelper·FusedLocation)는 `PleosSignalSource`로 코드에 연결해 SDK와 함께 컴파일되고, Pleos Connect 에뮬레이터에서의 실행 확인은 2차 준비 기간에 진행.

| SDK | 쓰는 API | 용도 |
|---|---|---|
| NaviHelper | `onTBTInfo`(다음 안내 종류·거리), `onDrivingInfo`(남은 거리·시간), `addWaypoint` | 분기점 접근 판단, 해설 길이, "가자" 경유지 추가 |
| FusedLocation | `registerFusedLocationCallback`(위치·방위), `addProximityAlert` | 명소 반경 진입, 속도 계산(Vehicle SDK에 차속 없음), 정차 판단 |
| Vehicle | TurnSignal `registerTurnSignalState`, SteeringWheel `registerSteeringWheelAngle`, Brake `registerBrakePedalStatus` | 회전·차선 변경 중 해설 보류, 정차 판단 보조 |
| Vehicle - Seat | `registerSeatOccupancy` | 모드 자동 전환, 퀴즈 참여 인원 |
| Vehicle - Door | `registerDoorPosition` | 하차 시점 여행 기록·QR |
| TextToSpeech | `speak`(문장마다), `stop`, `setLocale`, `EventListener`(onDone·onError) | 해설 음성. 일시정지가 없어 멈춤·이어 말하기는 `Narrator`가 문장 단위로 |
| SpeechToText | 승객 발화 인식 | 언어 선택, 퀴즈 답, "가자" 명령 |

- 관광지 원천: 한국관광공사 TourAPI (좌표·개요·운영시간·행사, 다국어)
- 해설 문장·퀴즈 생성(LLM)은 Pleos SDK를 쓰지 않음: TourAPI 사실 필드를 언어·길이·대상에 맞게 미리 문장화해 `FactGuard`로 검증해 두고, 차 안에서는 TextToSpeech로 재생
- 내비 안내 음성이 나오는 중인지는 NaviHelper에 없어 안드로이드 `AudioManager` 재생 정보(용도가 길 안내 음성인 소리)로 판단 (`NaviVoiceWatcher`)
- 차속은 Vehicle SDK에 없어 FusedLocation 위치 변화로 계산 (`SpeedEstimator`), 못 구하는 동안은 '모름'

## 구현 현황

| 구분 | 1차 (~11/15) | 2차 준비 (~12/2) |
|---|---|---|
| 엔진 | Kotlin 구현 + 단위 테스트 99개 (엔진·신호 공급원·신호 모으기·속도 계산·해설 음성·화면 브리지) | 실차 신호·실경로로 임계값 조정 |
| 해설 음성 | `Narrator`가 문장마다 말함: Pleos 기기는 Pleos TextToSpeech, 아니면 안드로이드 기본 TTS, 둘 다 없으면 소리 없이 진행. 브라우저 시연은 같은 규칙의 JS + 브라우저 음성 | Pleos Connect 에뮬레이터에서 실행 확인 |
| 명소 데이터 | 프로토타입 시나리오 (서울→담양) | TourAPI 연동, 명소 30곳 × 언어 3종 |
| 차량 신호 | 기본은 웹 프로토타입 신호 판 → `PrototypeSignalSource` → 엔진. '차량 신호' 단추로 Pleos SDK(`PleosSignalSource`) 신호로 바꿈, Pleos 기기가 아니면 시연 신호로 돌아감 | Pleos Connect 에뮬레이터에서 실행 확인 |
| 화면 | 웹 프로토타입을 WebView로 표시 | 네이티브 화면 |

- 앱에서는 해설 방식·청중 모드·명소 순서와 해설 길이·사실 검증을 모두 Kotlin 엔진이 계산 (`DocentBridge` → `DocentEngine`), 화면 아래에 '판단 · Kotlin 엔진' 표시
- 브라우저로 열면 같은 규칙의 JS가 대신함. 브라우저용 명소 목록이 엔진 결과와 같은지는 단위 테스트(`DocentBridgeTest`)가 확인

## 시연

- 시연 영상: [docs/demo.webm](docs/demo.webm) (약 1분, 무음, 시나리오: 가족 여행 서울→담양)
- 웹 프로토타입: [prototype/index.html](prototype/index.html) → 브라우저로 열면 바로 동작, '자동 시연' 버튼
- 해설은 소리로도 나옴('소리 켬/끔' 단추 · 브라우저 음성). 자동 시연의 분기점 장면에서 내비 음성이 나오면 말하던 해설이 바로 끊기고, 지나간 뒤 "아까 하던 이야기 이어서 할게요"와 함께 끊긴 문장부터 다시 말함
- 신호 단추: 방향지시등·내비 음성·브레이크·속도·뒷좌석·승객 언어·도어로 판단이 바뀌는 것을 확인, '사실 검증'은 틀린 연도가 든 초안이 사실 원문으로 바뀌는 것을 보여 줌
- '차량 신호': 앱에서 누르면 Pleos SDK 신호로 판단하고 신호 판에 실제 값과 모듈별 연결 상태(차량·내비·위치·안내 음성)를 보여 줌. 브라우저나 Pleos 기기가 아닌 곳에서는 안내 글을 띄우고 시연 신호를 그대로 씀

## 빌드·실행

필요: JDK 17, Android SDK 35, Gradle 8.11

```bash
gradle :app:testDebugUnitTest   # 엔진·브리지 단위 테스트
gradle :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

- Android Studio: 저장소 폴더 열기 → `app` 실행 (Automotive 에뮬레이터 또는 일반 안드로이드 기기)
- release APK: [Releases](https://github.com/minkyong2927/window-docent/releases) 또는 Actions 실행 결과의 `release-apk`
- CI(GitHub Actions): push마다 테스트 → release 빌드, `v*` 태그 push 시 Releases에 APK 첨부
- 서명키: CI가 빌드마다 새로 생성 (저장소에 키 없음, 로컬 빌드는 debug 키)

## 테스트 환경

- 단위 테스트: JVM(JDK 17)에서 실행, 차량·에뮬레이터 없이 돎. CI가 push마다 실행
- 필요한 설정: 없음. 앱 코드는 외부 API·키를 쓰지 않음 (글꼴 포함). Pleos SDK는 공개 저장소(`nexus-playground.pleos.ai`)에서 Gradle이 받음. 인터넷 권한은 Pleos TextToSpeech SDK 묶음이 자체 검증용으로 넣는 것 하나
- 권한: Pleos 읽기 권한만 선언(방향지시등·주행·조향·도어·좌석·내비 경로·위치), 위치는 '차량 신호'를 켤 때 요청. 차량 제어 권한은 없음
- 앱: Android 10(API 29) 이상. 화면은 1920×1080(차량 화면 1920×720 + 시연용 신호 판) 기준으로 그리고, 기기 화면 크기에 맞춰 전체가 보이게 자동으로 맞춤
- 대상 에뮬레이터: Pleos Connect Emulator 2.1.0 (Android 14, API 34, Automotive). 앱은 기본으로 화면 아래 '시연용 차량 신호' 판으로 동작하고, '차량 신호'를 켜면 Pleos Connect SDK 신호를 받도록 코드가 연결돼 있음. 에뮬레이터에서의 실행 확인은 2차 준비 기간에 진행

## 구조

```
app/src/main/java/com/windowdocent/
  MainActivity.kt            WebView로 프로토타입 화면을 띄움
  DocentBridge.kt            화면 ↔ 엔진 다리 (window.Docent: evaluate·plan·narrate·live·liveState·speak·voiceState·voiceStop)
  AppSpeaker.kt              말하는 장치 고르기 (Pleos TTS → 안드로이드 TTS → 소리 없음)
  core/
    DocentEngine.kt          엔진 네 개를 한 곳에서 부르는 창구
    Signals.kt               차량 신호·좌석 값, 설정(DocentConfig)
    RoutePoiPlanner.kt       경로 기반 명소 선정·순서·해설 길이
    NarrationGate.kt         운전 상황별 해설 방식
    Audience.kt              운전자·가족·외국어 모드
    FactGuard.kt             사실 필드 밖 숫자 차단
    VehicleSignalSource.kt   신호 공급원 인터페이스 (Pleos SDK 연결 지점)
    PrototypeSignalSource.kt 프로토타입 신호 판을 읽는 공급원 (기본)
    SignalBoard.kt           따로 오는 차량 신호를 모아 한 벌로 (실제 신호 공급원 LiveSignalSource)
    SpeedEstimator.kt        위치 변화로 차속 계산
    Narrator.kt              해설을 문장마다 말하고 멈춤·이어 말하기
  pleos/
    PleosSignalSource.kt     Pleos SDK(Vehicle·NaviHelper·FusedLocation)에서 신호를 받는 공급원
    NaviVoiceWatcher.kt      내비 안내 음성이 나오는 중인지 (AudioManager)
    PleosSpeaker.kt          Pleos TextToSpeech 로 한 문장씩 말함
app/src/test/...             단위 테스트
prototype/index.html         웹 프로토타입 (앱 assets로도 사용)
prototype/fonts/             Pretendard 글꼴 (SIL OFL 1.1)
docs/demo.webm               시연 영상
```

## 팀 역할

| 역할 | 담당 |
|---|---|
| 기획 | 문제 정의, 시나리오, 기획서 |
| 개발 | 엔진, 앱, 프로토타입 |
| 디자인 | 화면, 시연 영상 |
| 검증 | 데이터 조사, 테스트 |
