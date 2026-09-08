FROM golang:1.27.1-bookworm@sha256:648f440f42a0958804efb24df176f806f9d353b41f1c0627f666428e40310f6b AS gosu
WORKDIR /src
ADD --checksum=sha256:33d7537d588ea49458b9509bcf4554bdf5ceacc66da71e5caa1058ea3b689c3b https://codeload.github.com/tianon/gosu/tar.gz/6456aaa0f3c854d199d0f037f068eb97515b7513 /source.tar.gz
RUN tar -xzf /source.tar.gz --strip-components=1 -C /src
ARG TARGETARCH
ENV CGO_ENABLED=0 GOMAXPROCS=2 GOMEMLIMIT=1GiB
RUN GOARCH="$TARGETARCH" go build -mod=readonly -trimpath -buildvcs=false -ldflags=-w -o /gosu . && go version -m /gosu

# Preserve the official entrypoint, UID, locale archive and Debian runtime
# libraries. Build tools never become part of the final rootfs.
FROM postgres:17.11@sha256:67f41722b7a8cbdb868a44a4995c846eddfdc2973bccb291ce937dce88ad5675 AS build
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends \
    build-essential pkg-config python3 bison flex libreadline-dev zlib1g-dev liblzma-dev \
    libssl-dev libicu-dev liblz4-dev libzstd-dev libpam0g-dev libldap2-dev libkrb5-dev \
    libselinux1-dev libgcrypt20-dev uuid-dev gettext && apt-get clean && rm -rf /var/lib/apt/lists/*
COPY --from=gosu /gosu /usr/local/bin/gosu
COPY --from=gosu /src/LICENSE /usr/local/share/doc/gosu/LICENSE
ADD --checksum=sha256:98087fd181d9070724f3fbc65c7377db03038eb92bd882374daff44940138821 https://download.gnome.org/sources/libxml2/2.15/libxml2-2.15.4.tar.xz /sources/libxml2.tar.xz
ADD --checksum=sha256:9acfe68419c4d06a45c550321b3212762d92f41465062ca4ea19e632ee5d216e https://download.gnome.org/sources/libxslt/1.1/libxslt-1.1.45.tar.xz /sources/libxslt.tar.xz
ADD --checksum=sha256:dd27f2b3c59e73ed14aa3324901242bf69a032a6347805f274e6260322d42979 https://ftp.postgresql.org/pub/source/v17.11/postgresql-17.11.tar.bz2 /sources/postgresql.tar.bz2
ADD --checksum=sha256:01a7b881bd220bfdf615f97b8718f80bdfd3f6add385b993dcf6efd14e8c0ac6 https://deb.debian.org/debian/pool/main/g/gzip/gzip_1.14.orig.tar.xz /sources/gzip.tar.xz
COPY build-upstream.sh upstream-components.json gzip-fixes.py gzip-63dbf6b.patch gzip-e7378c2.patch /build/
RUN /bin/bash /build/build-upstream.sh
COPY assemble-runtime.py /build/
RUN python3 /build/assemble-runtime.py

FROM scratch
COPY --from=build /runtime/ /
ENV LANG=en_US.utf8 PG_MAJOR=17 PG_VERSION=17.11 PGDATA=/var/lib/postgresql/data \
    PATH=/usr/local/pgsql/bin:/usr/local/bin:/usr/bin:/bin
# gosu uses /proc/self/exe, so exercise it inside an actual container namespace.
RUN test "$(gosu nobody id -u)" = 65534 && postgres --version
VOLUME /var/lib/postgresql/data
EXPOSE 5432
STOPSIGNAL SIGINT
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["postgres"]
LABEL com.otziv.reviewed-component="postgres" \
    com.otziv.security.scope="C14 PostgreSQL17.11 Debian trixie/glibc; verified upstream libxml2/libxslt/gzip; explicit runtime closure and package inventory" \
    com.otziv.postgres.optional-interfaces="LLVM JIT and systemd-notify omitted; SQL/XML ICU SSL GSS LDAP PAM SELinux UUID lz4 zstd retained" \
    com.otziv.gosu.source="https://github.com/tianon/gosu/tree/6456aaa0f3c854d199d0f037f068eb97515b7513"
