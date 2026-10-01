#!/usr/bin/env bash
# Google Play 업로드 키를 새로 만들어 Doppler 에 저장한다. scripts/release-play.sh 가 빌드 때 꺼내 쓴다.
# 키스토어는 디스크에 남기지 않고, 비밀번호와 키스토어 값은 화면에 출력하지 않는다. 인증서 지문만 보여 준다.
#
#   scripts/doppler-store-upload-key.sh
#
# 저장 이름은 CURFEW_ANDROID_RELEASE_{KEYSTORE_B64,STORE_PASSWORD,KEY_ALIAS,KEY_PASSWORD} 이다.
# 이미 있으면 덮어쓰지 않는다(FORCE=1 이면 덮어쓴다). 덮어쓴 키는 되찾을 수 없고, Play 콘솔에서
# 업로드 키 재설정을 요청해야 한다.
# 위치는 CURFEW_DOPPLER_PROJECT / CURFEW_DOPPLER_CONFIG (기본 dev / dev).
set -euo pipefail

PROJECT="${CURFEW_DOPPLER_PROJECT:-dev}"
CONFIG="${CURFEW_DOPPLER_CONFIG:-dev}"
PREFIX=CURFEW_ANDROID_RELEASE
fail() { echo "doppler-store-upload-key: $*" >&2; exit 1; }

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi
export PATH="${JAVA_HOME:-/nonexistent}/bin:$PATH"
command -v doppler >/dev/null || fail "doppler CLI 가 없다."
command -v keytool >/dev/null || fail "keytool(JDK)이 없다. JAVA_HOME 을 JDK 21 로 잡아 준다."

# 값은 표준입력으로 넘긴다. 명령줄과 화면에 남지 않는다.
put() { doppler secrets set "$1" --project "$PROJECT" --config "$CONFIG" --silent >/dev/null; }
exists() { doppler secrets get "$1" --plain --project "$PROJECT" --config "$CONFIG" >/dev/null 2>&1; }

if [[ "${FORCE:-0}" != "1" ]] && exists "${PREFIX}_KEYSTORE_B64"; then
  fail "${PREFIX}_* 가 이미 $PROJECT/$CONFIG 에 있다. 덮어쓰려면 FORCE=1 (이전 키는 되찾을 수 없다)."
fi

tmp="$(mktemp "${TMPDIR:-/tmp}/curfew-upload-XXXXXX")"
trap 'rm -f "$tmp"' EXIT
rm -f "$tmp" # keytool 은 없는 파일에만 새로 만든다.
storepass="$(openssl rand -base64 24)"
alias_name="curfew-upload"
CURFEW_TMP_STOREPASS="$storepass" keytool -genkeypair -keystore "$tmp" -storetype PKCS12 \
  -storepass:env CURFEW_TMP_STOREPASS -keypass:env CURFEW_TMP_STOREPASS \
  -alias "$alias_name" -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=curfew, O=thsvkd, C=KR" >/dev/null 2>&1 || fail "새 키를 만들지 못했다."

sha="$(CURFEW_TMP_STOREPASS="$storepass" keytool -list -v -keystore "$tmp" -storepass:env CURFEW_TMP_STOREPASS \
  -alias "$alias_name" 2>/dev/null | awk -F'SHA256: ' '/SHA256:/ {print $2; exit}')"
[[ -n "$sha" ]] || fail "만든 키스토어를 열지 못했다."

base64 -i "$tmp" | tr -d '\n' | put "${PREFIX}_KEYSTORE_B64"
printf '%s' "$storepass" | put "${PREFIX}_STORE_PASSWORD"
printf '%s' "$alias_name" | put "${PREFIX}_KEY_ALIAS"
# PKCS12 는 키 비밀번호가 저장소 비밀번호와 같다.
printf '%s' "$storepass" | put "${PREFIX}_KEY_PASSWORD"
echo "저장했다: ${PREFIX}_{KEYSTORE_B64,STORE_PASSWORD,KEY_ALIAS,KEY_PASSWORD} → $PROJECT/$CONFIG"
echo "업로드 인증서 SHA-256: $sha"
