---
type: "handoff"
project: "komga"
date: "2026-11-28"
session: 1
summary: "웹 리더가 책을 열 때 띄우던 읽기 방향 알림 제거, PR #10 머지"
tags: ["handoff", "komga"]
hub: "[[gbrain-notes/bossard/project-logs/komga/README_CLAUDE.md|README_CLAUDE]]"
pair: "[[komga-2026-11-28-1-case]]"
prev: "[[komga-2026-11-27-1-handoff]]"
next: ""
gbrain: "전송됨"
---

# 2026-11-28 — 웹 리더가 책을 열 때 띄우던 읽기 방향 알림 제거, PR #10 머지

## 한 일
- 사용자의 "책 열 때마다 팝업 안 뜨게" 요청에 웹 리더(`DivinaReader.vue`)의 `sendNotificationReadingDirection` 호출 두 곳을 제거. 시리즈 메타데이터 읽기 방향 적용은 유지, 읽기 중 직접 방향을 바꿀 때 알림도 유지.
- eslint 통과, `npm run build` 번들 완성(타입체커 OOM은 기존 문제).
- 이후 Komelia를 붙여 보니 사용자가 본 팝업은 Komelia 것이었음. 웹 쪽 변경은 사용자 결정("머지해 다")으로 유지.
- PR #10 병합(master `dd5e22b`, 11-27 /save 문서 커밋 포함) → docker-publish #12 성공.

## 결정과 이유
- 웹 리더도 같은 동작이라 함께 제거(사용자 결정).

## 막힌 점
- 출처를 확인하기 전에 Komga 웹이라고 추정하고 고침. 팝업 문구로 출처부터 확인했어야 함.

## 다음에 할 일
- NAS 이미지 다시 받고 브라우저에서 확인(미확인).

## 연결
- 허브: [[gbrain-notes/bossard/project-logs/komga/README_CLAUDE.md|README_CLAUDE]]
- 짝: [[komga-2026-11-28-1-case]]
- 이전: [[komga-2026-11-27-1-handoff]]
- 다음:
