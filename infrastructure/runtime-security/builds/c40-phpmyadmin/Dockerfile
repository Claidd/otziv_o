FROM ghcr.io/claidd/otziv-security@sha256:858c57795d143518e553c54ab2d76d10650fb3a235c7dd686f5e0383eb72f973

# Preserve C39 PHP8.3.36, its tested GNU iconv extension, application and config.
# The sole changed runtime package is the official Alpine Brotli security fix.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc \
        /usr/lib/php83 /usr/bin/php83 /usr/local/share/otziv /docker-entrypoint.sh \
        /usr/local/bin/apache2-foreground /usr/local/bin/php /usr/local/bin/apachectl /usr/local/bin/apache2ctl \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc \
        /usr/lib/php83 /usr/bin/php83 /usr/local/share/otziv /docker-entrypoint.sh \
        /usr/local/bin/apache2-foreground /usr/local/bin/php /usr/local/bin/apachectl /usr/local/bin/apache2ctl \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e brotli-libs | grep -Fx 'brotli-libs-1.2.0-r1'; \
    inventory > /tmp/payload.before; \
    php -m | LC_ALL=C sort > /tmp/modules.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^brotli-libs-[0-9]' > /tmp/packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main brotli-libs=1.2.1-r0; \
    apk --no-network info -v -e brotli-libs | grep -Fx 'brotli-libs-1.2.1-r0'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^brotli-libs-[0-9]' > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    inventory > /tmp/payload.after; cmp /tmp/payload.before /tmp/payload.after; \
    php -m | LC_ALL=C sort > /tmp/modules.after; cmp /tmp/modules.before /tmp/modules.after; \
    test "$(php -r 'echo PHP_VERSION;')" = '8.3.36'; apachectl configtest; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after /tmp/modules.before /tmp/modules.after
LABEL com.otziv.security.brotli-refresh="C40: official Alpine3.24 brotli-libs1.2.1-r0; C39 PHP/iconv/application/config and unrelated packages unchanged"
