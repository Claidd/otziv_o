# syntax=docker/dockerfile:1
FROM golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132 AS gosu
WORKDIR /src

# Rebuild the same reviewed, unmodified gosu 1.19 source with Go 1.27.3.
ADD --checksum=sha256:33d7537d588ea49458b9509bcf4554bdf5ceacc66da71e5caa1058ea3b689c3b \
    https://codeload.github.com/tianon/gosu/tar.gz/6456aaa0f3c854d199d0f037f068eb97515b7513 /source.tar.gz
RUN tar -xzf /source.tar.gz --strip-components=1 -C /src
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
RUN test "$(go version)" = 'go version go1.27.3 linux/amd64' \
    && go build -mod=readonly -trimpath -buildvcs=false -ldflags=-w -o /gosu . \
    && go version -m /gosu | grep -F 'go1.27.3'

# C23 is the active PostgreSQL 17.11 / Debian trixie image with its reviewed
# SSL package refresh. Only the gosu executable changes in this derivative.
FROM ghcr.io/claidd/otziv-security@sha256:c974c12a1107e314b1ba8cf65bd1fc5b6964645ced420c2151d336ee8a9a6534
COPY --from=gosu /gosu /usr/local/bin/gosu
COPY --from=gosu /src/LICENSE /usr/local/share/doc/gosu/LICENSE
LABEL com.otziv.gosu.source="https://github.com/tianon/gosu/tree/6456aaa0f3c854d199d0f037f068eb97515b7513" \
      com.otziv.gosu.source-sha256="33d7537d588ea49458b9509bcf4554bdf5ceacc66da71e5caa1058ea3b689c3b" \
      com.otziv.gosu.go="1.27.3" \
      com.otziv.security.proof="postgres-c36-gosu-go1273"
RUN gosu --version && test "$(gosu nobody id -u)" = 65534 && postgres --version
