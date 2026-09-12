#!/bin/bash
set -euo pipefail
export LC_ALL=C SOURCE_DATE_EPOCH=1788134400
mkdir -p /sources/unpacked /stage /build/evidence
tar -xjf /sources/pcre2.tar.bz2 -C /sources/unpacked --strip-components=1
cd /sources/unpacked
./configure --prefix=/usr --libdir=/usr/lib/x86_64-linux-gnu \
  --enable-shared --disable-static --enable-jit --enable-unicode \
  --enable-pcre2-8 --disable-pcre2-16 --disable-pcre2-32 \
  CFLAGS='-O2 -g0 -fstack-protector-strong -D_FORTIFY_SOURCE=3 -ffile-prefix-map=/sources/unpacked=.' \
  LDFLAGS='-Wl,-z,relro,-z,now' > /build/evidence/pcre2-configure.log 2>&1
make -j2 > /build/evidence/pcre2-build.log 2>&1
make check > /build/evidence/pcre2-tests.log 2>&1
make DESTDIR=/stage install > /build/evidence/pcre2-install.log 2>&1
./pcre2test -C > /build/evidence/pcre2-features.txt
python3 /build/package-pcre2.py
ldconfig -r /runtime
chroot /runtime /bin/bash -c 'set -e; export PATH=/usr/local/pgsql/bin:/usr/local/bin:/usr/bin:/bin; printf "pcre2-runtime-check\n" | grep -P "^pcre2-runtime-check$"; postgres --version; locale -a | grep -Fx en_US.utf8'
