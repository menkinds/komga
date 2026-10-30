# README_CLAUDE — 인계서

마지막 갱신: 2026-10-30 일일 /save (KST), 브랜치 `claude/hook-20261030-150234-b30cb8`
현재 상태: master = `dd749a91` (PR #4 병합, 2026-10-30 22:26 KST). 그 아래 `801ff350` = upstream 1.29.2 병합(PR #3).

## 2026-10-30 일일 요약
- Claude 작업 1건: job `20261030-120026-8a2d48` (Opus 5.5, 완료). PR #4 병합됨. 커밋 `83c1692b` (21:28 KST).
- PR #3 (upstream 1.29.2 병합, `03184a7c`)은 오늘 병합됨. Claude 작업인지는 미확인.
- 코드 변경은 PR #4뿐: 9개 파일, +601/-5 (서비스 1, 테스트 1, 마이그레이션 1, hook 3, 문서 3).
- 열린 항목과 다음 단계는 아래 "남은 일" 참고.

## 저장소 요약
- gotson/komga 포크(menkinds/komga). 포크 기능 설명은 `FORK_NOTES.md`.
- 포크 전용 코드: `komga/src/main/kotlin/org/gotson/komga/domain/service/` 의
  `MovedSeriesRelink.kt`, `HashArchive.kt`, `SeriesNameNormalizer.kt`, `TakenOverSeriesCleanup.kt`.
- 포크 마이그레이션: `komga/src/flyway/resources/db/migration/sqlite/V20261016143000__hash_archive.sql`,
  `V20261030120000__book_takeover.sql`.
- 업스트림 파일의 포크 hook: `BookLifecycle.kt`, `LibraryContentLifecycle.kt`, `SeriesLifecycle.kt`.

## 이번 작업 (2026-10-30): 재연결 뒤 옛 시리즈 자동 삭제
요청: 해시로 기록이 옮겨진 옛(Unavailable) 시리즈를 "Empty trash" 없이 지우기.

읽은 재연결 경로:
- `LibraryContentLifecycle.tryRestoreSeries` / `tryRestoreBooks` (업스트림, 모든 라이브러리의 휴지통 책을 크기+해시로 찾음)
- `MovedSeriesRelink.tryRelink` / `reuseDeletedBooks` / `onBookHashed` (포크, 같은 라이브러리만 경로 재연결)
- `HashArchive.restore` (포크, 해시 아카이브에서 복원. 옛 행은 지우지 않음)

원인(테스트로 확인):
- 순서 A(새 라이브러리 먼저): 새 스캔 때 옛 책이 아직 살아 있어 복원 안 됨. 옛 스캔은 휴지통에 넣기만 함. → 옛 시리즈가 남음.
- 순서 B(옛 라이브러리 먼저)이고 파일 구성이 같으면: 업스트림 `tryRestoreSeries`가 이미 옛 시리즈를 지움.
- 실제 운영에서 남은 이유는 순서 A 또는 해시 계산 시점 → `onBookHashed`의 `HashArchive.restore`가 기록만 옮긴 경우로 추정.

바꾼 것:
- 새 테이블 `BOOK_TAKEOVER(OLD_BOOK_ID PK, OLD_SERIES_ID, NEW_BOOK_ID, CREATED_DATE)`. HASH_ARCHIVE 스키마는 그대로.
- `TakenOverSeriesCleanup` (새 파일)
  - `markTakenOver(old, new)`: 이어받음 표시.
  - `markRestored(live, snapshot)`: `HashArchive.restore` 성공 뒤 그 스냅숏의 옛 휴지통 책 표시.
  - `afterScan()`: 스캔 끝에 실행.
    1. 휴지통 시리즈의 해시 있는 미표시 책마다, 같은 크기·같은 해시의 살아 있는 책이 정확히 1개인지 찾음(해시 없으면 계산·저장).
    2. 그 책이 "새것"(읽기 진행 없음, 메타데이터 잠금 없음)이면 `HashArchive.restore` 후 표시.
       아니면 진행·메타데이터(summary, tags, authors, isbn, releaseDate)가 이미 같을 때만 표시(이미 복원된 기존 휴지통 대응).
    3. 휴지통 시리즈의 남은 책이 모두 표시되고, 새 책이 살아 있고, 사용자별 진행이 새 책에 있고, 옛 시리즈의 컬렉션이 새 시리즈를 포함하면 `SeriesLifecycle.deleteMany` (Empty trash와 같은 경로)로 삭제. INFO 로그 1줄. 새 책을 다시 아카이브.
    4. 없어진 시리즈의 표시 행 정리.
- hook: `LibraryContentLifecycle` (생성자 1줄, `afterScan()` 1줄, `tryRestoreBooks`에서 `markTakenOver` 1줄), `MovedSeriesRelink.onBookHashed` 1줄, `HashArchive.restore`가 적용한 스냅숏을 반환.
- 해시 작업(onBookHashed)에서는 표시만 하고 삭제하지 않음. 동시 스캔과 충돌을 피하려는 선택. 삭제는 다음 스캔(어느 라이브러리든) 끝에 일어남.

## 확인 결과
- 새 테스트 `TakenOverSeriesCleanupTest` 6개 통과(순서 A, 순서 B, 순서 B+권 추가, 같은 라이브러리 이름 변경, 부분 일치, 진짜 삭제).
- hook(`afterScan`)을 끄면 3개 실패(순서 A, 권 추가, 부분 일치-진행 확인). 순서 B 같은 구성과 이름 변경은 기존 코드로도 통과.
- `./gradlew :komga:ktlintCheck :komga:test`: BUILD SUCCESSFUL, 1008 tests, 0 failures, 0 errors.

## 재현 방법
러너에 Java가 없음. 작업 폴더 안 `.claude-tools/`(git exclude, 커밋 안 함)에 Temurin JDK 21을 받아 실행:
```bash
export JAVA_HOME=$PWD/.claude-tools/jdk-21.0.13+9 GRADLE_USER_HOME=$PWD/.claude-tools/gradle-home
./gradlew :komga:test --tests '*TakenOverSeriesCleanupTest' --no-daemon
```
첫 빌드는 의존성 다운로드로 약 9분.

## 남은 일 / 사람이 정할 것
- 운영 DB에 이미 남은 "グッバイ宣言 (完)" 시리즈: 새 책에 진행·메타데이터가 같으면 다음 스캔에서 지워질 것으로 추정(미확인, 운영 데이터로 시험 안 함). 컬렉션에 들어 있었는데 새 시리즈가 그 컬렉션에 없으면 남음.
- `tryRestoreBooks`(업스트림) 경로는 컬렉션을 옮기지 않으므로, 옛 시리즈가 컬렉션에 있으면 자동 삭제하지 않음(안전 쪽 선택).
- 배포 후 로그에서 `Deleted taken-over series:` 줄을 확인할 것.
