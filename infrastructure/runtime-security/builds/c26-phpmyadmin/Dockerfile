FROM ghcr.io/claidd/otziv-security@sha256:49a197c95684926f380e2a78614e1260d5889ea973a3cc98911235eacb84df48
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /var/www/html /etc/phpmyadmin /usr/lib/php83 /usr/bin/php83 /usr/sbin/httpd /etc/apache2 /etc/php83 \
        /usr/local/etc /docker-entrypoint.sh /usr/local/bin/apache2-foreground /usr/local/bin/apachectl \
        /usr/local/bin/apache2ctl /usr/local/bin/php /usr/local/bin/apache2 \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /var/www/html /etc/phpmyadmin /usr/lib/php83 /usr/bin/php83 /usr/sbin/httpd /etc/apache2 /etc/php83 \
        /usr/local/etc /docker-entrypoint.sh /usr/local/bin/apache2-foreground /usr/local/bin/apachectl \
        /usr/local/bin/apache2ctl /usr/local/bin/php /usr/local/bin/apache2 \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    inventory > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e pcre2)" > /tmp/packages.before; \
    apk add --no-cache --upgrade pcre2=10.49-r0; \
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e pcre2)" > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    apk --no-network info -v | grep -Fx 'pcre2-10.49-r0'; \
    inventory > /tmp/payload.after; \
    cmp /tmp/payload.before /tmp/payload.after; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after; \
    apachectl configtest; \
    test "$(php -r 'echo PHP_VERSION;')" = '8.3.35'; \
    php -r 'if (!preg_match("/^c26$/", "c26") || !str_starts_with(PCRE_VERSION, "10.49 ")) exit(1);'
