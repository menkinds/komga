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
