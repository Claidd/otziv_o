FROM ghcr.io/claidd/otziv-security@sha256:618901b10b646f87c426a6eacb5117ee0c50e1d555e38fe7999eb24542b364e5

# Refresh only Expat in the already published and proven C27 phpMyAdmin image.
# Keep the application, Apache/PHP runtime and every unrelated package unchanged.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /var/www/html /etc/phpmyadmin /usr/lib/php83 /usr/bin/php83 /etc/php83 \
        /usr/local/etc /docker-entrypoint.sh /usr/local/bin/apache2-foreground /usr/local/bin/php \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /var/www/html /etc/phpmyadmin /usr/lib/php83 /usr/bin/php83 /etc/php83 \
        /usr/local/etc /docker-entrypoint.sh /usr/local/bin/apache2-foreground /usr/local/bin/php \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    inventory > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e libexpat)" > /tmp/packages.before; \
    apk add --no-cache --upgrade libexpat=2.9.0-r0; \
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e libexpat)" > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    apk --no-network info -v -e libexpat | grep -Fx 'libexpat-2.9.0-r0'; \
    inventory > /tmp/payload.after; \
    cmp /tmp/payload.before /tmp/payload.after; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after; \
    apachectl configtest; \
    test "$(php -r 'echo PHP_VERSION;')" = '8.3.35'
