FROM ghcr.io/claidd/otziv-security@sha256:e7c5c3b4761c54f4c596e7470c8c901682fe1e017787b6bc2486ffbf1d81fa9c
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
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e apache2)" > /tmp/packages.before; \
    apk add --no-cache --upgrade apache2=2.4.69-r0; \
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e apache2)" > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    apk --no-network info -v -e apache2 | grep -Fx 'apache2-2.4.69-r0'; \
    inventory > /tmp/payload.after; \
    cmp /tmp/payload.before /tmp/payload.after; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after; \
    apachectl configtest; \
    httpd -v | grep -F 'Apache/2.4.69'; \
    test "$(php -r 'echo PHP_VERSION;')" = '8.3.35'
