FROM ghcr.io/claidd/otziv-security@sha256:24e44775699a8c6b2fc5fa80686a3fda4bc7d1372c12e80992704f43f4f968f9

# Refresh only libX11 in the published C31 phpMyAdmin image. This package is
# required by PHP GD through libXpm; keep the application and other packages intact.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /var/www/html /etc/phpmyadmin /usr/lib/php83 /usr/bin/php83 /etc/php83 \
        /usr/local/etc /docker-entrypoint.sh /usr/local/bin/apache2-foreground /usr/local/bin/php \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /var/www/html /etc/phpmyadmin /usr/lib/php83 /usr/bin/php83 /etc/php83 \
        /usr/local/etc /docker-entrypoint.sh /usr/local/bin/apache2-foreground /usr/local/bin/php \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e libx11 | grep -Fx 'libx11-1.8.13-r0'; \
    inventory > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e libx11)" > /tmp/packages.before; \
    apk add --no-cache --upgrade libx11=1.8.14-r0; \
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e libx11)" > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    apk --no-network info -v -e libx11 | grep -Fx 'libx11-1.8.14-r0'; \
    inventory > /tmp/payload.after; \
    cmp /tmp/payload.before /tmp/payload.after; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after; \
    apachectl configtest; \
    test "$(php -r 'echo PHP_VERSION;')" = '8.3.35'; \
    php -r 'if (!extension_loaded("gd") || !function_exists("imagecreatefromxpm")) exit(1);'
