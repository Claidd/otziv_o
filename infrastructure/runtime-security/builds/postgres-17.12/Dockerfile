FROM ghcr.io/claidd/otziv-security@sha256:13d2e1ba01b593f1f284d5d7869a9994f5251f036355e90064406ca4e7d809fc AS parent
FROM postgres:17.11@sha256:67f41722b7a8cbdb868a44a4995c846eddfdc2973bccb291ce937dce88ad5675 AS build
RUN apt-get update && apt-get install -y --no-install-recommends build-essential pkg-config python3 bison flex libreadline-dev zlib1g-dev liblzma-dev libssl-dev libicu-dev liblz4-dev libzstd-dev libpam0g-dev libldap2-dev libkrb5-dev libselinux1-dev libgcrypt20-dev uuid-dev gettext
WORKDIR /build
ADD --checksum=sha256:98087fd181d9070724f3fbc65c7377db03038eb92bd882374daff44940138821 https://download.gnome.org/sources/libxml2/2.15/libxml2-2.15.4.tar.xz libxml2.tar.xz
ADD --checksum=sha256:9acfe68419c4d06a45c550321b3212762d92f41465062ca4ea19e632ee5d216e https://download.gnome.org/sources/libxslt/1.1/libxslt-1.1.45.tar.xz libxslt.tar.xz
ADD --checksum=sha256:9cf136a703d8ee2f83465c43be4e9e8e7930c82b0bfba13be7e2537be769ac55 https://ftp.postgresql.org/pub/source/v17.12/postgresql-17.12.tar.bz2 postgresql.tar.bz2
ADD --checksum=sha256:2e02b8b27ec24abf8ee8ed3395703b02708bdf35284b65b254e84bc27351b5b6 https://deb.debian.org/debian-security/pool/updates/main/p/postgresql-17/postgresql-17_17.12-0%2Bdeb13u1.debian.tar.xz debian.tar.xz
COPY --from=parent /var/lib/dpkg/status /parent/status
COPY --from=parent /var/lib/dpkg/info/otziv-postgresql-17.list /parent/package.list
COPY --from=parent /usr/local/share/otziv/ /parent/inventory/
COPY build.sh package.py ./
RUN --network=none bash build.sh
FROM build AS kerberos
ADD --checksum=sha256:fe90af8441ad9fa9b064b8e53077cefb782a96f8a68276b031dc54af1789ff0b https://web.mit.edu/kerberos/dist/krb5/1.22/krb5-1.22.3.tar.gz /build/kerberos.tar.gz
COPY --from=parent /var/lib/dpkg/info/ /parent/info/
COPY kerberos.py /build/
RUN --network=none tar -xf kerberos.tar.gz && cd krb5-1.22.3/src && ./configure --prefix=/usr --libdir=/usr/lib/x86_64-linux-gnu --sysconfdir=/etc --localstatedir=/var --with-system-et --without-system-verto > /tmp/krb-config.log && make -j4 > /tmp/krb-build.log && make install DESTDIR=/krb > /tmp/krb-install.log && python3 /build/kerberos.py
FROM parent
COPY --from=kerberos /overlay/ /
ENV PG_VERSION=17.12
RUN --network=none test "$(postgres --version)" = 'postgres (PostgreSQL) 17.12' && test "$(pg_config --version)" = 'PostgreSQL 17.12' && ldd /usr/local/pgsql/bin/postgres > /tmp/postgres-libs && ! grep -q 'not found' /tmp/postgres-libs && rm /tmp/postgres-libs
LABEL com.otziv.security.proof="postgres-17.12" \
      com.otziv.security.generation="c54-postgres" \
      com.otziv.security.scope="PostgreSQL 17.12 with Debian security patches and MIT Kerberos 1.22.3 client libraries"
