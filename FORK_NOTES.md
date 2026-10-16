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

## ghcr.io/menkinds/komga

기본 브랜치에 푸시되거나 Actions에서 수동 실행되면 이 포크의 이미지를 `ghcr.io/menkinds/komga` 로 올립니다. 태그는 `latest` 와 그 커밋 SHA 입니다. 빌드는 업스트림 릴리스와 같이 `komga/docker/Dockerfile.tpl` 과 JReleaser, QEMU, Buildx 를 씁니다. 플랫폼은 `linux/amd64`, `linux/arm64/v8`, `linux/arm/v7` 입니다. DS723+ 는 AMD Ryzen 이므로 `linux/amd64` 이미지를 받습니다.

### 데이터베이스를 먼저 복사

컨테이너를 멈춘 뒤, `/config` 에 연결된 호스트 폴더를 다른 위치에 통째로 복사합니다. 라이브러리와 읽기 진행은 그 폴더의 `database.sqlite` 에 있습니다. `tasks.sqlite` 와 `sessions.sqlite` 도 같은 폴더에 있습니다. 컨테이너가 실행 중일 때 복사하면 파일이 깨질 수 있습니다.

### gotson/komga 에서 바꾸기

Container Manager에서 `gotson/komga` 컨테이너를 중지합니다. `/config` 와 만화 폴더로 쓰는 공유 폴더는 지우지 않습니다.

`ghcr.io/menkinds/komga:latest` 로 컨테이너를 새로 만들 때 기존과 같은 마운트를 지정합니다.

- 지금 `/config` 에 연결되어 있는 호스트 폴더를 다시 `/config` 에 연결
- 만화 폴더도 기존과 같은 경로에 연결
- 호스트 포트는 기존과 같이 `25600`

파일 권한은 바꾸지 않습니다. DSM에 이미 있는 공유 폴더 권한을 그대로 둡니다.

기존 컨테이너의 이미지만 `ghcr.io/menkinds/komga:latest` 로 바꿀 수 있으면 마운트를 유지한 채 그 방식으로 바꿔도 됩니다. 새 컨테이너가 라이브러리를 정상적으로 열면 이전 컨테이너만 지웁니다.

### 패키지가 비공개인 경우

개인 계정의 GHCR 패키지는 처음 올라오면 비공개일 수 있습니다. 나스에서 로그인 없이 받으려면 공개로 바꿉니다.

1. https://github.com/users/menkinds/packages/container/package/komga 를 엽니다.
2. Package settings 에서 Change visibility 를 Public 으로 바꿉니다.

공개로 바꾸기 전에는 Container Manager의 레지스트리에 `ghcr.io` 를 추가합니다. 사용자는 `menkinds`, 암호는 `read:packages` 권한이 있는 GitHub personal access token 입니다. `docker login ghcr.io -u menkinds` 에도 같은 토큰을 씁니다.

매주 월요일에 Actions가 gotson/komga 의 최신 릴리스를 확인합니다. 이 포크에 없는 릴리스면 upstream 태그를 머지한 풀 리퀘스트를 엽니다. 충돌이 있으면 표시를 그대로 두므로, 머지 전에 그 파일을 확인합니다.
