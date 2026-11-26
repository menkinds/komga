---
type: "handoff"
project: "komga"
date: "2026-11-26"
session: 1
summary: "폴더 이름 변경+권 수 증가 시 옛 시리즈 Unavailable 잔류 수정(재연결 규칙 완화, 빈 휴지통 시리즈 삭제), PR #8 머지"
tags: ["handoff", "komga"]
hub: "[[gbrain-notes/bossard/project-logs/komga/README_CLAUDE.md|README_CLAUDE]]"
pair: "[[komga-2026-11-26-1-case]]"
prev: ""
next: ""
gbrain: "전송됨"
---

# 2026-11-26 — 폴더 이름 변경+권 수 증가 시 옛 시리즈 Unavailable 잔류 수정(재연결 규칙 완화, 빈 휴지통 시리즈 삭제), PR #8 머지

## 한 일
- 옛 기능 확인: 폴더 이름 변경 시 시리즈 재연결(`d0506cb`, `MovedSeriesRelink`, `SeriesNameNormalizer`)과 라이브러리 스캔 흐름(`LibraryContentLifecycle.scanRootFolder`)을 사용자에게 설명.
- 버그: 업데이트 뒤에도 옛 시리즈 `今日の現場と終演ごはん`이 Unavailable로 남음(사용자 스크린샷, 새 시리즈는 `[えきあ] 今日の現場と終演ごはん 第01~02巻`).
- 원인(테스트로 확인): 해시 재연결 규칙 `mostly`가 새 폴더 쪽에도 과반 일치를 요구. 권 수가 두 배 이상 늘면(1→2, 3→8) 실패 → 새 시리즈 생성, `reuseDeletedBooks`가 옛 책을 옮김 → 옛 시리즈는 0권으로 휴지통에 남음. 10-30 자동 삭제는 `BOOK_TAKEOVER` 표시가 있어야 해서 못 지움. 이름 매칭도 `第..巻`, `[작가]`를 안 지우고, 옛 책에 해시가 있으면 아예 안 씀.
- 수정(PR #8, master `489767a`):
  - A: `MovedSeriesRelink.mostly(matched, deletedCount)` = 휴지통 시리즈 책의 과반만 보면 재연결. 호출 3곳 모두 적용.
  - B: `TakenOverSeriesCleanup.afterScan()`에 `deleteEmptiedSeries()` 추가. 책이 0권인 휴지통 시리즈를 `seriesLifecycle.deleteMany`로 삭제.
  - 테스트 3개 추가(3→8권, 스크린샷 사례, 두 시리즈 병합). `FORK_NOTES.md` 갱신.
- 검증: 관련 54개 통과, 전체 1014개 중 20개 실패 → 모두 로케일 문제(`InvalidPathException`), UTF-8로 해당 205개 재실행 통과. ktlint 통과.

## 결정과 이유
- A+B 둘 다 적용(사용자 결정). 표시는 `[작가] 제목 권수`(폴더 이름) 유지 — 재연결 시 제목은 잠겨 있지 않으면 새 폴더 이름을 따름.
- 동점(두 휴지통 시리즈가 같은 수로 맞음)이면 재연결 안 함 → B가 빈 시리즈를 치움.
- 바뀐 동작: 옛 시리즈가 그것을 포함한 큰 폴더(전권 묶음 등)에 붙을 수 있음. id·기록이 유지되므로 허용.
- 디스크 폴더·파일은 건드리지 않음(DB만 변경) — 사용자에게 확인해 줌.

## 막힌 점
- Maven Central 429로 의존성 다운로드가 여러 번 실패. 2분 간격 재시도로 해결.
- 로케일 미설정으로 비ASCII 경로 테스트 실패. `LANG=C.UTF-8 LC_ALL=C.UTF-8`로 해결.
- 사용자가 영어 응답을 지적함 → 이 사용자에게는 한국어로만 답할 것.
- 처음에 "두 배 넘게 늘면 실패"라고 했으나 실제는 "딱 두 배여도 실패". 정정함.

## 다음에 할 일
- NAS 배포 후 스캔, Unavailable 사라짐과 로그(`Relinked series`, `Deleted emptied trashed series:`) 확인.
- `SeriesNameNormalizer`에 `第..巻`, `[작가]` 제거를 넣을지 사용자 결정 대기.

## 연결
- 허브: [[gbrain-notes/bossard/project-logs/komga/README_CLAUDE.md|README_CLAUDE]]
- 짝: [[komga-2026-11-26-1-case]]
- 이전:
- 다음:
