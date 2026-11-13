# 창밖 도슨트

경로와 차량 신호로 창밖 명소를 먼저 이야기해 주는 차량용 음성 도슨트. 2026 Pleos Connect 차량용 앱 개발 공모전 출품작.

[![android](https://github.com/minkyong2927/window-docent/actions/workflows/android.yml/badge.svg)](https://github.com/minkyong2927/window-docent/actions/workflows/android.yml)

## 1. 실행 방법

| 대상 | 방법 |
|---|---|
| 웹 프로토타입 | prototype/index.html 을 브라우저로 열고 자동 시연 단추 |
| 앱 | Android Studio 에서 app 실행, 에뮬레이터나 안드로이드 기기 |
| 명령 빌드 | JDK 17, Android SDK 35, Gradle 8.11 |
| 자동 빌드 | push 마다 GitHub Actions 가 단위 테스트와 release APK |

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleRelease
```

## 2. 테스트 환경

| 항목 | 값 |
|---|---|
| 개발 PC | Windows 11 |
| 에뮬레이터 | Pleos Connect Emulator 2.1.0 |
| 시스템 이미지 | Android 14, API 34 |
| 가상 기기 | Pleos Connect v2.1.0, 메모리 4GB, 콜드 부팅 |
| 화면 | 2560x1440, 160dpi, 앱 영역 1682x1210 |
| 가속 | WHPX |
| 도구 | adb 37.0.1, 에뮬레이터 37.2.13, 가상 콘솔 |
| 빌드 | GitHub Actions, JDK 17 |
| 단위 테스트 | JVM 에서 167개, 차량 없이 돎 |

## 3. 필요한 설정

| 항목 | 내용 |
|---|---|
| 키, 외부 API | 없음 |
| SDK | Pleos 공개 저장소에서 Gradle 이 받음 |
| 권한 | Pleos 읽기 권한, 음성 권한 둘, 내비 경로 검색, 차량 제어 권한 없음 |
| 위치 권한 | 차량 신호를 켤 때 요청 |
| SDK 실패 | 앱은 꺼지지 않고 안드로이드 음성이나 시연 신호로 넘어감 |
| 인터넷 권한 | TextToSpeech 묶음이 넣는 것 하나 |

## 4. 에뮬레이터 버전과 화면 규격

| 항목 | 내용 |
|---|---|
| 버전 | Pleos Connect Emulator 2.1.0, RELEASE.release_connect_v2.1.0 |
| 화면 | 2560x1440, 160dpi |
| 앱 영역 | 1682x1210, WebView 배율 1.375, 보이는 영역 1224x880 |
| 화면 맞춤 | 1920x1080 으로 그려 보이는 영역에 맞춤 |

에뮬레이터에서 확인한 것

| 기능 | 결과 |
|---|---|
| 실행 | 꺼지지 않음, 화면 전체가 보임 |
| 차량 신호 | 차량, 내비, 위치, 안내 음성 연결 |
| 가상 콘솔 값 | 문, 조향각, 방향지시등, 브레이크가 판단에 반영 |
| 해설 음성 | Pleos TextToSpeech 로 끝까지, 첫 모델 읽기 약 15초 |
| 음성 명령 | SpeechToText 요청까지, 마이크 인식은 확인 전 |
| 내비 안내 | 연결됨, 경로가 없어 안내 값은 확인 전 |
| 위치 | 에뮬레이터 위치를 옮기면 따라옴 |

## 5. APK 위치

| 언제 | 위치 |
|---|---|
| 지금 | Actions 실행 결과의 release-apk, Releases |
| 제출 | apk/팀명_WindowDocent_release.apk, 팀명이 정해지면 |

## 구현 현황

| 구현 | 상태 | 대표 파일 |
|---|---|---|
| 엔진 넷, 명소 선정, 해설 방식, 청중, 사실 검증 | 완료 | DocentEngine.kt 외 4 |
| 화면과 엔진 연결 | 완료 | DocentBridge.kt |
| 1 차량 신호 받기 | 완료, 에뮬레이터 확인 | PleosSignalSource.kt 외 3 |
| 2 음성 해설, 멈추고 이어 말하기 | 완료, 에뮬레이터 확인 | Narrator.kt 외 2 |
| 2 보강 위험 구간, 못 한 이야기 | 완료, 거리 단위 확인 전 | CautionZone.kt |
| 3 가까워지면 해설, 예정 보이기 | 완료 | NarrationSchedule.kt |
| 기능 1 지나친 곳은 지난 뒤 한 줄 | 완료 | DocentBridge.kt |
| 기능 2 들러볼 곳 제안, 가자로 경유지 | 완료, 내비 반영은 에뮬레이터 확인 전 | DocentBridge.kt 외 2 |
| 기능 3 가족 퀴즈 문제, 들은 답 채점, 점수 | 완료 | Quiz.kt 외 1 |
| 여행 기록, 들은 곳, 퀴즈, 들른 곳, 멈춤, 못 들은 이야기 | 완료, QR 은 진행 중 | TripRecord.kt 외 2 |
| 기능 4 외국어 승객 해설, 한국어 한마디 | 완료, QR 은 진행 중 | DocentBridge.kt |
| 4 음성 명령, 다시 말하기 | 완료, 마이크 확인 전 | VoiceCommand.kt 외 1 |
| 5 ~ 8 경유지, 퀴즈, 기록과 QR, 외국어 | 진행 중 | |

## 사용한 데이터

| 자료 | 쓰는 곳 |
|---|---|
| 공개 SDK 다섯 | 차량 신호, 내비, 위치, 음성 |
| 공식 샘플 셋 | SDK 저장소, 의존성, 매니페스트 설정 |
| 가상 콘솔 값 | 에뮬레이터 확인 |
| 임의 자료 | sample-data 폴더, 아래 표 |
| 글꼴 | Pretendard, SIL OFL 1.1 |

## 임의 자료

| 파일 | 개수 | 기준 | 실제 서비스에서 |
|---|---|---|---|
| sample-data/scenario-a.js | 경로 2, 명소 7 | 기획안 시나리오 A | 내비 경로, TourAPI |
| sample-data/stories.js | 지나친 곳 한 줄 3 | 시나리오 A 명소 셋, 사실 숫자 없음 | TourAPI 소개글 |
| sample-data/stories.js | 들러볼 곳 1 | 시나리오 A 죽녹원 대나무축제 | TourAPI 행사 정보 |
| sample-data/stories.js | 퀴즈 3 | 지나친 곳 한 줄 안의 낱말이 답 | TourAPI 사실로 만든 문제 |
| sample-data/stories.js | 외국어 해설 3, 한국어 한마디 3 | 같은 명소 셋을 영어, 스페인어로, 인사 감사 맛 표현 | TourAPI 다국어 소개글 |

- 공모전이 주지 않는 자료만 임의로 씀
- 화면에도 시연 자료라고 적음

## Pleos SDK 사용

| SDK | 쓰는 것 | 용도 | 확인 |
|---|---|---|---|
| Vehicle 2.3.2 | 방향지시등, 조향각, 브레이크, 도어, 좌석 | 해설 멈춤, 모드, 기록 | 에뮬레이터 |
| NaviHelper 3.0.5 | 다음 안내, 위험 지점, 경로 시작, 도착, addWaypoint, requestRoute | 분기점, 위험 구간, 예정, 경유지 | 연결까지 |
| FusedLocation 1.1.3 | 위치 | 속도, 해설 시작 | 에뮬레이터 |
| TextToSpeech 3.3.1 | speak, stop, setLocale | 해설 음성, 기기 안 방식 | 에뮬레이터 |
| SpeechToText 3.3.1 | request, stop, setLocale | 음성 명령, 기기 안 방식 | 요청까지 |
| 차량 제어 | 쓰지 않음 | | |

- Pleos 문서에 없는 안드로이드 기능 둘, 내비 안내 음성 감지와 Pleos 음성이 없는 기기의 안드로이드 TTS
- 음성 인식은 Pleos SpeechToText 로만 하고 바깥 서비스로 보내지 않음
- 차속은 Vehicle SDK 에 없어 위치 변화로 계산
- 경로 좌표는 NaviHelper 가 주지 않아 시연 경로를 씀
- 해설 문장 생성에 LLM SDK 를 쓰지 않고 미리 쓴 문장을 사실 검증

## 엔진

| 모듈 | 하는 일 |
|---|---|
| RoutePoiPlanner | 경로 옆 명소 선정, 순서, 해설 길이 |
| NarrationGate | 차량 신호로 해설 방식 결정 |
| AudienceSelector | 좌석과 언어로 운전자, 가족, 외국어 모드 |
| FactGuard | 사실 자료에 없는 숫자를 막음 |
| Narrator | 문장마다 말하고 멈추고 이어 말함, 다시 말하기 |
| QuizBook | 퀴즈 답에 꼭 들어야 하는 낱말로 채점, 점수 |
| TripRecord | 여행 기록을 모아 문이 열릴 때 기기에 저장 |
| NarrationSchedule | 해설 예정과 시작 지점 |
| VoiceCommand | 들은 말을 명령으로, 다시, 응, 나중에, 가자 |
| CautionZone | 위험 지점 300m 앞부터 지난 뒤까지 멈춤 |
| SpeakerChain | Pleos 음성, 안드로이드 음성, 소리 없음 차례 |

## 시연

- 자동 시연 단추로 서울에서 담양까지 가족 여행 장면이 처음부터 끝까지 재생
- 신호 단추로 방향지시등, 내비 음성, 브레이크, 속도, 좌석, 언어, 도어를 바꿔 판단을 봄
- 주행 흉내 단추로 예정표가 접히고 시작 지점에서 해설이 시작
- 위험 구간 단추로 해설이 멈추고 끄면 이어 말함
- 다시, 응, 나중에 단추는 음성 명령 흉내, 말하기 단추는 앱에서만 듣기
- 차량 신호 단추는 앱에서 Pleos SDK 값으로 판단
- 시연 영상 docs/demo.webm, 무음, 새 화면으로 다시 찍을 예정

## 파일

| 종류 | 폴더 | 내용 |
|---|---|---|
| 판단 엔진 | app/src/main/java/com/windowdocent/core | 순수 Kotlin |
| Pleos 연결 | app/src/main/java/com/windowdocent/pleos | SDK 호출만 |
| 앱 연결 | app/src/main/java/com/windowdocent | 화면, 브리지, 음성 장치 |
| 단위 테스트 | app/src/test | 대상 하나에 테스트 하나 |
| 화면 | prototype | 웹 프로토타입, 앱 화면으로도 씀 |
| 글꼴 | prototype/fonts | Pretendard |
| 빌드 설정 | 맨 위, app, .github | Gradle, 매니페스트, Actions |
| 문서와 제출물 | README, docs, apk | 시연 영상, APK |
| 임의 자료 | sample-data | 시연 경로와 명소 |

## 팀 역할

| 역할 | 담당 |
|---|---|
| 기획 | 문제 정의, 시나리오, 기획서 |
| 개발 | 엔진, 앱, 프로토타입 |
| 디자인 | 화면, 시연 영상 |
| 검증 | 데이터 조사, 테스트 |
