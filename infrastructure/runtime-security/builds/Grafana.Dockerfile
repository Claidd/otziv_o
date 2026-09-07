# Rebuild the official OSS backend; keep the exact release's frontend, bundled
# plugins, configuration, entrypoint, CA store and non-root identity below.
FROM grafana/grafana@sha256:27e80e0f4fa3d423bcbbbb3418f2a6475833f94a2a88b6f2547c74830ce4286e AS upstream
FROM golang:1.27.1-bookworm@sha256:648f440f42a0958804efb24df176f806f9d353b41f1c0627f666428e40310f6b AS dependencies
ENV GOTOOLCHAIN=local GOMAXPROCS=2 GOMEMLIMIT=2GiB CGO_ENABLED=0 GOAMD64=v1 GOFLAGS=-p=2
ADD --checksum=sha256:d70b8c78535f4f1b49c72f23129f4a77f4a066627f4c90dd9d71cfb001ba97bc \
    https://codeload.github.com/grafana/grafana/tar.gz/81407c71e96e8351b4600164c1b4d30c8baf41d6 /tmp/source.tar.gz
WORKDIR /src
RUN tar xzf /tmp/source.tar.gz --strip-components=1 && mkdir /out \
    && cp go.mod /out/go.mod.before.txt && cp go.sum /out/go.sum.before.txt \
    && cp go.work /out/go.work.before.txt && cp go.work.sum /out/go.work.sum.before.txt
COPY --from=upstream /usr/share/grafana/bin /upstream
RUN for binary in grafana grafana-cli grafana-server; do go version -m /upstream/$binary > /out/upstream.$binary.buildinfo.txt; done
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go list -m all > /out/modules.before.txt \
    && go get google.golang.org/grpc@v1.83.1 github.com/apache/thrift@v0.24.0 \
    && go mod verify && go list -m all > /out/modules.after.txt \
    && cp go.mod /out/go.mod.after.txt && cp go.sum /out/go.sum.after.txt \
    && cp go.work /out/go.work.after.txt && cp go.work.sum /out/go.work.sum.after.txt \
    && (diff -u /out/go.mod.before.txt /out/go.mod.after.txt > /out/go.mod.diff.txt || test "$?" -eq 1) \
    && (diff -u /out/modules.before.txt /out/modules.after.txt > /out/modules.diff.txt || test "$?" -eq 1)
FROM scratch AS dependency-evidence
COPY --from=dependencies /out /

FROM dependencies AS build
# These are the two requested fixes and the four minimum versions required by
# grpc 1.83.1. Reject any additional root or workspace dependency changes.
RUN sed -e 's|google.golang.org/grpc v1.82.1|google.golang.org/grpc v1.83.1|' \
      -e 's|github.com/apache/thrift v0.23.1-0.20260429145742-d2acd3c49e58|github.com/apache/thrift v0.24.0|' \
      -e 's|cel.dev/expr v0.25.1|cel.dev/expr v0.25.2|' \
      -e 's|github.com/GoogleCloudPlatform/opentelemetry-operations-go/detectors/gcp v1.32.0|github.com/GoogleCloudPlatform/opentelemetry-operations-go/detectors/gcp v1.33.0|' \
      -e 's|github.com/spiffe/go-spiffe/v2 v2.6.0|github.com/spiffe/go-spiffe/v2 v2.7.0|' \
      -e 's|go.opentelemetry.io/contrib/detectors/gcp v1.43.0|go.opentelemetry.io/contrib/detectors/gcp v1.44.0|' \
      /out/go.mod.before.txt > /tmp/expected.go.mod \
    && cmp /tmp/expected.go.mod go.mod && cmp /out/go.work.before.txt go.work \
    && mkdir /tmp/source-modules \
    && tar xzf /tmp/source.tar.gz --wildcards --strip-components=1 -C /tmp/source-modules '*/go.mod' \
    && for module in $(find . -name go.mod ! -path ./go.mod); do \
         cmp /tmp/source-modules/${module#./} "$module" || exit 1; \
       done
# Same release entrypoints and flags as the inspected official binaries. SQLite
# stays on modernc (CGO=0); do not substitute a CGO build or add feature tags.
RUN grep -q 'CGO_ENABLED=0$' /out/upstream.grafana.buildinfo.txt \
    && grep -q 'GOAMD64=v1$' /out/upstream.grafana.buildinfo.txt \
    && grep -q 'modernc.org/sqlite' /out/upstream.grafana.buildinfo.txt
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    make build-go GO_BUILD_TAGS= WIRE_TAGS=oss BUILD_VERSION=12.4.10+otziv.1 \
      COMMIT_SHA=81407c71e96e8351b4600164c1b4d30c8baf41d6 BUILD_BRANCH=release-12.4.10-otziv-security \
      SOURCE_DATE_EPOCH=1787944799 \
    && cmp /out/go.mod.after.txt go.mod && cmp /out/go.work.after.txt go.work \
    && go list -m all > /out/modules.built.txt && cmp /out/modules.after.txt /out/modules.built.txt \
    && cp go.sum /out/go.sum.built.txt && cp go.work.sum /out/go.work.sum.built.txt \
    && for binary in grafana grafana-cli grafana-server; do \
         cp bin/linux/amd64/$binary /out/$binary; \
         go version -m /out/$binary > /out/$binary.buildinfo.txt; \
       done \
    && cp LICENSE /out/

FROM upstream AS runtime
USER 0
# Exact available Alpine security patch. No TLS/CA policy changes or package
# removal; a removed upstream package version fails the build for review.
RUN apk add --no-cache --upgrade 'libcrypto3=3.5.8-r0' 'libssl3=3.5.8-r0'
LABEL org.opencontainers.image.revision="81407c71e96e8351b4600164c1b4d30c8baf41d6" \
      org.opencontainers.image.version="12.4.10+otziv.1" \
      com.otziv.security.patch="Go1.27.1; grpc1.83.1; thrift0.24.0; exact required MVS; OpenSSL3.5.8; official CGO0 modernc SQLite"
COPY --from=build /out/grafana /out/grafana-cli /out/grafana-server /usr/share/grafana/bin/
COPY --from=build /out/*.txt /out/LICENSE /usr/share/otziv-build/
RUN grafana server -v | sed -e 's/Version //' > /.grafana-version && chmod 644 /.grafana-version
USER 472
