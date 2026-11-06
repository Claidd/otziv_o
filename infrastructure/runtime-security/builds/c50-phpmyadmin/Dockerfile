FROM ghcr.io/claidd/otziv-security@sha256:2f4863a2cca459beb732b9081588cec9f411ac68e83d2cddf2609b16cd708c27

# Official Alpine 3.24 CVE-2026-107891 update; reject unrelated package/payload changes.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc /usr/lib/php83 /usr/bin/php83 /usr/local/share/otziv /docker-entrypoint.sh /usr/local/bin -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc /usr/lib/php83 /usr/bin/php83 /usr/local/share/otziv /docker-entrypoint.sh /usr/local/bin -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e musl | grep -Fx 'musl-1.2.6-r2'; \
    apk --no-network info -v -e musl-utils | grep -Fx 'musl-utils-1.2.6-r2'; \
    inventory > /tmp/c50.payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(musl|musl-utils)-[0-9]' > /tmp/c50.packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main musl=1.2.6-r3 musl-utils=1.2.6-r3; \
    apk --no-network info -v -e musl | grep -Fx 'musl-1.2.6-r3'; \
    apk --no-network info -v -e musl-utils | grep -Fx 'musl-utils-1.2.6-r3'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(musl|musl-utils)-[0-9]' > /tmp/c50.packages.after; \
    cmp /tmp/c50.packages.before /tmp/c50.packages.after; \
    inventory > /tmp/c50.payload.after; cmp /tmp/c50.payload.before /tmp/c50.payload.after; \
    rm /tmp/c50.payload.before /tmp/c50.payload.after /tmp/c50.packages.before /tmp/c50.packages.after
RUN php -v && apachectl configtest && php -r 'require "/var/www/html/vendor/autoload.php"; if (\Composer\InstalledVersions::getPrettyVersion("symfony/expression-language") !== "v5.4.54") exit(2);'

LABEL com.otziv.security.c50="C50: official musl security package; accepted application and configuration bytes unchanged"
