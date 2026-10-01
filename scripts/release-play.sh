#!/usr/bin/env bash
# Google Play 에 올릴 릴리스 AAB 를 만든다. 올리지는 않는다(Play 콘솔에서 사람이 올린다).
#
#   scripts/release-play.sh
#
# 서명은 업로드 키(Doppler 의 CURFEW_ANDROID_RELEASE_*)로 한다. Play 앱 서명을 쓰므로 Play 가 설치 파일을
# 자기 키로 다시 서명한다. 그래서 Play 에서 받은 앱과 GitHub 릴리스 APK 는 같은 패키지여도 서로 덮어쓰지 못한다.
# 업로드 키는 scripts/doppler-store-upload-key.sh 로 만든다.
#
# 비밀은 Doppler 에 두고 빌드 때만 꺼낸다. 디스크에는 빌드하는 동안의 임시 키스토어뿐이고, 끝나면(실패해도) 지운다.
#   CURFEW_DOPPLER_PROJECT / CURFEW_DOPPLER_CONFIG   Doppler 위치 (기본 dev / dev)
#   JAVA_HOME     JDK 17 이상 (비어 있으면 brew 의 openjdk@21)
#   ANDROID_HOME  Android SDK (비어 있으면 brew 의 android-commandlinetools)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "release-play: $*" >&2; exit 1; }

DOPPLER_PROJECT="${CURFEW_DOPPLER_PROJECT:-dev}"
DOPPLER_CONFIG="${CURFEW_DOPPLER_CONFIG:-dev}"

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi
if [[ -z "${ANDROID_HOME:-}" && -d /opt/homebrew/share/android-commandlinetools ]]; then
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
export PATH="${JAVA_HOME:-/nonexistent}/bin:$PATH"

[[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]] || fail "ANDROID_HOME 이 없다."
java -version 2>&1 | grep -Eq 'version "(1[7-9]|[2-9][0-9])' || fail "JDK 17 이상이 필요하다(JAVA_HOME=${JAVA_HOME:-없음})."
command -v doppler >/dev/null || fail "doppler CLI 가 없다."
# ~/.gradle/gradle.properties 의 값이 환경변수보다 앞서므로, 거기 다른 키가 있으면 그 키로 서명된다.
if grep -qs '^CURFEW_KEYSTORE' "${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"; then
  fail "~/.gradle/gradle.properties 에 CURFEW_KEYSTORE 가 있다. Play 용은 Doppler 의 업로드 키만 쓰므로 그 줄을 빼고 다시 실행한다."
fi

cd "$ROOT"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
APP_ID="$(sed -n 's/^ *applicationId = "\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1)"
VERSION_CODE="$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)"
VERSION_NAME="$(sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1)"
TARGET_SDK="$(sed -n 's/^ *targetSdk = \([0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)"
[[ -n "$APP_ID" && -n "$VERSION_CODE" && -n "$VERSION_NAME" && -n "$TARGET_SDK" ]] \
  || fail "app/build.gradle.kts 에서 applicationId·versionCode·versionName·targetSdk 를 읽지 못했다."

TEMP_KEYSTORE=""
OUT=""
cleanup() {
  # 확인을 다 거치지 않은 AAB 는 남기지 않는다(중간에 끊겨도).
  if [[ -n "$OUT" ]]; then rm -f "$OUT"; fi
  if [[ -n "$TEMP_KEYSTORE" ]]; then rm -f "$TEMP_KEYSTORE"; fi
}
trap cleanup EXIT
secret() {
  doppler secrets get "$1" --plain --project "$DOPPLER_PROJECT" --config "$DOPPLER_CONFIG" 2>/dev/null
}

# --- 업로드 키: 임시 파일로 되살린다. 비밀번호는 명령줄이 아니라 그 명령의 환경으로만 넘긴다(ps 에 보이지 않게).
TEMP_KEYSTORE="$(mktemp "${TMPDIR:-/tmp}/curfew-upload-XXXXXX")"
chmod 600 "$TEMP_KEYSTORE"
secret CURFEW_ANDROID_RELEASE_KEYSTORE_B64 | base64 --decode > "$TEMP_KEYSTORE" \
  || fail "Doppler($DOPPLER_PROJECT/$DOPPLER_CONFIG)에서 CURFEW_ANDROID_RELEASE_KEYSTORE_B64 를 꺼내지 못했다. doppler login 과 시크릿 이름을 확인한다."
