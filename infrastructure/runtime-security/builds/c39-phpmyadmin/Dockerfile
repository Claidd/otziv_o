FROM ghcr.io/claidd/otziv-security@sha256:82afc14c5009b78c8bb1f4a011199654277edf5d4ff0fa2061851bbb3ca1a6c9 AS application

FROM alpine@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS iconv-build-packages
COPY iconv-build-packages.lock /tmp/build-packages.lock
RUN mkdir /build-packages && apk fetch --no-cache --recursive --output /build-packages $(cat /tmp/build-packages.lock) \
    && cd /build-packages && sha256sum *.apk > SHA256SUMS

FROM alpine@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS iconv-build
COPY --from=iconv-build-packages /build-packages /tmp/build-packages
RUN apk add --no-network --no-cache /tmp/build-packages/*.apk && mkdir /src
ADD --checksum=sha256:dd54693aa334c5c4d72e9aee96b082c2473750a18a6c1d105789c93a3b1692fd https://www.php.net/distributions/php-8.3.36.tar.xz /tmp/php-8.3.36.tar.xz
COPY build-iconv.sh iconv-proof.php /tmp/
RUN sh /tmp/build-iconv.sh

# Upgrade the complete installed PHP family, retaining the application, config,
# entrypoint and every non-PHP runtime package from the activated C32 image.
FROM application
COPY php-packages.lock /tmp/php-packages.lock
COPY unchanged-payload.sh /tmp/unchanged-payload.sh
RUN set -eu; set -o pipefail; \
    sh /tmp/unchanged-payload.sh > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -vE '^php83(-[a-z_0-9]+)?-8[.]3[.]' > /tmp/packages.before; \
    apk --no-network info -v | grep '^php83-' | grep -v '^php83-pecl-' | LC_ALL=C sort > /tmp/php.before; \
    sed 's/=8.3.36-r0$/-8.3.35-r0/' /tmp/php-packages.lock | LC_ALL=C sort > /tmp/php.expected; \
    cmp /tmp/php.before /tmp/php.expected; \
    php -m | LC_ALL=C sort > /tmp/modules.before; \
    apk add --no-cache --upgrade $(cat /tmp/php-packages.lock); \
    apk --no-network info -v | LC_ALL=C sort | grep -vE '^php83(-[a-z_0-9]+)?-8[.]3[.]' > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    apk --no-network info -v | grep '^php83-' | grep -v '^php83-pecl-' | LC_ALL=C sort > /tmp/php.after; \
    sed 's/=/-/' /tmp/php-packages.lock | LC_ALL=C sort > /tmp/php.expected; \
    cmp /tmp/php.after /tmp/php.expected
COPY --from=iconv-build /out/iconv.so /usr/lib/php83/modules/iconv.so
COPY --from=iconv-build /out/provenance /usr/local/share/otziv/iconv-build-c39
COPY iconv-proof.php /tmp/iconv-proof.php
RUN set -eu; set -o pipefail; \
    sh /tmp/unchanged-payload.sh > /tmp/payload.after; \
    cmp /tmp/payload.before /tmp/payload.after; \
    php -m | LC_ALL=C sort > /tmp/modules.after; \
    cmp /tmp/modules.before /tmp/modules.after; \
    apachectl configtest; \
    test "$(php -r 'echo PHP_VERSION;')" = '8.3.36'; \
    php /tmp/iconv-proof.php > /usr/local/share/otziv/iconv-build-c39/runtime-compatibility.json; \
    php -r 'if (!extension_loaded("gd") || !function_exists("imagecreatefromxpm")) exit(1);'; \
    cp /tmp/php-packages.lock /usr/local/share/otziv/php-packages-c39.lock; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after \
       /tmp/php.before /tmp/php.after /tmp/php.expected /tmp/modules.before /tmp/modules.after \
       /tmp/php-packages.lock /tmp/unchanged-payload.sh /tmp/iconv-proof.php
LABEL com.otziv.security.php-refresh="C39: PHP8.3.36-r0; version-matched official iconv with GNU libiconv1.18; C32 application/config/non-PHP runtime preserved"
