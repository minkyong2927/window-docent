# 창밖 도슨트 (Window Docent)

내비 경로를 미리 읽고, 운전 상황과 탑승자에 맞춰 창밖 명소를 먼저 이야기해 주는 차량용 음성 도슨트. 2026 현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작.

[![android](https://github.com/minkyong2927/window-docent/actions/workflows/android.yml/badge.svg)](https://github.com/minkyong2927/window-docent/actions/workflows/android.yml)

## 실행 방법

- 웹 프로토타입: [prototype/index.html](prototype/index.html)을 브라우저로 열면 바로 동작 (설치·인터넷 필요 없음). '자동 시연' 단추로 서울→담양 가족 여행 장면이 처음부터 끝까지 재생됨
- 앱: Android Studio에서 저장소 폴더를 열고 `app` 실행 (Pleos Connect 에뮬레이터 또는 일반 안드로이드 기기). 앱 화면은 같은 프로토타입을 WebView로 띄우고, 판단은 Kotlin 엔진이 함
- 명령으로 빌드 (필요: JDK 17, Android SDK 35, Gradle 8.11)

```bash
gradle :app:testDebugUnitTest   # 단위 테스트
gradle :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

- CI(GitHub Actions): push마다 단위 테스트 → release 빌드, `v*` 태그 push 시 Releases에 APK 첨부. 서명키는 CI가 빌드마다 새로 만듦 (저장소에 키 없음, 로컬 빌드는 debug 키)

## 테스트 환경과 필요한 설정

- 필요한 설정: 없음. 앱 코드는 외부 API·키를 쓰지 않음 (글꼴 포함). Pleos SDK는 공개 저장소(`nexus-playground.pleos.ai`)에서 Gradle이 받음. 인터넷 권한은 Pleos TextToSpeech SDK 묶음이 자체 검증용으로 넣는 것 하나
- 권한: Pleos 읽기 권한(방향지시등·주행·조향·도어·좌석·내비 경로·위치)과 음성 권한 둘(TextToSpeech·SpeechToText)만 선언, 위치는 '차량 신호'를 켤 때 요청. 차량 제어 권한은 없음. Pleos SDK 를 만들거나 부르다 예외가 나도 앱은 꺼지지 않고 다음 수단(안드로이드 음성, 시연 신호)으로 넘어감
- 단위 테스트: JVM(JDK 17)에서 실행, 차량·에뮬레이터 없이 돎. CI가 push마다 실행
- 앱: Android 10(API 29) 이상. 화면은 1920×1080(차량 화면 1920×720 + 시연용 신호 판) 기준으로 그리고, 기기 화면 크기에 맞춰 전체가 보이게 자동으로 맞춤

## Pleos Connect 에뮬레이터 버전과 화면 규격

- 대상: Pleos Connect Emulator 2.1.0 (Android 14, API 34, Automotive, AVD 'Pleos Connect v2.1.0')
- 1차 제출본은 에뮬레이터에서 아직 실행해 보지 않음 (에뮬레이터 설치에는 약관 동의가 필요해 1차 결과(11/23) 뒤로 미룸). 앱은 기본으로 화면 아래 '시연용 차량 신호' 판으로 동작하고, '차량 신호'를 켜면 Pleos Connect SDK 신호를 받도록 코드가 연결돼 있음
- 화면 규격: 1920×1080 기준으로 그린 화면을 기기 화면 크기에 맞춰 통째로 줄이고 늘림 (1280×720, 1920×720, 1024×768, 2560×1440에서 전체가 보이는 것을 확인)

## APK 위치

- 지금: [Releases](https://github.com/minkyong2927/window-docent/releases) 또는 Actions 실행 결과의 `release-apk`
- 제출 때: 저장소 `apk/팀명_WindowDocent_release.apk` (팀명이 정해지면 workflow의 `TEAM_NAME`을 바꿔 다시 빌드)

## 구현 현황

| 구현 | 상태 | 파일 | 확인 |
|---|---|---|---|
| 엔진 4종 (명소 선정·해설 방식·청중 모드·사실 검증) | 완료 | `core/RoutePoiPlanner` `NarrationGate` `Audience` `FactGuard` `DocentEngine` | 단위 테스트 |
| 화면 ↔ 엔진 | 완료 | `DocentBridge`, `prototype/index.html` | 단위 테스트(화면이 주고받는 JSON), 브라우저용 JS 규칙과 Kotlin 엔진 결과 같음 |
| 1. 실제 차량 신호 받기 | 코드 연결 | `pleos/PleosSignalSource`, `core/SignalBoard` `SpeedEstimator`, `NaviVoiceWatcher` | SDK와 컴파일, 단위 테스트. 에뮬레이터 실행은 2차 |
| 2. 음성으로 해설하기 (바로 멈추고 이어 말하기) | 코드 연결 | `core/Narrator`, `pleos/PleosSpeaker`, `AppSpeaker` | SDK와 컴파일, 단위 테스트, 브라우저 시연에서 소리. 에뮬레이터 실행은 2차 |
| 2 보강. 위험 구간 멈춤 · 두 번 끊긴 문장은 넘어감 · 못 한 이야기 모아 듣기 | 코드 연결 | `core/CautionZone`, `core/Narrator`, `pleos/PleosSignalSource` | SDK와 컴파일, 단위 테스트, 브라우저 시연(위험 구간 · 🎤 응 / 나중에 단추). 위험 지점 남은 거리 단위(미터로 봄)는 에뮬레이터에서 확인 |
| 3. 명소에 가까워지면 해설 시작 + 해설 예정 보여 주기 | 코드 연결 | `core/NarrationSchedule`, `core/RoutePoiPlanner`(경로 위 진행 거리), `pleos/PleosSignalSource`(위치 · 경로 안내 시작) | 단위 테스트(시연 경로 예정값 고정), 브라우저 시연(예정표 · 주행 흉내). 에뮬레이터 실행은 2차 |
| 4. 음성 명령 듣기 + 다시라고 말하면 반복 | 코드 연결 | `core/VoiceCommand`, `core/Narrator`(다시 말하기), `pleos/PleosVoiceInput` | SDK와 컴파일, 단위 테스트, 브라우저 시연(🎤 다시 · 응 · 나중에 흉내 단추). 에뮬레이터 실행은 2차 |
| 5 ~ 8. 경유지 추가, 가족 퀴즈, 여행 기록과 QR, 외국어 내용 | 진행 중 | | |
| 관광 데이터(TourAPI)·학습 모드·에뮬레이터 실행 확인 | 2차 (~12/2) | | |

- 단위 테스트 145개
- 앱에서는 해설 방식·청중 모드·명소 순서와 해설 길이·사실 검증·해설 음성을 모두 Kotlin 엔진이 계산 (`DocentBridge` → `DocentEngine`, `Narrator`), 화면 아래에 '판단 · Kotlin 엔진' 표시. 브라우저로 열면 같은 규칙의 JS가 대신함

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
| `Narrator` | 해설을 문장마다 소리로: 멈춤(방향지시등·큰 조향·내비 음성·위험 구간)이면 말하던 중이라도 바로 끊고, 풀리면 "아까 하던 이야기 이어서 할게요" 뒤 끊긴 문장을 처음부터. 같은 문장이 두 번 끊기면 다음 문장으로 넘어가고 그 문장은 못 한 이야기에 담음. "다시"라고 하면 말하던 문장을 처음부터(끝난 뒤 30초 안이면 마지막 문장을 한 번 더 · 멈춤 중에는 안 함). 정차가 5초 넘거나 도착하면 "아까 못 한 이야기가 n개 있어요"를 한 번 묻고 응이면 차례로 말함. 분기점 접근은 한 문장까지, 도어 열림이면 끝 |
| `NarrationSchedule` | 해설 예정과 시작 판단: 해설이 명소 옆에서 끝나도록 해설 길이만큼 앞을 시작 지점으로(앞 해설과 겹치지 않게), 예정 시각과 실제 시작이 같은 시작 지점을 씀. 시작 못 하고 지나친 해설은 못 한 이야기로 |
| `VoiceCommand` | 들은 말 → 명령: 다시 · 뭐라고 · 한 번 더 · 못 들었어 = 다시, 응 · 들려줘 = 예, 아니 · 나중에 = 아니오, 가자 · 들르자 = 경유지 (띄어쓰기 · 부호 무시 · 한 글자 낱말은 그 말만 했을 때) |
| `CautionZone` | 위험 구간 판단: 내비가 알려 준 위험 지점(13종) · 어린이 보호구역 카메라(2종)가 300m 안이면 멈춤, 지난 뒤 300m(속도를 모르면 20초) 더, 위험 지점 신호가 15초 안 오면 풀림. 과속방지턱·단속 카메라 등 10종은 보지 않음 |

- 엔진은 `DocentEngine` 한 곳에서 부르고, 차량 신호는 `VehicleSignalSource`로 들어옴: 기본은 프로토타입 신호 판을 읽는 `PrototypeSignalSource`, 화면의 '차량 신호'를 켜면 Pleos Connect SDK에서 받는 `PleosSignalSource`
- 임계값(조향각, 분기점 거리, 정차 속도, 경로 폭, 해설 길이)은 `DocentConfig` 설정값으로 분리
- 경로가 정해지면 해설을 미리 생성·캐시 → 터널·산간 통신 끊김에도 재생 (2차)

## Pleos Connect SDK 사용

공모전이 제공하는 것의 활용 상태 (1차 제출 기준)

| 제공되는 것 | 이 저장소에서 | 파일 |
|---|---|---|
| Vehicle SDK 2.3.2 | 방향지시등·조향각·브레이크·도어·좌석 착석 (읽기만) | `pleos/PleosSignalSource.kt` |
| NaviHelper SDK 3.0.5 | 다음 안내 거리, 위험 지점·카메라, 경로 안내 시작, 도착 | `pleos/PleosSignalSource.kt` |
| FusedLocation SDK 1.1.3 | 위치 (속도 계산, 해설 시작 판단) | `pleos/PleosSignalSource.kt` |
| TextToSpeech SDK 3.3.1 | 해설 음성 (기기 안 음성 ON_DEVICE · 앱 등록 없음) | `pleos/PleosSpeaker.kt` |
| SpeechToText SDK 3.3.1 | 음성 명령 (기기 안 인식 ON_DEVICE 로만 · 앱 등록 없음) | `pleos/PleosVoiceInput.kt` |
| 공식 샘플 앱 셋 (vehicle · navi · fusedlocation) | SDK 저장소 주소·의존성·매니페스트 설정을 따라 함 | `settings.gradle.kts`, `app/build.gradle.kts`, `AndroidManifest.xml` |
| Pleos Connect Emulator 2.1.0 | 아직 안 씀 (1차 결과 뒤) | |


차량 제어(set) API는 쓰지 않고 조회·음성 API만 사용. API 이름은 공개 SDK(Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3, TextToSpeech·SpeechToText 3.3.1) 묶음에서 확인. 확인 수준: '코드 연결' = 저장소 코드가 그 API를 부르고 SDK와 함께 컴파일됨(에뮬레이터 실행 확인은 2차), '계획' = 아직 코드 없음.

| SDK | 쓰는 API | 용도 | 확인 수준 |
|---|---|---|---|
| Vehicle | TurnSignal `registerTurnSignalState`, SteeringWheel `registerSteeringWheelAngle`, Brake `registerBrakePedalStatus` | 회전·차선 변경 중 해설 보류, 정차 판단 보조 | 코드 연결 |
| Vehicle - Seat | `registerSeatOccupancy` | 모드 자동 전환, 퀴즈 참여 인원 | 코드 연결 |
| Vehicle - Door | `registerDoorPosition` | 하차 시점 여행 기록·QR | 코드 연결 |
| NaviHelper | `onTBTInfo`(다음 안내 종류·거리), `onNavigationStatus` | 분기점 접근 판단 | 코드 연결 |
| NaviHelper | `onCautionPointInfo`(CautionPoint 종류·남은 거리), `onCameraAlert`(CameraAlert 종류·남은 거리) | 위험 구간 멈춤 | 코드 연결 (남은 거리 단위는 미터로 봄 · 에뮬레이터 확인 전) |
| NaviHelper | `onDestinationArrived`, `onWayPointArrived` | 도착 때 못 한 이야기 제안 | 코드 연결 |
| NaviHelper | `onDrivingInfo`(남은 거리·시간), `addWaypoint` | 해설 길이, "가자" 경유지 추가 | 계획 |
| FusedLocation | `registerFusedLocationCallback`(위치) | 속도 계산(Vehicle SDK에 차속 없음), 정차 판단, 해설 시작 판단(위치를 경로에 투영) | 코드 연결 |
| NaviHelper | `onRouteStarted`(거리·시간) | 경로 안내가 시작되면 해설 예정 다시 계산 | 코드 연결 |
| TextToSpeech | `speak`(문장마다), `stop`, `setLocale`, `EventListener`(onDone·onError) | 해설 음성. 일시정지가 없어 멈춤·이어 말하기는 `Narrator`가 문장 단위로 | 코드 연결 |
| SpeechToText | `request`, `stop`, `setLocale`, `ResultListener`(onUpdated · onError · onEndedRecognition) | 음성 명령 (다시 · 응 · 나중에 · 가자). 화면의 말하기 단추를 누를 때와 못 한 이야기를 물은 뒤에만 한 번 들음 (해설 중 계속 듣기 · 호출어는 안 씀) | 코드 연결 |

- 관광지 원천: 한국관광공사 TourAPI (좌표·개요·운영시간·행사, 다국어) · 2차. 1차는 시연 경로의 명소 7곳을 화면 안에 넣은 자료로 동작
- 해설 문장·퀴즈 생성(LLM)은 Pleos SDK를 쓰지 않음: TourAPI 사실 필드를 언어·길이·대상에 맞게 미리 문장화해 `FactGuard`로 검증해 두고, 차 안에서는 TextToSpeech로 재생
- Pleos 문서에 없는 안드로이드 기능 둘 (SDK에 같은 기능이 없어서):
  - 내비 안내 음성이 나오는 중인지: 안드로이드 `AudioManager` 재생 목록에서 용도가 길 안내 음성인 소리를 봄 (`NaviVoiceWatcher` · Pleos 기기에서도 이것으로 판단)
  - 해설 음성: Pleos 기기에서는 Pleos TextToSpeech 만 씀. Pleos 음성 서비스가 없는 기기(휴대폰 등)에서만 안드로이드 기본 TTS로 대신하고, 그것도 없으면 소리 없이 진행 (`AppSpeaker`)
- 음성 인식은 Pleos SpeechToText SDK로만 하고, 차량 마이크 음성을 바깥 음성 인식 서비스로 보내지 않음 (공지 규정). 브라우저 시연에서는 음성 인식 대신 흉내 단추를 씀
- 차속은 Vehicle SDK에 없어 FusedLocation 위치 변화로 계산 (`SpeedEstimator`), 못 구하는 동안은 '모름'
- 경로 좌표열은 NaviHelper가 주지 않음(출발·경유·도착 점만) → 1차 해설 예정은 앱이 가진 시연 경로로 계산하고, 명소에 가까워졌는지는 위치를 그 경로에 투영해 판단(반경 진입 알림 `addProximityAlert` 대신)

## 시연

- 시연 영상: [docs/demo.webm](docs/demo.webm) (약 1분, 무음, 시나리오: 가족 여행 서울→담양)
- 해설은 소리로도 나옴('소리 켬/끔' 단추 · 브라우저 음성). 자동 시연의 분기점 장면에서 내비 음성이 나오면 말하던 해설이 바로 끊기고, 지나간 뒤 "아까 하던 이야기 이어서 할게요"와 함께 끊긴 문장부터 다시 말함
- 신호 단추: 방향지시등·내비 음성·브레이크·속도·뒷좌석·승객 언어·도어로 판단이 바뀌는 것을 확인, '사실 검증'은 틀린 연도가 든 초안이 사실 원문으로 바뀌는 것을 보여 줌
- 예정표: 경로를 정한 순간 오른쪽에 "오늘 해설 4곳 · 첫 해설은 50분 뒤"와 명소마다 출발 뒤 몇 분 · 시각 · 이야기/한 줄 · 상태(예정 · 말하는 중 · 끊김 · 끝 · 못 함). '주행 흉내'를 누르면 시연 경로를 따라 달리며 예정표가 "다음 해설: 천안삼거리 · n분 뒤" 한 줄로 접히고, 시작 지점에 오면 그 명소 해설을 시작함 (한 줄을 누르면 펴고 접음)
- 음성 명령: '🎤 다시'(말하던 문장을 처음부터 · 끝난 뒤 30초 안이면 마지막 문장) · '🎤 응' '🎤 나중에'(못 한 이야기 물음에만 보임)는 음성 명령 흉내 단추. '🎤 말하기'는 앱(Pleos 기기)에서 Pleos SpeechToText로 한 마디를 듣고, 브라우저에서는 음성 인식을 쓰지 않는다는 안내만 띄움. 들은 말과 알아들은 명령이 화면에 보임
- '위험 구간': 어린이 보호구역 200m 앞을 켜고 끔. 켜면 해설이 바로 멈추고 내비 칸에 위험 지점이 보임, 끄면 이어 말함 (자동 시연 장면은 그대로)
- 못 한 이야기: 같은 문장이 두 번 끊기면(방향지시등을 두 번 켰다 끔) 그 문장은 넘어가고, 정차 5초가 지나면 "아까 못 한 이야기가 1개 있어요. 지금 들려드릴까요?"를 묻고 '🎤 응' '🎤 나중에'(음성 명령 흉내) 단추가 나옴
- '차량 신호': 앱에서 누르면 Pleos SDK 신호로 판단하고 신호 판에 실제 값과 모듈별 연결 상태(차량·내비·위치·안내 음성)를 보여 줌. 브라우저나 Pleos 기기가 아닌 곳에서는 안내 글을 띄우고 시연 신호를 그대로 씀

## 구조

```
app/src/main/java/com/windowdocent/
  core/                      판단 엔진 (순수 Kotlin · 안드로이드 · Pleos import 없음)
    DocentEngine.kt          엔진을 한 곳에서 부르는 창구
    Signals.kt               차량 신호·좌석 값, 설정(DocentConfig)
    RoutePoiPlanner.kt       경로 기반 명소 선정·순서·해설 길이
    NarrationGate.kt         운전 상황별 해설 방식
    Audience.kt              운전자·가족·외국어 모드
    FactGuard.kt             사실 필드 밖 숫자 차단
    Narrator.kt              해설을 문장마다 말하고 멈춤·이어 말하기·못 한 이야기
    CautionZone.kt           위험 구간 판단 (300m 앞 · 지난 뒤 300m · 15초)
    VoiceCommand.kt          들은 말 → 명령 (다시 · 응 · 나중에 · 가자) · 음성 입력 인터페이스
    NarrationSchedule.kt     해설 예정 · 시작 지점에서 해설 시작
    VehicleSignalSource.kt   신호 공급원 인터페이스
    PrototypeSignalSource.kt 프로토타입 신호 판을 읽는 공급원 (기본)
    SignalBoard.kt           따로 오는 차량 신호를 모아 한 벌로 (실제 신호 공급원 LiveSignalSource)
    SpeedEstimator.kt        위치 변화로 차속 계산
  pleos/                     Pleos SDK 연결 (SDK 호출만)
    PleosSignalSource.kt     Vehicle·NaviHelper·FusedLocation 에서 신호를 받는 공급원
    PleosSpeaker.kt          TextToSpeech 로 한 문장씩 말함
    PleosVoiceInput.kt       SpeechToText 로 한 마디 들음 (ON_DEVICE)
  MainActivity.kt            앱 연결: WebView로 프로토타입 화면을 띄움
  DocentBridge.kt            앱 연결: 화면 ↔ 엔진 다리 (window.Docent: evaluate·plan·narrate·live·liveState·speak·voiceState·voiceStop·voiceAnswer·schedule·progress·listen·command)
  AppSpeaker.kt              앱 연결: 말하는 장치 고르기 (Pleos TTS → 안드로이드 TTS → 소리 없음)
  NaviVoiceWatcher.kt        앱 연결: 내비 안내 음성이 나오는 중인지 (AudioManager)
app/src/test/java/com/windowdocent/   단위 테스트 (대상 파일 하나에 <대상>Test.kt 하나)
prototype/index.html         화면 한 장 (웹 프로토타입 · 앱 assets로도 사용)
prototype/fonts/             Pretendard 글꼴 (SIL OFL 1.1)
build.gradle.kts · settings.gradle.kts · gradle.properties · app/build.gradle.kts · AndroidManifest.xml · strings.xml · android.yml · .gitignore   빌드 설정
README.md                    문서
apk/ · docs/                 제출 산출물 (APK · 시연 영상)
```

## 팀 역할

| 역할 | 담당 |
|---|---|
| 기획 | 문제 정의, 시나리오, 기획서 |
| 개발 | 엔진, 앱, 프로토타입 |
| 디자인 | 화면, 시연 영상 |
| 검증 | 데이터 조사, 테스트 |
