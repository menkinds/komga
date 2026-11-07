---
type: "handoff"
project: "komga"
date: "2026-11-07"
session: 1
summary: "웹 UI 라이브러리 숨김(암호)·시리즈 커버 읽기 버튼, PR #6 머지, 포크 워크플로 재활성화 후 도커 빌드"
tags: ["handoff", "komga"]
hub: "[[gbrain-notes/bossard/project-logs/komga/README_CLAUDE.md|README_CLAUDE]]"
pair: "[[komga-2026-11-07-1-case]]"
prev: ""
next: ""
gbrain: "전송됨"
---

# 2026-11-07 — 웹 UI 라이브러리 숨김(암호)·시리즈 커버 읽기 버튼, PR #6 머지, 포크 워크플로 재활성화 후 도커 빌드

## 한 일
- webui 라이브러리 숨김: 라이브러리 ⋮ 메뉴 "숨기기"(첫 숨김 때 암호 설정), Libraries ⋮ 메뉴 "숨긴 라이브러리 해제"(암호 → 고른 것만 해제). 숨긴 라이브러리는 드로어·홈·전체 라이브러리·필터 목록·재정렬·검색에서 빠지고, URL로 열면 home으로 이동.
- 저장은 사용자 client setting(`webui.libraries`의 `hidden`, `webui.hidden_libraries_lock` = salt+SHA-256). 서버 코드 변경 없음. http에서 crypto.subtle이 없어 순수 JS `functions/sha256.ts`를 넣고 node crypto와 대조함(7개 입력 일치).
- 시리즈 화면 커버: 마우스를 올리면 책 카드와 같은 빨간 FAB, 커버 클릭도 바로 읽기. 대상은 진행 중 → 첫 안 읽은 책 → 첫 권(READY·삭제 안 된 책).
- PR menkinds/komga#6 생성·머지(2567b4cc). docker-publish 수동 실행 성공(run 42343647410).

## 결정과 이유
- 숨김은 기존 pinned/unpinned getter에 얹음: "전체 라이브러리" 화면들이 이미 pinned id 목록으로 조회하므로 변경이 가장 작음.
- 숨김은 웹 UI에만 적용(API·OPDS·Komelia 영향 없음). 서버 권한으로 숨기면 Komelia의 19+ 잠금과 충돌하기 때문.
- 커버는 탭하면 바로 읽기(사용자 선택 "바로 읽기"). 마우스 올림/길게 누름에는 FAB 표시.

## 막힌 점
- webpack 타입체커(fork-ts-checker)가 8GB 메모리에서도 OOM. `tsc --noEmit`은 바뀐 .ts에 오류 없음, .vue 타입 검사는 못 함.
- 머지 push에 빌드가 안 돌았음: GitHub가 포크 워크플로를 꺼 둠("Workflows on this fork have been disabled"). Settings > Actions 허용과는 별개라 껐다 켜도 소용없었고, Actions 탭의 re-enable 버튼으로 해결(사용자가 누름).
- 재시도 루프와 수동 실행이 겹쳐 빌드가 2개 생김. 취소 명령은 세션 권한에서 막혀 사용자가 직접 취소.

## 다음에 할 일
- NAS에서 `ghcr.io/menkinds/komga:latest` pull·재시작 후 숨김/해제, 검색 제외, 시리즈 커버 버튼을 브라우저와 폰에서 확인.
- re-enable로 켜진 업스트림 워크플로(Chromatic, Discord 알림, 정기 작업) 정리 여부 결정.

## 연결
- 허브: [[gbrain-notes/bossard/project-logs/komga/README_CLAUDE.md|README_CLAUDE]]
- 짝: [[komga-2026-11-07-1-case]]
- 이전:
- 다음:
