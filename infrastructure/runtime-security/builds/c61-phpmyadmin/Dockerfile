FROM ghcr.io/claidd/otziv-security@sha256:e6a68833d01e2a161f21565570659067bfd4a90d7af9817bac0eb82d5e6ee03d

# Official signed Alpine 3.24 fixes. Keep application/configuration and every unrelated package.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc /usr/local/share/otziv /docker-entrypoint.sh /usr/local/bin /usr/lib/php83/modules/uploadprogress.so -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc /usr/local/share/otziv /docker-entrypoint.sh /usr/local/bin /usr/lib/php83/modules/uploadprogress.so -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    inventory > /tmp/c61.payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(libxml2|php83|php83-apache2|php83-bcmath|php83-bz2|php83-common|php83-ctype|php83-curl|php83-dom|php83-fileinfo|php83-gd|php83-mbstring|php83-mysqli|php83-mysqlnd|php83-opcache|php83-openssl|php83-pdo|php83-pdo_sqlite|php83-phar|php83-posix|php83-session|php83-simplexml|php83-sodium|php83-sqlite3|php83-tokenizer|php83-xml|php83-xmlreader|php83-xmlwriter|php83-zip)-[0-9]' > /tmp/c61.packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/community \
      libxml2=2.13.9-r3 php83=8.3.37-r0 php83-apache2=8.3.37-r0 php83-bcmath=8.3.37-r0 php83-bz2=8.3.37-r0 php83-common=8.3.37-r0 php83-ctype=8.3.37-r0 php83-curl=8.3.37-r0 php83-dom=8.3.37-r0 php83-fileinfo=8.3.37-r0 php83-gd=8.3.37-r0 php83-mbstring=8.3.37-r0 php83-mysqli=8.3.37-r0 php83-mysqlnd=8.3.37-r0 php83-opcache=8.3.37-r0 php83-openssl=8.3.37-r0 php83-pdo=8.3.37-r0 php83-pdo_sqlite=8.3.37-r0 php83-phar=8.3.37-r0 php83-posix=8.3.37-r0 php83-session=8.3.37-r0 php83-simplexml=8.3.37-r0 php83-sodium=8.3.37-r0 php83-sqlite3=8.3.37-r0 php83-tokenizer=8.3.37-r0 php83-xml=8.3.37-r0 php83-xmlreader=8.3.37-r0 php83-xmlwriter=8.3.37-r0 php83-zip=8.3.37-r0; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(libxml2|php83|php83-apache2|php83-bcmath|php83-bz2|php83-common|php83-ctype|php83-curl|php83-dom|php83-fileinfo|php83-gd|php83-mbstring|php83-mysqli|php83-mysqlnd|php83-opcache|php83-openssl|php83-pdo|php83-pdo_sqlite|php83-phar|php83-posix|php83-session|php83-simplexml|php83-sodium|php83-sqlite3|php83-tokenizer|php83-xml|php83-xmlreader|php83-xmlwriter|php83-zip)-[0-9]' > /tmp/c61.packages.after; \
    cmp /tmp/c61.packages.before /tmp/c61.packages.after; \
    inventory > /tmp/c61.payload.after; cmp /tmp/c61.payload.before /tmp/c61.payload.after; \
    rm /tmp/c61.payload.before /tmp/c61.payload.after /tmp/c61.packages.before /tmp/c61.packages.after
RUN php -v && php -r 'if (PHP_VERSION !== "8.3.37") exit(2);' && apachectl configtest && httpd -v | grep -F 'Apache/2.4.71' && php -r 'require "/var/www/html/vendor/autoload.php"; if (\Composer\InstalledVersions::getPrettyVersion("symfony/expression-language") !== "v5.4.54") exit(2);'
LABEL com.otziv.security.c61="Official libxml2/PHP fixes; configuration and unrelated packages unchanged"
