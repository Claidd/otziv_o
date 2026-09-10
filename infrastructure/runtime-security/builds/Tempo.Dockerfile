FROM golang:1.27.1-bookworm@sha256:648f440f42a0958804efb24df176f806f9d353b41f1c0627f666428e40310f6b AS build
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
ENV GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2 GOMEMLIMIT=2GiB CGO_ENABLED=0 GOAMD64=v2 GOFLAGS=-mod=mod
ADD --checksum=sha256:c354a7495843161a41ad75d96dd4e8f17aabc9a5f439060cf974a57b4ef2ff85 \
    https://codeload.github.com/grafana/tempo/tar.gz/f0f3ed59197bfe9f54f3b0f8015ccca112f9e544 /tmp/source.tar.gz
WORKDIR /src
RUN tar xzf /tmp/source.tar.gz --strip-components=1 && mkdir /out \
    && cp go.mod /out/go.mod.before.txt && cp go.sum /out/go.sum.before.txt
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go get google.golang.org/grpc@v1.83.2 github.com/apache/thrift@v0.24.0 golang.org/x/crypto@v0.55.0 \
    && sed -e 's|google.golang.org/grpc v1.82.1|google.golang.org/grpc v1.83.2|' \
       -e 's|github.com/apache/thrift v0.23.0|github.com/apache/thrift v0.24.0|' \
       -e 's|golang.org/x/crypto v0.53.0|golang.org/x/crypto v0.55.0|' \
       -e 's|github.com/spiffe/go-spiffe/v2 v2.6.0|github.com/spiffe/go-spiffe/v2 v2.7.0|' \
       -e 's|go.opentelemetry.io/contrib/detectors/gcp v1.43.0|go.opentelemetry.io/contrib/detectors/gcp v1.44.0|' \
       -e 's|github.com/GoogleCloudPlatform/opentelemetry-operations-go/detectors/gcp v1.32.0|github.com/GoogleCloudPlatform/opentelemetry-operations-go/detectors/gcp v1.33.0|' \
       -e 's|golang.org/x/net v0.56.0|golang.org/x/net v0.57.0|' \
       -e 's|golang.org/x/sys v0.46.0|golang.org/x/sys v0.47.0|' \
       -e 's|golang.org/x/term v0.44.0|golang.org/x/term v0.45.0|' \
       -e 's|golang.org/x/text v0.39.0|golang.org/x/text v0.41.0|' \
       -e 's|golang.org/x/tools v0.47.0|golang.org/x/tools v0.48.0|' \
       -e 's|golang.org/x/mod v0.37.0|golang.org/x/mod v0.38.0|' \
       -e 's|golang.org/x/sync v0.21.0|golang.org/x/sync v0.22.0|' \
       -e 's|cel.dev/expr v0.25.1|cel.dev/expr v0.25.2|' \
       -e 's|go.opentelemetry.io/otel/sdk v1.43.0|go.opentelemetry.io/otel/sdk v1.44.0|' \
       -e 's|go.opentelemetry.io/otel/sdk/metric v1.43.0|go.opentelemetry.io/otel/sdk/metric v1.44.0|' \
       -e 's|google.golang.org/genproto/googleapis/rpc v0.0.0-20260414002931-afd174a4e478|google.golang.org/genproto/googleapis/rpc v0.0.0-20260526163538-3dc84a4a5aaa|' \
       -e 's|google.golang.org/genproto/googleapis/api v0.0.0-20260414002931-afd174a4e478|google.golang.org/genproto/googleapis/api v0.0.0-20260526163538-3dc84a4a5aaa|' \
       /out/go.mod.before.txt > /tmp/expected \
    && diff -u /tmp/expected go.mod \
    && go mod verify \
    && cp go.mod /out/go.mod.after.txt && cp go.sum /out/go.sum.after.txt \
    && diff -u /tmp/expected go.mod \
    && (diff -u /out/go.mod.before.txt /out/go.mod.after.txt > /out/modules.diff.txt || test "$?" -eq 1) \
    && cp LICENSE /out/
COPY builds/tempo-queue-shutdown.patch /out/source.patch.txt
COPY builds/tempo-queue-shutdown_test.go modules/frontend/queue/otziv_shutdown_test.go
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    echo '5978bc9e1d464d9d040b995f94e180bb8043c8542de813eb6ce8103f8630364a  modules/frontend/queue/queue.go' | sha256sum -c - \
    && sha256sum modules/frontend/queue/queue.go > /out/source.before.sha256.txt \
    && git apply --check /out/source.patch.txt && git apply /out/source.patch.txt \
    && gofmt -w modules/frontend/queue/queue.go modules/frontend/queue/otziv_shutdown_test.go \
    && sha256sum modules/frontend/queue/queue.go > /out/source.after.sha256.txt \
    && cp modules/frontend/queue/otziv_shutdown_test.go /out/queue-regression-source.txt \
    && go test -p 2 -count=1 -timeout=90s -v ./modules/frontend/queue | tee /out/queue-tests.txt \
    && CGO_ENABLED=1 go test -race -p 2 -count=5 -run '^TestOtziv' -timeout=60s -v ./modules/frontend/queue | tee /out/queue-race-tests.txt \
    && diff -u /tmp/expected go.mod \
    && go build -p 2 -trimpath -ldflags '-w -X main.Version=2.10.8-otziv.2 -X main.Revision=f0f3ed59197bfe9f54f3b0f8015ccca112f9e544 -X main.Branch=release-2.10' -o /out/tempo ./cmd/tempo \
    && diff -u /tmp/expected go.mod \
    && go version -m /out/tempo > /out/tempo.buildinfo.txt
COPY healthprobe /healthprobe
WORKDIR /healthprobe
RUN --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go test -count=1 -v ./... && go build -trimpath -ldflags '-s -w' -o /out/http-ready . \
    && go version -m /out/http-ready > /out/http-ready.buildinfo.txt

FROM grafana/tempo@sha256:b18e2bf60dd852ae891d721f906c15eb6af558c0e785fe82c019da4f2006f071
LABEL org.opencontainers.image.source="https://github.com/grafana/tempo" \
      org.opencontainers.image.revision="f0f3ed59197bfe9f54f3b0f8015ccca112f9e544" \
      org.opencontainers.image.version="2.10.8-otziv.2" \
      com.otziv.security.patch="Go1.27.1; grpc1.83.2; thrift0.24.0; xcrypto0.55.0; exact required transitive versions; request queue drain patch; loopback HTTP200 readiness"
COPY --from=build /out/tempo /tempo
COPY --from=build /out/http-ready /usr/bin/http-ready
COPY --from=build /out/*.txt /out/LICENSE /usr/share/otziv-build/
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=12 CMD ["/usr/bin/http-ready", "http://127.0.0.1:3200/ready"]
