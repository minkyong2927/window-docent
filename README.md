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
| `RoutePoiPlanner` | 출발 전 내비 경로를 읽어 경로 3km 안 명소 선정, 경로 순서 정렬, 붙은 명소는 우선순위로 정리, 다음 명소까지 시간으로 해설 길이(15~60초) 결정 |
| `NarrationGate` | 차량 신호로 해설 방식 결정: 정차 긴 이야기 / 직진 해설·퀴즈 / 분기점 1km 한 문장 / 회전·차선 변경·내비 음성 보류 / 도어 열림 여행 기록 |
| `AudienceSelector` | 좌석 착석·승객 언어로 운전자·가족·외국어 모드 자동 전환 |
| `FactGuard` | 환각 차단: LLM 출력에 사실 필드에 없는 숫자(연도 등)가 있으면 거부하고 사실 원문으로 대체 |

- 임계값(조향각, 분기점 거리, 경로 폭, 해설 길이)은 `DocentConfig` 설정값으로 분리
- 경로가 정해지면 해설을 미리 생성·캐시 → 터널·산간 통신 끊김에도 재생 (2차)

## Pleos Connect SDK/API 사용 계획

차량 제어(set) API는 쓰지 않고 조회·음성 API만 사용.

| 모듈 | 쓰는 데이터 | 용도 |
|---|---|---|
| NaviHelper | 목적지, 경로, 도착 예정 시간 | 명소 미리 선정, 해설 길이, 경유지 추가 |
| FusedLocation | 현재 위치 | 명소 반경 진입, 정차 판단 |
| Vehicle | 방향지시등, 조향각, 브레이크 | 회전·차선 변경 중 해설 보류 |
| Vehicle - Seat | 좌석별 착석 | 모드 자동 전환, 퀴즈 참여 인원 |
| Vehicle - Door | 도어 열림 | 하차 시점 여행 기록·QR |
| STT / TTS | 승객 발화, 해설문 | 언어 선택, 퀴즈 답, "가자" 명령 / 다국어 해설 |
| LLM | 명소 사실 필드 | 언어·길이·대상에 맞춘 문장화, 퀴즈 생성 |

- 관광지 원천: 한국관광공사 TourAPI (좌표·개요·운영시간·행사, 다국어)

## 구현 현황

| 구분 | 1차 (~11/15) | 2차 준비 (~12/2) |
|---|---|---|
| 엔진 4종 | Kotlin 구현 + 단위 테스트 26개 | 실차 신호·실경로로 임계값 조정 |
| 명소 데이터 | 프로토타입 시나리오 (서울→담양) | TourAPI 연동, 명소 30곳 × 언어 3종 |
| 차량 신호 | 웹 프로토타입 시뮬레이션 → 엔진 | `VehicleSignalSource`에 Pleos SDK 구현체 연결 |
| 화면 | 웹 프로토타입을 WebView로 표시 | 네이티브 화면 |

- 앱 화면의 해설 보류 단계와 근거는 프로토타입 JS가 아니라 Kotlin `NarrationGate`가 계산 (`MainActivity` 브리지)

## 시연

- 시연 영상: [docs/demo.webm](docs/demo.webm) (약 1분, 무음, 시나리오: 가족 여행 서울→담양)
- 웹 프로토타입: [prototype/index.html](prototype/index.html) → 브라우저로 열면 바로 동작, '자동 시연' 버튼

## 빌드·실행

필요: JDK 17, Android SDK 35, Gradle 8.11

```bash
gradle :app:testDebugUnitTest   # 엔진 테스트
gradle :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

- Android Studio: 저장소 폴더 열기 → `app` 실행 (Automotive 에뮬레이터 또는 일반 안드로이드 기기)
- release APK: [Releases](https://github.com/minkyong2927/window-docent/releases) 또는 Actions 실행 결과의 `release-apk`
- CI(GitHub Actions): push마다 테스트 → release 빌드, `v*` 태그 push 시 Releases에 APK 첨부
- 서명키: CI가 빌드마다 새로 생성 (저장소에 키 없음, 로컬 빌드는 debug 키)

## 구조

```
app/src/main/java/com/windowdocent/
  MainActivity.kt            WebView + 엔진 브리지
  core/
    Signals.kt               차량 신호·좌석 값, 설정(DocentConfig)
    RoutePoiPlanner.kt       경로 기반 명소 선정·순서·해설 길이
    NarrationGate.kt         운전 상황별 해설 방식
    Audience.kt              운전자·가족·외국어 모드
    FactGuard.kt             사실 필드 밖 숫자 차단
    VehicleSignalSource.kt   신호 공급원 인터페이스 (Pleos SDK 연결 지점)
app/src/test/...             단위 테스트
prototype/index.html         웹 프로토타입 (앱 assets로도 사용)
docs/demo.webm               시연 영상
```

## 팀 역할

| 역할 | 담당 |
|---|---|
| 기획 | 문제 정의, 시나리오, 기획서 |
| 개발 | 엔진, 앱, 프로토타입 |
| 디자인 | 화면, 시연 영상 |
| 검증 | 데이터 조사, 테스트 |
