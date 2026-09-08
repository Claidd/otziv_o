#!/bin/sh
set -eu
trap 'rc=$?; if [ "$rc" -ne 0 ]; then for log in /out/provenance/*.log /out/provenance/compatibility.json; do [ ! -f "$log" ] || tail -n 60 "$log"; done; fi' 0
export LC_ALL=C.UTF-8
mkdir -p /out/provenance
test "$(php-config83 --version)" = 8.3.33
test "$(awk '/^#define PHP_API_VERSION / {print $3}' /usr/include/php83/main/php.h)" = 20230831
test "$(php83 -n -r 'echo PHP_ZTS;')" = 0
test "$(php83 -n -r 'echo PHP_DEBUG;')" = 0
cd /src
tar -xf /tmp/php-8.3.33.tar.xz
cd php-8.3.33/ext/iconv
find . -type f -exec sha256sum '{}' \; | sort > /out/provenance/official-extension-files.sha256
phpize83 > /out/provenance/phpize.log
CFLAGS='-O2 -fstack-protector-strong -fPIC' LDFLAGS='-Wl,-z,relro,-z,now' LIBS='-liconv' \
 ./configure --with-php-config=/usr/bin/php-config83 --with-iconv=/usr > /out/provenance/configure.log
make -j2 > /out/provenance/make.log
sha256sum -c /out/provenance/official-extension-files.sha256 > /out/provenance/unchanged-source.log
cp modules/iconv.so /out/iconv.so
php83 -n -d extension=/out/iconv.so /tmp/iconv-proof.php > /out/provenance/compatibility.json
cd /src/php-8.3.33
TEST_PHP_EXECUTABLE=/usr/bin/php83 REPORT_EXIT_STATUS=1 \
 php83 -n run-tests.php -n -d extension=/out/iconv.so -q ext/iconv/tests > /out/provenance/upstream-tests.log 2>&1
cp LICENSE /out/provenance/PHP-LICENSE
cp /lib/apk/db/installed /out/provenance/build-apk-database
cp /tmp/build-packages/SHA256SUMS /out/provenance/build-apk-payload-sha256.txt
cc --version > /out/provenance/compiler.txt
php-config83 --configure-options > /out/provenance/php-abi-configure-options.txt
printf '%s\n' 'php=8.3.33' 'php-api=20230831' 'zts=0' 'debug=0' 'gnu-libiconv=1.18-r0' \
 'source-sha256=e293ed620cec74651bb4a071317892a478aa6840fab22db45c72d77cd42f9676' \
 'CFLAGS=-O2 -fstack-protector-strong -fPIC' 'LDFLAGS=-Wl,-z,relro,-z,now' 'LIBS=-liconv' \
 'configure=--with-php-config=/usr/bin/php-config83 --with-iconv=/usr' > /out/provenance/build-identity.txt
php83 -n -d extension=/out/iconv.so -r 'echo file_get_contents("/proc/self/maps");' > /out/provenance/loaded-libraries.txt
grep -F '/usr/lib/libiconv.so.2' /out/provenance/loaded-libraries.txt
readelf -d /out/iconv.so > /out/provenance/elf-dynamic.txt
grep -F 'Shared library: [libiconv.so.2]' /out/provenance/elf-dynamic.txt
sha256sum /out/iconv.so > /out/provenance/module.sha256
