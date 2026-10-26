FROM ghcr.io/claidd/otziv-security@sha256:ef39d5374b37296d840c1a4355fc9ecc3e44c55a4ddf958334bf74d8f8bf0599

# Preserve C40 PHP8.3.36, its tested GNU iconv extension, application and config.
# Only the official Alpine curl/libcurl security update may change packages.
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
    apk --no-network info -v -e curl | grep -Fx 'curl-8.22.0-r0'; \
    apk --no-network info -v -e libcurl | grep -Fx 'libcurl-8.22.0-r0'; \
    inventory > /tmp/payload.before; \
    php -m | LC_ALL=C sort > /tmp/modules.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(curl|libcurl)-[0-9]' > /tmp/packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main curl=8.23.0-r0 libcurl=8.23.0-r0; \
    apk --no-network info -v -e curl | grep -Fx 'curl-8.23.0-r0'; \
    apk --no-network info -v -e libcurl | grep -Fx 'libcurl-8.23.0-r0'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(curl|libcurl)-[0-9]' > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    inventory > /tmp/payload.after; cmp /tmp/payload.before /tmp/payload.after; \
    php -m | LC_ALL=C sort > /tmp/modules.after; cmp /tmp/modules.before /tmp/modules.after; \
    apk --no-network info -v -e brotli-libs | grep -Fx 'brotli-libs-1.2.1-r0'; \
    curl --version | grep -F 'curl 8.23.0 '; \
    php -r 'if (curl_version()["version"] !== "8.23.0") exit(1);'; \
    test "$(php -r 'echo PHP_VERSION;')" = '8.3.36'; apachectl configtest; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after /tmp/modules.before /tmp/modules.after
LABEL com.otziv.security.curl-refresh="C41: official Alpine3.24 curl/libcurl8.23.0-r0; C40 PHP/iconv/application/config and all other packages unchanged"
