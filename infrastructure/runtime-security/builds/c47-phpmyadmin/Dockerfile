FROM ghcr.io/claidd/otziv-security@sha256:919ceaa723a3490d75ce1f264b2a852127cb2e7e6aa64c04aa7199a1761d5c47

# Keep the accepted C41 application, server and configuration bytes intact.
# Install only the official Alpine 3.24 libexpat 2.10.0 security update.
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
    apk --no-network info -v -e libexpat | grep -Fx 'libexpat-2.9.0-r0'; \
    inventory > /tmp/payload.before; \
    php -m | LC_ALL=C sort > /tmp/modules.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^libexpat-[0-9]' > /tmp/packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main libexpat=2.10.0-r0; \
    apk --no-network info -v -e libexpat | grep -Fx 'libexpat-2.10.0-r0'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^libexpat-[0-9]' > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    inventory > /tmp/payload.after; cmp /tmp/payload.before /tmp/payload.after; \
    php -m | LC_ALL=C sort > /tmp/modules.after; cmp /tmp/modules.before /tmp/modules.after; \
    test "$(php -r 'echo PHP_VERSION;')" = '8.3.36'; apachectl configtest; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after /tmp/modules.before /tmp/modules.after
LABEL com.otziv.security.expat-refresh="C47: official Alpine3.24 libexpat2.10.0-r0; C41 server/application/config and unrelated packages unchanged"
