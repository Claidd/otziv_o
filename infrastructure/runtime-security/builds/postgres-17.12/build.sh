#!/bin/bash
set -euo pipefail
export CFLAGS='-O2 -g -fstack-protector-strong -fstack-clash-protection -fcf-protection -fno-omit-frame-pointer -D_FORTIFY_SOURCE=2'
export LDFLAGS='-Wl,-z,relro -Wl,-z,now'
export PKG_CONFIG_PATH=/usr/local/lib/pkgconfig LD_LIBRARY_PATH=/usr/local/lib
tar -xf libxml2.tar.xz
tar -xf libxslt.tar.xz
tar -xf postgresql.tar.bz2
# Generate headers and link against the same upstream XML ABIs as the accepted parent.
(cd libxml2-2.15.4 && ./configure --prefix=/usr/local --without-python --with-legacy --with-zlib --without-readline && make -j4 && make install) > /tmp/xml-build.log 2>&1 || { tail -40 /tmp/xml-build.log; exit 1; }
(cd libxslt-1.1.45 && ./configure --prefix=/usr/local --without-python --with-crypto && make -j4 && make install) > /tmp/xslt-build.log 2>&1 || { tail -40 /tmp/xslt-build.log; exit 1; }
mkdir -p /overlay/usr/local/share/otziv/build
evidence=/overlay/usr/local/share/otziv/build
cd postgresql-17.12
tar -xf ../debian.tar.xz
# Apply the Debian security source series. Retain the parent runtime's socket and
# filesystem layout, built without postgresql-common, and its existing client defaults.
while read -r change; do
    case "$change" in
        50-per-version-dirs.patch|51-default-sockets-in-var.patch|53-pg_service.conf_directory_doc.patch|54-debian-alternatives-for-external-tools.patch) continue ;;
    esac
    patch --batch --fuzz=0 -p1 < "debian/patches/$change"
done < debian/patches/series
./configure --prefix=/usr/local/pgsql --enable-nls --enable-debug --with-pam --with-openssl --with-libxml --with-libxslt --with-uuid=e2fs --with-gssapi --with-ldap --with-icu --with-lz4 --with-zstd --with-selinux --with-system-tzdata=/usr/share/zoneinfo --with-pgport=5432 > "$evidence/postgresql-configure.log"
make -j4 world-bin > "$evidence/postgresql-build.log"
chown -R postgres:postgres .
gosu postgres env LD_LIBRARY_PATH=/usr/local/lib make -j4 check > "$evidence/postgresql-check.log" || { tail -60 "$evidence/postgresql-check.log"; exit 1; }
gosu postgres env LD_LIBRARY_PATH=/usr/local/lib make -C contrib/xml2 check > "$evidence/postgresql-xml2-check.log"
make install-world-bin DESTDIR=/stage > "$evidence/postgresql-install.log"
/stage/usr/local/pgsql/bin/pg_config > "$evidence/pg-config.txt"
python3 /build/package.py
