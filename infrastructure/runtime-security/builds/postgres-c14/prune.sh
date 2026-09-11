#!/bin/sh
set -eu
postgres --version > /tmp/pg.before
locale -a > /tmp/locale.before
find /usr/lib/postgresql/17 /usr/share/postgresql/17 /usr/lib/locale -type f -exec sha256sum {} + | LC_ALL=C sort > /tmp/files.before
ldd /usr/lib/postgresql/17/bin/* /usr/lib/postgresql/17/lib/*.so | sed -E 's/0x[0-9a-f]+/ADDR/g' > /tmp/ldd.before
dpkg-query -W -f='${binary:Package}\t${Version}\n' > /tmp/packages.before
apt-get -s --auto-remove purge gnupg gnupg-l10n gpg gpg-agent gpgconf gpgsm dirmngr > /tmp/purge.plan
awk '/^Purg / {print $2}' /tmp/purge.plan | LC_ALL=C sort > /tmp/planned.txt
cmp /tmp/expected-removals.txt /tmp/planned.txt
apt-get -y --auto-remove purge gnupg gnupg-l10n gpg gpg-agent gpgconf gpgsm dirmngr
dpkg --audit > /tmp/dpkg-audit.txt
test ! -s /tmp/dpkg-audit.txt
dpkg-query -W -f='${binary:Package}\t${Version}\n' > /tmp/packages.after
postgres --version > /tmp/pg.after
locale -a > /tmp/locale.after
find /usr/lib/postgresql/17 /usr/share/postgresql/17 /usr/lib/locale -type f -exec sha256sum {} + | LC_ALL=C sort > /tmp/files.after
ldd /usr/lib/postgresql/17/bin/* /usr/lib/postgresql/17/lib/*.so | sed -E 's/0x[0-9a-f]+/ADDR/g' > /tmp/ldd.after
cmp /tmp/files.before /tmp/files.after
cmp /tmp/ldd.before /tmp/ldd.after
cmp /tmp/pg.before /tmp/pg.after
cmp /tmp/locale.before /tmp/locale.after
if grep 'not found' /tmp/ldd.after; then exit 1; fi
mkdir -p /usr/local/share/otziv/runtime-minimization
cp /tmp/packages.before /tmp/packages.after /tmp/purge.plan /tmp/files.before /tmp/ldd.after /usr/local/share/otziv/runtime-minimization/
rm -f /tmp/*.before /tmp/*.after /tmp/purge.plan /tmp/planned.txt /tmp/expected-removals.txt /tmp/dpkg-audit.txt /tmp/prune.sh
