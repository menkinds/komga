# Fork notes

시리즈 폴더나 그 상위 폴더 이름이 바뀌면 Komga는 시리즈를 폴더 URL로만 찾기 때문에 기존 시리즈를 휴지통으로 보내고 새 시리즈를 만듭니다. 파일 해시는 스캔이 끝난 뒤 분석 작업에서 계산되고, 업스트림 복원은 휴지통에 해시가 이미 있고 권 수와 파일 크기가 완전히 같을 때만 새 행으로 진행 정보를 복사합니다. 휴지통을 비우면 그 복사본마저 사라집니다.

이 포크는 스캔 때 다음 순서로 기존 시리즈를 다시 연결합니다.

- 새 폴더의 파일 해시가 휴지통 시리즈와 과반으로 같으면 그 시리즈의 경로를 새 폴더로 바꿉니다. 읽기 진행, 시리즈 메타데이터, 컬렉션, 읽기 목록은 같은 id에 남습니다.
- 해시가 아직 없으면 기호, 공백, `(완)` / `(完)` / `완결`, `제01~09권` 같은 권 표시를 뺀 이름이 완전히 같을 때만 연결합니다. 이름이 포함 관계인 것만으로는 합치지 않습니다.
- 해시는 스캔 후에 계산될 수 있으므로, 해시가 저장되는 시점에도 같은 연결을 다시 시도합니다.
- 휴지통을 비우거나 책을 완전히 지워도 `HASH_ARCHIVE` 테이블에 해시별 읽기 진행, 책 메타데이터, 시리즈 메타데이터, 컬렉션, 읽기 목록 소속이 남습니다. 같은 해시의 책이 다시 들어오고 살아있는 다른 책이 없으면 그 값을 복원합니다.

라이브러리 설정의 **Compute hash for files** 가 켜져 있어야 휴지통을 비운 뒤에도 복원됩니다. 해시가 생기기 전에 휴지통을 비우면 아카이브에 넣을 키가 없습니다.

## Synology Container Manager

UI가 필요하면 웹 프론트를 먼저 만든 뒤 jar에 넣습니다. JDK 21과 Node가 있는 빌드 머신에서:

```bash
cd komga-webui && npm ci && npm run build && cd ..
cd next-ui && npm ci && npm run build && cd ..
./gradlew :komga:webuiCopyIndex :komga:nextuiCopyIndex :komga:bootJar
```

API만 쓰면 `./gradlew :komga:bootJar` 만 실행해도 됩니다. jar는 `komga/build/libs/komga-*.jar` 입니다.

그 jar와 아래 Dockerfile을 같은 폴더에 두고, Container Manager에서 프로젝트로 빌드합니다. 이미지 안에서는 권한 변경을 하지 않습니다. 공유 폴더 권한은 DSM 파일 스테이션에서 컨테이너가 사용하는 계정에 맞게 미리 줍니다.

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY komga-*.jar /app/komga.jar
ENV KOMGA_CONFIGDIR=/config
EXPOSE 25600
ENTRYPOINT ["java", "-jar", "/app/komga.jar", "--spring.config.additional-location=file:/config/"]
```

컨테이너 설정 예:

- 포트 `25600`을 호스트 포트로 연결
- 호스트의 설정 폴더를 `/config`에 마운트
- 만화 폴더를 읽을 수 있는 경로로 마운트한 뒤, Komga 라이브러리 루트로 그 경로를 지정
- 재시작 정책은 원하는 값으로

브라우저에서는 `http://<nas>:25600` 으로 엽니다.