[[ -s "$TEMP_KEYSTORE" ]] || fail "업로드 키스토어가 비어 있다."
# export 하지 않는 셸 변수다.
UPLOAD_STORE_PASSWORD="$(secret CURFEW_ANDROID_RELEASE_STORE_PASSWORD || true)"
UPLOAD_KEY_ALIAS="$(secret CURFEW_ANDROID_RELEASE_KEY_ALIAS || true)"
UPLOAD_KEY_PASSWORD="$(secret CURFEW_ANDROID_RELEASE_KEY_PASSWORD || true)"
[[ -n "$UPLOAD_STORE_PASSWORD" && -n "$UPLOAD_KEY_ALIAS" && -n "$UPLOAD_KEY_PASSWORD" ]] \
  || fail "업로드 키의 비밀번호·별칭 시크릿이 비어 있다."

KEYSTORE_LIST="$(CURFEW_UPLOAD_STORE_PASSWORD="$UPLOAD_STORE_PASSWORD" keytool -list -v -keystore "$TEMP_KEYSTORE" \
  -storepass:env CURFEW_UPLOAD_STORE_PASSWORD -alias "$UPLOAD_KEY_ALIAS" 2>/dev/null)" \
  || fail "업로드 키스토어를 열지 못했다(비밀번호·별칭 확인)."
KEYSTORE_SHA256="$(awk -F'SHA256: ' '/SHA256:/ {print $2; exit}' <<<"$KEYSTORE_LIST")"
[[ -n "$KEYSTORE_SHA256" ]] || fail "업로드 키의 인증서 지문을 읽지 못했다."

echo "== 커퓨 $VERSION_NAME ($APP_ID, versionCode $VERSION_CODE) bundleRelease"
# 데몬 없이 돌린다. 업로드 키 비밀번호가 든 환경을 빌드가 끝난 뒤까지 들고 있는 프로세스를 남기지 않는다.
# app/build.gradle.kts 는 이 네 값을 Gradle 프로퍼티(ORG_GRADLE_PROJECT_*)로 읽어 release 서명 설정을 만든다.
ORG_GRADLE_PROJECT_CURFEW_KEYSTORE="$TEMP_KEYSTORE" \
ORG_GRADLE_PROJECT_CURFEW_KEYSTORE_PASSWORD="$UPLOAD_STORE_PASSWORD" \
ORG_GRADLE_PROJECT_CURFEW_KEY_ALIAS="$UPLOAD_KEY_ALIAS" \
ORG_GRADLE_PROJECT_CURFEW_KEY_PASSWORD="$UPLOAD_KEY_PASSWORD" \
  ./gradlew --no-daemon clean :app:bundleRelease

BUILT="$ROOT/app/build/outputs/bundle/release/app-release.aab"
[[ -s "$BUILT" ]] || fail "AAB 가 만들어지지 않았다: $BUILT"
OUT_DIR="$ROOT/dist-release"
mkdir -p "$OUT_DIR"
FINAL="$OUT_DIR/curfew-$VERSION_NAME-play.aab"
rm -f "$FINAL"
# 확인하는 동안은 다른 이름으로 두고, 모두 통과해야 최종 이름으로 옮긴다.
OUT="$FINAL.unchecked"
cp "$BUILT" "$OUT"

# --- 확인: 하나라도 어긋나면 AAB 를 남기지 않는다.
reject() { rm -f "$OUT"; fail "$* (AAB 를 지웠다)"; }

