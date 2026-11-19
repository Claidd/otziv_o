# syntax=docker/dockerfile:1
FROM golang:1.27.4-bookworm@sha256:4fa7c968ba93f1635670522dbb01a2c78150443e1385746924566079f36570ea AS gosu
WORKDIR /src

# Rebuild the same reviewed, unmodified gosu 1.19 source with Go 1.27.4.
ADD --checksum=sha256:33d7537d588ea49458b9509bcf4554bdf5ceacc66da71e5caa1058ea3b689c3b \
    https://codeload.github.com/tianon/gosu/tar.gz/6456aaa0f3c854d199d0f037f068eb97515b7513 /source.tar.gz
RUN tar -xzf /source.tar.gz --strip-components=1 -C /src
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
RUN test "$(go version)" = 'go version go1.27.4 linux/amd64' \
    && go build -mod=readonly -trimpath -buildvcs=false -ldflags=-w -o /gosu . \
    && go version -m /gosu | grep -F 'go1.27.4'

# C54 is the accepted PostgreSQL 17.12 / Kerberos 1.22.3 runtime.
# Only the gosu executable changes in this derivative.
FROM ghcr.io/claidd/otziv-security@sha256:32adf590dacac8d07c6dcd5bfbad27d527ae2ddf18e9822a404ba52ce69524a2
COPY --from=gosu /gosu /usr/local/bin/gosu
COPY --from=gosu /src/LICENSE /usr/local/share/doc/gosu/LICENSE
LABEL com.otziv.gosu.source="https://github.com/tianon/gosu/tree/6456aaa0f3c854d199d0f037f068eb97515b7513" \
      com.otziv.gosu.source-sha256="33d7537d588ea49458b9509bcf4554bdf5ceacc66da71e5caa1058ea3b689c3b" \
      com.otziv.gosu.go="1.27.4" \
      com.otziv.security.proof="postgres-c58-gosu-go1274"
RUN gosu --version && test "$(gosu nobody id -u)" = 65534 && postgres --version
