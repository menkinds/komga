# AI_CASE_LOG

## 2026-10-30 — 해시 재연결 뒤 옛 시리즈 자동 삭제 (claude/hook-20261030-120026-8a2d48)

- 도구: Claude Code (claude-runner 헤드리스, Opus 5.5)
- 결과: PR #4 병합 (master `dd749a91`, 2026-10-30 22:26 KST). 커밋 `83c1692b`, 9개 파일 +601/-5.
- 문제: 라이브러리 간 이동 후 읽기 진행은 새 시리즈로 옮겨졌지만, 옛 시리즈와 3권이 옛 라이브러리에 "Unavailable"로 남음. "휴지통 비우기"를 눌러야만 사라짐.
- 원인: 새 라이브러리를 먼저 스캔하면(순서 A) 옛 책이 아직 살아 있어 복원이 막힘. 옛 라이브러리 스캔은 휴지통에 넣기만 하고 재연결을 다시 하지 않음. 해시 복원(`HashArchive.restore`)은 옛 행을 지우지 않음.
- 한 일: `BOOK_TAKEOVER` 표시 테이블(새 Flyway 마이그레이션), 포크 전용 서비스 `TakenOverSeriesCleanup`, 업스트림 파일에 hook 3줄.
- 검증: 새 테스트 6개 통과. hook을 끄면 3개 실패(순서 A, 권 추가, 부분 일치)함을 확인. 전체 `:komga:test` 결과는 README_CLAUDE.md 참고.
- 배운 점: 순서 B(옛 라이브러리 먼저)에서 파일 구성이 같으면 업스트림 `tryRestoreSeries`가 이미 옛 시리즈를 지움. 실제 문제는 순서 A와 해시 계산 시점.
- 환경: 러너에 Java가 없어 `.claude-tools/`(커밋 안 함)에 Temurin JDK 21을 받아 실행.

## 2026-11-07 — 웹 UI 라이브러리 숨김(암호), 시리즈 커버 읽기 버튼 #2026-11
- 도구: Claude Code (Opus 5.5, 클라우드 세션). Komelia 작업과 같은 세션.
- 결과: PR menkinds/komga#6 병합(master `2567b4cc`). docker-publish 수동 실행 성공 → `ghcr.io/menkinds/komga:latest` 갱신.
- 한 일: 라이브러리 ⋮ 메뉴 숨기기(첫 숨김 때 암호), Libraries ⋮ 메뉴 해제(암호). 숨긴 라이브러리는 메뉴·홈·전체 화면·검색에서 빠짐. 시리즈 커버에 빨간 읽기 버튼, 커버 클릭으로 바로 읽기.
- 검증: eslint 통과, 웹 번들 빌드 성공, SHA-256 구현을 node crypto와 대조. .vue 타입 검사는 OOM으로 못 함. 실기기 미확인.
- 배운 점: 포크 저장소는 GitHub가 워크플로를 따로 꺼 둠. Settings > Actions 허용과 별개로 Actions 탭의 re-enable 버튼을 눌러야 빌드가 돎.
- 실제 프로젝트/제안서/납품물에 적용되었는가: 예 (menkinds/komga)
- 적용됐다면 프로젝트명 또는 맥락: menkinds/komga
- 결과물: 최근 커밋: 2567b4c Merge pull request #6 from menkinds/claude/friendly-babbage-2yplaz; b5a560c webui: hide libraries behind a password, read button on series cover; a7acf22 Merge pull request #5 from menkinds/claude/hook-20261030-150234-b30cb8; c629950 docs: 2026-10-30 일일 /save, README_CLAUDE와 AI_CASE_LOG를 master dd749a91에 맞춤; dd749a9 Merge pull request #4 from menkinds/claude/hook-20261030-120026-8a2d48; 83c1692 feat(scan): delete trashed series once every book's history was reattached by hash, so moved series no longer need Empty trash; 801ff35 Merge pull request #3 from menkinds/sync/upstream-1.29.2; 03184a7 merge upstream 1.29.2 / 변경 파일: README_CLAUDE.md

## 세션 기록

- [[komga-2026-11-07-1-case]] — 웹 UI 라이브러리 숨김(암호)·시리즈 커버 읽기 버튼, PR #6 머지, 포크 워크플로 재활성화 후 도커 빌드 #2026-11
