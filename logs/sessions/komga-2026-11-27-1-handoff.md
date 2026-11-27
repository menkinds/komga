---
type: "handoff"
project: "komga"
date: "2026-11-27"
session: 1
summary: "업데이트 후 Unavailable 해소 확인, 제목만 보이는 시리즈는 cBossard komga_sync의 제목 잠금 때문으로 확인(그대로 두기로 함)"
tags: ["handoff", "komga"]
hub: "[[gbrain-notes/bossard/project-logs/komga/README_CLAUDE.md|README_CLAUDE]]"
pair: "[[komga-2026-11-27-1-case]]"
prev: "[[komga-2026-11-26-1-handoff]]"
next: ""
gbrain: "전송됨"
---

# 2026-11-27 — 업데이트 후 Unavailable 해소 확인, 제목만 보이는 시리즈는 cBossard komga_sync의 제목 잠금 때문으로 확인(그대로 두기로 함)

## 한 일
- 배포 확인: 사용자가 업데이트 후에도 남아 있다고 해서 확인. PR #8 이미지 빌드(Actions `Publish Docker image` run 45908926430)가 머지 6분 뒤라 아직 진행 중이었음. 이미지 다시 받기 → 재생성 → 스캔 순서를 안내.
- 문서 PR #9(첫 /save 인계·사례 기록) 생성·병합 → master `ac11656`.
- 이후 사용자 스크린샷: `今日の現場と終演ごはん` Unavailable 항목이 사라짐(3850→3849). NAS 로그는 확인 안 함.
- 표시 차이 조사: 어떤 시리즈는 `[작가] 제목 第..巻`, 어떤 것은 제목만(`球場のシャーロット`).
  - 처음 추정(ComicInfo `<Series>` 가져오기)은 사용자가 "이미 메타데이터 꺼져 있다"고 해서 틀림.
  - 코드 확인: 시리즈 제목은 만들 때 폴더 이름(`SeriesLifecycle.kt:158`), 이후엔 가져오기(ComicInfo·EPUB·Mylar)만 덮어씀. 꺼도 되돌리지 않음. 서버는 잠금을 스스로 걸지 않음. 웹 UI 편집(`EditSeriesDialog.vue` `@change="form.titleLock = true"`)이나 외부 API만 잠금.
  - 사용자 편집 화면 캡처: Title·Sort title·Status(Ended)·Total book count(3)만 잠김.
  - GBrain 검색(`cBossard komga fetch`, `komga 메타데이터 잠금`) → cBossard README_CLAUDE: `app/komga_sync.py`가 Cmoa 정보로 title·titleSort·status·genres·완결 시 totalBookCount를 `...Lock: true`로 PATCH(2026-10-30부터, Cmoa 자동 채우기 뒤 자동 실행, 미리 보기 대상 3245 시리즈). 잠긴 칸과 정확히 일치 → 원인으로 판단.
- 사용자 결정: 그대로 둠. 재연결·자동 삭제·별점·검색에는 영향 없음을 설명.

## 결정과 이유
- 제목 잠금은 그대로 둔다(사용자). 화면 제목만 다르고 포크 기능(해시·폴더 이름 기반)에는 영향 없음.
- 나중에 바꾼다면 cBossard 쪽에서: title 전송 중지가 먼저(안 하면 다음 동기화 때 다시 덮어씀), 그다음 `komga_sent`와 같은 제목만 폴더 이름으로 되돌림.

## 막힌 점
- ComicInfo 원인 추정이 틀렸음. 설정 상태를 먼저 묻지 않고 추정했던 것이 원인.
- 또 영어로 답한 적이 있어 사용자가 지적함. 이 사용자에게는 항상 한국어.
- Komga DB(`TITLE_LOCK` 개수)는 직접 보지 못함. 사용자가 "엄청 많은 것 같다"고만 함.

## 다음에 할 일
- 없음(사용자가 그대로 두기로 함). 원하면 cBossard 저장소를 붙여 제목 전송 중지 + 되돌리기.

## 연결
- 허브: [[gbrain-notes/bossard/project-logs/komga/README_CLAUDE.md|README_CLAUDE]]
- 짝: [[komga-2026-11-27-1-case]]
- 이전: [[komga-2026-11-26-1-handoff]]
- 다음:
