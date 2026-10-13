FROM golang:1.27.2-bookworm@sha256:5cf287a799e6b94384bad13d16b14904c531f51ba65792237e122ce42b392f61 AS source
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
    go get google.golang.org/grpc@v1.83.2 golang.org/x/net@v0.60.0 \
    && go -C collector get google.golang.org/grpc@v1.83.2 golang.org/x/net@v0.60.0 \
    && sed -e '/google.golang.org\/grpc /d' \
       -e 's|golang.org/x/crypto v0.55.0|golang.org/x/crypto v0.57.0|' \
       -e 's|golang.org/x/mod v0.40.0|golang.org/x/mod v0.41.0|' \
       -e 's|golang.org/x/net v0.58.0|golang.org/x/net v0.60.0|' \
       -e 's|golang.org/x/sync v0.22.0|golang.org/x/sync v0.23.0|' \
       -e 's|golang.org/x/sys v0.47.0|golang.org/x/sys v0.48.0|' \
       -e 's|golang.org/x/term v0.45.0|golang.org/x/term v0.46.0|' \
       -e 's|golang.org/x/text v0.41.0|golang.org/x/text v0.42.0|' \
       /out/root.go.mod.before.txt > /tmp/before \
    && sed '/google.golang.org\/grpc /d' go.mod > /tmp/after && cmp /tmp/before /tmp/after \
    && sed -e '/google.golang.org\/grpc /d' \
       -e 's|golang.org/x/crypto v0.55.0|golang.org/x/crypto v0.57.0|' \
       -e 's|golang.org/x/mod v0.40.0|golang.org/x/mod v0.41.0|' \
       -e 's|golang.org/x/net v0.58.0|golang.org/x/net v0.60.0|' \
       -e 's|golang.org/x/sync v0.22.0|golang.org/x/sync v0.23.0|' \
       -e 's|golang.org/x/sys v0.47.0|golang.org/x/sys v0.48.0|' \
       -e 's|golang.org/x/term v0.45.0|golang.org/x/term v0.46.0|' \
       -e 's|golang.org/x/text v0.41.0|golang.org/x/text v0.42.0|' \
       /out/collector.go.mod.before.txt > /tmp/before \
    && sed '/google.golang.org\/grpc /d' collector/go.mod > /tmp/after && cmp /tmp/before /tmp/after \
    && go mod verify && go -C collector mod verify \
    && RELEASE_BUILD=1 VERSION=1.19.2-otziv.2 GO_TAGS='netgo embedalloyui promtail_journal_enabled' \
       SKIP_UI_BUILD=1 SKIP_CODE_GENERATION=1 GO_FLAGS='-p=2 -trimpath' make alloy \
       GIT_REVISION=becfd489a7bb459c0496893b555fb87a003296b1 GIT_BRANCH=release-1.19 BUILDER_USER=otziv-security BUILDER_HOST=build \
    && cp go.mod /out/root.go.mod.after.txt && cp go.sum /out/root.go.sum.after.txt \
    && cp collector/go.mod /out/collector.go.mod.after.txt && cp collector/go.sum /out/collector.go.sum.after.txt \
    && (diff -u /out/root.go.mod.before.txt /out/root.go.mod.after.txt > /out/root.modules.diff.txt || test "$?" -eq 1) \
    && (diff -u /out/collector.go.mod.before.txt /out/collector.go.mod.after.txt > /out/collector.modules.diff.txt || test "$?" -eq 1) \
    && cp LICENSE /out/ && go version -m build/alloy > /out/alloy.buildinfo.txt

# Capture the complete import closure with the exact build tags from the binary
# build. The Docker CVEs tracked for this component affect daemon packages.
FROM build AS closure
RUN --mount=type=cache,id=otziv-monitoring-go-mod,target=/go/pkg/mod,sharing=locked \
    --mount=type=cache,id=otziv-monitoring-go-build,target=/root/.cache/go-build,sharing=locked \
    go -C collector list -deps -tags 'gore2regex netgo embedalloyui promtail_journal_enabled' \
      -f '{{.ImportPath}}' . | LC_ALL=C sort -u > /out/alloy.packages.txt \
    && grep -qx 'github.com/docker/docker/client' /out/alloy.packages.txt \
    && ! grep -Eq '^(github.com/docker/docker/daemon|github.com/moby/moby(/v2)?/daemon)(/|$)' /out/alloy.packages.txt \
    && test "$(wc -l < /out/alloy.packages.txt)" -gt 1000

# C23 runtime carries the reviewed Ubuntu OpenSSL 3.0.13-0ubuntu3.16 patch.
FROM ghcr.io/claidd/otziv-security@sha256:82d7bc33adf7a8a2e7d6d4749ffeba837ee83ecf5dad5f260fee9992de460992
LABEL org.opencontainers.image.source="https://github.com/grafana/alloy" \
      org.opencontainers.image.revision="becfd489a7bb459c0496893b555fb87a003296b1" \
      org.opencontainers.image.version="1.19.2-otziv.2" \
      com.otziv.security.patch="Go1.27.2; google.golang.org/grpc1.83.2; original UI lock and vendor build tags" \
      com.otziv.security.proof="alloy-daemon-c29"
COPY --from=build /src/build/alloy /bin/alloy
COPY --from=build /out/*.txt /out/LICENSE /usr/share/otziv-build/
COPY --from=closure /out/alloy.packages.txt /usr/share/otziv-build/alloy.packages.txt
