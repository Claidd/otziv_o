FROM golang:1.27.1-bookworm@sha256:648f440f42a0958804efb24df176f806f9d353b41f1c0627f666428e40310f6b AS gosu
WORKDIR /src
# Unmodified gosu 1.19 source, rebuilt with the patched Go standard library.
ADD --checksum=sha256:33d7537d588ea49458b9509bcf4554bdf5ceacc66da71e5caa1058ea3b689c3b \
    https://codeload.github.com/tianon/gosu/tar.gz/6456aaa0f3c854d199d0f037f068eb97515b7513 /source.tar.gz
RUN tar -xzf /source.tar.gz --strip-components=1 -C /src
ARG TARGETARCH
ENV CGO_ENABLED=0
RUN GOARCH="$TARGETARCH" go build -mod=readonly -trimpath -buildvcs=false -ldflags=-w -o /gosu . \
    && go version -m /gosu

FROM postgres:17.11@sha256:67f41722b7a8cbdb868a44a4995c846eddfdc2973bccb291ce937dce88ad5675
# Keep PostgreSQL 17 and Debian trixie: do not change libc/collation families.
RUN apt-get update && apt-get upgrade -y && apt-get clean && rm -rf /var/lib/apt/lists/*
COPY --from=gosu /gosu /usr/local/bin/gosu
COPY --from=gosu /src/LICENSE /usr/local/share/doc/gosu/LICENSE
LABEL com.otziv.gosu.source="https://github.com/tianon/gosu/tree/6456aaa0f3c854d199d0f037f068eb97515b7513" \
      com.otziv.gosu.source-sha256="33d7537d588ea49458b9509bcf4554bdf5ceacc66da71e5caa1058ea3b689c3b"
RUN gosu --version && test "$(gosu nobody id -u)" = 65534 && postgres --version
