FROM golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132 AS build
ENV GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2 GOMEMLIMIT=2GiB CGO_ENABLED=0 GOAMD64=v1 GOFLAGS=-mod=mod
ADD --checksum=sha256:0ac3f5f81bcf8833a9d5c4bc134721fa95d7f062bd48eb1ed18bd5edd738e78b \
    https://codeload.github.com/versity/versitygw/tar.gz/refs/tags/v1.8.0 /tmp/source.tar.gz
WORKDIR /src
RUN tar xzf /tmp/source.tar.gz --strip-components=1 && mkdir /out \
    && cp go.mod /out/go.mod.before.txt && cp go.sum /out/go.sum.before.txt
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go get golang.org/x/net@v0.62.0 && go mod verify \
    && go build -p 2 -trimpath \
       -ldflags '-s -w -X=main.Build=otziv-c36 -X=main.Version=v1.8.0 -X=main.BuildTime=2026-10-21T00:00:00Z' \
       -o /out/versitygw ./cmd/versitygw \
    && cp go.mod /out/go.mod.after.txt && cp go.sum /out/go.sum.after.txt \
    && (diff -u /out/go.mod.before.txt /out/go.mod.after.txt > /out/modules.diff.txt || test "$?" -eq 1) \
    && go version -m /out/versitygw > /out/versitygw.buildinfo.txt
FROM ghcr.io/claidd/otziv-security@sha256:860e028744b41222a75dce94ce5c31572364a90787ccf478be95f575da6ad9a3
# Official Alpine 3.24 CVE-2026-107891 update; reject unrelated package/payload changes.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /usr/local/bin /usr/share/otziv-build -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /usr/local/bin /usr/share/otziv-build -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e musl | grep -Fx 'musl-1.2.6-r2'; \
    apk --no-network info -v -e musl-utils | grep -Fx 'musl-utils-1.2.6-r2'; \
    inventory > /tmp/c50.payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(musl|musl-utils)-[0-9]' > /tmp/c50.packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main musl=1.2.6-r3 musl-utils=1.2.6-r3; \
    apk --no-network info -v -e musl | grep -Fx 'musl-1.2.6-r3'; \
    apk --no-network info -v -e musl-utils | grep -Fx 'musl-utils-1.2.6-r3'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(musl|musl-utils)-[0-9]' > /tmp/c50.packages.after; \
    cmp /tmp/c50.packages.before /tmp/c50.packages.after; \
    inventory > /tmp/c50.payload.after; cmp /tmp/c50.payload.before /tmp/c50.payload.after; \
    rm /tmp/c50.payload.before /tmp/c50.payload.after /tmp/c50.packages.before /tmp/c50.packages.after
LABEL org.opencontainers.image.source="https://github.com/versity/versitygw" \
      org.opencontainers.image.version="v1.8.0-otziv.c36" \
      com.otziv.security.patch="Go1.27.3; x/net0.62.0; parent Alpine security packages preserved"
COPY --from=build /out/versitygw /usr/local/bin/versitygw
COPY --from=build /out/*.txt /usr/share/otziv-build/versity-c36/

LABEL com.otziv.security.c50="C50: official musl security package; golang.org/x/net v0.62.0; required x/* dependency closure"
