FROM golang:1.27.1-bookworm@sha256:648f440f42a0958804efb24df176f806f9d353b41f1c0627f666428e40310f6b AS build
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
ENV GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2 GOMEMLIMIT=2GiB CGO_ENABLED=0 GOAMD64=v1
WORKDIR /src
RUN git init && git remote add origin https://github.com/minio/mc.git \
    && git fetch --depth=1 origin 77f82e18b5401a65958f1619df6ebb994634bd88 \
    && git checkout --detach FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = 77f82e18b5401a65958f1619df6ebb994634bd88 \
    && mkdir /out && cp go.mod /out/go.mod.before.txt && cp go.sum /out/go.sum.before.txt
COPY mc.go.mod /src/go.mod
COPY mc.go.sum /src/go.sum
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go mod download && go mod verify && go list -mod=readonly -m -json all > /out/modules.json \
    && go test -mod=readonly -p 2 -count=1 -timeout=120s -v \
      -run '^(TestParseEnvURLStr|TestParseEnvURLStrInvalid|TestValidHostURL|TestIsValidAPI|TestValidSecretKeys|TestValidAccessKeys|TestParseStat|TestParseMetaData)$' ./cmd \
      | tee /out/unit-tests.txt \
    && go build -mod=readonly -p 2 -trimpath -buildvcs=true -tags kqueue \
      -ldflags '-s -w -X github.com/minio/mc/cmd.Version=2025-11-06T16:25:29Z -X github.com/minio/mc/cmd.ReleaseTag=RELEASE.2025-11-06T16-25-29Z.otziv-c14.1 -X github.com/minio/mc/cmd.CommitID=77f82e18b5401a65958f1619df6ebb994634bd88 -X github.com/minio/mc/cmd.ShortCommitID=77f82e18b540' \
      -o /out/mc . \
    && go version -m /out/mc > /out/buildinfo.txt \
    && cp go.mod /out/go.mod.after.txt && cp go.sum /out/go.sum.after.txt \
    && git diff -- go.mod go.sum > /out/dependency.patch \
    && git show -s --format='%H%n%cI%n%s' HEAD > /out/upstream.txt \
    && sha256sum /out/mc > /out/binary.sha256 \
    && tar --exclude=.git --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
      -czf /out/corresponding-source.tar.gz .

FROM registry.access.redhat.com/ubi9/ubi-minimal@sha256:7fbeae18dc9476399f565e68255f602a3374ea8614ba3d14843565131a13ff93 AS runtime
COPY runtime-packages.lock /usr/share/otziv-build/runtime-packages.lock
RUN microdnf --refresh upgrade --nodocs --assumeyes \
    && microdnf install --nodocs --assumeyes ca-certificates curl-minimal coreutils-single \
    && mkdir -p /usr/share/otziv-build/mc \
    && rpm -qa --qf '%{NAME}=%{EPOCHNUM}:%{VERSION}-%{RELEASE}.%{ARCH}\n' | sort > /usr/share/otziv-build/runtime-packages.txt \
    && test "$(sha256sum /usr/share/otziv-build/runtime-packages.lock | cut -d ' ' -f 1)" = "$(sha256sum /usr/share/otziv-build/runtime-packages.txt | cut -d ' ' -f 1)" \
    && microdnf clean all \
    && microdnf remove --assumeyes curl-minimal libcurl-minimal microdnf libdnf librepo rpm rpm-libs libmodulemd libsolv \
    && test ! -e /usr/bin/curl && test ! -e /usr/lib64/libcurl.so.4 \
    && test ! -e /usr/bin/rpm && test ! -e /usr/bin/microdnf \
    && test -s /var/lib/rpm/rpmdb.sqlite \
    && mv /usr/share/otziv-build/runtime-packages.txt /usr/share/otziv-build/runtime-packages.before-removal.txt
LABEL org.opencontainers.image.source="https://github.com/Claidd/otziv_o" \
      org.opencontainers.image.revision="77f82e18b5401a65958f1619df6ebb994634bd88" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.version="RELEASE.2025-11-06T16-25-29Z.otziv-c14.1" \
      com.otziv.upstream.archived="true" \
      com.otziv.corresponding-source="/usr/share/otziv-build/mc/corresponding-source.tar.gz"
COPY --from=build /out/mc /usr/bin/mc
COPY --from=build /out/*.txt /out/*.json /out/*.sha256 /out/dependency.patch /out/corresponding-source.tar.gz /usr/share/otziv-build/mc/
COPY --from=build /src/LICENSE /src/CREDITS /licenses/
ENV MC_CONFIG_DIR=/tmp/.mc
ENTRYPOINT ["mc"]
