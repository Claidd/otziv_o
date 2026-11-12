FROM ghcr.io/claidd/otziv-security@sha256:374430936cad1fcc6ea665ce93aa12314d980f35f623938b4d9c821a720322e5

# Official Alpine 3.24 apache2 2.4.71-r0 (CVE-2026-119618); reject unrelated package/payload changes.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc /usr/lib/php83 /usr/bin/php83 /usr/local/share/otziv /docker-entrypoint.sh /usr/local/bin -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc /usr/lib/php83 /usr/bin/php83 /usr/local/share/otziv /docker-entrypoint.sh /usr/local/bin -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e apache2 | grep -Fx 'apache2-2.4.69-r0'; \
    inventory > /tmp/c53.payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^apache2-[0-9]' > /tmp/c53.packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main apache2=2.4.71-r0; \
    apk --no-network info -v -e apache2 | grep -Fx 'apache2-2.4.71-r0'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^apache2-[0-9]' > /tmp/c53.packages.after; \
    cmp /tmp/c53.packages.before /tmp/c53.packages.after; \
    inventory > /tmp/c53.payload.after; cmp /tmp/c53.payload.before /tmp/c53.payload.after; \
    rm /tmp/c53.payload.before /tmp/c53.payload.after /tmp/c53.packages.before /tmp/c53.packages.after
RUN php -v && apachectl configtest && httpd -v | grep -F 'Apache/2.4.71' && php -r 'require "/var/www/html/vendor/autoload.php"; if (\Composer\InstalledVersions::getPrettyVersion("symfony/expression-language") !== "v5.4.54") exit(2);'

LABEL com.otziv.security.c53="C53: official apache2 security package; accepted application and configuration bytes unchanged"