# -strict 는 쓰지 않는다. AAB 는 META-INF 가 앞에 오지 않는 구조라 서명이 맞아도 경고로 실패한다.
# 대신 서명되지 않은 파일(jarsigner 가 0 으로 끝난다)을 "jar verified." 로 가린다.
SIGNATURE="$(jarsigner -verify "$OUT" 2>&1)" || reject "AAB 서명이 올바르지 않다."
grep -q '^jar verified\.' <<<"$SIGNATURE" || reject "AAB 가 서명되지 않았다."
CERT="$(keytool -printcert -jarfile "$OUT")" || reject "AAB 서명 인증서를 읽지 못했다."
SIGNED_SHA256="$(awk -F'SHA256: ' '/SHA256:/ {print $2; exit}' <<<"$CERT")"
SIGNED_SHA1="$(awk -F'SHA1: ' '/SHA1:/ {print $2; exit}' <<<"$CERT")"
[[ "$SIGNED_SHA256" == "$KEYSTORE_SHA256" ]] || reject "AAB 의 서명 인증서가 업로드 키와 다르다: $SIGNED_SHA256"

# 버전과 targetSdk 는 Gradle 이 합친 XML 매니페스트에서 본다. AAB 안의 것은 protobuf 라 숫자를 grep 할 수 없다.
MERGED="$(find "$ROOT/app/build/intermediates" -path '*release*' -name AndroidManifest.xml -path '*merged_manifest*' | head -1)"
[[ -n "$MERGED" ]] || reject "합친 release 매니페스트를 찾지 못했다."
grep -q "package=\"$APP_ID\"" "$MERGED" || reject "매니페스트의 패키지가 $APP_ID 가 아니다."
grep -q "android:versionCode=\"$VERSION_CODE\"" "$MERGED" || reject "매니페스트의 versionCode 가 $VERSION_CODE 가 아니다."
grep -q "android:targetSdkVersion=\"$TARGET_SDK\"" "$MERGED" || reject "매니페스트의 targetSdkVersion 이 $TARGET_SDK 가 아니다."

# AAB 의 실제 매니페스트(protobuf)에서 문자열만 뽑아 본다.
# unzip 뒤에는 grep -q 를 쓰지 않는다. grep 이 먼저 끝나면 unzip 이 SIGPIPE 로 실패해 pipefail 이 결과를 뒤집는다.
MANIFEST_PROTO="$(unzip -p "$OUT" base/manifest/AndroidManifest.xml | LC_ALL=C tr -c '[:print:]' '\n')" \
  || reject "AAB 매니페스트를 읽지 못했다."
grep -qF "$APP_ID" <<<"$MANIFEST_PROTO" || reject "AAB 매니페스트에 패키지 $APP_ID 가 없다."
grep -q 'android.permission.PACKAGE_USAGE_STATS' <<<"$MANIFEST_PROTO" || reject "AAB 매니페스트에 사용량 접근 권한이 없다."
# 디버그 빌드는 debuggable 속성을 넣고, 릴리스 빌드는 그 속성이 아예 없다.
if grep -q 'debuggable' <<<"$MANIFEST_PROTO"; then reject "디버그 가능한 AAB 다."; fi
# Play 데이터 보안 양식과 개인정보처리방침에 "기기 밖으로 보내지 않는다"고 신고했다. 의존성이 인터넷 권한을
# 끌고 들어오면 그 신고가 거짓이 되므로 멈춘다.
if grep -q 'android.permission.INTERNET' <<<"$MANIFEST_PROTO"; then reject "AAB 에 인터넷 권한이 들어왔다."; fi
if grep -q 'permission.AD_ID' <<<"$MANIFEST_PROTO"; then reject "AAB 에 광고 ID 권한(AD_ID)이 있다."; fi

mv "$OUT" "$FINAL"
OUT=""
echo
echo "AAB: $FINAL"
echo "패키지: $APP_ID  versionCode: $VERSION_CODE  versionName: $VERSION_NAME  targetSdk: $TARGET_SDK"
echo "업로드 인증서 SHA-256: $SIGNED_SHA256"
echo "업로드 인증서 SHA-1:   $SIGNED_SHA1"
echo "AAB SHA-256: $(shasum -a 256 "$FINAL" | awk '{print $1}')"
echo "Play 콘솔 → 테스트 → 내부 테스트 에서 이 AAB 를 올린다. 이 스크립트는 어디에도 올리지 않는다."
