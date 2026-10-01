# Play Store 자산

| 파일 | 규격 |
|---|---|
| `icon-512.png` | 512x512, 알파 없음. 런처 어댑티브 아이콘의 보이는 영역(108dp 캔버스 중앙 72dp) 그대로. |
| `feature-graphic.png` | 1024x500, 알파 없음 |
| `screenshots/{phone,tablet7,tablet10}-0N.png` | 1080x1920, 알파 없음. phone 455dp(밀도 380), 7인치 540dp(320), 10인치 810dp(213) |

## 재현

1. 에뮬레이터에 디버그 빌드 설치, `adb shell appops set com.thsvkd.curfew GET_USAGE_STATS allow`.
2. `adb exec-out run-as com.thsvkd.curfew cat databases/curfew.db > curfew.db` (앱을 force-stop한 뒤),
   `python3 seed_db.py curfew.db`로 최근 7일치 사용 기록을 채우고, 다시 `databases/curfew.db`로 밀어 넣는다
   (`-wal`, `-shm`은 지운다). 수집 커서를 지금으로 두므로 수집 작업이 기록을 덮어쓰지 않는다.
3. `SER=emulator-5558 PY=python3 ./capture.sh` — `wm size/density`로 화면을 바꾸고 데모 모드로 상태 표시줄을
   정리한 뒤 캡처, RGB로 변환하고 1080x1920을 검증한다. 끝나면 `wm size reset; wm density reset`.
4. `python3 make_graphics.py` (Pillow, cairosvg 필요) — 아이콘과 그래픽 이미지.
