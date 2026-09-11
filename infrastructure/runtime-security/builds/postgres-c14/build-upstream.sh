#!/bin/bash
set -Eeuo pipefail
trap 'rc=$?; if [ "$rc" -ne 0 ]; then find /build/evidence -name "*.log" -exec sh -c '\''echo "$1"; tail -n 25 "$1"'\'' _ {} \;; fi; exit "$rc"' EXIT
export CFLAGS='-O2 -g -fstack-protector-strong -fstack-clash-protection -fcf-protection -fno-omit-frame-pointer -D_FORTIFY_SOURCE=2'
export LDFLAGS='-Wl,-z,relro -Wl,-z,now'
mkdir -p /build/evidence /packages /staged
cd /sources
tar -xf libxml2.tar.xz
tar -xf libxslt.tar.xz
tar -xf postgresql.tar.bz2
tar -xf gzip.tar.xz
cd /sources/libxml2-2.15.4
./configure --prefix=/usr/local --without-python --with-legacy --with-zlib --without-readline > /build/evidence/libxml2-configure.log 2>&1
make -j2 > /build/evidence/libxml2-build.log 2>&1
make -j2 check > /build/evidence/libxml2-check.log 2>&1
make install DESTDIR=/staged/libxml2 > /build/evidence/libxml2-install.log 2>&1
cp -a /staged/libxml2/usr/local/. /usr/local/
ldconfig
export PKG_CONFIG_PATH=/usr/local/lib/pkgconfig
export LD_LIBRARY_PATH=/usr/local/lib
cd /sources/libxslt-1.1.45
./configure --prefix=/usr/local --without-python --with-crypto > /build/evidence/libxslt-configure.log 2>&1
make -j2 > /build/evidence/libxslt-build.log 2>&1
make -j2 check > /build/evidence/libxslt-check.log 2>&1
make install DESTDIR=/staged/libxslt > /build/evidence/libxslt-install.log 2>&1
cp -a /staged/libxslt/usr/local/. /usr/local/
ldconfig
cd /sources/gzip-1.14
python3 /build/gzip-fixes.py > /build/evidence/gzip-patch-regression.log 2>&1
./configure --prefix=/usr/local > /build/evidence/gzip-configure.log 2>&1
make -j2 > /build/evidence/gzip-build.log 2>&1
make -j2 check > /build/evidence/gzip-check.log 2>&1
make install DESTDIR=/staged/gzip > /build/evidence/gzip-install.log 2>&1
cp -a /staged/gzip/usr/local/. /usr/local/
cd /sources/postgresql-17.11
# Omit only optional systemd notification and LLVM JIT. Debian LLVM brings the
# old libxml2 ABI back into the image; no compatibility symlink is introduced.
./configure --prefix=/usr/local/pgsql --enable-nls --enable-debug \
  --with-pam --with-openssl --with-libxml --with-libxslt --with-uuid=e2fs \
  --with-gssapi --with-ldap --with-icu --with-lz4 --with-zstd --with-selinux \
  --with-system-tzdata=/usr/share/zoneinfo --with-pgport=5432 \
  > /build/evidence/postgresql-configure.log 2>&1
make -j2 world-bin > /build/evidence/postgresql-build.log 2>&1
chown -R postgres:postgres /sources/postgresql-17.11
gosu postgres env LD_LIBRARY_PATH=/usr/local/lib make -j2 check > /build/evidence/postgresql-check.log 2>&1
gosu postgres env LD_LIBRARY_PATH=/usr/local/lib make -C contrib/xml2 check > /build/evidence/postgresql-xml2-check.log 2>&1
make install-world-bin DESTDIR=/staged/postgresql > /build/evidence/postgresql-install.log 2>&1
# Preserve the official container's external TCP default for fresh clusters.
# This changes the installed sample only, never an existing data volume.
python3 - <<'PY'
from pathlib import Path
p=Path('/staged/postgresql/usr/local/pgsql/share/postgresql.conf.sample')
s=p.read_text(); old="#listen_addresses = 'localhost'"
assert s.count(old)==1
p.write_text(s.replace(old,"listen_addresses = '*'",1))
PY
cp -a /staged/postgresql/usr/local/pgsql /usr/local/
/usr/local/pgsql/bin/pg_config > /build/evidence/pg-config.txt
/usr/local/pgsql/bin/postgres --version
python3 - <<'PY'
import json, pathlib, subprocess, hashlib, os
items=json.load(open('/build/upstream-components.json'))
for item in items:
    root=pathlib.Path('/staged')/item['name']
    for path in list(root.rglob('*')):
        if path.is_file() and (path.suffix in ['.a','.la'] or '/include/' in str(path) or '/pkgconfig/' in str(path)):
            path.unlink()
    control=root/'DEBIAN'; control.mkdir()
    version=item.get('packageVersion',item['version'])
    (control/'control').write_text(f"Package: {item['package']}\nSource: {item.get('sourcePackage',item['name'])}\nVersion: {version}\nArchitecture: amd64\nMaintainer: Otziv runtime maintainers <runtime@localhost>\nDescription: Verified upstream {item['name']} runtime with recorded container configuration and upstream security patches\nHomepage: {item['url']}\n")
    (control/'md5sums').write_text(''.join(hashlib.md5(p.read_bytes()).hexdigest()+'  '+str(p.relative_to(root))+'\n' for p in sorted(root.rglob('*')) if p.is_file() and not p.is_symlink() and 'DEBIAN' not in p.parts))
    subprocess.run(['dpkg-deb','--root-owner-group','--build',str(root),f"/packages/{item['package']}_{version}_amd64.deb"],check=True,
      env={**os.environ,'DPKG_DEB_THREADS_MAX':'2'})
PY
