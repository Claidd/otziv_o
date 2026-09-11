FROM golang:1.27.1-bookworm@sha256:648f440f42a0958804efb24df176f806f9d353b41f1c0627f666428e40310f6b AS build
ENV GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2 GOMEMLIMIT=2GiB CGO_ENABLED=0 GOFLAGS=-mod=mod
ADD --checksum=sha256:426e46991bc84535681b7b38a407655bfc2c685c7da6358adffe3aa53c79ef09 \
    https://codeload.github.com/grafana/loki/tar.gz/7a40404f32b3e6464c9cfc6cc7dd75a40f3931da /tmp/source.tar.gz
WORKDIR /src
RUN tar xzf /tmp/source.tar.gz --strip-components=1 && mkdir /out \
    && cp go.mod /out/go.mod.before.txt && cp go.sum /out/go.sum.before.txt
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go get google.golang.org/grpc@v1.83.2 \
    && sed -e 's|google.golang.org/grpc v1.82.1|google.golang.org/grpc v1.83.2|' \
       -e 's|golang.org/x/net v0.57.0|golang.org/x/net v0.58.0|' \
       -e 's|github.com/spiffe/go-spiffe/v2 v2.6.0|github.com/spiffe/go-spiffe/v2 v2.7.0|' \
       -e 's|go.opentelemetry.io/contrib/detectors/gcp v1.43.0|go.opentelemetry.io/contrib/detectors/gcp v1.44.0|' \
       -e 's|github.com/GoogleCloudPlatform/opentelemetry-operations-go/detectors/gcp v1.32.0|github.com/GoogleCloudPlatform/opentelemetry-operations-go/detectors/gcp v1.33.0|' \
       /out/go.mod.before.txt > /tmp/expected && cmp /tmp/expected go.mod \
    && go mod verify \
    && go build -p 2 -trimpath -tags netgo \
       -ldflags '-s -w -X github.com/grafana/loki/v3/pkg/util/build.Version=3.7.7-otziv.1 -X github.com/grafana/loki/v3/pkg/util/build.Revision=7a40404f32b3e6464c9cfc6cc7dd75a40f3931da -X github.com/grafana/loki/v3/pkg/util/build.Branch=release-3.7 -X github.com/grafana/loki/v3/pkg/util/build.BuildUser=otziv-security' \
       -o /out/loki ./cmd/loki \
    && cp go.mod /out/go.mod.after.txt && cp go.sum /out/go.sum.after.txt \
    && (diff -u /out/go.mod.before.txt /out/go.mod.after.txt > /out/modules.diff.txt || test "$?" -eq 1) \
    && cp LICENSE /out/ && go version -m /out/loki > /out/loki.buildinfo.txt
COPY healthprobe /healthprobe
WORKDIR /healthprobe
RUN --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go test -count=1 -v ./... && go build -trimpath -ldflags '-s -w' -o /out/http-ready . \
    && go version -m /out/http-ready > /out/http-ready.buildinfo.txt

FROM grafana/loki@sha256:550d599ec4efacd8ebc0a5871766855057cba2bd0c669c0711d898c00d6d901f
LABEL org.opencontainers.image.source="https://github.com/grafana/loki" \
      org.opencontainers.image.revision="7a40404f32b3e6464c9cfc6cc7dd75a40f3931da" \
      org.opencontainers.image.version="3.7.7-otziv.1" \
      com.otziv.security.patch="Go1.27.1; google.golang.org/grpc1.83.2; loopback HTTP200 readiness helper"
COPY --from=build /out/loki /usr/bin/loki
COPY --from=build /out/http-ready /usr/bin/http-ready
COPY --from=build /out/*.txt /out/LICENSE /usr/share/otziv-build/
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=12 CMD ["/usr/bin/http-ready", "http://127.0.0.1:3100/ready"]
