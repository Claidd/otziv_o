# syntax=docker/dockerfile:1.7
# Preserve the reviewed PostgreSQL, glibc, locale, entrypoint and runtime closure.
FROM ghcr.io/claidd/otziv-security@sha256:07834abbfd80ed7183afa9096db99afc4d93b99fd51aae726e550d07ade65dbf AS original
FROM postgres:17.11@sha256:67f41722b7a8cbdb868a44a4995c846eddfdc2973bccb291ce937dce88ad5675 AS build
RUN apt-get update && apt-get install -y --no-install-recommends build-essential python3 bzip2 ca-certificates && apt-get clean && rm -rf /var/lib/apt/lists/*
COPY --from=original / /runtime/
ADD --checksum=sha256:b6c68fdf6f3ac31388b50aa89ff0fc49c00c987c16e7b5146491d12003f2c8ed https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.48/pcre2-10.48.tar.bz2 /sources/pcre2.tar.bz2
COPY build-pcre2.sh package-pcre2.py component.json /build/
RUN /bin/bash /build/build-pcre2.sh

FROM scratch
COPY --from=build /runtime/ /
ENV LANG=en_US.utf8 PG_MAJOR=17 PG_VERSION=17.11 PGDATA=/var/lib/postgresql/data \
    PATH=/usr/local/pgsql/bin:/usr/local/bin:/usr/bin:/bin
RUN test "$(gosu nobody id -u)" = 65534 && postgres --version
VOLUME /var/lib/postgresql/data
EXPOSE 5432
STOPSIGNAL SIGINT
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["postgres"]
LABEL com.otziv.reviewed-component="postgres" \
    com.otziv.security.generation="c16-pcre2" \
    com.otziv.security.scope="C16 PCRE2 10.48; unchanged C14 PostgreSQL17.11, glibc, locales and entrypoint" \
    com.otziv.postgres.optional-interfaces="LLVM JIT and systemd-notify omitted; SQL/XML ICU SSL GSS LDAP PAM SELinux UUID lz4 zstd retained" \
    com.otziv.gosu.source="https://github.com/tianon/gosu/tree/6456aaa0f3c854d199d0f037f068eb97515b7513"
