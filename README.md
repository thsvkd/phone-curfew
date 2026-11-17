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

JDK 17 이상과 Android SDK 36이 필요하다.

```bash
./gradlew assembleDebug            # APK
./gradlew testDebugUnitTest        # 단위 테스트 26개
./gradlew connectedDebugAndroidTest # 기기 연결 후 E2E 4개
```

Windows에서는 PowerShell이면 `.\gradlew.bat testDebugUnitTest`처럼 `gradlew.bat`을 쓰고, Git Bash면 위
명령을 그대로 쓴다. Android Studio로 설치한 SDK는 `local.properties`의 `sdk.dir`로 잡힌다.
`scripts/`와 `store/`의 셸 스크립트는 macOS와 Windows의 Git Bash에서 돈다.

`connectedDebugAndroidTest`의 `OvernightE2ETest`는 밤을 기다리지 않고 일주일치 밤을 15분 주기
수집으로 재현한다. 정답지가 되는 사용 구간에서 이벤트를 만들고, 실제 Room 데이터베이스를 거쳐
판정까지 돌린 뒤 정답지와 맞춰 본다.

## 설치

그냥 쓰려면 [최신 릴리즈](https://github.com/thsvkd/phone-curfew/releases/latest)에서 APK를
폰으로 내려받아 눌러 설치한다. 설치 방법과 권한 설정은 릴리즈 노트에 적어 두었다.

개발 중에는 adb로 붙인다.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.thsvkd.curfew GET_USAGE_STATS allow
```

## 릴리즈

### Google Play (내부 테스트)

Play 앱 서명을 쓴다. 앱 서명 키는 Google이 갖고, 이 저장소는 업로드 키로 서명한 AAB만 만든다.
업로드 키는 Doppler 프로젝트 `curfew-signing`의 `dev` 설정에 `CURFEW_ANDROID_RELEASE_*`로 있고, 빌드할
때만 임시 파일로 꺼낸다. 다른 위치를 쓰려면 `CURFEW_DOPPLER_PROJECT`, `CURFEW_DOPPLER_CONFIG`로 바꾼다.

```bash
scripts/release-play.sh   # dist-release/curfew-<버전>-play.aab
```

Windows에서는 Git Bash로 실행한다. `JAVA_HOME`과 `ANDROID_HOME`이 비어 있으면 PATH의 JDK와 Android
Studio 기본 SDK 위치(`%LOCALAPPDATA%\Android\Sdk`)를 쓴다. 앞서 돌던 Gradle 데몬이 `build/`의 파일을
잡고 있으면 `clean`이 실패하므로, Windows에서는 빌드 전에 데몬을 멈춘다.

스크립트는 서명 인증서가 업로드 키와 같은지, 디버그 불가인지, 인터넷 권한과 광고 ID 권한이 없는지,
versionCode와 targetSdk가 `app/build.gradle.kts`와 같은지 확인한 뒤에만 AAB를 남긴다. 올리는 일은
Play 콘솔의 테스트 → 내부 테스트 → 새 버전 만들기에서 손으로 한다. 올릴 때마다 versionCode를 올린다.

업로드 키를 처음 만들 때만 `scripts/doppler-store-upload-key.sh`를 쓴다. 이미 있으면 덮어쓰지 않는다.
업로드 키를 잃어버려도 앱은 살아 있고, Play 콘솔에서 업로드 키 재설정을 요청하면 된다.

개인정보처리방침은 `docs/privacy.html`이고 GitHub Pages로
<https://thsvkd.github.io/phone-curfew/privacy.html>에 올라간다. 앱 설정 화면과 Play 등록정보가 이 주소를
가리킨다. 스토어 이미지와 스크린샷은 `store/`에 있다.

### GitHub 릴리즈 APK

Play와 별도로, 다른 키(`CN=phone-curfew`)로 서명한 APK를 GitHub 릴리즈에도 낸다(v1.0.x, v1.2.3).
v1.0.x를 GitHub에서 받아 쓰던 사람은 이 APK로 기록을 유지한 채 덮어쓸 수 있다. Play 버전과는 서명이
달라 서로 덮어쓰지 못하므로, 옮기려면 기존 앱을 지우고 다시 받아야 한다. 지우면 그때까지 쌓인 기록이
사라진다.

이 키의 자격 증명은 `~/.gradle/gradle.properties`(Windows는 `%USERPROFILE%\.gradle\gradle.properties`)에
두고 `./gradlew assembleRelease`로 만든다. 값이 없으면 서명 설정을 만들지 않으므로, 키가 없는 환경에서도
체크아웃과 디버그 빌드는 그대로 된다.

```properties
CURFEW_KEYSTORE=<키스토어 경로>
CURFEW_KEYSTORE_PASSWORD=<비밀번호>
CURFEW_KEY_ALIAS=curfew
CURFEW_KEY_PASSWORD=<비밀번호>
```

릴리즈에는 `phone-curfew-<버전>.apk`라는 이름으로 첨부한다. 릴리즈 노트의 설치 안내가 이 이름을 가리킨다.
