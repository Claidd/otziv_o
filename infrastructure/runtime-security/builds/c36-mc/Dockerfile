FROM golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132 AS build
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
ENV GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2 GOMEMLIMIT=2GiB CGO_ENABLED=0 GOAMD64=v1
WORKDIR /src
RUN git init && git remote add origin https://github.com/minio/mc.git \
    && git fetch --depth=1 origin 77f82e18b5401a65958f1619df6ebb994634bd88 \
    && git checkout --detach FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = 77f82e18b5401a65958f1619df6ebb994634bd88 \
    && mkdir /out && cp go.mod /out/go.mod.before.txt && cp go.sum /out/go.sum.before.txt
COPY builds/c33-mc/mc.go.mod /src/go.mod
COPY builds/c33-mc/mc.go.sum /src/go.sum
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go get golang.org/x/net@v0.60.0 && go mod download && go mod verify \
    && test "$(go list -mod=readonly -m github.com/klauspost/compress)" = 'github.com/klauspost/compress v1.18.7' \
    && go list -mod=readonly -m -json all > /out/modules.json \
    && go test -mod=readonly -p 2 -count=1 -timeout=120s -v \
      -run '^(TestParseEnvURLStr|TestParseEnvURLStrInvalid|TestValidHostURL|TestIsValidAPI|TestValidSecretKeys|TestValidAccessKeys|TestParseStat|TestParseMetaData)$' ./cmd \
      | tee /out/unit-tests.txt \
    && go build -mod=readonly -p 2 -trimpath -buildvcs=true -tags kqueue \
      -ldflags '-s -w -X github.com/minio/mc/cmd.Version=2025-11-06T16:25:29Z -X github.com/minio/mc/cmd.ReleaseTag=RELEASE.2025-11-06T16-25-29Z.otziv-c36.1 -X github.com/minio/mc/cmd.CommitID=77f82e18b5401a65958f1619df6ebb994634bd88 -X github.com/minio/mc/cmd.ShortCommitID=77f82e18b540' \
      -o /out/mc . \
    && go version -m /out/mc > /out/buildinfo.txt \
    && grep -Eq 'github.com/klauspost/compress[[:space:]]+v1\.18\.7([[:space:]]|$)' /out/buildinfo.txt \
    && cp go.mod /out/go.mod.after.txt && cp go.sum /out/go.sum.after.txt \
    && git diff -- go.mod go.sum > /out/dependency.patch \
    && git show -s --format='%H%n%cI%n%s' HEAD > /out/upstream.txt \
    && sha256sum /out/mc > /out/binary.sha256 \
    && tar --exclude=.git --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
      -czf /out/corresponding-source.tar.gz .

# Start from a client-free base so the vulnerable old mc cannot remain in an image layer.
FROM alpine@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS runtime
RUN apk upgrade --no-cache && apk add --no-cache ca-certificates \
    && test ! -e /usr/bin/mc \
    && mkdir -p /usr/share/otziv-build/mc \
    && cp /lib/apk/db/installed /usr/share/otziv-build/mc/apk-installed-database
LABEL org.opencontainers.image.source="https://github.com/Claidd/otziv_o" \
      org.opencontainers.image.revision="77f82e18b5401a65958f1619df6ebb994634bd88" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.version="RELEASE.2025-11-06T16-25-29Z.otziv-c36.1" \
      com.otziv.upstream.archived="true" \
      com.otziv.corresponding-source="/usr/share/otziv-build/mc/corresponding-source.tar.gz"
COPY --from=build /out/mc /usr/bin/mc
COPY --from=build /out/*.txt /out/*.json /out/*.sha256 /out/dependency.patch /out/corresponding-source.tar.gz /usr/share/otziv-build/mc/
COPY --from=build /src/LICENSE /src/CREDITS /licenses/
RUN mc --version
ENV MC_CONFIG_DIR=/tmp/.mc
ENTRYPOINT ["/usr/bin/mc"]
