FROM golang:1.27.1-bookworm@sha256:648f440f42a0958804efb24df176f806f9d353b41f1c0627f666428e40310f6b AS build
ENV GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2 GOMEMLIMIT=2GiB CGO_ENABLED=0 GOFLAGS=-mod=mod
ADD --checksum=sha256:57b1ac858e1dc26a16a4d2e659f708014b6e048fd4bfcc48a9ce50cf7c601052 \
    https://codeload.github.com/prometheus/prometheus/tar.gz/b273ae3adeb64ad630d65ef7f16440df95658410 /tmp/source.tar.gz
ADD --checksum=sha256:2647eb2e0b5dbc2bb16bbcec2cea78e4b8fc36f4564f6ce6569627b47f3432bd \
    https://github.com/prometheus/prometheus/releases/download/v3.13.3/prometheus-web-ui-3.13.3.tar.gz /tmp/ui.tar.gz
WORKDIR /src
RUN tar xzf /tmp/source.tar.gz --strip-components=1 && tar xzf /tmp/ui.tar.gz -C web/ui \
    && PREBUILT_ASSETS_STATIC_DIR=web/ui/static bash scripts/compress_assets.sh \
    && mkdir /out && cp go.mod /out/go.mod.before.txt && cp go.sum /out/go.sum.before.txt
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go get google.golang.org/grpc@v1.83.2 \
    && sed -e '/google.golang.org\/grpc /d' -e 's|golang.org/x/net v0.57.0|golang.org/x/net v0.58.0|' /out/go.mod.before.txt > /tmp/before \
    && sed '/google.golang.org\/grpc /d' go.mod > /tmp/after && cmp /tmp/before /tmp/after \
    && go mod verify \
    && go build -p 2 -trimpath -tags netgo,builtinassets \
       -ldflags '-s -w -X github.com/prometheus/common/version.Version=3.13.3-otziv.1 -X github.com/prometheus/common/version.Revision=b273ae3adeb64ad630d65ef7f16440df95658410 -X github.com/prometheus/common/version.Branch=release-3.13 -X github.com/prometheus/common/version.BuildUser=otziv-security' \
       -o /out/prometheus ./cmd/prometheus \
    && go build -p 2 -trimpath -tags netgo,builtinassets \
       -ldflags '-s -w -X github.com/prometheus/common/version.Version=3.13.3-otziv.1 -X github.com/prometheus/common/version.Revision=b273ae3adeb64ad630d65ef7f16440df95658410' \
       -o /out/promtool ./cmd/promtool \
    && cp go.mod /out/go.mod.after.txt && cp go.sum /out/go.sum.after.txt \
    && (diff -u /out/go.mod.before.txt /out/go.mod.after.txt > /out/modules.diff.txt || test "$?" -eq 1) \
    && cp LICENSE NOTICE /out/ && go version -m /out/prometheus > /out/prometheus.buildinfo.txt

FROM prom/prometheus@sha256:595c907995955f2d5fda19fae66392680921d9501cad0be5270b3fee959780b1
LABEL org.opencontainers.image.source="https://github.com/prometheus/prometheus" \
      org.opencontainers.image.revision="b273ae3adeb64ad630d65ef7f16440df95658410" \
      org.opencontainers.image.version="3.13.3-otziv.1" \
      com.otziv.security.patch="Go1.27.1; google.golang.org/grpc1.83.2; unmodified official web assets"
COPY --from=build /out/prometheus /bin/prometheus
COPY --from=build /out/promtool /bin/promtool
COPY --from=build /out/*.txt /out/LICENSE /out/NOTICE /usr/share/otziv-build/
