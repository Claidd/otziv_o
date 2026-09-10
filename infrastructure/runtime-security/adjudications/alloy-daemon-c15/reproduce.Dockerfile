FROM golang:1.27.1-bookworm@sha256:648f440f42a0958804efb24df176f806f9d353b41f1c0627f666428e40310f6b AS source
ADD --checksum=sha256:69efdb87a91bb538323f5ccb6bcd86240cee3a78ee97632bd1005c434344c7f1 \
    https://codeload.github.com/grafana/alloy/tar.gz/becfd489a7bb459c0496893b555fb87a003296b1 /tmp/source.tar.gz
WORKDIR /src
RUN tar xzf /tmp/source.tar.gz --strip-components=1

# Keep the vendor's CGO/systemd/UI build dependencies and replace only Go.
FROM grafana/alloy-build-image:v0.1.35@sha256:9fa2a341b53503ce42cf9900c401d689a68ea67cdec6a20f53d72e3665fb8dc6 AS build
RUN rm -rf /usr/local/go
COPY --from=source /usr/local/go /usr/local/go
ENV GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2 GOMEMLIMIT=2GiB GOFLAGS=-mod=mod
COPY --from=source /src /src
WORKDIR /src/internal/web/ui
RUN --mount=type=cache,id=otziv-alloy-npm,target=/root/.npm,sharing=locked \
    npm ci --no-audit --no-fund && npm run build
WORKDIR /src
RUN mkdir /out && cp go.mod /out/root.go.mod.before.txt && cp go.sum /out/root.go.sum.before.txt \
    && cp collector/go.mod /out/collector.go.mod.before.txt && cp collector/go.sum /out/collector.go.sum.before.txt
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go get google.golang.org/grpc@v1.83.2 \
    && go -C collector get google.golang.org/grpc@v1.83.2 \
    && sed -e '/google.golang.org\/grpc /d' -e 's|golang.org/x/net v0.57.0|golang.org/x/net v0.58.0|' /out/root.go.mod.before.txt > /tmp/before \
    && sed '/google.golang.org\/grpc /d' go.mod > /tmp/after && cmp /tmp/before /tmp/after \
    && sed -e '/google.golang.org\/grpc /d' -e 's|golang.org/x/net v0.57.0|golang.org/x/net v0.58.0|' /out/collector.go.mod.before.txt > /tmp/before \
    && sed '/google.golang.org\/grpc /d' collector/go.mod > /tmp/after && cmp /tmp/before /tmp/after \
    && go mod verify && go -C collector mod verify \
    && RELEASE_BUILD=1 VERSION=1.19.2-otziv.1 GO_TAGS='netgo embedalloyui promtail_journal_enabled' \
       SKIP_UI_BUILD=1 SKIP_CODE_GENERATION=1 GO_FLAGS='-p=2 -trimpath' make alloy \
       GIT_REVISION=becfd489a7bb459c0496893b555fb87a003296b1 GIT_BRANCH=release-1.19 BUILDER_USER=otziv-security BUILDER_HOST=build \
    && cp go.mod /out/root.go.mod.after.txt && cp go.sum /out/root.go.sum.after.txt \
    && cp collector/go.mod /out/collector.go.mod.after.txt && cp collector/go.sum /out/collector.go.sum.after.txt \
    && (diff -u /out/root.go.mod.before.txt /out/root.go.mod.after.txt > /out/root.modules.diff.txt || test "$?" -eq 1) \
    && (diff -u /out/collector.go.mod.before.txt /out/collector.go.mod.after.txt > /out/collector.modules.diff.txt || test "$?" -eq 1) \
    && cp LICENSE /out/ && go version -m build/alloy > /out/alloy.buildinfo.txt

FROM grafana/alloy@sha256:1eeba15ef3193438c72f66efd3d76f769c523a4c661db0fae6eddde906004bc8
LABEL org.opencontainers.image.source="https://github.com/grafana/alloy" \
      org.opencontainers.image.revision="becfd489a7bb459c0496893b555fb87a003296b1" \
      org.opencontainers.image.version="1.19.2-otziv.1" \
      com.otziv.security.patch="Go1.27.1; google.golang.org/grpc1.83.2; original UI lock and vendor build tags"
COPY --from=build /src/build/alloy /bin/alloy
COPY --from=build /out/*.txt /out/LICENSE /usr/share/otziv-build/


# Read-only dependency inventory from the exact original build stage.
FROM build AS closure
RUN --network=none --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    mkdir /closure \
    && go -C collector list -mod=readonly -deps -json -tags='gore2regex netgo embedalloyui promtail_journal_enabled' . > /closure/packages.jsons \
    && sha256sum build/alloy go.mod go.sum collector/go.mod collector/go.sum .govulncheck.yaml Makefile > /closure/build-sha256.txt \
    && go env -json GOOS GOARCH CGO_ENABLED GOEXPERIMENT GOVERSION > /closure/go-env.json \
    && cp .govulncheck.yaml Makefile /closure/ \
    && cp /out/*.txt /closure/
FROM scratch AS closure-output
COPY --from=closure /closure/ /


# The vendor supports SOURCE_DATE_EPOCH. Reproduce the observed publication date;
# retain the complete original build and closure before this explicit relink.
FROM closure AS reproducible
RUN --network=none --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    RELEASE_BUILD=1 VERSION=1.19.2-otziv.1 GO_TAGS='netgo embedalloyui promtail_journal_enabled' \
    SKIP_UI_BUILD=1 SKIP_CODE_GENERATION=1 GO_FLAGS='-p=2 -trimpath' SOURCE_DATE_EPOCH=1789058980 make alloy \
    GIT_REVISION=becfd489a7bb459c0496893b555fb87a003296b1 GIT_BRANCH=release-1.19 BUILDER_USER=otziv-security BUILDER_HOST=build \
    && echo '6535f200bc605f313eae5deba4e912d35ff7236f3dd066639d8d52d8eb01e07a  build/alloy' | sha256sum --check --strict \
    && sha256sum build/alloy go.mod go.sum collector/go.mod collector/go.sum .govulncheck.yaml Makefile > /closure/build-sha256.txt \
    && go version -m build/alloy > /closure/alloy.buildinfo.txt
FROM scratch AS repro-output
COPY --from=reproducible /closure/ /
