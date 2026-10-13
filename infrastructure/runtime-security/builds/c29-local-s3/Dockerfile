FROM golang:1.27.2-bookworm@sha256:5cf287a799e6b94384bad13d16b14904c531f51ba65792237e122ce42b392f61 AS build
ENV GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2 GOMEMLIMIT=2GiB CGO_ENABLED=0 GOAMD64=v1 GOFLAGS=-mod=mod
ADD --checksum=sha256:0ac3f5f81bcf8833a9d5c4bc134721fa95d7f062bd48eb1ed18bd5edd738e78b \
    https://codeload.github.com/versity/versitygw/tar.gz/refs/tags/v1.8.0 /tmp/source.tar.gz
WORKDIR /src
RUN tar xzf /tmp/source.tar.gz --strip-components=1 && mkdir /out \
    && cp go.mod /out/go.mod.before.txt && cp go.sum /out/go.sum.before.txt
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go get golang.org/x/net@v0.60.0 && go mod verify \
    && go build -p 2 -trimpath \
       -ldflags '-s -w -X=main.Build=otziv-c29 -X=main.Version=v1.8.0 -X=main.BuildTime=2026-10-13T00:00:00Z' \
       -o /out/versitygw ./cmd/versitygw \
    && cp go.mod /out/go.mod.after.txt && cp go.sum /out/go.sum.after.txt \
    && (diff -u /out/go.mod.before.txt /out/go.mod.after.txt > /out/modules.diff.txt || test "$?" -eq 1) \
    && go version -m /out/versitygw > /out/versitygw.buildinfo.txt
FROM ghcr.io/claidd/otziv-security@sha256:3bd06e6605d318c852fc56f42d14316b5cd74ed233119d586b078bd4a5e4be21
LABEL org.opencontainers.image.source="https://github.com/versity/versitygw" \
      org.opencontainers.image.version="v1.8.0-otziv.c29" \
      com.otziv.security.patch="Go1.27.2; x/net0.60.0; parent Alpine security packages preserved"
COPY --from=build /out/versitygw /usr/local/bin/versitygw
COPY --from=build /out/*.txt /usr/share/otziv-build/versity-c29/
