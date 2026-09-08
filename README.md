# 커퓨 (phone-curfew)

새벽 2시에 잠들기로 한 약속을 지켰는지, 핸드폰 사용 기록으로 매일 판정해서 보여 주는 개인용
Android 앱.

아침에 앱을 열면 어젯밤 결과가 성공인지 실패인지 한 줄로 보이고, 최근 일주일이 초록과 빨강
원으로 남는다. 실패했다면 몇 시에 얼마나 썼는지 10분 간격 그래프에서 확인할 수 있다.

**차단하지 않는다.** 기록하고 채점만 한다.

## 문서

- [PRD.md](PRD.md) — 무엇을 왜 만드는가. 확정된 결정과 기능 요구사항.
- [SPEC.md](SPEC.md) — 어떻게 만드는가. 수집 알고리즘, 데이터 모델, 판정 규칙, 화면 규격.
- [mockups/](mockups/) — 채택 전 검토한 디자인 초안 5종과 검토용 서버.

## 구성

| 경로 | 역할 |
|---|---|
| `collect/UsageCollector.kt` | 사용량 이벤트를 사용 구간으로, 구간을 10분 칸으로. Android 의존 없음 |
| `collect/CollectWorker.kt` | 15분 주기 수집, 커서 이어붙이기, 수집 공백 기록 |
| `score/Curfew.kt` | 커퓨 창 산출, 부분 칸 안분, 4상태 판정 |
| `data/` | Room 3테이블, DataStore 설정 |
| `ui/` | 한 장짜리 화면과 Compose Canvas 차트 |

Kotlin · Jetpack Compose · Room · WorkManager. 차트 라이브러리는 쓰지 않는다.
선언하는 권한은 `PACKAGE_USAGE_STATS` 하나뿐이고 네트워크를 쓰지 않는다.

## 빌드

JDK 17 이상과 Android SDK 35가 필요하다.

```bash
./gradlew assembleDebug            # APK
./gradlew testDebugUnitTest        # 단위 테스트 16개
./gradlew connectedDebugAndroidTest # 기기 연결 후 E2E 3개
```

`connectedDebugAndroidTest`의 `OvernightE2ETest`는 밤을 기다리지 않고 일주일치 밤을 15분 주기
수집으로 재현한다. 정답지가 되는 사용 구간에서 이벤트를 만들고, 실제 Room 데이터베이스를 거쳐
판정까지 돌린 뒤 정답지와 맞춰 본다.

## 설치

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.thsvkd.curfew GET_USAGE_STATS allow
```

권한은 앱 안의 배너에서 시스템 설정으로 들어가 켜도 된다.
